package build.spin.module.modulesystem.maven;

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

import build.codemodel.foundation.CodeModel;
import build.codemodel.jdk.descriptor.JDKModuleDescriptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives JPMS module names from Maven coordinates, and locates jars/modules for a coordinate in
 * the local repository, for the {@code PomBased*} resource implementations.
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
class MavenModuleNaming {

    private static final Pattern MODULE_NAME_PATTERN =
        Pattern.compile("(?:open\\s+)?module\\s+(\\S+)\\s*\\{");

    private MavenModuleNaming() {
    }

    /**
     * Returns the first hyphen-delimited segment of a Maven artifactId.
     * <p>
     * Examples: {@code junit-jupiter-api} → {@code junit}, {@code assertj-core} → {@code assertj}.
     * Returns an empty string if the artifactId contains no hyphen.
     */
    static String firstHyphenSegment(final String artifactId) {
        final int idx = artifactId.indexOf('-');
        return idx >= 0 ? artifactId.substring(0, idx) : "";
    }

    /**
     * Returns the last hyphen-delimited segment of a Maven artifactId.
     * <p>
     * Examples: {@code assertj-core} → {@code core}, {@code junit-jupiter-api} → {@code api}.
     * Returns an empty string if the artifactId contains no hyphen.
     */
    static String lastHyphenSegment(final String artifactId) {
        final int idx = artifactId.lastIndexOf('-');
        return idx >= 0 ? artifactId.substring(idx + 1) : "";
    }

    /**
     * Derives a JPMS module name from a Maven artifactId, following the Java Platform specification.
     * Non-alphanumeric characters are replaced with {@code .}, and leading/trailing/duplicate dots
     * are stripped.
     */
    static String derivedModuleName(final String artifactId) {
        return artifactId
            .replaceAll("[^A-Za-z0-9]", ".")
            .replaceAll("^\\.*|\\.*$", "")
            .replaceAll("\\.{2,}", ".");
    }

    /**
     * Derives a JPMS module name using the "group-prefix" convention, where the first
     * hyphen-segment of the artifactId mirrors the last dot-segment of the groupId and is stripped.
     * <p>
     * Examples:
     * <ul>
     *   <li>{@code build.base:base-telemetry-foundation} → {@code build.base.telemetry.foundation}</li>
     *   <li>{@code build.codemodel:codemodel-framework} → {@code build.codemodel.framework}</li>
     * </ul>
     * Returns empty if the prefix does not match or the artifactId has no hyphen.
     */
    static Optional<String> groupPrefixedModuleName(final String groupId, final String artifactId) {
        final String groupLastSegment = groupId.contains(".")
            ? groupId.substring(groupId.lastIndexOf('.') + 1)
            : groupId;
        final int firstHyphen = artifactId.indexOf('-');
        if (firstHyphen < 0) {
            return Optional.empty();
        }
        final String firstArtifactSegment = artifactId.substring(0, firstHyphen);
        if (!groupLastSegment.equals(firstArtifactSegment)) {
            return Optional.empty();
        }
        final String remainder = derivedModuleName(artifactId.substring(firstHyphen + 1));
        return remainder.isEmpty() ? Optional.empty() : Optional.of(groupId + "." + remainder);
    }

    /**
     * Derives a JPMS module name using the "group-suffix" convention, where the last
     * hyphen-segment of the artifactId mirrors the last dot-segment of the groupId and is stripped,
     * leaving the first part as a suffix on the groupId.
     * <p>
     * Examples:
     * <ul>
     *   <li>{@code build.codemodel:codemodel-jdk} → {@code build.codemodel.jdk}</li>
     * </ul>
     * Returns empty if the suffix does not match or the artifactId has no hyphen.
     */
    static Optional<String> groupSuffixedModuleName(final String groupId, final String artifactId) {
        final String groupLastSegment = groupId.contains(".")
            ? groupId.substring(groupId.lastIndexOf('.') + 1)
            : groupId;
        final int lastHyphen = artifactId.lastIndexOf('-');
        if (lastHyphen < 0) {
            return Optional.empty();
        }
        final String lastArtifactSegment = artifactId.substring(lastHyphen + 1);
        if (!groupLastSegment.equals(lastArtifactSegment)) {
            return Optional.empty();
        }
        final String remainder = derivedModuleName(artifactId.substring(0, lastHyphen));
        return remainder.isEmpty() ? Optional.empty() : Optional.of(groupId + "." + remainder);
    }

