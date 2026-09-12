package build.spin.module.modulesystem;

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

import build.spin.Project;
import build.spin.Resource;
import build.spin.module.modulesystem.maven.PomWorkspaces;
import build.spin.module.modulesystem.properties.PropertiesModuleCatalog;

import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A no-op fallback {@link ModuleCatalog} {@link Resource} that activates at the workspace root
 * when no {@code module-catalog.properties} file is present.
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public class EmptyModuleCatalog
    implements ModuleCatalog, Resource {

    /**
     * The internal empty {@link ModuleCatalog}.
     */
    private final ModuleCatalog catalog = ModuleCatalog.HeapBased.create();

    @Override
    public ModuleCatalog add(final String moduleName,
                             final Artifact.Constraint constraint) {
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
     * The {@link Resource.MetaClass} for {@link EmptyModuleCatalog}.
     */
    public static class MetaClass
        implements Resource.MetaClass {

        @Override
        public boolean isWorkspace(final Path path) {
            return false;
        }

        @Override
        public boolean isDetectedIn(final Project project) {
            return PomWorkspaces.isConfigless(project, PropertiesModuleCatalog.MODULE_CATALOG_FILENAME);
        }
    }
}
