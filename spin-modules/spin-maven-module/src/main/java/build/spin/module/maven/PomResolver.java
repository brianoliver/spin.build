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

import build.base.telemetry.TelemetryRecorder;
import build.spin.module.modulesystem.pom.Dependency;
import build.spin.module.modulesystem.pom.GA;
import build.spin.module.modulesystem.pom.Gav;
import build.spin.module.modulesystem.pom.Pom;
import build.spin.module.modulesystem.pom.PomReader;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A pure-JDK Maven artifact resolver: checks the local repository first, then downloads from
 * configured remote repositories via {@link HttpClient}.
 * <p>
 * All XML parsing is delegated to dedicated readers: pom.xml effective-model merging (parent
 * chains, property interpolation, BOM imports, profile activation, dependencyManagement) to
 * {@link PomReader}, supplied a {@link PomReader.PomLocator} backed by this class's download
 * machinery so parent and BOM-import POMs are fetched on demand; {@code ~/.m2/settings.xml}
 * (repositories, credentials, mirrors) to {@link MavenSettingsReader}; and SNAPSHOT
 * {@code maven-metadata.xml} to {@link SnapshotMetadataReader}. This class owns only what's
 * unique to resolving artifacts from a repository: HTTP downloads, SNAPSHOT update-policy
 * handling, SHA-1 verification, and the transitive dependency-graph walk (nearest-wins version
 * mediation, exclusion enforcement).
 */
class PomResolver {

    /**
     * A resolvable Maven artifact: a {@link Gav} plus the packaging {@code extension} (e.g.
     * {@code jar}, {@code pom}) and optional {@code classifier} that select which file of that
     * artifact is wanted.
     */
    record Coordinates(Gav gav, String extension, Optional<String> classifier) {

        static Coordinates of(final String groupId, final String artifactId, final String version,
                              final String extension, final String classifier) {
            return new Coordinates(new Gav(groupId, artifactId, version), extension, Optional.ofNullable(classifier));
        }
    }

    private final TelemetryRecorder recorder;
    private final Path localRepository;
    private final boolean offline;
    private final List<RemoteRepo> remoteRepos;
    private final HttpClient httpClient;
    private final PomReader pomReader;

    private static final long SNAPSHOT_TTL_MS = 86_400_000L; // daily

    private final ConcurrentHashMap<String, Optional<Path>> downloadCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Dependency>> descriptorCache = new ConcurrentHashMap<>();

    PomResolver(final TelemetryRecorder recorder,
                final Path localRepository,
                final boolean offline,
                final List<RemoteRepo> remoteRepos) {
        this(recorder, localRepository, offline, remoteRepos, Optional.empty());
    }

    PomResolver(final TelemetryRecorder recorder,
                final Path localRepository,
                final boolean offline,
                final List<RemoteRepo> remoteRepos,
                final Optional<MavenProxy> proxy) {
        this.recorder = recorder;
        this.localRepository = localRepository;
        this.offline = offline;
        this.remoteRepos = remoteRepos;
        this.httpClient = buildHttpClient(proxy);
        // repository-only reader: no <relativePath> reactor to walk, and both parent and
        // BOM-import lookups go through downloadIfNeeded so missing POMs are fetched on demand
        this.pomReader = new PomReader(localRepository, recorder, false,
            (groupId, artifactId, version) ->
                downloadIfNeeded(new Coordinates(new Gav(groupId, artifactId, version), "pom", Optional.empty())));
    }

    static PomResolver fromSettings(final TelemetryRecorder recorder, final boolean offline) {
        final Path home = Path.of(System.getProperty("user.home"));
        final Path localRepo = home.resolve(".m2/repository");
        final Path settingsPath = home.resolve(".m2/settings.xml");

        final List<RemoteRepo> repos = Files.exists(settingsPath)
            ? new ArrayList<>(MavenSettingsReader.read(settingsPath, recorder))
            : new ArrayList<>();
        if (repos.isEmpty()) {
            repos.add(RemoteRepo.of("central", "https://repo.maven.apache.org/maven2"));
        }
        final Optional<MavenProxy> proxy = Files.exists(settingsPath)
            ? MavenSettingsReader.readProxy(settingsPath, recorder)
            : Optional.empty();

        return new PomResolver(recorder, localRepo, offline, repos, proxy);
    }