    /**
     * Derives a JPMS module name using the "parent group + last artifact segment" convention,
     * where a sub-group suffix in the groupId (e.g. {@code core}) is replaced by the last
     * hyphen-segment of the artifactId.
     * <p>
     * Examples:
     * <ul>
     *   <li>{@code com.fasterxml.jackson.core:jackson-databind} → {@code com.fasterxml.jackson.databind}</li>
     * </ul>
     * Returns empty if the groupId has no parent (no dot) or the artifactId has no hyphen.
     */
    static Optional<String> groupParentWithLastArtifactSegment(final String groupId, final String artifactId) {
        final int lastDot = groupId.lastIndexOf('.');
        if (lastDot < 0) {
            return Optional.empty();
        }
        // require at least 3 groupId segments so TLD-only parents like "io" or "com" are not used
        if (groupId.indexOf('.') == lastDot) {
            return Optional.empty();
        }
        final String lastArtifactSegment = lastHyphenSegment(artifactId);
        if (lastArtifactSegment.isEmpty()) {
            return Optional.empty();
        }
        final String parentGroupId = groupId.substring(0, lastDot);
        return Optional.of(parentGroupId + "." + lastArtifactSegment);
    }

    /**
     * Returns the derived JPMS module name candidates for a (groupId, artifactId) pair.
     * The list always includes the groupId and the derived artifactId name; additional candidates
     * are included when the group-prefix/suffix/parent conventions above produce them.
     * <p>
     * This is the single canonical set of naming-convention candidates for a Maven coordinate —
     * every caller that needs to guess a JPMS module name from a coordinate alone (no jar to read
     * a ground-truth name from) must go through this method rather than reimplementing a subset
     * of these conventions by hand, which is exactly how {@code PomBasedTestModuleDescriptor} once
     * silently dropped multi-hyphen-segment artifactIds like {@code base-transport-json}.
     */
    static List<String> deriveNames(final String groupId, final String artifactId) {
        final List<String> names = new ArrayList<>(6);
        names.add(groupId);
        names.add(derivedModuleName(artifactId));
        final String lastSegment = lastHyphenSegment(artifactId);
        if (!lastSegment.isEmpty()) {
            // bare segment matches projects whose directory name is just the last artifact segment
            // (e.g. "processor" directory for artifact "ap-simple-processor")
            names.add(lastSegment);
            // only fire when groupPrefixedModuleName won't, and only when the artifact's first
            // hyphen-segment is actually part of the groupId (guards spurious steals like acme-api
            // claiming com.example.api)
            if (groupPrefixedModuleName(groupId, artifactId).isEmpty()) {
                final String firstSeg = firstHyphenSegment(artifactId);
                if (!firstSeg.isEmpty() && groupIdContainsSegment(groupId, firstSeg)) {
                    names.add(groupId + "." + lastSegment);
                }
            }
        }
        groupPrefixedModuleName(groupId, artifactId).ifPresent(names::add);
        groupSuffixedModuleName(groupId, artifactId).ifPresent(names::add);
        groupParentWithLastArtifactSegment(groupId, artifactId).ifPresent(names::add);
        return names;
    }

    private static boolean groupIdContainsSegment(final String groupId, final String segment) {
        int start = 0;
        while (start < groupId.length()) {
            final int dot = groupId.indexOf('.', start);
            final int end = dot < 0 ? groupId.length() : dot;
            if (groupId.regionMatches(start, segment, 0, end - start) && segment.length() == end - start) {
                return true;
            }
            if (dot < 0) {
                break;
            }
            start = dot + 1;
        }
        return false;
    }

    /**
     * Extracts the JPMS module name from the {@code module-info.java} file located at
     * {@code src/main/java/module-info.java} relative to the directory containing the given pom.xml.
     *
     * @param pomPath path to the {@code pom.xml}
     * @return the module name, or empty if no {@code module-info.java} is present or parseable
     */
    static Optional<String> readModuleName(final Path pomPath) {
        final Path moduleInfo = pomPath.getParent().resolve("src/main/java/module-info.java");
        if (!Files.exists(moduleInfo)) {
            return Optional.empty();
        }
        try {
            for (final String line : Files.readAllLines(moduleInfo)) {
                final Matcher matcher = MODULE_NAME_PATTERN.matcher(line);
                if (matcher.find()) {
                    return Optional.of(matcher.group(1));
                }
            }
        } catch (final Exception ignored) {
        }
        return Optional.empty();
    }

    /**
     * Returns {@code true} if the jar for the given Maven coordinates exists in the local repository.
     */
    static boolean jarExists(final String groupId,
                             final String artifactId,
                             final String version,
                             final Path localRepo) {
        return Files.exists(localRepo
            .resolve(groupId.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version)
            .resolve(artifactId + "-" + version + ".jar"));
    }

