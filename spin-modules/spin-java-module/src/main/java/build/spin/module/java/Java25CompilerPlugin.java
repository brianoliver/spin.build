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
import build.base.option.JDKVersion;
import build.spin.Plugin;
import build.spin.Project;
import build.spin.Task;
import build.spin.annotation.After;
import build.spin.annotation.Category;
import build.spin.annotation.Description;
import build.spin.annotation.From;
import build.spin.annotation.System;
import build.spin.common.task.AbstractDetectSourcePaths;
import build.spin.common.task.SourcePathKind;
import build.spin.module.clean.CleanPlugin;
import build.spin.module.modulesystem.ArtifactDescriptor;
import build.spin.module.modulesystem.CompilationResolution;
import build.spin.option.TargetDirectoryName;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A {@link JavaCompilerPlugin} for Java 25 based {@link Project}s.
 *
 * @author brian.oliver
 * @since Jul-2019
 */
public class Java25CompilerPlugin
    extends AbstractJavaPlugin
    implements JavaCompilerPlugin {

    /**
     * The {@link JDKVersion} for the {@link Plugin}.
     */
    private final JDKVersion javaVersion;

    /**
     * Constructs a {@link Java25CompilerPlugin}.
     */
    public Java25CompilerPlugin() {
        this.javaVersion = JDKVersion.of(25);
    }

    @Override
    public JDKVersion getJavaVersion() {
        return this.javaVersion;
    }

    /**
     * A {@link Task} to determine the source paths for compilation.
     */
    @Named("detect.source.paths")
    public static class DetectSourcePaths
        extends AbstractDetectSourcePaths {

        @Override
        protected Set<SourcePathKind> kinds() {
            return EnumSet.of(SourcePathKind.MAIN, SourcePathKind.GENERATED, SourcePathKind.EXTERNAL_GENERATED);
        }
    }

    /**
     * A {@link Task} that resolves the full source-graph dependency closure for main compilation
     * and classifies candidates into module-path vs classpath.
     */
    @Named("detect.compilation.resolution")
    public static class DetectCompilationResolution
        extends AbstractDetectResolution
        implements JavaCompilerPlugin.DetectCompilationResolution {

    }

    /**
     * A {@link Task} to compile the source code in the {@link Project}.
     */
    @Named("compile")
    @Description("Compile Java sources")
    public static class Compile
        extends AbstractCompile
        implements JavaCompilerPlugin.Compile {

        private static final String TARGET_PREFIX = SourcePathKind.MAIN.outputPrefix().orElseThrow();

        @Inject
        private TargetDirectoryName target;

        /**
         * Compiles the source code detected under the provided {@link SourcePathKind}s into the
         * specified build {@link Path}.
         *
         * @param sourcePaths the detected source root directories, by {@link SourcePathKind}
         * @param resolution the {@link CompilationResolution} (module-path and classpath)
         * @param buildPath the build {@link Path}
         *
         * @return the {@link PathSet} containing the compiled classes
         * @throws Exception should compilation fail
         */
        public PathSet compile(final @From(DetectSourcePaths.class) Map<SourcePathKind, PathSet> sourcePaths,
                               final @From(DetectCompilationResolution.class) CompilationResolution resolution,
                               final @From(CleanPlugin.CreateBuildPath.class) Path buildPath)
            throws Exception {

            // the path in which to place the compiled classes
            final Path targetPath = buildPath.resolve(TARGET_PREFIX + this.target.get());

            final PathSet sourceCode = build.spin.common.task.DetectSourcePaths.filesOf(sourcePaths, SourcePathKind.MAIN);
            final PathSet externalGeneratedSources =
                build.spin.common.task.DetectSourcePaths.filesOf(sourcePaths, SourcePathKind.EXTERNAL_GENERATED);

            return super.compile(sourceCode, externalGeneratedSources, resolution, buildPath, targetPath);
        }
    }

    /**
     * A {@link Task} to generate Java Documentation from the source code in the {@link Project}.
     */
    @Named("javadoc")
    @Description("Generate Javadoc")
    @Category("document")
    @Category("build")
    @After(Compile.class)
    public static class JavaDoc
        extends AbstractJavaDoc {

        /**
         * Generates Java Documentation from the source code detected under the provided
         * {@link SourcePathKind}s in the {@link Project}.
         *
         * @param sourcePaths the detected source root directories, by {@link SourcePathKind}
         * @param resolution the {@link CompilationResolution} (module-path and classpath)
         * @param buildPath the build {@link Path}
         *
         * @return the {@link Path} of the generated documentation
         * @throws Exception should documentation fail
         */
        public Path javadoc(final @From(DetectSourcePaths.class) Map<SourcePathKind, PathSet> sourcePaths,
                            final @From(DetectCompilationResolution.class) CompilationResolution resolution,
                            final @From(CleanPlugin.CreateBuildPath.class) Path buildPath)
            throws Exception {

            final PathSet sourceCode = build.spin.common.task.DetectSourcePaths.filesOf(
                sourcePaths, SourcePathKind.MAIN, SourcePathKind.GENERATED, SourcePathKind.EXTERNAL_GENERATED);

            return super.javadoc(
                sourceCode,
                resolution,
                buildPath,
                Optional.of(new URL("https://docs.oracle.com/en/java/javase/25/docs/api/")));
        }
    }

    /**
     * A {@link Task} to perform Java Dependencies analysis on compiled and packaged code in the {@link Project}.
     */
    @Named("jdeps")
    @Description("Analyse module dependencies with jdeps")
    public static class JavaDependencyAnalysis
        extends AbstractJavaDependencyAnalysis {

        @Override
        public DependencyAnalysis jdeps(final @From(CleanPlugin.CreateBuildPath.class) Path buildPath,
                                        final @From(PackageModule.class) Stream<ArtifactDescriptor> descriptors)
            throws Exception {

            return super.jdeps(buildPath, descriptors);
        }
    }

    /**
     * A {@link Task} to run a {@link Project}'s own compiled application directly.
     */
    @Named("exec")
    @Description("Run the project's application")
    @Category("run")
    public static class Exec
        extends AbstractJavaExec {

        @Override
        public Void exec(final @From(DetectCompilationResolution.class) CompilationResolution resolution,
                         final @From(Compile.class) PathSet compiledClasses)
            throws Exception {

            return super.exec(resolution, compiledClasses);
        }
    }

    /**
     * A {@link Task} to perform Java Linking on a {@link Project}.
     */
    @Named("jlink")
    @Description("Build a jlink runtime image")
    public static class JavaLinker
        extends AbstractJavaLinker {

        @Override
        public Set<Path> jlink(final @From(CleanPlugin.CreateBuildPath.class) Path buildPath,
                               final @From(JavaDependencyAnalysis.class) DependencyAnalysis analysis)
            throws Exception {

            return super.jlink(buildPath, analysis);
        }
    }

    /**
     * The {@link Plugin.MetaClass} for {@link Java25CompilerPlugin}.
     */
    public static class MetaClass
        implements Plugin.MetaClass {

        @Inject
        @System
        private JDKVersion defaultJavaVersion;

        @Override
        public boolean isDetectedIn(final Path path) {
            final String sourceRoot = SourcePathKind.MAIN.sourceRoot().orElseThrow();
            return (Files.exists(path.resolve(sourceRoot + "java")) && this.defaultJavaVersion.major() == 25)
                || Files.exists(path.resolve(sourceRoot + "java25"));
        }
    }
}
