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
import build.base.version.Version;
import build.spin.module.modulesystem.Artifact;
import build.spin.module.modulesystem.ModuleCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies that {@link PomBasedModuleCatalog}'s visitor wires the walker output into an
 * {@link Artifact.Constraint} correctly. The walker's own behavior (derivation, BFS, scope
 * filtering) is tested in {@link PomDependencyGraphWalkerTests}.
 */
class PomBasedModuleCatalogTests {

    private static final TelemetryRecorder RECORDER = mock(TelemetryRecorder.class);

    @Test
    void buildFromWorkspace_storesConstraintUnderGroupIdWithParsedVersion(
        @TempDir final Path workspace) throws Exception {

        Files.writeString(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>org.assertj</groupId>
                  <artifactId>assertj-core</artifactId>
                  <version>3.25.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        final ModuleCatalog catalog = PomBasedModuleCatalog.buildFromWorkspace(workspace, RECORDER);

        // constraint is present under the groupId (and every other derived name, but those
        // are a walker concern — see PomDependencyGraphWalkerTests)
        assertThat(catalog.constraints("org.assertj"))
            .isNotEmpty()
            .anyMatch(c -> c.contains(Version.parse("3.25.0")));
    }

    @Test
    void buildFromWorkspace_returnsEmptyCatalogWhenNoPomExists(@TempDir final Path workspace) {
        final ModuleCatalog catalog = PomBasedModuleCatalog.buildFromWorkspace(workspace, RECORDER);
        assertThat(catalog.constraints("anything")).isEmpty();
    }
}
