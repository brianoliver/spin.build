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
 * A Maven {@code <packaging>} (project-level) or dependency {@code <type>}. Unlike
 * {@link DependencyScope}, this set is not closed — Maven plugins can register arbitrary custom
 * packagings (e.g. {@code bundle}, {@code eclipse-plugin}) — so an unrecognized value parses to a
 * {@link Custom} instance carrying its raw text rather than throwing.
 *
 * @author reed.vonredwitz
 * @since Sep-2026
 */
public sealed interface PackagingType {

    String raw();

    /**
     * The packaging/type values this codebase actually branches on or otherwise treats specially.
     */
    enum Standard implements PackagingType {

        JAR("jar"),
        POM("pom"),
        WAR("war"),
        EAR("ear"),
        MAVEN_PLUGIN("maven-plugin"),
        TEST_JAR("test-jar");

        private final String raw;

        Standard(final String raw) {
            this.raw = raw;
        }

        @Override
        public String raw() {
            return this.raw;
        }
    }

    /**
     * Any packaging/type not in {@link Standard}, carrying its raw {@code <packaging>}/{@code
     * <type>} text verbatim.
     */
    record Custom(String raw) implements PackagingType {
    }

    /**
     * Parses a {@code <packaging>}/{@code <type>} element's text (case-insensitively against
     * {@link Standard}), falling back to {@link Custom} for anything else.
     */
    static PackagingType of(final String text) {
        for (final Standard standard : Standard.values()) {
            if (standard.raw.equalsIgnoreCase(text)) {
                return standard;
            }
        }
        return new Custom(text);
    }
}
