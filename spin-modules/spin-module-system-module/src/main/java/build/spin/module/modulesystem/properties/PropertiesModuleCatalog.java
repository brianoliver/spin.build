package build.spin.module.modulesystem.properties;

/*-
 * #%L
 * Spin Module System Module
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

import build.base.telemetry.TelemetryRecorder;
import build.codemodel.dependency.injection.PostInject;
import build.spin.Project;
import build.spin.Resource;
import build.spin.module.modulesystem.Artifact;
import build.spin.module.modulesystem.ModuleCatalog;
import build.spin.module.modulesystem.ModuleReference;
import jakarta.inject.Inject;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * A {@link Resource} providing a {@link ModuleCatalog} for a {@link Project} and its descendants.
 *
 * @author brian.oliver
 * @since Nov-2019
 */
public class PropertiesModuleCatalog
    implements ModuleCatalog, Resource {

    public static final String MODULE_CATALOG_FILENAME = "module-catalog.properties";

    /**
     * The {@link TelemetryRecorder} for the {@link Resource}.
     */
    @Inject
    private TelemetryRecorder recorder;

    /**
     * The {@link Project} in which the {@link ModuleCatalog} is defined.
     */
    @Inject
    private Project project;

    /**
     * The internal {@link ModuleCatalog}.
     */
    private ModuleCatalog catalog;

    /**
     * Performs post-injection initialization.
     * <p>
     * Once injected with the {@link Project}, the internal {@link ModuleCatalog} will be created
     * by reading the "artifact-catalog.properties" file.
     */
    @PostInject
    private void onInjected() {
        final Path catalogPath = this.project.path().resolve(MODULE_CATALOG_FILENAME);

        if (Files.exists(catalogPath)) {
            // load the Module Catalog
            try (BufferedReader reader = Files.newBufferedReader(catalogPath)) {

                final Properties properties = new Properties();
                properties.load(reader);

                this.catalog = ModuleCatalog.HeapBased.create(properties);

                this.recorder
                    .diagnostic("Loaded ModuleCatalog from [%s] for [%s]", catalogPath, this.project.name());
            } catch (final IOException e) {
                this.recorder
                    .warn(e, "Failed to read ModuleCatalog [%s]. Defaulting to an empty ModuleCatalog", catalogPath);
                this.catalog = ModuleCatalog.HeapBased.create();
            }
        } else {
            this.recorder
                .warn("Missing ModuleCatalog at [%s]. Defaulting to an empty ModuleCatalog", catalogPath);

            // use an empty catalog as we can't find one
            this.catalog = ModuleCatalog.HeapBased.create();
        }
    }

    @Override
    public ModuleCatalog add(final String moduleName, final Artifact.Constraint constraint) {
        return this.catalog.add(moduleName, constraint);
    }

    @Override
    public Stream<Artifact.Constraint> constraints(final String moduleName) {
        return this.catalog.constraints(moduleName);
    }

    @Override
    public Optional<ModuleReference> getModuleReference(final Artifact artifact) {
        return this.catalog.getModuleReference(artifact);
    }

    /**
     * The {@link Resource.MetaClass} for {@link PropertiesModuleCatalog}.
     */
    public static class MetaClass
        implements Resource.MetaClass {

        @Override
        public boolean isWorkspace(final Path path) {
            return Files.exists(path.resolve(MODULE_CATALOG_FILENAME));
        }

        @Override
        public boolean isDetectedIn(final Project project) {
            return Files.exists(project.path().resolve(MODULE_CATALOG_FILENAME));
        }
    }
}
