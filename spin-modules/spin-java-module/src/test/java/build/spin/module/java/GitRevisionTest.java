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
import build.base.telemetry.TelemetryRecorder;
import build.spawn.platform.local.LocalMachine;
import build.spin.common.telemetry.TelemetryPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GitRevisionTest {

    @TempDir
    Path tempDir;

    // --- buildMetadata ---

    @Test
    void buildMetadata_isTheBareHashForACleanTree() {
        assertThat(new GitRevision("a1b2c3d", false).buildMetadata()).isEqualTo("a1b2c3d");
    }

    @Test
    void buildMetadata_marksADirtyTree() {
        assertThat(new GitRevision("a1b2c3d", true).buildMetadata()).isEqualTo("a1b2c3d-dirty");
    }

    // --- detect ---

    @Test
    void detect_reportsTheHeadHashOfACleanWorkingTree() throws Exception {
        final String head = initializeRepositoryWithOneCommit();

        final var revision = GitRevision.detect(LocalMachine.get(), this.tempDir, recorder());

        assertThat(revision).isPresent();
        assertThat(head).startsWith(revision.get().hash());
        assertThat(revision.get().dirty()).isFalse();
    }

    @Test
    void detect_reportsAModifiedTrackedFileAsDirty() throws Exception {
        initializeRepositoryWithOneCommit();
        Files.writeString(this.tempDir.resolve("tracked.txt"), "changed");

        final var revision = GitRevision.detect(LocalMachine.get(), this.tempDir, recorder());

        assertThat(revision).isPresent();
        assertThat(revision.get().dirty()).isTrue();
    }

    @Test
    void detect_ignoresUntrackedFiles() throws Exception {
        initializeRepositoryWithOneCommit();
        Files.writeString(this.tempDir.resolve("untracked.txt"), "new");

        final var revision = GitRevision.detect(LocalMachine.get(), this.tempDir, recorder());

        assertThat(revision).isPresent();
        assertThat(revision.get().dirty()).isFalse();
    }

    @Test
    void detect_isEmptyOutsideAWorkingTree() throws Exception {
        assumeTrue(gitIsAvailable(), "git is not available");

        assertThat(GitRevision.detect(LocalMachine.get(), this.tempDir, recorder())).isEmpty();
    }

    // creates a repository in tempDir with a single commit of tracked.txt, returning the full HEAD hash
    private String initializeRepositoryWithOneCommit() throws Exception {
        assumeTrue(gitIsAvailable(), "git is not available");

        Files.writeString(this.tempDir.resolve("tracked.txt"), "original");
        git("init", "--quiet");
        git("add", "tracked.txt");
        git("-c", "user.name=test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false",
            "commit", "--quiet", "-m", "initial");
        return git("rev-parse", "HEAD");
    }

    private String git(final String... arguments) throws IOException, InterruptedException {
        final var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(arguments));
        final var process = new ProcessBuilder(command)
            .directory(this.tempDir.toFile())
            .redirectErrorStream(true)
            .start();
        final String output = new String(process.getInputStream().readAllBytes()).trim();
        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("git " + String.join(" ", arguments) + " exited with " + exitCode + ": " + output);
        }
        return output;
    }

    private static boolean gitIsAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (final IOException | InterruptedException e) {
            return false;
        }
    }

    private static TelemetryRecorder recorder() {
        return new TelemetryPublisher(
            UniformResource.createURI("test", "GitRevisionTest"),
            telemetry -> {
            });
    }
}
