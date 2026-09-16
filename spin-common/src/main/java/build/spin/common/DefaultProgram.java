package build.spin.common;

/*-
 * #%L
 * Spin Common Library
 * %%
 * Copyright (C) 2026 Workday, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import build.base.configuration.Configuration;
import build.base.foundation.Capture;
import build.base.foundation.Introspection;
import build.base.graph.Graph;
import build.base.graph.GraphCycles;
import build.base.telemetry.Activity;
import build.base.telemetry.Meter;
import build.base.telemetry.TelemetryRecorder;
import build.codemodel.dependency.injection.Binder;
import build.codemodel.dependency.injection.ConfigurationResolver;
import build.codemodel.dependency.injection.Context;
import build.codemodel.dependency.injection.InjectionFramework;
import build.codemodel.dependency.injection.Module;
import build.codemodel.dependency.injection.ProvidesResolver;
import build.codemodel.dependency.injection.Resolver;
import build.codemodel.dependency.injection.ValueBinding;
import build.codemodel.jdk.TypeUsages;
import build.spin.AssetCache;
import build.spin.Engine;
import build.spin.Instruction;
import build.spin.Invocable;
import build.spin.Plugin;
import build.spin.Program;
import build.spin.ProgramExecutionException;
import build.spin.Project;
import build.spin.Reference;
import build.spin.SpinURI;
import build.spin.Task;
import build.spin.Workspace;
import build.spin.annotation.PostProcess;
import build.spin.annotation.PreProcess;
import build.spin.common.injection.FromResolver;
import build.spin.common.injection.ProjectResourceResolver;
import build.spin.common.telemetry.TelemetryPublisher;
import build.spin.option.ExecutionSlots;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The default implementation of a {@link Program}.
 *
 * @author brian.oliver
 * @since Nov-2019
 */
