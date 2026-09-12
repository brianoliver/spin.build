package build.spin.module.modulesystem;

/*-
 * #%L
 * Spin Module System Module
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
import build.base.version.Version;
import build.base.version.VersionOrder;
import build.codemodel.foundation.CodeModel;
import build.spin.module.modulesystem.pom.Dependency;
import build.spin.module.modulesystem.pom.Pom;
import build.spin.module.modulesystem.pom.PomReader;

import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Walks a Maven workspace's {@code pom.xml} files and their transitive dependencies from the
 * local repository, invoking a {@link CoordinateVisitor} for every (module-name list, coordinate)
 * pair that can be derived from the poms.
 * <p>
 * Effective-POM computation (parent chains, {@code dependencyManagement} including BOM imports,
 * property interpolation) is delegated entirely to a single {@link PomReader} instance shared
 * across the whole walk, so each pom's own real parent chain is honored — including through
 * intermediate aggregator poms — rather than a single workspace-root fallback.
 * <p>
 * Shared by {@link PomBasedModuleCatalog} and {@link PomBasedModuleVersioning} — the only thing
 * that differs between those two is what they store per visit, which lives in the visitor.
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
final class PomDependencyGraphWalker {

    private static final String POM_FILENAME = "pom.xml";

    private PomDependencyGraphWalker() {
    }

    /**
     * Called once for each (module-name list, coordinate) pair discovered during the walk.
     * {@code moduleNames} contains every JPMS name under which the coordinate should be
     * registered: the groupId, derived names from the artifactId, and optionally the
     * {@code Automatic-Module-Name} from the jar manifest.
     */
    @FunctionalInterface
    interface CoordinateVisitor {
        void accept(List<String> moduleNames, String groupId, String artifactId, String resolvedVersion);
    }

    /**
     * Walks the workspace pom tree, then follows module-info.class requires transitively
     * through the local repository, invoking {@code visitor} for every coordinate found.
     * <p>
     * Equivalent to calling {@link #walk(Path, Path, TelemetryRecorder, CodeModel, Predicate, CoordinateVisitor)}
     * with a {@code isIgnored} predicate that never ignores anything.
     */
    static void walk(final Path workspacePath,
                     final Path localRepo,
                     final TelemetryRecorder recorder,
                     final CodeModel codeModel,
                     final CoordinateVisitor visitor) {
        walk(workspacePath, localRepo, recorder, codeModel, path -> false, visitor);
    }

    /**
     * Walks the workspace pom tree, then follows module-info.class requires transitively
     * through the local repository, invoking {@code visitor} for every coordinate found.
     * <p>
     * Directories for which {@code isIgnored} returns {@code true} (e.g. a {@code .spinignore}'d
     * {@code .worktrees} folder) are pruned from the workspace pom walk entirely, the same way
     * {@code target}/{@code .build} directories are — a workspace's own {@code .spinignore} rules
     * must not be silently bypassed by pom-based module discovery.
     */
    static void walk(final Path workspacePath,
                     final Path localRepo,
                     final TelemetryRecorder recorder,
                     final CodeModel codeModel,
                     final Predicate<Path> isIgnored,
                     final CoordinateVisitor visitor) {
        final Path rootPom = workspacePath.resolve(POM_FILENAME);
        if (!Files.exists(rootPom)) {
            return;
        }

        try {
            final PomReader pomReader = new PomReader(localRepo, recorder);

            // visited keyed by "groupId:artifactId" -> the highest version visited so far for that
            // coordinate. A later-discovered higher version re-enqueues the coordinate (see
            // enqueueIfNew) rather than being silently dropped by a plain visited-set gate.
            final Map<String, String> visited = new HashMap<>();

            // Phase 1: walk the workspace poms, visit each module's own artifact, and seed the
            // dependency BFS queue with their directly declared dependencies.
            final Deque<String[]> moduleQueue = new ArrayDeque<>();

            final List<Path> pomPaths = new ArrayList<>();
            Files.walkFileTree(workspacePath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                    final String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (name.equals("target") || name.equals(".build") || isIgnored.test(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                    if (file.getFileName().toString().equals(POM_FILENAME)) {
                        pomPaths.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            // A workspace module's own coordinate is already visited authoritatively by visitSelf
            // (preferring its current module-info.java). It must never also be enqueued into the
            // dependency BFS below: a stale jar for that same coordinate sitting in the local repo
            // (e.g. left over from before a module rename) would otherwise be re-visited a second
            // time under whatever ground-truth name that stale jar carries, alongside — or instead
            // of — the correct, current one.
            final Map<String, String> workspaceCoordinates = collectWorkspaceCoordinates(pomReader, pomPaths);

            for (final Path pomPath : pomPaths) {
                walkPom(pomReader, pomPath, rootPom, recorder, visitor);
                effectiveDependencyCoordinates(pomReader, pomPath)
                    .forEach(d -> enqueueIfNew(d, workspaceCoordinates, visited, moduleQueue, recorder));
            }

            // Phase 2: BFS transitive poms from the local repository. A coordinate is visited once
            // per distinct (non-superseded) version — the visited gate fires before enqueue, so
            // visitDependency is called here at dequeue time rather than at the enqueue site. When a
            // higher version of an already-visited coordinate is later discovered, it is re-enqueued
            // and re-visited, so the last visit for a given coordinate always carries its highest
            // discovered version.
            while (!moduleQueue.isEmpty()) {
                final String[] coord = moduleQueue.poll();
                visitDependency(coord[0], coord[1], coord[2], localRepo, recorder, codeModel, visitor);
                PomReader.localRepoPomPath(localRepo, coord[0], coord[1], coord[2])
                    .ifPresent(pomPath -> effectiveDependencyCoordinates(pomReader, pomPath).stream()
                        .filter(d -> !"test".equals(d[3]) && !"provided".equals(d[3]))
                        .forEach(d -> enqueueIfNew(d, workspaceCoordinates, visited, moduleQueue, recorder)));
            }

        } catch (final Exception e) {
            recorder.warn(e, "PomDependencyGraphWalker failed to walk workspace [%s]", workspacePath);
        }
    }

    /**
     * Reads a pom's effective direct dependencies via {@link PomReader} and converts them to
     * {@code [groupId, artifactId, resolvedVersion, scope]} arrays, omitting entries whose version
     * cannot be resolved.
     * <p>
     * One heuristic on top of real Maven semantics: a same-groupId dependency declared with no
     * version at all (a common reactor-workspace shorthand for "the sibling at my own version")
     * defaults to this pom's own resolved version — real Maven has no such default, but this
     * codebase's workspace poms rely on it.
     */
    private static List<String[]> effectiveDependencyCoordinates(final PomReader pomReader, final Path pomPath) {
        final Optional<Pom> pom = pomReader.read(pomPath);
        if (pom.isEmpty()) {
            return List.of();
        }
        final String selfGroupId = pom.get().groupId();
        final String selfVersion = pom.get().version();

        final List<String[]> out = new ArrayList<>();
        for (final Dependency d : pom.get().dependencies()) {
            final String resolvedVersion = d.version()
                .or(() -> d.groupId().equals(selfGroupId) && !selfVersion.isEmpty()
                    ? Optional.of(selfVersion) : Optional.empty())
                .orElse(null);
            if (resolvedVersion == null || resolvedVersion.contains("${")) {
                continue;
            }
            out.add(new String[]{d.groupId(), d.artifactId(), resolvedVersion, d.scope()});
        }
        return out;
    }

    /**
     * Visits the self-artifact of a single workspace pom (unless it is the root aggregator pom).
     * Direct dependencies are seeded into the BFS queue by the caller; they are visited in Phase 2.
     */
    private static void walkPom(final PomReader pomReader,
                                final Path pomPath,
                                final Path rootPomPath,
                                final TelemetryRecorder recorder,
                                final CoordinateVisitor visitor) {
        try {
            // Skip self-registration only for root aggregator poms (packaging=pom).
            // Single-module root poms (packaging=jar or absent) must be registered so
            // their own version is in the map.
            final boolean isRootAggregator = pomPath.equals(rootPomPath)
                && pomReader.read(pomPath).map(Pom::packaging).map("pom"::equals).orElse(false);
            if (!isRootAggregator) {
                visitSelf(pomReader, pomPath, recorder, visitor);
            }
        } catch (final Exception e) {
            recorder.warn(e, "PomDependencyGraphWalker failed to parse [%s]", pomPath);
        }
    }

    /**
     * Returns a map of {@code "groupId:artifactId"} → resolved version for every workspace pom.
     * Used to keep workspace modules out of the dependency BFS queue — see the call site in
     * {@link #walk} — and to detect when a sibling's declared dependency version disagrees with
     * the module's own pom.
     */
    private static Map<String, String> collectWorkspaceCoordinates(final PomReader pomReader,
                                                                   final List<Path> pomPaths) {
        final Map<String, String> coordinates = new HashMap<>();
        for (final Path pomPath : pomPaths) {
            pomReader.read(pomPath).ifPresent(pom -> {
                if (!pom.groupId().isEmpty() && pom.artifactId() != null && !pom.version().isEmpty()
                    && !pom.version().contains("${")) {
                    coordinates.put(pom.groupId() + ":" + pom.artifactId(), pom.version());
                }
            });
        }
        return coordinates;
    }

    /**
     * Enqueues a dependency coordinate for Phase 2 BFS unless it belongs to a workspace module
     * (already visited authoritatively via {@link #visitSelf}), or has already been visited at an
     * equal or higher version.
     * <p>
     * When a coordinate does belong to a workspace module, and the dependency declares an explicit
     * version that disagrees with that module's own pom, this is surfaced via {@code recorder} —
     * otherwise the mismatch would be entirely invisible, since the dependency edge is deliberately
     * not walked (the workspace module's own version always wins).
     * <p>
     * When the same coordinate is reached more than once with different versions — two workspace
     * modules can easily depend on the same transitive artifact through different paths, at
     * different pinned versions — whichever path is visited first must not permanently win. A
     * strictly higher version re-enqueues the coordinate so it is visited again; {@code visited} is
     * updated so a subsequent equal-or-lower encounter is still suppressed.
     */
    private static void enqueueIfNew(final String[] coordinate,
                                     final Map<String, String> workspaceCoordinates,
                                     final Map<String, String> visited,
                                     final Deque<String[]> moduleQueue,
                                     final TelemetryRecorder recorder) {
        final String key = coordinate[0] + ":" + coordinate[1];
        final String workspaceVersion = workspaceCoordinates.get(key);
        if (workspaceVersion != null) {
            if (!workspaceVersion.contains("${") && !workspaceVersion.equals(coordinate[2])) {
                recorder.warn(
                    "Dependency on workspace module [%s:%s] declares version [%s] but the module's own pom is "
                        + "at [%s] — the workspace's own version always wins; this dependency edge is not walked",
                    coordinate[0], coordinate[1], coordinate[2], workspaceVersion);
            }
            return;
        }
        final String existingVersion = visited.get(key);
        if (existingVersion == null || isHigherVersion(coordinate[2], existingVersion)) {
            visited.put(key, coordinate[2]);
            moduleQueue.add(coordinate);
        }
    }

    /**
     * Compares two raw version strings using Maven-qualifier-aware ordering, matching the
     * conflict-resolution semantics {@code MavenCoordinateDedupe} already uses elsewhere in
     * spin-java-module. Unparseable versions never supersede an existing one.
     */
    private static boolean isHigherVersion(final String candidate, final String existing) {
        try {
            return VersionOrder.MAVEN.compare(Version.parse(candidate), Version.parse(existing)) > 0;
        } catch (final Exception e) {
            return false;
        }
    }

    /**
     * Visits the {@code <artifactId>} of a workspace pom itself, using its {@link PomReader}
     * effective model (parent-inherited groupId/version already resolved). Prefers the JPMS
     * module name from {@code module-info.java} when present; otherwise falls back to the
     * derived-name heuristics.
     */
    private static void visitSelf(final PomReader pomReader,
                                  final Path pomPath,
                                  final TelemetryRecorder recorder,
                                  final CoordinateVisitor visitor) {
        try {
            final Optional<Pom> pom = pomReader.read(pomPath);
            if (pom.isEmpty()) {
                return;
            }
            final String groupId = pom.get().groupId();
            final String artifactId = pom.get().artifactId();
            final String resolvedVersion = pom.get().version();

            if (groupId.isEmpty() || artifactId == null
                || resolvedVersion.isEmpty() || resolvedVersion.contains("${")) {
                return;
            }

            final Optional<String> preferred = MavenModuleNaming.readModuleName(pomPath);
            final List<String> names = preferred.isPresent()
                ? List.of(preferred.get())
                : MavenModuleNaming.deriveNames(groupId, artifactId);

            visitor.accept(names, groupId, artifactId, resolvedVersion);
        } catch (final Exception e) {
            recorder.warn(e, "PomDependencyGraphWalker failed to visit self-artifact for [%s]", pomPath);
        }
    }

    /**
     * Visits a dependency coordinate, registering it under the ground-truth JPMS module name when
     * one can be read directly from the jar ({@code module-info.class}, then {@code Automatic-Module-Name});
     * otherwise falls back to the single JPMS-spec-derived automatic module name when the jar is
     * present locally but confirmed to carry neither (it's then definitely an automatic module,
     * which the JDK always names purely from the jar filename, never group-prefixed), or to the
     * full {@link MavenModuleNaming#deriveNames} heuristic set when the jar isn't resolved yet and
     * its eventual naming can't be determined.
     * <p>
     * The ground truth is preferred exclusively (not additively) because heuristic names are derived from the
     * groupId/artifactId shared by every sibling artifact under that groupId (e.g. every {@code io.helidon.config:*}
     * artifact). Registering heuristic names *alongside* a known-correct name lets an unrelated sibling's heuristic
     * guess collide with - and, depending on visit order, shadow - the artifact that actually owns that module name
     * (e.g. {@code io.helidon.config:helidon-config-metadata} guessing its way into {@code io.helidon.confg},
     * which is really owned by {@code io.helidon.config:helidon-config}).
     */
    private static void visitDependency(final String groupId,
                                        final String artifactId,
                                        final String resolvedVersion,
                                        final Path localRepo,
                                        final TelemetryRecorder recorder,
                                        final CodeModel codeModel,
                                        final CoordinateVisitor visitor) {
        try {
            final Optional<String> groundTruth = MavenModuleNaming
                .readNamedModuleName(groupId, artifactId, resolvedVersion, localRepo, codeModel)
                    .or(() -> MavenModuleNaming.readAutomaticModuleName(groupId, artifactId, resolvedVersion, localRepo));
            final boolean confirmedUnnamed = groundTruth.isEmpty()
                && MavenModuleNaming.jarExists(groupId, artifactId, resolvedVersion, localRepo);
            final List<String> names = groundTruth.map(List::of)
                .orElseGet(() -> confirmedUnnamed
                    ? List.of(MavenModuleNaming.derivedModuleName(artifactId))
                    : MavenModuleNaming.deriveNames(groupId, artifactId));
            visitor.accept(names, groupId, artifactId, resolvedVersion);
        } catch (final Exception e) {
            recorder.warn(e, "PomDependencyGraphWalker failed to visit dependency [%s:%s:%s]",
                groupId, artifactId, resolvedVersion);
        }
    }
}
