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

import build.base.option.JDKVersion;
import build.spawn.jdk.Architecture;
import build.spawn.jdk.JDK;
import build.spawn.jdk.OperatingSystem;
import build.spawn.jdk.option.JDKHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ModuleDesc;
import java.lang.constant.PackageDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link JavaPlatform}, in particular the target-platform-aware {@link JDK} lookups added to
 * support generating a {@code jlink} runtime image per staged target platform.
 *
 * @author reed.vonredwitz
 * @since Jul-2026
 */
class JavaPlatformTest {

    @TempDir
    Path tempDir;

    private static JDK jdk(final String version, final OperatingSystem os, final Architecture arch, final String home) {
        return JDK.of(JDKVersion.of(version), JDKHome.of(home), os, arch);
    }

    @Test
    void targets_returnsDistinctPlatformsAcrossJDKsOfTheSameVersion() {
        // same version, different platforms — the historically buggy case where a version-only
        // comparator would treat these as duplicates and silently drop one
        final var linux = jdk("25.0.3", OperatingSystem.LINUX, Architecture.X86_64, "/usr/lib/jvm/zulu25-linux");
        final var mac = jdk("25.0.3", OperatingSystem.MAC, Architecture.AARCH64, "/usr/lib/jvm/zulu25-mac");
        final var platform = new JavaPlatform(List.of(linux, mac));

        assertThat(platform.targets()).containsExactlyInAnyOrder(
            new TargetPlatform(OperatingSystem.LINUX, Architecture.X86_64),
            new TargetPlatform(OperatingSystem.MAC, Architecture.AARCH64));
    }

    @Test
    void stream_retainsBothJDKsWhenVersionsMatchButPlatformsDiffer() {
        final var linux = jdk("25.0.3", OperatingSystem.LINUX, Architecture.X86_64, "/usr/lib/jvm/zulu25-linux");
        final var mac = jdk("25.0.3", OperatingSystem.MAC, Architecture.AARCH64, "/usr/lib/jvm/zulu25-mac");
        final var platform = new JavaPlatform(List.of(linux, mac));

        assertThat(platform.stream()).containsExactlyInAnyOrder(linux, mac);
    }

    @Test
    void getVersion_withTarget_findsTheJDKMatchingBothMajorVersionAndTarget() {
        final var linux25 = jdk("25.0.3", OperatingSystem.LINUX, Architecture.X86_64, "/usr/lib/jvm/zulu25-linux");
        final var mac25 = jdk("25.0.3", OperatingSystem.MAC, Architecture.AARCH64, "/usr/lib/jvm/zulu25-mac");
        final var mac21 = jdk("21.0.1", OperatingSystem.MAC, Architecture.AARCH64, "/usr/lib/jvm/zulu21-mac");
        final var platform = new JavaPlatform(List.of(linux25, mac25, mac21));

        final var target = new TargetPlatform(OperatingSystem.MAC, Architecture.AARCH64);

        assertThat(platform.getVersion(25, target)).contains(mac25);
        assertThat(platform.getVersion(21, target)).contains(mac21);
    }

    @Test
    void getVersion_withTarget_isEmptyWhenNoJDKMatchesTheTarget() {
        final var linux25 = jdk("25.0.3", OperatingSystem.LINUX, Architecture.X86_64, "/usr/lib/jvm/zulu25-linux");
        final var platform = new JavaPlatform(List.of(linux25));

        final var windowsTarget = new TargetPlatform(OperatingSystem.WINDOWS, Architecture.X86_64);

        assertThat(platform.getVersion(25, windowsTarget)).isEmpty();
    }

    @Test
    void getLatest_withTarget_returnsHighestVersionForThatTargetOnly() {
        final var mac21 = jdk("21.0.1", OperatingSystem.MAC, Architecture.AARCH64, "/usr/lib/jvm/zulu21-mac");
        final var mac25 = jdk("25.0.3", OperatingSystem.MAC, Architecture.AARCH64, "/usr/lib/jvm/zulu25-mac");
        final var linux30 = jdk("30.0.0", OperatingSystem.LINUX, Architecture.X86_64, "/usr/lib/jvm/zulu30-linux");
        final var platform = new JavaPlatform(List.of(mac21, mac25, linux30));

        final var target = new TargetPlatform(OperatingSystem.MAC, Architecture.AARCH64);

        assertThat(platform.getLatest(target)).contains(mac25);
    }

