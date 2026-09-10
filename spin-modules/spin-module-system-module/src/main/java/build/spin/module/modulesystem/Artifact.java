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

import build.base.foundation.Exceptional;
import build.base.foundation.Strings;
import build.base.io.LookaheadReader;
import build.base.parsing.ParseException;
import build.base.version.Version;
import build.base.version.VersionConstraint;
import build.codemodel.jdk.descriptor.JDKModuleDescriptor;
import build.spin.Project;
import build.spin.Task;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Specifies information identifying an artifact required by a {@link Project}.
 * <p>
 * An {@link Artifact} identifies a file of some type, required by or produced by a {@link Project} that is possibly
 * made available to or published to an Apache Maven-based repository.
 * <p>
 * Each {@link Artifact} specifies:
 * <ol>
 *     <li>a {@code groupId} which is usually the name of a company or group of products,
 *          often as a reverse domain name (eg: com.workday)</li>
 *     <li>an {@code artifactId} which is usually the name of the artifact (eg: quark)</li>
 *     <li>a {@code version}</li>
 *     <li>a {@code type}, which is usually the file name module</li>
 * </ol>
 * <p>
 * {@link Artifact}s may optionally define a {@code classifier}, which usually signifies environmental constraints
 * (eg: windows or linux operating specific artifact, but these are {@link Optional}).
 *
 * @author brian.oliver
 * @since Sep-2019
 */
public interface Artifact {

    /**
     * Obtains the group for the {@link Artifact}.
     *
     * @return the group for the {@link Artifact}.
     */
    String groupId();

    /**
     * Obtains the name for the {@link Artifact}.
     *
     * @return the name for the {@link Artifact}.
     */
    String artifactId();

    /**
     * Obtains the {@link Version} for the {@link Artifact}.
     *
     * @return the {@link Version} for the {@link Artifact}.
     */
    Version version();

    /**
     * Obtains the type for the {@link Artifact}.
     *
     * @return the type for the {@link Artifact}.
     */
    String type();

    /**
     * Obtains the {@link Optional} classifier for the {@link Artifact}.
     *
     * @return the {@link Optional} classifier for the {@link Artifact}.
     */
    Optional<String> classifier();

    /**
     * Creates an {@link Artifact}.
     *
     * @param groupId the groupId for the {@link Artifact}
     * @param artifactId the artifactId for the {@link Artifact}
     * @param version the version for the {@link Artifact}
     * @param type the type for the {@link Artifact}
     * @param classifier the optional classifier for the {@link Artifact}
     *
     * @return a new {@link Artifact}
     */
    static Artifact create(final String groupId,
                           final String artifactId,
                           final String version,
                           final String type,
                           final String classifier) {

        return new Artifact.Implementation(groupId, artifactId, version, type, classifier);
    }

    /**
     * Creates an {@link Artifact} without a classifier.
     *
     * @param groupId the groupId for the {@link Artifact}
     * @param artifactId the artifactId for the {@link Artifact}
     * @param version the version for the {@link Artifact}
     * @param type the type for the {@link Artifact}
     *
     * @return a new {@link Artifact}
     */
    static Artifact create(final String groupId,
                           final String artifactId,
                           final String version,
                           final String type) {

        return create(groupId, artifactId, version, type, null);
    }

    /**
     * Creates an {@link Artifact} based on the specified Apache Maven Coordinates, which are in the following format
     * {@code <groupId>:<artifactId>[:<module>[:<classifier>]]:<version>}.
     *
     * @param coordinates the coordinates
     *
     * @return a new {@link Artifact}
     * @throws ParseException if the specified coordinates are in an unknown format
     */
    static Artifact parse(final String coordinates) {

        // the Apache Maven Coordinates Pattern
        final Pattern PATTERN = Pattern.compile("([^: ]+):([^: ]+)(:([^: ]*)(:([^: ]+))?)?:([^: ]+)");

        final Matcher matcher = PATTERN.matcher(coordinates);

        if (!matcher.matches()) {
            throw new ParseException(LookaheadReader.Location.START,
                "<groupId>:<artifactId>[:<module>[:<classifier>]]:<version>",
                coordinates);
        }

        final String groupId = matcher.group(1);
        final String artifactId = matcher.group(2);
        final String extension = Strings.isEmpty(matcher.group(4)) ? "jar" : matcher.group(4);
        final String classifier = matcher.group(6);
        final String version = matcher.group(7);

        return create(groupId, artifactId, version, extension, classifier);
    }

