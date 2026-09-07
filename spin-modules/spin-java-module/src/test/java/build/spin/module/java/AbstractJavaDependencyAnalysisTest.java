package build.spin.module.java;

/*-
 * #%L
 * Spin Java Module
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

import build.base.foundation.UniformResource;
import build.base.telemetry.Telemetry;
import build.base.telemetry.TelemetryRecorder;
import build.base.telemetry.Warning;
import build.base.version.Version;
import build.codemodel.foundation.CodeModel;
import build.codemodel.foundation.ConceptualCodeModel;
import build.codemodel.foundation.descriptor.RequiresModuleDescriptor;
import build.codemodel.foundation.naming.NonCachingNameProvider;
import build.codemodel.jdk.descriptor.RequiresVersionTrait;
import build.spin.common.telemetry.TelemetryPublisher;
import build.spin.module.modulesystem.Artifact;
import build.spin.module.modulesystem.ArtifactDescriptor;
import build.spin.module.modulesystem.ModuleReference;
import build.spin.module.modulesystem.ModuleVersioning;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the static helpers in {@link AbstractJavaDependencyAnalysis}, in particular
 * {@code dedupeByMavenCoordinates}, which is the dedupe pass that catches the slf4j-api
 * 1.x → 2.x rename
 */
class AbstractJavaDependencyAnalysisTest {

    @Test
    void dedupeByMavenCoordinates_emptyInput_returnsEmpty() {
        final var result = AbstractJavaDependencyAnalysis.dedupeByMavenCoordinates(
            List.of(), (kept, dropped) -> { });
        assertThat(result).isEmpty();
    }

    @Test
    void dedupeByMavenCoordinates_distinctCoordinates_keepsAll() {
        final var a = descriptor("com.example", "lib-a", "1.0.0", "lib.a");
        final var b = descriptor("com.example", "lib-b", "1.0.0", "lib.b");
        final var result = AbstractJavaDependencyAnalysis.dedupeByMavenCoordinates(
            List.of(a, b), (kept, dropped) -> { });
        assertThat(result).containsExactly(a, b);
    }