    // --- JAVA_HOME tie-break for same-version JDKs ---
    //
    // Regression coverage for a real, reported failure: a CI runner had two JDKs both reporting
    // version 25.0.4 (a preinstalled Temurin and the Zulu actually staged for the build via
    // JAVA_HOME) — the previous path-string tie-break picked whichever sorted first
    // alphabetically ("Temurin" < "Zulu"), silently ignoring which JDK the build was actually
    // configured to use.

    @Test
    void preferJavaHome_fallsBackToTheFirstCandidateWhenJavaHomeIsUnset() {
        final var temurin = jdk("25.0.4", OperatingSystem.LINUX, Architecture.X86_64,
            "/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/25.0.4-7/x64");
        final var zulu = jdk("25.0.4", OperatingSystem.LINUX, Architecture.X86_64,
            "/opt/hostedtoolcache/Java_Zulu_jdk/25.0.4-7/x64");

        // candidates are already ordered best-first by the caller (as stream() yields them);
        // with no JAVA_HOME to break the tie, the first (nominal-best) entry wins, same as before
        assertThat(JavaPlatform.preferJavaHome(Stream.of(temurin, zulu), null)).contains(temurin);
    }

    @Test
    void preferJavaHome_prefersTheJavaHomeMatchOverPathOrderingOnATie() {
        final var temurin = jdk("25.0.4", OperatingSystem.LINUX, Architecture.X86_64,
            "/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/25.0.4-7/x64");
        final var zulu = jdk("25.0.4", OperatingSystem.LINUX, Architecture.X86_64,
            "/opt/hostedtoolcache/Java_Zulu_jdk/25.0.4-7/x64");

        // Temurin still sorts first, but JAVA_HOME points at the Zulu install actually staged for
        // the build -- that one must win the tie instead
        assertThat(JavaPlatform.preferJavaHome(Stream.of(temurin, zulu), zulu.home().path().toString()))
            .contains(zulu);
    }

    @Test
    void preferJavaHome_prefersTheJavaHomeMatchAcrossDifferingPatchVersionsOfTheSameMajor() {
        // real, reported CI failure: a runner had a Temurin 25.0.4.1 (no jmods/ directory) ranked
        // ahead of the Zulu 25.0.4 actually staged via JAVA_HOME, purely because .4.1 outranks .4
        // as a JDKVersion -- the two were never a literal tie, so the tie-break above never kicked
        // in, and the higher-patch-but-wrong JDK won every time
        final var temurin = jdk("25.0.4.1", OperatingSystem.LINUX, Architecture.X86_64,
            "/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/25.0.4-1/x64");
        final var zulu = jdk("25.0.4", OperatingSystem.LINUX, Architecture.X86_64,
            "/opt/hostedtoolcache/Java_Zulu_jdk/25.0.4-7/x64");

        assertThat(JavaPlatform.preferJavaHome(Stream.of(temurin, zulu), zulu.home().path().toString()))
            .contains(zulu);
    }

    @Test
    void preferJavaHome_ignoresJavaHomeWhenItDoesNotMatchTheBestVersion() {
        final var older = jdk("21.0.1", OperatingSystem.LINUX, Architecture.X86_64, "/opt/hostedtoolcache/old");
        final var newer = jdk("25.0.4", OperatingSystem.LINUX, Architecture.X86_64, "/opt/hostedtoolcache/new");

        // JAVA_HOME points at the older, non-best JDK -- the tie-break only ever operates among
        // entries tied on the *best* version, so it must not downgrade to a worse version
        assertThat(JavaPlatform.preferJavaHome(Stream.of(newer, older), older.home().path().toString()))
            .contains(newer);
    }

    @Test
    void preferJavaHome_fallsBackToTheFirstCandidateWhenJavaHomeMatchesNoCandidate() {
        final var temurin = jdk("25.0.4", OperatingSystem.LINUX, Architecture.X86_64,
            "/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/25.0.4-7/x64");
        final var zulu = jdk("25.0.4", OperatingSystem.LINUX, Architecture.X86_64,
            "/opt/hostedtoolcache/Java_Zulu_jdk/25.0.4-7/x64");

        // JAVA_HOME is set but doesn't match either tied candidate -- must fall back to the nominal
        // best (first) entry rather than e.g. throwing or returning empty
        assertThat(JavaPlatform.preferJavaHome(Stream.of(temurin, zulu), "/opt/hostedtoolcache/does-not-exist"))
            .contains(temurin);
    }

