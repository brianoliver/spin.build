package build.spin.module.java.tests;

import build.base.assertion.Eventually;
import build.base.configuration.Option;
import build.base.flow.CompletingSubscriber;
import build.base.flow.RecordingSubscriber;
import build.base.foundation.Strings;
import build.base.foundation.stream.Streams;
import build.base.io.PathSetBuilder;
import build.base.option.JDKVersion;
import build.base.telemetry.Error;
import build.base.telemetry.Telemetry;
import build.base.version.Version;
import build.codemodel.foundation.CodeModel;
import build.codemodel.foundation.descriptor.RequiresModuleDescriptor;
import build.codemodel.jdk.descriptor.JDKModuleDescriptor;
import build.percolate.core.ModuleGraphClassifier;
import build.spawn.application.Application;
import build.spawn.application.Console;
import build.spawn.application.option.Argument;
import build.spawn.application.option.Executable;
import build.spawn.application.option.Name;
import build.spawn.application.option.StandardOutputSubscriber;
import build.spawn.platform.local.LocalMachine;
import build.spin.Asset;
import build.spin.AssetCache;
import build.spin.Engine;
import build.spin.Program;
import build.spin.ProgramExecutionException;
import build.spin.Project;
import build.spin.Task;
import build.spin.Workspace;
import build.spin.common.DefaultAssetCache;
import build.spin.common.ProcessFailedException;
import build.spin.module.checkstyle.CheckstylePlugin;
import build.spin.module.clean.CleanPlugin;
import build.spin.module.java.AbstractDetectResolution;
import build.spin.module.java.CustomizationPlugin;
import build.spin.module.java.Java25CompilerPlugin;
import build.spin.module.java.Java8CompilerPlugin;
import build.spin.module.java.JavaCompilerPlugin;
import build.spin.module.java.JavaPlatform;
import build.spin.module.junit.Java25JUnitPlugin;
import build.spin.module.junit.Java8JUnitPlugin;
import build.spin.module.maven.MavenRepository;
import build.spin.module.modulesystem.Artifact;
import build.spin.module.modulesystem.CompilationResolution;
import build.spin.module.modulesystem.EmptyModuleCatalog;
import build.spin.module.modulesystem.EmptyModuleVersioning;
import build.spin.module.modulesystem.ModuleCatalog;
import build.spin.module.modulesystem.ModuleReference;
import build.spin.module.modulesystem.ModuleVersioning;
import build.spin.module.modulesystem.TestModuleDescriptor;
import build.spin.module.modulesystem.maven.PomBasedModuleCatalog;
import build.spin.module.modulesystem.maven.PomBasedModuleVersioning;
import build.spin.module.modulesystem.maven.PomBasedTestModuleDescriptor;
import build.spin.option.JlinkTargets;
import build.spin.option.ReuseExternalBuildOutput;
import build.spin.testing.RequireJavaVersion;
import build.spin.testing.WorkspaceDiscovery;
import build.spin.testing.WorkspacePath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Stack;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for Java-based Spin-based {@link Project}s.
 * 
 * @author brian.oliver
 * @since Jun-2020
 */
@ExtendWith(WorkspaceDiscovery.class)
public class JavaProjectTests {

