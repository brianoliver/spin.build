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

/**
 * A Maven dependency {@code <scope>}. Named {@code DependencyScope} rather than {@code Scope} to
 * avoid colliding with {@code jakarta.inject.Scope} in files that also do DI.
 *
 * @author reed.vonredwitz
 * @since Sep-2026
 */
public enum DependencyScope {

    COMPILE("compile"),
    PROVIDED("provided"),
    RUNTIME("runtime"),
    TEST("test"),
    SYSTEM("system"),
    IMPORT("import");

    private final String mavenName;

    DependencyScope(final String mavenName) {
        this.mavenName = mavenName;
    }

    public String mavenName() {
        return this.mavenName;
    }

    /**
     * Parses a {@code <scope>} element's text (Maven's own scope literals are lowercase, but this
     * matches case-insensitively).
     *
     * @throws IllegalArgumentException if {@code text} is not one of Maven's six scope values
     */
    public static DependencyScope of(final String text) {
        for (final DependencyScope scope : values()) {
            if (scope.mavenName.equalsIgnoreCase(text)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Unknown Maven dependency scope [" + text + "]");
    }
}
