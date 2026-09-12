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

import build.base.io.PathSet;
import build.base.io.PathSetBuilder;
import build.base.telemetry.TelemetryRecorder;
import build.codemodel.dependency.injection.Binding;
import build.codemodel.dependency.injection.Dependency;
import build.codemodel.dependency.injection.InjectionPointDependency;
import build.codemodel.dependency.injection.Resolver;
import build.codemodel.jdk.descriptor.JDKType;
import build.spin.Project;
import build.spin.Resource;
import build.spin.Workspace;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * A {@link Project} {@link Resource} providing support to resolve {@link Configuration}, determining the
 * {@link Workspace} (due to the presence of a {@link #SPIN_IGNORE_FILENAME}), and ignoring files/folders specified in
 * the {@link #SPIN_IGNORE_FILENAME}.
 *
 * @author brian.oliver
 * @since Mar-2021
 */
public class ConfigurationResource
    implements Resource, Resolver<Object> {

    /**
     * The {@code .spinignore} filename.
     */
    public static final String SPIN_IGNORE_FILENAME = ".spinignore";

    /**
     * The {@link Project}.
     */
    private final Project project;

    /**
     * The {@link ConfigurationResolver} for the {@link ConfigurationResource}.
     */
    private final ConfigurationResolver resolver;

    /**
     * The {@link Predicate}, compiled from the optionally defined {@link #SPIN_IGNORE_FILENAME},
     * that is {@code true} for every {@link Path} the {@link Project} should ignore.
     */
    private final Predicate<Path> ignored;

    /**
     * Constructs a {@link ConfigurationResource}.
     *
     * @param project  the {@link Project}
     * @param recorder the {@link TelemetryRecorder}
     */
    @Inject
    public ConfigurationResource(final Project project,
                                 final TelemetryRecorder recorder) {

        this.project = Objects.requireNonNull(project, "The Project must not be null");
        Objects.requireNonNull(recorder, "The TelemetryRecorder must not be null");

        // -------------------
        // establish the PathSet in which to look for Configuration
        final PathSetBuilder builder = PathSetBuilder.create();

        // TODO: include the user.dir .spin configuration path?

        Project prj = project;
        while (prj != null) {
            builder.add(prj.path().resolve(Configuration.DIRECTORY));
            prj = prj.parent().orElse(null);
        }
        final PathSet pathSet = builder.build();

        this.resolver = new ConfigurationResolver(
            recorder,
            pathSet,
            dependency -> definingClass(dependency).getCanonicalName()
        );

        // -------------------
        // establish the Predicate to hold the rules to ignore files in Spin

        // attempt to load the .spinignore rules from the FileSystem
        final Path path = this.project.path().resolve(SPIN_IGNORE_FILENAME);

        Predicate<Path> compiled = candidate -> false;
        if (Files.exists(path)) {
            try (Stream<String> stream = Files.lines(path)) {
                compiled = SpinIgnorePatterns.compile(this.project.path(), stream);
            } catch (final IOException e) {
                recorder.warn(e,
                    "Failed to read %s.  Project paths be included or excluded when they shouldn't be!",
                    SPIN_IGNORE_FILENAME);
            }
        }
        this.ignored = compiled;
    }

    @Override
    public boolean isIgnored(final Path path) {
        return this.ignored.test(path);
    }

    static Class<?> definingClass(final Dependency dependency) {
        if (dependency instanceof InjectionPointDependency injectionPointDependency
            && injectionPointDependency.injectionPoint() != null) {

            return injectionPointDependency.injectionPoint().typeDescriptor().getTrait(JDKType.class)
                .map(JDKType::type)
                .filter(Class.class::isInstance)
                .map(Class.class::cast)
                .orElse(null);
        }

        return dependency.getClass();
    }

    @Override
    public Optional<? extends Binding<Object>> resolve(final Dependency dependency) {
        return this.resolver.resolve(dependency);
    }

    /**
     * The {@link Resource.MetaClass} for {@link ConfigurationResource}
     */
    public static class MetaClass
        implements Resource.MetaClass {

        @Override
        public boolean isWorkspace(final Path path) {
            return Files.exists(path.resolve(SPIN_IGNORE_FILENAME));
        }

        @Override
        public boolean isDetectedIn(final Project project) {
            // we always allow @Configuration to be resolved for a Project, regardless of
            // whether the Project has a defined .spin directory
            return true;
        }
    }
}