public final class DefaultProgram
    implements Program {

    /**
     * The {@link TelemetryRecorder} for the {@link Engine}.
     */
    private final TelemetryRecorder recorder;

    /**
     * The {@link Engine} that created the {@link Program}.
     */
    private final Engine engine;

    /**
     * The {@link Configuration} for the {@link Program}.
     */
    private final Configuration optionsByType;

    /**
     * The {@link DefaultInstruction} {@link Instruction}s by {@link Reference}.
     */
    private final LinkedHashMap<Reference, DefaultInstruction<?>> instructions;

    /**
     * The task dependency graph: edge A → B means "A depends on B".
     */
    private final Graph<Reference> dependencyGraph;

    /**
     * A cycle detected in the task dependency graph, if any.
     * Non-empty means {@link #execute} must throw immediately.
     */
    private final List<Reference> detectedCycle;

    /**
     * The {@link Context} for the {@link Program}.
     */
    private final Context context;

    /**
     * The {@link InjectionFramework} from the {@link Engine}.
     */
    private final InjectionFramework framework;

    /**
     * Bounds how many {@link Task}s actually execute their body concurrently, independent of how many are
     * simultaneously DAG-ready.
     * <p>
     * Tasks are still forked onto a virtual thread as soon as they're ready — that dispatch is cheap — but
     * without this gate, every readiness front in a wide dependency graph would run its full width
     * concurrently. Most tasks here launch a real CPU-bound JDK tool (compile, link, dependency
     * analysis); running more of those at once than there are cores oversubscribes the host, showing
     * up as run-queue depth and context-switch storms well beyond what the machine can actually
     * execute in parallel.
     * <p>
     * Fair, so slots are granted in the order tasks began waiting: under sustained saturation this keeps
     * execution order close to dispatch order rather than letting a task be repeatedly passed over, which
     * keeps the per-task timing telemetry interpretable.
     * <p>
     * The slot count comes from the {@link ExecutionSlots} {@link build.base.configuration.Option}, which
     * defaults to {@link Runtime#availableProcessors()} — a rough proxy, since many tasks spend their held
     * slot largely waiting on a JDK-tool subprocess that is itself multi-core — and can be overridden
     * (via {@code .spin/} configuration or the {@code spin.execution.slots} system property) when a build
     * wants to cap spin's host footprint more tightly than one-slot-per-core.
     */
    private final Semaphore executionSlots;

    /**
     * Constructs a {@link DefaultProgram} for the specified {@link Project} with the provided {@link Configuration}.
     *
     * @param project       the {@link Project}
     * @param optionsByType the {@link Configuration}
     */
    @SuppressWarnings("unchecked")
    public DefaultProgram(final Project project,
                          final Configuration optionsByType) {

        Objects.requireNonNull(project, "The project must not be null");

        this.engine = project.engine();
        this.framework = this.engine.framework();
        this.optionsByType = optionsByType == null ? Configuration.empty() : optionsByType;
        this.instructions = new LinkedHashMap<>();

        this.executionSlots = new Semaphore(this.optionsByType.getOptional(ExecutionSlots.class)
            .orElseGet(ExecutionSlots::autodetect).get(), true);

        // determine the Task.Pattern
        final Task.Pattern pattern = this.optionsByType
            .getOptional(Task.Pattern.class)
            .orElseThrow(() -> new RuntimeException("Failed to define a Task.Pattern"));

        // establish TelemetryRecorder
        final URI uri = SpinURI.create("program", pattern.get());
        this.recorder = new TelemetryPublisher(uri, this.engine::publish);

        this.context = this.framework.newContext(new ProgramModule(this.optionsByType, project.workspace()));
        this.context.addResolver(ConfigurationResolver.of(this.optionsByType));
        this.context.addResolver(this.engine.context().resolver());
        this.context.bind(Program.class).to(this);
        this.context.validate();

        // TODO: prepare the plugins for each of the Projects?

        final Activity inference = this.recorder.commence("Creating Program for Tasks Matching [%s]", pattern.get());

        // the tasks for which we need to determine an Executable
        final Stack<Invocable<?>> stack = new Stack<>();

        // walk the Project tree to determine the Tasks to be executed (based on their name/regex)
        project.walk(prj -> prj.invocables()
            .filter(invocable -> invocable.matches(pattern))
            .forEach(stack::push));

        // we keep track of the Projects and the Projects that their Tasks depend upon (not including themselves)
        // (this allows us to create a Project-based Dependency Graph)
        final HashMap<Project, HashSet<Project>> projects = new HashMap<>();

        // we've not yet included the @Automatic Tasks
        boolean includedAutomaticTasks = false;

        this.recorder.info("Found %d Invocable(s)", stack.size());

        final Activity creatingInstructions = this.recorder
            .commence("Creating Instructions for [%s]", project.name());

        final AtomicInteger nextExecutableIdentity = new AtomicInteger(1);

        while (!stack.isEmpty()) {
            final Invocable<?> taskInvocable = stack.peek();
            final Reference taskReference = taskInvocable.getReference();
            final Project taskProject = taskInvocable.getProject();
            final Plugin taskPlugin = taskInvocable.getPlugin();

            final int size = stack.size();

            // add the current task as a known tasks (ignored if already known)
            this.instructions.computeIfAbsent(taskReference, __ -> {
                // include the Project in the Projects being tracked
                projects.computeIfAbsent(taskProject, existing -> new HashSet<>());

                // establish a TelemetryRecorder for the publishing Task specific Telemetry
                final URI taskURI = taskInvocable.getURI();
                final TelemetryPublisher publisher = new TelemetryPublisher(taskURI, this.engine::publish);

                final Context taskContext = this.framework.newContext(new TaskContextModule(taskProject, publisher));
                taskContext.addResolver(ProvidesResolver.of(taskPlugin, this.framework));

                // allow the Plugin to contribute Binder bindings (e.g. multibindings via bindSet)
                // before the Task is created, so they're visible to its injection points
                taskPlugin.contributeBindings(taskContext);

                // add a Resolver for Iterable of Plugins implementing the specified interface
                taskContext.addResolver(injectionPoint -> {
                    if (TypeUsages.getThreadContextClass(injectionPoint.typeUsage())
                        .map(Iterable.class::equals).orElse(false)) {

                        return TypeUsages.getFirstTypeParameterClass(injectionPoint.typeUsage())
                            .map(c -> {
                                final Iterable<Plugin> iterable = () -> taskProject
                                    .plugins()
                                    .filter(c::isInstance)
                                    .iterator();
                                return ValueBinding.of(injectionPoint, iterable);
                            });
                    }
                    return Optional.empty();
                });

                // add a Resolver to allow Resolving of the Executables for the Program
                taskContext.addResolver(injectionPoint -> {
                    if (TypeUsages.getThreadContextClass(injectionPoint.typeUsage())
                        .map(Stream.class::equals).orElse(false)) {
                        // TODO: we're assuming it's a Stream<Instruction>
                        return Optional.of(ValueBinding.of(injectionPoint, this.instructions.values().stream()));
                    }
                    return Optional.empty();
                });

                // bind the interfaces implemented by the Plugin
                taskContext.bind(taskPlugin).asAllInterfaces();

                // allow project resources that are resolvers to resolve
                taskContext.addResolver(dependency -> taskProject.resources()
                    .filter(Resolver.class::isInstance)
                    .map(r -> (Resolver<Object>) r)
                    .flatMap(resolver -> resolver.resolve(dependency).stream())
                    .findFirst());

                // allow the Program to resolve InjectionPoints
                taskContext.addResolver(this.context.resolver());

                // allow project resources to be resolved and injected — registered last since it always
                // resolves an Optional<X>-typed dependency, so other resolvers must get first refusal
                taskContext.addResolver(new ProjectResourceResolver(taskProject));

                this.recorder.diagnostic("Creating Instruction [%s]", taskInvocable);

                // create the Executable Instruction
                final DefaultInstruction<?> instruction = new DefaultInstruction<>(
                    nextExecutableIdentity.getAndIncrement(),
                    taskInvocable,
                    publisher,
                    taskContext);

                // queue dependencies of the current task (that aren't already known) so we can create
                // Executables for them.

                // we don't create Executables for codependencies as they'll be executed as part of an Executable
                // we do however include the dependencies of the codependencies
                // (ie: implied transitive dependencies)

                // NOTE: we deliberately do NOT include Tasks that are only related via @Before/@After - per their
                // contract, such Tasks "will only be executed if required", unlike @PostProcess/@PreProcess
                // codependencies, which "will always be executed with their codependent Task". Only genuine
                // dependencies (@From, Task#dependencies()) and codependencies are pulled in here;
                // instruction.requiredDependencies() deliberately excludes the ordering-only @Before/@After
                // relationships that instruction.dependencies() (used later, for graph edges) still includes.
                Stream.concat(
                        instruction.codependencies().flatMap(Invocable::dependencies),
                        instruction.requiredDependencies())
                    .filter(r -> !this.instructions.containsKey(r))
                    .peek(r -> {
                        //include the dependency for the project (when it's not itself)
                        if (taskProject != r.project()) {
                            projects.get(taskProject).add(r.project());
                        }
                    })
                    .forEach(r -> r.project().getInvocable(r).ifPresent(stack::push));

                return instruction;
            });

            // remove the current task when all of its dependencies (if any) are processed (ie: known)
            if (stack.size() == size) {
                stack.pop();
            }

            // when the Stack is empty, introduce the automatic Tasks that haven't been included yet
            if (stack.isEmpty() && !includedAutomaticTasks) {
                project.walk(prj -> prj.invocables()
                    .filter(definition -> definition.isAutomatic() && !definition.isCodependency())
                    .filter(definition -> !this.instructions.containsKey(definition.getReference()))
                    .forEach(stack::push));

                includedAutomaticTasks = true;
            }
        }

        creatingInstructions.complete();

        // build the dependency graph: edge A → B means "A depends on B"
        //
        // a codependency (@PreProcess/@PostProcess) never gets its own Instruction / graph node - it's
        // executed inline as part of its owning Task (see runTask) - but its own @From dependencies are
        // real data dependencies of that inline execution, so they must be wired in as edges from the
        // owning Instruction too. Without this, a codependency's dependency is merely included somewhere
        // in the Program with no ordering tie to the owner, letting the owner (and its inline codependency)
        // be dispatched concurrently with, rather than after, that dependency.
        final Graph.Builder<Reference> graphBuilder = Graph.directed();
        this.instructions.keySet().forEach(graphBuilder::addVertex);
        this.instructions.values().forEach(instruction ->
            Stream.concat(
                    instruction.dependencies(),
                    instruction.codependencies().flatMap(Invocable::dependencies))
                .filter(this.instructions::containsKey)
                .forEach(dep -> graphBuilder.addEdge(instruction.getReference(), dep)));
        final Graph<Reference> dependencyGraph = graphBuilder.build();

        this.dependencyGraph = dependencyGraph;
        this.detectedCycle = GraphCycles.findCycle(dependencyGraph).orElse(List.of());

        inference.complete();

        this.recorder.diagnostic("Enumerating %d unordered Instruction(s)", this.instructions.size());

        this.instructions.values()
            .forEach(instruction -> {
                this.recorder.diagnostic("Instruction #%d: %s", instruction.getIdentity(), instruction);

                // group same-task cross-project sibling dependencies (eg: forcing every workspace
                // sibling's own Compile task) onto a single line instead of one repetitive line per
                // project naming the identical task over and over
                final LinkedHashMap<String, List<String>> dependenciesByTask = new LinkedHashMap<>();
                instruction.dependencies().forEach(reference ->
                    dependenciesByTask
                        .computeIfAbsent(reference.taskDisplayName(), __ -> new ArrayList<>())
                        .add(reference.project().name()));

                dependenciesByTask.forEach((task, projectNames) -> {
                    if (projectNames.size() == 1) {
                        this.recorder.diagnostic("   requires %s/%s", projectNames.get(0), task);
                    } else {
                        this.recorder.diagnostic("   requires %s in %s", task, projectNames);
                    }
                });

                instruction.codependencies()
                    .filter(definition ->
                        Introspection.hasDeclaredAnnotation(definition.getTaskClass(), PreProcess.class))
                    .forEach(definition ->
                        this.recorder.diagnostic("   pre-processed by %s", definition));

                instruction.codependencies()
                    .filter(definition ->
                        Introspection.hasDeclaredAnnotation(definition.getTaskClass(), PostProcess.class))
                    .forEach(definition ->
                        this.recorder.diagnostic("   post-processed by %s", definition));
            });

        if (projects.values().stream().anyMatch(deps -> !deps.isEmpty())) {
            this.recorder.diagnostic("Project-level Dependencies:");

            projects.forEach((prj, deps) -> {
                if (!deps.isEmpty()) {
                    this.recorder.diagnostic("Project %s", prj.name());
                    deps.forEach(dep -> this.recorder.diagnostic("   requires %s", dep.name()));
                }
            });
        }
    }

    record ProgramModule(Configuration options, Workspace workspace) implements Module {

        @Override
        public void configure(final Binder binder) {
            binder.bind(Configuration.class).to(this.options);
            binder.bind(Workspace.class).to(this.workspace);
        }
    }

    record TaskContextModule(Project project, TelemetryPublisher recorder) implements Module {

        @Override
        public void configure(final Binder binder) {
            binder.bind(Workspace.class).to(this.project.workspace());
            binder.bind(Project.class).to(this.project);
            binder.bind(Path.class).to(this.project.path());
            binder.bind(TelemetryRecorder.class).to(this.recorder);
        }
    }

    @Override
    public AssetCache execute(final AssetCache cache) throws ProgramExecutionException {

        if (!this.detectedCycle.isEmpty()) {
            throw new ProgramExecutionException(this, this.detectedCycle.get(0),
                "Cyclic task dependency detected: " + this.detectedCycle);
        }

        final Meter execution = this.recorder.commence(this.instructions.size(), "Executing Program Instructions");

        final AssetCache localCache = DefaultAssetCache.create();
        final AssetCache executionCache = NearAssetCache.of(localCache, cache);

        // pending[ref] = number of direct dependencies not yet completed
        final var pending = new ConcurrentHashMap<Reference, AtomicInteger>();
        this.instructions.keySet().forEach(ref ->
            pending.put(ref, new AtomicInteger(this.dependencyGraph.successors(ref).size())));

        final Queue<ProgramExecutionException> failures = new ConcurrentLinkedQueue<>();

        // guards against a task being dispatched more than once when multiple dependents
        // complete concurrently and both decrement pending to zero for the same reference
        final var dispatched = ConcurrentHashMap.<Reference>newKeySet();

        try (var scope = StructuredTaskScope.open()) {
            // seed with tasks that have no dependencies. These are all logically concurrent
            // starting points, so they're forked unconditionally rather than gated on
            // failures.isEmpty() — gating here raced against the tasks' own forked threads:
            // a root task with no work to do (an immediate failure) could complete and record
            // its failure before this sequential loop reached a later root, silently skipping
            // that root's fork entirely instead of leaving it in flight. The fail-fast gate
            // (stop starting new tasks once a failure is known) still applies to dependents
            // that become ready later, in runTask below.
            this.instructions.keySet().stream()
                .filter(ref -> pending.get(ref).get() == 0)
                .forEach(ref -> {
                    if (dispatched.add(ref)) {
                        scope.fork(() -> runTask(ref, pending, dispatched, executionCache, execution, failures));
                    }
                });

            scope.join();
        } catch (final StructuredTaskScope.FailedException e) {
            // runTask no longer propagates failures to the scope; this branch guards against
            // unexpected internal errors only
            throw new ProgramExecutionException(this, this.instructions.keySet().iterator().next(),
                "Unexpected failure during task execution", e.getCause());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Program execution interrupted", e);
        }

        if (!failures.isEmpty()) {
            final ProgramExecutionException first = failures.poll();
            failures.forEach(first::addSuppressed);
            throw first;
        }

        execution.complete();
        return localCache;
    }

    private static String extractOutput(final Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof ProcessFailedException p && !p.output().isEmpty()) {
                return p.output();
            }
        }
        return "";
    }

    private Void runTask(final Reference reference,
                         final ConcurrentHashMap<Reference, AtomicInteger> pending,
                         final Set<Reference> dispatched,
                         final AssetCache executionCache,
                         final Meter execution,
                         final Queue<ProgramExecutionException> failures) {

        if (!executionCache.contains(reference)) {
            final DefaultInstruction<?> instruction = this.instructions.get(reference);

            // acquire an execution slot before running the task body. slot-wait time is queueing, not
            // execution; folding it into the "Executing" Activity would hide it from the telemetry used
            // to tell a graph-blocked task from a genuinely slow one. the fast path — a slot is free, the
            // common case at low graph width — records nothing; only a task that actually has to wait
            // gets an "Awaiting execution slot" Activity, so a slot-starved build stays visible without
            // a zero-duration Activity on every task.
            //
            // the zero-timeout tryAcquire (rather than the no-arg one) honors the semaphore's fair
            // ordering: it takes a permit only when one is free AND no task is already queued ahead of
            // this one, so the fast path can't barge past a task parked in "Awaiting execution slot".
            final boolean tookSlotImmediately;
            try {
                tookSlotImmediately = this.executionSlots.tryAcquire(0L, TimeUnit.NANOSECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return null; // the scope is being torn down; leave failure reporting to the interrupter
            }
            final Optional<Activity> awaitingSlot;
            if (tookSlotImmediately) {
                awaitingSlot = Optional.empty();
            } else {
                final Activity activity = instruction.getRecorder().commence("Awaiting execution slot");
                try {
                    this.executionSlots.acquire();
                } catch (final InterruptedException e) {
                    activity.completeExceptionally(e);
                    Thread.currentThread().interrupt();
                    return null; // the scope is being torn down; leave failure reporting to the interrupter
                }
                awaitingSlot = Optional.of(activity);
            }

            // a slot is held from here on; the finally releases it on every exit.
            //
            // the release is scoped to the task body only — deliberately not a method-wide finally. once
            // executeInstruction returns, this thread goes on to fork ready dependents into a nested scope
            // and block in join() until they finish. holding the slot across that wait would let a full
            // readiness front of parents sit on every slot while their children can't get one: deadlock.
            try {
                awaitingSlot.ifPresent(Activity::complete);
                if (!executeInstruction(reference, instruction, executionCache, execution, failures)) {
                    return null; // dependents are not fired when a task fails
                }
            } catch (final RuntimeException e) {
                // executeInstruction records its own task failures without throwing; anything reaching
                // here is from completing the "Awaiting execution slot" Activity. record it as a failure
                // rather than letting it unwind into the nested scope, whose catch discards
                // FailedException — which would silently strand this task's dependents.
                failures.add(new ProgramExecutionException(
                    this, reference, "Failed to execute " + instruction.getInvocable(), e));
                return null;
            } finally {
                this.executionSlots.release();
            }
        }

        // fire any dependents whose last dependency just completed;
        // each task owns a fresh nested scope — fork() requires the calling thread to be the scope owner
        final var readyDependents = this.dependencyGraph.predecessors(reference).stream()
            .filter(this.instructions::containsKey)
            .filter(dependent -> pending.get(dependent).decrementAndGet() == 0)
            .toList();

        if (!readyDependents.isEmpty() && failures.isEmpty()) {
            try (var nestedScope = StructuredTaskScope.open()) {
                readyDependents.stream()
                    .filter(_ -> failures.isEmpty())
                    .filter(dispatched::add)
                    .forEach(dep -> nestedScope.fork(() ->
                        runTask(dep, pending, dispatched, executionCache, execution, failures)));
                nestedScope.join();
            } catch (final StructuredTaskScope.FailedException ignored) {
                // runTask records failures without throwing; this branch is unreachable in normal operation
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return null;
    }

    /**
     * Runs a single {@link Instruction}'s body: its {@link PreProcess} codependencies, the task itself,
     * result capture and {@link PostProcess} codependencies, then commits the result to
     * {@code executionCache}. The caller holds an execution slot for the duration of this call.
     *
     * @return {@code true} if the task completed; {@code false} if it failed — in which case the failure
     *     has been added to {@code failures} and the caller must not fire the task's dependents
     */
    @SuppressWarnings("unchecked")
    private boolean executeInstruction(final Reference reference,
                                       final DefaultInstruction<?> instruction,
                                       final AssetCache executionCache,
                                       final Meter execution,
                                       final Queue<ProgramExecutionException> failures) {

        final Invocable<?> invocable = instruction.getInvocable();
        final Task<?> task = instruction.getTask();

        final Context executionContext = this.framework.newContext();
        executionContext.addResolver(new FromResolver(this.recorder, instruction, executionCache));
        executionContext.addResolver(instruction.getContext().resolver());

        // the Instruction's own recorder URI (spin-task://workspace/project/task-name) already
        // identifies the Project and Task; no need to repeat that (or the redundant Invocable
        // Class) in the message itself
        final Activity activity = instruction.getRecorder().commence("Executing");

        try {
            instruction.codependencies()
                .filter(codependency -> codependency.getTaskClass().isAnnotationPresent(PreProcess.class))
                .forEach(codependency ->
                    instruction.codependencyTask(codependency)
                        .execute(codependency, executionContext, this.framework));

            final Object initialResult = task.execute(invocable, executionContext, this.framework);

            // create a Capture for the Result to allow injection and re-definition by PostProcessors
            final Capture<Object> capture = Capture.ofNullable(initialResult);

            // allow PostProcessors to inject the current task's result before it's committed to the cache:
            //   @From(CurrentTask.class) Capture<T> — mutable; PostProcessor can change the result
            //   @From(CurrentTask.class) T          — read-only view of the initial result
            executionContext.addResolver(dependency ->
                FromResolver.taskClass(dependency)
                    .filter(c -> c.isAssignableFrom(task.getClass()))
                    .flatMap(__ -> TypeUsages.getThreadContextClass(dependency.typeUsage()))
                    .flatMap(requiredClass -> {
                        if (Capture.class.equals(requiredClass)) {
                            return TypeUsages.getFirstTypeParameterClass(dependency.typeUsage())
                                .flatMap(t -> invocable.getTaskResultClass().filter(t::isAssignableFrom))
                                .map(__ -> ValueBinding.of(dependency, (Object) capture));
                        }
                        if (initialResult != null && requiredClass.isInstance(initialResult)) {
                            return Optional.of(ValueBinding.of(dependency, initialResult));
                        }
                        return Optional.empty();
                    }));

            instruction.codependencies()
                .filter(codependency -> codependency.getTaskClass().isAnnotationPresent(PostProcess.class))
                .forEach(codependency ->
                    instruction.codependencyTask(codependency)
                        .execute(codependency, executionContext, this.framework));

            final Object taskResult = capture.isPresent() ? capture.get() : null;
            activity.complete(taskResult);
            execution.progress();

            executionCache.putIfAbsent(taskResult == null
                ? VoidAsset.create(invocable)
                : DefaultAsset.create((Invocable<Object>) invocable, taskResult));
            return true;
        } catch (final Exception e) {
            activity.completeExceptionally(e);
            final String output = extractOutput(e);
            failures.add(new ProgramExecutionException(
                this, reference,
                output.isEmpty() ? "Failed to execute " + invocable
                                 : "Failed to execute " + invocable + "\n" + output,
                ProcessFailedException.unwrap(e)));
            return false;
        }
    }
}