    @Test
    void preferJavaHome_matchesJavaHomeDespiteNonNormalizedPathDifferences() {
        final var temurin = jdk("25.0.4", OperatingSystem.LINUX, Architecture.X86_64,
            "/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/25.0.4-7/x64");
        final var zulu = jdk("25.0.4", OperatingSystem.LINUX, Architecture.X86_64,
            "/opt/hostedtoolcache/Java_Zulu_jdk/25.0.4-7/x64");

        // JAVA_HOME carries a trailing slash and a redundant "." segment -- the comparison must
        // normalize both sides rather than requiring a literal string match
        assertThat(JavaPlatform.preferJavaHome(Stream.of(temurin, zulu),
            "/opt/hostedtoolcache/./Java_Zulu_jdk/25.0.4-7/x64/"))
            .contains(zulu);
    }

    @Test
    void preferJavaHome_returnsEmptyWhenThereAreNoCandidates() {
        assertThat(JavaPlatform.preferJavaHome(Stream.<JDK>empty(), "/opt/hostedtoolcache/anything")).isEmpty();
    }

    @Test
    void getVersion_delegatesToPreferJavaHomeUsingTheRealEnvironment() {
        // sanity check that the public getVersion(major, target) overload actually routes through
        // preferJavaHome(candidates, System.getenv("JAVA_HOME")) rather than the raw unbroken tie --
        // exercised with a single candidate so the result is deterministic regardless of whatever
        // JAVA_HOME happens to be set to in this test process
        final var host = jdk("25.0.3", OperatingSystem.current(), Architecture.current(), "/usr/lib/jvm/zulu25-host");
        final var platform = new JavaPlatform(List.of(host));

        assertThat(platform.getVersion(25)).contains(host);
    }

    // --- host-platform scoping for getVersion(major)/getLatest()/getEarliest() ---
    //
    // These overloads (unlike their *(..., target) counterparts) return a JDK intended to be executed
    // directly on this host (e.g. to run javac) — a same-version JDK staged only for a foreign target
    // platform must never be returned, since it can't run here. This is the actual bug: a version-only
    // tie-break previously let a foreign-platform JDK win non-deterministically over the host one.

    private static OperatingSystem foreignOperatingSystem() {
        return OperatingSystem.current() == OperatingSystem.LINUX ? OperatingSystem.MAC : OperatingSystem.LINUX;
    }

    private static Architecture foreignArchitecture() {
        return Architecture.current() == Architecture.X86_64 ? Architecture.AARCH64 : Architecture.X86_64;
    }

    @Test
    void getVersion_withoutTarget_neverReturnsAForeignPlatformJDKEvenWhenItSortsFirst() {
        final var host = jdk("25.0.3", OperatingSystem.current(), Architecture.current(), "/usr/lib/jvm/zulu25-host");
        // deliberately given a "AARCH64"-style home/name that would previously have sorted before the
        // host JDK under a naive alphabetical tie-break on OS/arch name
        final var foreign = jdk("25.0.3", foreignOperatingSystem(), foreignArchitecture(), "/usr/lib/jvm/aaa-foreign");
        final var platform = new JavaPlatform(List.of(host, foreign));

        assertThat(platform.getVersion(25)).contains(host);
    }

    @Test
    void getLatest_withoutTarget_onlyConsidersHostPlatformJDKs() {
        final var hostOlder = jdk("21.0.1", OperatingSystem.current(), Architecture.current(), "/usr/lib/jvm/zulu21-host");
        final var foreignNewer = jdk("30.0.0", foreignOperatingSystem(), foreignArchitecture(), "/usr/lib/jvm/zulu30-foreign");
        final var platform = new JavaPlatform(List.of(hostOlder, foreignNewer));

        assertThat(platform.getLatest()).contains(hostOlder);
    }

    @Test
    void getEarliest_withoutTarget_onlyConsidersHostPlatformJDKs() {
        final var hostNewer = jdk("25.0.3", OperatingSystem.current(), Architecture.current(), "/usr/lib/jvm/zulu25-host");
        final var foreignOlder = jdk("17.0.1", foreignOperatingSystem(), foreignArchitecture(), "/usr/lib/jvm/zulu17-foreign");
        final var platform = new JavaPlatform(List.of(hostNewer, foreignOlder));

        assertThat(platform.getEarliest()).contains(hostNewer);
    }

    @Test
    void getVersion_withoutTarget_isEmptyWhenOnlyForeignPlatformJDKsMatchTheVersion() {
        final var foreign = jdk("25.0.3", foreignOperatingSystem(), foreignArchitecture(), "/usr/lib/jvm/zulu25-foreign");
        final var platform = new JavaPlatform(List.of(foreign));

        assertThat(platform.getVersion(25)).isEmpty();
    }

    @Test
    void hostTarget_matchesTheCurrentlyExecutingVirtualMachinesPlatform() {
        assertThat(JavaPlatform.hostTarget())
            .isEqualTo(new TargetPlatform(OperatingSystem.current(), Architecture.current()));
    }

