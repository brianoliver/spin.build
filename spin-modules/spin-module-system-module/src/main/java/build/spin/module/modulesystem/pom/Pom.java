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
import java.util.Map;
import java.util.Optional;

/**
 * The effective Maven Project Object Model for a single project, with parent inheritance,
 * dependency-management injection, plugin-management injection, and {@code ${...}} property
 * interpolation already applied.
 * <p>
 * Coords of the form {@code ${groupId:artifactId:type[:classifier]}} (the dependency-plugin
 * {@code properties} goal output) are <em>not</em> interpolated here — those need a resolved
 * test classpath, which is the consumer's responsibility.
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public sealed interface Pom
    permits DefaultPom {

    String groupId();

    String artifactId();

    String version();

    Gav gav();

    /**
     * The {@code <packaging>} of this project (e.g. {@code jar}, {@code pom}, {@code war}),
     * defaulting to {@code jar} when not declared. Unlike groupId/version, packaging is not
     * inherited from the parent.
     */
    String packaging();

    /**
     * The parent pom, if any (resolved via {@code <relativePath>} or local-repo lookup).
     */
    Optional<Pom> parent();

    /**
     * Effective properties: own {@code <properties>} block merged over parent's effective properties,
     * own values winning.
     */
    Map<String, String> properties();

    /**
     * Effective dependency management: own {@code <dependencyManagement>} merged over parent's,
     * keyed by {@link GA}, own entries winning per key.
     */
    Map<GA, Dependency> dependencyManagement();

    /**
     * Effective dependencies: own {@code <dependencies>} entries with version, scope, type, and
     * classifier filled in from {@link #dependencyManagement()} where the original entry omitted
     * them.
     */
    List<Dependency> dependencies();

    /**
     * Effective plugin management: own {@code <pluginManagement>} merged over parent's, keyed by
     * {@link GA}, configurations deep-merged (own children winning per element name).
     */
    Map<GA, Plugin> pluginManagement();

    /**
     * Effective plugins: own {@code <build><plugins>} entries, each with version and configuration
     * deep-merged from the matching {@link #pluginManagement()} entry.
     */
    List<Plugin> plugins();

    /**
     * Returns the first effective plugin matching the given {@link GA}, if any.
     */
    default Optional<Plugin> plugin(final GA ga) {
        return plugins().stream().filter(p -> p.ga().equals(ga)).findFirst();
    }
}
