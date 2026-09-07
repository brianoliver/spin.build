package build.spin.module.jar;

/*-
 * #%L
 * Spin Jar Module
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

import build.base.io.PathSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link JarPlugin}.
 *
 * @author reed.von.redwitz
 * @since Sep-2026
 */
class JarPluginTests {

    /**
     * Ensure {@link JarPlugin.CreateModuleArchiveBuilder} adds a single compile output's classes at the
     * root of the archive when there's nothing else to reconcile it against — the common, non-multi-release
     * case.
     */
    @Test
    void shouldAddSingleCompileOutputAtArchiveRoot(@TempDir final Path tempDir)
        throws Exception {

        final var classes = Files.createDirectories(tempDir.resolve("classes"));
        Files.writeString(classes.resolve("Main.class"), "main");

        final var jar = buildArchive(tempDir, PathSet.of(classes));

        try (var jarFile = new JarFile(jar.toFile())) {
            assertThat(jarFile.stream().map(JarEntry::getName))
                .contains("Main.class");
        }
    }

    /**
     * Ensure {@link JarPlugin.CreateModuleArchiveBuilder} includes resource files that already sit
     * alongside compiled classes in a reused build-output directory, not just the {@code .class} files.
     * This is the scenario a reused Maven {@code target/classes} produces in practice: {@code mvn}'s own
     * {@code resources:resources} goal already copied resources in there before {@code compiler:compile}
     * ran, in the same directory. Since {@link JarPlugin.CreateModuleArchiveBuilder} now packages
     * exactly what {@link build.spin.module.java.AbstractCompile}'s {@code Compile} task returns (see
     * {@link build.spin.option.ReuseExternalBuildOutput}) rather than unconditionally packaging spin's
     * own conventional build directory, whatever else lives in a reused candidate directory must still
     * make it into the archive.
     */
    @Test
    void shouldIncludeResourcesAlreadyPresentInReusedOutputDirectory(@TempDir final Path tempDir)
        throws Exception {

        final var reusedOutput = Files.createDirectories(tempDir.resolve("target/classes"));
        Files.writeString(reusedOutput.resolve("Main.class"), "main");
        Files.writeString(reusedOutput.resolve("application.properties"), "key=value");

        final var jar = buildArchive(tempDir, PathSet.of(reusedOutput));

        try (var jarFile = new JarFile(jar.toFile())) {
            assertThat(jarFile.stream().map(JarEntry::getName))
                .contains("Main.class", "application.properties");
        }
    }

    /**
     * Ensure {@link JarPlugin.CreateModuleArchiveBuilder} does not duplicate a multi-release variant's
     * output when it's physically nested inside another compile output already being added — as it is when
     * {@link build.spin.module.java.AbstractCompile} moves a non-default-version variant's classes into the
     * default variant's own {@code META-INF/versions/N} subdirectory. Adding both paths independently (the
     * pre-fix behavior) would add every class under {@code META-INF/versions/25} twice, which throws once
     * the underlying {@code JarOutputStream} rejects the resulting duplicate entry name.
     */
    @Test
    void shouldNotDuplicateNestedMultiReleaseVariantOutput(@TempDir final Path tempDir)
        throws Exception {

        final var root = Files.createDirectories(tempDir.resolve("classes"));
        Files.writeString(root.resolve("Main.class"), "main");

        final var versioned = Files.createDirectories(root.resolve("META-INF/versions/25"));
        Files.writeString(versioned.resolve("Main.class"), "main-25");

        final var jar = buildArchive(tempDir, PathSet.of(root), PathSet.of(versioned));

        try (var jarFile = new JarFile(jar.toFile())) {
            final var names = jarFile.stream().map(JarEntry::getName).toList();

            assertThat(names)
                .contains("Main.class", "META-INF/versions/25/Main.class")
                .filteredOn("META-INF/versions/25/Main.class"::equals)
                .hasSize(1);
        }
    }

    /**
     * Ensure {@link JarPlugin.CreateModuleArchiveBuilder} places a multi-release variant's output under its
     * explicit {@code META-INF/versions/N/} path in the archive, rather than flattening it to the archive
     * root, when that output isn't nested inside another compile output being added — as happens when the
     * default variant reused an external tool's build output (e.g. Maven's own {@code target/classes}) that
     * has no {@code META-INF/versions} tree of its own.
     */
    @Test
    void shouldNamespaceUnnestedMultiReleaseVariantOutput(@TempDir final Path tempDir)
        throws Exception {

        final var mavenClasses = Files.createDirectories(tempDir.resolve("maven-target/classes"));
        Files.writeString(mavenClasses.resolve("Main.class"), "main");

        final var versioned = Files.createDirectories(tempDir.resolve("spin-build/META-INF/versions/25"));
        Files.writeString(versioned.resolve("Main.class"), "main-25");

        final var jar = buildArchive(tempDir, PathSet.of(mavenClasses), PathSet.of(versioned));

        try (var jarFile = new JarFile(jar.toFile())) {
            final var names = jarFile.stream().map(JarEntry::getName).toList();

            assertThat(names)
                .contains("Main.class", "META-INF/versions/25/Main.class")
                .doesNotContain("25/Main.class");
        }
    }

    private static Path buildArchive(final Path tempDir, final PathSet... pathSets)
        throws Exception {

        final var task = new JarPlugin.CreateModuleArchiveBuilder();
        final var builder = task.create(new Manifest(), Stream.of(pathSets));

        final var archive = tempDir.resolve("archive-" + System.nanoTime() + ".jar");
        builder.build(archive);
        return archive;
    }
}
