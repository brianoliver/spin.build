package build.spin.integration;

/*-
 * #%L
 * Spin Integration Tests
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

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests that launch the real, already-built spin runtime image
 * ({@code spin/.build/spin-<os>-<arch>/bin/spin.sh}, produced by spin's own self-hosting build in
 * {@code spin/pom.xml}) as a subprocess against sample fixture projects -- not spin's {@code Engine}
 * in-process on whatever full JDK is running the test suite.
 *
 * <p>This class exists because some bugs only manifest when spin is actually <em>running from</em>
 * its own self-hosted, trimmed jlink image. An in-process {@code Engine} test always runs on a
 * full, untrimmed JDK, so a supplemental {@link java.lang.module.ModuleFinder} that reads {@code
 * ModuleFinder.ofSystem()} (the currently-running JVM's own modules) rather than a target JDK's
 * real {@code jmods/} directory will resolve fine there -- and only fail once spin links its own
 * image without a dev-tool module like {@code jdk.jdwp.agent}. Only launching the real {@code
 * spin.sh} reproduces that.
 */
class SpinRuntimeIntegrationTests {

    @TempDir
    Path tempDir;

    @Test
    void jlinkShouldResolveRootModuleThatRequiresAJdkPlatformModule() throws Exception {
        // Regression coverage for a real, reported failure: "[classify] classifyAndResolve
        // failed (unable to resolve JPMS module graph from root [app]: Module jdk.jdwp.agent not
        // found, required by app) - falling back to classify-only". AbstractJavaLinker's
        // classification of the root module's graph must succeed via a supplemental
        // ModuleFinder over the *target* JDK's real jmods/ directory, not ModuleFinder.ofSystem()
        // -- this spin process's own module set, which genuinely lacks jdk.jdwp.agent once spin
        // links its own dev-tool-free runtime image (asserted below).

        final Path spinSh = spinHome().resolve("bin/spin.sh");
        assertThat(spinSh)
            .as("expected a self-hosted spin runtime at [%s] -- run `./mvnw install` from the "
                + "repo root first so spin's own jlink image exists", spinSh)
            .isRegularFile();

        assertThat(runListModules(spinHome()))
            .as("this test only proves anything if spin's own runtime genuinely lacks "
                + "jdk.jdwp.agent -- otherwise ModuleFinder.ofSystem() would trivially succeed "
                + "regardless of the fix under test")
            .doesNotContain("jdk.jdwp.agent");

        final Path fixture = copyFixture("jlink-jdk-module");

        final Process jlink = new ProcessBuilder(spinSh.toString(), "clean", "jlink", "--jlink-host-only")
            .directory(fixture.toFile())
            .redirectErrorStream(true)
            .start();
        final String output = new String(jlink.getInputStream().readAllBytes());
        final int exitCode = jlink.waitFor();

        assertThat(exitCode).as("spin.sh clean jlink failed:%n%s", output).isZero();
        assertThat(output)
            .as("expected no classify fallback warning:%n%s", output)
            .doesNotContain("falling back to classify-only");

        final Path packagePath = fixture.resolve(".build/jlink-jdk-module-" + hostOs() + "-" + hostArch());
        assertThat(packagePath).as("expected a host-target runtime image at [%s]", packagePath).isDirectory();

        // jdk.jdwp.agent must be linked directly into the produced image's own lib/modules -- not
        // merely reachable via some external module-path -- which only happens if the
        // classifier's Configuration#resolve call actually succeeded against the root module's
        // requires.
        final String modules = runListModules(packagePath);
        assertThat(modules).as("expected jdk.jdwp.agent linked into the produced image:%n%s", modules)
            .contains("jdk.jdwp.agent");
    }

