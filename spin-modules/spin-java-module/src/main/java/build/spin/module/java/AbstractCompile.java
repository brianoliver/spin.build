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

import build.base.configuration.ConfigurationBuilder;
import build.base.foundation.Capture;
import build.base.foundation.Strings;
import build.base.io.PathSet;
import build.base.io.PathSetBuilder;
import build.base.option.JDKVersion;
import build.base.telemetry.Activity;
import build.base.telemetry.Meter;
import build.base.telemetry.TelemetryRecorder;
import build.base.version.Version;
import build.codemodel.jdk.descriptor.JDKModuleDescriptor;
import build.spawn.application.Application;
import build.spawn.application.option.Argument;
import build.spawn.application.option.Name;
import build.spawn.application.option.StandardErrorSubscriber;
import build.spawn.jdk.JDK;
import build.spawn.jdk.option.ClassPath;
import build.spawn.jdk.option.JDKHome;
import build.spawn.jdk.option.ModulePath;
import build.spawn.platform.local.LocalMachine;
import build.spin.Project;
import build.spin.Reference;
import build.spin.Task;
import build.spin.annotation.System;
import build.spin.common.JDKTools;
import build.spin.common.ProcessFailedException;
import build.spin.common.ProcessRunner;
import build.spin.common.reactive.ConditionalConsumingObserver;
import build.spin.common.task.SourcePathKind;
import build.spin.module.configuration.Configuration;
import build.spin.module.configuration.Source;
import build.spin.module.modulesystem.Artifact;
import build.spin.module.modulesystem.CompilationResolution;
import build.spin.module.modulesystem.CompilerArguments;
import build.spin.module.modulesystem.ModuleCatalog;
import build.spin.module.modulesystem.ModuleVersioning;
import build.spin.option.BuildDirectoryName;
import build.spin.option.ReuseExternalBuildOutput;
import build.spin.option.TargetDirectoryName;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static build.spin.module.clean.CleanPlugin.delete;

/**
 * An abstract {@link Task} to compile Java Source Code using the Java Platform {@code javac} command.
 *
 * @author brian.oliver
 * @since Oct-2019
 */
