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

import java.util.Optional;
import java.util.Set;

/**
 * Record implementation of {@link Dependency}.
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public record DefaultDependency(GA ga,
                                Optional<String> version,
                                DependencyScope scope,
                                PackagingType type,
                                Optional<String> classifier,
                                boolean optional,
                                Set<String> exclusions) implements Dependency {
    @Override
    public String groupId() {
        return ga.groupId();
    }

    @Override
    public String artifactId() {
        return ga.artifactId();
    }
}