    @Test
    void dedupeByMavenCoordinates_sameArtifactDifferentGroupId_bothKept() {
        // Different groupIds with the same artifactId are NOT duplicates — both kept.
        final var a = descriptor("com.example", "lib", "1.0.0", "mod.a");
        final var b = descriptor("org.other", "lib", "1.0.0", "mod.b");
        final var result = AbstractJavaDependencyAnalysis.dedupeByMavenCoordinates(
            List.of(a, b), (kept, dropped) -> { });
        assertThat(result).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void dedupeByMavenCoordinates_olderThenNewer_keepsNewer() {
        final var older = descriptor("com.example", "lib", "1.0.0", "com.example.lib");
        final var newer = descriptor("com.example", "lib", "2.0.0", "com.example.lib");
        final var result = AbstractJavaDependencyAnalysis.dedupeByMavenCoordinates(
            List.of(older, newer), (kept, dropped) -> { });
        assertThat(result).containsExactly(newer);
    }

    @Test
    void dedupeByMavenCoordinates_newerThenOlder_keepsNewer() {
        // Order independence: the higher version wins regardless of insertion order.
        final var newer = descriptor("com.example", "lib", "2.0.0", "com.example.lib");
        final var older = descriptor("com.example", "lib", "1.0.0", "com.example.lib");
        final var result = AbstractJavaDependencyAnalysis.dedupeByMavenCoordinates(
            List.of(newer, older), (kept, dropped) -> { });
        assertThat(result).containsExactly(newer);
    }

    @Test
    void dedupeByMavenCoordinates_threeVersions_keepsHighestSemantic() {
        // 11.0.0 must beat both 1.0.0 and 2.0.0 (semantic, not lexicographic, ordering).
        final var v1 = descriptor("com.example", "lib", "1.0.0", "com.example.lib");
        final var v2 = descriptor("com.example", "lib", "2.0.0", "com.example.lib");
        final var v11 = descriptor("com.example", "lib", "11.0.0", "com.example.lib");
        final var result = AbstractJavaDependencyAnalysis.dedupeByMavenCoordinates(
            List.of(v1, v2, v11), (kept, dropped) -> { });
        assertThat(result).containsExactly(v11);
    }

    @Test
    void dedupeByMavenCoordinates_slf4jStyleRename_keepsNewerEvenWithDifferentModuleName() {
        // slf4j-api 1.7.25 has the filename-derived automatic module name "slf4j.api"; slf4j-api 2.0.17 is the proper
        // JPMS module "org.slf4j". Both share Maven coordinates org.slf4j:slf4j-api so the
        // coordinate dedupe pass must drop the older one before any classifier sees the
        // org.slf4j package conflict. A naive module-name dedupe (the second pass) cannot
        // catch this because the names are different.
        final var slf4jOld = descriptor("org.slf4j", "slf4j-api", "1.7.25", "slf4j.api");
        final var slf4jNew = descriptor("org.slf4j", "slf4j-api", "2.0.17", "org.slf4j");
        final var result = AbstractJavaDependencyAnalysis.dedupeByMavenCoordinates(
            List.of(slf4jOld, slf4jNew), (kept, dropped) -> { });
        assertThat(result).containsExactly(slf4jNew);
    }

    @Test
    void dedupeByMavenCoordinates_callbackInvokedWhenIncomingIsLower() {
        // existing higher, incoming lower → kept = existing, dropped = incoming
        final var newer = descriptor("com.example", "lib", "2.0.0", "lib");
        final var older = descriptor("com.example", "lib", "1.0.0", "lib");
        final var calls = new ArrayList<String>();
        AbstractJavaDependencyAnalysis.dedupeByMavenCoordinates(
            List.of(newer, older),
            (kept, dropped) -> calls.add(
                kept.artifact().version() + "/" + dropped.artifact().version()));
        assertThat(calls).containsExactly("2.0.0/1.0.0");
    }

    @Test
    void dedupeByMavenCoordinates_callbackInvokedWhenIncomingDisplacesExisting() {
        // existing lower, incoming higher → kept = incoming, dropped = existing
        final var older = descriptor("com.example", "lib", "1.0.0", "lib");
        final var newer = descriptor("com.example", "lib", "2.0.0", "lib");
        final var calls = new ArrayList<String>();
        AbstractJavaDependencyAnalysis.dedupeByMavenCoordinates(
            List.of(older, newer),
            (kept, dropped) -> calls.add(
                kept.artifact().version() + "/" + dropped.artifact().version()));
        assertThat(calls).containsExactly("2.0.0/1.0.0");
    }

    @Test
    void dedupeByMavenCoordinates_callbackNotInvokedWhenSameVersionResolvedTwice() {
        // Same coordinate, same version, reached via two different transitive requires paths —
        // not a real ambiguity, so onDuplicate must not fire even though a "duplicate" coordinate
        // was seen twice.
        final var first = descriptor("com.example", "lib", "1.0.0", "com.example.lib");
        final var second = descriptor("com.example", "lib", "1.0.0", "com.example.lib");
        final var calls = new ArrayList<String>();
        final var result = AbstractJavaDependencyAnalysis.dedupeByMavenCoordinates(
            List.of(first, second),
            (kept, dropped) -> calls.add(kept + "/" + dropped));
        assertThat(calls).isEmpty();
        assertThat(result).containsExactly(first);
    }

    @Test
    void dedupeByMavenCoordinates_callbackNotInvokedWhenNoDuplicates() {
        final var a = descriptor("com.example", "lib-a", "1.0.0", "lib.a");
        final var b = descriptor("com.example", "lib-b", "1.0.0", "lib.b");
        final var calls = new ArrayList<String>();
        AbstractJavaDependencyAnalysis.dedupeByMavenCoordinates(
            List.of(a, b),
            (kept, dropped) -> calls.add(kept + "/" + dropped));
        assertThat(calls).isEmpty();
    }

    @Test
    void dedupeByMavenCoordinates_mavenQualifierOrdering_prefersHigherRankQualifierOverLexicographic() {
        // Plain String/Version#compareTo is case-sensitive lexicographic: "RC" (R=0x52) sorts
        // below "beta" (b=0x62), so a naive compareTo would keep 2.0.0-beta1 here. Maven's own
        // qualifier chain ranks rc (4) above beta (2), so 2.0.0-RC1 is the version Maven itself
        // would pick — the same VersionOrder.MAVEN ordering AbstractDetectResolution.dedupeByMavenCoordinate
        // already uses for this exact reason.
        final var beta = descriptor("com.example", "lib", "2.0.0-beta1", "com.example.lib");
        final var rc = descriptor("com.example", "lib", "2.0.0-RC1", "com.example.lib");
        final var result = AbstractJavaDependencyAnalysis.dedupeByMavenCoordinates(
            List.of(beta, rc), (kept, dropped) -> { });
        assertThat(result).containsExactly(rc);
    }

    @Test
    void dedupeByMavenCoordinates_preservesInsertionOrder() {
        final var a = descriptor("com.example", "lib-a", "1.0.0", "lib.a");
        final var b = descriptor("com.example", "lib-b", "1.0.0", "lib.b");
        final var c = descriptor("com.example", "lib-c", "1.0.0", "lib.c");
        final var result = AbstractJavaDependencyAnalysis.dedupeByMavenCoordinates(
            List.of(b, c, a), (kept, dropped) -> { });
        assertThat(result).containsExactly(b, c, a);
    }

    // -------------------------------------------------------------------------
    // shouldProcess
    // -------------------------------------------------------------------------

    @Test
    void shouldProcess_moduleNotYetSeen_returnsTrue() {
        final Map<String, Optional<Version>> seen = new LinkedHashMap<>();
        assertThat(AbstractJavaDependencyAnalysis.shouldProcess(
            "build.base.parsing", Optional.of(Version.parse("0.21.5")), seen)).isTrue();
    }

    @Test
    void shouldProcess_seenWithSameVersion_returnsFalse() {
        final Map<String, Optional<Version>> seen = new LinkedHashMap<>();
        seen.put("build.base.parsing", Optional.of(Version.parse("0.21.5")));
        assertThat(AbstractJavaDependencyAnalysis.shouldProcess(
            "build.base.parsing", Optional.of(Version.parse("0.21.5")), seen)).isFalse();
    }

    @Test
    void shouldProcess_seenAtLowerVersion_higherVersionArrives_returnsTrue() {
        // The exact scenario that was broken: 0.21.5 processed first; 0.26.0 arrives
        // and must trigger re-processing so its jars reach the module-path.
        final Map<String, Optional<Version>> seen = new LinkedHashMap<>();
        seen.put("build.base.parsing", Optional.of(Version.parse("0.21.5")));
        assertThat(AbstractJavaDependencyAnalysis.shouldProcess(
            "build.base.parsing", Optional.of(Version.parse("0.26.0")), seen)).isTrue();
    }

    @Test
    void shouldProcess_seenAtHigherVersion_lowerVersionArrives_returnsFalse() {
        // 0.26.0 processed first; a stale 0.21.5 reference must not clobber it.
        final Map<String, Optional<Version>> seen = new LinkedHashMap<>();
        seen.put("build.base.parsing", Optional.of(Version.parse("0.26.0")));
        assertThat(AbstractJavaDependencyAnalysis.shouldProcess(
            "build.base.parsing", Optional.of(Version.parse("0.21.5")), seen)).isFalse();
    }

    @Test
    void shouldProcess_mavenQualifierOrdering_higherRankQualifierTriggersReprocessing() {
        // 2.0.0-beta processed first; 2.0.0-rc arrives. The prerelease qualifier decides: under
        // Version#compareTo's case-sensitive lexicographic order "rc" (r=0x72) sorts above "beta"
        // here, but flip the case to "RC" (R=0x52) and it sorts below — the ordering is unstable.
        // VersionOrder.MAVEN's qualifier chain ranks rc above beta unconditionally, so 2.0.0-rc is
        // strictly higher and the higher-version graph is walked.
        final Map<String, Optional<Version>> seen = new LinkedHashMap<>();
        seen.put("com.example.lib", Optional.of(Version.parse("2.0.0-beta")));
        assertThat(AbstractJavaDependencyAnalysis.shouldProcess(
            "com.example.lib", Optional.of(Version.parse("2.0.0-RC")), seen)).isTrue();
    }

    @Test
    void shouldProcess_mavenQualifierOrdering_lowerRankQualifierDoesNotReprocess() {
        // Reverse of the above: 2.0.0-RC processed first, stale 2.0.0-beta arrives and must not
        // clobber it. Version#compareTo would (wrongly) treat "beta" as higher than "RC" here.
        final Map<String, Optional<Version>> seen = new LinkedHashMap<>();
        seen.put("com.example.lib", Optional.of(Version.parse("2.0.0-RC")));
        assertThat(AbstractJavaDependencyAnalysis.shouldProcess(
            "com.example.lib", Optional.of(Version.parse("2.0.0-beta")), seen)).isFalse();
    }

    @Test
    void shouldProcess_seenWithoutVersion_versionedReferenceArrives_returnsTrue() {
        // A module-info.java requires clause carries no version; when the resolved
        // ArtifactDescriptor later provides a version we must re-walk.
        final Map<String, Optional<Version>> seen = new LinkedHashMap<>();
        seen.put("build.base.parsing", Optional.empty());
        assertThat(AbstractJavaDependencyAnalysis.shouldProcess(
            "build.base.parsing", Optional.of(Version.parse("0.26.0")), seen)).isTrue();
    }

    @Test
    void shouldProcess_seenWithoutVersion_unversionedReferenceArrives_returnsFalse() {
        final Map<String, Optional<Version>> seen = new LinkedHashMap<>();
        seen.put("build.base.parsing", Optional.empty());
        assertThat(AbstractJavaDependencyAnalysis.shouldProcess(
            "build.base.parsing", Optional.empty(), seen)).isFalse();
    }

    @Test
    void shouldProcess_seenWithVersion_unversionedReferenceArrives_returnsFalse() {
        // A no-version reference can never be "higher" than a known version.
        final Map<String, Optional<Version>> seen = new LinkedHashMap<>();
        seen.put("build.base.parsing", Optional.of(Version.parse("0.26.0")));
        assertThat(AbstractJavaDependencyAnalysis.shouldProcess(
            "build.base.parsing", Optional.empty(), seen)).isFalse();
    }

    // -------------------------------------------------------------------------
    // resolveRequiredVersion
    // -------------------------------------------------------------------------

    private static TelemetryRecorder recorder() {
        return new TelemetryPublisher(
            UniformResource.createURI("test", "AbstractJavaDependencyAnalysisTest"),
            System.out::println);
    }

    // builds a bare `requires <moduleName>;` clause, optionally with a bytecode-compiled-version
    // hint — mirrors AbstractDetectResolutionTest's identical helper for resolveExternalArtifact.
    private static RequiresModuleDescriptor requires(final String moduleName, final Optional<Version> version) {
        final CodeModel codeModel = new ConceptualCodeModel(new NonCachingNameProvider());
        final var name = codeModel.getNameProvider().getModuleName(moduleName).orElseThrow();
        final RequiresModuleDescriptor r = RequiresModuleDescriptor.of(codeModel, name);

        version.ifPresent(v -> r.addTrait(RequiresVersionTrait.of(v)));

        return r;
    }

    @Test
    void resolveRequiredVersion_catalogHasVersion_bytecodeHintAbsent_usesCatalogVersion() {
        final RequiresModuleDescriptor r = requires("build.base.telemetry", Optional.empty());

        final ModuleVersioning versioning = moduleName ->
            "build.base.telemetry".equals(moduleName) ? Optional.of(Version.parse("0.30.2-SNAPSHOT")) : Optional.empty();

        final Optional<Version> resolved = AbstractJavaDependencyAnalysis.resolveRequiredVersion(
            r, "build.base.telemetry", "build.codemodel.jdk", versioning, List.of(), recorder());

        assertThat(resolved).contains(Version.parse("0.30.2-SNAPSHOT"));
    }

    @Test
    void resolveRequiredVersion_catalogAndBytecodeHintAgree_usesCatalogVersion_noWarning() {
        final RequiresModuleDescriptor r = requires("build.base.telemetry", Optional.of(Version.parse("0.30.1")));

        final ModuleVersioning versioning = moduleName ->
            "build.base.telemetry".equals(moduleName) ? Optional.of(Version.parse("0.30.1")) : Optional.empty();

        final List<Telemetry> emitted = new ArrayList<>();
        final TelemetryRecorder recorder = new TelemetryPublisher(
            UniformResource.createURI("test", "AbstractJavaDependencyAnalysisTest"),
            emitted::add);

        final Optional<Version> resolved = AbstractJavaDependencyAnalysis.resolveRequiredVersion(
            r, "build.base.telemetry", "build.codemodel.jdk", versioning, List.of(), recorder);

        assertThat(resolved).contains(Version.parse("0.30.1"));
        assertThat(emitted).noneMatch(t -> t instanceof Warning);
    }

    @Test
    void resolveRequiredVersion_catalogDivergesFromStaleBytecodeHint_prefersCatalogAndWarns() {
        // the exact regression this method exists for: codemodel.jdk's own published jar has a
        // requires clause for build.base.telemetry with a compiled-version hint of 0.30.1 — frozen
        // in that jar's bytecode from whenever codemodel.jdk was last built — while the workspace's
        // own ModuleVersioning catalog (walked fresh from every pom.xml/version.properties) has
        // since moved on to 0.30.2-SNAPSHOT. The catalog must win, and the divergence must be
        // visible rather than silently resolving to the stale version.
        final RequiresModuleDescriptor r = requires("build.base.telemetry", Optional.of(Version.parse("0.30.1")));

        final ModuleVersioning versioning = moduleName ->
            "build.base.telemetry".equals(moduleName) ? Optional.of(Version.parse("0.30.2-SNAPSHOT")) : Optional.empty();

        final List<Telemetry> emitted = new ArrayList<>();
        final TelemetryRecorder recorder = new TelemetryPublisher(
            UniformResource.createURI("test", "AbstractJavaDependencyAnalysisTest"),
            emitted::add);

        final Optional<Version> resolved = AbstractJavaDependencyAnalysis.resolveRequiredVersion(
            r, "build.base.telemetry", "build.codemodel.jdk", versioning, List.of(), recorder);

        assertThat(resolved).contains(Version.parse("0.30.2-SNAPSHOT"));
        assertThat(emitted)
            .as("a stale bytecode-compiled-version hint diverging from the workspace catalog must warn,"
                + " naming both the declared and the resolved version")
            .anyMatch(t -> t instanceof Warning
                && t.toString().contains("0.30.1")
                && t.toString().contains("0.30.2-SNAPSHOT"));
    }

    @Test
    void resolveRequiredVersion_catalogEmpty_bytecodeHintPresent_fallsBackToBytecodeHint() {
        final RequiresModuleDescriptor r = requires("build.base.telemetry", Optional.of(Version.parse("0.30.1")));

        final ModuleVersioning versioning = _ -> Optional.empty();

        final Optional<Version> resolved = AbstractJavaDependencyAnalysis.resolveRequiredVersion(
            r, "build.base.telemetry", "build.codemodel.jdk", versioning, List.of(), recorder());

        assertThat(resolved).contains(Version.parse("0.30.1"));
    }

    @Test
    void resolveRequiredVersion_catalogAndBytecodeHintEmpty_fallsBackToKnownArtifactDescriptor() {
        // workspace modules come from source module-info files, which carry no compiled-version
        // hint at all — the only remaining source of a version is an already-resolved
        // ArtifactDescriptor for that same module name, loaded earlier in the jdeps walk.
        final RequiresModuleDescriptor r = requires("build.spin.common", Optional.empty());

        final ModuleVersioning versioning = _ -> Optional.empty();

        // built directly rather than via the shared descriptor(...) helper below: that helper's
        // ModuleReference deliberately carries no version (it exists for the module-name-dedupe
        // tests), but this fallback path specifically needs a *versioned* ModuleReference to fall
        // back to.
        final ArtifactDescriptor known = ArtifactDescriptor.create(
            ModuleReference.of("build.spin.common", Version.parse("0.4.1-SNAPSHOT")),
            Artifact.create("build.spin", "spin-common", "0.4.1-SNAPSHOT", "jar"),
            Path.of("/repo/build/spin/spin-common/0.4.1-SNAPSHOT/spin-common-0.4.1-SNAPSHOT.jar"));

        final Optional<Version> resolved = AbstractJavaDependencyAnalysis.resolveRequiredVersion(
            r, "build.spin.common", "build.spin.application", versioning, List.of(known), recorder());

        assertThat(resolved).contains(Version.parse("0.4.1-SNAPSHOT"));
    }

    @Test
    void resolveRequiredVersion_noSourceHasAVersion_returnsEmpty() {
        final RequiresModuleDescriptor r = requires("build.base.telemetry", Optional.empty());

        final ModuleVersioning versioning = _ -> Optional.empty();

        final Optional<Version> resolved = AbstractJavaDependencyAnalysis.resolveRequiredVersion(
            r, "build.base.telemetry", "build.codemodel.jdk", versioning, List.of(), recorder());

        assertThat(resolved).isEmpty();
    }

    // -------------------------------------------------------------------------
    // moduleNameFromListDepsLine
    // -------------------------------------------------------------------------

    @Test
    void moduleNameFromListDepsLine_plainModuleName_returnsUnchanged() {
        assertThat(AbstractJavaDependencyAnalysis.moduleNameFromListDepsLine("java.base"))
            .isEqualTo("java.base");
    }

    @Test
    void moduleNameFromListDepsLine_qualifiedExportLine_returnsModulePortion() {
        // jdeps prints "module/package" instead of plain "module" whenever the package isn't
        // part of the module's default export surface — e.g. a workspace module com.example.library
        // with "exports com.example.library to com.example.app;" is reported as
        // "com.example.library/com.example.library" when depended on by com.example.app.
        assertThat(AbstractJavaDependencyAnalysis.moduleNameFromListDepsLine(
            "com.example.library/com.example.library")).isEqualTo("com.example.library");
    }

    @Test
    void moduleNameFromListDepsLine_jdkInternalApiLine_returnsModulePortion() {
        // The same "module/package" shape also appears for fully internal, unexported JDK
        // packages (e.g. sun.security.x509), distinct from the qualified-export case above.
        assertThat(AbstractJavaDependencyAnalysis.moduleNameFromListDepsLine(
            "java.base/sun.security.x509")).isEqualTo("java.base");
    }

    @Test
    void moduleNameFromListDepsLine_multipleSlashes_splitsOnFirst() {
        assertThat(AbstractJavaDependencyAnalysis.moduleNameFromListDepsLine(
            "java.base/sun.security.x509/extra")).isEqualTo("java.base");
    }

    @Test
    void moduleNameFromListDepsLine_emptyLine_returnsEmpty() {
        assertThat(AbstractJavaDependencyAnalysis.moduleNameFromListDepsLine("")).isEqualTo("");
    }

    // Dedupe now keys off the resolved artifact path (see MavenCoordinateDedupe), following the
    // same <groupId-path>/<artifactId>/<version>/<artifactId>-<version>.jar local-repository layout
    // AbstractDetectResolutionTest uses for the same reason — so two descriptors with the same
    // groupId/artifactId land under the same coordinate directory regardless of module name.
    private static ArtifactDescriptor descriptor(final String groupId,
                                                 final String artifactId,
                                                 final String version,
                                                 final String moduleName) {
        final Artifact artifact = Artifact.create(groupId, artifactId, version, "jar");
        final ModuleReference reference = ModuleReference.of(moduleName);
        final Path path = Path.of("/repo", groupId.replace('.', '/'), artifactId, version,
            artifactId + "-" + version + ".jar");
        return ArtifactDescriptor.create(reference, artifact, path);
    }
}
