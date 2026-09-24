package build.spin.module.configuration;

/*-
 * #%L
 * Spin Configuration Module
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

import build.base.foundation.Strings;
import build.base.foundation.memoizer.Memoizer;
import build.base.foundation.stream.Streamable;
import build.base.io.PathSet;
import build.base.json.Json;
import build.base.json.JsonBoolean;
import build.base.json.JsonNumber;
import build.base.json.JsonObject;
import build.base.json.JsonParseException;
import build.base.json.JsonString;
import build.base.json.JsonValue;
import build.base.telemetry.TelemetryRecorder;
import build.codemodel.dependency.injection.Binding;
import build.codemodel.dependency.injection.Dependency;
import build.codemodel.dependency.injection.InjectionPointDependency;
import build.codemodel.dependency.injection.Resolver;
import build.codemodel.dependency.injection.ValueBinding;
import build.codemodel.foundation.usage.AnnotationTypeUsage;
import build.codemodel.jdk.TypeUsages;
import build.spin.common.util.AnnotationValues;
import jakarta.inject.Named;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A {@link Resolver} of {@link Configuration}s values, to be deserialized from external {@link Path}s.
 *
 * @author brian.oliver
 * @since Mar-2021
 */
public class ConfigurationResolver
    implements Resolver<Object> {

    /**
     * The {@link TelemetryRecorder} to output telemetry when {@link Configuration} files are read and parsed.
     */
    private final TelemetryRecorder recorder;

    /**
     * The {@link PathSet} of locations, in order of search, in which to find {@link Configuration}s.
     */
    private final PathSet configurationPaths;

    /**
     * The {@link Function} to produce a fallback {@link Configuration} file name for a {@link Dependency} requiring
     * injection of a {@link Configuration}, used only when no {@link Source} can be resolved for the {@link Dependency}
     * (see {@link #resolveSource(Dependency)}).
     */
    private final Function<? super Dependency, String> baseFileNameFactory;

    /**
     * A {@link Map} from {@link Configuration} file types (extension) to the {@link FileTypeResolver} describing
     * how to read/parse and resolve values from files of that type.
     * <p>
     * Common file types (extensions) are ".properties", ".json", ".xml" and ".yaml"
     * </p>
     */
    private final LinkedHashMap<String, FileTypeResolver<?>> resolvers;

    /**
     * A {@link Memoizer} of previously parsed {@link Configuration} files (a {@link JsonValue} or {@link Properties},
     * depending on file type), keyed by {@link Path} (which are always unique), so that multiple {@code @Named} (or
     * other) values sourced from the same file don't cause it to be re-read/re-parsed.
     */
    private final Memoizer<Path, Object> configurationsByPath;

    /**
     * Constructs a {@link ConfigurationResolver} for the specified {@link FileSystem}.
     *
     * @param recorder            the {@link TelemetryRecorder}
     * @param configurationPaths  the {@link PathSet} defining the {@link Path}s in which to search for
     *                            {@link Configuration} files
     * @param baseFileNameFactory the {@link Function} to produce a fallback {@link Configuration} file name, used
     *                            when no {@link Source} can be resolved for a {@link Dependency}
     */
    public ConfigurationResolver(final TelemetryRecorder recorder,
                                 final PathSet configurationPaths,
                                 final Function<? super Dependency, String> baseFileNameFactory) {

        this.recorder = Objects.requireNonNull(recorder, "The TelemetryRecorder must not be null");
        this.configurationPaths = Objects.requireNonNull(configurationPaths,
            "The Configuration paths (PathSet) must not be null");
        this.baseFileNameFactory = Objects.requireNonNull(baseFileNameFactory,
            "The File Name Factory must not be null");

        // establish the memo of previously parsed Configuration files
        this.configurationsByPath = Memoizer.of(this::parse).concurrent().build();

        // establish the supported file type resolvers
        this.resolvers = new LinkedHashMap<>();
        this.resolvers.put(".json", FileTypeResolver.json(this.recorder));
        this.resolvers.put(".properties", FileTypeResolver.properties(this.recorder));
    }

    /**
     * Coerces the raw {@link String} value to the required {@link Class}.
     *
     * @param raw           the raw {@link String} value
     * @param requiredClass the required {@link Class} to coerce the value to
     * @return the coerced {@link Object}
     */
    private static Object coerce(final String raw, final Class<?> requiredClass) {
        return raw == null ? null : Strings.convert(raw, requiredClass);
    }

    /**
     * Resolves a single, specifically named value from a parsed {@link Configuration} value, coerced to a
     * required {@link Class}.
     *
     * @param <A> the type of the parsed {@link Configuration} value
     * @param <B> the required {@link Class}
     * @param <C> the type of the name
     * @param <R> the type of the resolved result
     */
    @FunctionalInterface
    private interface NamedValueResolver<A, B, C, R> {

        R resolve(A a, B b, C c);
    }

    /**
     * Reads/parses a {@link Configuration} file located at a {@link Path} into a value of type {@code T}.
     *
     * @param <T> the type of value produced when the file is read/parsed
     */
    @FunctionalInterface
    private interface Reader<T> {

        T read(Path path);
    }

    /**
     * Describes how to resolve {@link Configuration} values from a particular type of file: how to read/parse the
     * file into a value of type {@code T} (eg: a {@link JsonValue} or {@link Properties}), and how to resolve a
     * single, specifically named (and possibly nested, {@code "/"}-separated) value, coerced to a required
     * {@link Class}, from that parsed value.
     *
     * @param <T> the type of value produced when a file of this type is read/parsed
     */
    private interface FileTypeResolver<T> {

        /**
         * The {@link Reader} to read/parse a file located at a {@link Path} into a value of type {@code T}.
         *
         * @return the {@link Reader}
         */
        Reader<T> reader();

        /**
         * The {@link NamedValueResolver} to resolve a single, specifically named (and possibly nested, {@code "/"}
         * -separated) value, coerced to the required {@link Class}, from a parsed value of type {@code T}.
         *
         * @return the {@link NamedValueResolver}
         */
        NamedValueResolver<T, Class<?>, String, Object> resolver();

        /**
         * Creates the {@link FileTypeResolver} for {@code .json} {@link Configuration} files.
         *
         * @param recorder the {@link TelemetryRecorder} to use when reading/parsing files
         * @return the {@code .json} {@link FileTypeResolver}
         */
        static FileTypeResolver<JsonValue> json(final TelemetryRecorder recorder) {
            return new FileTypeResolver<JsonValue>() {
                @Override
                public Reader<JsonValue> reader() {
                    return path -> {
                        try (var activity = recorder.commence("Reading Configuration File [%s]", path)) {
                            try (var reader = Files.newBufferedReader(path)) {
                                return Json.parse(reader);
                            } catch (final IOException | JsonParseException e) {
                                activity.completeExceptionally(e);
                                return null;
                            }
                        }
                    };
                }

                @Override
                public NamedValueResolver<JsonValue, Class<?>, String, Object> resolver() {
                    return (json, requiredClass, name) -> coerce(navigateJson(json, name.split("/")), requiredClass);
                }

                /**
                 * Navigates a {@link JsonValue} tree, following the specified path segments, returning the raw
                 * {@link String} representation of the scalar value found at the end of the path.
                 *
                 * @param root     the root {@link JsonValue}
                 * @param segments the path segments to navigate
                 * @return the raw {@link String} value, or {@code null} if the path can't be navigated or resolves
                 *         to a non-scalar value
                 */
                private static String navigateJson(final JsonValue root, final String[] segments) {
                    var current = root;

                    for (final String segment : segments) {
                        if (!(current instanceof JsonObject object) || !object.has(segment)) {
                            return null;
                        }
                        current = object.get(segment);
                    }

                    return switch (current) {
                        case JsonString string -> string.value();
                        case JsonNumber number -> number.toNumber().toString();
                        case JsonBoolean bool -> String.valueOf(bool.value());
                        default -> null;
                    };
                }
            };
        }

        /**
         * Creates the {@link FileTypeResolver} for {@code .properties} {@link Configuration} files.
         *
         * @param recorder the {@link TelemetryRecorder} to use when reading/parsing files
         * @return the {@code .properties} {@link FileTypeResolver}
         */
        static FileTypeResolver<Properties> properties(final TelemetryRecorder recorder) {
            return new FileTypeResolver<Properties>() {
                @Override
                public Reader<Properties> reader() {
                    return path -> {
                        try (var activity = recorder.commence("Reading Configuration File [%s]", path)) {
                            try (var reader = Files.newBufferedReader(path)) {
                                final var properties = new Properties();
                                properties.load(reader);
                                return properties;
                            } catch (final IOException e) {
                                activity.completeExceptionally(e);
                                return null;
                            }
                        }
                    };
                }

                @Override
                public NamedValueResolver<Properties, Class<?>, String, Object> resolver() {
                    return (properties, requiredClass, name) ->
                        coerce(properties.getProperty(name.replace('/', '.')), requiredClass);
                }
            };
        }
    }

    /**
     * Resolves the value for the {@link Configuration} file located at the specified {@link Path}, using the
     * specified {@link FileTypeResolver}, either the single specifically named value within it (when {@code name}
     * is present), or the whole parsed value itself (when it's an instance of the required {@link Class}).
     *
     * @param fileTypeResolver the {@link FileTypeResolver} for the type of file located at the {@link Path}
     * @param path             the {@link Path} to the {@link Configuration} file
     * @param requiredClass    the required {@link Class}
     * @param name             the (possibly {@code "/"}-separated) name of a single value to resolve, if any
     * @param <T>              the type of value produced when the file is read/parsed
     * @return the resolved {@link Object}, or {@code null} if it can't be resolved
     */
    private <T> Object resolveValue(final FileTypeResolver<T> fileTypeResolver,
                                    final Path path,
                                    final Class<?> requiredClass,
                                    final Optional<String> name) {

        @SuppressWarnings("unchecked")
        final var value = (T) this.configurationsByPath.compute(path);

        if (value == null) {
            return null;
        }

        return name
            .map(named -> fileTypeResolver.resolver().resolve(value, requiredClass, named))
            .orElseGet(() -> requiredClass.isInstance(value) ? value : null);
    }

    /**
     * Parses the {@link Configuration} file located at the specified {@link Path}, based on its file type
     * (extension), producing either a {@link JsonValue} or {@link Properties} (or {@code null}, if the file type
     * isn't supported, or it can't be read/parsed).
     *
     * @param path the {@link Path} to the {@link Configuration} file
     * @return the parsed {@link JsonValue} or {@link Properties}, or {@code null}
     */
    private Object parse(final Path path) {
        final var fileName = path.toString();
        final var extension = fileName.substring(fileName.lastIndexOf("."));

        final var fileTypeResolver = this.resolvers.get(extension);
        return fileTypeResolver == null ? null : fileTypeResolver.reader().read(path);
    }

    private static Class<?> getRequiredClass(final Dependency dependency) {
        return TypeUsages.getThreadContextClass(dependency.typeUsage()).orElse(null);
    }

    /**
     * Resolves the {@link Source#value()} to use as the base {@link Configuration} file name for the specified
     * {@link Dependency}, preferring (in order, "closest" first):
     * <ol>
     *     <li>a {@link Source} declared directly on the injection point (the field/parameter itself)</li>
     *     <li>a {@link Source} declared on the class defining the injection point (eg: the {@code Task} class or
     *     interface), or on one of that class's ancestors (superclasses and/or implemented interfaces), nearest
     *     ancestor first</li>
     * </ol>
     *
     * @param dependency the {@link Dependency}
     * @return an {@link Optional} containing the resolved {@link Source#value()}, or {@link Optional#empty()} when
     *         no {@link Source} is declared anywhere
     */
    private static Optional<String> resolveSource(final Dependency dependency) {
        final var direct = firstSource(dependency.typeUsage().traits(AnnotationTypeUsage.class));

        if (direct.isPresent()) {
            return direct;
        }

        if (dependency instanceof InjectionPointDependency injectionPointDependency
            && injectionPointDependency.injectionPoint() != null) {

            final var typeDescriptor = injectionPointDependency.injectionPoint().typeDescriptor();

            return firstSource(Stream.concat(Stream.of(typeDescriptor), typeDescriptor.ancestors())
                .flatMap(ancestor -> ancestor.traits(AnnotationTypeUsage.class)));
        }

        return Optional.empty();
    }

    /**
     * Obtains the {@link Source#value()} of the first {@link Source} {@link AnnotationTypeUsage} in the specified
     * {@link Stream}, if any.
     *
     * @param annotations the {@link Stream} of {@link AnnotationTypeUsage}s to search
     * @return an {@link Optional} containing the {@link Source#value()}, or {@link Optional#empty()} if none of the
     *         {@link AnnotationTypeUsage}s is a {@link Source}
     */
    private static Optional<String> firstSource(final Stream<AnnotationTypeUsage> annotations) {
        return annotations
            .filter(a -> a.typeName().canonicalName().equals(Source.class.getCanonicalName()))
            .findFirst()
            .flatMap(a -> AnnotationValues.firstLiteral(a, String.class));
    }

    private static Optional<Class<?>> firstTypeArg(final Dependency dep) {
        return TypeUsages.getFirstTypeParameterClass(dep.typeUsage());
    }

    private static <T> Binding<T> bind(final Dependency dep, final T val) {
        return ValueBinding.of(dep, val);
    }

    /**
     * Constructs an instance of the specified {@link Record} {@link Class} by resolving each of its
     * components as its own {@code @Named} {@link Configuration} value (against the same source
     * {@link #resolveSource(Dependency) file(s)} as {@code dependency} itself), then invoking the
     * record's canonical constructor with the resolved values.
     * <p>
     * A component whose type is itself a {@link Record} (bare, or wrapped in {@link Optional}) is resolved
     * by recursing into this method rather than requiring a {@code @Named} value of its own; every other
     * component must be annotated {@code @Named}. When a bare (non-{@link Optional}) component's value
     * can't be resolved, the whole record is left unresolved ({@link Optional#empty()}), so that the
     * {@link Dependency} is treated as unsatisfied the same way it would be for a non-record value.
     *
     * @param dependency  the {@link Dependency} whose {@link #resolveSource(Dependency) source file}
     *                    the record's components are resolved against
     * @param recordClass the {@link Record} {@link Class} to construct
     * @return an {@link Optional} containing the constructed record instance, or {@link Optional#empty()}
     * if a bare (non-{@link Optional}) component's value couldn't be resolved
     */
    private Optional<Object> resolveRecord(final Dependency dependency, final Class<?> recordClass) {
        final var components = recordClass.getRecordComponents();
        final var parameterTypes = new Class<?>[components.length];
        final var args = new Object[components.length];

        for (int i = 0; i < components.length; i++) {
            final var component = components[i];
            parameterTypes[i] = component.getType();

            if (component.getType().isRecord()) {
                final var nested = resolveRecord(dependency, component.getType());
                if (nested.isEmpty()) {
                    return Optional.empty();
                }
                args[i] = nested.get();
            } else if (component.getType().equals(Optional.class)) {
                if (!(component.getGenericType() instanceof ParameterizedType parameterizedType)) {
                    throw new IllegalStateException("Configuration record component [" + component
                        + "] must be a parameterized Optional");
                }
                final var valueType = (Class<?>) parameterizedType.getActualTypeArguments()[0];
                args[i] = valueType.isRecord()
                    ? resolveRecord(dependency, valueType)
                    : getValues(dependency, valueType, Optional.of(requireNamed(component).value())).stream().findFirst();
            } else {
                final var value = getValues(dependency, component.getType(), Optional.of(requireNamed(component).value()))
                    .stream().findFirst();
                if (value.isEmpty()) {
                    return Optional.empty();
                }
                args[i] = value.get();
            }
        }

        try {
            final var constructor = recordClass.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return Optional.of(constructor.newInstance(args));
        } catch (final ReflectiveOperationException e) {
            throw new RuntimeException("Failed to construct Configuration record [" + recordClass + "]", e);
        }
    }

    /**
     * Obtains the {@code @Named} annotation of the specified {@link Record} component, throwing when it isn't
     * annotated.
     *
     * @param component the {@link RecordComponent} to inspect
     * @return the {@link Named} annotation
     */
    private static Named requireNamed(final RecordComponent component) {
        final var named = component.getAnnotation(Named.class);
        if (named == null) {
            throw new IllegalStateException("Configuration record component [" + component
                + "] must be annotated @Named");
        }
        return named;
    }

    @Override
    public Optional<? extends Binding<Object>> resolve(final Dependency dependency) {

        // only a @Configuration dependency may be resolved
        final var hasConfiguration = dependency.typeUsage()
            .traits(AnnotationTypeUsage.class)
            .anyMatch(a -> a.typeName().canonicalName().equals(Configuration.class.getCanonicalName()));

        if (!hasConfiguration) {
            return Optional.empty();
        }

        // determine whether a specific (possibly nested, "/"-separated) named value is required
        final var name = dependency.typeUsage()
            .traits(AnnotationTypeUsage.class)
            .filter(a -> a.typeName().canonicalName().equals(Named.class.getCanonicalName()))
            .findFirst()
            .flatMap(a -> AnnotationValues.firstLiteral(a, String.class));

        // ie: Handle if Optional<T>, Streamable<T>, Iterable<T> was specified
        final var requiredClass = getRequiredClass(dependency);
        if (requiredClass == null) {
            return Optional.empty();
        }

        if (requiredClass.equals(Optional.class)) {
            // obtain the Optional<T> configuration to be injected; a T that's a Record is resolved
            // component-by-component (see #resolveRecord), the same as it would be if it were required
            return firstTypeArg(dependency)
                .map(type -> type.isRecord()
                    ? resolveRecord(dependency, type)
                    : getValues(dependency, type, name).stream().findFirst())
                .map(val -> bind(dependency, (Object) val));
        } else if (requiredClass.equals(Hierarchical.class)) {
            // obtain the list of values in the hierarchy
            final var values = firstTypeArg(dependency)
                .map(type -> getValues(dependency, type, name).stream())
                .orElse(Stream.empty())
                .collect(Collectors.toList());

            // establish a Hierarchical<T> for the values
            final Hierarchical<Object> hierarchical = new Hierarchical<Object>() {
                @Override
                public boolean isEmpty() {
                    return values.isEmpty();
                }

                @Override
                public Iterator<Object> iterator() {
                    return values.iterator();
                }
            };

            return Optional.of(bind(dependency, hierarchical));
        } else if (requiredClass.isRecord()) {
            // a record groups several named values behind one injection point - resolve each of its
            // components as if it were its own @Named dependency, then construct the record from them
            return resolveRecord(dependency, requiredClass).map(val -> bind(dependency, val));
        } else {
            // obtain the T configuration (or specifically named value) to be injected
            return getValues(dependency, requiredClass, name).stream()
                .findFirst()
                .map(val -> bind(dependency, val));
        }
    }

    /**
     * Obtains the {@link Object}s of the specified {@link Class} for the provided {@link Dependency}, produced by
     * deserializing {@link Configuration} files, or, when {@code name} is present, the single, specifically named
     * (and possibly nested, {@code "/"}-separated) value located within them, coerced to the required {@link Class}.
     * <p>
     * When the underlying {@link Configuration} is JSON, the segments of the name are used to navigate the JSON
     * structure, resolving the value found at the end of the path. When the underlying {@link Configuration} is a
     * {@code .properties} file, the {@code "/"} separators are replaced with {@code "."} and the resulting flat key
     * is used to resolve the value.
     *
     * @param dependency    the {@link Dependency}
     * @param requiredClass the required {@link Class}
     * @param name          the (possibly {@code "/"}-separated) name of a single value to resolve, if any
     * @return a {@link Streamable} of {@link Object}s
     */
    private Streamable<Object> getValues(final Dependency dependency,
                                         final Class<?> requiredClass,
                                         final Optional<String> name) {

        // obtain the base file name we can use to locate configuration files: a @Source declared on the
        // injection point, its defining class, or an ancestor of that class, wins over the fallback factory
        final var baseFileName = resolveSource(dependency).orElseGet(() -> this.baseFileNameFactory.apply(dependency));

        return Streamable.of(this.configurationPaths.stream()
            .filter(Files::exists)
            .filter(Files::isDirectory)
            .flatMap(path -> Stream.concat(
                // include a Path for a directory that is the name of the plugin itself (not a Configuration file)
                // (thus allowing a Path to a directory to be injected)
                Stream.of(path.resolve(baseFileName)),

                // include Paths for each type of Configuration files
                Stream.concat(
                    this.resolvers.keySet().stream()
                        .map(extension -> path.resolve(baseFileName + "/").resolve("config" + extension)),
                    this.resolvers.keySet().stream()
                        .map(extension -> path.resolve(baseFileName + extension)))))
            .filter(Files::exists)
            .map(path -> {
                if (requiredClass.equals(Path.class)) {
                    // is only the Path required?
                    return path;
                } else {
                    // attempt to use the FileTypeResolver for the file's type to resolve the value
                    final var fileName = path.toString();
                    final var extension = fileName.substring(fileName.lastIndexOf("."));
                    final var fileTypeResolver = this.resolvers.get(extension);
                    return fileTypeResolver == null ? null : resolveValue(fileTypeResolver, path, requiredClass, name);
                }
            })
            .filter(Objects::nonNull));
    }
}
