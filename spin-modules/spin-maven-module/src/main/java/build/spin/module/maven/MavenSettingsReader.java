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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads {@code ~/.m2/settings.xml}: active profiles' {@code <repositories>} (including each
 * repository's {@code <snapshots><updatePolicy>}), {@code <server>} credentials, and
 * {@code <mirror>} substitution.
 */
final class MavenSettingsReader {

    private MavenSettingsReader() {
    }

    /**
     * Parses the given {@code settings.xml} into its effective list of {@link RemoteRepo}s: every
     * {@code <repository>} declared under an active profile, credentials attached from matching
     * {@code <server>} entries, with {@code <mirror>} substitution applied last. Returns an empty
     * list (with a warning logged) if the file cannot be parsed.
     */
    static List<RemoteRepo> read(final Path settingsPath, final TelemetryRecorder recorder) {
        final List<RemoteRepo> repos = new ArrayList<>();
        final Path settingsSecurityPath = settingsPath.resolveSibling("settings-security.xml");
        try {
            final XMLInputFactory factory = XMLInputFactory.newInstance();
            factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

            final Map<String, String[]> serverAuth = new HashMap<>();
            final Map<String, List<String[]>> profileRepos = new HashMap<>();
            final List<String> activeProfiles = new ArrayList<>();
            final Set<String> defaultActiveProfiles = new HashSet<>();
            final List<String[]> mirrors = new ArrayList<>();

            try (InputStream in = Files.newInputStream(settingsPath)) {
                final XMLStreamReader r = factory.createXMLStreamReader(in);

                String context = "";
                String profileId = null;
                boolean inActivation = false;
                boolean activeByDefault = false;
                String repoId = null;
                String repoUrl = null;
                String repoSnapshotUpdatePolicy = null;
                String repoReleaseUpdatePolicy = null;
                boolean inRepoSnapshots = false;
                boolean inRepoReleases = false;
                String serverId = null;
                String serverUser = null;
                String serverPass = null;
                String mirrorId = null;
                String mirrorUrl = null;
                String mirrorOf = null;

                while (r.hasNext()) {
                    final int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        final String name = r.getLocalName();
                        switch (name) {
                            case "profile" -> context = "profile";
                            case "server" -> context = "server";
                            case "mirror" -> context = "mirror";
                            case "repository" -> context = "repository";
                            case "activeProfile" -> activeProfiles.add(r.getElementText());
                            case "activation" -> {
                                if ("profile".equals(context)) {
                                    inActivation = true;
                                }
                            }
                            case "activeByDefault" -> {
                                if (inActivation) {
                                    activeByDefault = Boolean.parseBoolean(r.getElementText());
                                }
                            }
                            case "mirrorOf" -> mirrorOf = r.getElementText();
                            case "snapshots" -> {
                                if ("repository".equals(context)) {
                                    inRepoSnapshots = true;
                                }
                            }
                            case "releases" -> {
                                if ("repository".equals(context)) {
                                    inRepoReleases = true;
                                }
                            }
                            case "updatePolicy" -> {
                                if (inRepoSnapshots) {
                                    repoSnapshotUpdatePolicy = r.getElementText();
                                } else if (inRepoReleases) {
                                    repoReleaseUpdatePolicy = r.getElementText();
                                }
                            }
                            case "id" -> {
                                final String val = r.getElementText();
                                if ("profile".equals(context)) {
                                    profileId = val;
                                } else if ("server".equals(context)) {
                                    serverId = val;
                                } else if ("repository".equals(context)) {
                                    repoId = val;
                                } else if ("mirror".equals(context)) {
                                    mirrorId = val;
                                }
                            }
                            case "url" -> {
                                if ("repository".equals(context)) {
                                    repoUrl = r.getElementText();
                                } else if ("mirror".equals(context)) {
                                    mirrorUrl = r.getElementText();
                                }
                            }
                            case "username" -> {
                                if ("server".equals(context)) {
                                    serverUser = r.getElementText();
                                }
                            }
                            case "password" -> {
                                if ("server".equals(context)) {
                                    serverPass = r.getElementText();
                                }
                            }
                            default -> { /* ignore */ }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        final String name = r.getLocalName();
                        switch (name) {
                            case "snapshots" -> inRepoSnapshots = false;
                            case "releases" -> inRepoReleases = false;
                            case "activation" -> inActivation = false;
                            case "repository" -> {
                                if (repoId != null && repoUrl != null && profileId != null) {
                                    profileRepos.computeIfAbsent(profileId, __ -> new ArrayList<>())
                                        .add(new String[]{repoId, repoUrl, repoSnapshotUpdatePolicy, repoReleaseUpdatePolicy});
                                }
                                repoId = null;
                                repoUrl = null;
                                repoSnapshotUpdatePolicy = null;
                                repoReleaseUpdatePolicy = null;
                                context = "profile";
                            }
                            case "profile" -> {
                                if (activeByDefault && profileId != null) {
                                    defaultActiveProfiles.add(profileId);
                                }
                                profileId = null;
                                activeByDefault = false;
                                context = "";
                            }
                            case "server" -> {
                                if (serverId != null && serverUser != null) {
                                    final String resolvedPass = MavenPasswordCipher.resolve(
                                        serverPass != null ? serverPass : "", settingsSecurityPath, recorder);
                                    serverAuth.put(serverId, new String[]{serverUser, resolvedPass});
                                }
                                serverId = null;
                                serverUser = null;
                                serverPass = null;
                                context = "";
                            }
                            case "mirror" -> {
                                if (mirrorId != null && mirrorUrl != null && mirrorOf != null) {
                                    mirrors.add(new String[]{mirrorId, mirrorUrl, mirrorOf});
                                }
                                mirrorId = null;
                                mirrorUrl = null;
                                mirrorOf = null;
                                context = "";
                            }
                            default -> { /* ignore */ }
                        }
                    }
                }
            }

            // Explicit <activeProfiles> takes precedence; a profile's <activeByDefault>true</activeByDefault>
            // only takes effect when nothing has been activated explicitly, matching Maven's semantics that
            // default activation is suppressed once any profile is activated some other way.
            final List<String> effectiveActiveProfiles = activeProfiles.isEmpty()
                ? new ArrayList<>(defaultActiveProfiles)
                : activeProfiles;

            for (final String activeId : effectiveActiveProfiles) {
                final List<String[]> repoList = profileRepos.get(activeId);
                if (repoList == null) {
                    continue;
                }
                for (final String[] repo : repoList) {
                    final String[] auth = serverAuth.get(repo[0]);
                    if (auth != null) {
                        repos.add(RemoteRepo.of(repo[0], repo[1], repo[2], repo[3], auth[0], auth[1]));
                    } else {
                        repos.add(new RemoteRepo(repo[0], repo[1], Optional.empty(),
                            Optional.ofNullable(repo[2]), Optional.ofNullable(repo[3])));
                    }
                }
            }
            if (!mirrors.isEmpty()) {
                applyMirrors(repos, mirrors, serverAuth);
            }
        } catch (final IOException | XMLStreamException e) {
            recorder.warn(e, "Failed to parse ~/.m2/settings.xml; falling back to Maven Central");
        }
        return repos;
    }