    private static <T extends Throwable> Optional<T> causeOfType(final Throwable t, final Class<T> type) {
        for (Throwable current = t; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return Optional.of(type.cast(current));
            }
        }
        return Optional.empty();
    }

    @Test
    @WorkspacePath("java-8")
    void shouldDetectJava8Project(final Engine engine, final Workspace workspace) {
        assertThat(engine.options().get(JDKVersion.class).major()).isEqualTo(8);
        assertThat(workspace.name()).isEqualTo("java-8");
        assertThat(workspace.getPlugin(Java8CompilerPlugin.class).isPresent()).isTrue();
    }

    @Test
    @WorkspacePath("java-25")
    void shouldDetectJava25Project(final Engine engine, final Workspace workspace) {
        assertThat(engine.options().get(JDKVersion.class).major()).isEqualTo(25);
        assertThat(workspace.name()).isEqualTo("java-25");
        assertThat(workspace.getPlugin(Java25CompilerPlugin.class).isPresent()).isTrue();
    }

    @Test
    @WorkspacePath("javadoc-config")
    void shouldApplyNamedConfigurationToJavadocArguments(final Engine engine, final Workspace workspace)
        throws Exception {

        // create a Program to generate javadoc for the Workspace
        final Program program = engine.createProgram(workspace, Task.Pattern.of("javadoc"));

        // create a Task ExecutionCache for the Program
        final AssetCache cache = DefaultAssetCache.create();

        program.execute(cache);

        // locate the generated "arguments-javadoc-*" file, containing the arguments passed to javadoc
        final Path buildPath = workspace.path().resolve(".build");
        final Path argumentsFile;
        try (var files = Files.list(buildPath)) {
            argumentsFile = files
                .filter(path -> path.getFileName().toString().startsWith("arguments-javadoc-"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "Expected an arguments-javadoc-* file in [" + buildPath + "]"));
        }

        final String contents = Files.readString(argumentsFile);

        // the .spin/build.spin.module.javadoc.properties fixture sets author, version and nodeprecated to true
        assertThat(contents).contains("-author");
        assertThat(contents).contains("-version");
        assertThat(contents).contains("-nodeprecated");

        // ... and sets enable-preview to false, overriding the default (enabled) behavior
        assertThat(contents).doesNotContain("--enable-preview");
    }

    @Test
    @WorkspacePath("no-config")
    void shouldDiscoverJavaWorkspaceWithoutSpinConfig(final Workspace workspace) {
        // Reaching this assertion means workspace discovery completed without
        // throwing UnsatisfiedDependencyException when MavenPlugin (activated via
        // the Java25CompilerPlugin from src/main/java) tried to inject a ModuleCatalog.
        // On a workspace without module-catalog.properties, EmptyModuleCatalog and
        // EmptyModuleVersioning are the only resources that satisfy those injections.
        assertThat(workspace.getPlugin(Java25CompilerPlugin.class)).isPresent();
        assertThat(workspace.resources().filter(ModuleCatalog.class::isInstance).findFirst())
            .get().isInstanceOf(EmptyModuleCatalog.class);
        assertThat(workspace.resources().filter(ModuleVersioning.class::isInstance).findFirst())
            .get().isInstanceOf(EmptyModuleVersioning.class);
    }

    @Test
    @WorkspacePath("pom-based")
    void shouldDiscoverMavenWorkspaceWithoutSpinConfig(final Workspace workspace) {
        // A workspace with a pom.xml but no spin config files should resolve
        // ModuleCatalog/ModuleVersioning to the PomBased* variants (not Default*,
        // whose detection deliberately excludes pom.xml workspaces to leave room
        // for this case).
        assertThat(workspace.getPlugin(Java25CompilerPlugin.class)).isPresent();
        assertThat(workspace.resources().filter(ModuleCatalog.class::isInstance).findFirst())
            .get().isInstanceOf(PomBasedModuleCatalog.class);
        assertThat(workspace.resources().filter(ModuleVersioning.class::isInstance).findFirst())
            .get().isInstanceOf(PomBasedModuleVersioning.class);
    }

    @Test
    @WorkspacePath("pom-based")
    void shouldProvidePomBasedTestModuleDescriptorForMavenWorkspace(final Workspace workspace) {
        // PomBasedTestModuleDescriptor should activate on the same pom.xml-only
        // workspace and expose the test-scoped deps (junit-jupiter-api) from the
        // fixture's pom.xml as requires on the test module descriptor.
        final TestModuleDescriptor testDescriptor = workspace.resources()
            .filter(TestModuleDescriptor.class::isInstance)
            .map(TestModuleDescriptor.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Expected a TestModuleDescriptor resource in workspace [" + workspace.path() + "]"));

        assertThat(testDescriptor).isInstanceOf(PomBasedTestModuleDescriptor.class);

        final JDKModuleDescriptor descriptor = testDescriptor.get(workspace);
        assertThat(descriptor.requiresClauses().map(r -> r.requiresModuleName().toString()))
            .contains("org.junit.jupiter", "junit.jupiter.api");
    }

    @Test
    @WorkspacePath("maven-built-main")
    @RequireJavaVersion("25")
    void shouldIncludeMavenBuiltMainOutputInTestResolution(final Engine engine, final Workspace workspace)
        throws Exception {

        // Simulates a project already compiled by `mvn compile` directly -- never (yet) by spin: a
        // target/classes directory exists on disk before spin has run at all, while spin's own
        // .build directory does not exist yet. additionalSiblingCandidates() must find this via
        // the same spin/Maven/Gradle-aware lookup used for sibling-project candidates
        // (resolveCompiledOutput), not the previously-hardcoded .build/main/<target> convention,
        // which never exists for a project like this one.
        deleteRecursively(workspace.path().resolve("target"));
        deleteRecursively(workspace.path().resolve(".build"));

        // pre-build for real, once, via spin, to get genuine bytecode -- AbstractDetectResolution#dependencies
        // now forces this project's own main Compile task to run before test resolution (see
        // shouldForceOwnMainCompileBeforeTestResolution), and AbstractCompile#compile only ever reuses a
        // candidate that actually contains compiled classes, so the Maven candidate below needs to be
        // real bytecode rather than a placeholder marker file (marker is deliberately not named *.class
        // elsewhere in this class for the same maven-dependency-plugin bytecode-scan reason noted on
        // shouldReuseAlreadyBuiltMavenOutputWithoutInvokingJavac -- that constraint doesn't apply here
        // since this candidate is genuine bytecode, not a stand-in)
        engine.createProgram(workspace, Task.Pattern.of("compile")).execute(DefaultAssetCache.create());

        final Path spinOutput = workspace.path().resolve(".build/main/target");
        assertThat(spinOutput.resolve("Foo.class"))
            .as("sanity check: expected the pre-build step to have actually compiled [Foo]")
            .exists();

        // relocate that output to the Maven convention (target/classes) and remove spin's own
        // .build directory, simulating "already built by Maven, never (yet) built by spin"
        final Path mavenClasses = workspace.path().resolve("target/classes");
        Files.createDirectories(mavenClasses);
        copyDirectoryContents(spinOutput, mavenClasses);
        deleteRecursively(workspace.path().resolve(".build"));

        assertThat(workspace.path().resolve(".build")).doesNotExist();

        assertThat(workspace.getPlugin(Java25JUnitPlugin.class)).isPresent();

        // requesting only the test-resolution task now also forces this project's own main Compile
        // task (see shouldForceOwnMainCompileBeforeTestResolution) -- Maven's target/classes is
        // already up to date with Foo.java (just written, strictly newer than the checked-out source
        // file), so AbstractCompile's own freshness short-circuit reuses it directly rather than
        // writing a fresh .build/main/target
        final Program program = engine.createProgram(workspace,
            Task.Pattern.of("detect.test.compilation.resolution"), ReuseExternalBuildOutput.ENABLED);
        final AssetCache cache = DefaultAssetCache.create();

        final AssetCache results = program.execute(cache);

        final CompilationResolution resolution = results.get(workspace, AbstractDetectResolution.class)
            .map(Asset::get)
            .orElseThrow(() -> new AssertionError(
                "Expected a CompilationResolution asset for [" + workspace + "]"));

        assertThat(Stream.concat(resolution.modulePath().stream(), resolution.classPath().stream()))
            .as("expected the Maven-built target/classes output to be a resolution candidate")
            .contains(mavenClasses);

        assertThat(workspace.path().resolve(".build/main/target"))
            .as("expected AbstractCompile to have reused Maven's target/classes directly rather than "
                + "writing a fresh .build/main/target")
            .doesNotExist();
    }

    @Test
    @WorkspacePath("maven-built-main")
    @RequireJavaVersion("25")
    void shouldReuseAlreadyBuiltMavenOutputWithoutInvokingJavac(final Engine engine, final Workspace workspace)
        throws Exception {

        // The exact scenario docs/spin-build-issues.md's "spin1-build-spin2" write-up settles on as
        // the fix: AbstractCompile.compile() must itself detect an already-valid Maven target/classes
        // and skip invoking javac entirely, rather than relying on a graph-level "should I force this
        // sibling" decision that a concurrently-running AbstractDetectResolution can observe mid-write
        // (a TOCTOU race). This locks in the compile-task-level guard directly, distinct from
        // shouldNotRecompileAnAlreadyBuiltWorkspaceSibling below, which only locks in the graph-edge
        // guard that decides whether to force a *sibling's* Compile task.
        deleteRecursively(workspace.path().resolve("target"));
        deleteRecursively(workspace.path().resolve(".build"));

        // pre-build for real, once, via spin, to get genuine bytecode -- an empty placeholder .class
        // file isn't valid bytecode and fails maven-dependency-plugin's analyze-only bytecode scan of
        // this fixture once it's copied under this module's own target/test-classes (see the
        // "marker is deliberately not named *.class" note on shouldIncludeMavenBuiltMainOutputInTestResolution)
        engine.createProgram(workspace, Task.Pattern.of("compile")).execute(DefaultAssetCache.create());

        final Path spinOutput = workspace.path().resolve(".build/main/target");
        assertThat(spinOutput.resolve("Foo.class"))
            .as("sanity check: expected the pre-build step to have actually compiled [Foo]")
            .exists();

        // relocate that output to the Maven convention (target/classes) and remove spin's own
        // .build directory, simulating "already built by Maven, never (yet) built by spin"
        final Path mavenClasses = workspace.path().resolve("target/classes");
        Files.createDirectories(mavenClasses);
        copyDirectoryContents(spinOutput, mavenClasses);
        deleteRecursively(workspace.path().resolve(".build"));

        assertThat(workspace.path().resolve(".build")).doesNotExist();

        // compile again, from a fresh cache -- Maven's target/classes is already up to date with
        // Foo.java (just written, strictly newer than the checked-out source file). Reuse of external
        // build output is opt-in (see ReuseExternalBuildOutput) — this test is specifically exercising
        // that opted-in path.
        final AssetCache results = engine.createProgram(
                workspace, Task.Pattern.of("compile"), ReuseExternalBuildOutput.ENABLED)
            .execute(DefaultAssetCache.create());

        assertThat(workspace.path().resolve(".build/arguments-25"))
            .as("expected AbstractCompile to skip invoking javac entirely -- javac's arguments file "
                + "is only ever written immediately before launching the javac process")
            .doesNotExist();

        final var compiledOutput = results.get(workspace, JavaCompilerPlugin.Compile.class)
            .map(Asset::get)
            .orElseThrow(() -> new AssertionError("Expected a Compile PathSet asset for [" + workspace + "]"));

        assertThat(compiledOutput.stream())
            .as("expected Compile to have returned Maven's target/classes directly, rather than a "
                + "freshly (re)compiled .build/main/target")
            .containsExactly(mavenClasses);
    }

    @Test
    @WorkspacePath("maven-built-main")
    @RequireJavaVersion("25")
    void shouldRecompileWhenReusableMavenOutputIsStale(final Engine engine, final Workspace workspace)
        throws Exception {

        // Companion to shouldReuseAlreadyBuiltMavenOutputWithoutInvokingJavac above -- that test locks
        // in the "reuse" side of AbstractCompile's freshness check (AbstractDetectResolution.isUpToDate);
        // this locks in the other side, that a genuinely stale candidate is rejected and javac still runs.
        deleteRecursively(workspace.path().resolve("target"));
        deleteRecursively(workspace.path().resolve(".build"));

        // pre-build for real, once, via spin, to get genuine bytecode -- an empty placeholder .class
        // file isn't valid bytecode and fails maven-dependency-plugin's analyze-only bytecode scan of
        // this fixture once it's copied under this module's own target/test-classes (see the
        // "marker is deliberately not named *.class" note on shouldIncludeMavenBuiltMainOutputInTestResolution)
        engine.createProgram(workspace, Task.Pattern.of("compile")).execute(DefaultAssetCache.create());

        final Path spinPrebuildOutput = workspace.path().resolve(".build/main/target");
        assertThat(spinPrebuildOutput.resolve("Foo.class"))
            .as("sanity check: expected the pre-build step to have actually compiled [Foo]")
            .exists();

        // relocate that genuine bytecode to the Maven convention (target/classes), remove spin's own
        // .build directory, and back-date it well before Foo.java's on-disk mtime -- simulating
        // "already built by Maven, but from a version of Foo.java older than what's checked out now"
        final Path mavenClasses = workspace.path().resolve("target/classes");
        Files.createDirectories(mavenClasses);
        copyDirectoryContents(spinPrebuildOutput, mavenClasses);
        deleteRecursively(workspace.path().resolve(".build"));

        final var staleTime = java.nio.file.attribute.FileTime.fromMillis(1);
        try (Stream<Path> walk = Files.walk(mavenClasses)) {
            for (final Path p : (Iterable<Path>) walk::iterator) {
                Files.setLastModifiedTime(p, staleTime);
            }
        }

        final AssetCache results = engine.createProgram(
                workspace, Task.Pattern.of("compile"), ReuseExternalBuildOutput.ENABLED)
            .execute(DefaultAssetCache.create());

        assertThat(workspace.path().resolve(".build/arguments-25"))
            .as("expected AbstractCompile to have invoked javac -- the stale Maven candidate must not "
                + "have short-circuited compilation")
            .exists();

        final Path spinOutput = workspace.path().resolve(".build/main/target");
        assertThat(spinOutput.resolve("Foo.class"))
            .as("expected a freshly compiled Foo.class under spin's own output")
            .exists();

        final var compiledOutput = results.get(workspace, JavaCompilerPlugin.Compile.class)
            .map(Asset::get)
            .orElseThrow(() -> new AssertionError("Expected a Compile PathSet asset for [" + workspace + "]"));

        assertThat(compiledOutput.stream())
            .as("expected Compile to have returned its own freshly (re)compiled output, not the stale "
                + "Maven candidate")
            .containsExactly(spinOutput);
    }

    @Test
    @WorkspacePath("maven-built-main")
    @RequireJavaVersion("25")
    void shouldSkipRecompileOfItsOwnUpToDateOutputEvenWithReuseDisabled(final Engine engine, final Workspace workspace)
        throws Exception {

        // AbstractCompile's freshness short-circuit isn't gated by ReuseExternalBuildOutput -- that
        // option only controls whether Maven/Gradle output is a *candidate* at all (see
        // AbstractDetectResolution.resolveCompiledOutput); spin's own prior .build output is always a
        // candidate, reuse enabled or not. This locks in that a second compile against an already
        // up-to-date spin output skips javac even with reuse left at its DISABLED default.
        deleteRecursively(workspace.path().resolve("target"));
        deleteRecursively(workspace.path().resolve(".build"));

        engine.createProgram(workspace, Task.Pattern.of("compile")).execute(DefaultAssetCache.create());

        final Path spinOutput = workspace.path().resolve(".build/main/target");
        assertThat(spinOutput.resolve("Foo.class"))
            .as("sanity check: expected the pre-build step to have actually compiled [Foo]")
            .exists();

        final Path argumentsFile = workspace.path().resolve(".build/arguments-25");
        assertThat(argumentsFile)
            .as("sanity check: expected the pre-build step to have invoked javac")
            .exists();
        Files.delete(argumentsFile);

        // compile again, from a fresh cache, with ReuseExternalBuildOutput left at its DISABLED default
        final AssetCache results = engine.createProgram(workspace, Task.Pattern.of("compile"))
            .execute(DefaultAssetCache.create());

        assertThat(argumentsFile)
            .as("expected AbstractCompile to skip invoking javac entirely on the second compile -- "
                + "javac's arguments file is only ever written immediately before launching the javac "
                + "process, and this test deleted it after the pre-build step specifically to detect a "
                + "second invocation")
            .doesNotExist();

        final var compiledOutput = results.get(workspace, JavaCompilerPlugin.Compile.class)
            .map(Asset::get)
            .orElseThrow(() -> new AssertionError("Expected a Compile PathSet asset for [" + workspace + "]"));

        assertThat(compiledOutput.stream())
            .as("expected Compile to have returned its already-built .build/main/target directly")
            .containsExactly(spinOutput);
    }

    @Test
    @WorkspacePath("maven-built-main")
    @RequireJavaVersion("25")
    void shouldCompileTestsAgainstOwnMainOutputEvenWhenTestResolutionRanBeforeMainCompile(
            final Engine engine, final Workspace workspace)
        throws Exception {

        // AbstractDetectResolution#create's "this project's own main output" seed for TEST-scope
        // resolution is deliberately best-effort (see the comment there) -- dependencies() does NOT
        // force this project's own main Compile task, so detect-test-resolution can race it and finish
        // first, computing (and caching, since CompilationResolution is an AssetCache-cached asset) a
        // resolution that genuinely lacks the project's own main output. This test reproduces that
        // outcome deterministically -- by running detect-test-resolution alone, before main has ever
        // been compiled -- rather than relying on timing, then proves test-compile still succeeds
        // (FooTest.java references Foo, a main class) against the SAME stale cached resolution,
        // because Java25JUnitPlugin.Compile injects its own project's main output directly (see
        // @From(Java25CompilerPlugin.Compile.class) Optional<PathSet>) rather than trusting
        // DetectTestResolution's snapshot alone.
        deleteRecursively(workspace.path().resolve("target"));
        deleteRecursively(workspace.path().resolve(".build"));

        final AssetCache afterResolution = engine.createProgram(
                workspace, Task.Pattern.of("detect.test.compilation.resolution"))
            .execute(DefaultAssetCache.create());

        final CompilationResolution staleResolution = afterResolution.get(workspace, AbstractDetectResolution.class)
            .map(Asset::get)
            .orElseThrow(() -> new AssertionError("Expected a CompilationResolution asset for [" + workspace + "]"));

        assertThat(Stream.concat(staleResolution.modulePath().stream(), staleResolution.classPath().stream()))
            .as("sanity check: expected the stale resolution to genuinely lack the project's own "
                + "(not yet built) main output, reproducing the race this test guards against")
            .noneMatch(p -> p.toString().contains(".build/main"));

        // reusing the cache populated above -- so the stale CompilationResolution is what gets reused --
        // request test-compile; it must still succeed despite that stale/incomplete cached resolution
        engine.createProgram(workspace, Task.Pattern.of("test-compile")).execute(afterResolution);

        assertThat(workspace.path().resolve(".build/test/target/FooTest.class"))
            .as("expected FooTest.java (referencing Foo) to have compiled successfully despite the "
                + "stale cached resolution")
            .exists();
    }

    @Test
    @WorkspacePath("sibling-rebuild")
    @RequireJavaVersion("25")
    void shouldNotRecompileAnAlreadyBuiltWorkspaceSibling(final Engine engine, final Workspace workspace)
        throws Exception {

        // "library" is a workspace sibling that "consumer"'s module-info.java requires.
        // AbstractDetectResolution#siblingCompileDependencies is the single mechanism that wires
        // cross-project compile ordering from that requires clause, shared by both
        // AbstractDetectResolution#dependencies() and AbstractCompile#dependencies() -- it forces the
        // sibling's own Compile task only when the sibling doesn't already have usable compiled output
        // on disk. If either call site is still forcing an already-built sibling to be recompiled,
        // that's the bug this test is meant to catch.
        final Project libraryProject = workspace.getProject(workspace.path().resolve("library"))
            .orElseThrow(() -> new AssertionError("Expected a [library] Project in the workspace"));
        final Project consumerProject = workspace.getProject(workspace.path().resolve("consumer"))
            .orElseThrow(() -> new AssertionError("Expected a [consumer] Project in the workspace"));

        // leftover state from a prior run of this test (target/test-classes is only refreshed by a
        // clean build) would otherwise make this test order-dependent
        deleteRecursively(libraryProject.path().resolve("target"));
        deleteRecursively(libraryProject.path().resolve(".build"));
        deleteRecursively(consumerProject.path().resolve(".build"));

        // pre-build "library" for real, once, via spin, so we have genuine compiled module output
        engine.createProgram(libraryProject, Task.Pattern.of("compile")).execute(DefaultAssetCache.create());

        final Path spinLibraryOutput = libraryProject.path().resolve(".build/main/target");
        assertThat(spinLibraryOutput.resolve("module-info.class"))
            .as("sanity check: expected the pre-build step to have actually compiled [library]")
            .exists();

        // relocate that output to the Maven convention (target/classes) and remove spin's own
        // .build directory for [library], simulating "built by Maven, never (yet) built by spin"
        final Path mavenLibraryOutput = libraryProject.path().resolve("target/classes");
        Files.createDirectories(mavenLibraryOutput);
        copyDirectoryContents(spinLibraryOutput, mavenLibraryOutput);
        deleteRecursively(libraryProject.path().resolve(".build"));

        assertThat(libraryProject.path().resolve(".build")).doesNotExist();

        // now compile "consumer" only, with a fresh (empty) cache -- library's already-built output
        // at target/classes should be enough to satisfy the requires clause without spin recompiling it.
        // Reuse of external build output is opt-in (see ReuseExternalBuildOutput) — this test is
        // specifically exercising that opted-in path.
        engine.createProgram(consumerProject, Task.Pattern.of("compile"), ReuseExternalBuildOutput.ENABLED)
            .execute(DefaultAssetCache.create());

        assertThat(consumerProject.path().resolve(".build/main/target/com/example/consumer/Consumer.class"))
            .as("expected [consumer] to have actually compiled")
            .exists();

        assertThat(libraryProject.path().resolve(".build"))
            .as("compiling [consumer] must not force spin to recompile [library] -- it already has "
                + "usable output at target/classes, which AbstractDetectResolution#siblingCompileDependencies "
                + "already correctly treats as sufficient; if [library]'s own .build directory reappears "
                + "here, either AbstractDetectResolution#dependencies() or AbstractCompile#dependencies() "
                + "is forcing a sibling rebuild that the shared guard should have skipped")
            .doesNotExist();
    }

    @Test
    @WorkspacePath("sibling-rebuild")
    @RequireJavaVersion("25")
    void shouldOrderSiblingCompileBeforeDependentWhenBothNeedBuilding(final Engine engine, final Workspace workspace)
        throws Exception {

        // Unlike shouldNotRecompileAnAlreadyBuiltWorkspaceSibling above (where [library] is pre-built
        // and never itself a target of the requested Program), this compiles the whole workspace from
        // a completely clean state, so both [library] and [consumer] are independently scheduled,
        // top-level targets that genuinely need building. DefaultProgram walks the whole workspace and
        // pushes every matching Task onto the stack as its own target (see DefaultProgram#L167-169) —
        // that inclusion happens regardless of Task#dependencies() — so what's actually under test here
        // is ordering, not inclusion: the scheduler dispatches a Task the moment its pending dependency
        // count reaches zero (DefaultProgram#execute, driven purely by dependencyGraph edges built from
        // Task#dependencies()), with no other cross-project ordering mechanism. If AbstractCompile or
        // AbstractDetectResolution's "skip an already-built sibling" guard ever mis-fires here (treating
        // a genuinely-unbuilt [library] as already built), the missing edge lets [consumer]'s compile be
        // dispatched before [library]'s finishes, which deterministically fails ("module not found")
        // since [library] has never been built by any tool at this point.
        final Project libraryProject = workspace.getProject(workspace.path().resolve("library"))
            .orElseThrow(() -> new AssertionError("Expected a [library] Project in the workspace"));
        final Project consumerProject = workspace.getProject(workspace.path().resolve("consumer"))
            .orElseThrow(() -> new AssertionError("Expected a [consumer] Project in the workspace"));

        deleteRecursively(libraryProject.path().resolve("target"));
        deleteRecursively(libraryProject.path().resolve(".build"));
        deleteRecursively(consumerProject.path().resolve("target"));
        deleteRecursively(consumerProject.path().resolve(".build"));

        engine.createProgram(workspace, Task.Pattern.of("compile")).execute(DefaultAssetCache.create());

        assertThat(libraryProject.path().resolve(".build/main/target/module-info.class"))
            .as("expected [library] to have been compiled as part of the whole-workspace Program")
            .exists();
        assertThat(consumerProject.path().resolve(".build/main/target/com/example/consumer/Consumer.class"))
            .as("expected [consumer] to have compiled -- only possible if [library]'s Compile task ran "
                + "to completion first, since [consumer]'s module-info requires [library]'s module")
            .exists();
    }

    @Test
    @WorkspacePath("multi-release-sibling")
    @RequireJavaVersion("25")
    void shouldForceUnbuiltMultiVersionSiblingRegardlessOfVariant(final Engine engine, final Workspace workspace)
        throws Exception {

        // [library] is multi-release (both Java8CompilerPlugin.Compile and Java25CompilerPlugin.Compile
        // apply to it, like the "multi-release" fixture); [consumer] requires it. resolveCompiledOutput,
        // which the "already built" guard on AbstractCompile/AbstractDetectResolution checks, reports
        // existence per-PROJECT (a single .build/main/target, per SourcePathKind.MAIN.outputPrefix()) --
        // it has no notion of "which JDK variant" populated that output. This guards against that
        // granularity mismatch silently swallowing the forcing dependency for a multi-version sibling
        // that has genuinely never been built by any variant.
        final Project libraryProject = workspace.getProject(workspace.path().resolve("library"))
            .orElseThrow(() -> new AssertionError("Expected a [library] Project in the workspace"));
        final Project consumerProject = workspace.getProject(workspace.path().resolve("consumer"))
            .orElseThrow(() -> new AssertionError("Expected a [consumer] Project in the workspace"));

        deleteRecursively(libraryProject.path().resolve("target"));
        deleteRecursively(libraryProject.path().resolve(".build"));
        deleteRecursively(consumerProject.path().resolve("target"));
        deleteRecursively(consumerProject.path().resolve(".build"));

        engine.createProgram(workspace, Task.Pattern.of("compile")).execute(DefaultAssetCache.create());

        assertThat(libraryProject.path().resolve(".build/main/target/module-info.class"))
            .as("expected multi-version [library] to have been compiled as part of the whole-workspace Program")
            .exists();
        assertThat(consumerProject.path().resolve(".build/main/target/com/example/mrconsumer/Consumer.class"))
            .as("expected [consumer] to have compiled -- only possible if [library]'s Compile task ran "
                + "to completion first")
            .exists();
    }

    private static void copyDirectoryContents(final Path source, final Path destination) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (final Path path : (Iterable<Path>) paths::iterator) {
                final Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(final Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (final Path p : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }

    @Test
    @WorkspacePath("multi-release")
    @RequireJavaVersion("8")
    void shouldDetectMultipleJavaPlugins(final Engine engine, final Workspace workspace)
        throws Exception {

        // ensure the default Java Version is 8
        assertThat(engine.options().get(JDKVersion.class).major()).isEqualTo(8);

        assertThat(workspace.name()).isEqualTo("multi-release");

        assertThat(workspace.getPlugin(Java8CompilerPlugin.class).isPresent()).isTrue();
        assertThat(workspace.getPlugin(Java25CompilerPlugin.class).isPresent()).isTrue();

        // TODO: assert that Java25Plugin.Compile requires Java8Plugin.Compile

        assertThat(workspace.getPlugin(Java8JUnitPlugin.class).isPresent()).isTrue();
        assertThat(workspace.getPlugin(Java25JUnitPlugin.class).isPresent()).isTrue();

        // TODO: assert that Java25JUnitPlugin.Compile requires Java8JUnitPlugin.Compile

        // create a Program to compile the Workspace
        final Program program = engine.createProgram(workspace, Task.Pattern.of("compile"));

        // create a Task ExecutionCache for the Program
        final AssetCache cache = DefaultAssetCache.create();

        program.execute(cache);
    }

    @Test
    @WorkspacePath("broken-25")
    void shouldCaptureCompilerErrors(final Engine engine, final Workspace workspace) {

        assertThat(engine.options().get(JDKVersion.class).major()).isEqualTo(25);
        assertThat(workspace.name()).isEqualTo("broken-25");
        assertThat(workspace.getPlugin(Java25CompilerPlugin.class).isPresent()).isTrue();

        // create a Program to compile the Workspace
        final Program program = engine.createProgram(workspace, Task.Pattern.of("compile"));

        // create a Task ExecutionCache for the Program
        final AssetCache cache = DefaultAssetCache.create();

        // establish a CompletingObserver to observe the compilation failure
        final CompletingSubscriber<Telemetry> observer = new CompletingSubscriber<>();
        final CompletableFuture<Telemetry> future = observer.when(t -> t instanceof Error);
        engine.subscribe(observer);

        try {
            program.execute(cache);

            fail("The compilation of the source file should have failed");
        }
        catch (final ProgramExecutionException e) {
            Eventually.assertThat(future).isCompleted();

            // all task failures: thrown + suppressed
            final var allFailures = Stream.concat(Stream.of(e), Arrays.stream(e.getSuppressed()))
                .toList();

            // extractOutput embeds stderr into the ProgramExecutionException message — verify it surfaced
            final var compileTaskFailure = allFailures.stream()
                .filter(t -> t.getMessage().contains("Broken.java") && t.getMessage().contains("error:"))
                .findFirst();

            assertThat(compileTaskFailure)
                .as("expected javac errors (Broken.java, error:) to be embedded in a task failure message")
                .isPresent();

            // the ProcessFailedException in the cause chain should carry the raw output
            final var pfe = causeOfType(compileTaskFailure.get(), ProcessFailedException.class);
            assertThat(pfe)
                .as("expected a ProcessFailedException in the cause chain of the compile failure")
                .isPresent();
            assertThat(pfe.get().output()).contains("Broken.java").contains("error:");
        }
    }

    @Test
    @WorkspacePath("checkstyle-violation")
    void shouldCaptureCheckstyleViolations(final Engine engine, final Workspace workspace) {

        assertThat(workspace.name()).isEqualTo("checkstyle-violation");
        assertThat(workspace.getPlugin(Java25CompilerPlugin.class).isPresent()).isTrue();
        assertThat(workspace.getPlugin(CheckstylePlugin.class).isPresent()).isTrue();

        // create a Program to run Checkstyle on the Workspace
        final Program program = engine.createProgram(workspace, Task.Pattern.of("checkstyle"));

        // create a Task ExecutionCache for the Program
        final AssetCache cache = DefaultAssetCache.create();

        // establish a CompletingObserver to observe the checkstyle failure
        final CompletingSubscriber<Telemetry> observer = new CompletingSubscriber<>();
        final CompletableFuture<Telemetry> future = observer.when(t -> t instanceof Error);
        engine.subscribe(observer);

        try {
            program.execute(cache);

            fail("The Checkstyle check of the source file should have failed");
        }
        catch (final ProgramExecutionException e) {
            Eventually.assertThat(future).isCompleted();

            // all task failures: thrown + suppressed
            final var allFailures = Stream.concat(Stream.of(e), Arrays.stream(e.getSuppressed()))
                .toList();

            // extractOutput embeds the captured checkstyle output into the ProgramExecutionException
            // message — verify it surfaced
            final var checkstyleTaskFailure = allFailures.stream()
                .filter(t -> t.getMessage().contains("HasUnusedImport.java")
                    && t.getMessage().contains("Unused import"))
                .findFirst();

            assertThat(checkstyleTaskFailure)
                .as("expected a Checkstyle violation (HasUnusedImport.java, Unused import) to be "
                    + "embedded in a task failure message")
                .isPresent();

            // the ProcessFailedException in the cause chain should carry the raw output
            final var pfe = causeOfType(checkstyleTaskFailure.get(), ProcessFailedException.class);
            assertThat(pfe)
                .as("expected a ProcessFailedException in the cause chain of the checkstyle failure")
                .isPresent();
            assertThat(pfe.get().output()).contains("HasUnusedImport.java").contains("Unused import");
        }
    }

    @Test
    @WorkspacePath("checkstyle-test-sources")
    void shouldIncludeTestSourceDirectoryWhenConfigured(final Engine engine, final Workspace workspace) {

        assertThat(workspace.name()).isEqualTo("checkstyle-test-sources");
        assertThat(workspace.getPlugin(Java25CompilerPlugin.class).isPresent()).isTrue();
        assertThat(workspace.getPlugin(Java25JUnitPlugin.class).isPresent()).isTrue();
        assertThat(workspace.getPlugin(CheckstylePlugin.class).isPresent()).isTrue();

        // create a Program to run Checkstyle on the Workspace
        final Program program = engine.createProgram(workspace, Task.Pattern.of("checkstyle"));

        // create a Task ExecutionCache for the Program
        final AssetCache cache = DefaultAssetCache.create();

        // establish a CompletingObserver to observe the checkstyle failure
        final CompletingSubscriber<Telemetry> observer = new CompletingSubscriber<>();
        final CompletableFuture<Telemetry> future = observer.when(t -> t instanceof Error);
        engine.subscribe(observer);

        try {
            program.execute(cache);

            fail("The Checkstyle check of the test source file should have failed");
        } catch (final ProgramExecutionException e) {
            Eventually.assertThat(future).isCompleted();

            // all task failures: thrown + suppressed
            final var allFailures = Stream.concat(Stream.of(e), Arrays.stream(e.getSuppressed()))
                .toList();

            // the fixture's only violation lives in src/test/java (main is clean) — this only
            // surfaces if CheckstylePlugin.check's @From(DetectSourcePaths.class) merged
            // Java25JUnitPlugin.DetectSourcePaths's TEST-kind paths in alongside the compiler's
            // MAIN-kind ones, driven by <includeTestSourceDirectory>true</includeTestSourceDirectory>
            final var checkstyleTaskFailure = allFailures.stream()
                .filter(t -> t.getMessage().contains("HasUnusedImportInTest.java")
                    && t.getMessage().contains("Unused import"))
                .findFirst();

            assertThat(checkstyleTaskFailure)
                .as("expected a Checkstyle violation (HasUnusedImportInTest.java, Unused import) to be "
                    + "embedded in a task failure message, proving TEST-kind source paths were merged in")
                .isPresent();
        }
    }

    @Test
    @WorkspacePath("external-generated-sources")
    void shouldCompileWithExternalGeneratedSources(final Engine engine, final Workspace workspace)
        throws Exception {

        assertThat(workspace.name()).isEqualTo("external-generated-sources");
        assertThat(workspace.getPlugin(Java25CompilerPlugin.class).isPresent()).isTrue();

        // simulate Maven-style generated sources from a prior build (target/generated-sources/<processor>/*).
        // Written here rather than checked in as a fixture file since target/ is gitignored repo-wide
        // (real generated-source output is never committed either) — SourcePathKind.EXTERNAL_GENERATED
        // detects this directory and Java25CompilerPlugin.Compile is expected to feed it into javac as
        // an ordinary source root.
        final Path generatedSourceDir = workspace.path().resolve("target/generated-sources/protobuf");
        Files.createDirectories(generatedSourceDir);
        Files.writeString(generatedSourceDir.resolve("Generated.java"), """
            public class Generated {
                public static final String VALUE = "generated";
            }
            """);

        // create a Program to compile the Workspace
        final Program program = engine.createProgram(workspace, Task.Pattern.of("compile"));

        // create a Task ExecutionCache for the Program
        final AssetCache cache = DefaultAssetCache.create();

        program.execute(cache);

        // Main.java references Generated.VALUE, so Main.class only exists if compilation succeeded,
        // and Generated.class only exists if the generated-sources directory was actually compiled
        // (rather than merely detected and then dropped) — together they confirm EXTERNAL_GENERATED
        // paths flowed all the way from SourcePathKind.detect() through the @From-injected
        // Map<SourcePathKind, PathSet> into the javac invocation.
        final Path targetPath = workspace.path().resolve(".build/main/target");
        assertThat(targetPath.resolve("Main.class")).exists();
        assertThat(targetPath.resolve("Generated.class"))
            .as("expected Generated.class compiled from target/generated-sources/protobuf")
            .exists();
    }

    @Test
    @WorkspacePath("copy-module-resources")
    void shouldCopyModuleResourcesWhenCompiling(final Engine engine, final Workspace workspace)
        throws Exception {

        assertThat(workspace.name()).isEqualTo("copy-module-resources");

        // create a Program to compile the Workspace - ResourcePlugin.CopyModuleResources is
        // @PreProcess(JavaCompilerPlugin.Compile.class), a codependency that's always executed
        // alongside compile, so this verifies src/main/resources/marker.txt is copied into the
        // build output when only "compile" is requested
        final Program program = engine.createProgram(workspace, Task.Pattern.of("compile"));

        final AssetCache cache = DefaultAssetCache.create();

        program.execute(cache);

        final Path targetPath = workspace.path().resolve(".build/main/target");
        assertThat(targetPath.resolve("Main.class")).exists();
        assertThat(targetPath.resolve("marker.txt"))
            .as("expected src/main/resources/marker.txt to be copied by CopyModuleResources before compile")
            .exists();
    }

    @Test
    @WorkspacePath("copy-junit-resources")
    void shouldCopyJUnitResourcesWhenTestCompiling(final Engine engine, final Workspace workspace)
        throws Exception {

        assertThat(workspace.name()).isEqualTo("copy-junit-resources");
        assertThat(workspace.getPlugin(Java25JUnitPlugin.class).isPresent()).isTrue();

        // create a Program to test-compile the Workspace - ResourcePlugin.CopyJUnitResources is
        // @PreProcess(JUnitPlugin.Compile.class) and has no other path (no @From, not @Automatic)
        // into the Program, so this only copies src/test/resources/marker.txt into the build output
        // if the @PreProcess relationship still causes it to be included when only "test-compile" is
        // requested
        final Program program = engine.createProgram(workspace, Task.Pattern.of("test-compile"));

        final AssetCache cache = DefaultAssetCache.create();

        program.execute(cache);

        final Path targetPath = workspace.path().resolve(".build/test/target");
        assertThat(targetPath.resolve("MainTest.class")).exists();
        assertThat(targetPath.resolve("marker.txt"))
            .as("expected src/test/resources/marker.txt to be copied by CopyJUnitResources before test-compile")
            .exists();
    }

    @Test
    @WorkspacePath("jlink-tainted")
    void shouldDumpCdsBaseArchiveAlongsideTaintedRootModule(final Engine engine, final Workspace workspace)
        throws Exception {

        // create a Program to build a jlink runtime image for the Workspace
        final Program program = engine.createProgram(workspace, Task.Pattern.of("jlink"), JlinkTargets.HOST_ONLY);

        // create a Task ExecutionCache for the Program
        final AssetCache cache = DefaultAssetCache.create();

        program.execute(cache);

        // jlink() builds one runtime image per staged target platform (including foreign targets,
        // for which dumpBaseCdsArchive is deliberately skipped) — locate the host's own image, since
        // that's the only one dumpBaseCdsArchive actually runs against
        final Path buildPath = workspace.path().resolve(".build");
        final Path packagePath = buildPath.resolve(workspace.name() + "-" + JavaPlatform.hostTarget());
        assertThat(packagePath).as("expected a host-target runtime image at [" + packagePath + "]").isDirectory();

        // the fixture's own root module requires javax.inject, a real automatic module (no
        // module-info.class) — jlink cannot link that into lib/modules, so it must have been left
        // on an external runtime --module-path instead of silently dropped
        final Path modulePath = packagePath.resolve("modules");
        assertThat(modulePath).isDirectory();
        try (var modules = Files.list(modulePath)) {
            assertThat(modules.anyMatch(p -> p.getFileName().toString().startsWith("javax.inject")))
                .as("expected the tainted javax.inject jar under [" + modulePath + "]")
                .isTrue();
        }

        // dumpBaseCdsArchive must have been able to resolve "-m app/app.Main" against that external
        // module-path (rather than failing with a module-not-found error) and produced a base archive
        assertThat(packagePath.resolve("lib/server/classes.jsa")).exists();

        // the generated launch script should point AutoCreateSharedArchive at app.jsa alongside it
        final Path script = packagePath.resolve("bin/jlink-tainted.sh");
        assertThat(script).exists();
        assertThat(Files.readString(script)).contains("-XX:+AutoCreateSharedArchive");
    }

    @Test
    @WorkspacePath("jlink-tainted")
    void shouldRelinkOverAnExistingRuntimeImage(final Engine engine, final Workspace workspace)
        throws Exception {

        // jlink refuses to run when --output already exists, so a second link of the same project
        // must clear the prior image first rather than failing with "directory already exists"
        engine.createProgram(workspace, Task.Pattern.of("jlink"), JlinkTargets.HOST_ONLY)
            .execute(DefaultAssetCache.create());

        final Path packagePath = workspace.path().resolve(".build")
            .resolve(workspace.name() + "-" + JavaPlatform.hostTarget());
        assertThat(packagePath).as("expected a host-target runtime image after the first link").isDirectory();

        // a fresh Program and AssetCache force the linker to actually rerun rather than being
        // cache-short-circuited
        engine.createProgram(workspace, Task.Pattern.of("jlink"), JlinkTargets.HOST_ONLY)
            .execute(DefaultAssetCache.create());

        assertThat(packagePath).as("expected the runtime image to be relinked in place").isDirectory();
        assertThat(packagePath.resolve("lib/server/classes.jsa")).exists();
        assertThat(packagePath.resolve("bin/jlink-tainted.sh")).exists();
    }

    @Test
    @WorkspacePath("jdeps")
    void getEarliestShouldReturnPresentOptional(final JavaPlatform platform) {
        assertThat(platform.getEarliest().isPresent()).as("getEarliest() should return a JDK but no JDKs were discovered").isTrue();
    }

    @Test
    @WorkspacePath("jdeps")
    void getLatestShouldReturnPresentOptional(final JavaPlatform platform) {
        assertThat(platform.getLatest().isPresent()).as("getLatest() should return a JDK but no JDKs were discovered").isTrue();
    }

    @Test
    @WorkspacePath("jdeps")
    void earliestVersionShouldBeLessThanOrEqualToLatestVersion(final JavaPlatform platform) {
        var earliest = platform.getEarliest().orElseThrow(() -> new AssertionError("getEarliest() returned empty"));
        var latest = platform.getLatest().orElseThrow(() -> new AssertionError("getLatest() returned empty"));
        assertThat(earliest.version().compareTo(latest.version()) <= 0)
            .as("expected getEarliest() [" + earliest.version().get() + "] <= getLatest() [" + latest.version().get() + "]")
            .isTrue();
    }

    /**
     * Attempt to locate and execute jdeps from the {@link JavaPlatform} using the {@link LocalMachine}.
     *
     * @param platform the {@link JavaPlatform}
     * @param machine the {@link LocalMachine}
     * @param repository the {@link MavenRepository}
     */
    @Test
    @WorkspacePath("jdeps")
    void shouldRunJDeps(final JavaPlatform platform,
                        final LocalMachine machine,
                        final MavenRepository repository,
                        final Workspace workspace)
        throws Exception {

        // TODO: we should be able to inject these?
        var catalog = workspace.resources()
            .filter(ModuleCatalog.class::isInstance)
            .map(ModuleCatalog.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected a ModuleCatalog resource in workspace [" + workspace.path() + "]"));

        var versioning = workspace.resources()
            .filter(ModuleVersioning.class::isInstance)
            .map(ModuleVersioning.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected a ModuleVersioning resource in workspace [" + workspace.path() + "]"));

        // -----
        // obtain the non-Java Platform artifacts transitively
        // (so we can put them on a classpath/modulepath for analysis)
        final var pending = new Stack<ModuleReference>();
        final var processed = new LinkedHashSet<ModuleReference>();

        final var artifactModuleDescriptors = new LinkedHashMap<Artifact, JDKModuleDescriptor>();
        final var artifactPaths = new LinkedHashMap<Artifact, Path>();

        var initial = ModuleReference.of("build.spawn.platform.local",
            Version.parse("0.1.0"));

        pending.push(initial);

        while (!pending.isEmpty()) {
            var reference = pending.pop();

            if (!processed.contains(reference)) {

                // obtain the artifact for the module reference
                var artifact = catalog.getArtifact(reference).orElseThrow(() -> new AssertionError("No artifact in catalog for module reference [" + reference + "]"));

                // resolve the path to the artifact
                if (!artifactPaths.containsKey(artifact)) {
                    var path = repository.resolve(artifact).orElseThrow(() -> new AssertionError("Failed to resolve path for artifact [" + artifact + "]"));
                    artifactPaths.put(artifact, path);
                }

                // obtain the ModuleDescriptor
                if (!artifactModuleDescriptors.containsKey(artifact)) {
                    var moduleDescriptor = repository.getModuleDescriptor(artifact, catalog, versioning).orElseThrow(() -> new AssertionError("Failed to get ModuleDescriptor for artifact [" + artifact + "]"));
                    artifactModuleDescriptors.put(artifact, moduleDescriptor);

                    processed.add(reference);

                    // push the non-Java Platform required modules onto the stack for processing
                    moduleDescriptor.requiresClauses()
                        .filter(r -> !JavaPlatform.isJavaPlatformModule(r.requiresModuleName().toString()))
                        .map(r -> ModuleReference.of(r.requiresModuleName().toString(),
                            JDKModuleDescriptor.requiresVersion(r)))
                        .filter(module -> !processed.contains(module))
                        .forEach(pending::push);
                }
            }
        }

        // -----

        // copy the modules into a module path that can be used
        var modulePath = workspace.path().resolve(".build/modules/");
        CleanPlugin.delete(modulePath);

        // create the module path
        Files.createDirectories(modulePath);

        // classify and de-conflict: named modules go to module-path; demoted/superseded go to classpath.
        // Same-module/different-version jars share identical package sets, so resolveConflicts's own
        // version-dedupe tier (grouping by base name, keeping the newest) already avoids the "two
        // versions of module X found" jdeps error without a separate hand-rolled dedupe pass here.
        final var allPaths = artifactPaths.values().stream().toList();
        final var resolution = ModuleGraphClassifier.resolveConflicts(allPaths, java.util.Set.of(), msg -> {});

        // a jar goes on the module path — intentionally named-only here because jdeps does not
        // resolve modules by filename-derived names the way javac does; plain unnamed jars on a
        // jdeps --module-path cause "module not found" errors at analysis time rather than silently
        // becoming automatic modules. ModuleGraphClassifier.classify promotes all non-conflicting
        // jars (including filename-derived automatics) for javac. Everything else — unnamed,
        // superseded, or demoted — belongs on the classpath instead, never both, so the same jar
        // never appears at two different paths and trips jdeps's split-package detection.
        final Predicate<Path> onModulePath = source -> ModuleGraphClassifier.isNamedModule(source)
            && !resolution.superseded().contains(source) && !resolution.demoted().contains(source);

        allPaths.stream()
            .filter(onModulePath)
            .forEach(source -> {
                try {
                    Files.copy(source, modulePath.resolve(source.getFileName()));
                }
                catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

        // -----

        // obtain the top-level dependency to analyze
        var dependencyPath = artifactPaths.values().stream().findFirst().orElseThrow(() -> new AssertionError("Expected at least one resolved artifact path but artifactPaths was empty"));

        // build the classpath PathSet (containing only the jars that didn't go on the module path)
        final var classPathBuilder = PathSetBuilder.create();
        allPaths.stream().filter(onModulePath.negate()).forEach(classPathBuilder::add);
        var classPathSet = classPathBuilder.build();
        var classPath = Streams.reverse(classPathSet.stream())
            .map(Path::toString)
            .map(Strings::doubleQuoteIfContainsWhiteSpace)
            .reduce("", (left, right) -> left.isEmpty() ? right : left + File.pathSeparator + right);

        // build the list of module names to be processed
        var moduleNames = processed.stream()
            .map(ModuleReference::name)
            .reduce("", (left, right) -> left.isEmpty() ? right : left + "," + right);

        var jdk = platform.getVersion(25).orElseThrow(() -> {
            final var jdks = platform.stream().toList();
            return new RuntimeException("Failed to obtain Java Development Kit for Java 25. Available Java Development Kits: " + jdks);
        });
        var javaHome = jdk.home().path();

        var jdepsPath = javaHome.resolve("bin/jdeps");

        var recordingObserver = new RecordingSubscriber<String>();
        var stdoutObserver = StandardOutputSubscriber.of(recordingObserver);

        // --class-path is omitted entirely when empty (e.g. every dependency landed on the
        // module path) — jdeps errors with "no value given for --class-path" on an empty value
        final List<Option> jdepsOptions = new ArrayList<>();
        jdepsOptions.add(Executable.of(jdepsPath.toString()));
        jdepsOptions.add(Name.of("jdeps"));
        jdepsOptions.add(Argument.of("--module-path"));
        jdepsOptions.add(Argument.of(modulePath));
        if (!classPath.isEmpty()) {
            jdepsOptions.add(Argument.of("--class-path"));
            jdepsOptions.add(Argument.of(classPath));
        }
        jdepsOptions.add(Argument.of("--list-deps"));
        jdepsOptions.add(Argument.of("--ignore-missing-deps"));
        jdepsOptions.add(Argument.of("--multi-release"));
        jdepsOptions.add(Argument.of(jdk.version().major()));
        jdepsOptions.add(Argument.of("--module"));
        jdepsOptions.add(Argument.of(initial.name()));
        jdepsOptions.add(Console.ofSystem());
        jdepsOptions.add(stdoutObserver);

        try (var jdeps = machine.launch(Application.class, jdepsOptions.toArray(Option[]::new))) {

            Eventually.assertThat(jdeps.onExit()).isCompleted();
            assertThat(jdeps.exitValue().orElseThrow(() -> new AssertionError("jdeps process has no exit value — it may not have terminated"))).isEqualTo(0);

            // build maps of java platform, module and non-module dependencies
            final LinkedHashSet<ModuleReference> javaPlatformModules = new LinkedHashSet<>();
            final LinkedHashSet<JDKModuleDescriptor> modules = new LinkedHashSet<>();
            final LinkedHashSet<JDKModuleDescriptor> nonModules = new LinkedHashSet<>();
            final LinkedHashMap<JDKModuleDescriptor, Artifact> artifacts = new LinkedHashMap<>();
            final LinkedHashSet<String> unknownModules = new LinkedHashSet<>();

            // a Consumer of Artifacts together with their ModuleDescriptors
            // (to collect and categorize the ModuleDescriptors)
            final Consumer<Map.Entry<Artifact, JDKModuleDescriptor>> consumeArtifact = entry -> {
                final Artifact artifact = entry.getKey();
                final JDKModuleDescriptor descriptor = entry.getValue();

                artifacts.put(descriptor, artifact);

                if (!descriptor.isAutomatic()) {
                    modules.add(descriptor);
                }
                else {
                    nonModules.add(descriptor);
                }
            };

            // determine a Version for the Java Development Kit Modules
            final Version jdkVersion = Version.parse(jdk.version().get());

            recordingObserver.items()
                .map(String::trim)
                .filter(line -> !line.contains(" "))
                .forEach(moduleName -> {
                    if (JavaPlatform.isJavaPlatformModule(moduleName)) {
                        final ModuleReference reference = ModuleReference.of(moduleName, jdkVersion);
                        javaPlatformModules.add(reference);
                    }
                    else {
                        artifactModuleDescriptors.entrySet().stream()
                            .filter(entry -> entry.getValue().moduleName().toString().equals(moduleName))
                            .findFirst()
                            .ifPresentOrElse(consumeArtifact, () -> unknownModules.add(moduleName));
                    }
                });

            // ensure all artifacts provided are consumed as either modules or non-modules
            artifactModuleDescriptors.entrySet().stream()
                .filter(entry -> !modules.contains(entry.getValue()) && !nonModules.contains(entry.getValue()))
                .forEach(consumeArtifact);

            System.out.printf("Java Dependencies Analysis for: %s\n\n", initial);

            System.out.printf("Java Platform Modules: @ %s\n", jdk.version().get());
            javaPlatformModules.stream()
                .forEach(reference -> System.out.printf("  %s\n", reference.name()));

            if (!modules.isEmpty()) {
                System.out.println("\nModules: ");
                modules.stream()
                    .forEach(descriptor -> System.out.printf("  %s @ %s (%s) [%s]\n",
                        descriptor.moduleName().toString(),
                        descriptor.version().map(Version::toString).orElse("(unknown version)"),
                        descriptor.isAutomatic() ? "automatic module" : "fully-blown module",
                        artifactPaths.get(artifacts.get(descriptor))));
            }

            if (!nonModules.isEmpty()) {
                System.out.println("\nNon-Modules: (for classpath)");
                nonModules.stream()
                    .forEach(descriptor -> System.out.printf("  %s @ %s [%s]\n",
                        descriptor.moduleName().toString(),
                        descriptor.version().map(Version::toString).orElse("(unknown version)"),
                        artifactPaths.get(artifacts.get(descriptor))));
            }

            if (!unknownModules.isEmpty()) {
                System.out.println("\nUnknown Modules: ");
                unknownModules.stream()
                    .forEach(name -> System.out.printf("  %s\n", name));
            }
        }
    }

    @Test
    @WorkspacePath("custom-task-with-module-info")
    @RequireJavaVersion("25")
    void shouldDetectCustomizationPluginFromSrcBuildJava(final Workspace workspace) {
        // the "custom-task-with-module-info" fixture has src/build/java/Build.java, so
        // CustomizationPlugin.MetaClass should activate it -- the plugin the rest of these tests exercise
        assertThat(workspace.name()).isEqualTo("custom-task-with-module-info");
        assertThat(workspace.getPlugin(CustomizationPlugin.class)).isPresent();
    }

    @Test
    @WorkspacePath("custom-task-with-module-info")
    @RequireJavaVersion("25")
    void shouldResolveExternalModuleOntoCustomizationCompileClasspath(
            final Engine engine, final Workspace workspace) {

        // CustomizationPlugin.getDependencies() runs the ModuleCatalog + Artifact.Resolver path for
        // every requires clause of the customization module-info that isn't a workspace sibling or
        // build.spin.engine. This is where two bugs lived:
        //   - the catalog ModuleReference was built with the raw (empty) requires version instead of
        //     the version pinned via version.properties, and
        //   - the resolved Path was pulled out of an Exceptional via ifPresent().orElseThrow(...),
        //     which threw even on a successful resolve (Exceptional#ifPresent returns empty() on
        //     success), so this method blew up before it could return anything at all.
        final CustomizationPlugin plugin = workspace.getPlugin(CustomizationPlugin.class)
            .orElseThrow(() -> new AssertionError("Expected a CustomizationPlugin for [" + workspace + "]"));

        final var codeModel = engine.framework().codeModel();
        final RequiresModuleDescriptor requiresAssertj = RequiresModuleDescriptor.of(codeModel,
            codeModel.getNameProvider().getModuleName("org.assertj.core").orElseThrow());

        final var resolved = plugin.getDependencies(Stream.of(requiresAssertj),
                codeModel.getNameProvider().getModuleName("build").orElseThrow())
            .build().stream()
            .toList();

        assertThat(resolved)
            .as("expected getDependencies to resolve org.assertj.core (pinned to 3.27.7 by "
                + "version.properties) to a real jar without throwing")
            .anyMatch(p -> p.getFileName().toString().equals("assertj-core-3.27.7.jar"));
    }

    @Test
    @WorkspacePath("custom-task-with-module-info")
    @RequireJavaVersion("25")
    void shouldCompileAndExecuteACustomBuildTaskWithAModuleInfo(final Engine engine, final Workspace workspace)
        throws Exception {

        deleteRecursively(workspace.path().resolve(".build"));

        // "greet" is the @Named task defined by src/build/java/Build.java. Reaching a successful
        // execution proves CustomizationPlugin compiled Build.java (against a classpath it resolved,
        // including the external org.assertj.core module, via a JavaPlatform-discovered javac rather
        // than JDK.current()), loaded the Build class, and dispatched the task.
        final Program program = engine.createProgram(workspace, Task.Pattern.of("greet"));
        program.execute(DefaultAssetCache.create());

        final Path marker = workspace.path().resolve(".build/greeting.txt");
        assertThat(marker)
            .as("expected the custom 'greet' task to have written its marker file")
            .exists();
        assertThat(Files.readString(marker)).isEqualTo("hello custom task");
    }

    @Test
    @WorkspacePath("custom-task-without-module-info")
    @RequireJavaVersion("25")
    void shouldCompileAndExecuteACustomBuildTaskWithoutAModuleInfo(final Engine engine, final Workspace workspace)
        throws Exception {

        deleteRecursively(workspace.path().resolve(".build"));

        // the "custom-task-without-module-info" fixture has src/build/java/Build.java but no
        // module-info.java, so CustomizationPlugin gets no hand-written requires clauses to resolve
        // -- it must compile Build.java against the Spin API (build.spin.Task, Project) and
        // jakarta.inject sourced from
        // Spin's own runtime. Under this modular test launch that comes via spinRuntimePath()'s
        // module-path branch; a jlink-image launch of Spin instead resolves them via javac --system
        // pointed at the image (spinRuntimeImage()).
        final Program program = engine.createProgram(workspace, Task.Pattern.of("greet"));
        program.execute(DefaultAssetCache.create());

        final Path marker = workspace.path().resolve(".build/greeting.txt");
        assertThat(marker)
            .as("expected the module-info-less custom 'greet' task to have written its marker file")
            .exists();
        assertThat(Files.readString(marker)).isEqualTo("hello custom task without a module-info");
    }
}
