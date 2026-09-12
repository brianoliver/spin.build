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
import build.spin.module.modulesystem.pom.ConfigNode;
import build.spin.module.modulesystem.pom.Plugin;

import java.util.stream.Stream;

/**
 * Shared base for {@link build.spin.Resource}s that derive CLI-argument tokens from a single
 * plugin's {@code <configuration>} block in a project's {@code pom.xml}. Concrete subclasses
 * declare the config → token mapping; {@link AbstractPomBasedResource} owns the plugin lookup.
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public abstract class AbstractPomBasedArguments
    extends AbstractPomBasedResource {

    /**
     * Maps the plugin's effective {@code <configuration>} tree to the CLI argument tokens this
     * resource produces.
     */
    protected abstract Stream<String> toArgs(ConfigNode configuration);

    /**
     * Reads the project's effective pom, locates the target plugin, and streams tokens from
     * {@link #toArgs(ConfigNode)}. Returns an empty stream when the pom is missing, the plugin is
     * absent, or the mapping produces nothing.
     */
    public Stream<String> get(final Project project) {
        return plugin(project)
            .map(Plugin::configuration)
            .map(this::toArgs)
            .orElseGet(Stream::empty);
    }
}
