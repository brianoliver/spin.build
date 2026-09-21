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
import build.base.foundation.Strings;
import build.base.io.PathSet;
import build.base.option.JDKVersion;
import build.base.telemetry.Activity;
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
import build.spin.Task;
import build.spin.common.JDKTools;
import build.spin.common.ProcessFailedException;
import build.spin.common.ProcessRunner;
import build.spin.common.task.SourcePathKind;
import build.spin.module.configuration.Configuration;
import build.spin.module.configuration.Source;
import build.spin.module.modulesystem.CompilationResolution;
import build.spin.module.modulesystem.JavadocArguments;
import build.spin.module.modulesystem.ModuleVersioning;
import build.spin.option.Verbose;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An abstract {@link Task} that compiles and produces Java Documentation for the Java Source Code in a {@link Project}
 * using the Java Platform {@code javadoc} command.
 *
 * @author brian.oliver
 * @since Mar-2021
 */
@Source("build.spin.module.javadoc")
public abstract class AbstractJavaDoc
    implements JavaCompilerPlugin.JavaDoc {

    private static final String JAVADOC_PATH = SourcePathKind.MAIN.outputPrefix().orElseThrow() + "javadoc";

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
    private JDKVersion javaVersion;

    @Inject
    private Verbose verbose;

    @Inject
    @Configuration
    @Named("verbose")
    private Optional<Boolean> verboseOverride;

    @Inject
    @Configuration
    @Named("author")
    private Optional<Boolean> author;

    @Inject
    @Configuration
    @Named("version")
    private Optional<Boolean> showVersion;

    @Inject
    @Configuration
    @Named("nodeprecated")
    private Optional<Boolean> nodeprecated;

    @Inject
    @Configuration
    @Named("enable-preview")
    private Optional<Boolean> enablePreview;

    /**
     * Compiles and produces Java Documentation from the source code in the provided {@link PathSet}, outputting it
     * into the specified build {@link Path}.
     *
     * @param sourceCode the source code
     * @param resolution the {@link CompilationResolution} (module-path and classpath)
     * @param buildPath the build {@link Path} (.build)
     * @param javaPlatformURL the {@link Optional} {@link URL} for the external Java Development Kit documentation
     * @return the {@link Path} containing the generated documentation
     * @throws Exception should documentation fail
     */
    protected Path javadoc(final PathSet sourceCode,
                           final CompilationResolution resolution,
                           final Path buildPath,
                           final Optional<URL> javaPlatformURL)
        throws Exception {

        final ModulePath modulePath = ModulePath.of(resolution.modulePath().stream());
        final ClassPath classPath = ClassPath.of(resolution.classPath().stream());

        if (sourceCode.isEmpty()) {
            this.recorder.diagnostic("Skipping javadoc for [%s]: no source files", this.project.path());
            return buildPath.resolve(JAVADOC_PATH);
        }

        if (sourceCode.stream().noneMatch(this::hasPackageDeclaration)) {
            this.recorder.diagnostic("Skipping javadoc for [%s]: no source files contain a package declaration", this.project.path());
            return buildPath.resolve(JAVADOC_PATH);
        }

        // the path in which to place the javadoc
        final Path targetPath = buildPath.resolve("main/javadoc");

        // determine the version of the Module being documented (or use a default version)
        final Version version = this.versioning
            .getVersion(this.moduleDescriptor.moduleName().toString())
            .orElse(ModuleVersioning.DEFAULT_VERSION);

        final Activity documentation = this.recorder
            .commence("Generating Documentation %d file(s) for [%s]", sourceCode.size(), this.project.path());

        // create the target path for the compiled classes
        try {
            Files.createDirectories(targetPath);
        }
        catch (final IOException e) {
            documentation.completeExceptionally(e);
            throw new RuntimeException("Failed to create documentation target [" + targetPath + "]", e);
        }

        // --enable-preview: honors an explicit .spin/ config value, otherwise off. Never forced
        // unconditionally -- that would both reject projects pinned to an older --release and
        // enable preview-feature warnings/behavior for projects that never asked for it. Unlike
        // AbstractCompile, there's no pom-declared fallback here: maven-javadoc-plugin has no
        // <enablePreview> parameter of its own (a pom spells it out via <additionalJOptions>
        // instead, passed through verbatim below).
        final boolean previewEnabled = this.enablePreview.orElse(false);

        // create an "argument" file for "javadoc"
        // include the version number in the arguments file name
        // (so we can tell the arguments being used to compile with this plugin)
        final Path arguments = buildPath.resolve("arguments-javadoc-" + this.javaVersion.major());
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(arguments))) {
            // include the "javadoc" options

            // include the output path for documentation
            writer.println("-d " + Strings.doubleQuoteIfContainsWhiteSpace(targetPath.toString()));

            // include -verbose (for debugging); a configured "verbose" value overrides the CLI Verbose option
            if (this.verboseOverride.orElse(this.verbose == Verbose.ENABLED)) {
                writer.println("-verbose");
            }
            else {
                writer.println("-quiet");
            }

            // include the common javadoc toggles, when configured
            if (this.author.orElse(false)) {
                writer.println("-author");
            }
            if (this.showVersion.orElse(false)) {
                writer.println("-version");
            }
            if (this.nodeprecated.orElse(false)) {
                writer.println("-nodeprecated");
            }

            // pin the release; --release and --enable-preview are Java 9+ only
            if (this.javaVersion.isModular()) {
                writer.println("--release " + this.javaVersion.major());
                if (previewEnabled) {
                    writer.println("--enable-preview");
                }
            } else {
                writer.println("-source " + this.javaVersion.major());
            }

            // include the module-path and classpath from the detection tasks
            // --module-path is Java 9+; for Java 8 fold everything into -classpath
            if (this.javaVersion.isModular()) {
                if (!modulePath.isEmpty()) {
                    final String mp = modulePath.stream()
                        .map(Path::toString)
                        .collect(Collectors.joining(File.pathSeparator));
                    writer.println("--module-path " + Strings.doubleQuoteIfContainsWhiteSpace(mp));
                }
                if (!classPath.isEmpty()) {
                    final String cp = classPath.stream()
                        .map(Path::toString)
                        .collect(Collectors.joining(File.pathSeparator));
                    writer.println("-classpath " + Strings.doubleQuoteIfContainsWhiteSpace(cp));
                }
            } else {
                final String cp = Stream.concat(modulePath.stream(), classPath.stream())
                    .map(Path::toString)
                    .collect(Collectors.joining(File.pathSeparator));
                if (!cp.isEmpty()) {
                    writer.println("-classpath " + Strings.doubleQuoteIfContainsWhiteSpace(cp));
                }
            }

            // include the -link(s) to external documentation
            // TODO: we should get this from an external "catalog"

            // output the version of Java we're using (only if the URL is reachable)
            // connect() only establishes TCP; we must read from the stream to confirm
            // HTTP-level reachability before passing the URL to javadoc
            javaPlatformURL.ifPresent(url -> {
                try {
                    final var connection = url.openConnection();
                    connection.setConnectTimeout(3000);
                    connection.setReadTimeout(3000);
                    try (var stream = connection.getInputStream()) {
                        stream.read();
                    }
                    writer.println("-link " + url);
                }
                catch (final java.io.IOException e) {
                    this.recorder.diagnostic("Skipping external documentation link for [%s]: %s", url, e.getMessage());
                }
            });

            // output links to each of the "external" projects
            // TODO: https://javadoc.io/doc/<groupId>/<artifactId>/<version>

            // include any project-declared javadoc args (e.g. --release N, --enable-preview,
            // <additionalOptions> from maven-javadoc-plugin). Resource is workspace-scoped
            // and resolves the per-project effective pom.
            this.project.findResource(JavadocArguments.class).ifPresent(args ->
                args.get(this.project).forEach(writer::println));

            // lastly include the source code to document
            sourceCode.stream()
                .peek(path -> this.recorder.diagnostic("Preparing [%s] for documentation", path))
                .forEach(writer::println);
        }

        // establish the "javadoc" executable based on the Java Development Kit
        final JDKHome javaHome = this.javaDevelopmentKit.home();

        // launch "javadoc"
        final ErrorCapture captured = new ErrorCapture();
        final AtomicBoolean inWarning = new AtomicBoolean();
        final ConfigurationBuilder javadocConfiguration = ConfigurationBuilder.create()
            .add(JDKTools.executable(javaHome.path(), "javadoc"))
            .add(javaHome)
            .add(Name.of("javadoc " + this.javaDevelopmentKit.version().toString()))
            .add(Argument.of("@" + Strings.doubleQuoteIfContainsWhiteSpace(arguments.toString())))
            .add(StandardErrorSubscriber.of(line -> {
                if (line.contains(": error:")) {
                    inWarning.set(false);
                    this.recorder.error(line);
                    captured.append(line);
                } else if (line.contains(": warning:")) {
                    inWarning.set(true);
                    this.recorder.warn(line);
                } else if (inWarning.get()) {
                    this.recorder.warn(line);
                } else {
                    this.recorder.error(line);
                    captured.append(line);
                }
            }));

        try (Application javadoc = this.machine.launch(Application.class, javadocConfiguration)) {

            ProcessRunner.await(javadoc, "Documentation Generation", captured::output);

            documentation.complete();
        } catch (final ProcessFailedException e) {
            documentation.completeExceptionally(e);
            throw e;
        }

        return targetPath;
    }

    private boolean hasPackageDeclaration(final Path sourceFile) {
        try (var lines = Files.lines(sourceFile)) {
            return lines.anyMatch(line -> line.stripLeading().startsWith("package "));
        } catch (final IOException e) {
            return false;
        }
    }
}
