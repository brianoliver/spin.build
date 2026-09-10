package build.spin.module.java;

/*-
 * #%L
 * Spin Java Module
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

import build.base.io.PathSet;
import build.base.io.PathSetBuilder;
import build.base.option.JDKVersion;
import build.base.telemetry.TelemetryRecorder;
import build.base.version.Version;
import build.codemodel.foundation.descriptor.RequiresModuleDescriptor;
import build.codemodel.jdk.descriptor.JDKModuleDescriptor;
import build.percolate.core.ModuleGraphClassifier;
import build.spin.Invocable;
import build.spin.Project;
import build.spin.Reference;
import build.spin.Task;
import build.spin.common.task.BuildOutputLocations;
import build.spin.common.task.SourcePathKind;
import build.spin.module.modulesystem.Artifact;
import build.spin.module.modulesystem.CompilationResolution;
import build.spin.module.modulesystem.LocalMavenRepository;
import build.spin.module.modulesystem.ModuleCatalog;
import build.spin.module.modulesystem.ModuleReference;
import build.spin.module.modulesystem.ModuleVersioning;
import build.spin.module.modulesystem.pom.Pom;
import build.spin.module.modulesystem.pom.PomReader;
import build.spin.option.BuildDirectoryName;
import build.spin.option.ReuseExternalBuildOutput;
import build.spin.option.TargetDirectoryName;
import jakarta.inject.Inject;

import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An abstract {@link Task} that resolves the source-graph dependency closure for a
 * {@link JavaPlugin} {@link Project}, partitioning all candidates into a
 * {@link CompilationResolution} (module-path vs classpath).
 *
 * <p>The algorithm has three steps:
 * <ol>
 *   <li>Walk the workspace-sibling transitive closure starting from the injected
 *       the injected {@link JDKModuleDescriptor}'s {@code requiresClauses()}.</li>
 *   <li>Resolve each external require transitively via the {@link Artifact.Resolver}
 *       ({@link Artifact.Resolver#resolveTransitive}), so artifact-graph cycles are handled by the
 *       resolver rather than a hand-rolled BFS.</li>
 *   <li>Classify all candidates via {@link ModuleGraphClassifier#classify} with the three-tier
 *       split-package policy (required-module preference → proper-over-automatic → demote-all).</li>
 * </ol>
 *
 * <p>Concrete subclasses are empty inner classes of the enclosing plugin, which is what
 * causes the DI framework to bind {@link JDKModuleDescriptor} to the correct plugin-scoped
 * descriptor (main vs test). The main-vs-test behavioral differences below are driven entirely
 * by what the enclosing plugin provides via DI — {@link #scope} (via {@code @Provides}) and
 * {@link #additionalInfrastructureArtifacts} (via a {@code Set<Artifact>} multibinding) — rather
 * than by further subclassing.
 */
