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

import build.base.telemetry.TelemetryRecorder;
import build.spin.Project;
import build.spin.common.task.SourcePathKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Detects a project's main class, either from an explicit override or by scanning
 * {@code src/main/java} for a single source file containing a {@code main(} method.
 *
 * <p>Shared by {@link AbstractJavaLinker} (jlink's launch script) and {@code AbstractJavaExec}
 * (running the application directly) so the two never disagree about which class a project's
 * application entry point is.
 */
final class MainClassDetection {

    private MainClassDetection() {
    }

    /**
     * Detects the main class for the project at {@code projectPath}.
     *
     * @param projectPath           the {@link Project}'s path
     * @param mainClassOverride     an explicit override, if configured
     * @param mainClassKey          the configuration key the override came from, e.g. {@code "main-class"}
     *                              -- used only to compose error messages
     * @param configurationLocation a human-readable pointer to where {@code mainClassKey} is configured
     *                              -- used only to compose error messages
     * @param recorder              the {@link TelemetryRecorder} to report an auto-detected class to
     * @return the detected main class name, if any
     */
    static Optional<String> detect(final Path projectPath,
                                   final Optional<String> mainClassOverride,
                                   final String mainClassKey,
                                   final String configurationLocation,
                                   final TelemetryRecorder recorder) {
        final Path srcDir = projectPath.resolve(SourcePathKind.MAIN.sourceRoot().orElseThrow() + "java");
        if (mainClassOverride.isPresent()) {
            final String override = mainClassOverride.get();
            final Path expected = srcDir.resolve(override.replace('.', '/') + ".java");
            if (!Files.isRegularFile(expected)) {
                throw new RuntimeException(("Configured '%s' value [%s] does not exist: no source file at [%s] "
                    + "-- check %s")
                    .formatted(mainClassKey, override, expected, configurationLocation));
            }
            return mainClassOverride;
        }
        if (!Files.isDirectory(srcDir)) {
            return Optional.empty();
        }
        try (var walk = Files.walk(srcDir)) {
            final List<String> candidates = walk
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.getFileName().toString().equals("module-info.java"))
                .filter(MainClassDetection::hasMainMethod)
                .map(p -> toClassName(p, srcDir))
                .toList();
            if (candidates.size() > 1) {
                throw new RuntimeException(("Multiple candidate main classes found in [%s]: %s "
                    + "-- set '%s' in %s to disambiguate")
                    .formatted(srcDir, candidates, mainClassKey, configurationLocation));
            }
            candidates.forEach(name -> recorder.diagnostic("auto-detected main class: %s", name));
            return candidates.stream().findFirst();
        } catch (final IOException e) {
            return Optional.empty();
        }
    }

    private static boolean hasMainMethod(final Path javaFile) {
        try {
            return Files.readString(javaFile).contains("void main(");
        } catch (final IOException e) {
            return false;
        }
    }

    private static String toClassName(final Path javaFile, final Path srcDir) {
        final Path rel = srcDir.relativize(javaFile);
        final StringBuilder name = new StringBuilder();
        for (int i = 0; i < rel.getNameCount(); i++) {
            if (i > 0) {
                name.append('.');
            }
            final String part = rel.getName(i).toString();
            name.append(i == rel.getNameCount() - 1 ? part.replaceAll("\\.java$", "") : part);
        }
        return name.toString();
    }
}
