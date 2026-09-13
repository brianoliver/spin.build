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
 * A Maven dependency, post-effective: version, scope, type, and classifier are filled in from
 * {@code <dependencyManagement>} where the original {@code <dependency>} omitted them. An
 * explicit scope/type on the dependency itself always wins over {@code <dependencyManagement>},
 * even when the explicit value happens to equal the Maven default.
 * <p>
 * Version is {@link Optional} because effective resolution may still fail (no matching depMgmt
 * entry); scope and type carry Maven defaults ({@code "compile"}, {@code "jar"}) when neither the
 * dependency nor its managed entry declares one.
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public sealed interface Dependency
    permits DefaultDependency {

    String groupId();

    String artifactId();

    Optional<String> version();

    String scope();

    String type();

    Optional<String> classifier();

    boolean optional();

    /**
     * {@code groupId:artifactId} patterns (either side may be {@code *}) declared via this
     * dependency's own {@code <exclusions>}; applies to this dependency's transitive closure, not
     * to the dependency itself.
     */
    Set<String> exclusions();

    /**
     * The {@code (groupId, artifactId)} key for this dependency.
     */
    GA ga();
}
