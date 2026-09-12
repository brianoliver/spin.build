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

import build.base.version.Version;
import build.spin.Project;
import build.spin.Resource;
import build.spin.module.modulesystem.maven.PomWorkspaces;
import build.spin.module.modulesystem.properties.PropertiesModuleVersioning;

import java.nio.file.Path;
import java.util.Optional;

/**
 * A no-op fallback {@link ModuleVersioning} {@link Resource} that activates at the workspace root
 * when no {@code version.properties} file is present.
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public class EmptyModuleVersioning
    implements ModuleVersioning, Resource {

    @Override
    public Optional<Version> getVersion(final String moduleName) {
        return Optional.empty();
    }

    /**
     * The {@link Resource.MetaClass} for {@link EmptyModuleVersioning}.
     */
    public static class MetaClass
        implements Resource.MetaClass {

        @Override
        public boolean isWorkspace(final Path path) {
            return false;
        }

        @Override
        public boolean isDetectedIn(final Project project) {
            return PomWorkspaces.isConfigless(project, PropertiesModuleVersioning.VERSION_PROPERTIES_FILENAME);
        }
    }
}
