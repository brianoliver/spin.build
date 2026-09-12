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
 * Targeted unit tests for {@link PomWorkspaces}.
 */
class PomWorkspacesTests {

    // -------------------------------------------------------------------------
    // isMavenWorkspaceRoot — spin-native workspaces with a pom.xml must be recognized
    // -------------------------------------------------------------------------

    @Test
    void isMavenWorkspaceRoot_returnsTrueForSpinNativeWorkspaceWithPom(@TempDir final Path dir) throws Exception {
        Files.createFile(dir.resolve("pom.xml"));
        Files.writeString(dir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        Files.createFile(dir.resolve(".spinignore"));
        assertThat(PomWorkspaces.isMavenWorkspaceRoot(dir)).isTrue();
    }
}