public abstract class AbstractDetectResolution
    implements Task<CompilationResolution> {

    @Inject
    private Project project;

    @Inject
    private JDKModuleDescriptor moduleDescriptor;

    @Inject
    private ModuleCatalog catalog;

    @Inject
    private ModuleVersioning versioning;

    @Inject
    private Artifact.Resolver resolver;

    @Inject
    private BuildDirectoryName buildDirectoryName;

    @Inject
    private TargetDirectoryName target;

    @Inject
    private TelemetryRecorder recorder;

    @Inject
    private LocalMavenRepository localMavenRepository;

    /**
     * Whether already-built Maven/Gradle output is trusted as equivalent to spin's own {@code .build/}
     * output -- see {@link ReuseExternalBuildOutput}. Disabled by default, so this resolution and the
     * sibling-ordering decision in {@link #dependencies()} only ever recognize spin's own prior output.
     */
    @Inject
    private ReuseExternalBuildOutput reuseExternalBuildOutput;

    /**
     * Which {@link SourcePathKind} this resolution is for — {@link SourcePathKind#MAIN} or
     * {@link SourcePathKind#TEST} — provided by the enclosing plugin ({@code AbstractJavaPlugin}
     * vs {@code AbstractJUnitPlugin}). Gates the TEST-only sibling-main-output inclusion in
     * {@link #create()}.
     */
    @Inject
    private SourcePathKind scope;

    /**
     * The {@link JDKVersion} of the enclosing plugin — used, for TEST-scope resolutions only, to
     * find the sibling {@link JavaCompilerPlugin} of the same major version whose compiled MAIN
     * output should be added as a candidate.
     */
    @Inject
    private JDKVersion javaVersion;

    /**
     * Additional infrastructure {@link Artifact}s contributed by the enclosing plugin via a
     * {@code Set<Artifact>} multibinding (see {@code Plugin#contributeBindings}) — always resolved
     * transitively and included as classification candidates, regardless of whether the project
     * explicitly declares them as dependencies. Empty for MAIN scope; for TEST scope this is where
     * build-tool runner JARs (e.g. the JUnit Platform ConsoleLauncher) that spin provides itself,
     * rather than expecting the project to declare them, land.
     */
    @Inject
    private Set<Artifact> additionalInfrastructureArtifacts;

    /**
     * Resolves the compilation dependency closure and classifies all candidates.
     *
     * @return the {@link CompilationResolution}
     */
    public CompilationResolution create() {

        // Step 1 — Walk the workspace-sibling transitive closure.
        final List<Path> siblingCandidates = new ArrayList<>();
        final LinkedHashMap<String, RequiresModuleDescriptor> externalRequires = new LinkedHashMap<>();
        final Set<String> visited = new HashSet<>();

        // seed the frontier from additional paths first — TEST scope also sees this project's own
        // compiled MAIN output (e.g. so JUnit test sources can see the project's own main sources),
        // when a same-major-version compiler plugin has run for this project. This is deliberately a
        // best-effort disk check, not an ordering guarantee -- dependencies() below does NOT force the
        // matching-major-version Compile task, so this can race it (e.g. when this task is requested
        // in isolation, or scheduled before Compile finishes) and miss output that hasn't been written
        // yet. Forcing that edge here would serialize this task's own (potentially slow) dependency
        // resolution behind Compile's (potentially slow) javac invocation for every project in the
        // workspace, destroying real parallelism between the two. Instead, JUnitPlugin's Compile and
        // Test tasks -- which already force-order against their own project's main Compile task via
        // their own dependencies() overrides -- separately and reliably inject that same main output
        // themselves (see Java25JUnitPlugin.Compile#compile / Test#test), so correctness there never
        // depends on winning this race.
        if (this.scope == SourcePathKind.TEST) {
            final boolean hasMatchingCompilerPlugin = this.project.plugins(JavaCompilerPlugin.class)
                .anyMatch(p -> p.getJavaVersion().major() == this.javaVersion.major());
            if (hasMatchingCompilerPlugin) {
                resolveCompiledOutput(this.project.path(), this.buildDirectoryName.get(), this.target.get(),
                        SourcePathKind.MAIN, this.reuseExternalBuildOutput)
                    .ifPresent(siblingCandidates::add);
            }
        }

        // seed the frontier from the direct requires of the injected module descriptor
        final List<RequiresModuleDescriptor> frontier = new ArrayList<>();
        this.moduleDescriptor.requiresClauses()
            .filter(r -> !JavaPlatform.isJavaPlatformModule(r.requiresModuleName().toString()))
            .forEach(frontier::add);

        while (!frontier.isEmpty()) {
            final RequiresModuleDescriptor requires = frontier.remove(0);
            final String name = requires.requiresModuleName().toString();

            if (JavaPlatform.isJavaPlatformModule(name) || !visited.add(name)) {
                continue;
            }

            // look for a workspace sibling whose main JavaCompilerPlugin descriptor matches
            final Optional<Project> sibling = siblingProviding(this.project, name);

            if (sibling.isPresent()) {
                final Optional<Path> siblingClasses = resolveCompiledOutput(sibling.get().path(),
                    this.buildDirectoryName.get(), this.target.get(), SourcePathKind.MAIN,
                    this.reuseExternalBuildOutput);
                siblingClasses.ifPresent(siblingCandidates::add);

                // enqueue the sibling's own direct requires onto the frontier
                sibling.get().plugins(JavaCompilerPlugin.class)
                    .findFirst()
                    .ifPresent(plugin -> plugin.getModuleDescriptor().requiresClauses()
                        .filter(r -> !JavaPlatform.isJavaPlatformModule(r.requiresModuleName().toString()))
                        .filter(r -> !visited.contains(r.requiresModuleName().toString()))
                        .forEach(frontier::add));
            }
            else {
                // external require — deduplicated by module name via LinkedHashMap
                externalRequires.putIfAbsent(name, requires);
            }
        }

        // Step 2 — Resolve external requires via the Artifact.Resolver (handles artifact-graph cycles internally).
        final List<Path> externalCandidates = new ArrayList<>();

        // <exclusions> the project's own pom.xml declares on a direct dependency edge, keyed by that
        // dependency's "groupId:artifactId". resolveTransitive walks the resolved artifact's own pom
        // and would otherwise pull an excluded jar down that subtree — passing the edge's exclusions
        // in lets PomResolver's own BFS exclusion-inheritance drop it, exactly as Maven does.
        final Map<String, Set<String>> exclusionsByCoordinate = projectDependencyExclusions(
            this.project.path(), this.localMavenRepository.path(), this.recorder);

        for (final RequiresModuleDescriptor r : externalRequires.values()) {
            final Optional<Artifact> artifact = resolveExternalArtifact(
                r, this.project.name(), this.versioning, this.catalog, this.recorder);

            if (artifact.isEmpty()) {
                continue;
            }

            final Set<String> edgeExclusions = exclusionsByCoordinate.getOrDefault(
                artifact.get().groupId() + ":" + artifact.get().artifactId(), Set.of());
            final var resolved = this.resolver.resolveTransitive(artifact.get(), edgeExclusions);
            if (resolved.isException()) {
                resolved.exception().ifPresent(e -> this.recorder.error(
                    "Failed to resolve transitive dependencies for [%s]: %s",
                    artifact.get(), e.getMessage()));
            }
            else {
                resolved.ifPresent(externalCandidates::addAll);
            }
        }

        // Step 2b — Resolve additional infrastructure artifacts contributed by the enclosing plugin.
        this.additionalInfrastructureArtifacts.forEach(artifact -> {
            final var resolved = this.resolver.resolveTransitive(artifact);
            if (resolved.isException()) {
                resolved.exception().ifPresent(e -> this.recorder.error(
                    "Failed to resolve infrastructure artifact [%s]: %s",
                    artifact, e.getMessage()));
            }
            else {
                resolved.ifPresent(externalCandidates::addAll);
            }
        });

        // Step 2c — Correct candidates whose resolved version diverges from a project-wide pin.
        // Each top-level `requires` in Step 2 is resolved independently via the resolver, using
        // whatever version that require's own pom pins transitively (e.g. a Helidon dependency
        // transitively pulls in the older protobuf-java/grpc versions pinned by Helidon's own BOM)
        // — even when this project's pom.xml pins a newer version directly, if that artifact is only
        // ever reached transitively (never a direct `requires`), there's no resolver call scoped to
        // this project's own pom to apply that pin. `versioning` already has the correct, workspace-wide
        // answer (it's populated from every pom.xml and already used to correct `requires`-clause
        // versions for jlink/jdeps); reuse it here to re-resolve any candidate that disagrees.
        final List<Path> versionCorrectedCandidates =
            correctPinnedVersions(externalCandidates, this.versioning, this.catalog, this.resolver, this.recorder);

        // Step 2d — Dedupe external candidates by Maven coordinate, keeping the highest version.
        // Each top-level `requires` in Step 2 is resolved independently via the resolver, so two
        // different requires can transitively pull in different versions of the same artifact
        // with no cross-call reconciliation (e.g. this project directly requires a newer
        // graphql-java/grpc/protobuf, while a Helidon dependency transitively pulls in the older
        // versions pinned by Helidon's own BOM). Without this, both versions land on the
        // classpath/module-path together and javac non-deterministically picks the wrong one.
        final List<Path> dedupedExternalCandidates =
            dedupeByMavenCoordinate(versionCorrectedCandidates, this.recorder);

        // Step 3 — Classify all candidates via ModuleGraphClassifier.
        final List<Path> candidates = new ArrayList<>();
        candidates.addAll(siblingCandidates);
        candidates.addAll(dedupedExternalCandidates);

        final Set<String> directRequireNames = this.moduleDescriptor.requiresClauses()
            .map(r -> r.requiresModuleName().toString())
            .filter(n -> !JavaPlatform.isJavaPlatformModule(n))
            .collect(Collectors.toCollection(LinkedHashSet::new));

        final ModuleGraphClassifier.Classification classification = ModuleGraphClassifier.classify(
            candidates,
            directRequireNames,
            msg -> this.recorder.diagnostic("[classify] %s", msg));

        return new CompilationResolution(classification.modulePath(), classification.classPath());
    }

    /**
     * The {@code <exclusions>} the {@code pom.xml} under {@code projectPath} declares on each direct
     * {@code <dependency>}, keyed by that dependency's {@code "groupId:artifactId"}. Empty when the
     * project has no pom, its pom can't be read, or it declares no exclusions. Only the already-parsed
     * {@code Dependency} exclusion sets are collected here — enforcement stays entirely in
     * {@code PomResolver}, which these are threaded into via
     * {@link Artifact.Resolver#resolveTransitive(Artifact, Set)}.
     *
     * @param projectPath the project root
     * @param localRepository the local Maven repository, for the pom's own parent/BOM resolution
     * @param recorder the {@link TelemetryRecorder} for diagnostics
     *
     * @return the exclusion sets, keyed by {@code "groupId:artifactId"}
     */
    // Visible for testing.
    static Map<String, Set<String>> projectDependencyExclusions(final Path projectPath,
                                                                final Path localRepository,
                                                                final TelemetryRecorder recorder) {
        final Path pom = projectPath.resolve("pom.xml");
        if (!Files.exists(pom)) {
            return Map.of();
        }
        try {
            return new PomReader(localRepository, recorder).read(pom)
                .map(AbstractDetectResolution::edgeExclusions)
                .orElseGet(Map::of);
        } catch (final Exception e) {
            recorder.diagnostic("Failed to read <exclusions> from [%s]: %s", pom, e.getMessage());
            return Map.of();
        }
    }

    /**
     * The non-empty {@code <exclusions>} sets declared on {@code pom}'s direct {@code <dependency>}
     * entries, keyed by each dependency's {@code "groupId:artifactId"}. Dependencies without
     * exclusions are omitted, so the result is empty when the pom declares none.
     */
    private static Map<String, Set<String>> edgeExclusions(final Pom pom) {
        final Map<String, Set<String>> byCoordinate = new LinkedHashMap<>();
        pom.dependencies().stream()
            .filter(dependency -> !dependency.exclusions().isEmpty())
            .forEach(dependency -> byCoordinate.put(
                dependency.groupId() + ":" + dependency.artifactId(),
                dependency.exclusions()));
        return byCoordinate;
    }

    /**
     * Resolves a single external {@code requires} clause to the {@link Artifact} that should be
     * fetched for it, or {@link Optional#empty()} if it can't be resolved — logging the reason at
     * whichever level reflects how actionable it is.
     *
     * <p>Most misses here are one of several naming-convention candidates that
     * {@code PomBasedTestModuleDescriptor}/{@code PomBasedTestModuleCatalog} synthesize per dependency
     * (see {@code MavenModuleNaming#deriveNames}) — only one of which is ever the module's real name,
     * so the rest are expected to miss on every dependency, every project. Logging that at {@code warn}
     * buries the rare real problem (an actually-unresolvable require, or a stale version pin) under
     * this constant, harmless noise, so those misses log at {@code diagnostic} instead. The one
     * exception is a module name the {@link ModuleCatalog} does recognize but at a version that
     * doesn't match what was requested — usually a stale {@code version.properties} entry, and worth a
     * human's attention — which still logs at {@code warn}.
     *
     * @param r the external {@code requires} clause to resolve
     * @param projectName the name of the {@link Project} declaring the require, for diagnostics
     * @param versioning the workspace-wide {@link ModuleVersioning}
     * @param catalog the {@link ModuleCatalog}
     * @param recorder the {@link TelemetryRecorder} for diagnostics
     *
     * @return the {@link Optional} {@link Artifact} to resolve for this require
     */
    // Visible for testing.
    static Optional<Artifact> resolveExternalArtifact(final RequiresModuleDescriptor r,
                                                       final String projectName,
                                                       final ModuleVersioning versioning,
                                                       final ModuleCatalog catalog,
                                                       final TelemetryRecorder recorder) {

        final String moduleName = r.requiresModuleName().toString();
        final Optional<Version> requiredVersion =
            RequiredVersionResolution.resolve(r, moduleName, projectName, versioning, recorder);

        if (requiredVersion.isEmpty()) {
            recorder.diagnostic(
                "Cannot determine version for external require [%s] in [%s] — skipping",
                moduleName, projectName);
            return Optional.empty();
        }

        final ModuleReference moduleReference = ModuleReference.of(moduleName, requiredVersion.get());
        final Optional<Artifact> artifact = catalog.getArtifact(moduleReference, Optional.of(recorder));

        if (artifact.isEmpty()) {
            final List<String> knownVersions = catalog.constraints(moduleName)
                .map(constraint -> constraint.range().toString())
                .toList();
            if (knownVersions.isEmpty()) {
                recorder.diagnostic("Module [%s] not found in ModuleCatalog — skipping", moduleName);
            }
            else {
                recorder.warn(
                    "Module [%s] requested at version [%s] but the ModuleCatalog only has %s — skipping",
                    moduleName, requiredVersion.get(), knownVersions);
            }
        }

        return artifact;
    }

    /**
     * Finds the workspace sibling {@link Project} — one with its own {@link JavaCompilerPlugin} — whose
     * main module name matches {@code moduleName}, e.g. to resolve a {@code requires} clause to the
     * sibling {@link Project} that provides it.
     *
     * @param project    the {@link Project} whose {@link Project#workspace()} to search
     * @param moduleName the required module name to match
     * @return the {@link Optional} sibling {@link Project} providing {@code moduleName}
     */
    public static Optional<Project> siblingProviding(final Project project, final String moduleName) {
        return project.workspace()
            .stream()
            .filter(prj -> prj.plugins(JavaCompilerPlugin.class)
                .findFirst()
                .map(JavaCompilerPlugin::getModuleDescriptor)
                .map(d -> moduleName.equals(d.moduleName().toString()))
                .orElse(false))
            .findFirst();
    }

    // Protected (not private) since this is also used for the TEST-scope sibling-main-output lookup
    // above, in create() -- reusing this same multi-build-tool lookup for a project's own main output
    // instead of hardcoding spin's own .build/main/<target> convention, which resolves to nothing for
    // a project built via Maven/Gradle without ever having been built by spin directly.
    //
    // Picks the freshest existing candidate by mtime rather than a fixed spin > Maven > Gradle
    // preference order: a project once built by spin directly and since migrated to Maven (or vice
    // versa) can have a stale output directory from the old build tool still sitting on disk
    // alongside the current one, and a fixed preference order would silently keep reading the stale
    // one forever.
    protected static Optional<Path> resolveCompiledOutput(final Path projectPath,
                                                           final String buildDirectoryName,
                                                           final String targetDirectoryName,
                                                           final SourcePathKind scope,
                                                           final ReuseExternalBuildOutput reuseExternalBuildOutput) {
        final boolean isTest = scope == SourcePathKind.TEST;
        final boolean reuseExternal = reuseExternalBuildOutput == ReuseExternalBuildOutput.ENABLED;
        final List<Path> candidates = Stream.of(
                BuildOutputLocations.spin(projectPath, buildDirectoryName, targetDirectoryName, scope),
                reuseExternal
                    ? BuildOutputLocations.maven(projectPath, isTest ? "test-classes" : "classes")
                    : Optional.<Path>empty(),
                reuseExternal
                    ? BuildOutputLocations.gradle(projectPath, isTest ? "classes/java/test" : "classes/java/main")
                    : Optional.<Path>empty())
            .flatMap(Optional::stream)
            // a candidate directory can exist but still lack any compiled output -- e.g.
            // CleanPlugin$CreateBuildPath creates <buildDir>/main/target up front as a prerequisite for
            // several tasks, and CopyResources (a @PreProcess prerequisite of Compile) unconditionally
            // writes resource files into this exact same directory before Compile produces a single
            // .class file. Treating either of those as "already built" would let a directory with
            // nothing compiled into it yet -- non-empty, but only from a mkdir or a resource copy -- win
            // the freshest-mtime tie-break below against an older but fully-populated candidate (e.g.
            // Maven's target/classes from an earlier reactor build) whenever that sibling's own Compile
            // task happens to be running concurrently and unordered relative to this check (as it
            // legitimately can be -- see siblingCompileDependencies), handing back a module-path entry
            // that the requiring module then fails to find classes in.
            .filter(path -> containsCompiledClasses(path, false))
            .toList();

        // Only walk candidate trees to compare mtimes when there's an actual ambiguity to resolve —
        // the overwhelmingly common case is exactly one build tool's output existing at all, where
        // there's nothing to compare and the walk would be pure overhead.
        return switch (candidates.size()) {
            case 0 -> Optional.empty();
            case 1 -> Optional.of(candidates.get(0));
            default -> candidates.stream().max(Comparator.comparing(BuildOutputLocations::latestModifiedTime));
        };
    }

    /**
     * Determines whether {@code directory} contains at least one compiled {@code .class} file
     * anywhere beneath it (except under {@code META-INF/versions/}, which a different multi-release
     * variant of the same project may have already populated on its own). Used by both
     * {@link AbstractCompile} and {@code AbstractResourcePlugin.CopyResources} to decide whether a
     * {@link #resolveCompiledOutput} candidate is genuinely already-built output, rather than just a
     * non-empty directory -- {@code CopyResources} writes into this exact same conventional directory
     * independently of {@link AbstractCompile}, so a directory that only has resources in it
     * (compilation hasn't happened yet) is non-empty but contains no compiled output at all.
     *
     * @param directory the candidate compiled-output directory
     * @return {@code true} if a {@code .class} file exists anywhere beneath {@code directory}, outside
     *         {@code META-INF/versions/}
     */
    static boolean containsCompiledClasses(final Path directory) {
        return containsCompiledClasses(directory, true);
    }

    /**
     * As {@link #containsCompiledClasses(Path)}, but callers already scoped to a specific
     * multi-release variant's own {@code META-INF/versions/N} sub-directory (or the classpath/module
     * candidate needs no such carve-out, e.g. {@code AbstractResourcePlugin.CopyResources}) can allow
     * a match under {@code META-INF/versions/} too.
     *
     * @param directory             the candidate compiled-output directory
     * @param excludeVersionsSubdir {@code true} to ignore {@code .class} files under
     *                              {@code META-INF/versions/} -- e.g. the default-variant, top-level
     *                              check, where a sibling non-default variant's partial write there
     *                              must not be mistaken for this variant's own output
     * @return {@code true} if a {@code .class} file exists anywhere beneath {@code directory},
     *         subject to the {@code META-INF/versions/} exclusion when requested
     */
    static boolean containsCompiledClasses(final Path directory, final boolean excludeVersionsSubdir) {
        final Path versionsDir = directory.resolve("META-INF").resolve("versions");
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk
                .filter(p -> !excludeVersionsSubdir || !p.startsWith(versionsDir))
                .anyMatch(p -> p.getFileName().toString().endsWith(".class"));
        } catch (final IOException e) {
            return false;
        }
    }

    /**
     * Determines whether {@code outputDirectory} was produced no earlier than every file reachable
     * from {@code inputPaths} was last modified, i.e. whether it's safe to reuse as already-built
     * output instead of regenerating it. Shared by {@link AbstractCompile} ({@code inputPaths} is
     * individual {@code .java} source files) and {@code AbstractResourcePlugin.CopyResources}
     * ({@code inputPaths} is resource root directories, e.g. {@code src/main/resources}) so both make
     * the same kind of freshness decision about the same candidate directory. Entries in
     * {@code inputPaths} that are themselves directories are walked, since a directory's own mtime
     * doesn't change when a file nested inside it is edited.
     *
     * @param outputDirectory the candidate already-built output directory
     * @param inputPaths      the files (or root directories of files) that would otherwise be (re)generated
     * @return {@code true} if {@code outputDirectory} is at least as new as every file reachable from {@code inputPaths}
     */
    static boolean isUpToDate(final Path outputDirectory, final PathSet inputPaths) {
        final FileTime outputTime = latestClassFileModifiedTime(outputDirectory);
        return inputPaths.stream().allMatch(path -> isUpToDate(path, outputTime));
    }

    /**
     * Returns the most recent last-modified time of any {@code .class} file anywhere under
     * {@code directory}, falling back to epoch if none exist.
     *
     * <p>Deliberately narrower than {@link BuildOutputLocations#latestModifiedTime}, which takes the
     * newest file of any kind: {@code CopyResources} is a {@code @PreProcess} prerequisite of
     * {@code Compile} and writes resource files into this exact same directory whenever it doesn't
     * skip itself (see {@code AbstractResourcePlugin.CopyResources#doCopy}). Including those resource
     * mtimes here would let a resource file {@code CopyResources} just touched mask genuinely stale
     * {@code .class} files sitting right next to it, making a stale compile look up to date and never
     * re-invoking {@code javac}.
     *
     * @param directory the candidate already-built output directory
     * @return the latest {@link FileTime} among {@code .class} files under {@code directory}
     */
    private static FileTime latestClassFileModifiedTime(final Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk
                .filter(p -> p.getFileName().toString().endsWith(".class"))
                .map(p -> {
                    try {
                        return Files.getLastModifiedTime(p);
                    } catch (final IOException e) {
                        return FileTime.fromMillis(0);
                    }
                })
                .max(Comparator.naturalOrder())
                .orElse(FileTime.fromMillis(0));
        } catch (final IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    private static boolean isUpToDate(final Path path, final FileTime outputTime) {
        if (Files.isDirectory(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
                return walk.filter(Files::isRegularFile).allMatch(p -> isFileUpToDate(p, outputTime));
            } catch (final IOException e) {
                // can't establish freshness -- don't risk serving stale output
                return false;
            }
        }
        return isFileUpToDate(path, outputTime);
    }

    private static boolean isFileUpToDate(final Path file, final FileTime outputTime) {
        try {
            return Files.getLastModifiedTime(file).compareTo(outputTime) <= 0;
        } catch (final IOException e) {
            // can't establish freshness -- don't risk serving stale output
            return false;
        }
    }

    /**
     * Re-resolves any candidate {@link Path} whose actual (on-disk) version diverges from the
     * project-wide pin recorded in {@code versioning}, replacing it with the {@link Path}s resolved
     * for the pinned version instead.
     *
     * <p>A candidate's module name is derived directly from the jar/directory via {@link ModuleFinder}
     * — the same JDK-native mechanism {@link ModuleGraphClassifier} uses — so this works whether the
     * candidate is a proper module, carries an {@code Automatic-Module-Name}, or falls back to a
     * filename-derived automatic module name. Candidates with no version pin, an unparseable on-disk
     * version, or an already-matching version are returned unchanged.
     *
     * <p>Correction runs to a fixed point: paths pulled in by re-resolving a mismatched candidate are
     * themselves checked against their own pin (e.g. re-resolving a mismatched Helidon-transitive
     * {@code grpc-core} may pull in an old {@code protobuf-java} that is itself pinned elsewhere in the
     * workspace). Each mismatched module name is corrected at most once per call — once a module has
     * been re-resolved (or kept on-disk as a fallback), later mismatched occurrences of it are dropped
     * rather than passed through at their stale on-disk version, since that stale candidate would
     * otherwise beat the correction in the later coordinate-dedupe step whenever the pin is a
     * downgrade. This also issues no redundant resolver call and bounds the fixed-point iteration
     * against cycles in the transitive graph.
     *
     * @param paths the resolved external candidate {@link Path}s
     * @param versioning the project-wide {@link ModuleVersioning}
     * @param catalog the {@link ModuleCatalog}, used to recover the Maven coordinate for a module
     *     name so the pinned version can be re-resolved
     * @param resolver the {@link Artifact.Resolver} used to re-resolve at the pinned version
     * @param recorder the {@link TelemetryRecorder} for diagnostics
     *
     * @return the version-corrected {@link Path}s
     */
    // Visible for testing.
    static List<Path> correctPinnedVersions(final List<Path> paths,
                                            final ModuleVersioning versioning,
                                            final ModuleCatalog catalog,
                                            final Artifact.Resolver resolver,
                                            final TelemetryRecorder recorder) {

        final Set<String> resolvedModuleNames = new HashSet<>();
        final List<Path> corrected = new ArrayList<>();
        final Deque<Path> worklist = new ArrayDeque<>(paths);

        while (!worklist.isEmpty()) {
            final Path path = worklist.poll();
            final Optional<String> moduleName = moduleNameOf(path, recorder);

            if (moduleName.isEmpty()) {
                corrected.add(path);
                continue;
            }

            final Optional<Version> pinnedVersion = versioning.getVersion(moduleName.get());

            if (pinnedVersion.isEmpty()) {
                corrected.add(path);
                continue;
            }

            final Path versionDir = path.getParent();
            final Optional<Version> onDiskVersion = versionDir == null
                ? Optional.empty()
                : Version.tryParse(versionDir.getFileName().toString());

            if (onDiskVersion.isEmpty() || onDiskVersion.get().equals(pinnedVersion.get())) {
                corrected.add(path);
                continue;
            }

            if (!resolvedModuleNames.add(moduleName.get())) {
                // This module was already reached (via another transitive chain) and handled: its
                // correction — the re-resolved pinned paths on success, or the stale path kept as a
                // fallback on failure — is already in `corrected`. Drop this duplicate stale
                // occurrence rather than re-adding it at its on-disk version, which would otherwise
                // feed a stale candidate into dedupeByMavenCoordinate and win whenever the pin is a
                // downgrade. This also issues no duplicate resolver call and cannot loop on a
                // transitive-graph cycle.
                continue;
            }

            final ModuleReference reference = ModuleReference.of(moduleName.get(), pinnedVersion.get());
            final Optional<Artifact> artifact = catalog.getArtifact(reference, Optional.of(recorder));

            if (artifact.isEmpty()) {
                recorder.warn(
                    "Module [%s] is pinned to [%s] but is not present in the ModuleCatalog — "
                        + "keeping on-disk version [%s]",
                    moduleName.get(), pinnedVersion.get(), onDiskVersion.get());
                corrected.add(path);
                continue;
            }

            final var resolved = resolver.resolveTransitive(artifact.get());
            if (resolved.isException()) {
                resolved.exception().ifPresent(e -> recorder.error(
                    "Failed to re-resolve [%s] at pinned version [%s]: %s",
                    moduleName.get(), pinnedVersion.get(), e.getMessage()));
                recorder.warn(
                    "Module [%s] failed to re-resolve at pinned version [%s] — keeping on-disk version [%s]",
                    moduleName.get(), pinnedVersion.get(), onDiskVersion.get());
                corrected.add(path);
            }
            else {
                recorder.warn(
                    "Module [%s] on-disk version [%s] diverges from workspace pin [%s] — correcting",
                    moduleName.get(), onDiskVersion.get(), pinnedVersion.get());
                // re-queue the newly resolved paths so their own pins are checked too, achieving a
                // fixed point rather than a single correction pass
                resolved.ifPresent(worklist::addAll);
            }
        }

        return corrected;
    }

    /**
     * Dedupes resolved artifact {@link Path}s by Maven coordinate, keeping the highest version.
     *
     * <p>Every path resolved via {@link Artifact.Resolver#resolveTransitive} sits in a local Maven
     * repository under the standard {@code <groupId-path>/<artifactId>/<version>/<artifactId>-
     * <version>[-classifier].<ext>} layout, so the shared {@link MavenCoordinateDedupe} logic — which
     * {@link AbstractJavaDependencyAnalysis#dedupeByMavenCoordinates} also uses — applies directly.
     *
     * @param paths the resolved artifact {@link Path}s to dedupe
     * @param recorder the {@link TelemetryRecorder} for diagnostics
     *
     * @return the deduped {@link Path}s, in first-seen order
     */
    // Visible for testing.
    static List<Path> dedupeByMavenCoordinate(final List<Path> paths, final TelemetryRecorder recorder) {
        return MavenCoordinateDedupe.dedupeByMavenCoordinate(
            paths,
            Optional::of,
            (kept, dropped) -> recorder.warn(
                "Coordinate [%s] has multiple resolved versions [%s, %s] on the candidate graph — keeping [%s]",
                artifactDirectory(kept), versionSegment(dropped), versionSegment(kept), versionSegment(kept)));
    }

    private static Path artifactDirectory(final Path path) {
        final Path versionDir = path.getParent();
        return versionDir == null ? path : versionDir.getParent();
    }

    private static String versionSegment(final Path path) {
        final Path versionDir = path.getParent();
        return versionDir == null ? path.toString() : versionDir.getFileName().toString();
    }

    /**
     * Determines whether {@code path} is a real (named) JPMS module -- i.e. resolves to at least one
     * module via {@link ModuleFinder} -- as opposed to an unnamed/automatic-module candidate. Used to
     * decide module-path vs classpath placement for a candidate a caller already has on hand (see
     * {@code CompilationResolution#withAdditional}), the same question {@link ModuleGraphClassifier}
     * answers for every other candidate that goes through the ordinary resolution pipeline.
     *
     * @param path the candidate directory or jar
     * @return {@code true} if {@code path} resolves to at least one named module
     */
    public static boolean isNamedModule(final Path path) {
        try {
            return ModuleFinder.of(path).findAll().stream().findFirst().isPresent();
        } catch (final RuntimeException e) {
            return false;
        }
    }

    private static Optional<String> moduleNameOf(final Path path, final TelemetryRecorder recorder) {
        try {
            return ModuleFinder.of(path).findAll().stream()
                .findFirst()
                .map(ref -> ref.descriptor().name());
        }
        catch (final RuntimeException e) {
            recorder.warn(e, "Failed to derive a module name for candidate [%s] — treating as unnamed", path);
            return Optional.empty();
        }
    }

    @Override
    public Stream<Reference> dependencies() {
        return siblingCompileDependencies(this.project, this.moduleDescriptor, this.buildDirectoryName.get(),
            this.target.get(), this.reuseExternalBuildOutput);
    }

    /**
     * Locates, for every workspace sibling {@code requires}d by {@code moduleDescriptor} whose
     * existing compiled output (if any) isn't up to date with its own source, the {@link Reference} to
     * that sibling's {@link JavaCompilerPlugin.Compile} task — shared between
     * {@link AbstractDetectResolution} and {@link AbstractCompile}, since both must force the same
     * siblings before they can proceed.
     *
     * <p>Checks freshness (via {@link #isSiblingOutputUpToDate}), not just existence of a build-output
     * directory: those aren't the same thing, and treating "some output exists" as "already built" let
     * a stale leftover output (e.g. from an earlier build of this same project, before its source
     * changed) go unrefreshed forever, since {@code Compile} was never even scheduled to run and give
     * its own up-to-date check a chance to reject it. Conversely, forcing every required sibling
     * unconditionally (skipping this check entirely) is also wrong: it schedules -- and creates a
     * {@code .build} directory for -- siblings whose already-built output is genuinely current,
     * defeating the entire point of {@link ReuseExternalBuildOutput}.
     *
     * @param project             the {@link Project} whose {@code requires} clauses to walk
     * @param moduleDescriptor    the {@link JDKModuleDescriptor} to read {@code requires} clauses from
     * @param buildDirectoryName  spin's own build directory name, to check for prior spin output
     * @param targetDirectoryName the target directory name, to check for prior spin/Maven/Gradle output
     * @param reuseExternalBuildOutput whether Maven/Gradle output counts as "already built" -- see
     *                                 {@link ReuseExternalBuildOutput}
     * @return the {@link Reference}s to force
     */
    static Stream<Reference> siblingCompileDependencies(final Project project,
                                                        final JDKModuleDescriptor moduleDescriptor,
                                                        final String buildDirectoryName,
                                                        final String targetDirectoryName,
                                                        final ReuseExternalBuildOutput reuseExternalBuildOutput) {
        final var workspace = project.workspace();

        return moduleDescriptor.requiresClauses()
            .map(r -> r.requiresModuleName().toString())
            .flatMap(name -> workspace.stream()
                .flatMap(prj -> JavaCompilerPlugin.resolveRequiredCompilerPlugin(prj, name, moduleDescriptor)
                    .optional()
                    .filter(plugin -> !isSiblingOutputUpToDate(prj, plugin, buildDirectoryName,
                        targetDirectoryName, reuseExternalBuildOutput))
                    .flatMap(plugin -> crossProjectDeps(prj, plugin))
                    .stream()));
    }

    /**
     * Determines whether {@code sibling}'s already-built MAIN output (if any) is up to date with its
     * own declared source, so {@link #siblingCompileDependencies} can decide whether forcing its
     * {@link JavaCompilerPlugin.Compile} task is actually necessary.
     *
     * <p>Checks both the default and {@code plugin}'s own major-version-suffixed source directories
     * (e.g. {@code src/main/java} and {@code src/main/java25}) without needing the system default
     * {@link JDKVersion} to pick between them the way {@link SourcePathKind#detect} does -- checking
     * both unconditionally can only make this over-conservative (an extra forced rebuild), never miss a
     * genuinely stale source file.
     *
     * @param sibling                  the candidate workspace sibling {@link Project}
     * @param plugin                   the sibling's matched {@link JavaCompilerPlugin}
     * @param buildDirectoryName       spin's own build directory name, to check for prior spin output
     * @param targetDirectoryName      the target directory name, to check for prior spin/Maven/Gradle output
     * @param reuseExternalBuildOutput whether Maven/Gradle output counts as "already built" -- see
     *                                 {@link ReuseExternalBuildOutput}
     * @return {@code true} if {@code sibling} already has compiled output that's current with its source
     */
    private static boolean isSiblingOutputUpToDate(final Project sibling,
                                                    final JavaCompilerPlugin plugin,
                                                    final String buildDirectoryName,
                                                    final String targetDirectoryName,
                                                    final ReuseExternalBuildOutput reuseExternalBuildOutput) {

        final Optional<Path> output = resolveCompiledOutput(sibling.path(), buildDirectoryName,
            targetDirectoryName, SourcePathKind.MAIN, reuseExternalBuildOutput);

        if (output.isEmpty() || !containsCompiledClasses(output.get())) {
            return false;
        }

        final String sourceRoot = SourcePathKind.MAIN.sourceRoot().orElseThrow() + "java";
        final PathSetBuilder sources = PathSetBuilder.create();
        Stream.of(sibling.path().resolve(sourceRoot),
                sibling.path().resolve(sourceRoot + plugin.getJavaVersion().major()))
            .filter(Files::isDirectory)
            .forEach(sources::add);

        return isUpToDate(output.get(), sources.build());
    }

    /**
     * Determines whether {@code output} is at least as new as every file under {@code projectPath}'s
     * declared {@code scope} source root (e.g. {@code src/main/java}), if that root exists.
     *
     * <p>Used by {@code AbstractResourcePlugin.CopyResources} to decide whether it's safe to skip
     * copying resources into {@code output}: if {@code output} is already at least as new as the
     * declared source, {@link AbstractCompile}'s own freshness check -- run against this exact same
     * {@code output} candidate -- will independently reach the same "reuse, don't touch this
     * directory" conclusion, so skipping the resource copy here can never leave a freshly
     * (re)compiled directory missing its resources: the two decisions can't diverge, because both are
     * gated on the same declared source being unchanged rather than on resource freshness.
     *
     * @param projectPath the project root
     * @param scope       the {@link SourcePathKind} whose declared source root to check (MAIN or TEST)
     * @param output      the candidate already-built output directory
     * @return {@code true} if {@code output} is at least as new as every file under the declared source root
     */
    static boolean isDeclaredSourceUpToDate(final Path projectPath, final SourcePathKind scope, final Path output) {
        final Path sourceRoot = projectPath.resolve(scope.sourceRoot().orElseThrow() + "java");
        final PathSetBuilder sources = PathSetBuilder.create();
        if (Files.isDirectory(sourceRoot)) {
            sources.add(sourceRoot);
        }
        return isUpToDate(output, sources.build());
    }

    static Optional<Reference> crossProjectDeps(final Project prj,
                                                final JavaCompilerPlugin plugin) {
        return prj.invocables()
            .filter(definition -> definition.getPlugin() == plugin)
            .filter(definition -> JavaCompilerPlugin.Compile.class.isAssignableFrom(definition.getTaskClass()))
            .findFirst()
            .map(Invocable::getTaskClass)
            .map(taskClass -> Reference.of(prj, taskClass));
    }
}
