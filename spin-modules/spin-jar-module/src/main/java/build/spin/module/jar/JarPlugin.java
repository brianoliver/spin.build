package build.spin.module.jar;

/*-
 * #%L
 * Spin Jar Module
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

import build.base.archiving.JarBuilder;
import build.base.foundation.Capture;
import build.base.io.PathSet;
import build.base.option.JDKVersion;
import build.base.version.Version;
import build.codemodel.dependency.injection.PostInject;
import build.codemodel.jdk.descriptor.JDKModuleDescriptor;
import build.spin.Plugin;
import build.spin.Project;
import build.spin.Task;
import build.spin.annotation.Before;
import build.spin.annotation.Category;
import build.spin.annotation.Description;
import build.spin.annotation.From;
import build.spin.common.task.DetectSourcePaths;
import build.spin.common.task.SourcePathKind;
import build.spin.module.clean.CleanPlugin;
import build.spin.module.gpg.GpgPlugin;
import build.spin.module.gpg.SignableResource;
import build.spin.module.java.AbstractJavaPlugin;
import build.spin.module.java.JavaCompilerPlugin;
import build.spin.module.java.ResourcePlugin;
import build.spin.module.modulesystem.Artifact;
import build.spin.module.modulesystem.ArtifactDescriptor;
import build.spin.module.modulesystem.ModuleCatalog;
import build.spin.module.modulesystem.ModuleReference;
import build.spin.module.modulesystem.UnresolvableModuleException;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * A {@link Plugin} providing {@link Task}s for building the module jar, sources jar, and javadoc jar for a
 * {@link Project}.
 * <p>
 * Jar building is not Maven-specific: it names artifacts via the {@link ModuleCatalog}/{@link ArtifactDescriptor}
 * and is reusable by any packaging path. Maven-specific packaging (pom.xml generation, repository publishing)
 * lives in {@code build.spin.module.maven.MavenPlugin}, which consumes this plugin's {@link ArtifactDescriptor}s.
 *
 * @author reed.von.redwitz
 * @since Sep-2026
 */