    /**
     * Builds the {@link HttpClient} used for all repository requests, routing through the given
     * {@code <proxy>} (if any and if the target host isn't covered by its {@code <nonProxyHosts>})
     * and supplying proxy credentials via an {@link Authenticator} when configured.
     */
    private static HttpClient buildHttpClient(final Optional<MavenProxy> proxy) {
        final HttpClient.Builder builder = HttpClient.newBuilder();
        proxy.ifPresent(p -> {
            builder.proxy(new ProxySelector() {
                @Override
                public List<Proxy> select(final URI uri) {
                    final String host = uri.getHost();
                    if (host != null && p.bypasses(host)) {
                        return List.of(Proxy.NO_PROXY);
                    }
                    return List.of(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(p.host(), p.port())));
                }

                @Override
                public void connectFailed(final URI uri, final java.net.SocketAddress sa, final IOException ioe) {
                    // nothing to record here; the failed connection attempt surfaces to the caller
                }
            });
            if (p.username().isPresent()) {
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        if (getRequestorType() == RequestorType.PROXY) {
                            return new PasswordAuthentication(p.username().get(),
                                p.password().map(String::toCharArray).orElseGet(() -> new char[0]));
                        }
                        return null;
                    }
                });
            }
        });
        return builder.build();
    }

    /**
     * Resolves a single artifact to its local {@code .m2} path, downloading if necessary.
     *
     * @param coordinates {@code groupId:artifactId[:extension[:classifier]]:version}
     * @return the local path, or empty if resolution fails
     */
    Optional<Path> resolveArtifact(final String coordinates) {
        final Coordinates parsed = parseCoordinates(coordinates);
        if (parsed == null) {
            this.recorder.error("Invalid Maven coordinates: %s", coordinates);
            return Optional.empty();
        }
        return resolveArtifact(parsed);
    }

    /**
     * Resolves a single artifact to its local {@code .m2} path, downloading if necessary. For
     * callers that already have structured coordinates rather than a formatted coordinate string.
     */
    Optional<Path> resolveArtifact(final Coordinates coordinates) {
        return downloadIfNeeded(coordinates);
    }

    /**
     * Resolves the effective direct dependencies declared by the POM at the given coordinates,
     * after merging parent chains, properties, and BOM imports.
     *
     * @param coordinates {@code groupId:artifactId[:extension[:classifier]]:version}
     * @return the effective direct {@link Dependency} list, or empty list on failure
     */
    List<Dependency> resolveDescriptor(final String coordinates) {
        final Coordinates parsed = parseCoordinates(coordinates);
        if (parsed == null) {
            this.recorder.error("Invalid Maven coordinates: %s", coordinates);
            return List.of();
        }
        return resolveDescriptor(parsed.gav());
    }

    /**
     * Resolves the effective direct dependencies declared by the POM at the given coordinate, for
     * callers that already have a {@link Gav} rather than a formatted coordinate string.
     */
    List<Dependency> resolveDescriptor(final Gav gav) {
        final String cacheKey = gav.groupId() + ":" + gav.artifactId() + ":" + gav.version();
        final List<Dependency> cached = this.descriptorCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        final List<Dependency> result = effectiveDependencies(gav);
        this.descriptorCache.putIfAbsent(cacheKey, result);
        return result;
    }

    /**
     * Downloads the POM (if needed) and returns its effective direct dependencies via
     * {@link PomReader}.
     */
    private List<Dependency> effectiveDependencies(final Gav gav) {
        final Optional<Path> pomPath = downloadIfNeeded(new Coordinates(gav, "pom", Optional.empty()));
        if (pomPath.isEmpty()) {
            return List.of();
        }
        return this.pomReader.read(pomPath.get())
            .map(Pom::dependencies)
            .orElse(List.of());
    }

    private record TransitiveNode(Gav gav, int depth, Set<String> excluded) {
        GA ga() {
            return this.gav.ga();
        }
    }

    /**
     * Resolves all transitive compile/runtime dependencies and returns their local paths.
     * <p>
     * Version conflicts are resolved by Maven's nearest-wins rule: when the same {@code groupId:artifactId}
     * is reachable at multiple depths, the version declared at the shallowest depth wins; ties at equal
     * depth are broken by which occurrence is encountered first. The graph is walked breadth-first so the
     * winner for each artifact is known before any of its jars are downloaded.
     * <p>
     * {@code <exclusions>} declared on a dependency edge apply to that dependency's own transitive
     * closure: they're accumulated and inherited down the subtree rooted at that dependency, so a
     * {@code groupId:artifactId} excluded higher up never reaches the queue via that branch, regardless
     * of how many levels deeper it's actually declared. A sibling branch without the exclusion still
     * pulls it in normally.
     *
     * @param coordinates {@code groupId:artifactId[:extension[:classifier]]:version}
     * @return list of local paths for the root artifact and all transitive deps
     */
    List<Path> resolveTransitive(final String coordinates) {
        final Coordinates parsed = parseCoordinates(coordinates);
        if (parsed == null) {
            this.recorder.error("Invalid Maven coordinates: %s", coordinates);
            return List.of();
        }
        return resolveTransitive(parsed.gav(), parsed.extension());
    }

    /**
     * Resolves all transitive compile/runtime dependencies and returns their local paths, for
     * callers that already have a {@link Gav} rather than a formatted coordinate string. See
     * {@link #resolveTransitive(String)} for the version-mediation and exclusion-enforcement rules.
     *
     * @param rootExtension the packaging extension (e.g. {@code jar}, {@code war}) of the root
     *                      artifact itself; every transitive dependency is downloaded as a
     *                      {@code jar} regardless (non-jar-typed dependencies are never queued —
     *                      see the {@code "jar".equals(dep.type())} filter below), since only the
     *                      root artifact's own packaging is caller-supplied rather than declared
     *                      by a {@code <dependency>} entry
     */
    List<Path> resolveTransitive(final Gav gav, final String rootExtension) {
        return resolveTransitive(gav, rootExtension, Set.of());
    }

    /**
     * As {@link #resolveTransitive(Gav, String)}, but seeds the BFS with a set of
     * {@code "groupId:artifactId"} exclusion patterns (either side may be {@code *}) already applied
     * to the root — the {@code <exclusions>} a consuming pom declared on this dependency edge, which
     * are otherwise unknown once the edge has been reduced to a bare coordinate. They inherit down
     * the whole subtree rooted here, exactly like an {@code <exclusion>} declared inside the walk.
     *
     * @param rootExclusions the {@code "groupId:artifactId"} exclusion patterns to apply to the root
     */
    List<Path> resolveTransitive(final Gav gav, final String rootExtension, final Set<String> rootExclusions) {
        // Phase 1: BFS the dependency graph and record the winning version for each groupId:artifactId.
        // Processing is level-by-level, so the first occurrence of a GA to be dequeued is guaranteed to be
        // its shallowest; deeper occurrences of an already-won GA are skipped and their own subtrees are
        // never explored, matching Maven's mediation semantics.
        final Map<GA, String> winningVersion = new LinkedHashMap<>();
        final Set<GA> visited = new HashSet<>();
        final ArrayDeque<TransitiveNode> queue = new ArrayDeque<>();
        queue.add(new TransitiveNode(gav, 0, Set.copyOf(rootExclusions)));

        while (!queue.isEmpty()) {
            final TransitiveNode node = queue.poll();
            final GA ga = node.ga();
            if (!visited.add(ga)) {
                continue;
            }
            winningVersion.put(ga, node.gav().version());

            try {
                final List<Dependency> deps = effectiveDependencies(node.gav());
                for (final Dependency dep : deps) {
                    final String depScope = dep.scope();
                    if (dep.optional()
                        || !"jar".equals(dep.type())
                        || ("test".equals(depScope) || "system".equals(depScope))) {
                        continue;
                    }
                    if (dep.version().isEmpty()
                        || visited.contains(dep.ga())
                        || isExcluded(dep.groupId(), dep.artifactId(), node.excluded())) {
                        continue;
                    }
                    final Set<String> childExcluded;
                    if (dep.exclusions().isEmpty()) {
                        childExcluded = node.excluded();
                    } else {
                        childExcluded = new HashSet<>(node.excluded());
                        childExcluded.addAll(dep.exclusions());
                    }
                    queue.add(new TransitiveNode(
                        new Gav(dep.groupId(), dep.artifactId(), dep.version().get()),
                        node.depth() + 1, childExcluded));
                }
            } catch (final Exception e) {
                this.recorder.warn(e, "Failed to resolve transitive deps for %s:%s:%s",
                    node.gav().groupId(), node.gav().artifactId(), node.gav().version());
            }
        }

        // Phase 2: download the artifact for each winning GA:version. Every entry other than the
        // root is a "jar"-typed transitive dependency (enforced by the queueing filter above); the
        // root keeps whatever packaging extension the caller asked to resolve (e.g. "war"), so it
        // isn't incorrectly requested as a jar when its actual packaging differs.
        final GA rootGa = gav.ga();
        final List<Path> result = new ArrayList<>();
        for (final Map.Entry<GA, String> entry : winningVersion.entrySet()) {
            final GA ga = entry.getKey();
            final String extension = ga.equals(rootGa) ? rootExtension : "jar";
            downloadIfNeeded(new Coordinates(new Gav(ga.groupId(), ga.artifactId(), entry.getValue()),
                extension, Optional.empty()))
                .ifPresent(result::add);
        }

        return result;
    }

    /**
     * Matches {@code groupId:artifactId} against a set of {@code <exclusion>} patterns, each side of
     * which may be the Maven wildcard {@code *}.
     */
    private static boolean isExcluded(final String groupId, final String artifactId, final Set<String> patterns) {
        if (patterns.isEmpty()) {
            return false;
        }
        for (final String pattern : patterns) {
            final int colon = pattern.indexOf(':');
            if (colon < 0) {
                continue;
            }
            final String patternGroupId = pattern.substring(0, colon);
            final String patternArtifactId = pattern.substring(colon + 1);
            if (("*".equals(patternGroupId) || patternGroupId.equals(groupId))
                && ("*".equals(patternArtifactId) || patternArtifactId.equals(artifactId))) {
                return true;
            }
        }
        return false;
    }

    private Optional<Path> downloadIfNeeded(final Coordinates coords) {
        final Gav gav = coords.gav();
        final String key = gav.groupId() + ":" + gav.artifactId() + ":" + gav.version() + ":" + coords.extension()
            + coords.classifier().map(c -> ":" + c).orElse("");

        return this.downloadCache.computeIfAbsent(key, __ -> {
            final Path local = localPath(coords);
            if (gav.version().endsWith("-SNAPSHOT")) {
                return resolveSnapshot(coords, local);
            }
            if (Files.exists(local)) {
                return Optional.of(local);
            }
            if (this.offline) {
                this.recorder.warn("Offline mode: cannot download %s", key);
                return Optional.empty();
            }
            return download(coords, local);
        });
    }

    private Optional<Path> resolveSnapshot(final Coordinates coords,
                                           final Path target) {
        final Gav gav = coords.gav();
        if (Files.exists(target) && isSnapshotFresh(target, effectiveSnapshotUpdateTtlMs())) {
            return Optional.of(target);
        }
        if (this.offline) {
            if (Files.exists(target)) {
                return Optional.of(target);
            }
            this.recorder.warn("Offline mode: cannot resolve snapshot %s:%s:%s",
                gav.groupId(), gav.artifactId(), gav.version());
            return Optional.empty();
        }
        final Optional<String> suffix = fetchSnapshotSuffix(gav);
        if (suffix.isEmpty()) {
            return Files.exists(target) ? Optional.of(target) : Optional.empty();
        }
        final String version = gav.version();
        final String baseVersion = version.substring(0, version.length() - "-SNAPSHOT".length());
        return downloadSnapshot(coords, baseVersion + "-" + suffix.get(), target);
    }

    /**
     * A snapshot is fresh if the artifact already in the local repository was last modified within
     * {@code ttlMs} — whether it was placed there by spin's own resolver or by a plain
     * {@code mvn install}, since either way the file's mtime reflects when it was actually produced.
     */
    private boolean isSnapshotFresh(final Path target, final long ttlMs) {
        try {
            return (System.currentTimeMillis() - Files.getLastModifiedTime(target).toMillis()) < ttlMs;
        } catch (final IOException e) {
            return false;
        }
    }

    /**
     * The snapshot freshness window to apply, derived from configured repositories'
     * {@code <snapshots><updatePolicy>}. Different repositories may declare different policies and
     * we don't yet know which one will actually serve the artifact (that's decided by
     * {@link #fetchSnapshotSuffix}'s per-repo iteration), so the strictest (shortest) TTL among all
     * configured repos is used: if any configured repo wants a re-check, we re-check. A repo with no
     * explicit policy falls back to the default daily TTL, matching prior behavior.
     */
    long effectiveSnapshotUpdateTtlMs() {
        if (this.remoteRepos.isEmpty()) {
            return SNAPSHOT_TTL_MS;
        }
        long ttlMs = Long.MAX_VALUE;
        for (final RemoteRepo repo : this.remoteRepos) {
            ttlMs = Math.min(ttlMs, updatePolicyTtlMs(repo.snapshotUpdatePolicy()));
        }
        return ttlMs;
    }

    private static long updatePolicyTtlMs(final Optional<String> updatePolicy) {
        if (updatePolicy.isEmpty()) {
            return SNAPSHOT_TTL_MS;
        }
        final String policy = updatePolicy.get();
        if ("always".equals(policy)) {
            return 0L;
        }
        if ("never".equals(policy)) {
            return Long.MAX_VALUE;
        }
        if (policy.startsWith("interval:")) {
            try {
                final long minutes = Long.parseLong(policy.substring("interval:".length()).trim());
                return minutes * 60_000L;
            } catch (final NumberFormatException e) {
                return SNAPSHOT_TTL_MS;
            }
        }
        // "daily" or unrecognized: fall back to the default daily TTL
        return SNAPSHOT_TTL_MS;
    }

    private Optional<String> fetchSnapshotSuffix(final Gav gav) {
        final String groupPath = gav.groupId().replace('.', '/');
        final String metaRelPath = groupPath + "/" + gav.artifactId() + "/" + gav.version() + "/maven-metadata.xml";
        for (final RemoteRepo repo : this.remoteRepos) {
            final String metaUrl = repo.url().endsWith("/")
                ? repo.url() + metaRelPath
                : repo.url() + "/" + metaRelPath;
            try {
                final HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(metaUrl))
                    .GET();
                repo.authHeader().ifPresent(h -> rb.header("Authorization", h));
                final HttpResponse<InputStream> response = this.httpClient.send(
                    rb.build(), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 200) {
                    try (InputStream body = response.body()) {
                        final Optional<String> suffix = SnapshotMetadataReader.parseSnapshotSuffix(body);
                        if (suffix.isPresent()) {
                            return suffix;
                        }
                    }
                }
            } catch (final IOException | InterruptedException e) {
                this.recorder.warn(e, "Failed to fetch snapshot metadata from %s", repo.url());
            }
        }
        return Optional.empty();
    }

    private Optional<Path> downloadSnapshot(final Coordinates coords,
                                            final String realVersion,
                                            final Path target) {
        final Gav gav = coords.gav();
        final String classifier = coords.classifier().orElse(null);
        final String filename = gav.artifactId() + "-" + realVersion
            + (classifier != null && !classifier.isEmpty() ? "-" + classifier : "")
            + "." + coords.extension();
        final String groupPath = gav.groupId().replace('.', '/');
        final String relativePath = groupPath + "/" + gav.artifactId() + "/" + gav.version() + "/" + filename;
        for (final RemoteRepo repo : this.remoteRepos) {
            final String url = repo.url().endsWith("/")
                ? repo.url() + relativePath
                : repo.url() + "/" + relativePath;
            try {
                final HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET();
                repo.authHeader().ifPresent(h -> rb.header("Authorization", h));
                final HttpResponse<InputStream> response = this.httpClient.send(
                    rb.build(), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 200) {
                    Files.createDirectories(target.getParent());
                    try (InputStream body = response.body()) {
                        Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (!verifySha1(url, target, repo)) {
                        Files.deleteIfExists(target);
                        continue;
                    }
                    this.recorder.info("Downloaded snapshot %s from %s", filename, repo.id());
                    return Optional.of(target);
                } else if (response.statusCode() != 404) {
                    this.recorder.warn("HTTP %d fetching snapshot %s from %s",
                        response.statusCode(), filename, repo.id());
                }
            } catch (final IOException | InterruptedException e) {
                this.recorder.warn(e, "Failed to download snapshot %s from %s", filename, repo.url());
            }
        }
        return Optional.empty();
    }

    private Optional<Path> download(final Coordinates coords,
                                    final Path target) {
        final Gav gav = coords.gav();
        final String classifier = coords.classifier().orElse(null);
        final String filename = gav.artifactId() + "-" + gav.version()
            + (classifier != null && !classifier.isEmpty() ? "-" + classifier : "")
            + "." + coords.extension();

        final String groupPath = gav.groupId().replace('.', '/');
        final String relativePath = groupPath + "/" + gav.artifactId() + "/" + gav.version() + "/" + filename;

        for (final RemoteRepo repo : this.remoteRepos) {
            final String url = repo.url().endsWith("/")
                ? repo.url() + relativePath
                : repo.url() + "/" + relativePath;

            try {
                final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET();

                repo.authHeader().ifPresent(h -> requestBuilder.header("Authorization", h));

                final HttpResponse<InputStream> response = this.httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() == 200) {
                    Files.createDirectories(target.getParent());
                    try (InputStream body = response.body()) {
                        Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (!verifySha1(url, target, repo)) {
                        Files.deleteIfExists(target);
                        continue;
                    }
                    this.recorder.info("Downloaded %s from %s", filename, repo.id());
                    return Optional.of(target);
                } else if (response.statusCode() != 404) {
                    this.recorder.warn("HTTP %d fetching %s from %s", response.statusCode(), filename, repo.id());
                }
            } catch (final IOException | InterruptedException e) {
                this.recorder.warn(e, "Failed to download %s from %s", filename, repo.url());
            }
        }

        this.recorder.warn("Could not resolve %s:%s:%s:%s", gav.groupId(), gav.artifactId(), gav.version(), coords.extension());
        return Optional.empty();
    }

    /**
     * Verifies the just-downloaded {@code target} against its {@code .sha1} sidecar.
     * <p>
     * Only a {@code 404} for the sidecar itself (no checksum published for this artifact) is
     * treated as "nothing to verify, accept the download" — matching Maven's behavior for
     * repositories that don't publish checksums. Any other failure to obtain or compute a
     * checksum (a non-{@code 404} HTTP error, a network/IO error, or a digest mismatch) rejects
     * the download so the caller falls through to the next configured repository, rather than
     * silently trusting a file whose integrity couldn't actually be confirmed.
     */
    private boolean verifySha1(final String url, final Path target, final RemoteRepo repo) {
        final HttpResponse<String> sha1Response;
        try {
            final HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url + ".sha1"))
                .GET();
            repo.authHeader().ifPresent(h -> rb.header("Authorization", h));
            sha1Response = this.httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        } catch (final IOException | InterruptedException e) {
            this.recorder.warn(e, "Could not fetch checksum for %s; rejecting download", target.getFileName());
            return false;
        }
        if (sha1Response.statusCode() == 404) {
            return true; // no checksum sidecar published for this artifact; accept the download
        }
        if (sha1Response.statusCode() != 200) {
            this.recorder.warn("HTTP %d fetching checksum for %s; rejecting download",
                sha1Response.statusCode(), target.getFileName());
            return false;
        }
        try {
            final String expectedHex = sha1Response.body().trim().split("\\s+")[0];
            final MessageDigest md = MessageDigest.getInstance("SHA-1");
            final String actualHex = HexFormat.of().formatHex(md.digest(Files.readAllBytes(target)));
            if (!actualHex.equalsIgnoreCase(expectedHex)) {
                this.recorder.warn("SHA-1 mismatch for %s (expected %s, got %s); trying next repo",
                    target.getFileName(), expectedHex, actualHex);
                return false;
            }
            return true;
        } catch (final IOException | NoSuchAlgorithmException e) {
            this.recorder.warn(e, "Could not verify checksum for %s; rejecting download", target.getFileName());
            return false;
        }
    }

    private Path localPath(final Coordinates coords) {
        final Gav gav = coords.gav();
        final String classifier = coords.classifier().orElse(null);
        final String groupPath = gav.groupId().replace('.', '/');
        final String filename = gav.artifactId() + "-" + gav.version()
            + (classifier != null && !classifier.isEmpty() ? "-" + classifier : "")
            + "." + coords.extension();
        return this.localRepository.resolve(groupPath).resolve(gav.artifactId()).resolve(gav.version()).resolve(filename);
    }

    /**
     * Parses Maven coordinates: {@code groupId:artifactId[:extension[:classifier]]:version}.
     *
     * @return the parsed {@link Coordinates}, or null on parse failure
     */
    static Coordinates parseCoordinates(final String coordinates) {
        if (coordinates == null || coordinates.isEmpty()) {
            return null;
        }
        final String[] parts = coordinates.split(":");
        return switch (parts.length) {
            case 3 -> Coordinates.of(parts[0], parts[1], parts[2], "jar", null);
            case 4 -> Coordinates.of(parts[0], parts[1], parts[3], parts[2], null);
            case 5 -> Coordinates.of(parts[0], parts[1], parts[4], parts[2], parts[3]);
            default -> null;
        };
    }

}