    @Test
    void execShouldRunTheProjectsOwnMainClassDirectly() throws Exception {
        // Regression coverage for AbstractJavaExec: `spin exec` should compile the project, resolve
        // its dependency module-path/classpath via the same CompilationResolution Compile/JavaDoc
        // already use (no jdeps fork), and fork `java --module-path ... -m app/app.Main` with its
        // console inherited so the application's own stdout reaches spin's.
        //
        // Reuses the jlink-jdk-module fixture as-is -- it already has a real module-info.java (module
        // "app") and an app.Main with a main() that prints "hello world", exactly what exec needs, so
        // there's no reason to hand-write a second near-identical fixture just for this.

        final Path spinSh = spinHome().resolve("bin/spin.sh");
        assertThat(spinSh)
            .as("expected a self-hosted spin runtime at [%s] -- run `./mvnw install` from the "
                + "repo root first so spin's own jlink image exists", spinSh)
            .isRegularFile();

        final Path fixture = copyFixture("jlink-jdk-module");

        final Process spin = new ProcessBuilder(spinSh.toString(), "exec")
            .directory(fixture.toFile())
            .redirectErrorStream(true)
            .start();
        final String output = new String(spin.getInputStream().readAllBytes());
        final int exitCode = spin.waitFor();

        assertThat(exitCode).as("spin.sh exec failed:%n%s", output).isZero();
        assertThat(output)
            .as("expected app.Main's own stdout to reach spin's console live:%n%s", output)
            .contains("hello world");
    }

    @Test
    void jlinkRunningSpinShouldCompileAModuleInfoLessCustomizationAgainstItsOwnImage() throws Exception {
        // Regression coverage for CustomizationPlugin.spinRuntimeImage(): when spin is running from
        // its own trimmed jlink image, every spin module resolves to a jrt: location and cannot go
        // on a -classpath, so spinRuntimePath() yields nothing. The customization compile must then
        // resolve the Spin API (build.spin.Task, Project) and jakarta.inject via `javac --system`
        // pointed at spin's own image. An in-process Engine test can never exercise this -- it
        // always runs on a full, module-path/classpath JDK -- so it lives here.
        //
        // The fixture has src/build/java/Build.java but no module-info.java, so the plugin gets no
        // hand-written requires clauses to fall back on: reaching a successful `greet` execution
        // proves the --system branch compiled and loaded it.

        final Path spinSh = spinHome().resolve("bin/spin.sh");
        assertThat(spinSh)
            .as("expected a self-hosted spin runtime at [%s] -- run `./mvnw install` from the "
                + "repo root first so spin's own jlink image exists", spinSh)
            .isRegularFile();

        final Path fixture = copyFixture("custom-task-without-module-info");

        final Process spin = new ProcessBuilder(spinSh.toString(), "greet")
            .directory(fixture.toFile())
            .redirectErrorStream(true)
            .start();
        final String output = new String(spin.getInputStream().readAllBytes());
        final int exitCode = spin.waitFor();

        assertThat(exitCode).as("spin.sh greet failed:%n%s", output).isZero();

        final Path marker = fixture.resolve(".build/greeting.txt");
        assertThat(marker)
            .as("expected the module-info-less custom 'greet' task to have written its marker:%n%s", output)
            .isRegularFile();
        assertThat(Files.readString(marker)).isEqualTo("hello custom task without a module-info");
    }

    private static String runListModules(final Path imagePath) throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(imagePath.resolve("bin/java").toString(), "--list-modules")
            .redirectErrorStream(true)
            .start();
        final String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as("bin/java --list-modules failed:%n%s", output).isZero();
        return output;
    }

    private static String hostOs() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return "mac";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        return "other";
    }

    private static String hostArch() {
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "aarch64", "arm64" -> "aarch64";
            case "x86_64", "amd64" -> "x86_64";
            default -> "other";
        };
    }

    /**
     * This module's own basedir is a direct child of the repo root, exactly like {@code spin/} --
     * so the self-hosted image spin's own build produced sits at {@code <repoRoot>/spin/.build/
     * spin-<os>-<arch>}.
     */
    private static Path spinHome() {
        final Path repoRoot = Path.of("").toAbsolutePath().getParent();
        return repoRoot.resolve("spin/.build/spin-" + hostOs() + "-" + hostArch());
    }

    private Path copyFixture(final String name) throws IOException, URISyntaxException {
        final URL resource = Thread.currentThread().getContextClassLoader().getResource("workspaces/" + name);
        assertThat(resource).as("expected a workspaces/%s test resource", name).isNotNull();
        final Path source = Path.of(resource.toURI());

        final Path destination = this.tempDir.resolve(name);
        try (var paths = Files.walk(source)) {
            for (final var path : paths.sorted(Comparator.naturalOrder()).toList()) {
                final Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return destination;
    }
}
