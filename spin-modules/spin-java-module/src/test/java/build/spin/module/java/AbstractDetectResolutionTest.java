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

import build.base.foundation.Exceptional;
import build.base.foundation.UniformResource;
import build.base.io.PathSet;
import build.base.telemetry.Diagnostic;
import build.base.telemetry.Telemetry;
import build.base.telemetry.TelemetryRecorder;
import build.base.telemetry.Warning;
import build.base.version.Version;
import build.codemodel.foundation.CodeModel;
import build.codemodel.foundation.ConceptualCodeModel;
import build.codemodel.foundation.descriptor.RequiresModuleDescriptor;
import build.codemodel.foundation.naming.NonCachingNameProvider;
import build.codemodel.jdk.descriptor.JDKModuleDescriptor;
import build.codemodel.jdk.descriptor.RequiresVersionTrait;
import build.spin.common.task.SourcePathKind;
import build.spin.common.telemetry.TelemetryPublisher;
import build.spin.module.modulesystem.Artifact;
import build.spin.module.modulesystem.ModuleCatalog;
import build.spin.module.modulesystem.ModuleReference;
import build.spin.module.modulesystem.ModuleVersioning;
import build.spin.option.ReuseExternalBuildOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractDetectResolutionTest {

    @TempDir
    Path projectRoot;

    private static TelemetryRecorder recorder() {
        return new TelemetryPublisher(
            UniformResource.createURI("test", "AbstractDetectResolutionTest"),
            System.out::println);
    }

    // an Artifact.Resolver stub whose other members every test in this class leaves
    // unexercised — only resolveTransitive varies per test.
    private static Artifact.Resolver stubResolver(
        final Function<Artifact, Exceptional<List<Path>>> resolveTransitive) {

        return new Artifact.Resolver() {
            @Override
            public Exceptional<Path> resolve(final Artifact artifact) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Exceptional<ModuleReference> getModuleReference(
                final Artifact artifact, final ModuleCatalog catalog) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Exceptional<JDKModuleDescriptor> getModuleDescriptor(
                final Artifact artifact, final ModuleCatalog catalog, final ModuleVersioning versioning) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Exceptional<List<Path>> resolveTransitive(final Artifact artifact) {
                return resolveTransitive.apply(artifact);
            }
        };
    }

    // creates a minimal jar with the given Automatic-Module-Name at <repoRoot>/<groupPath>/<artifactId>/<version>/<artifactId>-<version>.jar
    private static Path createArtifactJar(final Path repoRoot,
                                          final String groupPath,
                                          final String artifactId,
                                          final String version,
                                          final String automaticModuleName) throws IOException {

        final Path versionDir = repoRoot.resolve(groupPath).resolve(artifactId).resolve(version);
        Files.createDirectories(versionDir);

        final Path jar = versionDir.resolve(artifactId + "-" + version + ".jar");
        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Automatic-Module-Name"), automaticModuleName);

        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            // no entries needed — an automatic module is derived purely from the manifest/filename
        }

        return jar;
    }

    @Test
    void resolveCompiledOutput_noneExists_returnsEmpty() {
        assertThat(AbstractDetectResolution.resolveCompiledOutput(
            projectRoot, ".build", "classes", SourcePathKind.MAIN, ReuseExternalBuildOutput.DISABLED)).isEmpty();
    }

    @Test
    void resolveCompiledOutput_spinOutputExists_returnsSpinPath() throws IOException {
        final Path spinOutput = projectRoot.resolve(".build/main/classes");
        Files.createDirectories(spinOutput);
        Files.createFile(spinOutput.resolve("Foo.class"));

        assertThat(AbstractDetectResolution.resolveCompiledOutput(
            projectRoot, ".build", "classes", SourcePathKind.MAIN, ReuseExternalBuildOutput.DISABLED))
            .contains(spinOutput);
    }

    @Test
    void resolveCompiledOutput_spinOutputExistsButEmpty_returnsEmpty() throws IOException {
        // an empty directory isn't "already built" -- e.g. CleanPlugin$CreateBuildPath creates
        // <buildDir>/main/<target> up front as a prerequisite for several tasks, independent of
        // whether Compile has actually run yet.
        final Path spinOutput = projectRoot.resolve(".build/main/classes");
        Files.createDirectories(spinOutput);

        assertThat(AbstractDetectResolution.resolveCompiledOutput(
            projectRoot, ".build", "classes", SourcePathKind.MAIN, ReuseExternalBuildOutput.DISABLED)).isEmpty();
    }

    @Test
    void resolveCompiledOutput_spinOutputHasOnlyResources_prefersOlderButGenuinelyCompiledMavenOutput()
        throws IOException {
        // the exact race behind the spin1-build-spin2 "module not found" failure: CopyResources (a
        // @PreProcess prerequisite of Compile) writes resource files into spin's own build output
        // before Compile has produced a single .class file. A directory that's non-empty for that
        // reason alone must not beat a fully-populated Maven candidate on the freshest-mtime tie-break,
        // even though the resource file's mtime is newer than anything in the Maven candidate.
        final Path spinOutput = projectRoot.resolve(".build/main/classes");
        Files.createDirectories(spinOutput);
        final Path resource = spinOutput.resolve("application.properties");
        Files.createFile(resource);
        setModifiedTime(resource, FileTime.fromMillis(10_000));

        final Path mavenClasses = projectRoot.resolve("target/classes");
        Files.createDirectories(mavenClasses);
        final Path mavenClass = mavenClasses.resolve("Foo.class");
        Files.createFile(mavenClass);
        setModifiedTime(mavenClass, FileTime.fromMillis(1_000));

        assertThat(AbstractDetectResolution.resolveCompiledOutput(
            projectRoot, ".build", "classes", SourcePathKind.MAIN, ReuseExternalBuildOutput.ENABLED))
            .contains(mavenClasses);
    }

    @Test
    void resolveCompiledOutput_mavenClassesExist_butReuseDisabled_returnsEmpty() throws IOException {
        final Path mavenClasses = projectRoot.resolve("target/classes");
        Files.createDirectories(mavenClasses);
        Files.createFile(mavenClasses.resolve("Foo.class"));

        assertThat(AbstractDetectResolution.resolveCompiledOutput(
            projectRoot, ".build", "classes", SourcePathKind.MAIN, ReuseExternalBuildOutput.DISABLED)).isEmpty();
    }

    @Test
    void resolveCompiledOutput_mavenClassesExist_reuseEnabled_returnsMavenPath() throws IOException {
        final Path mavenClasses = projectRoot.resolve("target/classes");
        Files.createDirectories(mavenClasses);
        Files.createFile(mavenClasses.resolve("Foo.class"));

        assertThat(AbstractDetectResolution.resolveCompiledOutput(
            projectRoot, ".build", "classes", SourcePathKind.MAIN, ReuseExternalBuildOutput.ENABLED))
            .contains(mavenClasses);
    }

    @Test
    void resolveCompiledOutput_gradleClassesExist_butReuseDisabled_returnsEmpty() throws IOException {
        final Path gradleClasses = projectRoot.resolve("build/classes/java/main");
        Files.createDirectories(gradleClasses);
        Files.createFile(gradleClasses.resolve("Foo.class"));

        assertThat(AbstractDetectResolution.resolveCompiledOutput(
            projectRoot, ".build", "classes", SourcePathKind.MAIN, ReuseExternalBuildOutput.DISABLED)).isEmpty();
    }

    @Test
    void resolveCompiledOutput_gradleClassesExist_reuseEnabled_returnsGradlePath() throws IOException {
        final Path gradleClasses = projectRoot.resolve("build/classes/java/main");
        Files.createDirectories(gradleClasses);
        Files.createFile(gradleClasses.resolve("Foo.class"));

        assertThat(AbstractDetectResolution.resolveCompiledOutput(
            projectRoot, ".build", "classes", SourcePathKind.MAIN, ReuseExternalBuildOutput.ENABLED))
            .contains(gradleClasses);
    }

    @Test
    void resolveCompiledOutput_testScope_mavenReuseEnabled_returnsMavenTestClassesNotMainClasses() throws IOException {
        // for SourcePathKind.TEST, the Maven candidate must be target/test-classes -- target/classes
        // (the MAIN candidate) exists here too, to confirm the TEST-scope lookup doesn't fall back to it.
        final Path mavenMainClasses = projectRoot.resolve("target/classes");
        Files.createDirectories(mavenMainClasses);
        Files.createFile(mavenMainClasses.resolve("Foo.class"));

        final Path mavenTestClasses = projectRoot.resolve("target/test-classes");
        Files.createDirectories(mavenTestClasses);
        Files.createFile(mavenTestClasses.resolve("FooTest.class"));

        assertThat(AbstractDetectResolution.resolveCompiledOutput(
            projectRoot, ".build", "classes", SourcePathKind.TEST, ReuseExternalBuildOutput.ENABLED))
            .contains(mavenTestClasses);
    }

    @Test
    void resolveCompiledOutput_testScope_gradleReuseEnabled_returnsGradleTestClassesNotMainClasses() throws IOException {
        // for SourcePathKind.TEST, the Gradle candidate must be build/classes/java/test --
        // build/classes/java/main (the MAIN candidate) exists here too, to confirm the TEST-scope
        // lookup doesn't fall back to it.
        final Path gradleMainClasses = projectRoot.resolve("build/classes/java/main");
        Files.createDirectories(gradleMainClasses);
        Files.createFile(gradleMainClasses.resolve("Foo.class"));

        final Path gradleTestClasses = projectRoot.resolve("build/classes/java/test");
        Files.createDirectories(gradleTestClasses);
        Files.createFile(gradleTestClasses.resolve("FooTest.class"));

        assertThat(AbstractDetectResolution.resolveCompiledOutput(
            projectRoot, ".build", "classes", SourcePathKind.TEST, ReuseExternalBuildOutput.ENABLED))
            .contains(gradleTestClasses);
    }

    @Test
    void resolveCompiledOutput_testScope_mavenClassesExist_butReuseDisabled_returnsEmpty() throws IOException {
        final Path mavenTestClasses = projectRoot.resolve("target/test-classes");
        Files.createDirectories(mavenTestClasses);
        Files.createFile(mavenTestClasses.resolve("FooTest.class"));

        assertThat(AbstractDetectResolution.resolveCompiledOutput(
            projectRoot, ".build", "classes", SourcePathKind.TEST, ReuseExternalBuildOutput.DISABLED)).isEmpty();
    }

    @Test
    void resolveCompiledOutput_spinAndMavenExist_reuseEnabled_prefersFresherMaven() throws IOException {
        final Path spinOutput = projectRoot.resolve(".build/main/classes");
        final Path mavenClasses = projectRoot.resolve("target/classes");
        Files.createDirectories(spinOutput);
        Files.createDirectories(mavenClasses);
        final Path spinClass = spinOutput.resolve("Foo.class");
        final Path mavenClass = mavenClasses.resolve("Foo.class");
        Files.createFile(spinClass);
        Files.createFile(mavenClass);
        setModifiedTime(spinClass, FileTime.fromMillis(1_000));
        setModifiedTime(spinOutput, FileTime.fromMillis(1_000));
        setModifiedTime(mavenClass, FileTime.fromMillis(2_000));
        setModifiedTime(mavenClasses, FileTime.fromMillis(2_000));

        assertThat(AbstractDetectResolution.resolveCompiledOutput(
            projectRoot, ".build", "classes", SourcePathKind.MAIN, ReuseExternalBuildOutput.ENABLED))
            .contains(mavenClasses);
    }

    @Test
    void resolveCompiledOutput_spinAndMavenExist_reuseDisabled_alwaysPrefersSpinRegardlessOfFreshness()
        throws IOException {
        // with reuse disabled, Maven's output isn't a candidate at all -- even when it's fresher --
        // so spin's own output wins unconditionally rather than via the freshness tiebreak.
        final Path spinOutput = projectRoot.resolve(".build/main/classes");
        final Path mavenClasses = projectRoot.resolve("target/classes");
        Files.createDirectories(spinOutput);
        Files.createDirectories(mavenClasses);
        final Path spinClass = spinOutput.resolve("Foo.class");
        final Path mavenClass = mavenClasses.resolve("Foo.class");
        Files.createFile(spinClass);
        Files.createFile(mavenClass);
        setModifiedTime(spinClass, FileTime.fromMillis(1_000));
        setModifiedTime(spinOutput, FileTime.fromMillis(1_000));
        setModifiedTime(mavenClass, FileTime.fromMillis(2_000));
        setModifiedTime(mavenClasses, FileTime.fromMillis(2_000));

        assertThat(AbstractDetectResolution.resolveCompiledOutput(
            projectRoot, ".build", "classes", SourcePathKind.MAIN, ReuseExternalBuildOutput.DISABLED))
            .contains(spinOutput);
    }

    @Test
    void resolveCompiledOutput_spinAndMavenExist_prefersFresherSpin() throws IOException {
        final Path spinOutput = projectRoot.resolve(".build/main/classes");
        final Path mavenClasses = projectRoot.resolve("target/classes");
        Files.createDirectories(spinOutput);
        Files.createDirectories(mavenClasses);
        final Path spinClass = spinOutput.resolve("Foo.class");
        final Path mavenClass = mavenClasses.resolve("Foo.class");
        Files.createFile(spinClass);
        Files.createFile(mavenClass);
        setModifiedTime(spinClass, FileTime.fromMillis(2_000));
        setModifiedTime(spinOutput, FileTime.fromMillis(2_000));
        setModifiedTime(mavenClass, FileTime.fromMillis(1_000));
        setModifiedTime(mavenClasses, FileTime.fromMillis(1_000));

        assertThat(AbstractDetectResolution.resolveCompiledOutput(
            projectRoot, ".build", "classes", SourcePathKind.MAIN, ReuseExternalBuildOutput.ENABLED))
            .contains(spinOutput);
    }

    @Test
    void resolveCompiledOutput_mavenIsStaleLeftoverFromEarlierSpinBuild_prefersCurrentSpinOutput() throws IOException {
        // the exact scenario this freshness comparison exists for: a project once built by spin
        // directly still has a stale target/classes from that era, but is now built by Maven and
        // recompiled since — the current .build/ output must win, not whichever tool happens to be
        // checked first.
        final Path spinOutput = projectRoot.resolve(".build/main/classes");
        final Path mavenClasses = projectRoot.resolve("target/classes");
        Files.createDirectories(mavenClasses);
        final Path staleClass = mavenClasses.resolve("Stale.class");
        Files.createFile(staleClass);
        setModifiedTime(staleClass, FileTime.fromMillis(1_000));
        setModifiedTime(mavenClasses, FileTime.fromMillis(1_000));

        Files.createDirectories(spinOutput);
        final Path freshClass = spinOutput.resolve("Fresh.class");
        Files.createFile(freshClass);
        setModifiedTime(freshClass, FileTime.fromMillis(5_000));
        setModifiedTime(spinOutput, FileTime.fromMillis(5_000));

        assertThat(AbstractDetectResolution.resolveCompiledOutput(
            projectRoot, ".build", "classes", SourcePathKind.MAIN, ReuseExternalBuildOutput.ENABLED))
            .contains(spinOutput);
    }

    private static void setModifiedTime(final Path path, final FileTime time) throws IOException {
        Files.setLastModifiedTime(path, time);
    }

    @Test
    void containsCompiledClasses_classFileAtTopLevel_returnsTrue() throws IOException {
        final Path directory = projectRoot.resolve("classes");
        Files.createDirectories(directory);
        Files.createFile(directory.resolve("Foo.class"));

        assertThat(AbstractDetectResolution.containsCompiledClasses(directory)).isTrue();
    }

    @Test
    void containsCompiledClasses_classFileNestedOutsideVersions_returnsTrue() throws IOException {
        final Path directory = projectRoot.resolve("classes");
        final Path nested = Files.createDirectories(directory.resolve("build/spin/module/java"));
        Files.createFile(nested.resolve("Foo.class"));

        assertThat(AbstractDetectResolution.containsCompiledClasses(directory)).isTrue();
    }

    @Test
    void containsCompiledClasses_onlyResourceFiles_returnsFalse() throws IOException {
        // e.g. CopyResources has already run but Compile hasn't -- the directory is non-empty but
        // contains nothing that was actually compiled.
        final Path directory = projectRoot.resolve("classes");
        Files.createDirectories(directory);
        Files.createFile(directory.resolve("application.properties"));

        assertThat(AbstractDetectResolution.containsCompiledClasses(directory)).isFalse();
    }

    @Test
    void containsCompiledClasses_classOnlyUnderVersionsSubdir_excludingVersions_returnsFalse() throws IOException {
        // the default-variant, top-level check: a sibling non-default variant's own partial write
        // under META-INF/versions/N must not be mistaken for this variant's own (not-yet-written)
        // top-level output.
        final Path directory = projectRoot.resolve("classes");
        final Path versioned = Files.createDirectories(directory.resolve("META-INF/versions/25"));
        Files.createFile(versioned.resolve("Foo.class"));

        assertThat(AbstractDetectResolution.containsCompiledClasses(directory, true)).isFalse();
    }

    @Test
    void containsCompiledClasses_classOnlyUnderVersionsSubdir_includingVersions_returnsTrue() throws IOException {
        // a caller already scoped to a specific variant's own META-INF/versions/N sub-directory
        // allows a match there.
        final Path directory = projectRoot.resolve("classes");
        final Path versioned = Files.createDirectories(directory.resolve("META-INF/versions/25"));
        Files.createFile(versioned.resolve("Foo.class"));

        assertThat(AbstractDetectResolution.containsCompiledClasses(directory, false)).isTrue();
    }

    @Test
    void isUpToDate_outputNewerThanAllInputs_returnsTrue() throws IOException {
        final Path output = projectRoot.resolve("classes");
        Files.createDirectories(output);
        final Path outputClass = output.resolve("Foo.class");
        Files.createFile(outputClass);
        setModifiedTime(outputClass, FileTime.fromMillis(2_000));

        final Path source = projectRoot.resolve("Foo.java");
        Files.createFile(source);
        setModifiedTime(source, FileTime.fromMillis(1_000));

        assertThat(AbstractDetectResolution.isUpToDate(output, PathSet.of(source))).isTrue();
    }

    @Test
    void isUpToDate_inputNewerThanOutput_returnsFalse() throws IOException {
        final Path output = projectRoot.resolve("classes");
        Files.createDirectories(output);
        final Path outputClass = output.resolve("Foo.class");
        Files.createFile(outputClass);
        setModifiedTime(outputClass, FileTime.fromMillis(1_000));

        final Path source = projectRoot.resolve("Foo.java");
        Files.createFile(source);
        setModifiedTime(source, FileTime.fromMillis(2_000));

        assertThat(AbstractDetectResolution.isUpToDate(output, PathSet.of(source))).isFalse();
    }

    @Test
    void isUpToDate_inputIsDirectoryWithNestedNewerFile_returnsFalse() throws IOException {
        // resources are passed as their root directory (e.g. src/main/resources), not individual
        // files -- a directory's own mtime doesn't change when a file nested inside it is edited, so
        // the check must walk into it.
        final Path output = projectRoot.resolve("classes");
        Files.createDirectories(output);
        final Path outputClass = output.resolve("Foo.class");
        Files.createFile(outputClass);
        setModifiedTime(outputClass, FileTime.fromMillis(1_000));

        final Path resourceRoot = projectRoot.resolve("src/main/resources");
        final Path nested = Files.createDirectories(resourceRoot.resolve("config"));
        final Path resourceFile = nested.resolve("application.properties");
        Files.createFile(resourceFile);
        setModifiedTime(resourceFile, FileTime.fromMillis(2_000));

        assertThat(AbstractDetectResolution.isUpToDate(output, PathSet.of(resourceRoot))).isFalse();
    }

    @Test
    void isUpToDate_noInputPaths_returnsTrue() throws IOException {
        final Path output = projectRoot.resolve("classes");
        Files.createDirectories(output);
        Files.createFile(output.resolve("Foo.class"));

        assertThat(AbstractDetectResolution.isUpToDate(output, PathSet.empty())).isTrue();
    }

    @Test
    void isUpToDate_staleClassFileButFreshlyTouchedResource_returnsFalse() throws IOException {
        // the exact bug this scoping exists for: CopyResources unconditionally rewrites resource
        // files into this same output directory as a @PreProcess prerequisite of Compile, regardless
        // of whether Compile ends up reusing existing output. A freshly-touched resource sitting next
        // to a genuinely stale .class file must not make the directory as a whole look up to date --
        // only the .class file's own mtime should count.
        final Path output = projectRoot.resolve("classes");
        Files.createDirectories(output);
        final Path outputClass = output.resolve("Foo.class");
        Files.createFile(outputClass);
        setModifiedTime(outputClass, FileTime.fromMillis(1_000));

        final Path freshResource = output.resolve("application.properties");
        Files.createFile(freshResource);
        setModifiedTime(freshResource, FileTime.fromMillis(10_000));

        final Path source = projectRoot.resolve("Foo.java");
        Files.createFile(source);
        setModifiedTime(source, FileTime.fromMillis(5_000));

        assertThat(AbstractDetectResolution.isUpToDate(output, PathSet.of(source))).isFalse();
    }

    @Test
    void dedupeByMavenCoordinate_sameCoordinateDifferentVersions_keepsHighestVersion() {
        final Path repo = Path.of("/repo");
        final Path older = repo.resolve("io/helidon/grpc/grpc-core/1.0/grpc-core-1.0.jar");
        final Path newer = repo.resolve("io/helidon/grpc/grpc-core/2.0/grpc-core-2.0.jar");

        assertThat(AbstractDetectResolution.dedupeByMavenCoordinate(List.of(older, newer), recorder()))
            .containsExactly(newer);
    }

    @Test
    void dedupeByMavenCoordinate_sameCoordinateSameVersion_keepsFirstWithoutWarning() {
        final Path repo = Path.of("/repo");
        final Path first = repo.resolve("build/base/base-mereology/0.29.0/base-mereology-0.29.0.jar");
        final Path second = repo.resolve("build/base/base-mereology/0.29.0/base-mereology-0.29.0.jar");
        final List<Telemetry> emitted = new ArrayList<>();
        final TelemetryRecorder recorder = new TelemetryPublisher(
            UniformResource.createURI("test", "AbstractDetectResolutionTest"),
            emitted::add);

        assertThat(AbstractDetectResolution.dedupeByMavenCoordinate(List.of(first, second), recorder))
            .containsExactly(first);
        assertThat(emitted).isEmpty();
    }

    @Test
    void dedupeByMavenCoordinate_differentCoordinates_keepsBoth() {
        final Path repo = Path.of("/repo");
        final Path grpc = repo.resolve("io/helidon/grpc/grpc-core/1.0/grpc-core-1.0.jar");
        final Path protobuf = repo.resolve("com/google/protobuf/protobuf-java/3.0/protobuf-java-3.0.jar");

        assertThat(AbstractDetectResolution.dedupeByMavenCoordinate(List.of(grpc, protobuf), recorder()))
            .containsExactly(grpc, protobuf);
    }

    @Test
    void correctPinnedVersions_onDiskVersionDivergesFromPin_reResolvesAtPinnedVersion() throws IOException {
        final Path repo = projectRoot.resolve("repo");
        final Path helidonPinned = createArtifactJar(
            repo, "io/grpc", "grpc-core", "1.10.0", "io.grpc");
        final Path directlyPinned = createArtifactJar(
            repo, "io/grpc", "grpc-core", "1.60.0", "io.grpc");

        final ModuleVersioning versioning = moduleName ->
            "io.grpc".equals(moduleName) ? Optional.of(Version.parse("1.60.0")) : Optional.empty();

        final ModuleCatalog catalog = ModuleCatalog.HeapBased.create()
            .add("io.grpc", Artifact.create("io.grpc", "grpc-core", "1.60.0", "jar"));

        final Artifact.Resolver resolver = stubResolver(artifact -> "1.60.0".equals(artifact.version().get())
            ? Exceptional.of(List.of(directlyPinned))
            : Exceptional.ofException(new IllegalStateException("unexpected artifact " + artifact)));

        final List<Path> corrected = AbstractDetectResolution.correctPinnedVersions(
            List.of(helidonPinned), versioning, catalog, resolver, recorder());

        assertThat(corrected).containsExactly(directlyPinned);
    }

    @Test
    void correctPinnedVersions_pinnedReferenceIsAmbiguousInCatalog_warns() throws IOException {
        final Path repo = projectRoot.resolve("repo");
        final Path onDisk = createArtifactJar(
            repo, "io/grpc", "grpc-core", "1.10.0", "io.grpc");
        final Path resolvedAtPin = repo.resolve("resolved-at-pin.jar");

        final ModuleVersioning versioning = moduleName ->
            "io.grpc".equals(moduleName) ? Optional.of(Version.parse("1.60.0")) : Optional.empty();

        // two distinct artifacts both satisfy the pinned reference — this is the exact ambiguity
        // ModuleCatalog#getArtifact(reference, Optional<TelemetryRecorder>) is meant to warn about,
        // but correctPinnedVersions calls the recorder-less overload, so the warning never fires.
        final ModuleCatalog catalog = ModuleCatalog.HeapBased.create()
            .add("io.grpc", Artifact.create("io.grpc", "grpc-core", "1.60.0", "jar"))
            .add("io.grpc", Artifact.create("io.grpc", "grpc-core-shaded", "1.60.0", "jar"));

        final Artifact.Resolver resolver = stubResolver(_ -> Exceptional.of(List.of(resolvedAtPin)));

        final List<Telemetry> emitted = new ArrayList<>();
        final TelemetryRecorder recorder = new TelemetryPublisher(
            UniformResource.createURI("test", "AbstractDetectResolutionTest"),
            emitted::add);

        AbstractDetectResolution.correctPinnedVersions(
            List.of(onDisk), versioning, catalog, resolver, recorder);

        assertThat(emitted)
            .as("an ambiguous catalog match for the pinned reference should warn")
            .anyMatch(t -> t.toString().contains("matches multiple distinct Artifact Constraints"));
    }

    @Test
    void correctPinnedVersions_ambiguousCatalogMatchThenReResolutionFails_doesNotClaimTheAmbiguousArtifactWasKept()
        throws IOException {

        final Path repo = projectRoot.resolve("repo");
        final Path onDisk = createArtifactJar(
            repo, "io/grpc", "grpc-core", "1.10.0", "io.grpc");

        final ModuleVersioning versioning = moduleName ->
            "io.grpc".equals(moduleName) ? Optional.of(Version.parse("1.60.0")) : Optional.empty();

        // same ambiguity as correctPinnedVersions_pinnedReferenceIsAmbiguousInCatalog_warns, but this
        // time re-resolving the artifact getArtifact picked fails, so the method actually falls back
        // to the on-disk 1.10.0 jar — not the "kept" grpc-core:1.60.0 the ambiguity warning names.
        final ModuleCatalog catalog = ModuleCatalog.HeapBased.create()
            .add("io.grpc", Artifact.create("io.grpc", "grpc-core", "1.60.0", "jar"))
            .add("io.grpc", Artifact.create("io.grpc", "grpc-core-shaded", "1.60.0", "jar"));

        final Artifact.Resolver resolver = stubResolver(
            _ -> Exceptional.ofException(new IllegalStateException("resolution failure")));

        final List<Telemetry> emitted = new ArrayList<>();
        final TelemetryRecorder recorder = new TelemetryPublisher(
            UniformResource.createURI("test", "AbstractDetectResolutionTest"),
            emitted::add);

        final List<Path> corrected = AbstractDetectResolution.correctPinnedVersions(
            List.of(onDisk), versioning, catalog, resolver, recorder);

        assertThat(corrected)
            .as("re-resolution failed, so the on-disk jar should be kept")
            .containsExactly(onDisk);
        assertThat(emitted)
            .as("the log should say the on-disk version was kept after the failed re-resolution, "
                + "not just leave the earlier 'keeping [grpc-core:1.60.0]' ambiguity message unqualified")
            .anyMatch(t -> t.toString().contains("keeping on-disk version"));
    }

    @Test
    void dedupeByMavenCoordinate_qualifiersDisagreeWithLexicographicOrder_usesMavenRanking() {
        final Path repo = Path.of("/repo");
        // lexicographically "cr" < "milestone" (c < m), but Maven ranks milestone(3) below rc/cr(4) —
        // the milestone build is the older one and should be discarded in favor of the rc build.
        final Path milestone = repo.resolve("io/helidon/grpc/grpc-core/1.0-milestone/grpc-core-1.0-milestone.jar");
        final Path releaseCandidate = repo.resolve("io/helidon/grpc/grpc-core/1.0-cr/grpc-core-1.0-cr.jar");

        assertThat(AbstractDetectResolution.dedupeByMavenCoordinate(List.of(milestone, releaseCandidate), recorder()))
            .containsExactly(releaseCandidate);
    }

    @Test
    void correctPinnedVersions_pinnedModuleNotInCatalog_keepsOnDiskVersion() throws IOException {
        final Path repo = projectRoot.resolve("repo");
        final Path onDisk = createArtifactJar(repo, "io/grpc", "grpc-core", "1.10.0", "io.grpc");

        final ModuleVersioning versioning = moduleName ->
            "io.grpc".equals(moduleName) ? Optional.of(Version.parse("1.60.0")) : Optional.empty();

        // catalog has no entry for the pinned reference
        final ModuleCatalog catalog = ModuleCatalog.HeapBased.create();

        final Artifact.Resolver resolver = stubResolver(_ -> {
            throw new UnsupportedOperationException("should not resolve — no catalog entry for the pin");
        });

        final List<Path> corrected = AbstractDetectResolution.correctPinnedVersions(
            List.of(onDisk), versioning, catalog, resolver, recorder());

        assertThat(corrected).containsExactly(onDisk);
    }

    @Test
    void correctPinnedVersions_reResolutionFails_keepsOnDiskVersion() throws IOException {
        final Path repo = projectRoot.resolve("repo");
        final Path onDisk = createArtifactJar(repo, "io/grpc", "grpc-core", "1.10.0", "io.grpc");

        final ModuleVersioning versioning = moduleName ->
            "io.grpc".equals(moduleName) ? Optional.of(Version.parse("1.60.0")) : Optional.empty();

        final ModuleCatalog catalog = ModuleCatalog.HeapBased.create()
            .add("io.grpc", Artifact.create("io.grpc", "grpc-core", "1.60.0", "jar"));

        final Artifact.Resolver resolver = stubResolver(
            _ -> Exceptional.ofException(new IllegalStateException("resolution failure")));

        final List<Path> corrected = AbstractDetectResolution.correctPinnedVersions(
            List.of(onDisk), versioning, catalog, resolver, recorder());

        assertThat(corrected).containsExactly(onDisk);
    }

    @Test
    void correctPinnedVersions_transitiveCorrectionItselfDivergesFromPin_correctsToFixedPoint() throws IOException {
        final Path repo = projectRoot.resolve("repo");
        // grpc-core is pinned to 1.60.0; the on-disk copy (pulled in transitively by Helidon) is 1.10.0.
        final Path grpcOnDisk = createArtifactJar(repo, "io/grpc", "grpc-core", "1.10.0", "io.grpc");
        // re-resolving grpc-core at 1.60.0 pulls in a protobuf-java that is itself stale (2.0.0),
        // even though protobuf-java is pinned workspace-wide to 3.0.0.
        final Path grpcCorrected = createArtifactJar(repo, "io/grpc", "grpc-core", "1.60.0", "io.grpc");
        final Path protobufStale = createArtifactJar(
            repo, "com/google/protobuf", "protobuf-java", "2.0.0", "com.google.protobuf");
        final Path protobufCorrected = createArtifactJar(
            repo, "com/google/protobuf", "protobuf-java", "3.0.0", "com.google.protobuf");

        final ModuleVersioning versioning = moduleName -> switch (moduleName) {
            case "io.grpc" -> Optional.of(Version.parse("1.60.0"));
            case "com.google.protobuf" -> Optional.of(Version.parse("3.0.0"));
            default -> Optional.empty();
        };

        final ModuleCatalog catalog = ModuleCatalog.HeapBased.create()
            .add("io.grpc", Artifact.create("io.grpc", "grpc-core", "1.60.0", "jar"))
            .add("com.google.protobuf", Artifact.create("com.google.protobuf", "protobuf-java", "3.0.0", "jar"));

        final Artifact.Resolver resolver = stubResolver(artifact -> switch (artifact.version().get()) {
            case "1.60.0" -> Exceptional.of(List.of(grpcCorrected, protobufStale));
            case "3.0.0" -> Exceptional.of(List.of(protobufCorrected));
            default -> Exceptional.ofException(new IllegalStateException("unexpected artifact " + artifact));
        });

        final List<Path> corrected = AbstractDetectResolution.correctPinnedVersions(
            List.of(grpcOnDisk), versioning, catalog, resolver, recorder());

        assertThat(corrected).containsExactly(grpcCorrected, protobufCorrected);
    }

    @Test
    void correctPinnedVersions_sameStaleModuleReachedViaTwoChains_correctsBothNotJustTheFirst() throws IOException {
        final Path repo = projectRoot.resolve("repo");
        // grpc-core is pinned to 1.10.0 — a *downgrade* from the 1.60.0 sitting on disk, reached via
        // two independent transitive chains (so it appears twice in the candidate list).
        final Path grpcStale = createArtifactJar(repo, "io/grpc", "grpc-core", "1.60.0", "io.grpc");
        final Path grpcPinned = createArtifactJar(repo, "io/grpc", "grpc-core", "1.10.0", "io.grpc");

        final ModuleVersioning versioning = moduleName ->
            "io.grpc".equals(moduleName) ? Optional.of(Version.parse("1.10.0")) : Optional.empty();

        final ModuleCatalog catalog = ModuleCatalog.HeapBased.create()
            .add("io.grpc", Artifact.create("io.grpc", "grpc-core", "1.10.0", "jar"));

        final Artifact.Resolver resolver = stubResolver(artifact -> "1.10.0".equals(artifact.version().get())
            ? Exceptional.of(List.of(grpcPinned))
            : Exceptional.ofException(new IllegalStateException("unexpected artifact " + artifact)));

        final List<Path> corrected = AbstractDetectResolution.correctPinnedVersions(
            List.of(grpcStale, grpcStale), versioning, catalog, resolver, recorder());

        // the second occurrence must not pass through at its stale 1.60.0 — otherwise
        // dedupeByMavenCoordinate would keep it (higher version) and the downgrade pin would be lost.
        assertThat(corrected).containsExactly(grpcPinned);
    }

    @Test
    void correctPinnedVersions_staleModuleReachedTwiceAndReResolutionFails_keepsExactlyOneOnDiskCopy()
        throws IOException {

        final Path repo = projectRoot.resolve("repo");
        // grpc-core is pinned to 1.60.0 but on disk at 1.10.0, reached via two chains (listed twice);
        // re-resolving at the pin fails, so the fallback is to keep the on-disk jar.
        final Path grpcOnDisk = createArtifactJar(repo, "io/grpc", "grpc-core", "1.10.0", "io.grpc");

        final ModuleVersioning versioning = moduleName ->
            "io.grpc".equals(moduleName) ? Optional.of(Version.parse("1.60.0")) : Optional.empty();

        final ModuleCatalog catalog = ModuleCatalog.HeapBased.create()
            .add("io.grpc", Artifact.create("io.grpc", "grpc-core", "1.60.0", "jar"));

        final Artifact.Resolver resolver = stubResolver(
            _ -> Exceptional.ofException(new IllegalStateException("resolution failure")));

        final List<Path> corrected = AbstractDetectResolution.correctPinnedVersions(
            List.of(grpcOnDisk, grpcOnDisk), versioning, catalog, resolver, recorder());

        // the first occurrence keeps the on-disk jar as a fallback; the duplicate is dropped by the
        // seen-module guard — so exactly one copy survives, not zero and not two.
        assertThat(corrected).containsExactly(grpcOnDisk);
    }

    @Test
    void correctPinnedVersions_onDiskVersionAlreadyMatchesPin_returnsUnchanged() throws IOException {
        final Path repo = projectRoot.resolve("repo");
        final Path jar = createArtifactJar(repo, "io/grpc", "grpc-core", "1.60.0", "io.grpc");

        final ModuleVersioning versioning = moduleName ->
            "io.grpc".equals(moduleName) ? Optional.of(Version.parse("1.60.0")) : Optional.empty();

        final ModuleCatalog catalog = ModuleCatalog.HeapBased.create();

        final Artifact.Resolver resolver = stubResolver(_ -> {
            throw new UnsupportedOperationException("should not re-resolve when already pinned");
        });

        final List<Path> corrected = AbstractDetectResolution.correctPinnedVersions(
            List.of(jar), versioning, catalog, resolver, recorder());

        assertThat(corrected).containsExactly(jar);
    }

    // builds a bare `requires <moduleName>;` clause, optionally with a bytecode-style declared version —
    // resolveExternalArtifact only inspects the clause itself, so it needs no owning JDKModuleDescriptor.
    private static RequiresModuleDescriptor requires(final String moduleName, final Optional<Version> version) {
        final CodeModel codeModel = new ConceptualCodeModel(new NonCachingNameProvider());
        final var name = codeModel.getNameProvider().getModuleName(moduleName).orElseThrow();
        final RequiresModuleDescriptor r = RequiresModuleDescriptor.of(codeModel, name);

        version.ifPresent(v -> r.addTrait(RequiresVersionTrait.of(v)));

        return r;
    }

    @Test
    void resolveExternalArtifact_versionKnownAndModuleInCatalog_returnsArtifact() {
        final RequiresModuleDescriptor r = requires("org.mockito", Optional.empty());

        final ModuleVersioning versioning = moduleName ->
            "org.mockito".equals(moduleName) ? Optional.of(Version.parse("5.23.0")) : Optional.empty();

        final ModuleCatalog catalog = ModuleCatalog.HeapBased.create()
            .add("org.mockito", Artifact.create("org.mockito", "mockito-core", "5.23.0", "jar"));

        final Optional<Artifact> artifact = AbstractDetectResolution.resolveExternalArtifact(
            r, "example-project", versioning, catalog, recorder());

        assertThat(artifact).contains(Artifact.create("org.mockito", "mockito-core", "5.23.0", "jar"));
    }

    @Test
    void resolveExternalArtifact_versionCannotBeDetermined_logsDiagnosticNotWarn() {
        // no ModuleVersioning entry and no bytecode-declared requires-version — this is the routine,
        // expected miss from MavenModuleNaming#deriveNames's synthesized candidate names, not a real
        // problem, so it must not surface as a warning.
        final RequiresModuleDescriptor r = requires("org.mockito", Optional.empty());

        final ModuleVersioning versioning = _ -> Optional.empty();
        final ModuleCatalog catalog = ModuleCatalog.HeapBased.create();

        final List<Telemetry> emitted = new ArrayList<>();
        final TelemetryRecorder recorder = new TelemetryPublisher(
            UniformResource.createURI("test", "AbstractDetectResolutionTest"),
            emitted::add);

        final Optional<Artifact> artifact = AbstractDetectResolution.resolveExternalArtifact(
            r, "example-project", versioning, catalog, recorder);

        assertThat(artifact).isEmpty();
        assertThat(emitted).noneMatch(t -> t instanceof Warning);
        assertThat(emitted).anyMatch(t -> t instanceof Diagnostic);
    }

    @Test
    void resolveExternalArtifact_moduleNameUnknownToCatalog_logsDiagnosticNotWarn() {
        // a resolvable version, but the module name itself was never registered — again the routine,
        // expected case for most of a dependency's synthesized candidate names, not a real problem.
        final RequiresModuleDescriptor r = requires("org.mockito", Optional.empty());

        final ModuleVersioning versioning = moduleName ->
            "org.mockito".equals(moduleName) ? Optional.of(Version.parse("5.23.0")) : Optional.empty();

        final ModuleCatalog catalog = ModuleCatalog.HeapBased.create();

        final List<Telemetry> emitted = new ArrayList<>();
        final TelemetryRecorder recorder = new TelemetryPublisher(
            UniformResource.createURI("test", "AbstractDetectResolutionTest"),
            emitted::add);

        final Optional<Artifact> artifact = AbstractDetectResolution.resolveExternalArtifact(
            r, "example-project", versioning, catalog, recorder);

        assertThat(artifact).isEmpty();
        assertThat(emitted).noneMatch(t -> t instanceof Warning);
        assertThat(emitted).anyMatch(t -> t instanceof Diagnostic);
    }

    @Test
    void resolveExternalArtifact_moduleKnownButRequestedVersionUnmatched_logsWarnWithKnownVersions() {
        // the exact scenario this split exists for: version.properties pins org.mockito to a version
        // module-catalog.properties has no entry for (a stale pin), which is a real, actionable
        // problem and must not be silently downgraded to diagnostic noise.
        final RequiresModuleDescriptor r = requires("org.mockito", Optional.empty());

        final ModuleVersioning versioning = moduleName ->
            "org.mockito".equals(moduleName) ? Optional.of(Version.parse("2.19.0")) : Optional.empty();

        final ModuleCatalog catalog = ModuleCatalog.HeapBased.create()
            .add("org.mockito", Artifact.create("org.mockito", "mockito-core", "5.23.0", "jar"));

        final List<Telemetry> emitted = new ArrayList<>();
        final TelemetryRecorder recorder = new TelemetryPublisher(
            UniformResource.createURI("test", "AbstractDetectResolutionTest"),
            emitted::add);

        final Optional<Artifact> artifact = AbstractDetectResolution.resolveExternalArtifact(
            r, "example-project", versioning, catalog, recorder);

        assertThat(artifact).isEmpty();
        assertThat(emitted)
            .as("a module name the catalog knows, but not at the requested version, is a real "
                + "version-pin problem and must warn, naming the requested and known versions")
            .anyMatch(t -> t instanceof Warning
                && t.toString().contains("2.19.0")
                && t.toString().contains("5.23.0"));
    }

    // writes the given pom.xml body as the project's own pom -- the "repository" path handed to
    // projectDependencyExclusions points nowhere because the fixtures below are self-contained
    // (no parent, no dependencyManagement), so no local repository lookup is needed.
    private void writeProjectPom(final String xml) throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"), xml);
    }

    private Path noRepository() {
        return projectRoot.resolve("no-such-repository");
    }

    @Test
    void projectDependencyExclusions_noPom_returnsEmpty() {
        assertThat(AbstractDetectResolution.projectDependencyExclusions(
            projectRoot, noRepository(), recorder())).isEmpty();
    }

    @Test
    void projectDependencyExclusions_onlyDependenciesDeclaringExclusionsAreKeptKeyedByGroupAndArtifact()
        throws IOException {
        writeProjectPom("""
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>consumer</artifactId>
              <version>1.0</version>
              <dependencies>
                <dependency>
                  <groupId>org.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>2.0</version>
                  <exclusions>
                    <exclusion>
                      <groupId>commons-logging</groupId>
                      <artifactId>commons-logging</artifactId>
                    </exclusion>
                    <exclusion>
                      <groupId>org.unwanted</groupId>
                      <artifactId>*</artifactId>
                    </exclusion>
                  </exclusions>
                </dependency>
                <dependency>
                  <groupId>org.example</groupId>
                  <artifactId>plain</artifactId>
                  <version>1.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        assertThat(AbstractDetectResolution.projectDependencyExclusions(projectRoot, noRepository(), recorder()))
            .containsOnlyKeys("org.example:lib")
            .hasEntrySatisfying("org.example:lib", exclusions -> assertThat(exclusions)
                .containsExactlyInAnyOrder("commons-logging:commons-logging", "org.unwanted:*"));
    }

    @Test
    void projectDependencyExclusions_noDependencyDeclaresExclusions_returnsEmpty() throws IOException {
        writeProjectPom("""
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>consumer</artifactId>
              <version>1.0</version>
              <dependencies>
                <dependency>
                  <groupId>org.example</groupId>
                  <artifactId>plain</artifactId>
                  <version>1.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        assertThat(AbstractDetectResolution.projectDependencyExclusions(
            projectRoot, noRepository(), recorder())).isEmpty();
    }

    @Test
    void projectDependencyExclusions_unparseablePom_returnsEmpty() throws IOException {
        // PomReader#read returns Optional.empty() for a pom it can't parse (logging its own
        // warning), so exclusion detection degrades to "no exclusions" rather than failing the
        // whole resolution.
        writeProjectPom("<project><this is not well-formed xml");

        assertThat(AbstractDetectResolution.projectDependencyExclusions(
            projectRoot, noRepository(), recorder())).isEmpty();
    }
}
