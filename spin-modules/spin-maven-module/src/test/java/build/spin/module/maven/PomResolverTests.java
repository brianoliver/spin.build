package build.spin.module.maven;

/*-
 * #%L
 * Spin Maven Module
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

import build.spin.common.telemetry.TelemetryPublisher;
import build.spin.module.modulesystem.pom.Gav;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PomResolver}, using a small synthetic offline repository fixture to verify
 * dependency-graph behavior that would otherwise require flaky, non-deterministic assertions
 * against Maven Central.
 */
public class PomResolverTests {

    /**
     * A diamond dependency graph, fixed as an offline local repository under
     * {@code src/test/resources/nearest-wins-repo}:
     *
     * <pre>
     * root -&gt; b -&gt; c -&gt; conflict:2.0   (depth 3)
     * root -&gt; a -&gt; conflict:1.0         (depth 2)
     * </pre>
     *
     * {@code b} is declared before {@code a} in {@code root}'s POM, so an implementation that picked
     * the first-encountered version by declaration order (rather than by depth) would wrongly select
     * {@code conflict:2.0}. Maven's nearest-wins rule requires {@code conflict:1.0} (the shallower
     * occurrence) to win.
     */
    @Test
    void shouldResolveNearestWinsOnVersionConflict() throws URISyntaxException {
        final Path localRepository = fixtureRepository();

        final PomResolver resolver = new PomResolver(
            new TelemetryPublisher(URI.create("maven://nearest-wins-test"), System.out::println),
            localRepository,
            true, // offline: everything must be satisfied from the fixture repo
            List.of());

        final List<Path> resolved = resolver.resolveTransitive("test:root:1.0");

        assertThat(resolved)
            .extracting(Path::getFileName)
            .extracting(Object::toString)
            .containsExactlyInAnyOrder("root-1.0.jar", "b-1.0.jar", "a-1.0.jar", "c-1.0.jar", "conflict-1.0.jar");
    }

    /**
     * A fixture, fixed as an offline local repository under
     * {@code src/test/resources/exclusions-repo}:
     *
     * <pre>
     * root -&gt; a (excludes test:conflict) -&gt; conflict:1.0
     * root -&gt; b                          -&gt; conflict:2.0
     * </pre>
     *
     * {@code a} and {@code b} both reach {@code test:conflict} at the same depth, and {@code a} is
     * declared first, so without exclusion enforcement nearest-wins (first-encountered tie-break)
     * would pick {@code conflict:1.0} via {@code a}. Because {@code a} excludes {@code test:conflict},
     * that occurrence must never even enter the graph, leaving {@code b}'s unexcluded
     * {@code conflict:2.0} as the only candidate.
     */
    @Test
    void shouldEnforceTransitiveExclusions() throws URISyntaxException {
        final Path localRepository = fixtureRepository("exclusions-repo");

        final PomResolver resolver = new PomResolver(
            new TelemetryPublisher(URI.create("maven://exclusions-test"), System.out::println),
            localRepository,
            true, // offline: everything must be satisfied from the fixture repo
            List.of());

        final List<Path> resolved = resolver.resolveTransitive("test:root:1.0");

        assertThat(resolved)
            .extracting(Path::getFileName)
            .extracting(Object::toString)
            .containsExactlyInAnyOrder("root-1.0.jar", "a-1.0.jar", "b-1.0.jar", "conflict-2.0.jar");
    }

    /**
     * The 3-arg {@link PomResolver#resolveTransitive(Gav, String, Set)} overload seeds the
     * BFS with a set of exclusions already applied to the <em>root</em> — the {@code <exclusions>} a
     * consuming pom declared on this dependency edge, which are lost once the edge is reduced to a
     * bare coordinate for {@code AbstractDetectResolution}'s per-{@code requires} resolution. Using
     * the {@code exclusions-repo} fixture, where {@code a} → {@code conflict:1.0}: resolving
     * {@code a} alone pulls in {@code conflict:1.0}, but seeding {@code test:conflict} as a root
     * exclusion must drop it.
     */
    @Test
    void shouldApplyRootExclusionsPassedToThreeArgOverload() throws URISyntaxException {
        final Path localRepository = fixtureRepository("exclusions-repo");
        final PomResolver resolver = new PomResolver(
            new TelemetryPublisher(URI.create("maven://root-exclusions-test"), System.out::println),
            localRepository,
            true,
            List.of());

        final Gav a = new Gav("test", "a", "1.0");

        assertThat(resolver.resolveTransitive(a, "jar", Set.of()))
            .extracting(Path::getFileName)
            .extracting(Object::toString)
            .containsExactlyInAnyOrder("a-1.0.jar", "conflict-1.0.jar");

        assertThat(resolver.resolveTransitive(a, "jar", Set.of("test:conflict")))
            .extracting(Path::getFileName)
            .extracting(Object::toString)
            .containsExactly("a-1.0.jar");
    }

