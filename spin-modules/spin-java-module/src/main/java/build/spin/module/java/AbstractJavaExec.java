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
import build.base.io.PathSet;
import build.base.option.JDKVersion;
import build.base.option.WorkingDirectory;
import build.base.telemetry.TelemetryRecorder;
import build.codemodel.jdk.descriptor.JDKModuleDescriptor;
import build.spawn.application.Application;
import build.spawn.application.Console;
import build.spawn.application.option.Argument;
import build.spawn.application.option.Name;
import build.spawn.platform.local.LocalMachine;
import build.spin.Project;
import build.spin.Task;
import build.spin.annotation.System;
import build.spin.common.JDKTools;
import build.spin.common.ProcessRunner;
import build.spin.module.configuration.Source;
import build.spin.module.modulesystem.CompilationResolution;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An abstract {@link Task} that runs a {@link Project}'s own compiled application directly, forked
 * as {@code java --module-path ... [-cp ...] -m rootModule/mainClass} for a project with a real
 * {@code module-info.java}, or plain {@code java -cp ... mainClass} otherwise -- see {@link #exec}.
 *
 * <p>Forked with {@link Console#ofSystem()} rather than the output-capturing {@link
 * build.spawn.application.option.StandardOutputSubscriber}/{@code StandardErrorSubscriber} pattern
 * {@link AbstractJavaLinker} and {@link AbstractJavaDependencyAnalysis} use for {@code jlink}/{@code
 * jdeps} -- those tools' output only ever matters for spin's own diagnostics on failure, but exec's
 * entire point is to run the application, so its stdin/stdout/stderr must flow straight through to
 * spin's own console live.
 *
 * <p>Depends on {@link CompilationResolution} (the same module-path/classpath split {@code Compile}
 * and {@code JavaDoc} already consume), not {@link DependencyAnalysis} -- the latter's {@code jdeps}
 * run exists only to discover which JDK platform modules an application needs so {@code jlink} knows
 * what to {@code --add-modules}. A plain {@code java -m} launch against a full JDK resolves platform
 * modules itself at startup, so paying for a {@code jdeps} fork here would buy exec nothing.
 */
@Source(AbstractJavaExec.CONFIGURATION_SOURCE)
public abstract class AbstractJavaExec
    implements Task<Void> {

    static final String CONFIGURATION_SOURCE = "build.spin.module.exec";

    private static final String MAIN_CLASS_KEY = "main-class";

    @Inject
    private TelemetryRecorder recorder;

    @Inject
    @build.spin.module.configuration.Configuration
    @Named(MAIN_CLASS_KEY)
    private Optional<String> mainClassOverride;

    @Inject
    private JavaPlatform platform;

    @Inject
    private LocalMachine machine;

    @Inject
    private Project project;

    @Inject
    private JDKModuleDescriptor descriptor;

    @Inject
    @System
    private JDKVersion systemJavaVersion;

    /**
     * Runs the {@link Project}'s own compiled application.
     *
     * @param resolution      the {@link CompilationResolution} (dependency module-path and classpath)
     * @param compiledClasses the {@link PathSet} returned by this {@link Project}'s own {@code Compile}
     *                        task -- its single entry is this project's own compiled module, added to
     *                        the module-path alongside {@code resolution}'s dependencies
     * @throws Exception should the {@link Task} execution fail, or the application exit non-zero
     */
    public Void exec(final CompilationResolution resolution,
                     final PathSet compiledClasses)
        throws Exception {

        final String mainClass = MainClassDetection
            .detect(this.project.path(), this.mainClassOverride, MAIN_CLASS_KEY, configurationLocation(), this.recorder)
            .orElseThrow(() -> new RuntimeException(
                "No main class found for [" + this.project.path() + "] -- set '" + MAIN_CLASS_KEY
                    + "' in " + configurationLocation() + " to specify one"));

        final Path ownOutput = compiledClasses.stream().findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Compile produced no output for [" + this.project.path() + "]"));

        final var hostJdk = this.platform.getVersion(this.systemJavaVersion.major())
            .or(this.platform::getLatest)
            .orElseThrow(() -> new RuntimeException(
                "No host-executable JDK found for Java " + this.systemJavaVersion.major() + " to run exec with, "
                    + "and no latest host JDK available"));

        final ConfigurationBuilder execConfiguration = ConfigurationBuilder.create()
            .add(JDKTools.executable(hostJdk.home().path(), "java"))
            .add(Name.of("exec/" + this.project.name()))
            .add(WorkingDirectory.of(this.project.path().toString()))
            .add(Console.ofSystem());

        // A raw compiled-classes directory (no module-info.class inside it) put on the module path
        // is treated as an automatic module named after the *directory's own basename* (e.g.
        // "target"), never after this.descriptor.moduleName() -- that synthesized name only lines
        // up once the output is packaged into a JAR file named to match (which jlink's launch
        // script relies on, but exec runs straight against Compile's raw output). So `-m
        // rootModule/mainClass` only resolves for a project with a *real* module-info.java; every
        // other project must run on the classpath instead.
        if (this.descriptor.isAutomatic()) {
            final List<Path> classPath = Stream
                .of(Stream.of(ownOutput), resolution.modulePath().stream(), resolution.classPath().stream())
                .flatMap(s -> s)
                .toList();
            execConfiguration.add(Argument.of("-cp")).add(Argument.of(joinPaths(classPath)));
            execConfiguration.add(Argument.of(mainClass));
            this.recorder.info("running [%s] for [%s]", mainClass, this.project.path());
        } else {
            final List<Path> modulePath =
                Stream.concat(Stream.of(ownOutput), resolution.modulePath().stream()).toList();
            final List<Path> classPath = resolution.classPath();
            final String rootModule = this.descriptor.moduleName().toString();

            execConfiguration.add(Argument.of("--module-path")).add(Argument.of(joinPaths(modulePath)));
            if (!classPath.isEmpty()) {
                execConfiguration.add(Argument.of("-cp")).add(Argument.of(joinPaths(classPath)));
            }
            execConfiguration.add(Argument.of("-m")).add(Argument.of(rootModule + "/" + mainClass));
            this.recorder.info("running [%s/%s] for [%s]", rootModule, mainClass, this.project.path());
        }

        try (var application = this.machine.launch(Application.class, execConfiguration)) {
            ProcessRunner.await(application, "exec",
                () -> "see the application's own output above");
        }
        return null;
    }

    // human-readable pointer to where exec's own configuration lives, e.g.
    // ".spin/build.spin.module.exec.properties" -- built from the same constants that back the
    // @Source/@Configuration/@Named wiring above so it can't describe a path that doesn't match
    // what ConfigurationResolver actually looks up.
    private static String configurationLocation() {
        return "%s/%s.properties"
            .formatted(build.spin.module.configuration.Configuration.DIRECTORY, CONFIGURATION_SOURCE);
    }

    private static String joinPaths(final List<Path> paths) {
        return paths.stream()
            .map(Path::toString)
            .collect(Collectors.joining(File.pathSeparator));
    }
}
