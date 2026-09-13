package build.spin.module.modulesystem.pom;

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

import java.util.List;
import java.util.Optional;

/**
 * A Maven build plugin, post-effective: configuration deep-merged from the matching
 * {@code <pluginManagement>} entry (own and inherited).
 * <p>
 * {@link #configuration()} returns {@link ConfigNode#empty()} when the plugin declares no
 * {@code <configuration>} block.
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public sealed interface Plugin
    permits DefaultPlugin {

    Optional<String> version();

    ConfigNode configuration();

    /**
     * The plugin's own {@code <dependencies>} (e.g. extra rule/check artifacts layered onto the
     * plugin's classpath), merged with the matching {@code <pluginManagement>} entry's
     * dependencies by {@link GA} — own wins per coordinate, parent-only entries are kept. Does
     * <em>not</em> fall back to the project's own {@code <dependencyManagement>}: Maven resolves
     * plugin dependency versions independently of the project's dependency graph.
     */
    List<Dependency> dependencies();

    /**
     * The {@code (groupId, artifactId)} key for this plugin.
     */
    GA ga();
}
