package build.spin.module.modulesystem;

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

import build.base.telemetry.TelemetryRecorder;
import build.codemodel.foundation.naming.NonCachingNameProvider;
import build.codemodel.jdk.JDKCodeModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ModuleDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link PomDependencyGraphWalker}. Uses a simple collecting visitor to verify which
 * (module-name list, coordinate) tuples the walker emits for a given workspace layout.
 * <p>
 * These tests exercise the shared walking algorithm that backs both {@link PomBasedModuleCatalog}
 * and {@link PomBasedModuleVersioning} — the Catalog/Versioning-specific test files only verify
 * their visitor wiring, not the walker behavior.
 */
class PomDependencyGraphWalkerTests {

    private static final TelemetryRecorder RECORDER = mock(TelemetryRecorder.class);
    private static final JDKCodeModel CODE_MODEL = new JDKCodeModel(new NonCachingNameProvider());

    // -------------------------------------------------------------------------
    // basic walking
    // -------------------------------------------------------------------------

    @Test
    void walk_emitsNothingWhenNoPomExists(@TempDir final Path workspace) {
        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, missingRepo(workspace), RECORDER, CODE_MODEL, visitor);
        assertThat(visitor.visits).isEmpty();
    }

    @Test
    void walk_visitsDirectDependencyUnderGroupIdAndDerivedName(@TempDir final Path workspace) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter-api</artifactId>
                  <version>5.10.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, missingRepo(workspace), RECORDER, CODE_MODEL, visitor);

        final Visit v = visitor.forCoordinate("org.junit.jupiter", "junit-jupiter-api");
        assertThat(v.version).isEqualTo("5.10.0");
        assertThat(v.names).contains("org.junit.jupiter", "junit.jupiter.api");
    }

    @Test
    void walk_resolvesPropertyReferencedVersion(@TempDir final Path workspace) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <properties>
                <assertj.version>3.25.0</assertj.version>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>org.assertj</groupId>
                  <artifactId>assertj-core</artifactId>
                  <version>${assertj.version}</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, missingRepo(workspace), RECORDER, CODE_MODEL, visitor);

        assertThat(visitor.forCoordinate("org.assertj", "assertj-core").version).isEqualTo("3.25.0");
    }

    @Test
    void walk_resolvesDependencyManagementVersionForSubmodule(@TempDir final Path workspace) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <properties>
                <base.version>0.22.1</base.version>
              </properties>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>build.base</groupId>
                    <artifactId>base-marshalling</artifactId>
                    <version>${base.version}</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);

        final Path submodule = Files.createDirectory(workspace.resolve("sub"));
        writePom(submodule.resolve("pom.xml"), """
            <project>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>root</artifactId>
                <version>1.0.0</version>
              </parent>
              <artifactId>sub</artifactId>
              <dependencies>
                <dependency>
                  <groupId>build.base</groupId>
                  <artifactId>base-marshalling</artifactId>
                </dependency>
              </dependencies>
            </project>
            """);

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, missingRepo(workspace), RECORDER, CODE_MODEL, visitor);

        assertThat(visitor.forCoordinate("build.base", "base-marshalling").version).isEqualTo("0.22.1");
    }

    // -------------------------------------------------------------------------
    // derivation heuristics — positive cases
    // -------------------------------------------------------------------------

    @Test
    void walk_derivesGroupIdPlusLastHyphenSegmentForDependency(@TempDir final Path workspace) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter-api</artifactId>
                  <version>5.10.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, missingRepo(workspace), RECORDER, CODE_MODEL, visitor);

        // org.junit.jupiter + last segment "api" -> org.junit.jupiter.api
        assertThat(visitor.forCoordinate("org.junit.jupiter", "junit-jupiter-api").names)
            .contains("org.junit.jupiter.api");
    }

    @Test
    void walk_derivesGroupPrefixedModuleNameForDependency(@TempDir final Path workspace) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>build.base</groupId>
                  <artifactId>base-telemetry-foundation</artifactId>
                  <version>0.21.5</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, missingRepo(workspace), RECORDER, CODE_MODEL, visitor);

        // build.base + artifactId-minus-groupId-prefix -> build.base.telemetry.foundation
        assertThat(visitor.forCoordinate("build.base", "base-telemetry-foundation").names)
            .contains("build.base.telemetry.foundation");
    }

    @Test
    void walk_derivesGroupSuffixedModuleNameForDependency(@TempDir final Path workspace) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>build.codemodel</groupId>
                  <artifactId>codemodel-jdk</artifactId>
                  <version>0.19.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, missingRepo(workspace), RECORDER, CODE_MODEL, visitor);

        // build.codemodel + artifactId-minus-groupId-suffix -> build.codemodel.jdk
        assertThat(visitor.forCoordinate("build.codemodel", "codemodel-jdk").names)
            .contains("build.codemodel.jdk");
    }

    @Test
    void walk_derivesGroupParentWithLastArtifactSegmentForDependency(@TempDir final Path workspace) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>com.fasterxml.jackson.core</groupId>
                  <artifactId>jackson-databind</artifactId>
                  <version>2.18.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, missingRepo(workspace), RECORDER, CODE_MODEL, visitor);

        // parent groupId + last artifact segment -> com.fasterxml.jackson.databind
        assertThat(visitor.forCoordinate("com.fasterxml.jackson.core", "jackson-databind").names)
            .contains("com.fasterxml.jackson.databind");
    }

    @Test
    void walk_doesNotClaimShorterSiblingNameForMultiSegmentArtifact(@TempDir final Path workspace) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>build.base</groupId>
                  <artifactId>base-json</artifactId>
                  <version>0.26.1</version>
                </dependency>
                <dependency>
                  <groupId>build.base</groupId>
                  <artifactId>base-transport-json</artifactId>
                  <version>0.26.1</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final var visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, missingRepo(workspace), RECORDER, CODE_MODEL, visitor);

        // base-transport-json must NOT claim build.base.json -- that belongs to base-json
        assertThat(visitor.forCoordinate("build.base", "base-transport-json").names)
            .contains("build.base.transport.json")
            .doesNotContain("build.base.json");

        // base-json must still claim build.base.json via groupPrefixedModuleName
        assertThat(visitor.forCoordinate("build.base", "base-json").names)
            .contains("build.base.json");
    }

    @Test
    void walk_doesNotClaimGroupPrefixedNameOfSiblingForNonPrefixedArtifact(@TempDir final Path workspace) throws Exception {
        // com.example:example-api  -> groupPrefixedModuleName fires -> com.example.api
        // com.example:acme-api     -> groupPrefixedModuleName is empty (acme != example),
        //   so the groupId+lastSegment fallback currently adds com.example.api — collision
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>root</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>example-api</artifactId>
                  <version>1.0.0</version>
                </dependency>
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>acme-api</artifactId>
                  <version>1.0.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final var visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, missingRepo(workspace), RECORDER, CODE_MODEL, visitor);

        // example-api is the rightful owner of com.example.api via groupPrefixedModuleName
        assertThat(visitor.forCoordinate("com.example", "example-api").names)
            .contains("com.example.api");

        // acme-api must NOT steal com.example.api via the groupId+lastSegment fallback
        assertThat(visitor.forCoordinate("com.example", "acme-api").names)
            .doesNotContain("com.example.api");
    }

    @Test
    void walk_doesNotProduceJunkNamesFromTwoSegmentGroupId(@TempDir final Path workspace) throws Exception {
        // groupParentWithLastArtifactSegment for io.netty:netty-transport produces io.transport
        // because the "parent" of io.netty is just the TLD "io" — that is not a useful namespace
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>io.netty</groupId>
                  <artifactId>netty-transport</artifactId>
                  <version>4.1.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final var visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, missingRepo(workspace), RECORDER, CODE_MODEL, visitor);

        assertThat(visitor.forCoordinate("io.netty", "netty-transport").names)
            .doesNotContain("io.transport");
    }

    // -------------------------------------------------------------------------
    // self-artifact handling
    // -------------------------------------------------------------------------

    @Test
    void walk_skipsRootAggregatorPomAsSelf(@TempDir final Path workspace) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>aggregator</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
            </project>
            """);

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, missingRepo(workspace), RECORDER, CODE_MODEL, visitor);

        // root pom has no deps and its own artifact is deliberately NOT visited
        assertThat(visitor.visits).isEmpty();
    }

    @Test
    void walk_visitsChildPomSelfArtifactWithParentInheritance(@TempDir final Path workspace) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>2.0.0</version>
            </project>
            """);

        final Path childDir = workspace.resolve("child-module");
        Files.createDirectories(childDir);
        writePom(childDir.resolve("pom.xml"), """
            <project>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>2.0.0</version>
              </parent>
              <artifactId>child-module</artifactId>
            </project>
            """);

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, missingRepo(workspace), RECORDER, CODE_MODEL, visitor);

        // child's groupId/version inherited from <parent>; names come from derivation
        final Visit v = visitor.forCoordinate("com.example", "child-module");
        assertThat(v.version).isEqualTo("2.0.0");
        assertThat(v.names).contains("com.example", "child.module");
    }

    @Test
    void walk_prefersModuleInfoNameOverDerivationForSelfArtifact(@TempDir final Path workspace) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>2.0.0</version>
            </project>
            """);

        final Path childDir = workspace.resolve("child-module");
        Files.createDirectories(childDir.resolve("src/main/java"));
        writePom(childDir.resolve("pom.xml"), """
            <project>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>2.0.0</version>
              </parent>
              <artifactId>child-module</artifactId>
            </project>
            """);
        Files.writeString(childDir.resolve("src/main/java/module-info.java"),
            "module com.example.totally.custom {\n}\n");

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, missingRepo(workspace), RECORDER, CODE_MODEL, visitor);

        // when module-info.java is present, its declared name is the ONLY name the walker emits
        final Visit v = visitor.forCoordinate("com.example", "child-module");
        assertThat(v.names).containsExactly("com.example.totally.custom");
    }

    // -------------------------------------------------------------------------
    // BFS transitive resolution & scope filtering
    // -------------------------------------------------------------------------

    @Test
    void walk_followsTransitiveDepsFromLocalRepo(@TempDir final Path workspace,
                                                 @TempDir final Path localRepo) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>build.codemodel</groupId>
                  <artifactId>jdk-codemodel</artifactId>
                  <version>0.19.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final Path pomDir = localRepo.resolve("build/codemodel/jdk-codemodel/0.19.0");
        Files.createDirectories(pomDir);
        writePom(pomDir.resolve("jdk-codemodel-0.19.0.pom"), """
            <project>
              <groupId>build.codemodel</groupId>
              <artifactId>jdk-codemodel</artifactId>
              <version>0.19.0</version>
              <dependencies>
                <dependency>
                  <groupId>build.codemodel</groupId>
                  <artifactId>codemodel-expression</artifactId>
                  <version>0.19.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, localRepo, RECORDER, CODE_MODEL, visitor);

        assertThat(visitor.forCoordinate("build.codemodel", "codemodel-expression").version)
            .isEqualTo("0.19.0");
    }

    @Test
    void walk_excludesTestScopedTransitiveDepsOfExternalArtifacts(@TempDir final Path workspace,
                                                                  @TempDir final Path localRepo) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>build.codemodel</groupId>
                  <artifactId>codemodel-jdk</artifactId>
                  <version>0.19.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final Path jdkDir = localRepo.resolve("build/codemodel/jdk-codemodel/0.19.0");
        Files.createDirectories(jdkDir);
        writePom(jdkDir.resolve("jdk-codemodel-0.19.0.pom"), """
            <project>
              <groupId>build.codemodel</groupId>
              <artifactId>codemodel-jdk</artifactId>
              <version>0.19.0</version>
              <dependencies>
                <dependency>
                  <groupId>org.only.in.external.test</groupId>
                  <artifactId>external-test-only</artifactId>
                  <version>9.9.9</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
            </project>
            """);

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, localRepo, RECORDER, CODE_MODEL, visitor);

        assertThat(visitor.coordinateVisited("org.only.in.external.test", "external-test-only")).isFalse();
    }

    @Test
    void walk_includesTestScopedWorkspaceDepsInBfs(@TempDir final Path workspace,
                                                   @TempDir final Path localRepo) throws Exception {
        // workspace has a test-scoped dep on base-assertion; base-assertion's pom lists base-retryable
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>build.base</groupId>
                  <artifactId>base-assertion</artifactId>
                  <version>1.0.0</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
            </project>
            """);

        final Path assertionDir = localRepo.resolve("build/base/base-assertion/1.0.0");
        Files.createDirectories(assertionDir);
        writePom(assertionDir.resolve("base-assertion-1.0.0.pom"), """
            <project>
              <groupId>build.base</groupId>
              <artifactId>base-assertion</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>build.base</groupId>
                  <artifactId>base-retryable</artifactId>
                  <version>1.0.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, localRepo, RECORDER, CODE_MODEL, visitor);

        // base-retryable is only reachable through the test-scoped workspace dep chain
        assertThat(visitor.forCoordinate("build.base", "base-retryable").names)
            .contains("build.base.retryable");
    }

    @Test
    void walk_revisitsCoordinateWithHigherVersionDiscoveredViaALaterPath(@TempDir final Path workspace,
                                                                         @TempDir final Path localRepo)
        throws Exception {
        // Reproduces the real-world bug: two workspace dependencies transitively reach the same
        // external coordinate through different paths, pinned to different versions in each path's
        // own pom. Whichever path the BFS happens to visit first must not permanently win just
        // because it got there first -- the higher version must supersede it.
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>build.base</groupId>
                  <artifactId>base-old-path</artifactId>
                  <version>1.0.0</version>
                </dependency>
                <dependency>
                  <groupId>build.base</groupId>
                  <artifactId>base-new-path</artifactId>
                  <version>1.0.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final Path oldPathDir = localRepo.resolve("build/base/base-old-path/1.0.0");
        Files.createDirectories(oldPathDir);
        writePom(oldPathDir.resolve("base-old-path-1.0.0.pom"), """
            <project>
              <groupId>build.base</groupId>
              <artifactId>base-old-path</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>build.base</groupId>
                  <artifactId>base-shared</artifactId>
                  <version>0.30.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final Path newPathDir = localRepo.resolve("build/base/base-new-path/1.0.0");
        Files.createDirectories(newPathDir);
        writePom(newPathDir.resolve("base-new-path-1.0.0.pom"), """
            <project>
              <groupId>build.base</groupId>
              <artifactId>base-new-path</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>build.base</groupId>
                  <artifactId>base-shared</artifactId>
                  <version>0.30.1</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, localRepo, RECORDER, CODE_MODEL, visitor);

        final List<Visit> sharedVisits = visitor.visits.stream()
            .filter(v -> v.groupId().equals("build.base") && v.artifactId().equals("base-shared"))
            .toList();

        // the lower-version edge (visited first, via base-old-path) must not be the last word --
        // once the higher-version edge (via base-new-path) is discovered, the coordinate is
        // re-visited and the final visit must carry the higher version
        assertThat(sharedVisits).isNotEmpty();
        assertThat(sharedVisits.getLast().version()).isEqualTo("0.30.1");
    }

    @Test
    void walk_infersSameGroupVersionFromPomVersion(@TempDir final Path workspace) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>2.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>sibling</artifactId>
                </dependency>
              </dependencies>
            </project>
            """);

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, missingRepo(workspace), RECORDER, CODE_MODEL, visitor);

        assertThat(visitor.forCoordinate("com.example", "sibling").version).isEqualTo("2.0.0");
    }

    // -------------------------------------------------------------------------
    // ground-truth module names take priority over derived heuristics
    // -------------------------------------------------------------------------

    @Test
    void walk_registersOnlyAutomaticModuleNameForDependencyExclusively(@TempDir final Path workspace,
                                                                       @TempDir final Path localRepo) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>io.helidon.config</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>io.helidon.config</groupId>
                  <artifactId>helidon-config-metadata</artifactId>
                  <version>3.0.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        automaticModuleJar(
            localRepo.resolve("io/helidon/config/helidon-config-metadata/3.0.0"
                + "/helidon-config-metadata-3.0.0.jar"),
            "io.helidon.config.metadata");

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, localRepo, RECORDER, CODE_MODEL, visitor);

        // only the manifest's Automatic-Module-Name is registered — not the groupId, and not any
        // of the derived heuristic names that would otherwise let this artifact claim a name
        // (e.g. io.helidon.config, which really belongs to a sibling artifact)
        assertThat(visitor.forCoordinate("io.helidon.config", "helidon-config-metadata").names)
            .containsExactly("io.helidon.config.metadata");
    }

    @Test
    void walk_registersOnlyNamedModuleNameForDependencyExclusively(@TempDir final Path workspace,
                                                                   @TempDir final Path localRepo) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>example-thing</artifactId>
                  <version>1.0.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        namedModuleJar(
            localRepo.resolve("com/example/example-thing/1.0.0/example-thing-1.0.0.jar"),
            "com.example.totally.custom");

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, localRepo, RECORDER, CODE_MODEL, visitor);

        assertThat(visitor.forCoordinate("com.example", "example-thing").names)
            .containsExactly("com.example.totally.custom");
    }

    @Test
    void walk_prefersNamedModuleNameOverAutomaticModuleNameForDependency(@TempDir final Path workspace,
                                                                        @TempDir final Path localRepo) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>example-thing</artifactId>
                  <version>1.0.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final Path jar = localRepo.resolve("com/example/example-thing/1.0.0/example-thing-1.0.0.jar");
        Files.createDirectories(jar.getParent());
        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "com.example.automatic.guess");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            jos.putNextEntry(new JarEntry("module-info.class"));
            jos.write(moduleInfoBytes("com.example.named.module"));
            jos.closeEntry();
        }

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, localRepo, RECORDER, CODE_MODEL, visitor);

        assertThat(visitor.forCoordinate("com.example", "example-thing").names)
            .containsExactly("com.example.named.module");
    }

    /**
     * Reproduces the snappy-java bug: a jar present in the local repo with neither
     * {@code module-info.class} nor {@code Automatic-Module-Name} is a confirmed automatic module,
     * so only the single JPMS-spec-derived name should be registered — not the full
     * {@link MavenModuleNaming#deriveNames} heuristic set, whose {@code lastHyphenSegment} candidate
     * would otherwise register the misleading name {@code "java"} for {@code org.xerial:snappy-java}.
     */
    @Test
    void walk_registersOnlyTheDerivedNameForAConfirmedUnnamedDependencyJar(@TempDir final Path workspace,
                                                                           @TempDir final Path localRepo) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>org.xerial</groupId>
                  <artifactId>snappy-java</artifactId>
                  <version>1.1.10.8</version>
                </dependency>
              </dependencies>
            </project>
            """);

        unnamedJar(localRepo.resolve("org/xerial/snappy-java/1.1.10.8/snappy-java-1.1.10.8.jar"));

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, localRepo, RECORDER, CODE_MODEL, visitor);

        assertThat(visitor.forCoordinate("org.xerial", "snappy-java").names)
            .containsExactly("snappy.java");
    }

    // -------------------------------------------------------------------------
    // ${project.groupId} / ${project.version} self-reference resolution
    // -------------------------------------------------------------------------

    @Test
    void walk_resolvesProjectSelfReferencesInSubmoduleDependency(@TempDir final Path workspace) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
            </project>
            """);

        final Path submodule = Files.createDirectory(workspace.resolve("sub"));
        writePom(submodule.resolve("pom.xml"), """
            <project>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>root</artifactId>
                <version>1.0.0</version>
              </parent>
              <groupId>com.acme.sub</groupId>
              <artifactId>sub</artifactId>
              <version>2.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>${project.groupId}</groupId>
                  <artifactId>sibling-artifact</artifactId>
                  <version>${project.version}</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, missingRepo(workspace), RECORDER, CODE_MODEL, visitor);

        // ${project.groupId}/${project.version} must resolve against the submodule's OWN
        // coordinates (com.acme.sub:2.0.0), not the parent's (com.example:1.0.0)
        assertThat(visitor.forCoordinate("com.acme.sub", "sibling-artifact").version).isEqualTo("2.0.0");
    }

    @Test
    void walk_mergesBomImportDeclaredInTheWorkspaceRootPomItself(@TempDir final Path workspace,
                                                                 @TempDir final Path localRepo) throws Exception {
        // The most common real-world case: a company aggregator root pom imports a BOM to manage
        // versions centrally for all its submodules -- not a BOM imported by some external
        // dependency's own pom fetched from the local repo during BFS.
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.example.bom</groupId>
                    <artifactId>some-bom</artifactId>
                    <version>1.0.0</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);

        final Path bomDir = localRepo.resolve("com/example/bom/some-bom/1.0.0");
        Files.createDirectories(bomDir);
        writePom(bomDir.resolve("some-bom-1.0.0.pom"), """
            <project>
              <groupId>com.example.bom</groupId>
              <artifactId>some-bom</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>build.base</groupId>
                    <artifactId>base-marshalling</artifactId>
                    <version>0.22.1</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);

        final Path submodule = Files.createDirectory(workspace.resolve("sub"));
        writePom(submodule.resolve("pom.xml"), """
            <project>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>root</artifactId>
                <version>1.0.0</version>
              </parent>
              <artifactId>sub</artifactId>
              <dependencies>
                <dependency>
                  <groupId>build.base</groupId>
                  <artifactId>base-marshalling</artifactId>
                </dependency>
              </dependencies>
            </project>
            """);

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, localRepo, RECORDER, CODE_MODEL, visitor);

        assertThat(visitor.forCoordinate("build.base", "base-marshalling").version).isEqualTo("0.22.1");
    }

    @Test
    void walk_neverRevisitsAWorkspaceModuleAsADependency(@TempDir final Path workspace,
                                                         @TempDir final Path localRepo) throws Exception {
        // A workspace module that is also depended on by a sibling must be visited exactly once,
        // via visitSelf (current module-info.java) -- never a second time via the dependency BFS,
        // where a stale jar left over in the local repo (e.g. from before a module rename) could
        // otherwise register it under a name that no longer reflects its real source.
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
              <dependencies>
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>b-module</artifactId>
                  <version>1.0.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final Path bDir = Files.createDirectory(workspace.resolve("b-module"));
        Files.createDirectories(bDir.resolve("src/main/java"));
        writePom(bDir.resolve("pom.xml"), """
            <project>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>root</artifactId>
                <version>1.0.0</version>
              </parent>
              <artifactId>b-module</artifactId>
            </project>
            """);
        Files.writeString(bDir.resolve("src/main/java/module-info.java"), "module com.example.b.current {\n}\n");

        // simulate a stale jar for b-module already installed in the local repo from before a rename
        namedModuleJar(
            localRepo.resolve("com/example/b-module/1.0.0/b-module-1.0.0.jar"),
            "com.example.b.stale.old.name");

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, localRepo, RECORDER, CODE_MODEL, visitor);

        assertThat(visitor.visits.stream()
            .filter(v -> v.groupId().equals("com.example") && v.artifactId().equals("b-module"))
            .count())
            .isEqualTo(1);
        assertThat(visitor.forCoordinate("com.example", "b-module").names)
            .containsExactly("com.example.b.current");
    }

    @Test
    void walk_warnsWhenSiblingDependencyVersionDisagreesWithWorkspaceModuleVersion(
            @TempDir final Path workspace) throws Exception {
        // Since a workspace module's dependency edge is deliberately not walked (its own pom's
        // version always wins), a mismatched version declared by a sibling would otherwise be
        // completely invisible -- this must be surfaced instead.
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
              <dependencies>
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>b-module</artifactId>
                  <version>9.9.9</version>
                </dependency>
              </dependencies>
            </project>
            """);

        writePom(Files.createDirectory(workspace.resolve("b-module")).resolve("pom.xml"), """
            <project>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>root</artifactId>
                <version>1.0.0</version>
              </parent>
              <artifactId>b-module</artifactId>
              <version>1.0.0</version>
            </project>
            """);

        final TelemetryRecorder recorder = mock(TelemetryRecorder.class);
        PomDependencyGraphWalker.walk(workspace, missingRepo(workspace), recorder, CODE_MODEL, new CollectingVisitor());

        verify(recorder).warn(anyString(), eq("com.example"), eq("b-module"), eq("9.9.9"), eq("1.0.0"));
    }

    @Test
    void walk_resolvesProjectSelfReferencesInDependencyManagementEntry(@TempDir final Path workspace) throws Exception {
        // BOMs commonly list their own sibling modules in dependencyManagement using
        // ${project.groupId}/${project.version} instead of naming themselves literally.
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.acme.sub</groupId>
              <artifactId>root</artifactId>
              <version>3.0.0</version>
              <packaging>pom</packaging>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>${project.groupId}</groupId>
                    <artifactId>managed-sibling</artifactId>
                    <version>${project.version}</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);

        final Path submodule = Files.createDirectory(workspace.resolve("sub"));
        writePom(submodule.resolve("pom.xml"), """
            <project>
              <parent>
                <groupId>com.acme.sub</groupId>
                <artifactId>root</artifactId>
                <version>3.0.0</version>
              </parent>
              <artifactId>sub</artifactId>
              <dependencies>
                <dependency>
                  <groupId>com.acme.sub</groupId>
                  <artifactId>managed-sibling</artifactId>
                </dependency>
              </dependencies>
            </project>
            """);

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, missingRepo(workspace), RECORDER, CODE_MODEL, visitor);

        assertThat(visitor.forCoordinate("com.acme.sub", "managed-sibling").version).isEqualTo("3.0.0");
    }

    // -------------------------------------------------------------------------
    // <scope>import</scope> BOM dependencyManagement merging
    // -------------------------------------------------------------------------

    @Test
    void walk_mergesImportedBomDependencyManagementForTransitiveDependency(@TempDir final Path workspace,
                                                                           @TempDir final Path localRepo) throws Exception {
        // the BOM import merge only happens when readDependencyManagement is given a localRepo, which
        // is only true for poms fetched during the Phase 2 BFS -- so the consumer pom whose
        // dependencyManagement imports the BOM must itself live in the local repo, not the workspace.
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>build.example</groupId>
                  <artifactId>consumer</artifactId>
                  <version>1.0.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final Path bomDir = localRepo.resolve("com/example/bom/some-bom/1.0.0");
        Files.createDirectories(bomDir);
        writePom(bomDir.resolve("some-bom-1.0.0.pom"), """
            <project>
              <groupId>com.example.bom</groupId>
              <artifactId>some-bom</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>build.base</groupId>
                    <artifactId>base-marshalling</artifactId>
                    <version>0.22.1</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);

        final Path consumerDir = localRepo.resolve("build/example/consumer/1.0.0");
        Files.createDirectories(consumerDir);
        writePom(consumerDir.resolve("consumer-1.0.0.pom"), """
            <project>
              <groupId>build.example</groupId>
              <artifactId>consumer</artifactId>
              <version>1.0.0</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.example.bom</groupId>
                    <artifactId>some-bom</artifactId>
                    <version>1.0.0</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency>
                </dependencies>
              </dependencyManagement>
              <dependencies>
                <dependency>
                  <groupId>build.base</groupId>
                  <artifactId>base-marshalling</artifactId>
                </dependency>
              </dependencies>
            </project>
            """);

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, localRepo, RECORDER, CODE_MODEL, visitor);

        // base-marshalling has no literal version in consumer's pom -- it is only resolvable
        // because the imported BOM's dependencyManagement entry was merged in
        assertThat(visitor.forCoordinate("build.base", "base-marshalling").version).isEqualTo("0.22.1");
    }

    @Test
    void walk_literalDependencyManagementEntryWinsOverImportedBomEntry(@TempDir final Path workspace,
                                                                       @TempDir final Path localRepo) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>build.example</groupId>
                  <artifactId>consumer</artifactId>
                  <version>1.0.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final Path bomDir = localRepo.resolve("com/example/bom/some-bom/1.0.0");
        Files.createDirectories(bomDir);
        writePom(bomDir.resolve("some-bom-1.0.0.pom"), """
            <project>
              <groupId>com.example.bom</groupId>
              <artifactId>some-bom</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>build.base</groupId>
                    <artifactId>base-marshalling</artifactId>
                    <version>0.22.1</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);

        final Path consumerDir = localRepo.resolve("build/example/consumer/1.0.0");
        Files.createDirectories(consumerDir);
        writePom(consumerDir.resolve("consumer-1.0.0.pom"), """
            <project>
              <groupId>build.example</groupId>
              <artifactId>consumer</artifactId>
              <version>1.0.0</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>build.base</groupId>
                    <artifactId>base-marshalling</artifactId>
                    <version>0.99.0</version>
                  </dependency>
                  <dependency>
                    <groupId>com.example.bom</groupId>
                    <artifactId>some-bom</artifactId>
                    <version>1.0.0</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency>
                </dependencies>
              </dependencyManagement>
              <dependencies>
                <dependency>
                  <groupId>build.base</groupId>
                  <artifactId>base-marshalling</artifactId>
                </dependency>
              </dependencies>
            </project>
            """);

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, localRepo, RECORDER, CODE_MODEL, visitor);

        // consumer's own literal entry (0.99.0) must win over the imported BOM's (0.22.1)
        assertThat(visitor.forCoordinate("build.base", "base-marshalling").version).isEqualTo("0.99.0");
    }

    // -------------------------------------------------------------------------
    // isIgnored-pruned directories (e.g. .spinignore'd worktrees)
    // -------------------------------------------------------------------------

    @Test
    void walk_prunesDirectoryForWhichIsIgnoredReturnsTrue(@TempDir final Path workspace) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
            </project>
            """);

        final Path worktrees = Files.createDirectory(workspace.resolve(".worktrees"));
        final Path stale = Files.createDirectory(worktrees.resolve("stale-branch"));
        writePom(stale.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>0.1.0-stale</version>
            </project>
            """);

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, missingRepo(workspace), RECORDER, CODE_MODEL,
            path -> path.equals(worktrees), visitor);

        // only the workspace root pom is visited; the stale pom under the pruned .worktrees
        // directory must never be reached
        assertThat(visitor.visits).hasSize(1);
        assertThat(visitor.forCoordinate("com.example", "root").version).isEqualTo("1.0.0");
    }

    @Test
    void walk_withoutIsIgnoredArgumentWalksEverything(@TempDir final Path workspace) throws Exception {
        writePom(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
            </project>
            """);

        final Path submodule = Files.createDirectory(workspace.resolve("sub"));
        writePom(submodule.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>sub</artifactId>
              <version>1.0.0</version>
            </project>
            """);

        final CollectingVisitor visitor = new CollectingVisitor();
        PomDependencyGraphWalker.walk(workspace, missingRepo(workspace), RECORDER, CODE_MODEL, visitor);

        assertThat(visitor.forCoordinate("com.example", "sub").version).isEqualTo("1.0.0");
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    /**
     * Returns a repo path that doesn't exist — used by tests that don't need Phase 2 BFS.
     */
    private static Path missingRepo(final Path workspace) {
        return workspace.resolve("__no_repo__");
    }

    private static void automaticModuleJar(final Path jarPath, final String automaticModuleName) throws Exception {
        Files.createDirectories(jarPath.getParent());
        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", automaticModuleName);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            // empty jar body — the manifest attribute alone is enough for readAutomaticModuleName
        }
    }

    private static void unnamedJar(final Path jarPath) throws Exception {
        Files.createDirectories(jarPath.getParent());
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            // no module-info.class, no manifest attribute — a genuinely unnamed/automatic jar
        }
    }

    private static void namedModuleJar(final Path jarPath, final String moduleName) throws Exception {
        Files.createDirectories(jarPath.getParent());
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jos.putNextEntry(new JarEntry("module-info.class"));
            jos.write(moduleInfoBytes(moduleName));
            jos.closeEntry();
        }
    }

    private static byte[] moduleInfoBytes(final String moduleName) {
        return ClassFile.of().buildModule(
            ModuleAttribute.of(ModuleDesc.of(moduleName), mb -> mb.requires(ModuleDesc.of("java.base"), 0, null)));
    }

    private static void writePom(final Path path, final String content) throws Exception {
        Files.writeString(path, content);
    }

    /**
     * Simple visitor that captures every visit made by the walker for later inspection.
     */
    private static final class CollectingVisitor implements PomDependencyGraphWalker.CoordinateVisitor {

        final List<Visit> visits = new ArrayList<>();

        @Override
        public void accept(final List<String> moduleNames,
                           final String groupId,
                           final String artifactId,
                           final String resolvedVersion) {
            this.visits.add(new Visit(List.copyOf(moduleNames), groupId, artifactId, resolvedVersion));
        }

        Visit forCoordinate(final String groupId, final String artifactId) {
            return this.visits.stream()
                .filter(v -> v.groupId.equals(groupId) && v.artifactId.equals(artifactId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "Expected visit for [" + groupId + ":" + artifactId + "] not found among " + this.visits));
        }

        boolean coordinateVisited(final String groupId, final String artifactId) {
            return this.visits.stream()
                .anyMatch(v -> v.groupId.equals(groupId) && v.artifactId.equals(artifactId));
        }
    }

    private record Visit(List<String> names, String groupId, String artifactId, String version) {
    }
}