    /**
     * Specifies a {@link Version} constrained {@link Artifact}.
     * <p>
     * {@link Artifact}s are constrained to {@link Version}s using {@link VersionConstraint}s.
     */
    class Constraint
        implements Predicate<Artifact> {

        private final String groupId;
        private final String artifactId;
        private final VersionConstraint range;
        private final String type;
        private final String classifier;

        /**
         * Constructs an {@link Artifact.Constraint}.
         *
         * @param groupId the groupId for the {@link Artifact.Constraint}
         * @param artifactId the artifactId for the {@link Artifact.Constraint}
         * @param range the {@link VersionConstraint} for the {@link Artifact.Constraint}
         * @param type the type for the {@link Artifact.Constraint}
         * @param classifier the optional classifier for the {@link Artifact.Constraint}
         */
        private Constraint(final String groupId,
                           final String artifactId,
                           final VersionConstraint range,
                           final String type,
                           final String classifier) {

            this.groupId = Objects.requireNonNull(groupId, "The groupId must not be null");
            this.artifactId = Objects.requireNonNull(artifactId, "The artifactId must not be null");
            this.range = Objects.requireNonNull(range, "The range must not be null");
            this.type = Objects.requireNonNull(type, "The type must not be null");
            this.classifier = Strings.isEmpty(classifier) ? null : classifier;
        }

        /**
         * Obtains the group for the {@link Artifact}.
         *
         * @return the group for the {@link Artifact}.
         */
        public String groupId() {
            return this.groupId;
        }

        /**
         * Obtains the name for the {@link Artifact}.
         *
         * @return the name for the {@link Artifact}.
         */
        public String artifactId() {
            return this.artifactId;
        }

        /**
         * Obtains the {@link VersionConstraint} constraining the {@link Version}s of the {@link Artifact}.
         *
         * @return the {@link VersionConstraint}
         */
        public VersionConstraint range() {
            return this.range;
        }

        /**
         * Obtains the type for the {@link Artifact}.
         *
         * @return the type for the {@link Artifact}.
         */
        public String type() {
            return this.type;
        }

        /**
         * Obtains the {@link Optional} classifier for the {@link Artifact}.
         *
         * @return the {@link Optional} classifier for the {@link Artifact}.
         */
        public Optional<String> classifier() {
            return Optional.ofNullable(this.classifier);
        }

        /**
         * Determines if the specified {@link Artifact} satisfies the {@link Constraint} by having the same
         * {@link #groupId()}, {@link #artifactId()}, {@link #type()}, {@link #classifier()} and the
         * {@link Artifact#version()} is in the {@link #range()}.
         *
         * @param artifact the {@link Artifact}
         * @return {@code true} if the {@link Artifact} satisfies the {@link Constraint}, {@code false} otherwise
         */
        public boolean contains(final Artifact artifact) {
            return test(artifact);
        }

        /**
         * Determines if the specified {@link Version} is in the {@link VersionConstraint} defined by the {@link Constraint}.
         *
         * @param version the {@link Version}
         * @return {@code true} if the {@link Version} satisfies the {@link Constraint}, {@code false} otherwise
         */
        public boolean contains(final Version version) {
            return version != null && range().contains(version);
        }

        /**
         * Creates an {@link Artifact} based on the information defined by the {@link Constraint} and the specified
         * {@link Version}, if and only if the said {@link Version} satisfies the {@link Constraint}.
         *
         * @param version the {@link Version}
         * @return {@link Optional} {@link Artifact}
         * @throws IllegalArgumentException if the specified {@link Version} is not in the {@link VersionConstraint}
         *          defined by the {@link Constraint}
         */
        public Artifact createArtifact(final Version version)
            throws IllegalArgumentException {

            if (!contains(version)) {
                throw new IllegalArgumentException(
                    "The specified version " + version + " is not in the range " + this.range);
            }

            return new Artifact.Implementation(groupId(), artifactId(), version, type(), this.classifier);
        }

        @Override
        public boolean test(final Artifact artifact) {
            return artifact != null
                && groupId().equals(artifact.groupId())
                && artifactId().equals(artifact.artifactId())
                && type().equals(artifact.type())
                && classifier().equals(artifact.classifier())
                && range().contains(artifact.version());
        }

        @Override
        public String toString() {
            return this.groupId + ":" +
                this.artifactId + ":" +
                this.type + ":" +
                (classifier().map(c -> c + ":").orElse("")) +
                this.range;
        }

        @Override
        public boolean equals(final Object object) {
            if (this == object) {
                return true;
            }
            if (object == null || getClass() != object.getClass()) {
                return false;
            }
            final Constraint that = (Constraint) object;

            return this.groupId.equals(that.groupId) &&
                this.artifactId.equals(that.artifactId) &&
                this.range.equals(that.range) &&
                this.type.equals(that.type) &&
                Objects.equals(this.classifier, that.classifier);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.groupId, this.artifactId, this.range, this.type, this.classifier);
        }

