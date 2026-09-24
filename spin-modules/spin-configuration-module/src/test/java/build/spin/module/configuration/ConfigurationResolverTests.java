package build.spin.module.configuration;

import build.base.flow.SubscriberRegistry;
import build.base.foundation.UniformResource;
import build.base.io.PathSetBuilder;
import build.base.json.JsonValue;
import build.base.telemetry.Commenced;
import build.base.telemetry.Telemetry;
import build.base.telemetry.TelemetryRecorder;
import build.spin.common.telemetry.TelemetryPublisher;
import build.codemodel.dependency.injection.Context;
import build.codemodel.dependency.injection.InjectionFramework;
import build.codemodel.dependency.injection.UnsatisfiedDependencyException;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ConfigurationResolver}s.
 *
 * @author brian.oliver
 * @since Apr-2021
 */
class ConfigurationResolverTests {

    /**
     * The {@link Path} to the sample configuration files and directories.
     */
    private Path path;

    /**
     * The {@link SubscriberRegistry} for observing {@link Telemetry}.
     */
    private SubscriberRegistry<Telemetry> observers;

    /**
     * The {@link TelemetryRecorder} for a {@link ConfigurationResolver}.
     */
    private TelemetryRecorder recorder;

    /**
     * The {@link ConfigurationResolver} under test.
     */
    private ConfigurationResolver resolver;

    @BeforeEach
    void onBeforeEach()
        throws URISyntaxException {

        // determine the Path in which the sample configuration files reside
        // (based on a known configuration file)
        final var sourcePath = Paths.get(
            this.getClass().getProtectionDomain().getCodeSource().getLocation().getPath());
        this.path = sourcePath.resolve("configuration/config.properties").getParent();

        // establish the SubscriberRegistry for observing Telemetry
        this.observers = new SubscriberRegistry<>();

        // establish the TelemetryRecorder
        this.recorder = new TelemetryPublisher(
            UniformResource.createURI("class", this.getClass().getCanonicalName()),
            this.observers::publish);
    }

    /**
     * Creates a {@link Context} configured with a {@link ConfigurationResolver}, including the specified {@link Path}s
     * from which to resolve configuration.
     *
     * @param paths the additional {@link Path}s to include for resolving {@link Configuration} files in search order
     * @return a new {@link Context}
     */
    private Context createContext(final Path... paths) {

        // establish the PathSet of directories in which configuration may be present, in search order
        final var builder = PathSetBuilder.create(paths);

        // include the sample path directory
        builder.add(this.path);

        final var pathSet = builder.build();

        // establish the ConfigurationResolver; @Source (on the injection point, its defining class, or an
        // ancestor) is now resolved internally by ConfigurationResolver, so this factory only supplies the
        // fallback base file name used when no @Source can be resolved
        this.resolver = new ConfigurationResolver(
            this.recorder,
            pathSet,
            dependency -> "config");

        // establish the Context for testing the ConfigurationResolver
        return InjectionFramework.create().newContext(this.resolver);
    }

    /**
     * Ensure {@link Path} can be resolved to a file.
     */
    @Test
    void shouldResolvePathToFile() {

        class Example {

            @Inject
            @Configuration
            @Source("config.properties")
            Path path;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.path).isNotNull();
    }

    /**
     * Ensure {@link Optional} {@link Path} can be resolved to a file.
     */
    @Test
    void shouldResolveOptionalPathToFile() {

        class Example {

            @Inject
            @Configuration
            @Source("config.properties")
            Optional<Path> path;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.path).isNotNull();
        assertThat(example.path.isPresent()).isTrue();
    }

    /**
     * Ensure {@link Optional} {@link Path} can be resolved to a missing file.
     */
    @Test
    void shouldResolveOptionalPathToMissingFile() {

        class Example {

            @Inject
            @Configuration
            @Source("this.source.is.missing")
            Optional<Path> path;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.path).isNotNull();
        assertThat(example.path.isPresent()).isFalse();
    }

    /**
     * Ensure {@link Properties} can be resolved from a {@code .properties} file.
     */
    @Test
    void shouldResolvePropertiesFromPropertiesFile() {

        class Example {

            @Inject
            @Configuration
            @Source("config")
            Properties properties;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.properties).isNotNull();
        assertThat(example.properties.get("message")).isEqualTo("hello world");
    }

