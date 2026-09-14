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

import build.codemodel.foundation.CodeModel;
import build.codemodel.foundation.naming.NonCachingNameProvider;
import build.codemodel.jdk.JDKCodeModel;
import build.spin.module.modulesystem.pom.GA;
import build.spin.module.modulesystem.pom.Gav;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

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
        assertThat(MavenModuleNaming.findJarByModuleName("singlemodule", "1.0.0", repo, CODE_MODEL)).isEmpty();
    }

    @Test
    void findJarByModuleName_returnsEmptyWhenNoJarExists(@TempDir final Path repo) {
        assertThat(MavenModuleNaming.findJarByModuleName("build.spin.module.clean", "0.1.0", repo, CODE_MODEL)).isEmpty();
    }

    @Test
    void findJarByModuleName_findsJarByNamingConvention(@TempDir final Path repo) throws Exception {
        // build.spin.module.clean -> groupId=build.spin.module, candidates include spin-clean-module
        writeJar(repo, "build/spin/module", "spin-clean-module", "0.1.0", "build.spin.module.clean");

        final Gav gav = MavenModuleNaming.findJarByModuleName("build.spin.module.clean", "0.1.0", repo, CODE_MODEL).orElseThrow();
        assertThat(gav.groupId()).isEqualTo("build.spin.module");
        assertThat(gav.artifactId()).isEqualTo("spin-clean-module");
        assertThat(gav.version()).isEqualTo("0.1.0");
    }

    @Test
    void findJarByModuleName_rejectsCandidateWhoseJarCarriesADifferentGroundTruthName(@TempDir final Path repo)
        throws Exception {
        // a jar exists exactly where the naming convention predicts, but it carries its own
        // ground-truth name that doesn't match what was asked for — must not be substituted
        writeJar(repo, "build/spin/module", "spin-clean-module", "0.1.0", "some.other.module");

        assertThat(MavenModuleNaming.findJarByModuleName("build.spin.module.clean", "0.1.0", repo, CODE_MODEL)).isEmpty();
    }

    @Test
    void findJarByModuleName_findsJarUnderFullModuleNameWhenModuleNameEqualsGroupIdVerbatim(
        @TempDir final Path repo) throws Exception {
        // Helidon convention: io.helidon.config:helidon-config has module name "io.helidon.config" --
        // identical to its own groupId, with no "extra" suffix segment. The stripped-groupId
        // candidates from the first pass (groupId=io.helidon, extra=config) never find a jar because
        // no such jar exists under io/helidon/ -- only under the unstripped io/helidon/config/ path.
        writeJar(repo, "io/helidon/config", "helidon-config", "1.0.0", "io.helidon.config");

        final Gav gav = MavenModuleNaming.findJarByModuleName("io.helidon.config", "1.0.0", repo, CODE_MODEL).orElseThrow();
        assertThat(gav.groupId()).isEqualTo("io.helidon.config");
        assertThat(gav.artifactId()).isEqualTo("helidon-config");
        assertThat(gav.version()).isEqualTo("1.0.0");
    }

    // -------------------------------------------------------------------------
    // requiresNamesFor — ground-truth-from-jar vs naming-convention fallback
    //
    // The PomBased{Main,Test}ModuleDescriptor resources synthesize `requires` clauses from a
    // non-modular project's pom deps; they must land on the exact name the jar carries, not a
    // coordinate guess, or the dependency silently drops (e.g. com.google.guava:guava's real
    // Automatic-Module-Name is com.google.common).
    // -------------------------------------------------------------------------

    private static final CodeModel CODE_MODEL = new JDKCodeModel(new NonCachingNameProvider());

    @Test
    void requiresNamesFor_readsAutomaticModuleNameFromJar_returnsExactlyThatName(@TempDir final Path repo)
        throws Exception {

        writeJar(repo, "com/google/guava", "guava", "33.2.1-jre", "com.google.common");

        assertThat(MavenModuleNaming.requiresNamesFor(
            new GA("com.google.guava", "guava"), Optional.of("33.2.1-jre"), repo, CODE_MODEL))
            .containsExactly("com.google.common");
    }

    @Test
    void requiresNamesFor_jarPresentButCarriesNoModuleName_derivesFromFilename(@TempDir final Path repo)
        throws Exception {

        writeJar(repo, "com/example", "thing-api", "1.0.0", null);

        assertThat(MavenModuleNaming.requiresNamesFor(
            new GA("com.example", "thing-api"), Optional.of("1.0.0"), repo, CODE_MODEL))
            .containsExactly("thing.api");
    }

    @Test
    void requiresNamesFor_jarNotResolvable_fallsBackToNamingConventionGuesses(@TempDir final Path repo) {
        // no jar written — every candidate must be offered so the caller can try each in turn
        assertThat(MavenModuleNaming.requiresNamesFor(
            new GA("com.example", "thing-api"), Optional.of("1.0.0"), repo, CODE_MODEL))
            .isEqualTo(MavenModuleNaming.deriveNames("com.example", "thing-api"));
    }

    @Test
    void requiresNamesFor_noVersion_fallsBackToNamingConventionGuesses(@TempDir final Path repo) {
        assertThat(MavenModuleNaming.requiresNamesFor(
            new GA("com.example", "thing-api"), Optional.empty(), repo, CODE_MODEL))
            .isEqualTo(MavenModuleNaming.deriveNames("com.example", "thing-api"));
    }

    private static void writeJar(final Path repo,
                                 final String groupPath,
                                 final String artifactId,
                                 final String version,
                                 final String automaticModuleName) throws Exception {

        final Path dir = Files.createDirectories(repo.resolve(groupPath).resolve(artifactId).resolve(version));
        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (automaticModuleName != null) {
            manifest.getMainAttributes().put(new Attributes.Name("Automatic-Module-Name"), automaticModuleName);
        }
        try (JarOutputStream out = new JarOutputStream(
            Files.newOutputStream(dir.resolve(artifactId + "-" + version + ".jar")), manifest)) {
            // an automatic module is derived purely from the manifest / filename — no entries needed
        }
    }
}