    /**
     * {@link MavenSettingsReader#read} must pick up {@code <repository><snapshots><updatePolicy>}
     * per repository rather than ignoring it, and must not confuse it with any other repository's
     * policy (or lack thereof).
     */
    @Test
    void shouldParseSnapshotUpdatePolicyFromSettingsXml(@org.junit.jupiter.api.io.TempDir final Path tempDir)
        throws IOException {
        final Path settingsPath = tempDir.resolve("settings.xml");
        Files.writeString(settingsPath, """
            <settings>
              <profiles>
                <profile>
                  <id>internal</id>
                  <repositories>
                    <repository>
                      <id>internal-nexus</id>
                      <url>https://nexus.example.com/repository/maven-snapshots</url>
                      <snapshots>
                        <enabled>true</enabled>
                        <updatePolicy>interval:5</updatePolicy>
                      </snapshots>
                    </repository>
                    <repository>
                      <id>central-mirror</id>
                      <url>https://repo.maven.apache.org/maven2</url>
                    </repository>
                  </repositories>
                </profile>
              </profiles>
              <activeProfiles>
                <activeProfile>internal</activeProfile>
              </activeProfiles>
            </settings>
            """);

        final List<RemoteRepo> repos = MavenSettingsReader.read(settingsPath,
            new TelemetryPublisher(URI.create("maven://settings-test"), System.out::println));

        assertThat(repos)
            .filteredOn(repo -> "internal-nexus".equals(repo.id()))
            .extracting(RemoteRepo::snapshotUpdatePolicy)
            .containsExactly(Optional.of("interval:5"));
        assertThat(repos)
            .filteredOn(repo -> "central-mirror".equals(repo.id()))
            .extracting(RemoteRepo::snapshotUpdatePolicy)
            .containsExactly(Optional.empty());
    }

    /**
     * A profile with {@code <activation><activeByDefault>true</activeByDefault></activation>} must
     * be treated as active when {@code <activeProfiles>} is absent — Maven doesn't require the
     * default-active convenience to also be listed explicitly.
     */
    @Test
    void shouldActivateProfileMarkedActiveByDefault(@org.junit.jupiter.api.io.TempDir final Path tempDir)
        throws IOException {
        final Path settingsPath = tempDir.resolve("settings.xml");
        Files.writeString(settingsPath, """
            <settings>
              <profiles>
                <profile>
                  <id>internal</id>
                  <activation>
                    <activeByDefault>true</activeByDefault>
                  </activation>
                  <repositories>
                    <repository>
                      <id>internal-nexus</id>
                      <url>https://nexus.example.com/repository/maven-releases</url>
                    </repository>
                  </repositories>
                </profile>
              </profiles>
            </settings>
            """);

        final List<RemoteRepo> repos = MavenSettingsReader.read(settingsPath,
            new TelemetryPublisher(URI.create("maven://settings-test"), System.out::println));

        assertThat(repos)
            .extracting(RemoteRepo::id)
            .containsExactly("internal-nexus");
    }

    /**
     * An explicit {@code <activeProfiles>} list must suppress a profile's own
     * {@code activeByDefault}, matching Maven's rule that default activation only applies when
     * nothing has been activated some other way.
     */
    @Test
    void shouldSuppressActiveByDefaultWhenActiveProfilesIsExplicit(
        @org.junit.jupiter.api.io.TempDir final Path tempDir) throws IOException {
        final Path settingsPath = tempDir.resolve("settings.xml");
        Files.writeString(settingsPath, """
            <settings>
              <profiles>
                <profile>
                  <id>defaulted</id>
                  <activation>
                    <activeByDefault>true</activeByDefault>
                  </activation>
                  <repositories>
                    <repository>
                      <id>defaulted-repo</id>
                      <url>https://defaulted.example.com/repo</url>
                    </repository>
                  </repositories>
                </profile>
                <profile>
                  <id>explicit</id>
                  <repositories>
                    <repository>
                      <id>explicit-repo</id>
                      <url>https://explicit.example.com/repo</url>
                    </repository>
                  </repositories>
                </profile>
              </profiles>
              <activeProfiles>
                <activeProfile>explicit</activeProfile>
              </activeProfiles>
            </settings>
            """);

        final List<RemoteRepo> repos = MavenSettingsReader.read(settingsPath,
            new TelemetryPublisher(URI.create("maven://settings-test"), System.out::println));

        assertThat(repos)
            .extracting(RemoteRepo::id)
            .containsExactly("explicit-repo");
    }

