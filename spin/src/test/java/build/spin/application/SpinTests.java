package build.spin.application;

/*-
 * #%L
 * Spin Command Line Application
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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link Spin}.
 */
class SpinTests {

    @TempDir
    Path tempDir;

    /**
     * Regression coverage for {@link Spin#discover}: a {@code -w}/{@code -r} pointing at a path that
     * doesn't exist must fail the invocation outright, rather than silently falling back to
     * discovering a workspace/project at some other, unintended location.
     */
    @Test
    void requireDirectoryThrowsWhenPathDoesNotExist() {
        final Path missing = this.tempDir.resolve("no-such-directory");

        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> Spin.requireDirectory(missing, "Working directory"));

        assertThat(exception.getMessage()).isEqualTo("Working directory does not exist: [" + missing + "]");
    }

    @Test
    void requireDirectoryThrowsWhenPathIsARegularFile() throws Exception {
        final Path file = Files.createFile(this.tempDir.resolve("not-a-directory"));

        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> Spin.requireDirectory(file, "Additional root"));

        assertThat(exception.getMessage()).isEqualTo("Additional root does not exist: [" + file + "]");
    }

    @Test
    void requireDirectoryDoesNotThrowWhenPathIsADirectory() {
        Spin.requireDirectory(this.tempDir, "Working directory");
    }
}
