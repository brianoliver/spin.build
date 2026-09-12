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

import build.base.telemetry.TelemetryRecorder;
import build.codemodel.foundation.naming.NonCachingNameProvider;
import build.codemodel.jdk.JDKCodeModel;
import build.spin.module.modulesystem.ModuleVersioning;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies that {@link PomBasedModuleVersioning}'s visitor wires the walker output into a
 * wired module versioning correctly. The walker's own behavior (derivation,
 * BFS, scope filtering) is tested in {@link PomDependencyGraphWalkerTests}.
 */
class PomBasedModuleVersioningTests {

    private static final TelemetryRecorder RECORDER = mock(TelemetryRecorder.class);

    @Test
    void buildFromWorkspace_exposesParsedVersionForDependency(@TempDir final Path workspace) throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), """
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

        final ModuleVersioning versioning = PomBasedModuleVersioning.buildFromWorkspace(workspace, RECORDER);

        assertThat(versioning.getVersion("org.junit.jupiter"))
            .isPresent()
            .hasValueSatisfying(v -> assertThat(v.get()).isEqualTo("5.10.0"));
    }

    @Test
    void buildFromWorkspace_resolvesDependencyManagementVersionForSubmodule(@TempDir final Path workspace)
        throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), """
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
        Files.writeString(submodule.resolve("pom.xml"), """
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

        final ModuleVersioning versioning = PomBasedModuleVersioning.buildFromWorkspace(workspace, RECORDER);

        // build.base.marshalling (the groupId-prefixed heuristic, matching this artifact's real
        // module-info.java) rather than the bare "base.marshalling" guess -- when a ground-truth
        // module name is available for this coordinate in the real local repo, it wins exclusively
        // and the bare heuristic is not registered.
        assertThat(versioning.getVersion("build.base.marshalling"))
            .isPresent()
            .hasValueSatisfying(v -> assertThat(v.get()).isEqualTo("0.22.1"));
    }

    @Test
    void buildFromWorkspace_keepsHighestVersionWhenSameModuleReachedAtDifferentVersions(
        @TempDir final Path workspace, @TempDir final Path localRepo) throws Exception {
        // Reproduces a real-world bug: two workspace modules transitively depend on the same
        // coordinate through different paths, pinned to different versions -- whichever path the
        // walker's BFS visits first must not permanently pin the lower version.
        Files.writeString(workspace.resolve("pom.xml"), """
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
        Files.writeString(oldPathDir.resolve("base-old-path-1.0.0.pom"), """
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
        Files.writeString(newPathDir.resolve("base-new-path-1.0.0.pom"), """
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

        final ModuleVersioning versioning = PomBasedModuleVersioning.buildFromWorkspace(
            workspace, localRepo, new JDKCodeModel(new NonCachingNameProvider()), RECORDER);

        assertThat(versioning.getVersion("build.base.shared"))
            .isPresent()
            .hasValueSatisfying(v -> assertThat(v.get()).isEqualTo("0.30.1"));
    }

    @Test
    void buildFromWorkspace_returnsEmptyWhenNoPomExists(@TempDir final Path workspace) {
        final ModuleVersioning versioning = PomBasedModuleVersioning.buildFromWorkspace(workspace, RECORDER);
        assertThat(versioning.getVersion("anything")).isEmpty();
    }
}