@Source(AbstractCompile.CONFIGURATION_SOURCE)
public abstract class AbstractCompile
    implements Task<PathSet> {

    static final String CONFIGURATION_SOURCE = "build.spin.module.compile";

    private static final String GENERATED_SOURCES_PATH =
        SourcePathKind.MAIN.outputPrefix().orElseThrow() + "generated-sources";

    @Inject
    private TelemetryRecorder recorder;

    @Inject
    private LocalMachine machine;

    @Inject
    private Project project;

    @Inject
    private JDK javaDevelopmentKit;

    @Inject
    private JDKModuleDescriptor moduleDescriptor;

    @Inject
    private ModuleVersioning versioning;

    @Inject
    private ModuleCatalog catalog;

    @Inject
    private Artifact.Resolver resolver;

    @Inject
    @System
    private JDKVersion systemJavaVersion;

    @Inject
    private JDKVersion javaVersion;

    @Inject
    private BuildDirectoryName buildDirectoryName;

    @Inject
    private ReuseExternalBuildOutput reuseExternalBuildOutput;

    /**
     * The {@link SourcePathKind} this compile is for — {@link SourcePathKind#MAIN} for the ordinary
     * Java compiler plugins, {@link SourcePathKind#TEST} for the JUnit plugins' test-compile, provided
     * by the enclosing {@code Plugin} (see {@code AbstractJavaPlugin#sourceScope}). Determines which
     * scope's already-built output the short-circuit below looks for — a test compile must never be
     * satisfied by the project's already-built MAIN output.
     */
    @Inject
    private SourcePathKind scope;

    @Inject
    private TargetDirectoryName targetDirectoryName;

    @Inject
    @Configuration
    @Named("enable-preview")
    private Optional<Boolean> enablePreviewOverride;

    private String buildProcessorModulePath() {
        return AnnotationProcessorPaths.build(
            this.moduleDescriptor, this.project,
            this.catalog, this.versioning, this.resolver,
            this.buildDirectoryName, this.targetDirectoryName,
            this.javaDevelopmentKit);
    }

    /**
     * Determines whether an annotation processor is active for this compile.
     *
     * <p>Generated-source directories detected under Maven's conventional {@code generated-sources/*}
     * layout cannot be reliably distinguished by name or location alone: they may hold output from an
     * unrelated external tool (protobuf, ANTLR), or they may hold output from the very annotation
     * processor active in this compile (e.g. a prior Maven build's run of the same processor). In the
     * latter case, feeding that directory back in as an explicit source root while the processor is
     * also active causes it to attempt to recreate a file it already owns
     * ({@code FilerException: Attempt to recreate a file}). Merging externally-detected sources is
     * therefore only safe when no annotation processor is active for this compile.
     *
     * @return {@code true} if at least one annotation processor module is on the processor path
     */
    protected boolean hasAnnotationProcessors() {
        return !buildProcessorModulePath().isEmpty();
    }

    @Override
    public Stream<Reference> dependencies() {
        // locate projects in the Workspace that this project requires, forcing the sibling's own
        // JavaCompilerPlugin.Compile task when it isn't already built (shared with AbstractDetectResolution,
        // which forces the same siblings for the same reason)
        final Stream<Reference> requiresDeps = AbstractDetectResolution.siblingCompileDependencies(
            this.project, this.moduleDescriptor, this.buildDirectoryName.get(), this.targetDirectoryName.get(),
            this.reuseExternalBuildOutput);

        // also depend on any workspace annotation processor modules so they are compiled before us
        final Stream<Reference> processorDeps = AnnotationProcessorPaths
            .annotationProcessorProjects(this.moduleDescriptor, this.project)
            .flatMap(prj -> prj.plugins(JavaCompilerPlugin.class)
                .findFirst()
                .flatMap(plugin -> AbstractDetectResolution.crossProjectDeps(prj, plugin))
                .stream());

        return Stream.concat(requiresDeps, processorDeps);
    }

    /**
     * Compiles the source code in the provided {@link PathSet} into the specified build {@link Path}.
     *
     * @param sourceCode the source code
     * @param resolution the {@link CompilationResolution} (module-path and classpath)
     * @param buildPath  the build {@link Path} (.build)
     * @param targetPath the path in which to place the compiled classes
     * @return the {@link PathSet} containing the compiled classes
     * @throws Exception should compilation fail
     */
    protected PathSet compile(final PathSet sourceCode,
                              final CompilationResolution resolution,
                              final Path buildPath,
                              final Path targetPath)
        throws Exception {

        return compile(sourceCode, PathSet.empty(), resolution, buildPath, targetPath);
    }

    /**
     * Compiles the source code in the provided {@link PathSet} into the specified build {@link Path},
     * merging in externally-generated sources (e.g. protobuf, ANTLR) from a prior build when no
     * annotation processor is active for this compile.
     *
     * @param sourceCode the source code
     * @param externalGeneratedSources generated source root directories from a prior build; merged
     *                                 into {@code sourceCode} only when {@link #hasAnnotationProcessors()}
     *                                 is {@code false}
     * @param resolution the {@link CompilationResolution} (module-path and classpath)
     * @param buildPath  the build {@link Path} (.build)
     * @param targetPath the path in which to place the compiled classes
     * @return the {@link PathSet} containing the compiled classes -- an already-valid build from
     *         another tool (or a prior compile of this project) reused as-is, without invoking
     *         {@code javac}, when it's at least as new as every file in {@code sourceCode}
     * @throws Exception should compilation fail
     */
    protected PathSet compile(final PathSet sourceCode,
                              final PathSet externalGeneratedSources,
                              final CompilationResolution resolution,
                              final Path buildPath,
                              final Path targetPath)
        throws Exception {

        // resolved once and reused for both the merge decision below and the -processorpath arg
        final String processorModulePath = buildProcessorModulePath();

        final PathSet effectiveSourceCode;
        if (externalGeneratedSources.isEmpty() || !processorModulePath.isEmpty()) {
            effectiveSourceCode = sourceCode;
        } else {
            final PathSetBuilder mergedBuilder = PathSetBuilder.create();
            mergedBuilder.addAll(sourceCode.stream());
            mergedBuilder.addAll(externalGeneratedSources.stream());
            effectiveSourceCode = mergedBuilder.build();
        }

        if (effectiveSourceCode.isEmpty()) {
            this.recorder.diagnostic("Skipping compile: no source files");
            return emptySourceResult(targetPath);
        }

        // compilation output location varies depending on whether the plugin is
        // using the system provided version of java
        final boolean isDefaultJavaVersion = this.javaVersion.major() == this.systemJavaVersion.major();

        // reuse an already-valid build from another tool (Maven's target/classes especially) or a
        // prior spin compile of this same project, rather than unconditionally re-invoking javac —
        // a genuine perf win, and it also closes the spin1-build-spin2 module-not-found race: a
        // sibling whose Compile task reuses valid output never writes a fresh, momentarily-incomplete
        // directory for a concurrently-running AbstractDetectResolution to observe mid-write.
        //
        // For a multi-release project, a non-default-version variant's compile writes only into
        // targetPath/META-INF/versions/N, leaving targetPath's own top-level output untouched -- so
        // the check below must look at the exact sub-path *this* variant would itself produce, not
        // just "does the shared project-level output directory have classes anywhere in it". Without
        // that, the default-version variant would see the versions/N subfolder a sibling variant just
        // wrote and wrongly conclude its own (never-yet-written) top-level output already exists.
        // resolveCompiledOutput's own "non-empty directory" check is not enough here either:
        // CopyResources writes into this exact same directory independently of Compile, so a
        // directory that only has resources in it (Compile hasn't run yet) is non-empty but contains
        // no compiled output at all -- containsCompiledClasses guards against mistaking that for
        // "already built".
        final String variantRelativeOutput = isDefaultJavaVersion
            ? null
            : "META-INF/versions/" + this.javaVersion.major();

        final Optional<Path> existingOutput = AbstractDetectResolution.resolveCompiledOutput(
                this.project.path(), this.buildDirectoryName.get(), this.targetDirectoryName.get(), this.scope,
                this.reuseExternalBuildOutput)
            .map(dir -> variantRelativeOutput == null ? dir : dir.resolve(variantRelativeOutput))
            .filter(Files::isDirectory);

        if (existingOutput.isPresent()
            && AbstractDetectResolution.containsCompiledClasses(existingOutput.get(), isDefaultJavaVersion)
            && AbstractDetectResolution.isUpToDate(existingOutput.get(), effectiveSourceCode)) {
            this.recorder.diagnostic(
                "Skipping compile for [%s]: existing output at [%s] is up to date with source",
                this.project.path(), existingOutput.get());
            return PathSetBuilder.create(existingOutput.get()).build();
        }

        final ModulePath modulePath = ModulePath.of(resolution.modulePath().stream());
        final ClassPath classPath = ClassPath.of(resolution.classPath().stream());

        // determine the version of the Module being compiled (or use the system provided version)
        final Version version = this.versioning
            .getVersion(this.moduleDescriptor.moduleName().toString())
            .orElse(ModuleVersioning.DEFAULT_VERSION);

        final Activity compilation = this.recorder
            .commence("Compiling %d file(s) as [%s]", effectiveSourceCode.size(), version);

        // determine the target location for the compiles classes based on the JDKVersion
        final Path target;

        if (isDefaultJavaVersion) {
            // when the system provided JDKVersion and the JDKVersion for the Plugin are the same,
            // we use the specified targetPath as the target
            target = targetPath;
        } else {
            // otherwise place the compiled classes in a target folder for the major version of java
            target = targetPath.resolveSibling(targetPath.getFileName() + "-" + this.javaVersion.major() + "/");
        }

        // create the target path for the compiled classes
        try {
            Files.createDirectories(target);
        } catch (final IOException e) {
            throw new RuntimeException("Failed to create compilation target [" + target + "]", e);
        }

        // when this project isn't using the system version of java, augment the classpath
        // with the default classes folder so previously compiled classes are available
        final ClassPath compilationClassPath;
        if (this.javaVersion.major() != this.systemJavaVersion.major()) {
            final PathSetBuilder builder = PathSetBuilder.create();
            builder.addAll(classPath.stream());
            builder.add(targetPath);
            compilationClassPath = ClassPath.of(builder.build().stream());
        } else {
            compilationClassPath = classPath;
        }

        // create an "argument" file for "javac"
        // include the version number in the arguments file name
        // (so we can tell the arguments being used to compile with this plugin)
        // a project's own maven-compiler-plugin config (<release>, <compilerArgs>, ...) always
        // wins over / augments the compiler plugin's own javaVersion -- fetch it up front so it
        // can be appended verbatim below.
        final Optional<CompilerArguments> compilerArguments = this.project.findResource(CompilerArguments.class);
        final var projectCompilerArgs = compilerArguments
            .map(args -> args.get(this.project).toList())
            .orElse(List.of());

        // --enable-preview: an explicit .spin/ config value always wins; otherwise fall back to the
        // project's own pom-declared <enablePreview>; otherwise off. Never forced unconditionally --
        // that would both reject projects pinned to an older --release and enable preview-feature
        // warnings/behavior for projects that never asked for it.
        final boolean enablePreview = this.enablePreviewOverride
            .or(() -> compilerArguments.flatMap(args -> args.enablePreview(this.project)))
            .orElse(false);

        final Path arguments = buildPath.resolve("arguments-" + this.javaVersion.major());
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(arguments))) {
            // include the "javac" options

            // include the output path for compiled classes
            writer.println("-d " + Strings.doubleQuoteIfContainsWhiteSpace(target.toString()));

            // preserve method/constructor parameter names in bytecode for reflection
            writer.println("-parameters");

            // include -verbose (for debugging)
            writer.println("-verbose");

            // pin the release; --release and --enable-preview are Java 9+ only
            if (this.javaVersion.isModular()) {
                writer.println("--release " + this.javaVersion.major());
                if (enablePreview) {
                    writer.println("--enable-preview");
                }
            } else {
                writer.println("-source " + this.javaVersion.major());
                writer.println("-target " + this.javaVersion.major());
            }

            // when the source set contains a module-info.java the compilation is named-module mode:
            // use the module path and classpath as classified by the detection tasks.
            // when there is no module-info.java the sources belong to the unnamed module and JPMS
            // split-package enforcement would reject packages that already exist in a named module
            // (e.g. test sources in build.base.foundation while base.foundation is on the module
            // path).  In that case collapse everything onto the classpath so the unnamed module can
            // see all dependencies without JPMS boundaries.
            final boolean hasModuleInfo = effectiveSourceCode.stream()
                .anyMatch(p -> "module-info.java".equals(p.getFileName().toString()));

            if (hasModuleInfo) {
                writer.println("--module-version " + version);
                if (!modulePath.isEmpty()) {
                    this.recorder.diagnostic("Module Path (%d entries)", modulePath.size());
                    writer.println("--module-path " + Strings.doubleQuoteIfContainsWhiteSpace(
                        modulePath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator))));
                }
                if (!compilationClassPath.isEmpty()) {
                    this.recorder.diagnostic("Class Path (%d entries)", compilationClassPath.size());
                    writer.println("-classpath " + Strings.doubleQuoteIfContainsWhiteSpace(
                        compilationClassPath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator))));
                }
            } else {
                // unnamed-module sources: merge module path + classpath into one flat classpath
                final ClassPath flatClassPath = ClassPath.of(
                    Stream.concat(modulePath.stream(), compilationClassPath.stream()));
                if (!flatClassPath.isEmpty()) {
                    this.recorder.diagnostic("Class Path [unnamed-module] (%d entries)", flatClassPath.size());
                    writer.println("-classpath " + Strings.doubleQuoteIfContainsWhiteSpace(
                        flatClassPath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator))));
                }
            }

            // add annotation processor modules (+ their full transitive dep closure) to the processor path
            // --processor-module-path is Java 9+; Java 8 uses -processorpath
            if (!processorModulePath.isEmpty()) {
                final String processorFlag = this.javaVersion.isModular() ? "--processor-module-path" : "-processorpath";
                writer.println(processorFlag + " "
                    + Strings.doubleQuoteIfContainsWhiteSpace(processorModulePath));

                // direct generated source files to a predictable directory so javadoc can find them
                final Path generatedSources = buildPath.resolve(GENERATED_SOURCES_PATH);
                try {
                    Files.createDirectories(generatedSources);
                } catch (final IOException e) {
                    throw new RuntimeException("Failed to create generated-sources directory [" + generatedSources + "]", e);
                }
                writer.println("-s " + Strings.doubleQuoteIfContainsWhiteSpace(generatedSources.toString()));
            }

            // include any project-declared javac args (e.g. --release N, <compilerArgs> from
            // maven-compiler-plugin), fetched above.
            projectCompilerArgs.forEach(writer::println);

            // lastly include the source code to compile
            effectiveSourceCode.stream()
                .peek(path -> this.recorder.diagnostic(
                    "Preparing [%s] for compilation", this.project.path().relativize(path)))
                .forEach(writer::println);
        }

        // establish the "javac" executable based on the Java Development Kit
        final JDKHome javaHome = this.javaDevelopmentKit.home();

        // establish the StandardOutputObserver to observe and translate "javac" verbose output into Telemetry
        // a Capture for the last error message
        final Capture<String> error = Capture.empty();
        final ErrorCapture captured = new ErrorCapture();

        final ConditionalConsumingObserver<String> observer =
            buildOutputObserver(this.recorder, error, captured, this::flushError);

        // the "javac" arguments — shared by both the in-process and forked launch paths below, so
        // argument-building never diverges between them
        final ConfigurationBuilder javacConfiguration = ConfigurationBuilder.create()
            .add(Argument.of("@" + Strings.doubleQuoteIfContainsWhiteSpace(arguments.toString())));

        final int exitCode;

        if (isDefaultJavaVersion && JDKTools.canRunInProcess(this.javaDevelopmentKit)) {
            // the resolved JDK is the exact installation running this Spin process, so "javac" can run
            // in-process via ToolProvider instead of forking a whole child JVM
            exitCode = JDKTools.runInProcess("javac", observer, javacConfiguration);

            // flush any error lines that were not followed by a subsequent "[" line
            flushError(error, captured);
        } else {
            // launch "javac"
            javacConfiguration
                .add(JDKTools.executable(javaHome.path(), "javac"))
                .add(javaHome)
                .add(Name.of("javac " + this.javaDevelopmentKit.version().toString()))
                .add(StandardErrorSubscriber.of(observer));

            try (Application javac = this.machine.launch(Application.class, javacConfiguration)) {
                try {
                    ProcessRunner.await(javac, "Compilation", () -> {
                        // failure path: flush any trailing error line before it is attached to the exception
                        flushError(error, captured);
                        return captured.output();
                    });
                } catch (final ProcessFailedException e) {
                    compilation.completeExceptionally(e);
                    throw e;
                }

                // success path: the supplier above is never invoked, so flush the trailing error line here
                flushError(error, captured);
            }

            // await() throws on a failed wait or a non-zero exit, so reaching here means success
            exitCode = 0;
        }

        if (exitCode != 0) {
            final ProcessFailedException exception =
                new ProcessFailedException("Compilation Failed (exit code: " + exitCode + ")",
                    captured.output());
            compilation.completeExceptionally(exception);
            throw exception;
        }

        compilation.complete();

        // move the compiled classes into the appropriate location based on the version of java used to compile them
        final Path path;

        if (isDefaultJavaVersion) {
            path = target;
        } else {
            // when this java version is not the system version, move the compiled classes into the appropriate
            // versions folder
            final Path versions = targetPath.resolve("META-INF/versions/");

            try {
                Files.createDirectories(versions);
            } catch (final IOException e) {
                throw new RuntimeException("Failed to create [" + versions + "]", e);
            }

            path = versions.resolve(this.javaVersion.major() + "/");

            // FUTURE: here we must remove the folder before the move, otherwise the move will fail
            // however this may disrupt conditional compilation in the future
            delete(path);

            Files.move(target, path, StandardCopyOption.REPLACE_EXISTING);
        }

        return PathSetBuilder.create(path).build();
    }

    static PathSet emptySourceResult(final Path targetPath) throws IOException {
        Files.createDirectories(targetPath);
        return PathSetBuilder.create(targetPath).build();
    }

    void flushError(final Capture<String> error, final ErrorCapture captured) {
        flushError(error, captured, this.project.path(), this.recorder);
    }

    // package-private and static so it can be unit-tested without standing up an AbstractCompile;
    // onFlush is invoked instead of calling flushError directly so the "[" branch below exercises
    // the caller's own error-relativizing/warn-vs-capture logic rather than a hardcoded one.
    static ConditionalConsumingObserver<String> buildOutputObserver(final TelemetryRecorder recorder,
                                                                    final Capture<String> error,
                                                                    final ErrorCapture captured,
                                                                    final BiConsumer<Capture<String>, ErrorCapture> onFlush) {

        final AtomicInteger parseCount = new AtomicInteger(0);
        final AtomicInteger checkingCount = new AtomicInteger(0);

        final Capture<Meter> compiling = Capture.empty();
        final Capture<Activity> parsing = Capture.empty();

        final String parsingPrefix = "[parsing started";
        final String compilingPrefix = "[checking ";

        return ConditionalConsumingObserver.Builder.<String>create()
            .with(string -> string.startsWith(parsingPrefix), __ -> {
                if (parseCount.getAndIncrement() == 0) {
                    parsing.set(recorder.commence("Parsing"));
                }
            })
            .with(string -> string.startsWith(compilingPrefix), string -> {
                if (checkingCount.getAndIncrement() == 0) {
                    parsing.ifPresent(Activity::complete);
                    compiling.set(recorder.commence(parseCount.get(), "Checking"));
                } else {
                    compiling.ifPresent(meter ->
                        meter.progress("Checking [%s]",
                            string.substring(compilingPrefix.length(), string.length() - 1)));
                }
            })
            .with(string -> string.startsWith("[total"), __ -> {
                parsing.ifPresent(Activity::complete);
                compiling.ifPresent(Meter::complete);
            })
            .with(string -> !string.startsWith("["), string -> {
                // capture the error
                if (error.isPresent()) {
                    error.set(error.get() + "\n" + string);
                } else {
                    error.set(string);
                }
            })
            .with(string -> string.startsWith("["), string -> {
                onFlush.accept(error, captured);
            })
            .build();
    }

    // package-private and static so it can be unit-tested without standing up an AbstractCompile
    static void flushError(final Capture<String> error, final ErrorCapture captured,
                           final Path projectRoot, final TelemetryRecorder recorder) {
        error.ifPresent(e -> {
            // javac reports source paths absolutely; strip the project root so messages read
            // relative to it (eg: "src/main/java/...") instead of the full filesystem path
            final String relativized = e.replace(projectRoot.toString() + File.separator, "");
            if (ErrorCapture.isJavacWarning(relativized)) {
                recorder.warn(relativized);
            } else {
                // captured only, not streamed live via recorder.error() - it's embedded verbatim in
                // the eventual ProgramExecutionException message (see DefaultProgram#runTask), so
                // streaming it here too would print every javac diagnostic twice: once as it's
                // captured, and again in the final failure report. CheckstylePlugin.Checkstyle#check
                // follows the same capture-only pattern for violations, for the same reason.
                captured.append(relativized);
            }
        });
        error.clear();
    }

}
