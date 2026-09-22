package build.spin.module.modulesystem.pom;

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
import build.spin.option.OperatingSystem;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Reads a {@code pom.xml} into an effective {@link Pom} model: parent inheritance applied,
 * {@code <dependencyManagement>} (including {@code <scope>import</scope>} BOM merging) and
 * {@code <pluginManagement>} merged into dependencies and plugins, active {@code <profiles>}
 * (jdk/os/property/file/activeByDefault activation) folded in, {@code <exclusions>} preserved on
 * each dependency, and {@code ${...}} property references interpolated against the effective
 * property map plus a small set of Maven built-ins.
 * <p>
 * Parent and BOM-import POMs are located via a {@link PomLocator}. The default locator (used by
 * the two-arg constructor) only looks in the local repository and never downloads; a caller that
 * needs to fetch missing POMs over the network (e.g. an artifact resolver) supplies its own.
 * <p>
 * Parent resolution tries {@code <relativePath>} first (default {@code ../pom.xml}) when
 * {@code allowRelativePath} is enabled, and falls back to the {@link PomLocator} otherwise.
 * Cycles in the parent/BOM-import chain are guarded against.
 * <p>
 * Coords of the form {@code ${groupId:artifactId:type[:classifier]}} (output of the
 * {@code maven-dependency-plugin:properties} goal) are <strong>not</strong> interpolated here —
 * they require a resolved test classpath, which lives outside the pom.
 * <p>
 * Reads are cached by absolute path per {@code PomReader} instance so parent chains, BOM imports,
 * and sibling sub-projects share parsed POMs.
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public final class PomReader {

    /**
     * Locates the local file for a POM given its coordinates. Implementations may download the
     * POM first if it isn't already present; {@link #locate} returning empty means the POM could
     * not be made available (e.g. offline and not cached).
     */
    @FunctionalInterface
    public interface PomLocator {
        Optional<Path> locate(Gav gav);
    }

    private static final Pattern PROPERTY_REF = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * Matches the dependency-plugin {@code properties}-goal coord syntax
     * {@code ${groupId:artifactId:type[:classifier]}}. Resolved by callers with classpath context.
     */
    private static final Pattern COORD_REF = Pattern.compile("\\$\\{[^:}]+:[^:}]+(?::[^:}]+){1,2}}");

    private final Path localRepository;
    private final TelemetryRecorder recorder;
    private final boolean allowRelativePath;
    private final PomLocator locator;
    private final Map<Path, Pom> cache = new ConcurrentHashMap<>();

    /**
     * Reads POMs already present on disk: parent resolution tries {@code <relativePath>} first,
     * then falls back to the local repository, never downloading.
     */
    public PomReader(final Path localRepository,
                     final TelemetryRecorder recorder) {
        this(localRepository, recorder, true, gav -> localRepoPomPath(localRepository, gav));
    }

    /**
     * @param allowRelativePath whether parent resolution should try {@code <relativePath>} first.
     *     Only meaningful for workspace poms with sibling reactor modules on disk; a reader that
     *     only ever resolves downloaded repository artifacts should pass {@code false}.
     * @param locator resolves a POM's local file for both parent and BOM-import lookups,
     *     downloading it first if the implementation wants to
     */
    public PomReader(final Path localRepository,
                     final TelemetryRecorder recorder,
                     final boolean allowRelativePath,
                     final PomLocator locator) {
        this.localRepository = localRepository;
        this.recorder = recorder;
        this.allowRelativePath = allowRelativePath;
        this.locator = locator;
    }

    /**
     * Reads and returns the effective pom at the given path. Returns {@link Optional#empty()} if
     * the file does not exist or cannot be parsed.
     */
    public Optional<Pom> read(final Path pomXml) {
        if (!Files.exists(pomXml)) {
            return Optional.empty();
        }
        return Optional.ofNullable(readEffective(pomXml.toAbsolutePath().normalize(), new HashSet<>()));
    }

    /**
     * Returns {@code true} if the pom at the given path declares a direct {@code <parent>}
     * element, without resolving or reading that parent. Unlike {@link #read}, this is a cheap
     * structural check: it doesn't matter whether the declared parent is actually reachable
     * (e.g. a corporate parent that lives only in a remote repository).
     */
    public static boolean hasDeclaredParent(final Path pomXml) {
        try {
            final Document doc = newDocumentBuilderFactory().newDocumentBuilder().parse(pomXml.toFile());
            return !directChildren(doc.getDocumentElement(), "parent").isEmpty();
        } catch (final Exception e) {
            return false;
        }
    }

    private Pom readEffective(final Path pomPath,
                              final Set<Path> visited) {
        final Pom cached = this.cache.get(pomPath);
        if (cached != null) {
            return cached;
        }
        if (!visited.add(pomPath)) {
            // cycle in parent/BOM-import chain — log and treat as no-parent terminus
            this.recorder.warn("Cycle detected in pom parent/import chain at [%s]", pomPath);
            return null;
        }

        final Raw raw = parseRaw(pomPath);
        if (raw == null) {
            visited.remove(pomPath);
            return null;
        }

        final Optional<Pom> parent = resolveParent(pomPath, raw, visited);

        // effective properties: parent ⊕ own (own wins), computed BEFORE this pom's own
        // groupId/version so a ${revision}-style placeholder in <version> can be interpolated
        // against a property (e.g. `revision`) declared only in the parent's <properties>.
        final Map<String, String> effectiveProps = new LinkedHashMap<>();
        parent.ifPresent(p -> effectiveProps.putAll(p.properties()));
        effectiveProps.putAll(raw.properties);

        final String groupId = interpolate(
            raw.self.groupId() != null ? raw.self.groupId() : parent.map(Pom::groupId).orElse(""), effectiveProps);
        final String artifactId = interpolate(raw.self.artifactId(), effectiveProps);
        final String version = interpolate(
            raw.self.version() != null ? raw.self.version() : parent.map(Pom::version).orElse(""), effectiveProps);

        effectiveProps.put("project.groupId", groupId);
        effectiveProps.put("project.artifactId", artifactId);
        effectiveProps.put("project.version", version);
        effectiveProps.put("project.basedir", pomPath.getParent().toString());
        // Maven also recognizes the bare (unprefixed) built-in alongside "project.basedir".
        effectiveProps.put("basedir", pomPath.getParent().toString());
        // <build><directory> / <build><finalName>: this pom's own explicit value if it declares
        // one (Maven's defaults of <basedir>/target and ${artifactId}-${version} otherwise) --
        // seeded unconditionally (like project.basedir above), so a value inherited from the
        // parent's own effectiveProps merge above never wins over this pom's own directory. Plugin
        // config referencing ${project.build.directory} / ${project.build.finalName} (e.g.
        // maven-surefire-plugin's <argLine>) then interpolates correctly instead of being
        // forwarded to consumers as a literal, unresolved "${...}" token.
        final Path buildDir = raw.buildDirectory != null
            ? pomPath.getParent().resolve(interpolate(raw.buildDirectory, effectiveProps)).normalize()
            : pomPath.getParent().resolve("target");
        effectiveProps.put("project.build.directory", buildDir.toString());
        effectiveProps.put("project.build.finalName", raw.buildFinalName != null
            ? interpolate(raw.buildFinalName, effectiveProps)
            : artifactId + "-" + version);
        effectiveProps.put("settings.localRepository", this.localRepository.toString());
        parent.ifPresent(p -> {
            effectiveProps.put("project.parent.groupId", p.groupId());
            effectiveProps.put("project.parent.artifactId", p.artifactId());
            effectiveProps.put("project.parent.version", p.version());
        });

        // active profiles: their <properties> apply at lower precedence than the pom's own
        final List<RawProfile> activeProfiles = evaluateActiveProfiles(raw.profiles);
        for (final RawProfile prof : activeProfiles) {
            prof.properties().forEach(effectiveProps::putIfAbsent);
        }

        // this pom's own dependencyManagement (+ active profiles'): literal entries always take
        // precedence over BOM imports, which are merged afterward and never override an entry
        // already present (own-literal or an earlier-listed import)
        final Map<GA, Dependency> ownDepMgmt = new LinkedHashMap<>();
        mergeOwnManagedDeps(raw.dependencyManagement, effectiveProps, ownDepMgmt, visited);
        for (final RawProfile prof : activeProfiles) {
            mergeOwnManagedDeps(prof.dependencyManagement(), effectiveProps, ownDepMgmt, visited);
        }

        // effective depMgmt: parent ⊕ own — own always wins over whatever the parent declared,
        // regardless of how many ancestor levels are involved (each level's own recursively
        // resolved the same way, so this single overlay is sufficient).
        final Map<GA, Dependency> effectiveDepMgmt = new LinkedHashMap<>();
        parent.ifPresent(p -> effectiveDepMgmt.putAll(p.dependencyManagement()));
        effectiveDepMgmt.putAll(ownDepMgmt);

        // effective dependencies: own (+ active profiles') with version/scope/type/classifier
        // filled from effective depMgmt
        final List<Dependency> effectiveDependencies = new ArrayList<>(raw.dependencies.size());
        for (final RawDependency rd : raw.dependencies) {
            effectiveDependencies.add(toEffectiveDependency(rd, effectiveProps, effectiveDepMgmt));
        }
        for (final RawProfile prof : activeProfiles) {
            for (final RawDependency rd : prof.dependencies()) {
                effectiveDependencies.add(toEffectiveDependency(rd, effectiveProps, effectiveDepMgmt));
            }
        }

        // effective pluginMgmt: parent ⊕ own (own wins per GA, configurations deep-merged)
        final Map<GA, Plugin> effectivePluginMgmt = new LinkedHashMap<>();
        parent.ifPresent(p -> effectivePluginMgmt.putAll(p.pluginManagement()));
        for (final RawPlugin rp : raw.pluginManagement) {
            final Plugin own = toPlugin(rp, effectiveProps);
            final Plugin merged = mergePluginInto(effectivePluginMgmt.get(own.ga()), own);
            effectivePluginMgmt.put(merged.ga(), merged);
        }

        // effective plugins: parent ⊕ own (own wins per GA, configurations deep-merged), then
        // each plugin further deep-merged with its matching effective pluginManagement entry.
        // Parent inheritance honors Maven semantics: a parent's <build><plugins>... entries are
        // active in child poms unless overridden.
        final Map<GA, Plugin> pluginsByGa = new LinkedHashMap<>();
        parent.ifPresent(p -> p.plugins().forEach(pp -> pluginsByGa.put(pp.ga(), pp)));
        for (final RawPlugin rp : raw.plugins) {
            final Plugin own = toPlugin(rp, effectiveProps);
            pluginsByGa.put(own.ga(), mergePluginInto(pluginsByGa.get(own.ga()), own));
        }
        final List<Plugin> effectivePlugins = new ArrayList<>(pluginsByGa.size());
        for (final Plugin p : pluginsByGa.values()) {
            effectivePlugins.add(mergePluginInto(effectivePluginMgmt.get(p.ga()), p));
        }

        final Pom pom = new DefaultPom(
            Gav.of(groupId, artifactId, version),
            PackagingType.of(orDefault(raw.packaging, PackagingType.Standard.JAR.raw())),
            parent,
            Collections.unmodifiableMap(effectiveProps),
            Collections.unmodifiableMap(effectiveDepMgmt),
            Collections.unmodifiableList(effectiveDependencies),
            Collections.unmodifiableMap(effectivePluginMgmt),
            Collections.unmodifiableList(effectivePlugins));

        this.cache.put(pomPath, pom);
        visited.remove(pomPath);
        return pom;
    }

    /**
     * Merges one level's own {@code <dependencyManagement>} entries into {@code out}: literal
     * entries are applied directly (in document order, last one for a given GA wins), then BOM
     * imports are resolved via the {@link #locator} and merged with {@code putIfAbsent} so an
     * import never overrides a literal entry or an earlier-listed import.
     */
    private void mergeOwnManagedDeps(final List<RawDependency> rawManagedDeps,
                                     final Map<String, String> props,
                                     final Map<GA, Dependency> out,
                                     final Set<Path> visited) {
        final List<RawDependency> imports = new ArrayList<>();
        for (final RawDependency rd : rawManagedDeps) {
            if (PackagingType.Standard.POM.raw().equals(rd.type())
                && DependencyScope.IMPORT.mavenName().equals(rd.scope())) {
                imports.add(rd);
            } else {
                final Dependency dep = toEffectiveDependency(rd, props, Map.of());
                out.put(dep.ga(), dep);
            }
        }
        for (final RawDependency rd : imports) {
            final String bomGroupId = interpolate(rd.gav().groupId(), props);
            final String bomArtifactId = interpolate(rd.gav().artifactId(), props);
            final String bomVersion = interpolate(rd.gav().version(), props);
            if (bomVersion == null || bomVersion.contains("${")) {
                this.recorder.warn("Could not resolve BOM import version for [%s:%s]",
                    bomGroupId, bomArtifactId);
                continue;
            }
            this.locator.locate(Gav.of(bomGroupId, bomArtifactId, bomVersion)).ifPresentOrElse(bomPath -> {
                final Pom bom = readEffective(bomPath.toAbsolutePath().normalize(), visited);
                if (bom != null) {
                    bom.dependencyManagement().forEach(out::putIfAbsent);
                }
            }, () -> this.recorder.warn("Could not locate BOM import [%s:%s:%s]",
                bomGroupId, bomArtifactId, bomVersion));
        }
    }

    private Optional<Pom> resolveParent(final Path pomPath,
                                        final Raw raw,
                                        final Set<Path> visited) {
        if (raw.parent == null) {
            return Optional.empty();
        }
        if (this.allowRelativePath) {
            // try relativePath first (default ../pom.xml)
            final String relativePath = raw.parentRelativePath != null ? raw.parentRelativePath : "../pom.xml";
            if (!relativePath.isEmpty()) {
                Path candidate = pomPath.getParent().resolve(relativePath).normalize();
                if (Files.isDirectory(candidate)) {
                    candidate = candidate.resolve("pom.xml");
                }
                if (Files.exists(candidate)) {
                    final Pom parent = readEffective(candidate.toAbsolutePath().normalize(), visited);
                    if (parent != null) {
                        return Optional.of(parent);
                    }
                }
            }
        }
        // fall back to the locator (requires a resolved version; ${...} unresolved → skip)
        if (raw.parent.version() == null || raw.parent.version().contains("${")) {
            return Optional.empty();
        }
        return this.locator.locate(raw.parent)
            .map(repoPath -> readEffective(repoPath.toAbsolutePath().normalize(), visited));
    }

    // ----- raw parsing -------------------------------------------------------

    private static final class Raw {
        Gav self;
        String packaging;
        Gav parent;
        String parentRelativePath;
        Map<String, String> properties = Map.of();
        List<RawDependency> dependencies = List.of();
        List<RawDependency> dependencyManagement = List.of();
        List<RawPlugin> plugins = List.of();
        List<RawPlugin> pluginManagement = List.of();
        List<RawProfile> profiles = List.of();
        String buildDirectory;
        String buildFinalName;
    }

    private record RawDependency(Gav gav,
                                 String scope,
                                 String type,
                                 String classifier,
                                 boolean optional,
                                 Set<String> exclusions) {
    }

    private record RawPlugin(String groupId,
                             String artifactId,
                             String version,
                             ConfigNode configuration,
                             List<RawDependency> dependencies) {
    }

    private record RawProfile(
        boolean activeByDefault,
        String jdkActivation,
        String activationPropertyName,
        String activationPropertyValue,
        String osName,
        String osFamily,
        String osArch,
        String fileExists,
        String fileMissing,
        Map<String, String> properties,
        List<RawDependency> dependencyManagement,
        List<RawDependency> dependencies
    ) {
    }

    private Raw parseRaw(final Path pomPath) {
        try {
            final DocumentBuilder builder = newDocumentBuilderFactory().newDocumentBuilder();
            final Document doc = builder.parse(pomPath.toFile());
            final Element root = doc.getDocumentElement();
            final Raw raw = new Raw();

            raw.self = new Gav(
                directChildText(root, "groupId"),
                directChildText(root, "artifactId"),
                directChildText(root, "version"));
            raw.packaging = directChildText(root, "packaging");

            directChild(root, "parent").ifPresent(parentEl -> {
                raw.parent = new Gav(
                    directChildText(parentEl, "groupId"),
                    directChildText(parentEl, "artifactId"),
                    directChildText(parentEl, "version"));
                raw.parentRelativePath = directChildText(parentEl, "relativePath");
            });

            directChild(root, "properties").ifPresent(propsEl -> raw.properties = readProperties(propsEl));

            directChild(root, "dependencies").ifPresent(depsEl -> raw.dependencies = readDependencies(depsEl));

            directChild(root, "dependencyManagement").flatMap(dmEl -> directChild(dmEl, "dependencies"))
                .ifPresent(depsEl -> raw.dependencyManagement = readDependencies(depsEl));

            directChild(root, "build").ifPresent(buildEl -> {
                directChild(buildEl, "plugins").ifPresent(pluginsEl -> raw.plugins = readPlugins(pluginsEl));
                directChild(buildEl, "pluginManagement").flatMap(pmEl -> directChild(pmEl, "plugins"))
                    .ifPresent(pluginsEl -> raw.pluginManagement = readPlugins(pluginsEl));
                raw.buildDirectory = directChildText(buildEl, "directory");
                raw.buildFinalName = directChildText(buildEl, "finalName");
            });

            raw.profiles = readProfiles(root);

            return raw;
        } catch (final Exception e) {
            this.recorder.warn(e, "PomReader failed to parse [%s]", pomPath);
            return null;
        }
    }

    private static Map<String, String> readProperties(final Element propsEl) {
        final Map<String, String> props = new LinkedHashMap<>();
        for (final Element child : directChildElements(propsEl)) {
            final String text = child.getTextContent();
            props.put(child.getTagName(), text == null ? "" : text.trim());
        }
        return props;
    }

    private static List<RawDependency> readDependencies(final Element parent) {
        final List<RawDependency> out = new ArrayList<>();
        for (final Element dep : directChildren(parent, "dependency")) {
            final String groupId = directChildText(dep, "groupId");
            final String artifactId = directChildText(dep, "artifactId");
            if (groupId == null || artifactId == null) {
                continue;
            }
            final boolean optional = Boolean.parseBoolean(directChildText(dep, "optional"));
            final Set<String> exclusions = new LinkedHashSet<>();
            for (final Element exclusionsEl : directChildren(dep, "exclusions")) {
                for (final Element exclusionEl : directChildren(exclusionsEl, "exclusion")) {
                    final String eg = directChildText(exclusionEl, "groupId");
                    final String ea = directChildText(exclusionEl, "artifactId");
                    if (eg != null && ea != null) {
                        exclusions.add(eg + ":" + ea);
                    }
                }
            }
            out.add(new RawDependency(
                new Gav(groupId, artifactId, directChildText(dep, "version")),
                directChildText(dep, "scope"),
                directChildText(dep, "type"),
                directChildText(dep, "classifier"),
                optional,
                exclusions));
        }
        return out;
    }

    private static List<RawProfile> readProfiles(final Element root) {
        final List<RawProfile> out = new ArrayList<>();
        for (final Element profilesEl : directChildren(root, "profiles")) {
            for (final Element profileEl : directChildren(profilesEl, "profile")) {
                out.add(readProfile(profileEl));
            }
        }
        return out;
    }

    private static RawProfile readProfile(final Element profileEl) {
        boolean activeByDefault = false;
        String jdk = null;
        String propName = null;
        String propValue = null;
        String osName = null;
        String osFamily = null;
        String osArch = null;
        String fileExists = null;
        String fileMissing = null;

        for (final Element activationEl : directChildren(profileEl, "activation")) {
            activeByDefault = Boolean.parseBoolean(directChildText(activationEl, "activeByDefault"));
            jdk = directChildText(activationEl, "jdk");
            for (final Element propEl : directChildren(activationEl, "property")) {
                propName = directChildText(propEl, "name");
                propValue = directChildText(propEl, "value");
            }
            for (final Element osEl : directChildren(activationEl, "os")) {
                osName = directChildText(osEl, "name");
                osFamily = directChildText(osEl, "family");
                osArch = directChildText(osEl, "arch");
            }
            for (final Element fileEl : directChildren(activationEl, "file")) {
                fileExists = directChildText(fileEl, "exists");
                fileMissing = directChildText(fileEl, "missing");
            }
        }

        Map<String, String> properties = Map.of();
        for (final Element propsEl : directChildren(profileEl, "properties")) {
            properties = readProperties(propsEl);
        }

        List<RawDependency> deps = List.of();
        for (final Element depsEl : directChildren(profileEl, "dependencies")) {
            deps = readDependencies(depsEl);
        }

        List<RawDependency> depMgmt = List.of();
        for (final Element dmEl : directChildren(profileEl, "dependencyManagement")) {
            for (final Element depsEl : directChildren(dmEl, "dependencies")) {
                depMgmt = readDependencies(depsEl);
            }
        }

        return new RawProfile(activeByDefault, jdk, propName, propValue,
            osName, osFamily, osArch, fileExists, fileMissing, properties, depMgmt, deps);
    }

    private static List<RawPlugin> readPlugins(final Element parent) {
        final List<RawPlugin> out = new ArrayList<>();
        for (final Element plugin : directChildren(parent, "plugin")) {
            final String artifactId = directChildText(plugin, "artifactId");
            if (artifactId == null) {
                continue;
            }
            final String groupId = directChildText(plugin, "groupId");
            // Maven default plugin groupId
            final String effectiveGroupId = groupId != null ? groupId : "org.apache.maven.plugins";
            ConfigNode configuration = ConfigNode.empty();
            for (final Element configEl : directChildren(plugin, "configuration")) {
                configuration = toConfigNode(configEl);
                break;
            }
            List<RawDependency> dependencies = List.of();
            for (final Element depsEl : directChildren(plugin, "dependencies")) {
                dependencies = readDependencies(depsEl);
            }
            out.add(new RawPlugin(effectiveGroupId,
                artifactId,
                directChildText(plugin, "version"),
                configuration,
                dependencies));
        }
        return out;
    }

    private static ConfigNode toConfigNode(final Element element) {
        final Map<String, String> attrs = new LinkedHashMap<>();
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            final Node attr = element.getAttributes().item(i);
            attrs.put(attr.getNodeName(), attr.getNodeValue());
        }
        final List<Element> childElements = directChildElements(element);
        if (childElements.isEmpty()) {
            final String text = element.getTextContent();
            final Optional<String> textOpt = (text == null || text.isBlank())
                ? Optional.empty()
                : Optional.of(text.trim());
            return new DefaultConfigNode(element.getTagName(), attrs, textOpt, List.of());
        }
        final List<ConfigNode> children = new ArrayList<>(childElements.size());
        for (final Element child : childElements) {
            children.add(toConfigNode(child));
        }
        return new DefaultConfigNode(element.getTagName(), attrs, Optional.empty(), children);
    }

    // ----- effective construction --------------------------------------------

    /**
     * Builds the effective dependency for one {@code <dependency>} entry, filling version/scope/
     * type/classifier from the matching {@code dependencyManagement} entry (by GA, if any) wherever
     * the raw XML left that field unset. Pass {@link Map#of()} for {@code mgmt} to build a literal
     * dependencyManagement entry itself, with nothing to fall back to.
     * <p>
     * This checks the <em>raw, pre-default</em> scope/type text for {@code null} rather than
     * comparing an already-defaulted value to {@code "compile"}/{@code "jar"} — so a dependency
     * that explicitly declares {@code <scope>compile</scope>} (or {@code <type>jar</type>})
     * correctly keeps that explicit value instead of being overridden by a differing managed
     * scope/type, matching real Maven's "explicit on the dependency always wins over
     * dependencyManagement" semantics.
     */
    private Dependency toEffectiveDependency(final RawDependency rd,
                                             final Map<String, String> props,
                                             final Map<GA, Dependency> mgmt) {
        final String groupId = interpolate(rd.gav().groupId(), props);
        final String artifactId = interpolate(rd.gav().artifactId(), props);
        final Dependency managed = mgmt.get(new GA(groupId, artifactId));

        final Optional<String> version = optInterpolated(rd.gav().version(), props)
            .or(() -> managed == null ? Optional.empty() : managed.version());
        final DependencyScope scope = rd.scope() != null
            ? parseScope(orDefault(interpolate(rd.scope(), props), DependencyScope.COMPILE.mavenName()),
                groupId, artifactId)
            : (managed == null ? DependencyScope.COMPILE : managed.scope());
        final PackagingType type = rd.type() != null
            ? PackagingType.of(orDefault(interpolate(rd.type(), props), PackagingType.Standard.JAR.raw()))
            : (managed == null ? PackagingType.Standard.JAR : managed.type());
        final Optional<String> classifier = optInterpolated(rd.classifier(), props)
            .or(() -> managed == null ? Optional.empty() : managed.classifier());

        return new DefaultDependency(new GA(groupId, artifactId), version, scope, type, classifier,
            rd.optional(), rd.exclusions());
    }

    /**
     * Parses an effective (already-interpolated) {@code <scope>} value, defaulting to
     * {@link DependencyScope#COMPILE} and logging a warning if it isn't one of Maven's six scope
     * literals — e.g. an unresolved {@code ${...}} property left over from a failed interpolation.
     * Unlike {@link PackagingType}, {@link DependencyScope} has no open/{@code Custom} case, so a
     * bad value here must be defaulted rather than thrown, matching this reader's convention
     * elsewhere of tolerating malformed input instead of failing the whole pom read.
     */
    private DependencyScope parseScope(final String text,
                                       final String groupId,
                                       final String artifactId) {
        try {
            return DependencyScope.of(text);
        } catch (final IllegalArgumentException e) {
            this.recorder.warn("Invalid scope [%s] on dependency [%s:%s], defaulting to [%s]",
                text, groupId, artifactId, DependencyScope.COMPILE.mavenName());
            return DependencyScope.COMPILE;
        }
    }

    private Plugin toPlugin(final RawPlugin rp,
                            final Map<String, String> props) {
        final String groupId = interpolate(rp.groupId(), props);
        final String artifactId = interpolate(rp.artifactId(), props);
        final Optional<String> version = optInterpolated(rp.version(), props);
        final ConfigNode interpolated = interpolateConfig(rp.configuration(), props);
        final List<Dependency> dependencies = rp.dependencies().stream()
            .map(rd -> toEffectiveDependency(rd, props, Map.of()))
            .toList();
        return new DefaultPlugin(new GA(groupId, artifactId), version, interpolated, dependencies);
    }

    /**
     * Merges an own plugin (or pluginMgmt entry) over a parent/mgmt entry, deep-merging
     * configuration and merging dependencies by {@link GA} (own wins per coordinate, parent-only
     * entries kept; a field the own entry leaves unset falls back to the parent/mgmt entry's value
     * — see {@link #mergeDependency}). Returns own if mgmt is null (no parent or mgmt match).
     */
    private static Plugin mergePluginInto(final Plugin parent,
                                          final Plugin own) {
        if (parent == null) {
            return own;
        }
        final Map<GA, Dependency> depsByGa = new LinkedHashMap<>();
        parent.dependencies().forEach(d -> depsByGa.put(d.ga(), d));
        for (final Dependency ownDep : own.dependencies()) {
            final Dependency managed = depsByGa.get(ownDep.ga());
            depsByGa.put(ownDep.ga(), managed == null ? ownDep : mergeDependency(managed, ownDep));
        }
        return new DefaultPlugin(
            own.ga(),
            own.version().or(parent::version),
            mergeConfig(parent.configuration(), own.configuration()),
            List.copyOf(depsByGa.values()));
    }

    /**
     * Fills in a plugin dependency's {@code version} and {@code classifier} from the matching
     * {@code parent}/{@code pluginManagement} entry when {@code own} leaves them unset — e.g. a
     * plugin {@code <dependency>} redeclared by GA only, to inherit its version from
     * {@code <pluginManagement>}, as real Maven does. Unlike {@link #toEffectiveDependency}, this
     * can't disambiguate an explicit {@code scope}/{@code type} from Maven's own default (both
     * arguments are already-effective {@link Dependency}s, so the pre-default raw text is gone by
     * this point), so those two fields stay own-wins-always as before.
     */
    private static Dependency mergeDependency(final Dependency parent,
                                              final Dependency own) {
        return new DefaultDependency(
            own.ga(),
            own.version().or(parent::version),
            own.scope(),
            own.type(),
            own.classifier().or(parent::classifier),
            own.optional(),
            own.exclusions());
    }

    /**
     * Deep-merges two configuration nodes: own wins per child element name, recursively. If own
     * is empty, parent is returned. If parent is empty, own is returned. Attributes and text are
     * own-wins-when-present.
     */
    private static ConfigNode mergeConfig(final ConfigNode parent,
                                          final ConfigNode own) {
        if (parent.name().isEmpty()) {
            return own;
        }
        if (own.name().isEmpty()) {
            return parent;
        }
        final Map<String, String> attrs = new LinkedHashMap<>(parent.attributes());
        attrs.putAll(own.attributes());
        final Optional<String> text = own.text().or(parent::text);
        // child merge by name: own takes precedence; same-named children are recursively merged
        final Map<String, ConfigNode> byName = new LinkedHashMap<>();
        for (final ConfigNode pc : parent.children()) {
            byName.put(pc.name(), pc);
        }
        for (final ConfigNode oc : own.children()) {
            final ConfigNode existing = byName.get(oc.name());
            byName.put(oc.name(), existing == null ? oc : mergeConfig(existing, oc));
        }
        return new DefaultConfigNode(own.name(), attrs, text, List.copyOf(byName.values()));
    }

    // ----- profile activation --------------------------------------------------

    private static List<RawProfile> evaluateActiveProfiles(final List<RawProfile> profiles) {
        final List<RawProfile> explicit = profiles.stream()
            .filter(p -> !p.activeByDefault() && isProfileActivated(p))
            .toList();
        if (!explicit.isEmpty()) {
            return explicit;
        }
        return profiles.stream().filter(RawProfile::activeByDefault).toList();
    }

    private static boolean isProfileActivated(final RawProfile p) {
        boolean hasCondition = false;
        if (p.jdkActivation() != null) {
            hasCondition = true;
            if (!matchesJdk(p.jdkActivation())) {
                return false;
            }
        }
        if (p.activationPropertyName() != null) {
            hasCondition = true;
            final String val = System.getProperty(p.activationPropertyName());
            if (p.activationPropertyValue() != null
                ? !p.activationPropertyValue().equals(val) : val == null) {
                return false;
            }
        }
        if (p.osName() != null) {
            hasCondition = true;
            if (!System.getProperty("os.name", "").toLowerCase()
                .contains(p.osName().toLowerCase())) {
                return false;
            }
        }
        if (p.osFamily() != null) {
            hasCondition = true;
            if (!matchesOsFamily(p.osFamily())) {
                return false;
            }
        }
        if (p.osArch() != null) {
            hasCondition = true;
            if (!System.getProperty("os.arch", "").toLowerCase()
                .contains(p.osArch().toLowerCase())) {
                return false;
            }
        }
        if (p.fileExists() != null) {
            hasCondition = true;
            if (!Files.exists(Path.of(p.fileExists()))) {
                return false;
            }
        }
        if (p.fileMissing() != null) {
            hasCondition = true;
            if (Files.exists(Path.of(p.fileMissing()))) {
                return false;
            }
        }
        return hasCondition;
    }

    private static boolean matchesJdk(final String spec) {
        final String javaVersion = System.getProperty("java.version", "");
        if (spec.startsWith("!")) {
            return !matchesJdkPositive(spec.substring(1), javaVersion);
        }
        if (spec.startsWith("[") || spec.startsWith("(")) {
            return matchesVersionRange(spec, javaVersion.split("[.\\-+]")[0]);
        }
        return matchesJdkPositive(spec, javaVersion);
    }

    private static boolean matchesJdkPositive(final String spec, final String javaVersion) {
        final String major = javaVersion.split("[.\\-+]")[0];
        return javaVersion.startsWith(spec) || major.equals(spec);
    }

    private static boolean matchesVersionRange(final String range, final String version) {
        try {
            final boolean lowerInclusive = range.startsWith("[");
            final boolean upperInclusive = range.endsWith("]");
            final String inner = range.substring(1, range.length() - 1);
            final String[] bounds = inner.split(",", 2);
            final int v = Integer.parseInt(version);
            final int lower = bounds[0].trim().isEmpty()
                ? Integer.MIN_VALUE : Integer.parseInt(bounds[0].trim());
            final int upper = bounds.length < 2 || bounds[1].trim().isEmpty()
                ? Integer.MAX_VALUE : Integer.parseInt(bounds[1].trim());
            return (lowerInclusive ? v >= lower : v > lower)
                && (upperInclusive ? v <= upper : v < upper);
        } catch (final NumberFormatException e) {
            return false;
        }
    }

    private static boolean matchesOsFamily(final String family) {
        final OperatingSystem.Kind kind = OperatingSystem.detect().kind();
        return switch (family.toLowerCase()) {
            case "windows" -> kind == OperatingSystem.Kind.WINDOWS;
            case "mac" -> kind == OperatingSystem.Kind.MAC;
            case "unix", "linux" -> kind == OperatingSystem.Kind.UNIX
                || kind == OperatingSystem.Kind.POSIX
                || kind == OperatingSystem.Kind.MAC;
            default -> System.getProperty("os.name", "").toLowerCase().contains(family.toLowerCase());
        };
    }

    // ----- interpolation -----------------------------------------------------

    /**
     * Replaces {@code ${prop}} references with their resolved values. Coord-style references
     * matching {@link #COORD_REF} are passed through unchanged. Unresolved property references
     * are also passed through unchanged.
     * <p>
     * Properties commonly reference other properties (e.g. a BOM's
     * {@code <jackson.version.annotations>${jackson.version}</jackson.version.annotations>}), so a
     * single pass can leave a nested reference unresolved even though every property in the chain is
     * individually known. This re-scans its own output up to 5 times, stopping as soon as a pass
     * makes no further change.
     */
    static String interpolate(final String value,
                              final Map<String, String> props) {
        if (value == null || value.indexOf('$') < 0) {
            return value;
        }
        String result = value;
        for (int i = 0; i < 5 && result.indexOf('$') >= 0; i++) {
            final String next = interpolateOnce(result, props);
            if (next.equals(result)) {
                break;
            }
            result = next;
        }
        return result;
    }

    private static String interpolateOnce(final String value,
                                          final Map<String, String> props) {
        final Matcher m = PROPERTY_REF.matcher(value);
        final StringBuilder out = new StringBuilder();
        while (m.find()) {
            final String full = m.group(0);
            // Skip coord-style refs; they belong to the consumer, not pom-property interpolation.
            if (COORD_REF.matcher(full).matches()) {
                m.appendReplacement(out, Matcher.quoteReplacement(full));
                continue;
            }
            final String key = m.group(1);
            final String resolved = props.get(key);
            m.appendReplacement(out, Matcher.quoteReplacement(resolved != null ? resolved : full));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static Optional<String> optInterpolated(final String value,
                                                    final Map<String, String> props) {
        if (value == null) {
            return Optional.empty();
        }
        final String resolved = interpolate(value, props);
        return (resolved == null || resolved.isEmpty()) ? Optional.empty() : Optional.of(resolved);
    }

    private static ConfigNode interpolateConfig(final ConfigNode node,
                                                final Map<String, String> props) {
        if (node.name().isEmpty()) {
            return node;
        }
        final Optional<String> text = node.text().map(t -> interpolate(t, props));
        final List<ConfigNode> children = node.children().stream()
            .map(c -> interpolateConfig(c, props))
            .toList();
        return new DefaultConfigNode(node.name(), node.attributes(), text, children);
    }

    // ----- xml helpers (local to keep this class self-contained) -------------

    private static DocumentBuilderFactory newDocumentBuilderFactory() throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static List<Element> directChildren(final Element parent,
                                                final String tagName) {
        final List<Element> out = new ArrayList<>();
        final NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child && child.getTagName().equals(tagName)) {
                out.add(child);
            }
        }
        return out;
    }

    /**
     * Returns the first direct child element with the given tag name, if any. Per the Maven POM
     * schema each of these container elements (e.g. {@code <parent>}, {@code <properties>},
     * {@code <dependencyManagement>}) can appear at most once, so callers only ever want the first.
     */
    private static Optional<Element> directChild(final Element parent,
                                                 final String tagName) {
        final List<Element> children = directChildren(parent, tagName);
        return children.isEmpty() ? Optional.empty() : Optional.of(children.get(0));
    }

    private static List<Element> directChildElements(final Element parent) {
        final List<Element> out = new ArrayList<>();
        final NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                out.add(child);
            }
        }
        return out;
    }

    private static String directChildText(final Element parent,
                                          final String tagName) {
        for (final Element child : directChildren(parent, tagName)) {
            final String text = child.getTextContent();
            return (text == null || text.isBlank()) ? null : text.trim();
        }
        return null;
    }

    private static String orDefault(final String value,
                                    final String defaultValue) {
        return (value == null || value.isEmpty()) ? defaultValue : value;
    }

    /**
     * The standard Maven local repo layout:
     * {@code <localRepo>/<groupId-as-path>/<artifactId>/<version>/<artifactId>-<version>.pom}
     */
    public static Optional<Path> localRepoPomPath(final Path localRepository,
                                                  final Gav gav) {
        final Path pomPath = localRepository
            .resolve(gav.groupId().replace('.', '/'))
            .resolve(gav.artifactId())
            .resolve(gav.version())
            .resolve(gav.artifactId() + "-" + gav.version() + ".pom");
        return Files.exists(pomPath) ? Optional.of(pomPath) : Optional.empty();
    }
}
