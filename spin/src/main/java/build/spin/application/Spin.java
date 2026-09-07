package build.spin.application;

/*-
 * #%L
 * Spin Command Line Application
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

import build.base.commandline.Command;
import build.base.commandline.CommandLineParser;
import build.base.configuration.ConfigurationBuilder;
import build.base.foundation.Introspection;
import build.base.option.JDKVersion;
import build.base.option.WorkingDirectory;
import build.base.table.Table;
import build.base.telemetry.Telemetry;
import build.spin.AssetCache;
import build.spin.BackgroundProcessor;
import build.spin.Daemon;
import build.spin.Engine;
import build.spin.Invocable;
import build.spin.Program;
import build.spin.ProgramExecutionException;
import build.spin.Project;
import build.spin.Task;
import build.spin.Workspace;
import build.spin.annotation.Category;
import build.spin.annotation.Description;
import build.spin.common.DefaultAssetCache;
import build.spin.engine.DefaultEngine;
import build.spin.module.checkstyle.CheckstylePlugin;
import build.spin.module.clean.CleanPlugin;
import build.spin.module.jar.JarPlugin;
import build.spin.module.java.Java25CompilerPlugin;
import build.spin.module.junit.Java25JUnitPlugin;
import build.spin.option.EngineVersion;
import build.spin.option.ExecutionSlots;
import build.spin.option.JlinkTargets;
import build.spin.option.NetworkAccess;
import build.spin.option.OperatingSystem;
import build.spin.option.ReuseExternalBuildOutput;
import build.spin.option.Root;
import build.spin.option.ServerMode;
import build.spin.option.ServerPort;
import build.spin.option.Verbose;
import jakarta.inject.Named;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class Spin {

    /**
     * Formats a {@link build.base.telemetry.Telemetry#instant()} for the console: {@code Telemetry}'s
     * own {@code toString()} (base.build) never prints one, so correlating interleaved output from
     * concurrently-executing tasks -- e.g. "was this task actually graph-blocked, or just slow to get
     * scheduled" -- otherwise requires reconstructing wall-clock order from relative durations by hand.
     */
    private static final DateTimeFormatter TELEMETRY_TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private Spin() {
    }

    /**
     * Prints a "[spin]" console message prefixed with the current time, formatted identically to
     * {@link Telemetry} events, so pre-{@link Engine} bootstrap/discovery output (which predates the
     * {@link Engine}'s own {@link Telemetry} stream) can still be correlated against it.
     */
    private static void log(final String format, final Object... args) {
        System.err.print(TELEMETRY_TIMESTAMP_FORMAT.format(Instant.now()) + " [spin] " + String.format(format, args));
    }

    static void main(final String[] args) {

        final ParseResult parsed = buildParser();
        final ConfigurationBuilder options = parsed.parser().parse(args);

        if (options.isPresent(EngineVersion.class)) {
            System.out.println(options.get(EngineVersion.class).get());
            System.exit(0);
        }

        printBanner();

        final Engine engine = createEngine(options, parsed.parser(), args);
        final Discovery discovery = discover(engine);

        if (engine.options().get(ServerMode.class) == ServerMode.ENABLED) {
            runServerMode(engine, discovery.workspace());
        } else {
            executeTasks(engine, discovery.workspace(), discovery.project(), parsed.tasks());
        }
    }

    // ---------------------------------------------

    private static ParseResult buildParser() {
        final LinkedHashSet<String> tasks = new LinkedHashSet<>();
        final List<Class<?>> commandClasses = List.of(
            CleanPlugin.RemoveBuildPath.class,
            Java25CompilerPlugin.Compile.class,
            Java25JUnitPlugin.Compile.class,
            Java25JUnitPlugin.Test.class,
            Java25CompilerPlugin.JavaDoc.class,
            CheckstylePlugin.Checkstyle.class,
            Java25CompilerPlugin.JavaLinker.class,
            Java25CompilerPlugin.JavaDependencyAnalysis.class
        );

        final LinkedHashSet<String> registeredTaskNames = new LinkedHashSet<>();
        commandClasses.forEach(cls -> registeredTaskNames.add(taskName(cls)));

        final LinkedHashMap<String, String> aliases = deriveAliases(
            List.of(
                CleanPlugin.class,
                Java25CompilerPlugin.class,
                Java25JUnitPlugin.class,
                CheckstylePlugin.class,
                JarPlugin.class
            ),
            registeredTaskNames);

        final var commandBuilder = Command.create("spin")
            .argument("<task>...")
            .helpHandler(help -> {
                System.out.println(help);
                System.exit(0);
            })
            .commandsSectionName("Tasks:")
            .categoriesSectionName("Aliases:")
            .positionalArgument(tasks::add)
            .option(List.of("--working-dir", "-w"), WorkingDirectory.class, "of", "Working directory (default: current directory)", String.class)
            .envVar("SPIN_WORKING_DIR", "--working-dir")
            .option(List.of("--root", "-r"), Root.class, "of",
                "Additional physical root directory to federate into the Workspace (may be repeated)", String.class);

        commandClasses.forEach(cls -> commandBuilder.command(taskName(cls), taskDescription(cls)));

        final CommandLineParser parser = commandBuilder
            .option(EngineVersion.class)
            .option(NetworkAccess.class)
            .option(Verbose.class)
            .option(ServerMode.class)
            .option(ServerPort.class)
            .option(JlinkTargets.class)
            .option(ExecutionSlots.class)
            .option(ReuseExternalBuildOutput.class)
            .build();

        aliases.forEach(parser::registerCategory);

        return new ParseResult(parser, tasks);
    }

    private static void printBanner() {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(Spin.class.getResourceAsStream("/banner.txt")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.err.println(line);
            }
        } catch (final Exception e) {
            System.err.println("[spin] Failed to read banner.txt");
            e.printStackTrace(System.err);
        }
    }

    private static Engine createEngine(final ConfigurationBuilder options,
                                       final CommandLineParser parser,
                                       final String[] args) {

        options.compute(JDKVersion.class, existing -> existing != null ? existing : JDKVersion.current());
        options.compute(OperatingSystem.class, existing -> existing != null ? existing : OperatingSystem.detect());
        options.compute(EngineVersion.class, existing -> existing != null ? existing : EngineVersion.autodetect());
        log("Engine Version: %s\n", options.get(EngineVersion.class));
        log("Operating System: %s\n", options.get(OperatingSystem.class));
        log("Java Version: %s\n", JDKVersion.current());
        log("Server Port: %s\n", options.get(ServerPort.class));
        System.err.println();

        final FileSystem fileSystem = FileSystems.getDefault();
        final Path userPath = fileSystem.getPath(System.getProperty("user.dir"));
        log("Current Folder: %s\n", userPath);

        Optional.ofNullable(options.getWithoutDefault(WorkingDirectory.class))
            .map(directory -> userPath.resolve(fileSystem.getPath(directory.get())))
            .map(path -> WorkingDirectory.of(path.normalize().toString()))
            .ifPresent(options::add);

        final Table table = Table.create();
        options.stream().forEach(option -> table.addRow(option.getClass().getSimpleName(), option.toString()));
        log("Bootstrap Options:\n%s\n", table);

        return new DefaultEngine(
            Thread.currentThread().getContextClassLoader(),
            fileSystem,
            options.build(),
            Optional.of(parser),
            Optional.ofNullable(args),
            Optional.of(observable -> observable.subscribe(event -> {
                final Telemetry telemetry = (Telemetry) event;
                System.err.printf("%s %s\n", TELEMETRY_TIMESTAMP_FORMAT.format(telemetry.instant()), telemetry);
            })));
    }

    private record ParseResult(CommandLineParser parser, LinkedHashSet<String> tasks) {
    }

    private record Discovery(Workspace workspace, Project project) {
    }


    private static Discovery discover(final Engine engine) {

        final FileSystem fileSystem = FileSystems.getDefault();
        final Path userPath = fileSystem.getPath("").toAbsolutePath();

        final Path path = userPath.resolve(engine.options().get(WorkingDirectory.class).get());

        final List<Path> additionalRoots = engine.options().stream(Root.class)
            .map(root -> root.path(fileSystem))
            .map(userPath::resolve)
            .toList();

        final Workspace workspace;

        if (additionalRoots.isEmpty()) {
            log("Discovering Workspace for Project in [%s]\n", path);

            workspace = engine.createWorkspace(path)
                .orElseThrow(() -> new RuntimeException("Failed to discover workspace containing " + path));
        } else {
            final List<Path> roots = Stream.concat(Stream.of(path), additionalRoots.stream()).toList();

            log("Discovering federated Workspace for Roots %s\n", roots);

            workspace = engine.createWorkspace(roots)
                .orElseThrow(() -> new RuntimeException("Failed to discover federated workspace for roots " + roots));
        }

        final Project project = workspace.getProject(path)
            .orElseThrow(() -> new IllegalStateException(
                "No project found in workspace [" + workspace.path() + "] at path [" + path + "]"));

        log("Detected Project [%s] at [%s]\n", project.name(), project.path());
        log("(Within Workspace [%s] at [%s])\n", workspace.name(), workspace.path());

        final StringBuilder builder = new StringBuilder(4096);
        workspace.treeify(builder, "", "", p -> p.name() + (p == project ? " *" : ""));
        log("Workspace Structure:\n%s\n", builder);

        final var availableTasks = workspace.stream()
            .flatMap(Project::invocables)
            .map(Invocable::getTaskName)
            .distinct()
            .sorted()
            .toList();
        log("Available Tasks: %s\n", availableTasks);

        return new Discovery(workspace, project);
    }

    private static void runServerMode(final Engine engine, final Workspace workspace) {

        log("Starting in Server Mode...\n");

        final LinkedHashSet<BackgroundProcessor> processors = new LinkedHashSet<>();
        engine.services(BackgroundProcessor.class).forEach(processors::add);
        workspace.stream()
            .forEach(prj -> prj.resources()
                .filter(Daemon.class::isInstance)
                .map(Daemon.class::cast)
                .forEach(processors::add));

        processors.forEach(BackgroundProcessor::onInitialize);

        final LinkedHashMap<CompletableFuture<Integer>, BackgroundProcessor> futures = new LinkedHashMap<>();
        final CompletableFuture<Integer> terminationFuture = new CompletableFuture<>();

        final Consumer<? super BackgroundProcessor> start = processor -> {
            final var processorName = Introspection.describe(processor.getClass()).replace('$', '.');
            log("Starting: [%s]\n", processorName);

            final var exceptional = processor.start();

            if (exceptional.isEmpty()) {
                // didn't start the processor... that's ok!
            } else if (exceptional.isException()) {
                log("Starting Failed (exceptionally): [%s]\n", processorName);
                exceptional.exception().get().printStackTrace(System.err);
            } else {
                final var future = exceptional.orElseThrow(
                    () -> new IllegalStateException("Unexpected empty Exceptional for processor: " + processorName));

                futures.put(future, processor);

                if (!future.isDone()) {
                    log("Started: [%s]\n", processorName);
                }

                future.whenComplete((statusCode, throwable) -> {
                    if (throwable == null) {
                        log("Terminated: [%s] (%d)\n", processorName, statusCode);
                        terminationFuture.complete(statusCode);
                    } else {
                        log("Terminated (exceptionally): [%s]\n", processorName);
                        throwable.printStackTrace(System.err);
                        terminationFuture.completeExceptionally(throwable);
                    }
                });
            }
        };

        processors.forEach(start);

        if (futures.isEmpty()) {
            log("No Background Processors (Servers or Daemons) Discovered or Started.\n");
            System.exit(-1);
        }

        terminationFuture.whenComplete((statusCode, throwable) -> futures.keySet().forEach(future -> future.cancel(true)));
        terminationFuture.join();

        try {
            workspace.close();
            engine.close();
            System.exit(terminationFuture.get());
        } catch (final Exception e) {
            log("Unexpected Spin Termination Failure\n");
            e.printStackTrace(System.err);
            System.exit(-1);
        }
    }

    @SuppressWarnings("unchecked")
    private static void executeTasks(final Engine engine,
                                     final Workspace workspace,
                                     final Project project,
                                     final LinkedHashSet<String> tasks) {

        if (tasks.isEmpty()) {
            log("No tasks specified.\n");
            workspace.close();
            System.exit(0);
        }

        final AssetCache shared = DefaultAssetCache.create();

        tasks.forEach(task -> {
            final Task.Pattern pattern = Task.Pattern.of(task);
            try {
                final Program program = engine.createProgram(project, pattern);
                final AssetCache cache = program.execute(shared);

                cache.invocables()
                    .filter(Invocable::isCacheable)
                    .map(Invocable.class::cast)
                    .forEach(invocable -> cache.get(invocable.getReference())
                        .ifPresent(result -> shared.putIfAbsent(invocable, result)));
            } catch (final ProgramExecutionException e) {
                log("Program Execution Failed\n");
                e.printStackTrace(System.err);
                System.exit(-1);
            }
        });

        workspace.close();
        log("Program Execution Completed\n");
        System.exit(0);
    }

    // ---------------------------------------------

    private static String taskName(final Class<?> taskClass) {
        final Named named = taskClass.getAnnotation(Named.class);
        return named != null ? named.value() : taskClass.getSimpleName();
    }

    private static String taskDescription(final Class<?> taskClass) {
        return Optional.ofNullable(taskClass.getAnnotation(Description.class))
            .map(Description::value)
            .orElse("");
    }

    /**
     * Derives category aliases by scanning inner classes of the given plugin classes.
     * <p>
     * Walks the full type hierarchy (superclasses and interfaces) of each inner class to collect
     * {@link Category} annotations. Returns a map of category name → comma-joined task names for
     * categories whose name is not already a registered task name.
     */
    private static LinkedHashMap<String, String> deriveAliases(final List<Class<?>> pluginClasses,
                                                               final LinkedHashSet<String> registeredTaskNames) {

        final TreeMap<String, TreeSet<String>> categoryTasks = new TreeMap<>();

        pluginClasses.stream()
            .flatMap(plugin -> Arrays.stream(plugin.getDeclaredClasses()))
            .forEach(inner -> {
                final String name = taskName(inner);
                collectCategoriesFrom(inner)
                    .distinct()
                    .forEach(cat -> categoryTasks.computeIfAbsent(cat, k -> new TreeSet<>()).add(name));
            });

        final LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
        categoryTasks.forEach((category, taskNames) -> {
            if (!registeredTaskNames.contains(category)) {
                aliases.put(category, String.join(", ", taskNames));
            }
        });
        return aliases;
    }

    /**
     * Recursively collects {@link Category} names from a class and its full type hierarchy,
     * including superclasses and interfaces.
     */
    private static Stream<String> collectCategoriesFrom(final Class<?> cls) {
        if (cls == null || cls == Object.class) {
            return Stream.empty();
        }
        final Stream<String> own = Arrays.stream(cls.getDeclaredAnnotationsByType(Category.class))
            .map(Category::value);
        final Stream<String> fromInterfaces = Arrays.stream(cls.getInterfaces())
            .flatMap(Spin::collectCategoriesFrom);
        final Stream<String> fromSuperclass = collectCategoriesFrom(cls.getSuperclass());
        return Stream.concat(own, Stream.concat(fromInterfaces, fromSuperclass));
    }
}