        /**
         * Creates a {@link Artifact.Constraint}, specifically constraining the {@link VersionConstraint} to the
         * specified {@link Artifact}.
         *
         * @param artifact the {@link Artifact}
         * @return a new {@link Artifact.Constraint}
         */
        public static Constraint of(final Artifact artifact) {
            Objects.requireNonNull(artifact, "The Artifact must not be null");

            return new Constraint(artifact.groupId(),
                artifact.artifactId(),
                VersionConstraint.parse(artifact.version().get()),
                artifact.type(),
                artifact.classifier().orElse(null));
        }

        /**
         * Creates a {@link Artifact.Constraint} based on the specified Apache Maven Coordinates, which are in the
         * following format {@code <groupId>:<artifactId>[:<module>[:<classifier>]]:<version-range>}, where by
         * the {@code <version-range>} is formatted according to {@link VersionConstraint#parse(String)}.
         *
         * @param coordinates the coordinates
         *
         * @return a new {@link Artifact.Constraint}
         * @throws ParseException if the specified coordinates are in an unknown format
         */
        public static Constraint parse(final String coordinates) {

            // the Apache Maven Coordinates Pattern
            final Pattern PATTERN = Pattern.compile("([^: ]+):([^: ]+)(:([^: ]*)(:([^: ]+))?)?:([^: ]+)");

            final Matcher matcher = PATTERN.matcher(coordinates);

            if (!matcher.matches()) {
                throw new ParseException(LookaheadReader.Location.START,
                    "<groupId>:<artifactId>[:<module>[:<classifier>]]:<version-range>",
                    coordinates);
            }

            final String groupId = matcher.group(1);
            final String artifactId = matcher.group(2);
            final String extension = Strings.isEmpty(matcher.group(4)) ? "jar" : matcher.group(4);
            final String classifier = matcher.group(6);
            final VersionConstraint range = VersionConstraint.parse(matcher.group(7));

            return new Constraint(groupId, artifactId, range, extension, classifier);
        }
    }

    /**
     * Provides the ability to resolve the {@link Path} and {@link JDKModuleDescriptor} of {@link Artifact}s so that
     * they may be used by {@link Task}s.
     */
    interface Resolver {

        /**
         * Attempts to resolve the specified {@link Artifact}.
         * <p>
         * Should the {@link Resolver} be unable to locate the {@link Artifact}, the returned {@link Exceptional}
         * will indicate the possible cause of the issue, including resolver failure.
         *
         * @param artifact the {@link Artifact}
         *
         * @return an {@link Exceptional} {@link Path} of the resolved {@link Artifact}
         */
        Exceptional<Path> resolve(Artifact artifact);

        /**
         * Attempts to obtain the {@link ModuleReference} for the specified {@link Artifact}.
         * <p>
         * Should the {@link Resolver} be unable to resolve the {@link ModuleReference}, the {@link ModuleCatalog}
         * will be used.  Should neither the {@link Resolver} or {@link ModuleCatalog} be able to determine the
         * {@link ModuleReference}, {@link Exceptional#empty()} will be returned.
         *
         * @param artifact the {@link Artifact}
         * @param catalog the {@link ModuleCatalog}
         *
         * @return an {@link Exceptional} {@link ModuleReference} for the specified {@link Artifact}, or
         *         {@link Exceptional#empty()} if unknown.
         *
         * @see ModuleCatalog#getDerivedModuleReference(Artifact)
         */
        Exceptional<ModuleReference> getModuleReference(Artifact artifact,
                                                        ModuleCatalog catalog);

        /**
         * Attempt to obtain the {@link JDKModuleDescriptor} for the specified {@link Artifact}.
         *
         * @param artifact the {@link Artifact}
         * @param catalog the {@link ModuleCatalog}
         * @param versioning the {@link ModuleVersioning}
         *
         * @return an {@link Exceptional} {@link JDKModuleDescriptor} for the specified {@link Artifact}
         */
        Exceptional<JDKModuleDescriptor> getModuleDescriptor(Artifact artifact,
                                                             ModuleCatalog catalog,
                                                             ModuleVersioning versioning);

        /**
         * Attempts to transitively resolve the specified {@link Artifact} and all of its
         * compile-scope transitive dependencies, returning the full set of {@link Path}s.
         * <p>
         * Implementations are expected to handle artifact-graph cycles correctly.
         *
         * @param artifact the root {@link Artifact}
         *
         * @return an {@link Exceptional} {@link List} of {@link Path}s, one per resolved
         *         artifact (root + all transitive compile-scope dependencies)
         */
        Exceptional<List<Path>> resolveTransitive(Artifact artifact);

        /**
         * As {@link #resolveTransitive(Artifact)}, but with a set of {@code "groupId:artifactId"}
         * exclusion patterns (either side may be the Maven wildcard {@code *}) applied to the root
         * artifact's own transitive closure — the {@code <exclusions>} a consuming pom declared on
         * this dependency edge, which the bare {@link Artifact} does not carry. Inherited down the
         * whole subtree, exactly as Maven's own {@code <exclusions>} semantics require.
         * <p>
         * The default ignores {@code exclusions} and falls back to {@link #resolveTransitive(Artifact)};
         * {@link build.spin.module.modulesystem.Artifact.Resolver} implementations backed by a real
         * pom walk override it.
         *
         * @param artifact the root {@link Artifact}
         * @param exclusions the {@code "groupId:artifactId"} exclusion patterns
         *
         * @return an {@link Exceptional} {@link List} of {@link Path}s
         */
        default Exceptional<List<Path>> resolveTransitive(final Artifact artifact, final Set<String> exclusions) {
            return resolveTransitive(artifact);
        }
    }

    /**
     * The default implementation of an {@link Artifact}.
     */
    class Implementation
        implements Artifact {

        private final String groupId;
        private final String artifactId;
        private final Version version;
        private final String type;
        private final String classifier;

        /**
         * Constructs an {@link Artifact}.
         *
         * @param groupId the groupId for the {@link Artifact}
         * @param artifactId the artifactId for the {@link Artifact}
         * @param version the version for the {@link Artifact}
         * @param type the type for the {@link Artifact}
         * @param classifier the optional classifier for the {@link Artifact}
         */
        public Implementation(final String groupId,
                              final String artifactId,
                              final Version version,
                              final String type,
                              final String classifier) {

            this.groupId = Objects.requireNonNull(groupId, "The groupId must not be null");
            this.artifactId = Objects.requireNonNull(artifactId, "The artifactId must not be null");
            this.version = Objects.requireNonNull(version, "The version must not be null");
            this.type = Objects.requireNonNull(type, "The type must not be null");
            this.classifier = Strings.isEmpty(classifier) ? null : classifier;
        }

        /**
         * Constructs an {@link Artifact}.
         *
         * @param groupId the groupId for the {@link Artifact}
         * @param artifactId the artifactId for the {@link Artifact}
         * @param version the version for the {@link Artifact}
         * @param type the type for the {@link Artifact}
         * @param classifier the optional classifier for the {@link Artifact}
         */
        public Implementation(final String groupId,
                              final String artifactId,
                              final String version,
                              final String type,
                              final String classifier) {

            this(groupId, artifactId, Version.parse(version), type, classifier);
        }

        /**
         * Constructs an {@link Artifact}, without a classifier.
         *
         * @param groupId the groupId for the {@link Artifact}
         * @param artifactId the artifactId for the {@link Artifact}
         * @param version the version for the {@link Artifact}
         * @param type the type for the {@link Artifact}
         */
        public Implementation(final String groupId,
                              final String artifactId,
                              final String version,
                              final String type) {

            this(groupId, artifactId, version, type, null);
        }

        @Override
        public String groupId() {
            return this.groupId;
        }

        @Override
        public String artifactId() {
            return this.artifactId;
        }

        @Override
        public Version version() {
            return this.version;
        }

        @Override
        public String type() {
            return this.type;
        }

        @Override
        public Optional<String> classifier() {
            return Optional.ofNullable(this.classifier);
        }

        @Override
        public String toString() {
            return this.groupId + ":" +
                this.artifactId + ":" +
                this.type + ":" +
                (classifier().map(c -> c + ":").orElse("")) +
                this.version;
        }

        @Override
        public boolean equals(final Object object) {
            if (this == object) {
                return true;
            }
            if (object == null || getClass() != object.getClass()) {
                return false;
            }
            final Implementation that = (Implementation) object;

            return this.groupId.equals(that.groupId) &&
                this.artifactId.equals(that.artifactId) &&
                this.version.equals(that.version) &&
                this.type.equals(that.type) &&
                Objects.equals(this.classifier, that.classifier);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.groupId, this.artifactId, this.version, this.type, this.classifier);
        }
    }
}
