package build.spin.common.task;

/*-
 * #%L
 * Spin Common Library
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
import build.base.io.PathSetBuilder;
import build.spin.Project;
import build.spin.Task;
import build.spin.annotation.Category;
import build.spin.annotation.Merge;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A {@link Task} that detects the root directories for source of a given {@link SourcePathKind} in a
 * {@link Project}.
 *
 * <p>Every {@link SourcePathKind} a {@link build.spin.Plugin} is responsible for is reported in a
 * single {@link #detect()} call. Tagged with the {@code source-paths} {@link Category}, inherited by
 * every implementor regardless of which {@link build.spin.Plugin} or JDK-version variant produces it,
 * so a caller that wants every source root in a {@link build.spin.Project} — across main, test, and
 * generated sources, from however many {@link build.spin.Plugin}s happen to be present — can request
 * the {@code source-paths} category in a single {@link build.spin.Program} rather than needing to know
 * which concrete {@link Task} classes exist.
 *
 * <p>Annotated {@link Merge}: a {@code @From(DetectSourcePaths.class)} consumer may declare a plain
 * {@code Map<SourcePathKind, PathSet>} parameter instead of {@code Stream<Map<SourcePathKind, PathSet>>}
 * — every {@link build.spin.Plugin}'s implementor still participates, but the framework combines their
 * maps via {@link #merge(Stream)} before injection.
 *
 * @author reed.vonredwitz
 * @since Jul-2026
 */
@Category("source-paths")
@Merge
public interface DetectSourcePaths
    extends Task<Map<SourcePathKind, PathSet>> {

    /**
     * Creates a {@link Map} of the source root directories detected for each {@link SourcePathKind}
     * this task is responsible for.
     *
     * @return the {@link Map} of {@link SourcePathKind} to {@link PathSet}
     */
    Map<SourcePathKind, PathSet> detect();

    /**
     * Combines the {@link Map}s produced by every {@code DetectSourcePaths} implementor present in a
     * {@link Project} into one, unioning the {@link PathSet} for any {@link SourcePathKind} more than
     * one implementor contributes — e.g. a multi-release project where both a Java 8 and a Java 25
     * {@link build.spin.Plugin} each detect {@link SourcePathKind#MAIN} from their own version-specific
     * source directory.
     *
     * @param results the {@link Map}s produced by each {@code DetectSourcePaths} implementor
     * @return the combined {@link Map} of {@link SourcePathKind} to {@link PathSet}
     */
    static Map<SourcePathKind, PathSet> merge(final Stream<Map<SourcePathKind, PathSet>> results) {
        final Map<SourcePathKind, PathSet> merged = new EnumMap<>(SourcePathKind.class);
        results.forEach(result -> result.forEach((kind, paths) ->
            merged.merge(kind, paths, (a, b) -> Stream.concat(a.stream(), b.stream()).collect(PathSet.collector()))));
        return merged;
    }

    /**
     * Merges the source root directories for the specified {@link SourcePathKind}s.
     *
     * @param sourcePaths the {@link Map} produced by {@link #detect()}
     * @param kinds       the {@link SourcePathKind}s to include
     * @return the merged {@link PathSet} of root directories
     */
    static PathSet pathsOf(final Map<SourcePathKind, PathSet> sourcePaths, final SourcePathKind... kinds) {
        return Stream.of(kinds)
            .map(kind -> sourcePaths.getOrDefault(kind, PathSet.empty()))
            .flatMap(PathSet::stream)
            .collect(PathSet.collector());
    }

    /**
     * Merges the source root directories for the specified {@link SourcePathKind}s, then walks them
     * to find the individual {@code .java} source files they contain.
     *
     * @param sourcePaths the {@link Map} produced by {@link #detect()}
     * @param kinds       the {@link SourcePathKind}s to include
     * @return the {@link PathSet} of source files found under the merged root directories
     */
    static PathSet filesOf(final Map<SourcePathKind, PathSet> sourcePaths, final SourcePathKind... kinds) {
        return walk(pathsOf(sourcePaths, kinds));
    }

    /**
     * Walks the specified root directories to find the individual {@code .java} source files they
     * contain, without going through a {@link #detect()} result — for callers that already have a
     * {@link PathSet} of root directories from outside the task graph.
     *
     * @param pathSet the root directories to walk
     * @return the {@link PathSet} of source files found under the root directories
     */
    static PathSet filesOf(final PathSet pathSet) {
        return walk(pathSet);
    }

    private static PathSet walk(final PathSet pathSet) {

        final PathSetBuilder builder = PathSetBuilder.create();

        pathSet.stream()
            .filter(Files::exists)
            .map(path -> {
                try {
                    return path.toRealPath();
                } catch (final IOException e) {
                    return path;
                }
            })
            .forEach(path -> {
                try {
                    Files.walk(path)
                        .sorted(Comparator.reverseOrder())
                        .map(p -> {
                            try {
                                return p.toRealPath();
                            } catch (final IOException e) {
                                return p;
                            }
                        })
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .forEach(builder::add);
                } catch (final IOException e) {
                    throw new RuntimeException("Failed to walk source root [" + path + "] for .java files", e);
                }
            });

        return builder.build();
    }
}
