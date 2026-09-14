package build.spin.common.injection;

/*-
 * #%L
 * Spin Common Library
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

import build.base.foundation.Introspection;
import build.base.telemetry.TelemetryRecorder;
import build.codemodel.dependency.injection.Binding;
import build.codemodel.dependency.injection.Dependency;
import build.codemodel.dependency.injection.InjectionPoint;
import build.codemodel.dependency.injection.InjectionPointDependency;
import build.codemodel.dependency.injection.Resolver;
import build.codemodel.dependency.injection.UnsatisfiedDependencyException;
import build.codemodel.dependency.injection.ValueBinding;
import build.codemodel.foundation.usage.AnnotationTypeUsage;
import build.codemodel.jdk.TypeUsages;
import build.codemodel.jdk.descriptor.JDKType;
import build.spin.Asset;
import build.spin.AssetCache;
import build.spin.Instruction;
import build.spin.Reference;
import build.spin.Task;
import build.spin.annotation.From;
import build.spin.annotation.Merge;
import build.spin.common.VoidAsset;
import build.spin.common.util.AnnotationValues;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Resolves values for injection into {@link InjectionPoint}s annotated with {@link From}.
 *
 * @author brian.oliver
 * @since Jan-2023
 */
public class FromResolver
    implements Resolver<Object> {

    /**
     * The {@link TelemetryRecorder} for recording fatal telemetry.
     */
    private final TelemetryRecorder recorder;

    /**
     * The {@link Instruction} for which the {@link FromResolver} has been created.
     */
    private final Instruction<?> instruction;

    /**
     * The {@link AssetCache} from which to resolve {@link From} values.
     */
    private final AssetCache cache;

    /**
     * Constructs a {@link FromResolver} for the specified {@link Instruction} and {@link AssetCache}.
     *
     * @param recorder    the {@link TelemetryRecorder}
     * @param instruction the {@link Instruction}
     * @param assetCache  the {@link AssetCache}
     */
    public FromResolver(final TelemetryRecorder recorder,
                        final Instruction<?> instruction,
                        final AssetCache assetCache) {

        this.recorder = Objects.requireNonNull(recorder, "The TelemetryRecorder must not be null");
        this.instruction = Objects.requireNonNull(instruction, "The Instruction must not be null");
        this.cache = Objects.requireNonNull(assetCache, "The AssetCache must not be null");
    }

    /**
     * Extracts the {@link Task} class declared by a {@link From} annotation on the dependency's type usage,
     * or returns empty if no such annotation is present.
     */
    @SuppressWarnings("unchecked")
    public static Optional<Class<? extends Task<?>>> taskClass(final Dependency dependency) {
        return dependency.typeUsage()
            .traits(AnnotationTypeUsage.class)
            .filter(a -> a.typeName().canonicalName().equals(From.class.getCanonicalName()))
            .findFirst()
            .flatMap(AnnotationValues::firstClassRef)
            .map(c -> (Class<? extends Task<?>>) c);
    }

    private static Class<?> requiredClass(final Dependency dependency) {
        return TypeUsages.getThreadContextClass(dependency.typeUsage()).orElse(null);
    }

    private static Optional<Class<?>> firstTypeArg(final Dependency dependency) {
        return TypeUsages.getFirstTypeParameterClass(dependency.typeUsage());
    }

    private static Class<?> definingClass(final Dependency dependency) {
        if (dependency instanceof InjectionPointDependency ipd && ipd.injectionPoint() != null) {
            return ipd.injectionPoint().typeDescriptor().getTrait(JDKType.class)
                .map(JDKType::type)
                .filter(Class.class::isInstance)
                .map(Class.class::cast)
                .orElse(null);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Binding<Object> binding(final Dependency dep, final Object val) {
        return ValueBinding.of(dep, val);
    }

    @Override
    public Optional<? extends Binding<Object>> resolve(final Dependency dependency) {
        final var fromClass = taskClass(dependency).orElse(null);
        if (fromClass == null) {
            return Optional.empty();
        }

        final var project = this.instruction.getProject();

        final var dependencyClass = requiredClass(dependency);
        if (dependencyClass == null) {
            return Optional.empty();
        }

        // handle: @From(SomePlugin.SomeTask) Optional<T> asset
        // handle: @From(SomePlugin.SomeTask) Optional<Asset<T>> asset
        if (dependencyClass.equals(Optional.class)) {
            final var typeArg = firstTypeArg(dependency).orElse(null);

            if (typeArg != null) {
                final Reference reference = Reference.of(project, fromClass);

                return this.cache.get(reference)
                    .flatMap(asset -> {
                        if (asset instanceof VoidAsset) {
                            this.recorder.fatal(
                                "A type of [%s] is required to execute [%s], but the underlying Task [%s] returns void.",
                                Introspection.describe(dependencyClass),
                                dependency.typeUsage(),
                                Introspection.describe(fromClass));

                            return Optional.of(binding(dependency, Optional.empty()));
                        }

                        // do we require an Asset<T> or a T?
                        if (Asset.class.isAssignableFrom(dependencyClass)) {
                            if (!typeArg.isInstance(asset.get())) {
                                this.recorder.fatal(
                                    "The type [%s] produced by [%s] is not assignable or convertable to [%s].",
                                    Introspection.describe(dependencyClass),
                                    Introspection.describe(fromClass),
                                    dependency.typeUsage());

                                return Optional.of(binding(dependency, Optional.empty()));
                            } else {
                                return Optional.of(binding(dependency, Optional.of(asset)));
                            }
                        } else {
                            if (!typeArg.isInstance(asset.get())) {
                                this.recorder.fatal(
                                    "The type [%s] produced by [%s] is not assignable or convertable to [%s].",
                                    Introspection.describe(dependencyClass),
                                    Introspection.describe(fromClass),
                                    dependency.typeUsage());

                                return Optional.of(binding(dependency, Optional.empty()));
                            } else {
                                return Optional.of(binding(dependency, Optional.of(asset.get())));
                            }
                        }
                    })
                    .or(() -> Optional.of(binding(dependency, Optional.empty())));
            } else {
                this.recorder.fatal(
                    "The required generic parameter for the type Optional<T> is missing or is not a concrete Class in [%s]",
                    definingClass(dependency));

                return Optional.of(binding(dependency, Optional.empty()));
            }
        }

        // eg: @From(SomePlugin.SomeTask) Stream<SomeResult> stream
        // eg: @From(SomePlugin.SomeTask) Stream<Asset<SomeResult>> stream
        else if (dependencyClass.equals(Stream.class)) {
            final var typeArg = firstTypeArg(dependency).orElse(null);

            if (typeArg != null) {
                // return the task results of the required class of Task from the
                // Task dependencies that are assignable to the required class
                final Stream<?> stream = this.instruction.dependencies()
                    .filter(reference -> fromClass.isAssignableFrom(reference.getTaskClass()))
                    .map(this.cache::get)
                    .filter(Objects::nonNull)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(asset -> !(asset instanceof VoidAsset))
                    .filter(asset -> typeArg.isInstance(asset.get()))
                    .map(asset -> Asset.class.isAssignableFrom(dependencyClass)
                        ? asset
                        : asset.get());
                return Optional.of(binding(dependency, stream));
            } else {
                this.recorder.fatal(
                    "The required generic parameter for the type Stream<T> is missing or is not a concrete Class in [%s]",
                    definingClass(dependency));

                return Optional.of(binding(dependency, Optional.empty()));
            }
        }

        // handle: @From(SomePlugin.SomeTask) SomeResult result
        // handle: @From(SomePlugin.SomeTask) Asset<T> asset
        else {
            // determine the required class to inject
            final Class<?> requiredClass;

            if (Asset.class.isAssignableFrom(dependencyClass)) {
                final var typeArg = firstTypeArg(dependency).orElse(null);

                if (typeArg != null) {
                    requiredClass = typeArg;
                } else {
                    this.recorder.fatal(
                        "The required generic parameter for the type Asset<T> is missing or is not a concrete Class in [%s]",
                        definingClass(dependency));

                    return Optional.empty();
                }
            } else {
                requiredClass = dependencyClass;
            }

            final boolean abstractFromClass = fromClass.isInterface() || Modifier.isAbstract(fromClass.getModifiers());

            // eg: @Merge @From(SomePlugin.SomeTask) SomeResult result
            if (abstractFromClass && fromClass.isAnnotationPresent(Merge.class)
                && !Asset.class.isAssignableFrom(dependencyClass)) {
                return resolveMerged(dependency, fromClass, requiredClass);
            }

            @SuppressWarnings("unchecked")
            final Optional<Asset<Object>> optional =
                abstractFromClass
                    ? this.cache.get(project, (Class<? extends Task<Object>>) fromClass)
                    : this.cache.get(Reference.of(project, fromClass));

            return optional
                .flatMap(asset -> {
                    if (asset instanceof VoidAsset) {
                        this.recorder.fatal(
                            "The @From asset type of [%s] is required to execute [%s], but the underlying Task [%s] returns void",
                            Introspection.describe(dependencyClass),
                            Introspection.describe(definingClass(dependency)),
                            Introspection.describe(fromClass));

                        return Optional.empty();
                    }

                    if (!requiredClass.isInstance(asset.get())) {
                        this.recorder.fatal(
                            "The type [%s] produced by [%s] is not assignable or convertable to [%s].",
                            Introspection.describe(dependencyClass),
                            Introspection.describe(fromClass),
                            dependency.typeUsage());

                        return Optional.empty();
                    }

                    if (Asset.class.isAssignableFrom(dependencyClass)) {
                        return Optional.of(binding(dependency, asset));
                    } else {
                        return Optional.of(binding(dependency, asset.get()));
                    }
                });
        }
    }

    /**
     * Resolves the value for a {@link Merge}-annotated abstract {@code @From} {@link Task} type by
     * invoking its declared {@code public static T merge(Stream<T>)} method over the results of every
     * matching implementor in the {@link #instruction}'s dependencies, rather than arbitrarily
     * selecting one (as the single-result branch does for non-{@link Merge} abstract types).
     *
     * @param dependency  the {@link Dependency} being resolved
     * @param fromClass   the {@link Merge}-annotated abstract {@link Task} {@link Class}
     * @param requiredClass the {@link Class} the merged result must be assignable to
     * @return the {@link Binding} for the merged result, or {@link Optional#empty()} on failure
     */
    private Optional<? extends Binding<Object>> resolveMerged(final Dependency dependency,
                                                               final Class<? extends Task<?>> fromClass,
                                                               final Class<?> requiredClass) {

        final Method mergeMethod;
        try {
            mergeMethod = fromClass.getMethod("merge", Stream.class);
        } catch (final NoSuchMethodException e) {
            this.recorder.fatal(
                "The @Merge annotated Task [%s] does not declare a public static merge(Stream<T>) method.",
                Introspection.describe(fromClass));
            throw new UnsatisfiedDependencyException(dependency,
                "The @Merge annotated Task " + Introspection.describe(fromClass)
                    + " does not declare a public static merge(Stream<T>) method.");
        }

        if (!Modifier.isStatic(mergeMethod.getModifiers())) {
            this.recorder.fatal(
                "The @Merge annotated Task [%s] does not declare a public static merge(Stream<T>) method.",
                Introspection.describe(fromClass));
            throw new UnsatisfiedDependencyException(dependency,
                "The @Merge annotated Task " + Introspection.describe(fromClass)
                    + " does not declare a public static merge(Stream<T>) method.");
        }

        final List<Object> results = this.instruction.dependencies()
            .filter(reference -> fromClass.isAssignableFrom(reference.getTaskClass()))
            .map(this.cache::get)
            .filter(Objects::nonNull)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .filter(asset -> !(asset instanceof VoidAsset))
            .map(Asset::get)
            .toList();

        final Object merged;
        try {
            merged = mergeMethod.invoke(null, results.stream());
        } catch (final ReflectiveOperationException e) {
            this.recorder.fatal(e, "Failed to invoke merge(Stream<T>) declared by [%s].", Introspection.describe(fromClass));
            throw new UnsatisfiedDependencyException(dependency,
                "Failed to invoke merge(Stream<T>) declared by " + Introspection.describe(fromClass) + ".", e);
        }

        if (!requiredClass.isInstance(merged)) {
            this.recorder.fatal(
                "The type [%s] produced by merging [%s] is not assignable or convertable to [%s].",
                Introspection.describe(requiredClass),
                Introspection.describe(fromClass),
                dependency.typeUsage());
            throw new UnsatisfiedDependencyException(dependency,
                "The type " + Introspection.describe(requiredClass) + " produced by merging "
                    + Introspection.describe(fromClass) + " is not assignable or convertable to "
                    + dependency.typeUsage() + ".");
        }

        return Optional.of(binding(dependency, merged));
    }
}