public class JarPlugin
    extends AbstractJavaPlugin {

    private static final String MAIN_OUTPUT_PREFIX = SourcePathKind.MAIN.outputPrefix().orElseThrow();

    /**
     * The {@link JDKVersion} to use for the {@link JarPlugin}, is the highest {@link JDKVersion} of
     * the {@link JavaCompilerPlugin}(s) used by the {@link Project}.
     */
    private JDKVersion javaVersion;

    @PostInject
    private void onInjection() {
        // capture the highest JDKVersion
        final Capture<JDKVersion> capture = Capture.empty();

        this.project.plugins(JavaCompilerPlugin.class)
            .forEach(plugin -> {
                if ((capture.isPresent() && plugin.getJavaVersion().compareTo(capture.get()) > 0)
                    || !capture.isPresent()) {
                    capture.set(plugin.getJavaVersion());
                }
            });

        capture.map(version -> this.javaVersion = version)
            .orElseThrow(() -> new RuntimeException("Failed to determine the JDKVersion for the JarPlugin"));
    }

    @Override
    public JDKVersion getJavaVersion() {
        return this.javaVersion;
    }

    /**
     * Creates the directory into which distribution artifacts will be placed.
     */
    @Named("create.distribution.path")
    public static class CreateDistributionPath
        implements Task<Path> {

        /**
         * Create the directory into which distribution artifacts will be placed.
         *
         * @param buildPath the {@link Path} into which build output is placed
         * @return the distribution {@link Path}
         * @throws IOException should creating the {@link Path} fail
         */
        public Path create(final @From(CleanPlugin.CreateBuildPath.class) Path buildPath)
            throws IOException {

            final Path distributionPath = buildPath.resolve(MAIN_OUTPUT_PREFIX + "distribution/");

            Files.createDirectories(distributionPath);

            return distributionPath;
        }
    }

    /**
     * Creates a {@link Manifest} for packaging the {@link Project} compiled code and resources.
     */
    @Named("create.module.manifest")
    public static class CreateModuleManifest
        implements Task<Manifest> {

        @Inject
        private Project project;

        @Inject
        private JDKModuleDescriptor descriptor;

        @Inject
        private Version version;

        /**
         * Creates an initial {@link Manifest} for packaging.
         *
         * @return a new {@link Manifest}
         */
        public Manifest create() {
            final Manifest manifest = new Manifest();

            final Attributes mainAttributes = manifest.getMainAttributes();

            // include the mandatory Manifest version
            mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0.0");

            // include the automatic module name
            mainAttributes.put(new Attributes.Name("Automatic-Module-Name"), this.descriptor.moduleName().toString());

            // include the implementation title and version for the Module
            mainAttributes.put(Attributes.Name.IMPLEMENTATION_TITLE, this.project.name());
            mainAttributes.put(Attributes.Name.IMPLEMENTATION_VERSION, this.version.get());

            // include the multi-release attribute when this is a multi-release project
            if (this.project.plugins(JavaCompilerPlugin.class).count() > 1L) {
                mainAttributes.put(new Attributes.Name("Multi-Release"), "true");
            }

            return manifest;
        }
    }

    /**
     * Creates an {@link JarBuilder} and populates it with the compiled code and resources.
     */
    @Named("create.module.archive.builder")
    public static class CreateModuleArchiveBuilder
        implements Task<JarBuilder> {

        /**
         * Creates and populates an {@link JarBuilder} containing the compiled code and resources for the
         * {@link Project}.
         *
         * @param manifest the {@link Manifest}
         * @param pathSets the {@link Stream} of compilation {@link PathSet}s
         * @return a new {@link JarBuilder}
         */
        public JarBuilder create(final @From(CreateModuleManifest.class) Manifest manifest,
                                 final @From(JavaCompilerPlugin.Compile.class) Stream<PathSet> pathSets)
            throws IOException {

            final JarBuilder builder = new JarBuilder(manifest);

            final List<Path> paths = pathSets.flatMap(PathSet::stream).distinct().toList();

            for (final Path path : paths) {
                // for a multi-release module, a non-default-version variant's compiled output can
                // physically live inside the default variant's own output tree, at
                // "<default variant root>/META-INF/versions/N" (see AbstractCompile) -- in that case
                // adding the default variant's root already recursively picks it up at the correct
                // jar-relative location, so adding it again here separately would duplicate every
                // entry underneath it. Only add paths not already covered by another path in the set.
                final boolean coveredByAnotherPath = paths.stream()
                    .anyMatch(other -> !other.equals(path) && path.startsWith(other));

                if (coveredByAnotherPath) {
                    continue;
                }

                final Path versionsDir = path.getParent();
                final boolean isVersionedVariant = versionsDir != null
                    && "versions".equals(String.valueOf(versionsDir.getFileName()))
                    && versionsDir.getParent() != null
                    && "META-INF".equals(String.valueOf(versionsDir.getParent().getFileName()));

                if (isVersionedVariant) {
                    // the default variant reused an external tool's output (e.g. Maven's own
                    // target/classes) that has no META-INF/versions/N tree of its own, so this
                    // variant's output isn't nested under anything else being added -- it must be
                    // placed under its own explicit versioned path rather than flattened to the jar
                    // root, which is what adding it unnamed would otherwise do.
                    builder.content().add("META-INF/versions/" + path.getFileName() + "/", path);
                } else {
                    builder.content().add(path);
                }
            }

            return builder;
        }
    }

    /**
     * Creates a Java Archive (jar) containing the compiled byte code and resources for the {@link Project}.
     * <p>
     * Marked {@link Before} {@code GpgPlugin.Sign} so, when GPG signing is available, the archive is
     * registered with the {@link SignableResource} prior to signing.
     */
    @Named("package.module")
    @Description("Package JAR, sources, and Javadoc")
    @Category("package")
    @Category("build")
    @Before(GpgPlugin.Sign.class)
    public static class PackageModule
        implements build.spin.module.java.PackageModule {

        @Inject
        private Optional<SignableResource> signableResource;

        @Inject
        private JDKModuleDescriptor descriptor;

        @Inject
        private Version version;

        @Inject
        private ModuleCatalog catalog;

        /**
         * Creates a Java Archive (jar) containing the compiled code and resources for the {@link Project}.
         *
         * @param distributionPath the {@link Path} in which to place the archive
         * @param archiveBuilder   the {@link JarBuilder} to use for building the archive
         * @return the {@link ArtifactDescriptor} for the newly created archive
         */
        public ArtifactDescriptor archive(final @From(CreateDistributionPath.class) Path distributionPath,
                                          final @From(CreateModuleArchiveBuilder.class) JarBuilder archiveBuilder) {

            // determine the Artifact to generate based on the ModuleDescriptor
            final ModuleReference ref = ModuleReference.of(this.descriptor.moduleName().toString(), this.version);
            return this.catalog.getArtifact(ref)
                .map(artifact -> {
                    // establish the name of the archive
                    final String artifactName = artifact.artifactId() + "-" + artifact.version().get() + ".jar";
                    final Path artifactPath = distributionPath.resolve(artifactName);

                    // attempt to create the archive
                    try {
                        archiveBuilder.build(artifactPath);
                    } catch (final IOException e) {
                        throw new RuntimeException("Failed to create Artifact [" + artifactName + "]", e);
                    }

                    // include the archive for signing, when GPG signing is available
                    this.signableResource.ifPresent(resource -> resource.include(artifactPath));

                    return ArtifactDescriptor.create(ref, artifact, artifactPath);

                })
                .orElseThrow(() -> new UnresolvableModuleException(ref));
        }
    }

    /**
     * Creates a Java Archive (jar) containing the source code and resources for a {@link Project}.
     * <p>
     * Marked {@link Before} {@code GpgPlugin.Sign} so, when GPG signing is available, the archive is
     * registered with the {@link SignableResource} prior to signing.
     */
    @Named("package.source")
    @Category("package")
    @Category("build")
    @Before(GpgPlugin.Sign.class)
    public static class PackageModuleSource
        implements Task<ArtifactDescriptor> {

        @Inject
        private Optional<SignableResource> signableResource;

        @Inject
        private JDKModuleDescriptor descriptor;

        @Inject
        private Version version;

        @Inject
        private ModuleCatalog catalog;

        /**
         * Create a Java Archive (jar) containing the source code and resources for the {@link Project}.
         *
         * @param distributionPath the {@link Path} in which to place the archive
         * @param sourcePaths      the {@link PathSet}s of the source code
         * @param resourcePaths    the {@link PathSet}s of the resources
         * @return the {@link ArtifactDescriptor} for the created source archive
         */
        public ArtifactDescriptor archive(final @From(CreateDistributionPath.class) Path distributionPath,
                                          final @From(DetectSourcePaths.class) Map<SourcePathKind, PathSet> sourcePaths,
                                          final @From(ResourcePlugin.DetectModuleResourcePaths.class) Stream<PathSet> resourcePaths) {

            // establish the Archive
            final JarBuilder archiveBuilder = new JarBuilder();

            // a Maven sources-jar contains only declared main source, matching maven-source-plugin's
            // own default scope (test sources need a separate, explicitly-requested test-sources jar)
            final PathSet mainSourcePaths = DetectSourcePaths.pathsOf(sourcePaths, SourcePathKind.MAIN);

            // include the paths from the PathSets
            Stream.concat(Stream.of(mainSourcePaths), resourcePaths)
                .flatMap(PathSet::stream)
                .filter(Files::exists)
                .forEach(path -> {
                    try {
                        archiveBuilder.content().add(path);
                    } catch (final IOException e) {
                        throw new RuntimeException("Failed to add source path [" + path + "] to the sources archive", e);
                    }
                });

            // determine the Artifact to generate based on the ModuleDescriptor
            final ModuleReference ref = ModuleReference.of(this.descriptor.moduleName().toString(), this.version);
            return this.catalog.getArtifact(ref)
                .map(artifact -> {
                    // establish the name of the archive
                    final String artifactName = artifact.artifactId() + "-" + artifact.version().get() + "-sources.jar";
                    final Path artifactPath = distributionPath.resolve(artifactName);

                    // attempt to create the archive
                    try {
                        archiveBuilder.build(artifactPath);
                    } catch (final IOException e) {
                        throw new RuntimeException("Failed to create Artifact [" + artifactName + "]", e);
                    }

                    // include the archive for signing, when GPG signing is available
                    this.signableResource.ifPresent(resource -> resource.include(artifactPath));

                    return ArtifactDescriptor.create(
                        ref,
                        Artifact.create(
                            artifact.groupId(),
                            artifact.artifactId(),
                            artifact.version().get(),
                            artifact.type(),
                            "sources"),
                        artifactPath);
                })
                .orElseThrow(() -> new UnresolvableModuleException(ref));
        }
    }

    /**
     * Creates a Java Archive (jar) containing the Java Documentation for a {@link Project}.
     * <p>
     * Marked {@link Before} {@code GpgPlugin.Sign} so, when GPG signing is available, the archive is
     * registered with the {@link SignableResource} prior to signing.
     */
    @Named("package.javadoc")
    @Category("package")
    @Category("build")
    @Before(GpgPlugin.Sign.class)
    public static class PackageJavaDoc
        implements Task<ArtifactDescriptor> {

        @Inject
        private Optional<SignableResource> signableResource;

        @Inject
        private JDKModuleDescriptor descriptor;

        @Inject
        private Version version;

        @Inject
        private ModuleCatalog catalog;

        /**
         * Create a Java Archive (jar) containing the Java Documentation for the {@link Project}.
         *
         * @param distributionPath the {@link Path} in which to place the archive
         * @param javadocPaths     the {@link PathSet}s of the Java Documentation
         * @return the {@link ArtifactDescriptor} of the created Java Documentation archive
         */
        public ArtifactDescriptor archive(final @From(CreateDistributionPath.class) Path distributionPath,
                                          final @From(JavaCompilerPlugin.JavaDoc.class) Stream<PathSet> javadocPaths) {

            // establish the Archive
            final JarBuilder archiveBuilder = new JarBuilder();

            // include the paths from the PathSets
            javadocPaths
                .flatMap(PathSet::stream)
                .filter(Files::exists)
                .forEach(path -> {
                    try {
                        archiveBuilder.content().add(path.toFile());
                    } catch (final IOException e) {
                        throw new RuntimeException("Failed to add Javadoc path [" + path + "] to the Javadoc archive", e);
                    }
                });

            // determine the Artifact to generate based on the ModuleDescriptor
            final ModuleReference ref = ModuleReference.of(this.descriptor.moduleName().toString(), this.version);
            return this.catalog.getArtifact(ref)
                .map(artifact -> {
                    // establish the name of the archive
                    final String artifactName = artifact.artifactId() + "-" + artifact.version().get() + "-javadoc.jar";
                    final Path artifactPath = distributionPath.resolve(artifactName);

                    // attempt to create the archive
                    try {
                        archiveBuilder.build(artifactPath);
                    } catch (final IOException e) {
                        throw new RuntimeException("Failed to create Artifact [" + artifactName + "]", e);
                    }

                    // include the archive for signing, when GPG signing is available
                    this.signableResource.ifPresent(resource -> resource.include(artifactPath));

                    return ArtifactDescriptor.create(
                        ref,
                        Artifact.create(
                            artifact.groupId(),
                            artifact.artifactId(),
                            artifact.version().get(),
                            artifact.type(),
                            "javadoc"),
                        artifactPath);
                })
                .orElseThrow(() -> new UnresolvableModuleException(ref));
        }
    }

    /**
     * The {@link Plugin.MetaClass} for the {@link JarPlugin}.
     */
    public static class MetaClass
        implements Plugin.MetaClass {

        @Override
        public boolean isDetectedIn(final Path path) {
            return false;
        }

        @Override
        public boolean isDetectedIn(final Project project) {
            return project.contains(JavaCompilerPlugin.class);
        }
    }
}
