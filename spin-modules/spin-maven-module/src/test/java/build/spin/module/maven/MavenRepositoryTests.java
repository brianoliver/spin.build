package build.spin.module.maven;

import build.base.foundation.Exceptional;
import build.base.version.Version;
import build.base.foundation.UniformResource;
import build.base.telemetry.TelemetryRecorder;
import build.codemodel.foundation.CodeModel;
import build.codemodel.foundation.naming.NonCachingNameProvider;
import build.codemodel.jdk.JDKCodeModel;
import build.spin.common.telemetry.TelemetryPublisher;
import build.spin.module.modulesystem.Artifact;
import build.spin.module.modulesystem.ModuleCatalog;
import build.codemodel.jdk.descriptor.JDKModuleDescriptor;
import build.spin.module.modulesystem.ModuleReference;
import build.spin.module.modulesystem.ModuleVersioning;
import build.codemodel.dependency.injection.Context;
import build.codemodel.dependency.injection.InjectionFramework;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link MavenRepository}s.
 *
 * @author brian.oliver
 * @since Dec-2022
 */
public class MavenRepositoryTests {

    /**
     * The {@link MavenRepository} under test.
     */
    private MavenRepository repository;

    /**
     * The {@link ModuleCatalog} for mapping modules to {@link Artifact}s.
     */
    private ModuleCatalog moduleCatalog;

    /**
     * The {@link ModuleVersioning} to determine the {@link Artifact} versions.
     */
    private ModuleVersioning versioning;

    @BeforeEach
    public void onBeforeEach() {
        final TelemetryRecorder recorder = new TelemetryPublisher(
            UniformResource.createURI("maven", "MavenRepository"),
            System.out::println);

        final CodeModel codeModel = new JDKCodeModel(new NonCachingNameProvider());

        final Context context = InjectionFramework.create().newContext();
        context.bind(TelemetryRecorder.class).to(recorder);
        context.bind(CodeModel.class).to(codeModel);

        this.repository = context.create(MavenRepository.class);

        this.moduleCatalog = ModuleCatalog.HeapBased.create();
        this.versioning = new ModuleVersioning() {
            @Override
            public Optional<Version> getVersion(final String moduleName) {
                return Optional.empty();
            }

        };
    }

    @Test
    void shouldResolveBaseFoundationDependency() {

        final Artifact artifact = Artifact.parse("build.base:base-foundation:0.29.0");

        final Exceptional<Path> path = this.repository.resolve(artifact);

        assertThat(path.isPresent()).isTrue();

        final Exceptional<JDKModuleDescriptor> moduleDescriptor =
            this.repository.getModuleDescriptor(artifact, this.moduleCatalog, this.versioning);

        assertThat(moduleDescriptor.isPresent()).isTrue();
    }

    @Test
    void shouldResolveSLF4JDependency() {

        final Artifact artifact = Artifact.parse("org.slf4j:slf4j-api:1.7.28");

        final Exceptional<Path> optional = this.repository.resolve(artifact);

        assertThat(optional.isPresent()).isTrue();

        final Exceptional<JDKModuleDescriptor> moduleDescriptor =
            this.repository.getModuleDescriptor(artifact, this.moduleCatalog, this.versioning);

        assertThat(moduleDescriptor.isPresent()).isTrue();
    }

    @Test
    void shouldResolveMockitoDependency() {

        final Artifact artifact = Artifact.parse("org.mockito:mockito-core:jar:3.7.7");

        final Exceptional<Path> optional = this.repository.resolve(artifact);

        assertThat(optional.isPresent()).isTrue();

        final Exceptional<JDKModuleDescriptor> moduleDescriptor =
            this.repository.getModuleDescriptor(artifact, this.moduleCatalog, this.versioning);

        moduleDescriptor.orElseThrow(() -> new AssertionError("Expected JDKModuleDescriptor for artifact [" + artifact + "] but none was resolved"));
        assertThat(moduleDescriptor.isPresent()).isTrue();
    }

    @Test
    void shouldResolveMavenCoreDependency() {

        final Artifact artifact = Artifact.parse("org.apache.maven:maven-core:jar:3.8.6");

        final Exceptional<Path> optional = this.repository.resolve(artifact);

        assertThat(optional.isPresent()).isTrue();

        final Exceptional<JDKModuleDescriptor> moduleDescriptor =
            this.repository.getModuleDescriptor(artifact, this.moduleCatalog, this.versioning);

        assertThat(moduleDescriptor.isPresent()).isTrue();
    }