    /**
     * Reads the {@code Automatic-Module-Name} attribute from the {@code META-INF/MANIFEST.MF} of
     * the jar for the given Maven coordinates in the local repository.
     *
     * @return the automatic module name, or empty if the jar is absent, has no manifest, or
     * carries no {@code Automatic-Module-Name} attribute
     */
    static Optional<String> readAutomaticModuleName(final String groupId,
                                                    final String artifactId,
                                                    final String version,
                                                    final Path localRepo) {
        final Path jarPath = localRepo
            .resolve(groupId.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version)
            .resolve(artifactId + "-" + version + ".jar");
        if (!Files.exists(jarPath)) {
            return Optional.empty();
        }
        try (var jar = new JarFile(jarPath.toFile())) {
            final var manifest = jar.getManifest();
            if (manifest == null) {
                return Optional.empty();
            }
            final String name = manifest.getMainAttributes().getValue("Automatic-Module-Name");
            return Optional.ofNullable(name == null || name.isBlank() ? null : name.trim());
        } catch (final Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * Reads the JPMS module name from the {@code module-info.class} entry inside the jar at the
     * computed local-repository path. Returns empty if the jar doesn't exist, has no
     * {@code module-info.class}, or cannot be read.
     */
    static Optional<String> readNamedModuleName(final String groupId,
                                                final String artifactId,
                                                final String version,
                                                final Path localRepo,
                                                final CodeModel codeModel) {
        final Path jarPath = localRepo
            .resolve(groupId.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version)
            .resolve(artifactId + "-" + version + ".jar");
        if (!Files.exists(jarPath)) {
            return Optional.empty();
        }
        return JDKModuleDescriptor.extractFresh(codeModel, jarPath)
            .map(d -> d.moduleName().toString());
    }

    /**
     * Attempts to find a jar in the local repository for the given module name and version using
     * the naming-convention heuristic: given a module name like {@code build.spin.module.clean},
     * the groupId is the prefix up to the last dot ({@code build.spin.module}), and the artifactId
     * is constructed as {@code {parentLastSegment}-{extra}-{groupLastSegment}}
     * (e.g. {@code spin-clean-module}). Falls back to the plain {@code {extra}} artifactId form.
     * Returns {@code [groupId, artifactId, version]} or empty.
     */
    static Optional<String[]> findJarByModuleName(final String moduleName,
                                                  final String version,
                                                  final Path localRepo) {
        final int lastDot = moduleName.lastIndexOf('.');
        if (lastDot < 0) {
            return Optional.empty();
        }
        final String groupId = moduleName.substring(0, lastDot);
        final String extra = moduleName.substring(lastDot + 1);
        final String groupLastSegment = groupId.contains(".")
            ? groupId.substring(groupId.lastIndexOf('.') + 1)
            : groupId;
        final String parentGroupId = groupId.contains(".")
            ? groupId.substring(0, groupId.lastIndexOf('.'))
            : null;
        final String parentLastSegment = parentGroupId != null && parentGroupId.contains(".")
            ? parentGroupId.substring(parentGroupId.lastIndexOf('.') + 1)
            : parentGroupId;

        final List<String> candidates = new ArrayList<>();
        if (parentLastSegment != null) {
            candidates.add(parentLastSegment + "-" + extra + "-" + groupLastSegment);
        }
        candidates.add(groupLastSegment + "-" + extra);
        candidates.add(extra + "-" + groupLastSegment);
        candidates.add(extra);

        for (final String artifactId : candidates) {
            final Path jarPath = localRepo
                .resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve(version)
                .resolve(artifactId + "-" + version + ".jar");
            if (Files.exists(jarPath)) {
                return Optional.of(new String[]{groupId, artifactId, version});
            }
        }

        // Some naming conventions (e.g. Helidon: groupId io.helidon.config, artifactId helidon-config)
        // have no "extra" suffix beyond the groupId at all - the module name *is* the full groupId verbatim. Retry
        // the same candidate artifactIds under the full, unstripped module name as groupId.
        for (final String artifactId : candidates) {
            final Path jarPath = localRepo
                .resolve(moduleName.replace('.', '/'))
                .resolve(artifactId)
                .resolve(version)
                .resolve(artifactId + "-" + version + ".jar");
            if (Files.exists(jarPath)) {
                return Optional.of(new String[]{moduleName, artifactId, version});
            }
        }
        return Optional.empty();
    }
}
