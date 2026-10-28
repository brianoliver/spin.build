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

import build.base.configuration.ConfigurationBuilder;
import build.base.flow.RecordingSubscriber;
import build.base.option.WorkingDirectory;
import build.base.telemetry.TelemetryRecorder;
import build.spawn.application.Application;
import build.spawn.application.option.Argument;
import build.spawn.application.option.Executable;
import build.spawn.application.option.Name;
import build.spawn.application.option.StandardErrorSubscriber;
import build.spawn.application.option.StandardOutputSubscriber;
import build.spawn.platform.local.LocalMachine;
import build.spin.common.ProcessRunner;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The git revision of the source tree a build is running from: the abbreviated hash of {@code HEAD}, and whether
 * any tracked file differs from it.
 * <p>
 * Untracked files are deliberately not counted towards {@link #dirty()}: a build output directory, an editor
 * scratch file or a new document would otherwise mark nearly every local build dirty.
 *
 * @param hash  the abbreviated {@code HEAD} hash
 * @param dirty {@code true} when a tracked file has uncommitted changes
 * @author reed.vonredwitz
 * @since Oct-2026
 */
record GitRevision(String hash, boolean dirty) {

    /**
     * The suffix marking a {@link #dirty()} revision.
     */
    private static final String DIRTY_SUFFIX = "-dirty";

    /**
     * Renders this {@link GitRevision} as SemVer build metadata (without the leading {@code +}), for example
     * {@code a1b2c3d} or {@code a1b2c3d-dirty}.
     *
     * @return the build metadata
     */
    String buildMetadata() {
        return this.dirty ? this.hash + DIRTY_SUFFIX : this.hash;
    }

    /**
     * Determines the {@link GitRevision} of the git working tree containing {@code directory}.
     * <p>
     * Best-effort: stamping a build with its revision is informational, so a missing {@code git} executable, a
     * {@code directory} outside any working tree or a repository without commits all report
     * {@link Optional#empty()} rather than failing the build.
     *
     * @param machine   the {@link LocalMachine} with which to launch {@code git}
     * @param directory a {@link Path} inside the working tree
     * @param recorder  the {@link TelemetryRecorder} to report why no revision could be determined
     * @return the {@link GitRevision}, or {@link Optional#empty()} when it can't be determined
     */
    static Optional<GitRevision> detect(final LocalMachine machine,
                                        final Path directory,
                                        final TelemetryRecorder recorder) {
        try {
            final List<String> hash = git(machine, directory, "rev-parse", "--short", "HEAD");
            if (hash.isEmpty()) {
                return Optional.empty();
            }
            final List<String> changes = git(machine, directory, "status", "--porcelain", "--untracked-files=no");
            return Optional.of(new GitRevision(hash.getFirst(), !changes.isEmpty()));
        } catch (final Exception e) {
            recorder.diagnostic("No git revision available for [%s]: %s", directory, e.getMessage());
            return Optional.empty();
        }
    }

    private static List<String> git(final LocalMachine machine,
                                    final Path directory,
                                    final String... arguments)
        throws Exception {

        final var output = new RecordingSubscriber<String>();
        final var errors = new RecordingSubscriber<String>();

        final ConfigurationBuilder configuration = ConfigurationBuilder.create()
            .add(Executable.of("git"))
            .add(Name.of("git"))
            .add(WorkingDirectory.of(directory.toString()));
        for (final String argument : arguments) {
            configuration.add(Argument.of(argument));
        }
        configuration
            .add(StandardOutputSubscriber.of(output))
            .add(StandardErrorSubscriber.of(errors));

        try (var git = machine.launch(Application.class, configuration)) {
            ProcessRunner.await(git, "git", () -> String.join("\n", errors.items().toList()));
        }

        return output.items()
            .map(String::trim)
            .filter(Predicate.not(String::isEmpty))
            .toList();
    }
}