    // --- isJavaPlatformModule reads a JDK's real module list instead of guessing from a name prefix ---
    //
    // Exercised against JDK.current() -- the JDK actually running this test -- so it takes whichever
    // code path readPlatformModules resolves to on this machine (jmods/ scan, or the
    // ModuleFinder.ofSystem() fallback, which only applies to a JDK equal to JDK.current()) -- both
    // must agree with the JDK's real module graph.

    private static JDK runningJdk() {
        return JDK.current();
    }

    @Test
    void isJavaPlatformModule_trueForAModuleTheRunningJdkActuallyProvides() {
        assertThat(JavaPlatform.isJavaPlatformModule(runningJdk(), "java.base")).isTrue();
    }

    @Test
    void isJavaPlatformModule_falseForAModuleNameRemovedFromModernJdksButStillUsedAsAnAutomaticModuleName() {
        // java.annotation was a real platform module in JDK 9/10, removed from JDK 11 onward -- but
        // javax.annotation-api still ships with Automatic-Module-Name: java.annotation, precisely so
        // old `requires java.annotation;` declarations keep compiling once that jar is back on the
        // module path. A prefix guess (startsWith("java.")) can't tell that apart from a module the
        // running (modern) JDK actually provides; reading the real module list can.
        assertThat(JavaPlatform.isJavaPlatformModule(runningJdk(), "java.annotation")).isFalse();
    }

    @Test
    void isJavaPlatformModule_falseForAnEmptyModuleName() {
        assertThat(JavaPlatform.isJavaPlatformModule(runningJdk(), "")).isFalse();
    }

    // --- isJavaPlatformModule against a target JDK that is NOT the one running this test ---
    //
    // readPlatformModules() has two paths for a non-running JDK: read its own jmods/ (below), or,
    // when jmods/ is missing/unreadable, throw rather than silently substituting the host's module
    // list -- ModuleFinder.ofSystem() is only ever a safe proxy for the JDK actually executing Spin.

    @Test
    void isJavaPlatformModule_readsFromTheGivenJdksOwnJmodsDirectoryRatherThanTheRunningJdk() throws IOException {
        final Path jmodsDir = Files.createDirectory(this.tempDir.resolve("jmods"));
        jmod(jmodsDir, "java.base.jmod", "java.base");
        jmod(jmodsDir, "jdk.compiler.jmod", "jdk.compiler", "java.base");

        final JDK target = jdk("25.0.3", OperatingSystem.current(), Architecture.current(), this.tempDir.toString());

        assertThat(JavaPlatform.isJavaPlatformModule(target, "jdk.compiler")).isTrue();
        // java.logging is a real module of the JDK actually running this test, but it was never
        // written into this fabricated jmods/ -- proves the check is scoped to `target`'s own
        // module list rather than falling back to (or merging with) the host's
        assertThat(JavaPlatform.isJavaPlatformModule(target, "java.logging")).isFalse();
    }

    @Test
    void isJavaPlatformModule_throwsForANonRunningJdkWithNoJmodsDirectory() {
        final JDK target = jdk("25.0.3", OperatingSystem.current(), Architecture.current(),
            this.tempDir.resolve("does-not-exist").toString());

        assertThatThrownBy(() -> JavaPlatform.isJavaPlatformModule(target, "java.base"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("jmods/")
            .hasMessageContaining(target.toString());
    }

    /**
     * Writes a minimal {@code .jmod}-shaped zip: a {@code classes/module-info.class} entry built with
     * the {@link ClassFile} API, same as {@code JmodModuleFinderTest} uses -- {@link JmodModuleFinder}
     * only ever reads that one entry, so the rest of a real {@code .jmod}'s shape is irrelevant here.
     */
    private static Path jmod(final Path dir, final String fileName, final String moduleName,
                             final String... requiresModules) throws IOException {
        final byte[] moduleInfoBytes = ClassFile.of().buildModule(
            ModuleAttribute.of(
                ModuleDesc.of(moduleName),
                mb -> {
                    for (final String req : requiresModules) {
                        mb.requires(ModuleDesc.of(req), 0, null);
                    }
                    mb.exports(PackageDesc.of(moduleName), 0);
                }));

        final Path jmod = dir.resolve(fileName);
        try (var zos = new ZipOutputStream(Files.newOutputStream(jmod))) {
            zos.putNextEntry(new ZipEntry("classes/module-info.class"));
            zos.write(moduleInfoBytes);
            zos.closeEntry();
        }
        return jmod;
    }
}