    @Test
    void shouldResolveDependencyModuleDescriptorsForMavenCore() {

        final Artifact artifact = Artifact.parse("org.apache.maven:maven-core:jar:3.8.6");

        final JDKModuleDescriptor moduleDescriptor = this.repository
            .getModuleDescriptor(artifact, this.moduleCatalog, this.versioning)
            .orElseThrow(() -> new AssertionError("Expected JDKModuleDescriptor for artifact [" + artifact + "] but none was resolved"));

        assertThat(moduleDescriptor.requiresClauses().count()).isEqualTo(24L);
    }

    @Test
    void shouldResolveJacksonDatabindModuleDescriptor() {
        final Artifact artifact = Artifact.parse("com.fasterxml.jackson.core:jackson-databind:2.12.2");

        final Exceptional<Path> path = this.repository.resolve(artifact);

        assertThat(path.isPresent()).isTrue();

        this.moduleCatalog.add("com.fasterxml.jackson.databind", artifact);

        final Exceptional<JDKModuleDescriptor> moduleDescriptor =
            this.repository.getModuleDescriptor(artifact, this.moduleCatalog, this.versioning);

        assertThat(moduleDescriptor.isPresent()).isTrue();
    }

    @Test
    void shouldResolveTransitiveDependenciesForJacksonDatabind() {

        final Artifact artifact = Artifact.parse("com.fasterxml.jackson.core:jackson-databind:2.12.2");

        final List<Path> paths = this.repository.resolveTransitive(artifact)
            .orElseThrow(() -> new AssertionError(
                "Expected a transitive path list for [" + artifact + "] but none was resolved"));

        assertThat(paths).extracting(path -> path.getFileName().toString())
            .contains("jackson-databind-2.12.2.jar", "jackson-core-2.12.2.jar", "jackson-annotations-2.12.2.jar");
    }

    @Test
    void shouldApplyEdgeExclusionsWhenResolvingTransitiveDependencies() {

        // jackson-databind depends directly on jackson-core and jackson-annotations. The Set overload
        // seeds the PomResolver BFS with a "groupId:artifactId" exclusion already applied to the root
        // artifact's own closure -- the <exclusions> a consuming pom declared on this dependency edge,
        // which the bare Artifact no longer carries -- so jackson-annotations must be dropped while
        // the unexcluded jackson-core survives.
        final Artifact artifact = Artifact.parse("com.fasterxml.jackson.core:jackson-databind:2.12.2");

        final List<Path> paths = this.repository
            .resolveTransitive(artifact, Set.of("com.fasterxml.jackson.core:jackson-annotations"))
            .orElseThrow(() -> new AssertionError(
                "Expected a transitive path list for [" + artifact + "] but none was resolved"));

        assertThat(paths).extracting(path -> path.getFileName().toString())
            .contains("jackson-databind-2.12.2.jar", "jackson-core-2.12.2.jar")
            .doesNotContain("jackson-annotations-2.12.2.jar");
    }

    @Test
    void shouldNotResolveModuleReferenceForUnknownModule() {

        final Artifact artifact = Artifact.parse("io.undertow:undertow-core:2.3.2.Final");

        // ensure the ModuleReference can't be resolved (with an empty ModuleCatalog)
        assertThat(this.repository.getModuleReference(artifact, this.moduleCatalog).isEmpty()).isTrue();
    }

    @Test
    void shouldResolveUndertowModuleDescriptor() {

        final Artifact artifact = Artifact.parse("io.undertow:undertow-core:2.3.2.Final");

        // include the Artifact in the ModuleCatalog
        final var moduleName = "io.undertow";
        this.moduleCatalog.add(moduleName, artifact);

        // ensure we can resolve the ModuleReference for the Artifact
        final Exceptional<ModuleReference> exceptional = this.repository
            .getModuleReference(artifact, this.moduleCatalog);

        assertThat(exceptional.isPresent()).isTrue();

        var moduleReference = exceptional.orElseThrow(() -> new AssertionError("Expected ModuleReference for artifact [" + artifact + "] but none was resolved"));

        assertThat(moduleReference.name()).isEqualTo(moduleName);
        assertThat(moduleReference.version().orElseThrow(() -> new AssertionError("Expected version on ModuleReference [" + moduleReference.name() + "] but it was empty")).get()).isEqualTo("2.3.2.Final");

        // ensure we can resolve the ModuleDescriptor
        final JDKModuleDescriptor moduleDescriptor = this.repository
            .getModuleDescriptor(artifact, this.moduleCatalog, this.versioning)
            .orElseThrow(() -> new AssertionError("Expected JDKModuleDescriptor for artifact [" + artifact + "] but none was resolved"));

        assertThat(moduleDescriptor.requiresClauses().count()).isEqualTo(7L);
    }
}