    /**
     * Ensure {@link Properties} can be resolved from a {@code .properties} file without specifying a {@link Source}.
     */
    @Test
    void shouldResolvePropertiesFromPropertiesFileWithoutASource() {

        class Example {

            @Inject
            @Configuration
            Properties properties;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.properties).isNotNull();
        assertThat(example.properties.get("message")).isEqualTo("hello world");
    }

    /**
     * Ensure an {@link Optional} {@link Properties} can be resolved from a {@code .properties} file.
     */
    @Test
    void shouldOptionallyResolvePropertiesFromPropertiesFile() {

        class Example {

            @Inject
            @Configuration
            @Source("config")
            Optional<Properties> properties;
        }
        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.properties).isNotNull();
        assertThat(example.properties.isPresent()).isTrue();
        assertThat(example.properties.get().get("message")).isEqualTo("hello world");
    }

    /**
     * Ensure {@link Properties} can't be resolved from a missing {@code .properties} file.
     */
    @Test
    void shouldNotResolvePropertiesFromMissingPropertiesFile() {

        class Example {

            @Inject
            @Configuration
            @Source("this.source.is.missing")
            Properties properties;
        }

        Assertions.assertThrows(UnsatisfiedDependencyException.class, () -> createContext().inject(new Example()));
    }

    /**
     * Ensure {@link Optional} {@link Properties} can be resolved as {@link Optional#empty()} when a
     * {@code .properties} file is missing.
     */
    @Test
    void shouldOptionallyResolvePropertiesFromMissingPropertiesFile() {

        class Example {

            @Inject
            @Configuration
            @Source("this.source.is.missing")
            Optional<Properties> properties;
        }
        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.properties).isNotNull();
        assertThat(example.properties.isPresent()).isFalse();
    }

    /**
     * Ensure {@link JsonValue} can be resolved from a {@code .json} file.
     */
    @Test
    void shouldResolveJsonValueFromJsonFile() {

        class Example {

            @Inject
            @Configuration
            JsonValue node;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.node).isNotNull();
        assertThat(example.node.asObject().get("message").asString().value()).isEqualTo("hello world");
    }

    /**
     * Ensure {@link JsonValue} can't be resolved from a malformed {@code .json} file.
     */
    @Test
    void shouldNotResolveJsonValueFromIllegalJsonFile() {

        class Example {

            @Inject
            @Configuration
            @Source("illegal")
            JsonValue node;
        }

        Assertions.assertThrows(UnsatisfiedDependencyException.class, () -> createContext().inject(new Example()));
    }

    /**
     * Ensure the {@link Hierarchical} {@link Path}s for a file can be resolved.
     */
    @Test
    void shouldResolveHierarchicalPathsToFile() {

        class Example {

            @Inject
            @Configuration
            @Source("hierarchical.properties")
            Hierarchical<Path> paths;
        }

        // establish a Context that uses our hierarchical search paths
        final var context = createContext(
            this.path.resolve("level-1/level-2/level-3/level-4"),
            this.path.resolve("level-1/level-2/level-3"),
            this.path.resolve("level-1/level-2"),
            this.path.resolve("level-1"));

        final var example = context.inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.paths.isEmpty()).isFalse();
        assertThat(example.paths.stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected at least one path in Hierarchical<Path> but it was empty")))
            .isEqualTo(this.path.resolve("level-1/level-2/level-3/hierarchical.properties"));
        assertThat(example.paths.stream()
                .skip(1)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected at least two paths in Hierarchical<Path> but only one was present")))
            .isEqualTo(this.path.resolve("level-1/hierarchical.properties"));
    }

    /**
     * Ensure the {@link Hierarchical} {@link Path}s for a file can be resolved.
     */
    @Test
    void shouldResolveHierarchicalPropertiesFile() {

        class Example {

            @Inject
            @Configuration
            @Source("hierarchical.properties")
            Hierarchical<Properties> properties;
        }

        // establish a Context that uses our hierarchical search paths
        final var context = createContext(
            this.path.resolve("level-1/level-2/level-3/level-4"),
            this.path.resolve("level-1/level-2/level-3"),
            this.path.resolve("level-1/level-2"),
            this.path.resolve("level-1"));

        final var example = context.inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.properties.isEmpty()).isFalse();
    }

    /**
     * Ensure the {@link Hierarchical} {@link Path}s for a file can be resolved.
     */
    @Test
    void shouldResolveEmptyHierarchicalForMissingFiles() {

        class Example {

            @Inject
            @Configuration
            @Source("this.source.is.missing")
            Hierarchical<Properties> properties;
        }

        // establish a Context that uses our hierarchical search paths
        final var context = createContext(
            this.path.resolve("level-1/level-2/level-3/level-4"),
            this.path.resolve("level-1/level-2/level-3"),
            this.path.resolve("level-1/level-2"),
            this.path.resolve("level-1"));

        final var example = context.inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.properties.isEmpty()).isTrue();
    }

    /**
     * Ensure a {@code "/"}-separated {@link Named} value is navigated as a path through a {@code .json} file.
     */
    @Test
    void shouldResolveNamedNestedValueFromJsonFile() {

        class Example {

            @Inject
            @Configuration
            @Source("config.json")
            @Named("server/port")
            Optional<Integer> port;

            @Inject
            @Configuration
            @Source("config.json")
            @Named("server/verbose")
            Optional<Boolean> verbose;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.port).contains(8080);
        assertThat(example.verbose).contains(true);
    }

    /**
     * Ensure a {@code "/"}-separated {@link Named} value has its separators replaced with {@code "."} and is
     * resolved as a flat key from a {@code .properties} file.
     */
    @Test
    void shouldResolveNamedNestedValueFromPropertiesFile() {

        class Example {

            @Inject
            @Configuration
            @Source("config.properties")
            @Named("server/port")
            Optional<Integer> port;

            @Inject
            @Configuration
            @Source("config.properties")
            @Named("server/verbose")
            Optional<Boolean> verbose;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.port).contains(8080);
        assertThat(example.verbose).contains(true);
    }

    /**
     * Ensure a required (non-{@link Optional}) {@link Named} value can be resolved and coerced.
     */
    @Test
    void shouldResolveRequiredNamedValue() {

        class Example {

            @Inject
            @Configuration
            @Source("config.json")
            @Named("server/port")
            Integer port;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.port).isEqualTo(8080);
    }

    /**
     * Ensure a required (non-{@link Optional}) {@link Named} value can be resolved and coerced to a primitive
     * {@code int} and {@code boolean}, from a {@code .json} file.
     */
    @Test
    void shouldResolveRequiredPrimitiveNamedValueFromJsonFile() {

        class Example {

            @Inject
            @Configuration
            @Source("config.json")
            @Named("server/port")
            int port;

            @Inject
            @Configuration
            @Source("config.json")
            @Named("server/verbose")
            boolean verbose;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.port).isEqualTo(8080);
        assertThat(example.verbose).isTrue();
    }

    /**
     * Ensure a required (non-{@link Optional}) {@link Named} value can be resolved and coerced to a primitive
     * {@code int} and {@code boolean}, from a {@code .properties} file.
     */
    @Test
    void shouldResolveRequiredPrimitiveNamedValueFromPropertiesFile() {

        class Example {

            @Inject
            @Configuration
            @Source("config.properties")
            @Named("server/port")
            int port;

            @Inject
            @Configuration
            @Source("config.properties")
            @Named("server/verbose")
            boolean verbose;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.port).isEqualTo(8080);
        assertThat(example.verbose).isTrue();
    }

    /**
     * Ensure an {@link Optional} {@link Named} value resolves to {@link Optional#empty()} when the named value
     * doesn't exist.
     */
    @Test
    void shouldResolveOptionalNamedValueAsEmptyWhenMissing() {

        class Example {

            @Inject
            @Configuration
            @Source("config.json")
            @Named("server/missing")
            Optional<String> missing;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.missing).isEmpty();
    }

    /**
     * Ensure a required (non-{@link Optional}) {@link Named} value throws when it doesn't exist.
     */
    @Test
    void shouldNotResolveRequiredNamedValueWhenMissing() {

        class Example {

            @Inject
            @Configuration
            @Source("config.json")
            @Named("server/missing")
            String missing;
        }

        Assertions.assertThrows(UnsatisfiedDependencyException.class, () -> createContext().inject(new Example()));
    }

    /**
     * Ensure a {@code .json} {@link Configuration} file is only read/parsed once, even when multiple
     * {@code @Named} values (and a whole-file value) are resolved from it, ie: {@link ConfigurationResolver}
     * memoizes previously parsed files.
     */
    @Test
    void shouldOnlyReadJsonFileOnceForMultipleValues() {

        class Example {

            @Inject
            @Configuration
            @Source("config.json")
            @Named("server/port")
            Optional<Integer> port;

            @Inject
            @Configuration
            @Source("config.json")
            @Named("server/verbose")
            Optional<Boolean> verbose;

            @Inject
            @Configuration
            @Source("config.json")
            Optional<JsonValue> node;
        }

        final var reads = new AtomicInteger();
        this.observers.subscribe(telemetry -> {
            if (telemetry instanceof Commenced) {
                reads.incrementAndGet();
            }
        });

        final var context = createContext();
        final var example = context.inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.port).contains(8080);
        assertThat(example.verbose).contains(true);
        assertThat(example.node).isPresent();
        assertThat(reads.get()).isEqualTo(1);
    }

    /**
     * Ensure a {@code .properties} {@link Configuration} file is only read/parsed once, even when multiple
     * {@code @Named} values (and a whole-file value) are resolved from it, ie: {@link ConfigurationResolver}
     * memoizes previously parsed files.
     */
    @Test
    void shouldOnlyReadPropertiesFileOnceForMultipleValues() {

        class Example {

            @Inject
            @Configuration
            @Source("config.properties")
            @Named("server/port")
            Optional<Integer> port;

            @Inject
            @Configuration
            @Source("config.properties")
            @Named("server/verbose")
            Optional<Boolean> verbose;

            @Inject
            @Configuration
            @Source("config.properties")
            Optional<Properties> properties;
        }

        final var reads = new AtomicInteger();
        this.observers.subscribe(telemetry -> {
            if (telemetry instanceof Commenced) {
                reads.incrementAndGet();
            }
        });

        final var context = createContext();
        final var example = context.inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.port).contains(8080);
        assertThat(example.verbose).contains(true);
        assertThat(example.properties).isPresent();
        assertThat(reads.get()).isEqualTo(1);
    }

    /**
     * Ensure a {@link Source} declared on the class defining the injection point is used when the injection
     * point itself has no {@link Source}.
     */
    @Test
    void shouldResolveSourceDeclaredOnTheDefiningClass() {

        @Source("config")
        class Example {

            @Inject
            @Configuration
            Properties properties;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.properties).isNotNull();
        assertThat(example.properties.get("message")).isEqualTo("hello world");
    }

    /**
     * Ensure a {@link Source} declared on an interface implemented by the defining class is used when neither
     * the injection point nor the defining class itself has a {@link Source}.
     */
    @Test
    void shouldResolveSourceDeclaredOnAnImplementedInterface() {

        @Source("config")
        interface ConfiguredSource {
        }

        class Example implements ConfiguredSource {

            @Inject
            @Configuration
            Properties properties;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.properties).isNotNull();
        assertThat(example.properties.get("message")).isEqualTo("hello world");
    }

    /**
     * Ensure a {@link Source} declared on a superclass of the defining class is used when neither the injection
     * point nor the defining class itself has a {@link Source}.
     */
    @Test
    void shouldResolveSourceDeclaredOnASuperclass() {

        @Source("config")
        class ConfiguredBase {
        }

        class Example extends ConfiguredBase {

            @Inject
            @Configuration
            Properties properties;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.properties).isNotNull();
        assertThat(example.properties.get("message")).isEqualTo("hello world");
    }

    /**
     * Ensure a {@link Source} declared on the defining class wins over one declared on an ancestor (superclass),
     * ie: the closest {@link Source} wins.
     */
    @Test
    void shouldPreferSourceOnTheDefiningClassOverAnAncestor() {

        @Source("this.source.is.missing")
        class WrongSourceBase {
        }

        @Source("config")
        class Example extends WrongSourceBase {

            @Inject
            @Configuration
            Properties properties;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.properties).isNotNull();
        assertThat(example.properties.get("message")).isEqualTo("hello world");
    }

    /**
     * Ensure a {@link Source} declared on a nearer ancestor wins over one declared on a farther ancestor, ie:
     * the closest {@link Source} wins.
     */
    @Test
    void shouldPreferNearerAncestorSourceOverFartherAncestor() {

        @Source("this.source.is.missing")
        class Grandparent {
        }

        @Source("config")
        class Parent extends Grandparent {
        }

        class Example extends Parent {

            @Inject
            @Configuration
            Properties properties;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.properties).isNotNull();
        assertThat(example.properties.get("message")).isEqualTo("hello world");
    }

    /**
     * Ensure a {@link Source} declared directly on the injection point wins over one declared on the defining
     * class, ie: the closest {@link Source} wins.
     */
    @Test
    void shouldPreferFieldLevelSourceOverClassLevelSource() {

        @Source("this.source.is.missing")
        class Example {

            @Inject
            @Configuration
            @Source("config")
            Properties properties;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.properties).isNotNull();
        assertThat(example.properties.get("message")).isEqualTo("hello world");
    }

    /**
     * A {@link Record} of {@code @Named} {@link Configuration} values, used to test {@code record} injection.
     */
    private record Server(@Named("server/port") Integer port,
                          @Named("server/verbose") Boolean verbose,
                          @Named("server/missing") Optional<String> missing) {
    }

    /**
     * Ensure a {@link Record} can be resolved by resolving each of its {@code @Named} components from a
     * {@code .json} file and constructing the record from them.
     */
    @Test
    void shouldResolveRecordFromJsonFile() {

        class Example {

            @Inject
            @Configuration
            @Source("config.json")
            Server server;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.server).isNotNull();
        assertThat(example.server.port()).isEqualTo(8080);
        assertThat(example.server.verbose()).isTrue();
        assertThat(example.server.missing()).isEmpty();
    }

    /**
     * Ensure an {@link Optional} {@link Record} can be resolved.
     */
    @Test
    void shouldResolveOptionalRecordFromJsonFile() {

        class Example {

            @Inject
            @Configuration
            @Source("config.json")
            Optional<Server> server;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.server).isPresent();
        assertThat(example.server.get().port()).isEqualTo(8080);
    }

    /**
     * A {@link Record} with a bare (non-{@link Optional}) component whose {@code @Named} value doesn't exist
     * in the {@link Configuration} file, used to test that the whole record is left unresolved.
     */
    private record ServerWithMissingRequiredValue(@Named("server/port") Integer port,
                                                  @Named("server/missing") String missing) {
    }

    /**
     * Ensure a required (non-{@link Optional}) {@link Record} component whose value doesn't exist causes the
     * whole record to be treated as an unsatisfied {@link Dependency}, rather than constructing the record with
     * a {@code null} argument.
     */
    @Test
    void shouldNotResolveRecordWhenARequiredComponentValueIsMissing() {

        class Example {

            @Inject
            @Configuration
            @Source("config.json")
            ServerWithMissingRequiredValue server;
        }

        Assertions.assertThrows(UnsatisfiedDependencyException.class, () -> createContext().inject(new Example()));
    }

    /**
     * A {@link Record} with a component that isn't annotated {@code @Named}, used to test that resolving such a
     * record fails fast.
     */
    private record ServerWithoutNamedComponent(Integer port) {
    }

    /**
     * Ensure resolving a {@link Record} with a component that isn't annotated {@code @Named} throws.
     */
    @Test
    void shouldNotResolveRecordWithoutNamedComponent() {

        class Example {

            @Inject
            @Configuration
            @Source("config.json")
            ServerWithoutNamedComponent server;
        }

        Assertions.assertThrows(IllegalStateException.class, () -> createContext().inject(new Example()));
    }

    /**
     * A {@link Record} whose component is itself a {@link Record}, used to test that nested records are
     * resolved recursively without needing a {@code @Named} value of their own.
     */
    private record Config(Server server, @Named("message") String message) {
    }

    /**
     * Ensure a {@link Record} component that's itself a {@link Record} is resolved recursively, rather than
     * requiring a {@code @Named} value for the nested record itself.
     */
    @Test
    void shouldResolveNestedRecordFromJsonFile() {

        class Example {

            @Inject
            @Configuration
            @Source("config.json")
            Config config;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.config).isNotNull();
        assertThat(example.config.server().port()).isEqualTo(8080);
        assertThat(example.config.server().verbose()).isTrue();
        assertThat(example.config.message()).isEqualTo("hello world");
    }

    /**
     * A {@link Record} whose compact constructor always throws, used to test that a failure constructing the
     * record (as opposed to failing to resolve its components) is wrapped in a {@link RuntimeException} with a
     * clear message, rather than leaking the raw reflective failure.
     */
    private record AlwaysInvalidRecord(@Named("server/port") Integer port) {

        private AlwaysInvalidRecord {
            throw new IllegalStateException("always invalid");
        }
    }

    /**
     * Ensure a failure constructing a {@link Record} (its compact constructor throwing) is wrapped in a
     * {@link RuntimeException} that names the {@link Record} {@link Class}.
     */
    @Test
    void shouldWrapConstructorFailureWhenResolvingRecord() {

        class Example {

            @Inject
            @Configuration
            @Source("config.json")
            AlwaysInvalidRecord invalid;
        }

        final var exception = Assertions.assertThrows(
            RuntimeException.class, () -> createContext().inject(new Example()));

        assertThat(exception).hasMessageContaining("Failed to construct Configuration record");
    }

    /**
     * Ensure an {@link Optional} {@link Record} whose bare (non-{@link Optional}) component's value doesn't exist
     * resolves to {@link Optional#empty()}, rather than throwing or constructing the record with a {@code null}
     * argument.
     */
    @Test
    void shouldResolveOptionalRecordAsEmptyWhenARequiredComponentValueIsMissing() {

        class Example {

            @Inject
            @Configuration
            @Source("config.json")
            Optional<ServerWithMissingRequiredValue> server;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.server).isEmpty();
    }

    /**
     * A {@link Record} whose nested {@link Record} component ({@link ServerWithMissingRequiredValue}) has a
     * bare component whose value doesn't exist, used to test that the failure propagates out of the nested
     * record to the enclosing one.
     */
    private record ConfigWithMissingNestedValue(ServerWithMissingRequiredValue server,
                                                @Named("message") String message) {
    }

    /**
     * Ensure a missing required value on a nested {@link Record} component causes the whole enclosing
     * {@link Record} to be treated as an unsatisfied {@link Dependency}, not just the nested one.
     */
    @Test
    void shouldNotResolveRecordWhenANestedRecordComponentValueIsMissing() {

        class Example {

            @Inject
            @Configuration
            @Source("config.json")
            ConfigWithMissingNestedValue config;
        }

        Assertions.assertThrows(UnsatisfiedDependencyException.class, () -> createContext().inject(new Example()));
    }

    /**
     * A {@link Record} whose component is an {@link Optional}-wrapped {@link Record}, used to test that a nested
     * {@link Optional} {@link Record} component is resolved recursively, the same as a bare nested {@link Record}
     * component, without needing a {@code @Named} value of its own.
     */
    private record ConfigWithOptionalNestedRecord(Optional<Server> server, @Named("message") String message) {
    }

    /**
     * Ensure a {@link Record} component that's an {@link Optional}-wrapped {@link Record} is resolved recursively
     * when its value is present.
     */
    @Test
    void shouldResolveNestedOptionalRecordFromJsonFile() {

        class Example {

            @Inject
            @Configuration
            @Source("config.json")
            ConfigWithOptionalNestedRecord config;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.config).isNotNull();
        assertThat(example.config.server()).isPresent();
        assertThat(example.config.server().get().port()).isEqualTo(8080);
        assertThat(example.config.server().get().verbose()).isTrue();
        assertThat(example.config.message()).isEqualTo("hello world");
    }

    /**
     * A {@link Record} whose component is an {@link Optional}-wrapped {@link Record}
     * ({@link ServerWithMissingRequiredValue}) that has a bare component whose value doesn't exist, used to test
     * that the nested {@link Optional} {@link Record} resolves to {@link Optional#empty()} rather than failing
     * the enclosing {@link Record}.
     */
    private record ConfigWithMissingOptionalNestedValue(Optional<ServerWithMissingRequiredValue> server,
                                                        @Named("message") String message) {
    }

    /**
     * Ensure a missing required value on an {@link Optional}-wrapped nested {@link Record} component resolves that
     * component to {@link Optional#empty()}, rather than failing the whole enclosing {@link Record}.
     */
    @Test
    void shouldResolveOptionalNestedRecordAsEmptyWhenARequiredComponentValueIsMissing() {

        class Example {

            @Inject
            @Configuration
            @Source("config.json")
            ConfigWithMissingOptionalNestedValue config;
        }

        final var example = createContext().inject(new Example());

        assertThat(example).isNotNull();
        assertThat(example.config).isNotNull();
        assertThat(example.config.server()).isEmpty();
        assertThat(example.config.message()).isEqualTo("hello world");
    }
}