    /**
     * An active {@code <proxy>} must be parsed with its host/port/credentials, and
     * {@code <nonProxyHosts>} must correctly bypass matching hosts.
     */
    @Test
    void shouldParseActiveProxyAndRespectNonProxyHosts(@org.junit.jupiter.api.io.TempDir final Path tempDir)
        throws IOException {
        final Path settingsPath = tempDir.resolve("settings.xml");
        Files.writeString(settingsPath, """
            <settings>
              <proxies>
                <proxy>
                  <id>corporate</id>
                  <active>true</active>
                  <protocol>http</protocol>
                  <host>proxy.example.com</host>
                  <port>8080</port>
                  <username>proxyuser</username>
                  <password>proxypass</password>
                  <nonProxyHosts>*.internal.example.com|localhost</nonProxyHosts>
                </proxy>
              </proxies>
            </settings>
            """);

        final var proxy = MavenSettingsReader.readProxy(settingsPath,
            new TelemetryPublisher(URI.create("maven://settings-test"), System.out::println));

        assertThat(proxy).isPresent();
        assertThat(proxy.orElseThrow().host()).isEqualTo("proxy.example.com");
        assertThat(proxy.orElseThrow().port()).isEqualTo(8080);
        assertThat(proxy.orElseThrow().username()).isEqualTo(Optional.of("proxyuser"));
        assertThat(proxy.orElseThrow().bypasses("localhost")).isTrue();
        assertThat(proxy.orElseThrow().bypasses("repo.internal.example.com")).isTrue();
        assertThat(proxy.orElseThrow().bypasses("repo.maven.apache.org")).isFalse();
    }

    /**
     * A {@code <proxy>} with {@code <active>false</active>} must not be returned, even when it's the
     * only proxy declared.
     */
    @Test
    void shouldIgnoreInactiveProxy(@org.junit.jupiter.api.io.TempDir final Path tempDir) throws IOException {
        final Path settingsPath = tempDir.resolve("settings.xml");
        Files.writeString(settingsPath, """
            <settings>
              <proxies>
                <proxy>
                  <id>disabled</id>
                  <active>false</active>
                  <host>proxy.example.com</host>
                  <port>8080</port>
                </proxy>
              </proxies>
            </settings>
            """);

        final var proxy = MavenSettingsReader.readProxy(settingsPath,
            new TelemetryPublisher(URI.create("maven://settings-test"), System.out::println));

        assertThat(proxy).isEmpty();
    }

    /**
     * We don't know which configured repository will actually serve a given SNAPSHOT until we've
     * already decided whether the cached copy is stale enough to check — so {@code
     * effectiveSnapshotUpdateTtlMs()} must use the strictest (shortest) TTL across all configured
     * repos: a {@code never} policy on one repo must not suppress a shorter {@code interval:5} policy
     * declared on another.
     */
    @Test
    void shouldUseStrictestSnapshotUpdatePolicyAcrossRepos() {
        final PomResolver resolver = new PomResolver(
            new TelemetryPublisher(URI.create("maven://ttl-test"), System.out::println),
            Path.of("."),
            true,
            List.of(
                RemoteRepo.of("lazy", "https://lazy.example.com", "never"),
                RemoteRepo.of("eager", "https://eager.example.com", "interval:5")));

        assertThat(resolver.effectiveSnapshotUpdateTtlMs()).isEqualTo(5 * 60_000L);
    }

    /**
     * A repo with no explicit {@code updatePolicy} must fall back to the historical daily default,
     * not to "never check" or "always check".
     */
    @Test
    void shouldDefaultToDailySnapshotUpdatePolicyWhenUnconfigured() {
        final PomResolver resolver = new PomResolver(
            new TelemetryPublisher(URI.create("maven://ttl-default-test"), System.out::println),
            Path.of("."),
            true,
            List.of(RemoteRepo.of("central", "https://repo.maven.apache.org/maven2")));

        assertThat(resolver.effectiveSnapshotUpdateTtlMs()).isEqualTo(86_400_000L);
    }

