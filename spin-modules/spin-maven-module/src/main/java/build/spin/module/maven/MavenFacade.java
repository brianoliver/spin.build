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

import build.base.configuration.Configuration;
import build.base.foundation.Exceptional;
import build.base.telemetry.TelemetryRecorder;
import build.spin.module.modulesystem.Artifact;
import build.spin.module.modulesystem.UnresolvableResourceException;
import build.spin.module.modulesystem.pom.Dependency;
import build.spin.module.modulesystem.pom.Gav;
import build.spin.option.NetworkAccess;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static build.spin.option.NetworkAccess.OFFLINE;

/**
 * A facade for Maven artifact resolution backed by the pure-JDK {@link PomResolver}.
 * Provides the same operations as before — single-artifact resolve, descriptor resolve,
 * and transitive dependency resolve — without any Aether or SLF4J dependencies.
 */
class MavenFacade {

    private final TelemetryRecorder recorder;
    private final PomResolver resolver;

    MavenFacade(final TelemetryRecorder recorder,
                final Configuration optionsByType) {
        this.recorder = recorder;
        final boolean offline = optionsByType.getOptional(NetworkAccess.class)
            .map(n -> n == OFFLINE)
            .orElse(false);
        this.resolver = PomResolver.fromSettings(recorder, offline);
    }

    /**
     * Resolves a single artifact to its local {@code .m2} path.
     *
     * @param coordinates {@code groupId:artifactId[:extension[:classifier]]:version}
     * @return an {@link Exceptional} {@link Path}
     */
    public Exceptional<Path> resolveArtifact(final String coordinates) {
        try {
            return this.resolver.resolveArtifact(coordinates)
                .map(Exceptional::of)
                .orElseGet(() -> Exceptional.ofException(new UnresolvableResourceException(coordinates)));
        } catch (final RuntimeException e) {
            this.recorder.error(e, "Unexpected error resolving %s", coordinates);
            return Exceptional.ofException(new UnresolvableResourceException(coordinates, e));
        }
    }

    /**
     * Resolves a single artifact to its local {@code .m2} path. For callers that already have a
     * resolved {@link Artifact} rather than a formatted coordinate string, so no
     * stringify-then-reparse round trip is needed.
     *
     * @param artifact the artifact to resolve
     * @return an {@link Exceptional} {@link Path}
     */
    public Exceptional<Path> resolveArtifact(final Artifact artifact) {
        try {
            return this.resolver.resolveArtifact(coordinatesOf(artifact))
                .map(Exceptional::of)
                .orElseGet(() -> Exceptional.ofException(new UnresolvableResourceException(artifact.toString())));
        } catch (final RuntimeException e) {
            this.recorder.error(e, "Unexpected error resolving %s", artifact);
            return Exceptional.ofException(new UnresolvableResourceException(artifact.toString(), e));
        }
    }

    /**
     * Resolves all transitive compile/runtime dependencies, returning their local paths.
     *
     * @param coordinates {@code groupId:artifactId[:extension[:classifier]]:version}
     * @return an {@link Exceptional} list of local {@link Path}s
     */
    public Exceptional<List<Path>> resolveTransitiveDependencies(final String coordinates) {
        try {
            final List<Path> paths = this.resolver.resolveTransitive(coordinates);
            return Exceptional.of(paths);
        } catch (final RuntimeException e) {
            this.recorder.error(e, "Unexpected error resolving transitive dependencies for %s", coordinates);
            return Exceptional.ofException(new UnresolvableResourceException(coordinates, e));
        }
    }

    /**
     * Resolves all transitive compile/runtime dependencies, returning their local paths. For
     * callers that already have a resolved {@link Artifact} rather than a formatted coordinate
     * string.
     *
     * @param artifact the root artifact
     * @return an {@link Exceptional} list of local {@link Path}s
     */
    public Exceptional<List<Path>> resolveTransitiveDependencies(final Artifact artifact) {
        return resolveTransitiveDependencies(artifact, Set.of());
    }

    /**
     * Resolves all transitive compile/runtime dependencies, returning their local paths, with a set
     * of {@code "groupId:artifactId"} exclusion patterns applied to the root artifact's own closure
     * (the {@code <exclusions>} a consuming pom declared on this dependency edge).
     *
     * @param artifact the root artifact
     * @param exclusions the {@code "groupId:artifactId"} exclusion patterns
     * @return an {@link Exceptional} list of local {@link Path}s
     */
    public Exceptional<List<Path>> resolveTransitiveDependencies(final Artifact artifact,
                                                                 final Set<String> exclusions) {
        try {
            final List<Path> paths = this.resolver.resolveTransitive(gavOf(artifact), artifact.type(), exclusions);
            return Exceptional.of(paths);
        } catch (final RuntimeException e) {
            this.recorder.error(e, "Unexpected error resolving transitive dependencies for %s", artifact);
            return Exceptional.ofException(new UnresolvableResourceException(artifact.toString(), e));
        }
    }

    /**
     * Resolves the effective direct dependencies declared by the POM at the given coordinates.
     *
     * @param coordinates {@code groupId:artifactId[:extension[:classifier]]:version}
     * @return an {@link Exceptional} list of {@link Dependency} entries
     */
    public Exceptional<List<Dependency>> resolveArtifactDescriptor(final String coordinates) {
        try {
            final List<Dependency> deps = this.resolver.resolveDescriptor(coordinates);
            return Exceptional.of(deps);
        } catch (final RuntimeException e) {
            this.recorder.error(e, "Unexpected error resolving descriptor for %s", coordinates);
            return Exceptional.ofException(new UnresolvableResourceException(coordinates, e));
        }
    }

    /**
     * Resolves the effective direct dependencies declared by the POM for the given artifact. For
     * callers that already have a resolved {@link Artifact} rather than a formatted coordinate
     * string.
     *
     * @param artifact the artifact whose POM should be read
     * @return an {@link Exceptional} list of {@link Dependency} entries
     */
    public Exceptional<List<Dependency>> resolveArtifactDescriptor(final Artifact artifact) {
        try {
            final List<Dependency> deps = this.resolver.resolveDescriptor(gavOf(artifact));
            return Exceptional.of(deps);
        } catch (final RuntimeException e) {
            this.recorder.error(e, "Unexpected error resolving descriptor for %s", artifact);
            return Exceptional.ofException(new UnresolvableResourceException(artifact.toString(), e));
        }
    }

    private static Gav gavOf(final Artifact artifact) {
        return new Gav(artifact.groupId(), artifact.artifactId(), artifact.version().toString());
    }

    private static PomResolver.Coordinates coordinatesOf(final Artifact artifact) {
        return new PomResolver.Coordinates(gavOf(artifact), artifact.type(), artifact.classifier());
    }
}