    /**
     * Parses the first active {@code <proxy>} declared in the given {@code settings.xml}, if any.
     * Maven allows multiple {@code <proxy>} entries but only the first with {@code <active>true</active>}
     * (the default when {@code <active>} is omitted) is used.
     */
    static Optional<MavenProxy> readProxy(final Path settingsPath, final TelemetryRecorder recorder) {
        final Path settingsSecurityPath = settingsPath.resolveSibling("settings-security.xml");
        try {
            final XMLInputFactory factory = XMLInputFactory.newInstance();
            factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

            try (InputStream in = Files.newInputStream(settingsPath)) {
                final XMLStreamReader r = factory.createXMLStreamReader(in);

                boolean inProxy = false;
                boolean active = true;
                String protocol = "http";
                String host = null;
                int port = -1;
                String username = null;
                String password = null;
                String nonProxyHosts = null;

                while (r.hasNext()) {
                    final int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        final String name = r.getLocalName();
                        if ("proxy".equals(name)) {
                            inProxy = true;
                            active = true;
                            protocol = "http";
                            host = null;
                            port = -1;
                            username = null;
                            password = null;
                            nonProxyHosts = null;
                        } else if (inProxy) {
                            switch (name) {
                                case "active" -> active = Boolean.parseBoolean(r.getElementText());
                                case "protocol" -> protocol = r.getElementText();
                                case "host" -> host = r.getElementText();
                                case "port" -> port = Integer.parseInt(r.getElementText().trim());
                                case "username" -> username = r.getElementText();
                                case "password" -> password = r.getElementText();
                                case "nonProxyHosts" -> nonProxyHosts = r.getElementText();
                                default -> { /* ignore */ }
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT && "proxy".equals(r.getLocalName())) {
                        inProxy = false;
                        if (active && host != null) {
                            final String resolvedPass = username != null
                                ? MavenPasswordCipher.resolve(password != null ? password : "",
                                    settingsSecurityPath, recorder)
                                : password;
                            return Optional.of(new MavenProxy(protocol, host, port,
                                Optional.ofNullable(username), Optional.ofNullable(resolvedPass),
                                Optional.ofNullable(nonProxyHosts)));
                        }
                    }
                }
            }
        } catch (final IOException | XMLStreamException e) {
            recorder.warn(e, "Failed to parse ~/.m2/settings.xml for <proxies>; proceeding without a proxy");
        }
        return Optional.empty();
    }

    /**
     * Substitutes each mirrored {@link RemoteRepo}'s id/url/credentials for its {@code <mirror>}'s,
     * while carrying over the original repository's {@code <snapshots>}/{@code <releases>}
     * {@code <updatePolicy>} — matching Maven's own mirror semantics, where a {@code <mirror>} replaces
     * only the connection endpoint, not the policies the original {@code <repository>} declared.
     * Dropping them here would otherwise silently fall every mirrored repo back to the daily default,
     * which is the common case: most {@code settings.xml} route everything through a single mirror.
     */
    private static void applyMirrors(final List<RemoteRepo> repos,
                                     final List<String[]> mirrors,
                                     final Map<String, String[]> serverAuth) {
        final List<RemoteRepo> result = new ArrayList<>();
        final Set<String> addedMirrors = new HashSet<>();
        for (final RemoteRepo repo : repos) {
            final String[] matchingMirror = findMirror(repo, mirrors);
            if (matchingMirror != null) {
                final String mirrorId = matchingMirror[0];
                if (addedMirrors.add(mirrorId)) {
                    final String[] auth = serverAuth.get(mirrorId);
                    result.add(auth != null
                        ? RemoteRepo.of(mirrorId, matchingMirror[1], repo.snapshotUpdatePolicy().orElse(null),
                            repo.releaseUpdatePolicy().orElse(null), auth[0], auth[1])
                        : new RemoteRepo(mirrorId, matchingMirror[1], Optional.empty(),
                            repo.snapshotUpdatePolicy(), repo.releaseUpdatePolicy()));
                }
            } else {
                result.add(repo);
            }
        }
        repos.clear();
        repos.addAll(result);
    }

    private static String[] findMirror(final RemoteRepo repo, final List<String[]> mirrors) {
        for (final String[] mirror : mirrors) {
            if (mirrorMatches(repo, mirror[2])) {
                return mirror;
            }
        }
        return null;
    }

    private static boolean mirrorMatches(final RemoteRepo repo, final String mirrorOf) {
        if ("*".equals(mirrorOf)) {
            return true;
        }
        final String[] parts = mirrorOf.split(",");
        boolean matched = false;
        for (final String raw : parts) {
            final String p = raw.trim();
            if (p.startsWith("!")) {
                if (repo.id().equals(p.substring(1))) {
                    return false;
                }
            } else if ("*".equals(p)) {
                matched = true;
            } else if ("external:http:*".equals(p)) {
                if (isExternalHttp(repo.url())) {
                    matched = true;
                }
            } else if ("external:*".equals(p)) {
                if (isExternal(repo.url())) {
                    matched = true;
                }
            } else if (repo.id().equals(p)) {
                matched = true;
            }
        }
        return matched;
    }

    private static boolean isExternal(final String url) {
        return !url.startsWith("file:") && !url.contains("localhost") && !url.contains("127.0.0.1");
    }

    private static boolean isExternalHttp(final String url) {
        return url.startsWith("http://") && isExternal(url);
    }
}