    /**
     * A fixture, fixed as an offline local repository under {@code src/test/resources/revision-repo},
     * modeling the Maven Flatten Plugin's CI-friendly-versions pattern: {@code root}'s own
     * {@code <version>} is the literal placeholder {@code ${revision}} (unresolved, exactly as it
     * would appear in an un-flattened source POM published as-is), with the real value
     * ({@code 2.5.0}) defined only in {@code root}'s parent's {@code <properties>}. {@code root}
     * declares a dependency on {@code lib} using {@code ${project.version}}, which must ultimately
     * resolve to {@code lib:2.5.0} rather than being left as a literal, unresolved placeholder (which
     * would silently drop the dependency from the resolved closure).
     */
    @Test
    void shouldResolveRevisionPlaceholderFromParentProperties() throws URISyntaxException {
        final Path localRepository = fixtureRepository("revision-repo");

        final PomResolver resolver = new PomResolver(
            new TelemetryPublisher(URI.create("maven://revision-test"), System.out::println),
            localRepository,
            true, // offline: everything must be satisfied from the fixture repo
            List.of());

        final List<Path> resolved = resolver.resolveTransitive("test:root:${revision}");

        assertThat(resolved)
            .extracting(Path::getFileName)
            .extracting(Object::toString)
            .containsExactlyInAnyOrder("root-${revision}.jar", "lib-2.5.0.jar");
    }

    /**
     * A fixture, fixed as an offline local repository under
     * {@code src/test/resources/dep-management-repo}: {@code child}'s parent {@code parent:1.0}
     * manages {@code test:lib} at {@code 1.0}, but {@code child} declares its own
     * {@code <dependencyManagement>} entry for {@code test:lib} at {@code 2.0} and depends on
     * {@code lib} with no explicit version. Maven's inheritance rules require a project's own
     * {@code <dependencyManagement>} to take precedence over anything inherited from a parent, so
     * the managed version applied to {@code lib} must be {@code 2.0}, not the parent's {@code 1.0}.
     */
    @Test
    void shouldPreferChildsOwnDependencyManagementOverParents() throws URISyntaxException {
        final Path localRepository = fixtureRepository("dep-management-repo");

        final PomResolver resolver = new PomResolver(
            new TelemetryPublisher(URI.create("maven://dep-management-test"), System.out::println),
            localRepository,
            true, // offline: everything must be satisfied from the fixture repo
            List.of());

        final List<Path> resolved = resolver.resolveTransitive("test:child:1.0");

        assertThat(resolved)
            .extracting(Path::getFileName)
            .extracting(Object::toString)
            .containsExactlyInAnyOrder("child-1.0.jar", "lib-2.0.jar");
    }

    /**
     * A fixture, fixed as an offline local repository under {@code src/test/resources/packaging-repo}:
     * {@code root:1.0} is packaged as a {@code war} (not the default {@code jar}) and declares one
     * ordinary {@code jar}-typed dependency on {@code test:lib:1.0}.
     * <p>
     * {@link PomResolver#resolveTransitive(String)} must download the root artifact using its own
     * requested extension ({@code war}) rather than unconditionally assuming {@code jar} — the
     * fixture's local repository only contains a {@code root-1.0.war} file, not a
     * {@code root-1.0.jar}, so resolving with the wrong extension fails to find the root artifact.
     * Transitive dependencies are unaffected: {@code lib} is still resolved as a {@code jar}, since
     * that's what its own {@code <dependency>} entry (implicitly) declares.
     */
    @Test
    void shouldResolveRootArtifactUsingItsOwnPackagingExtension() throws URISyntaxException {
        final Path localRepository = fixtureRepository("packaging-repo");

        final PomResolver resolver = new PomResolver(
            new TelemetryPublisher(URI.create("maven://packaging-test"), System.out::println),
            localRepository,
            true, // offline: everything must be satisfied from the fixture repo
            List.of());

        final List<Path> resolved = resolver.resolveTransitive("test:root:war:1.0");

        assertThat(resolved)
            .extracting(Path::getFileName)
            .extracting(Object::toString)
            .containsExactlyInAnyOrder("root-1.0.war", "lib-1.0.jar");
    }

