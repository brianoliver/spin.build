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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Targeted unit tests for {@link MavenModuleNaming}. Most behavior is exercised at the walker
 * level by {@link PomDependencyGraphWalkerTests}; this file only covers things that are hard or
 * impossible to reach from there:
 * <ul>
 *   <li>negative cases of the module-name derivation heuristics — the positive cases fire on every
 *       realistic pom, so the walker tests cover them; the negative "returns empty" branches do not</li>
 * </ul>
 */
class MavenModuleNamingTests {

    // -------------------------------------------------------------------------
    // derivation heuristics — negative cases
    //
    // Positive cases are exercised by PomDependencyGraphWalkerTests against realistic poms.
    // These empty-return branches fire only on specific groupId/artifactId shapes that no
    // walker test exercises end-to-end, so they need direct unit coverage.
    // -------------------------------------------------------------------------

    @Test
    void groupPrefixedModuleName_returnsEmptyWhenPrefixDoesNotMatch() {
        // org.junit.jupiter:junit-jupiter-api — first segment "junit" != last groupId segment "jupiter"
        assertThat(MavenModuleNaming.groupPrefixedModuleName("org.junit.jupiter", "junit-jupiter-api"))
            .isEmpty();
    }

    @Test
    void groupPrefixedModuleName_returnsEmptyForSingleSegmentArtifactId() {
        assertThat(MavenModuleNaming.groupPrefixedModuleName("build.base", "base")).isEmpty();
    }

    @Test
    void groupSuffixedModuleName_returnsEmptyWhenSuffixDoesNotMatch() {
        // org.junit.jupiter:junit-jupiter-api — last segment "api" != last groupId segment "jupiter"
        assertThat(MavenModuleNaming.groupSuffixedModuleName("org.junit.jupiter", "junit-jupiter-api"))
            .isEmpty();
    }

    @Test
    void groupSuffixedModuleName_returnsEmptyForSingleSegmentArtifactId() {
        assertThat(MavenModuleNaming.groupSuffixedModuleName("build.codemodel", "codemodel")).isEmpty();
    }

    @Test
    void groupParentWithLastArtifactSegment_returnsEmptyWhenGroupIdHasNoParent() {
        assertThat(MavenModuleNaming.groupParentWithLastArtifactSegment("singlegroup", "some-artifact"))
            .isEmpty();
    }

    @Test
    void groupParentWithLastArtifactSegment_returnsEmptyForSingleSegmentArtifactId() {
        assertThat(MavenModuleNaming.groupParentWithLastArtifactSegment("com.example.sub", "artifact"))
            .isEmpty();
    }

    @Test
    void groupParentWithLastArtifactSegment_returnsEmptyForTwoSegmentGroupId() {
        // io.netty has no meaningful parent namespace — "io" is a TLD, not a groupId prefix;
        // emitting io.transport for netty-transport would be pure noise in the catalog
        assertThat(MavenModuleNaming.groupParentWithLastArtifactSegment("io.netty", "netty-transport"))
            .isEmpty();
    }

    // -------------------------------------------------------------------------
    // findJarByModuleName
    // -------------------------------------------------------------------------

    @Test
    void findJarByModuleName_returnsEmptyForSingleSegmentModuleName(@TempDir final Path repo) {
        assertThat(MavenModuleNaming.findJarByModuleName("singlemodule", "1.0.0", repo)).isEmpty();
    }

    @Test
    void findJarByModuleName_returnsEmptyWhenNoJarExists(@TempDir final Path repo) {
        assertThat(MavenModuleNaming.findJarByModuleName("build.spin.module.clean", "0.1.0", repo)).isEmpty();
    }

    @Test
    void findJarByModuleName_findsJarByNamingConvention(@TempDir final Path repo) throws Exception {
        // build.spin.module.clean -> groupId=build.spin.module, candidates include spin-clean-module
        final Path jarDir = repo.resolve("build/spin/module/spin-clean-module/0.1.0");
        Files.createDirectories(jarDir);
        Files.createFile(jarDir.resolve("spin-clean-module-0.1.0.jar"));

        final String[] coord =
            MavenModuleNaming.findJarByModuleName("build.spin.module.clean", "0.1.0", repo).orElseThrow();
        assertThat(coord[0]).isEqualTo("build.spin.module");
        assertThat(coord[1]).isEqualTo("spin-clean-module");
        assertThat(coord[2]).isEqualTo("0.1.0");
    }

    @Test
    void findJarByModuleName_findsJarUnderFullModuleNameWhenModuleNameEqualsGroupIdVerbatim(
        @TempDir final Path repo) throws Exception {
        // Helidon convention: io.helidon.config:helidon-config has module name "io.helidon.config" --
        // identical to its own groupId, with no "extra" suffix segment. The stripped-groupId
        // candidates from the first pass (groupId=io.helidon, extra=config) never find a jar because
        // no such jar exists under io/helidon/ -- only under the unstripped io/helidon/config/ path.
        final Path jarDir = repo.resolve("io/helidon/config/helidon-config/1.0.0");
        Files.createDirectories(jarDir);
        Files.createFile(jarDir.resolve("helidon-config-1.0.0.jar"));

        final String[] coord = MavenModuleNaming.findJarByModuleName("io.helidon.config", "1.0.0", repo).orElseThrow();
        assertThat(coord[0]).isEqualTo("io.helidon.config");
        assertThat(coord[1]).isEqualTo("helidon-config");
        assertThat(coord[2]).isEqualTo("1.0.0");
    }
}
