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

import build.base.foundation.Strings;
import build.base.foundation.stream.Streams;
import build.base.option.JDKVersion;
import build.base.telemetry.TelemetryRecorder;
import build.spawn.jdk.Architecture;
import build.spawn.jdk.JDK;
import build.spawn.platform.local.LocalMachine;
import build.spawn.platform.local.jdk.JDKDetector;
import build.spin.Service;
import build.spin.option.OperatingSystem;
import jakarta.inject.Inject;

import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A {@link Service} providing access to the available {@link JDK}s.
 *
 * @author brian.oliver
 * @since Nov-2019
 */
public class JavaPlatform
    implements Service {

    /**
     * The available {@link JDK}s ordered by {@link JDKVersion}.
     */
    private final SortedSet<JDK> javaDevelopmentKits;

    /**
     * Whether JDK discovery has been initiated.
     */
    private final AtomicBoolean discovered;

    /**
     * The {@link TelemetryRecorder} for the {@link JavaPlatform}.
     */
    @Inject
    private TelemetryRecorder recorder;

    /**
     * The {@link JavaPlatform.MetaClass} provides access to the {@link JDK} {@link Path}s.
     */
    @Inject
    private JavaPlatform.MetaClass metaClass;

    /**
     * The {@link LocalMachine} on which to launch {@link JDK}s.
     */
    @Inject
    private LocalMachine localMachine;

    /**
     * The {@link OperatingSystem} of the {@link JavaPlatform}.
     */
    @Inject
    private OperatingSystem operatingSystem;

    /**
     * Constructs a {@link JavaPlatform}.
     */
    public JavaPlatform() {
        // store the discovered JDKs in reverse version order
        // (this is to ensure the "first" major version is always the latest of that version).
        // Version alone is not a unique key — two JDKs of the same version but built for different
        // target platforms must both be retained, so platform and home are used as tie-breakers to
        // stop the set from treating same-version foreign-platform JDKs as duplicates.
        this.javaDevelopmentKits = new ConcurrentSkipListSet<>(
            Comparator.<JDK, JDKVersion>comparing(JDK::version).reversed()
                .thenComparing(JDK::operatingSystem)
                .thenComparing(JDK::architecture)
                .thenComparing(jdk -> jdk.home().path().toString()));

        // discovery of JDKs is deferred until it's actually required
        this.discovered = new AtomicBoolean(false);
    }

    /**
     * Constructs a {@link JavaPlatform} pre-populated with the specified {@link JDK}s, bypassing real
     * discovery. Package-private — intended for tests.
     *
     * @param jdks the {@link JDK}s to seed the {@link JavaPlatform} with
     */
    JavaPlatform(final Collection<JDK> jdks) {
        this();
        this.javaDevelopmentKits.addAll(jdks);
        this.discovered.set(true);
    }

    /**
     * Obtains a {@link Stream} of the available {@link JDK}s for the {@link JavaPlatform}.
     *
     * @return a {@link Stream} of {@link JDK}s
     */
    public Stream<JDK> stream() {

        // attempt to detect the Java Development Kits once and only once!
        if (this.discovered.compareAndSet(false, true)) {
            JDKDetector.stream()
                .flatMap(JDKDetector::detect)
                .peek(jdk -> this.recorder.info("Discovered Java Development Kit %s at %s",
                    jdk.version().get(), jdk.home().get()))
                .forEach(this.javaDevelopmentKits::add);
        }

        return this.javaDevelopmentKits.stream();
    }

    /**
     * Obtains the highest available {@link JDK} with the specified major version, built for the current
     * host platform.
     * <p>
     * Restricted to the host platform because the returned {@link JDK} is intended to be executed
     * directly (e.g. to run {@code javac}) — a {@link JDK} built for a foreign {@link TargetPlatform}
     * (e.g. one staged only for cross-target {@code jlink}ing) cannot run on this host at all.
     *
     * @param major the major version
     * @return {@link Optional} {@link JDK}
     */
    public Optional<JDK> getVersion(final int major) {
        return getVersion(major, hostTarget());
    }

    /**
     * Obtains the highest available {@link JDK} with the specified major version, built for the specified
     * {@link TargetPlatform}.
     *
     * @param major  the major version
     * @param target the required {@link TargetPlatform}
     * @return {@link Optional} {@link JDK}
     */
    public Optional<JDK> getVersion(final int major, final TargetPlatform target) {
        return preferJavaHome(stream()
            .filter(jdk -> jdk.version().major() == major)
            .filter(jdk -> matches(jdk, target)));
    }

    /**
     * Obtains the latest (highest version) {@link JDK} available for the specified {@link TargetPlatform}.
     *
     * @param target the required {@link TargetPlatform}
     * @return {@link Optional} {@link JDK}
     */
    public Optional<JDK> getLatest(final TargetPlatform target) {
        return preferJavaHome(stream()
            .filter(jdk -> matches(jdk, target)));
    }

    /**
     * Among {@code candidates} (ordered best-first, as {@link #stream()} yields them), prefers the
     * entry whose home matches the {@code JAVA_HOME} environment variable over the nominal best
     * (first) entry — but only among entries sharing the same {@link JDKVersion#major() major
     * version} as that first entry, i.e. only to break a same-major ambiguity the underlying
     * comparator otherwise resolves arbitrarily.
     * <p>
     * Without this, two same-major {@link JDK}s (e.g. a CI runner's preinstalled JDK and the one
     * the build was actually configured to use via {@code JAVA_HOME}) are ordered solely by version
     * comparison and, failing that, an incidental string comparison of their install paths — which
     * can silently select the wrong one depending on whatever else happens to be installed on the
     * host, even when the two differ only in patch/build number (e.g. a CI runner's Temurin
     * 25.0.4.1, which ships without a {@code jmods/} directory, outranking the Zulu 25.0.4 actually
     * staged via {@code JAVA_HOME}, which does).
     *
     * @param candidates the candidate {@link JDK}s, ordered best-first
     * @return the preferred {@link JDK}, if any
     */
    private static Optional<JDK> preferJavaHome(final Stream<JDK> candidates) {
        return preferJavaHome(candidates, System.getenv("JAVA_HOME"));
    }

    /**
     * As {@link #preferJavaHome(Stream)}, but with the {@code JAVA_HOME} value passed in explicitly
     * rather than read from the environment, so the tie-break can be exercised deterministically
     * without mutating the test process's own environment.
     *
     * @param candidates the candidate {@link JDK}s, ordered best-first
     * @param javaHome   the {@code JAVA_HOME} value to prefer, or {@code null} if unset
     * @return the preferred {@link JDK}, if any
     */
    // Visible for testing.
    static Optional<JDK> preferJavaHome(final Stream<JDK> candidates, final String javaHome) {
        final List<JDK> ordered = candidates.toList();
        if (ordered.isEmpty()) {
            return Optional.empty();
        }

        if (javaHome != null) {
            final Path javaHomePath = Path.of(javaHome).normalize();
            final int bestMajor = ordered.getFirst().version().major();
            for (final var jdk : ordered) {
                if (jdk.version().major() != bestMajor) {
                    break;
                }
                if (jdk.home().path().normalize().equals(javaHomePath)) {
                    return Optional.of(jdk);
                }
            }
        }

        return Optional.of(ordered.getFirst());
    }

    /**
     * Obtains the distinct {@link TargetPlatform}s of the available {@link JDK}s, e.g. to generate one
     * {@code jlink} runtime image per platform a {@link JDK} has been staged for.
     *
     * @return a {@link Stream} of {@link TargetPlatform}s
     */
    public Stream<TargetPlatform> targets() {
        return stream()
            .map(jdk -> new TargetPlatform(jdk.operatingSystem(), jdk.architecture()))
            .distinct();
    }

    private static boolean matches(final JDK jdk, final TargetPlatform target) {
        return jdk.operatingSystem() == target.operatingSystem() && jdk.architecture() == target.architecture();
    }

    /**
     * Obtains the {@link TargetPlatform} of the current host, i.e. the platform of the currently
     * executing Virtual Machine.
     *
     * @return the host {@link TargetPlatform}
     */
    public static TargetPlatform hostTarget() {
        return new TargetPlatform(build.spawn.jdk.OperatingSystem.current(), Architecture.current());
    }

    /**
     * Obtains the earliest (lowest version) {@link JDK} available for the current host platform.
     * <p>
     * Restricted to the host platform — see {@link #getVersion(int)}.
     *
     * @return {@link Optional} {@link JDK}
     */
    public Optional<JDK> getEarliest() {
        final var host = hostTarget();
        return Streams.reverse(stream())
            .filter(jdk -> matches(jdk, host))
            .findFirst();
    }

    /**
     * Obtains the latest (highest version) {@link JDK} available for the current host platform.
     * <p>
     * Restricted to the host platform — see {@link #getVersion(int)}.
     *
     * @return {@link Optional} {@link JDK}
     */
    public Optional<JDK> getLatest() {
        return getLatest(hostTarget());
    }

    // cached per-JDK platform module name sets, keyed by JDK (whose equals/hashCode are value-based),
    // so repeated isJavaPlatformModule() calls across every spin-java-module/spin-maven-module call
    // site don't re-scan the same JDK's jmods/ directory over and over
    private static final Map<JDK, Set<String>> PLATFORM_MODULES_CACHE = new ConcurrentHashMap<>();

    /**
     * Determines if the specified module name is one of the real platform modules the given {@link JDK}
     * provides, read from its {@code jmods/} directory (falling back to {@link ModuleFinder#ofSystem()}
     * for a jlinked-down runtime that has no {@code jmods/} of its own) and cached thereafter.
     * <p>
     * This reads the actual module list rather than guessing from a name prefix (anything starting with
     * {@code java.}/{@code jdk.}): which modules a JDK actually provides varies by version -
     * {@code java.annotation}, {@code java.corba}, {@code java.xml.bind}, {@code java.xml.ws} and others
     * existed in JDK 9/10 and were removed from JDK 11 onward, while still being published as ordinary
     * Maven artifacts (e.g. {@code javax.annotation-api}) whose {@code Automatic-Module-Name} reuses the
     * old JDK module name specifically so old {@code requires java.annotation;} declarations keep
     * compiling once that jar is back on the module path. A prefix guess can't tell these apart from
     * modules the given {@link JDK} actually provides.
     *
     * @param jdk        the {@link JDK} whose platform modules to check against
     * @param moduleName the module name
     * @return {@code true} if the specified module name is a platform module of the given {@link JDK}
     */
    public static boolean isJavaPlatformModule(final JDK jdk, final String moduleName) {
        return !Strings.isEmpty(moduleName)
            && PLATFORM_MODULES_CACHE.computeIfAbsent(jdk, JavaPlatform::readPlatformModules).contains(moduleName);
    }

    private static Set<String> readPlatformModules(final JDK jdk) {
        final Path jmodsDir = jdk.home().path().resolve("jmods");

        if (Files.isDirectory(jmodsDir)) {
            try {
                return JmodModuleFinder.of(jmodsDir).findAll().stream()
                    .map(reference -> reference.descriptor().name())
                    .collect(Collectors.toUnmodifiableSet());
            } catch (final IOException e) {
                // fall through -- some JDK distributions (e.g. a hosted-runner's trimmed Temurin
                // install) omit jmods/ entirely, so an unreadable directory is treated the same as
                // a missing one rather than failing outright
            }
        }

        // ModuleFinder.ofSystem() only reflects the modules of the JDK Spin itself is currently
        // running on -- a safe proxy for `jdk` ONLY when `jdk` actually is that JDK (e.g. a jlinked-down
        // runtime image with no jmods/ of its own, still executing as this very process). For any other
        // JDK missing jmods/ (a different major version, or one staged only for a foreign target
        // platform), silently substituting the host's module list would misclassify that JDK's platform
        // modules without any indication something went wrong -- fail loudly instead.
        if (jdk.equals(JDK.current())) {
            return ModuleFinder.ofSystem().findAll().stream()
                .map(reference -> reference.descriptor().name())
                .collect(Collectors.toUnmodifiableSet());
        }

        throw new IllegalStateException(
            "Cannot determine the platform modules of Java Development Kit [" + jdk + "]: its jmods/ "
                + "directory [" + jmodsDir + "] is missing or unreadable, and it is not the Java "
                + "Development Kit currently executing Spin, so its module list cannot be inferred.");
    }

    /**
     * The {@link Service.MetaClass} for {@link JavaPlatform}.
     */
    public static class MetaClass
        implements Service.MetaClass {

        /**
         * The {@link TelemetryRecorder} for the {@link JavaPlatform.MetaClass}.
         */
        @Inject
        private TelemetryRecorder recorder;

        /**
         * The {@link OperatingSystem} of the {@link JavaPlatform}.
         */
        @Inject
        private OperatingSystem operatingSystem;

        @Override
        public boolean isDetectedIn(final FileSystem fileSystem) {
            final var detected = JDKDetector.stream()
                .flatMap(JDKDetector::paths)
                .findAny()
                .isPresent();

            if (!detected) {
                this.recorder.warn("No Java Development Kits were discovered!");
            }

            return detected;
        }
    }
}
