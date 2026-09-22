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
 * Record implementation of {@link Pom}.
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public record DefaultPom(Gav gav,
                         PackagingType packaging,
                         Optional<Pom> parent,
                         Map<String, String> properties,
                         Map<GA, Dependency> dependencyManagement,
                         List<Dependency> dependencies,
                         Map<GA, Plugin> pluginManagement,
                         List<Plugin> plugins) implements Pom {
    @Override
    public String groupId() {
        return gav.groupId();
    }

    @Override
    public String artifactId() {
        return gav.artifactId();
    }

    @Override
    public String version() {
        return gav.version();
    }
}
