package build.spin.module.modulesystem.maven;

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
import build.spin.module.modulesystem.pom.GA;
import build.spin.module.modulesystem.pom.Plugin;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Shared base for {@link Resource}s that derive their output from a single plugin's effective
 * {@code <plugin>} entry (configuration and/or dependencies) in a project's {@code pom.xml}. Reads
 * the effective pom via the injected {@link ProjectPom} rather than owning a {@code PomReader}
 * itself; concrete subclasses declare the target plugin GA and read whatever they need off the
 * resolved {@link Plugin}.
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public abstract class AbstractPomBasedResource
    implements Resource {

    @Inject
    private ProjectPom projectPom;

    /**
     * The Maven plugin this resource reads.
     */
    protected abstract GA pluginGA();

    /**
     * Reads the project's effective pom and locates {@link #pluginGA()}. Empty when the pom is
     * missing or the plugin is absent.
     */
    protected final Optional<Plugin> plugin(final Project project) {
        return this.projectPom.get(project)
            .flatMap(pom -> pom.plugin(pluginGA()));
    }
}
