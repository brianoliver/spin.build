package build.spin;

/*-
 * #%L
 * Spin API
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

import build.base.configuration.Configuration;

import java.nio.file.Path;
import java.util.LinkedList;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * A {@link Path} to content, files and folders, for which one or more {@link Plugin}s defining {@link Task}s
 * and {@link Resource}s have been detected as applicable.
 *
 * @see Plugin
 * @see Resource
 *
 * @author brian.oliver
 * @since Dec-2022
 */
public interface Project
    extends Comparable<Project> {

    /**
     * Obtains the name of the {@link Project}.
     *
     * @return the name of the {@link Project}
     */
    String name();

    /**
     * Obtains the {@link Engine} that produced the {@link Project}.
     *
     * @return the {@link Engine}
     */
    Engine engine();

    /**
     * Obtains the {@link Optional} parent {@link Project}.
     *
     * @return the {@link Optional} parent {@link Project}
     */
    Optional<Project> parent();

    /**
     * Obtains the {@link Stream} of {@link Project}s representing the hierarchical structure of this {@link Project}
     * all the way to and including the {@link Workspace}, the most immediate {@link Project} returned being this
     * {@link Project}, the last project being the {@link Workspace}.
     *
     * @return the {@link Stream} of {@link Project}s
     */
    default Stream<Project> hierarchy() {
        final LinkedList<Project> projects = new LinkedList<>();

        Project current = this;
        while (current != null) {
            projects.add(current);
            current = current.parent().orElse(null);
        }

        return projects.stream();
    }

    /**
     * Obtains the fully qualified name of the {@link Project}, formed from the {@link Workspace}'s
     * {@link #name()} followed by this {@link Project}'s {@link #path()} relative to the {@link Workspace}'s
     * own {@link #path()} - eg: {@code jeffrey/shared/ui/version}.
     * <p>
     * Deliberately derived from the filesystem {@link #path()} rather than {@link #hierarchy()}/
     * {@link #parent()}: a directory with no {@link Plugin} or {@link Resource} of its own detected in it
     * (eg: a pure aggregator {@code pom.xml} with no source of its own) never becomes a {@link Project},
     * so {@link #parent()} skips straight over it to the nearest ancestor that did - which would make a
     * {@link #hierarchy()}-based name just as ambiguous as {@link #name()} alone for two unrelated
     * {@link Project}s that happen to share a simple name but sit under different such skipped ancestors.
     *
     * @return the fully qualified name of the {@link Project}
     */
    default String qualifiedName() {
        final Project workspace = workspace();
        final String relative = workspace.path().relativize(path()).toString().replace('\\', '/');

        return relative.isEmpty() ? workspace.name() : workspace.name() + "/" + relative;
    }

    /**
     * Obtains the depth (distance) of the {@link Project} from the {@link Workspace}.
     * <p>
     * A depth of 0 means the {@link Project} is the {@link Workspace}.  A depth of 1 means the
     * {@link Project} is an immediate child of the {@link Workspace}, and so on.
     *
     * @return the depth
     */
    int depth();

    /**
     * Obtains the {@link Path} for the {@link Project}.
     *
     * @return the {@link Path} for the {@link Project}.
     */
    Path path();

    /**
     * Obtains the {@link Configuration} for the {@link Project}.
     *
     * @return the {@link Configuration}
     */
    Configuration options();

    /**
     * Obtains the {@link Stream} of {@link Plugin}s for the {@link Project}.
     *
     * @return the {@link Stream} of {@link Plugin}s
     */
    Stream<Plugin> plugins();

    /**
     * Obtains the {@link Stream} of {@link Plugin}s for the {@link Project} that are assignable to the specified
     * {@link Class}.
     *
     * @param <T> the assignable type
     * @param assignableTo the {@link Class} to which a {@link Plugin} must be assignable
     *
     * @return a {@link Stream} of {@link Plugin}s assignable to the specified {@link Class}
     */
    <T> Stream<T> plugins(Class<T> assignableTo);

    /**
     * Determines if the {@link Project} contains a {@link Plugin} that is assignable to the specified {@link Class}.
     *
     * @param assignableTo the {@link Class} to which the {@link Plugin} must be assignable
     * @return {@code true} when such a {@link Plugin} is defined by the {@link Project}, {@code false} otherwise
     */
    boolean contains(Class<?> assignableTo);

    /**
     * Obtains the {@link Stream} of {@link Resource}s defined by the {@link Project}.
     *
     * @return the {@link Stream} of {@link Resource}s
     */
    Stream<Resource> resources();

    /**
     * Obtains the {@link Stream} of {@link Extension}s (both {@link Plugin}s and {@link Resource}s) for the
     * {@link Project}.
     *
     * @return the {@link Stream} of {@link Extension}s
     */
    default Stream<Extension> extensions() {
        return Stream.concat(plugins(), resources());
    }

    /**
     * Obtains the {@link Stream} of {@link Extension}s (both {@link Plugin}s and {@link Resource}s) for the
     * {@link Project} that are assignable to the specified {@link Class}.
     *
     * @param <T> the assignable type
     * @param assignableTo the {@link Class} to which an {@link Extension} must be assignable
     *
     * @return a {@link Stream} of {@link Extension}s assignable to the specified {@link Class}
     */
    default <T> Stream<T> extensions(final Class<T> assignableTo) {
        return extensions()
            .filter(assignableTo::isInstance)
            .map(assignableTo::cast);
    }

    /**
     * Obtains the {@link Workspace} for the {@link Project}.
     *
     * @return the {@link Workspace}
     */
    Workspace workspace();

    /**
     * Obtains a {@link Stream} of {@link Project}s, including this {@link Project} and all descendant {@link Project}s
     * of this {@link Project}, that satisfy the specified {@link Predicate}.
     *
     * @param predicate the {@link Predicate}
     * @return the {@link Stream} of {@link Project}s
     */
    Stream<Project> stream(Predicate<? super Project> predicate);

    /**
     * Obtains a {@link Stream} of {@link Project}s, including this {@link Project} and all descendant {@link Project}s
     * of this {@link Project}.
     *
     * @return the {@link Stream} of {@link Project}s
     */
    Stream<Project> stream();

    /**
     * Obtains a {@link Stream} of the immediate child {@link Project}s of the {@link Project}.
     *
     * @return a {@link Stream} of the immediate child {@link Project}s
     */
    Stream<Project> children();

    /**
     * Obtains a {@link Stream} of <strong>all</strong> {@link Invocable}s defined by the {@link Project}.
     *
     * @return a {@link Stream} of all {@link Invocable}s.
     */
    Stream<Invocable<?>> invocables();

    /**
     * Obtains the {@link Invocable} defined by the {@link Project} for the specified {@link Reference}, if any.
     * <p>
     * The default implementation is a linear scan of {@link #invocables()}; implementations that maintain an
     * indexed lookup should override this for O(1) resolution.
     *
     * @param reference the {@link Reference} to resolve
     *
     * @return the {@link Optional} {@link Invocable} matching {@code reference}
     */
    default Optional<Invocable<?>> getInvocable(final Reference reference) {
        return invocables()
            .filter(definition -> definition.getReference().equals(reference))
            .findFirst();
    }

    /**
     * Obtains the {@link Stream} of {@link Invocable}s defined by the {@link Project} whose {@link Task} class
     * is assignable to {@code assignableTo}.
     * <p>
     * The default implementation is a linear scan of {@link #invocables()}; implementations that maintain an
     * indexed lookup should override this for callers that repeatedly query the same {@code assignableTo}.
     *
     * @param assignableTo the {@link Task} {@link Class} to match against
     *
     * @return the {@link Stream} of matching {@link Invocable}s
     */
    default Stream<Invocable<?>> getInvocables(final Class<? extends Task<?>> assignableTo) {
        return invocables()
            .filter(definition -> assignableTo.isAssignableFrom(definition.getTaskClass()));
    }

    /**
     * Obtains the {@link Plugin} present in the {@link Project} that is assignable to the specified {@link Class}.
     *
     * @param <T> the type of {@link Plugin}
     * @param pluginClass the {@link Class} of {@link Plugin}
     * @return an {@link Optional} {@link Plugin}
     */
    <T extends Plugin> Optional<T> getPlugin(Class<T> pluginClass);

    /**
     * Obtains the {@link Resource} present in the {@link Project} that is assignable to the specified {@link Class}.
     *
     * @param <T> the type of {@link Resource}
     * @param resourceClass the {@link Class} of {@link Resource}
     * @return an {@link Optional} type of {@link Resource}
     */
    <T extends Resource> Optional<T> getResource(Class<T> resourceClass);

    /**
     * Obtains the {@link Resource} present in either this {@link Project} or one of the parent {@link Project}s,
     * that is assignable to the specified {@link Class}.
     *
     * @param <T> the type of {@link Resource}
     * @param resourceClass the {@link Class} of {@link Resource}
     * @return an {@link Optional} {@link Resource}
     */
    <T extends Resource> Optional<T> findResource(Class<T> resourceClass);

    /**
     * Walks the {@link Project} and any defined child-{@link Project}s using the specified {@link Visitor}.
     *
     * @param visitor the {@link Visitor}
     */
    void walk(Visitor<? super Project> visitor);

    /**
     * Determines if the specified {@link Path} is contained with in the {@link Project}, or one of its child
     * {@link Project}s.
     *
     * @param path the {@link Path}
     * @return {@code true} if the {@link Path} is in the {@link Project} (or a child {@link Project},
     *         {@code false} otherwise
     */
    boolean contains(Path path);

    /**
     * Determines if the contents of the specified {@link Path}, a file or folder, must be ignored by
     * {@link Plugin}s in this {@link Project}, or if the parent {@link Project} requires it to be ignored.
     * <p>
     * {@link Project}s determine when {@link Path}s are ignored by consulting their {@link Resource}s
     * {@link Resource#isIgnored(Path)} method.
     * <p>
     * Implementations must be monotonic with respect to {@link #parent()}: if {@code parent().isIgnored(path)}
     * is {@code true}, this method must also return {@code true} for the same {@link Path}. Callers (e.g.
     * project discovery) are entitled to rely on a parent {@link Project}'s answer as a lower bound for any
     * child {@link Project}'s answer.
     *
     * @param path the {@link Path}
     * @return {@code true} if the {@link Path} must be ignored, {@code false} otherwise
     */
    boolean isIgnored(Path path);

    /**
     * Obtains the {@link Stream} of {@link Invocable}s of {@link Task}s for the {@link Project} that are
     * codependencies for the specified {@link Reference}.
     *
     * @param reference the {@link Reference}
     *
     * @return the {@link Stream} of codependency {@link Invocable}s
     */
    Stream<Invocable<?>> codependencies(Reference reference);

    /**
     * Discovers the {@link Project} that contains the specified {@link Path}.
     *
     * @param path the {@link Path}
     *
     * @return an {@link Optional} {@link Project}
     */
    Optional<Project> getProject(Path path);

    /**
     * Generates a {@link String}-based representation of the {@link Project} tree, from this {@link Project} down.
     *
     * @param builder the {@link StringBuilder} in which to place the generated tree
     * @param prefix the prefix for the {@link Project}
     * @param childPrefix the prefix for children of the {@link Project}
     * @param contentExtractor the {@link Function} producing {@link Project} content for the tree
     */
    void treeify(StringBuilder builder,
                 String prefix,
                 String childPrefix,
                 Function<Project, String> contentExtractor);

    @Override
    int compareTo(Project other);

    @Override
    String toString();
}