    /**
     * A SNAPSHOT artifact placed in the local repository by a plain {@code mvn install} (i.e. with no
     * spin-specific bookkeeping alongside it) must be trusted as up to date as long as its own file
     * mtime is within the snapshot update-policy TTL. This is the regression case for the bug where
     * spin unconditionally re-fetched (and clobbered) any snapshot it hadn't itself downloaded,
     * because freshness was tracked in a separate {@code .spin-lastUpdated-*} marker file that only
     * spin's own downloader ever wrote.
     */
    @Test
    void shouldTreatFreshLocallyInstalledSnapshotAsUpToDateWithoutSpinsOwnMarker(
        @org.junit.jupiter.api.io.TempDir final Path tempDir) throws IOException {
        final Path target = tempDir.resolve("test/artifact/1.0-SNAPSHOT/artifact-1.0-SNAPSHOT.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "locally-installed-bytes");

        try (SnapshotTestRepoServer server =
                 new SnapshotTestRepoServer("20260729.120000", "1", "remote-bytes".getBytes(StandardCharsets.UTF_8))) {
            final PomResolver resolver = new PomResolver(
                new TelemetryPublisher(URI.create("maven://snapshot-freshness-test"), System.out::println),
                tempDir,
                false,
                List.of(RemoteRepo.of("test", "http://localhost:" + server.port())));

            final Optional<Path> resolved = resolver.resolveArtifact("test:artifact:1.0-SNAPSHOT");

            assertThat(resolved).contains(target);
            assertThat(Files.readString(target)).isEqualTo("locally-installed-bytes");
            assertThat(server.requestCount()).isZero();
        }
    }

    /**
     * Conversely, a SNAPSHOT whose local copy has genuinely aged past the update-policy TTL must
     * still be re-fetched from the remote repository — the fix for the marker-file bug must not
     * regress into never re-checking snapshots at all.
     */
    @Test
    void shouldRefetchSnapshotOnceLocalCopyAgesPastTheUpdatePolicyTtl(
        @org.junit.jupiter.api.io.TempDir final Path tempDir) throws IOException {
        final Path target = tempDir.resolve("test/artifact/1.0-SNAPSHOT/artifact-1.0-SNAPSHOT.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "stale-local-bytes");
        Files.setLastModifiedTime(target, FileTime.from(Instant.now().minus(Duration.ofDays(2))));

        try (SnapshotTestRepoServer server = new SnapshotTestRepoServer(
                "20260729.120000", "1", "fresh-remote-bytes".getBytes(StandardCharsets.UTF_8))) {
            final PomResolver resolver = new PomResolver(
                new TelemetryPublisher(URI.create("maven://snapshot-staleness-test"), System.out::println),
                tempDir,
                false,
                List.of(RemoteRepo.of("test", "http://localhost:" + server.port())));

            final Optional<Path> resolved = resolver.resolveArtifact("test:artifact:1.0-SNAPSHOT");

            assertThat(resolved).contains(target);
            assertThat(Files.readString(target)).isEqualTo("fresh-remote-bytes");
            assertThat(server.requestCount()).isGreaterThan(0);
        }
    }

    /**
     * A checksum sidecar request that fails for a reason other than "not found" (here, an HTTP 500)
     * must reject the download rather than silently trusting the unverified file — unlike a
     * {@code 404}, which legitimately means the repository never published a checksum for this
     * artifact. With no other repository configured, the failed verification must surface as an
     * overall resolution failure, and the unverified artifact bytes must not be left behind in the
     * local repository.
     */
    @Test
    void shouldRejectDownloadWhenChecksumSidecarRequestFails(@org.junit.jupiter.api.io.TempDir final Path tempDir)
        throws IOException {
        try (TestRepoServer server = new TestRepoServer()) {
            final PomResolver resolver = new PomResolver(
                new TelemetryPublisher(URI.create("maven://checksum-test"), System.out::println),
                tempDir,
                false,
                List.of(RemoteRepo.of("test", "http://localhost:" + server.port())));

            final Optional<Path> resolved = resolver.resolveArtifact("test:artifact:1.0");

            assertThat(resolved).isEmpty();
            assertThat(tempDir.resolve("test/artifact/1.0/artifact-1.0.jar")).doesNotExist();
        }
    }

    /**
     * A minimal single-purpose HTTP/1.1 server (raw sockets, no JDK {@code jdk.httpserver} module
     * dependency) that serves a fixed {@code artifact-1.0.jar} body for any non-{@code .sha1}
     * request, and a {@code 500} for any {@code .sha1} request — enough to exercise checksum-sidecar
     * failure handling without a real repository.
     */
    private static final class TestRepoServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final Thread thread;
        private volatile boolean running = true;

        TestRepoServer() throws IOException {
            this.serverSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
            this.thread = new Thread(this::serve);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return this.serverSocket.getLocalPort();
        }

        private void serve() {
            while (this.running) {
                try (Socket socket = this.serverSocket.accept()) {
                    handle(socket);
                } catch (final IOException e) {
                    // expected once close() closes the server socket to unblock accept()
                }
            }
        }

        private static void handle(final Socket socket) throws IOException {
            final BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            final String requestLine = in.readLine();
            String header;
            while ((header = in.readLine()) != null && !header.isEmpty()) {
                // discard request headers
            }
            final String path = requestLine.split(" ")[1];
            final OutputStream out = socket.getOutputStream();
            if (path.endsWith(".sha1")) {
                out.write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            } else {
                final byte[] body = "jar-bytes".getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
                out.write(body);
            }
            out.flush();
        }

        @Override
        public void close() {
            this.running = false;
            try {
                this.serverSocket.close();
            } catch (final IOException ignored) {
                // best-effort shutdown
            }
        }
    }

    /**
     * A minimal single-purpose HTTP/1.1 server modeling a SNAPSHOT-enabled Maven repository: serves
     * a synthetic {@code maven-metadata.xml} (with the given {@code timestamp}/{@code buildNumber})
     * for any request ending in it, the given artifact bytes for any {@code .jar} request, and a
     * {@code 404} for any {@code .sha1} request (i.e. no checksum sidecar published, which
     * {@link PomResolver#verifySha1} treats as "accept unverified"). Counts every request received so
     * tests can assert whether the resolver contacted the network at all.
     */
    private static final class SnapshotTestRepoServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final Thread thread;
        private final AtomicInteger requestCount = new AtomicInteger();
        private final String metadataXml;
        private final byte[] artifactBytes;
        private volatile boolean running = true;

        SnapshotTestRepoServer(final String timestamp, final String buildNumber, final byte[] artifactBytes)
            throws IOException {
            this.serverSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
            this.metadataXml = "<metadata><versioning><snapshot><timestamp>" + timestamp
                + "</timestamp><buildNumber>" + buildNumber + "</buildNumber></snapshot></versioning></metadata>";
            this.artifactBytes = artifactBytes;
            this.thread = new Thread(this::serve);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return this.serverSocket.getLocalPort();
        }

        int requestCount() {
            return this.requestCount.get();
        }

        private void serve() {
            while (this.running) {
                try (Socket socket = this.serverSocket.accept()) {
                    handle(socket);
                } catch (final IOException e) {
                    // expected once close() closes the server socket to unblock accept()
                }
            }
        }

        private void handle(final Socket socket) throws IOException {
            this.requestCount.incrementAndGet();
            final BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            final String requestLine = in.readLine();
            String header;
            while ((header = in.readLine()) != null && !header.isEmpty()) {
                // discard request headers
            }
            final String path = requestLine.split(" ")[1];
            final OutputStream out = socket.getOutputStream();
            if (path.endsWith(".sha1")) {
                respond(out, 404, new byte[0]);
            } else if (path.endsWith("maven-metadata.xml")) {
                respond(out, 200, this.metadataXml.getBytes(StandardCharsets.UTF_8));
            } else if (path.endsWith(".jar")) {
                respond(out, 200, this.artifactBytes);
            } else {
                respond(out, 404, new byte[0]);
            }
        }

        private static void respond(final OutputStream out, final int statusCode, final byte[] body)
            throws IOException {
            final String statusLine = statusCode == 200 ? "200 OK" : "404 Not Found";
            out.write(("HTTP/1.1 " + statusLine + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        }

        @Override
        public void close() {
            this.running = false;
            try {
                this.serverSocket.close();
            } catch (final IOException ignored) {
                // best-effort shutdown
            }
        }
    }

    private static Path fixtureRepository() throws URISyntaxException {
        return fixtureRepository("nearest-wins-repo");
    }

    private static Path fixtureRepository(final String name) throws URISyntaxException {
        final URI uri = PomResolverTests.class.getClassLoader()
            .getResource(name)
            .toURI();
        return Path.of(uri);
    }
}
