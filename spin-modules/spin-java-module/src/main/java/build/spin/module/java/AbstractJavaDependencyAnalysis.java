package build.spin.module.java;

/*-
 * #%L
 * Spin Java Module
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

import build.base.configuration.ConfigurationBuilder;
import build.base.flow.RecordingSubscriber;
import build.base.foundation.Strings;
import build.base.foundation.stream.Streams;
import build.base.io.PathSetBuilder;
import build.base.option.JDKVersion;
import build.base.table.Table;
import build.base.telemetry.TelemetryRecorder;
import build.base.version.Version;
import build.base.version.VersionOrder;
import build.codemodel.foundation.descriptor.RequiresModuleDescriptor;
import build.codemodel.jdk.descriptor.JDKModuleDescriptor;
import build.codemodel.jdk.descriptor.RequiresModifier;
import build.percolate.core.ModuleGraphClassifier;
import build.spawn.application.Application;
import build.spawn.application.option.Argument;
import build.spawn.application.option.Name;
import build.spawn.application.option.StandardOutputSubscriber;
import build.spawn.platform.local.LocalMachine;
import build.spin.Invocable;
import build.spin.Project;
import build.spin.Reference;
import build.spin.Task;
import build.spin.Workspace;
import build.spin.annotation.System;
import build.spin.common.JDKTools;
import build.spin.common.ProcessFailedException;
import build.spin.common.ProcessRunner;
import build.spin.module.clean.CleanPlugin;
import build.spin.module.modulesystem.Artifact;
import build.spin.module.modulesystem.ArtifactDescriptor;
import build.spin.module.modulesystem.ModuleCatalog;
import build.spin.module.modulesystem.ModuleReference;
import build.spin.module.modulesystem.ModuleVersioning;
import jakarta.inject.Inject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Stack;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An abstract {@link Task} to perform Java Dependency Analysis using the Java Platform
 * <a href="https://docs.oracle.com/en/java/javase/25/docs/specs/man/jdeps.html">jdeps</a> tool
 * on the compiled and packaged {@link Artifact} for a {@link Project}.
 *
 * @author brian.oliver
 * @since Jan-2023
 */
public abstract class AbstractJavaDependencyAnalysis
    implements Task<DependencyAnalysis> {

    @Inject
    private TelemetryRecorder recorder;

    @Inject
    private JavaPlatform platform;

    @Inject
    private LocalMachine machine;

    @Inject
    private Workspace workspace;

    @Inject
    private JDKModuleDescriptor moduleDescriptor;

    @Inject
    private ModuleCatalog catalog;

    @Inject
    private ModuleVersioning versioning;

    @Inject
    @System
    private JDKVersion systemJavaVersion;

    @Inject
    private Artifact.Resolver artifactResolver;

    @Override
    public Stream<Reference> dependencies() {
        // locating each project's PackageModule task doesn't depend on which `requires` clause is
        // being resolved -- compute it once per project, not once per (requires clause, project)
        // pair, to avoid re-scanning every project's invocables() once per requires clause.
        final Map<Project, Reference> packageModuleReferences = new LinkedHashMap<>();
        this.workspace.stream().forEach(project -> project.getInvocables(PackageModule.class)
            .findFirst()
            .map(Invocable::getTaskClass)
            .ifPresent(taskClass -> packageModuleReferences.put(project, Reference.of(project, taskClass))));

        return this.moduleDescriptor.requiresClauses()
            .map(r -> r.requiresModuleName().toString())
            .flatMap(name -> this.workspace.stream()
                .filter(project -> JavaCompilerPlugin
                    .resolveRequiredCompilerPlugin(project, name, this.moduleDescriptor)
                    .optional()
                    .isPresent())
                .map(packageModuleReferences::get)
                .filter(Objects::nonNull));
    }

    /**
     * Perform {@code jdeps} dependency analysis on the packaged module.
     *
     * @param buildPath the build path for the {@link Project}
     * @param descriptors the {@link ArtifactDescriptor}s for {@link Project} dependencies in the {@link Workspace}
     *
     * @return the {@link DependencyAnalysis} on the packaged module
     * @throws Exception should the {@link Task} execution fail
     */
    public DependencyAnalysis jdeps(final Path buildPath,
                                    final Stream<ArtifactDescriptor> descriptors)
        throws Exception {

        final var jdk = this.platform.getVersion(this.systemJavaVersion.major())
            .orElseThrow(() -> {
                final var jdks = this.platform.stream().toList();
                return new RuntimeException("Failed to obtain Java Development Kit for Java " + this.systemJavaVersion.major() + ". Available Java Development Kits: " + jdks);
            });
        final var javaHome = jdk.home().path();

        // determine a Version for the Java Development Kit Modules
        final Version jdkVersion = Version.parse(jdk.version().get());

        // -----
        // obtain the non-Java Platform artifacts transitively, inside and outside the Workspace
        // (so we can put them on a classpath/modulepath for analysis)
        final var pending = new Stack<ModuleReference>();
        // Track processed modules by name → version so a higher-version encounter triggers
        // re-processing (the lower-version jar's transitive deps may differ from the higher one).
        final var processed = new LinkedHashMap<String, Optional<Version>>();
        final var ignored = new LinkedHashSet<String>();

        final var platformModules = new LinkedHashSet<ModuleReference>();
        final var artifactDescriptors = new LinkedHashMap<Artifact, ArtifactDescriptor>();
        final var moduleDescriptors = new LinkedHashMap<ModuleReference, JDKModuleDescriptor>();
        final var requiredModules = new LinkedHashSet<String>();
        final var unnamedArtifactDescriptors = new LinkedHashMap<Artifact, ArtifactDescriptor>();

        // initialize with ArtifactDescriptors using the dependency ArtifactDescriptors
        descriptors
            .forEach(descriptor -> artifactDescriptors.put(descriptor.artifact(), descriptor));

        // start with the ModuleDescriptor for this project
        pending.push(ModuleReference.of(this.moduleDescriptor.moduleName().toString(),
            this.moduleDescriptor.version()));

        while (!pending.isEmpty()) {
            final var module = pending.pop();

            if (shouldProcess(module.name(), module.version(), processed) && !ignored.contains(module.name())) {

                // ensure the ModuleReference has a Version with which we can resolve (when required)
                final ModuleReference reference = module.version().isPresent()
                    ? module
                    : ModuleReference.of(module.name(), this.versioning.getVersion(module.name()));

                // obtain the artifact for the module reference
                // (first try to find using the ArtifactDescriptors we've been provided with)
                final var artifact = artifactDescriptors.values().stream()
                    .filter(descriptor -> descriptor.reference().equals(reference))
                    .findFirst()
                    .map(ArtifactDescriptor::artifact)
                    .orElseGet(() -> this.catalog
                        .getArtifact(reference, Optional.of(this.recorder))
                        .orElseThrow(() -> new RuntimeException("Failed to determine Artifact for " + module)));

                // resolve the ArtifactDescriptor for the Artifact
                // (this will only happen for "external" Artifacts)
                if (!artifactDescriptors.containsKey(artifact)) {
                    final var artifactDescriptor = ArtifactDescriptor.create(reference, artifact,
                        this.artifactResolver.resolve(artifact)
                            .orElseThrow(() -> new IllegalStateException("Failed to resolve artifact [" + artifact + "] for module [" + reference + "]")));

                    artifactDescriptors.put(artifact, artifactDescriptor);
                }

                // obtain the ArtifactDescriptor
                final var artifactDescriptor = artifactDescriptors.get(artifact);
                if (artifactDescriptor == null) {
                    throw new RuntimeException("Failed to determine ArtifactDescriptor for " + artifact);
                }

                this.workspace.stream()
                    .filter(project -> project
                        .getPlugin(JavaCompilerPlugin.class)
                        .filter(plugin -> plugin.getModuleDescriptor().moduleName().toString().equals(reference.name()))
                        .isPresent())
                    .map(project -> project.getPlugin(JavaCompilerPlugin.class)
                        .map(JavaPlugin::getModuleDescriptor)
                        .orElseThrow(() -> new IllegalStateException("Expected JavaCompilerPlugin to have a ModuleDescriptor for module [" + reference + "] in project [" + project.name() + "]")))
                    .findFirst()
                    .or(() -> this.artifactResolver.getModuleDescriptor(artifact, this.catalog, this.versioning).optional())
                    .map(moduleDescriptor -> {
                        moduleDescriptors.put(reference, moduleDescriptor);
                        processed.put(reference.name(), reference.version());

                        // TODO: correct the module reference if it's name is different!
                        // (eg: asm-7.2 has a different jar name but the same module name as asm-9.4!)

                        // push the non-Java Platform required modules onto the stack for processing
                        moduleDescriptor.requiresClauses()
                            .filter(r -> r.traits(RequiresModifier.class).noneMatch(m -> m == RequiresModifier.STATIC))
                            .peek(r -> {
                                if (JavaPlatform.isJavaPlatformModule(r.requiresModuleName().toString())) {
                                    platformModules.add(ModuleReference.of(r.requiresModuleName().toString(), jdkVersion));
                                }
                            })
                            .filter(r -> !JavaPlatform.isJavaPlatformModule(r.requiresModuleName().toString()))
                            .map(r -> {
                                final String name = r.requiresModuleName().toString();
                                final Optional<Version> version = resolveRequiredVersion(
                                    r, name, moduleDescriptor.moduleName().toString(),
                                    this.versioning, artifactDescriptors.values(), this.recorder);
                                return ModuleReference.of(name, version);
                            })
                            .peek(r -> {
                                // track all required module names for module-path placement
                                // unconditionally — even if already processed. A module that was
                                // processed before its dependant ran would otherwise never get
                                // added to requiredModules, causing it to land on classpath
                                // instead of module-path (the graphql-java-kickstart bug).
                                requiredModules.add(r.name());
                            })
                            .filter(r -> shouldProcess(r.name(), r.version(), processed))
                            .peek(r -> this.recorder.info("Module [%s] requires [%s] — queuing for catalog lookup", moduleDescriptor.moduleName().toString(), r))
                            .forEach(pending::push);

                        return reference;
                    })
                    .orElseGet(() -> {
                        final var resolvedDescriptor = this.artifactResolver.getModuleDescriptor(artifact, this.catalog, this.versioning);
                        if (resolvedDescriptor.isException()) {
                            final String reason = resolvedDescriptor.exception()
                                .map(e -> ": " + e.getClass().getSimpleName() + ": " + e.getMessage())
                                .orElse("");
                            this.recorder.info("Ignoring module [%s] — no ModuleDescriptor available%s", reference, reason);
                        } else {
                            // no module-info.class and no Automatic-Module-Name: genuinely unnamed jar
                            unnamedArtifactDescriptors.put(artifact, artifactDescriptor);
                        }
                        ignored.add(reference.name());
                        return reference;
                    });
            }
        }

        // -----
        // First dedupe pass — by Maven coordinates (groupId + artifactId), newest version
        // wins. This catches the case where two versions of the same Maven artifact have
        // *different* JPMS module names: for example, slf4j-api-1.7.25 is automatic module
        // `slf4j.api` (filename-derived) while slf4j-api-2.0.17 is the proper JPMS module
        // `org.slf4j`. Both own package `org.slf4j`, so any ModuleFinder-based classifier
        // downstream would reject them as a split package. The module-name dedupe below
        // can't catch these because the names differ; the filename-based detection in
        // {@link AbstractCompile#resolveConflicts} can, but only after a package-overlap
        // scan. Keying directly on Maven coordinates is the strongest signal available.
        final var artifactDescriptorsByCoordinates = dedupeByMavenCoordinates(
            artifactDescriptors.values(),
            (kept, dropped) -> this.recorder.info(
                "[jdeps] Maven coordinate duplicate [%s:%s]: keeping [%s], dropping [%s]",
                kept.artifact().groupId(), kept.artifact().artifactId(),
                kept.artifact().version(), dropped.artifact().version()));

        // -----
        // Second dedupe pass — by JPMS module name. Keeps the highest version when two
        // different (groupId, artifactId) entries map to the same module name.
        // (jdeps will fail when duplicate modules are discovered)

        final var artifactDescriptorsByModuleName = new LinkedHashMap<String, ArtifactDescriptor>();

        artifactDescriptorsByCoordinates.forEach(descriptor -> {
            final var moduleName = descriptor.reference().name();
            final var existingDescriptor = artifactDescriptorsByModuleName.get(moduleName);

            if (existingDescriptor == null) {
                artifactDescriptorsByModuleName.put(moduleName, descriptor);
            }
            else {
                if (existingDescriptor.reference().version().isPresent()) {
                    if (descriptor.reference().version().isPresent()) {
                        final var existingVersion = existingDescriptor.reference().version().orElseThrow(() -> new IllegalStateException("Impossible: version.isPresent() was true but orElseThrow failed for [" + existingDescriptor.reference() + "]"));
                        final var version = descriptor.reference().version().orElseThrow(() -> new IllegalStateException("Impossible: version.isPresent() was true but orElseThrow failed for [" + descriptor.reference() + "]"));

                        if (version.compareTo(existingVersion) > 0) {
                            // override with the higher version
                            artifactDescriptorsByModuleName.put(moduleName, descriptor);
                        }
                        else {
                            // we discovered a duplicate, but we're ignoring the lower version
                        }

                    }
                    else {
                        // we discovered a duplicate, but the duplicate doesn't have a version, so we ignore it
                    }
                }
                else {
                    // default to using the versioned ArtifactDescriptor (when the existing doesn't have a version)
                    artifactDescriptorsByModuleName.put(moduleName, descriptor);
                }
            }
        });

        // establish the ClassPathBuilder based on the ArtifactDescriptors for non-modules (that aren't required modules)!
        // (the explicit and required modules will be placed on the module path!)
        final var classPathBuilder = PathSetBuilder.create();
        artifactDescriptorsByModuleName.values().stream()
            .filter(descriptor -> !ignored.contains(descriptor.reference().name()))
            .filter(descriptor -> {
                final var md = moduleDescriptors.get(descriptor.reference());
                return md != null && md.isAutomatic()
                    && !requiredModules.contains(descriptor.reference().name())
                    // workspace-local jars with module-info.class go to the module-path, not here
                    && !descriptor.path().map(ModuleGraphClassifier::isNamedModule).orElse(false);
            })
            .forEach(descriptor -> descriptor.path().ifPresent(classPathBuilder::add));

        // -----
        // copy the explicit modules into a module path
        final var modulePath = buildPath.resolve("modules/");
        CleanPlugin.delete(modulePath);

        // create the module path
        Files.createDirectories(modulePath);

        // copy the artifacts into the modules path
        // -----
        // detect split packages BEFORE copying to module-path.
        // scan all candidate module-path jars for their packages; any jar that shares
        // a package with another jar must stay on classpath (JPMS forbids split packages).
        final var modulePathCandidates = new ArrayList<ArtifactDescriptor>();
        artifactDescriptorsByModuleName.values().stream()
            .filter(descriptor -> {
                final String name = descriptor.reference().name();
                if (ignored.contains(name)) {
                    // include ignored modules that are explicitly required — jdeps
                    // treats them as automatic modules by filename
                    return requiredModules.contains(name);
                }
                final var md = moduleDescriptors.get(descriptor.reference());
                return md != null && (!md.isAutomatic()
                    || requiredModules.contains(name)
                    || descriptor.path().map(ModuleGraphClassifier::isNamedModule).orElse(false));
            })
            .forEach(modulePathCandidates::add);

        final List<Path> candidatePaths = modulePathCandidates.stream()
            .flatMap(d -> d.path().stream())
            .toList();

        final Consumer<String> log = msg -> this.recorder.info("[jdeps] %s", msg);
        // Seed required-name preference from the root module so split-package conflicts
        // involving modules the root transitively requires (e.g. maven.settings under
        // Maven 4, which shares org.apache.maven.settings.v4 with maven-support) keep the
        // required side on the module-path.
        final var resolution = ModuleGraphClassifier.resolveConflicts(
            candidatePaths,
            java.util.Set.of(this.moduleDescriptor.moduleName().toString()),
            log);

        // copy non-conflicting candidates to module-path; handle conflicts appropriately
        for (final var candidate : modulePathCandidates) {
            candidate.path().ifPresent(source -> {
                if (resolution.superseded().contains(source)) {
                    return;
                }
                if (resolution.demoted().contains(source)) {
                    classPathBuilder.add(source);
                }
                else {
                    try {
                        final var target = modulePath.resolve(source.getFileName());
                        Files.copy(source, target);
                    }
                    catch (final IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }

        // -----
        // execute jdeps for the Java Version with the provided modules

        // build the classpath PathSet (containing the dependencies)
        final var classPathSet = classPathBuilder.build();
        final var classPath = Streams.reverse(classPathSet.stream())
            .map(Path::toString)
            .map(Strings::doubleQuoteIfContainsWhiteSpace)
            .collect(Collectors.joining(File.pathSeparator));

        final var recordingObserver = new RecordingSubscriber<String>();
        final var stdoutObserver = StandardOutputSubscriber.of(recordingObserver);

        // the path to this artifact to analyze
        final var artifactPath = artifactDescriptorsByModuleName.get(this.moduleDescriptor.moduleName().toString())
            .path()
            .orElseThrow(() -> new IllegalStateException("No artifact path for module [" + this.moduleDescriptor.moduleName().toString() + "]"));

        final ErrorCapture captured = new ErrorCapture();

        // build jdeps arguments — omit --class-path when empty (jdeps rejects empty values) — shared by
        // both the in-process and forked launch paths below, so argument-building never diverges
        // between them
        final ConfigurationBuilder jdepsConfiguration = ConfigurationBuilder.create()
            .add(Argument.of("--module-path"))
            .add(Argument.of(modulePath));
        if (!classPath.isEmpty()) {
            jdepsConfiguration.add(Argument.of("--class-path"));
            jdepsConfiguration.add(Argument.of(classPath));
        }
        jdepsConfiguration
            .add(Argument.of("--list-deps"))
            .add(Argument.of("--ignore-missing-deps"))
            .add(Argument.of("--multi-release"))
            .add(Argument.of(jdk.version().major()))
            .add(Argument.of(artifactPath));

        final int exitCode;

        if (JDKTools.canRunInProcess(jdk)) {
            // the resolved JDK is the exact installation running this Spin process, so "jdeps" can run
            // in-process via ToolProvider instead of forking a whole child JVM
            final var errorSubscriber = captured
                .triageSubscriber(ErrorCapture::isJvmNoise, this.recorder::warn, this.recorder::error)
                .get();

            exitCode = JDKTools.runInProcess("jdeps", recordingObserver, errorSubscriber, jdepsConfiguration);
        } else {
            jdepsConfiguration
                .add(JDKTools.executable(javaHome, "jdeps"))
                .add(Name.of("jdeps"))
                .add(stdoutObserver)
                .add(captured.triageSubscriber(ErrorCapture::isJvmNoise, this.recorder::warn, this.recorder::error));

            try (var jdeps = this.machine.launch(Application.class, jdepsConfiguration)) {
                ProcessRunner.await(jdeps, "jdeps",
                    () -> ErrorCapture.selectOutput(captured.output(), recordingObserver.items()));
            }

            // await() throws on a failed wait or a non-zero exit, so reaching here means success
            exitCode = 0;
        }

        if (exitCode != 0) {
            throw new ProcessFailedException(
                "jdeps Execution Failed (exit code: " + exitCode + ")",
                ErrorCapture.selectOutput(captured.output(), recordingObserver.items()));
        }

        // build maps of java platform, module and non-module dependencies
        final LinkedHashSet<JDKModuleDescriptor> modules = new LinkedHashSet<>();
        final LinkedHashMap<JDKModuleDescriptor, ArtifactDescriptor> artifacts = new LinkedHashMap<>();
        final LinkedHashSet<String> unknownModules = new LinkedHashSet<>();

        // a Consumer of Artifacts together with their ModuleDescriptors
        // (to collect and categorize the ModuleDescriptors)
        final Consumer<Map.Entry<Artifact, ArtifactDescriptor>> consumeArtifact = entry -> {
            final ArtifactDescriptor artifactDescriptor = entry.getValue();
            final JDKModuleDescriptor descriptor = moduleDescriptors.get(artifactDescriptor.reference());

            if (descriptor != null) {
                if (descriptor.isAutomatic() && descriptor.isSynthetic()) {
                    // synthesized from catalog+POM: jar has no module-info.class and no Automatic-Module-Name
                    unnamedArtifactDescriptors.put(entry.getKey(), artifactDescriptor);
                } else {
                    artifacts.put(descriptor, artifactDescriptor);
                    modules.add(descriptor);
                }
            }
        };

        recordingObserver.items()
            .map(String::trim)
            .filter(line -> !line.contains(" "))
            .forEach(line -> {
                final var moduleName = moduleNameFromListDepsLine(line);

                if (JavaPlatform.isJavaPlatformModule(moduleName)) {
                    final ModuleReference reference = ModuleReference.of(moduleName, jdkVersion);
                    platformModules.add(reference);
                }
                else {
                    artifactDescriptors.entrySet().stream()
                        .filter(entry ->  entry.getValue().reference().name().equals(moduleName))
                        .findFirst()
                        .ifPresentOrElse(consumeArtifact, () -> unknownModules.add(moduleName));
                }
            });

        // ensure all artifacts provided are consumed as either modules or non-modules
        artifactDescriptors.entrySet().stream()
            .forEach(consumeArtifact);

        final Table platformModulesTable = Table.create();
        platformModulesTable.addRow("Module Name");
        platformModules.stream().forEach(reference -> platformModulesTable.addRow(reference.name()));
        this.recorder.diagnostic("Java Platform Modules (%s)\n%s", jdk.version().get(),
            platformModulesTable);

        if (!modules.isEmpty()) {
            final Table modulesTable = Table.create();
            modulesTable.addRow("Module Name", "Version", "Type");
            modules.stream().forEach(descriptor ->
                modulesTable.addRow(descriptor.moduleName().toString(),
                    descriptor.version().map(Version::toString).orElse("(unknown version)"),
                    descriptor.isAutomatic() ? "automatic module" : "fully-blown module"));
            this.recorder.diagnostic("Explicit Modules\n%s", modulesTable);
        }

        if (!unnamedArtifactDescriptors.isEmpty()) {
            final Table unnamedTable = Table.create();
            unnamedTable.addRow("Module Name (generated)", "Version");
            unnamedArtifactDescriptors.values().forEach(descriptor -> unnamedTable.addRow(
                descriptor.reference().name(),
                descriptor.reference().version().map(Version::toString).orElse("(unknown version)")));
            this.recorder.diagnostic("Unnamed Modules\n%s", unnamedTable);
        }

        if (!unknownModules.isEmpty()) {
            final Table unknownModulesTable = Table.create();
            unknownModules.stream().forEach(unknownModulesTable::addRow);
            this.recorder.diagnostic("Unknown Modules\n%s", unknownModulesTable);
        }

        return new DependencyAnalysis() {
            @Override
            public Dependency dependency() {
                return new Dependency() {
                    @Override
                    public JDKModuleDescriptor moduleDescriptor() {
                        return moduleDescriptor;
                    }

                    @Override
                    public ArtifactDescriptor artifactDescriptor() {
                        return artifacts.get(moduleDescriptor);
                    }
                };
            }

            @Override
            public Stream<ModuleReference> platformModules() {
                return platformModules.stream();
            }

            @Override
            public Stream<Dependency> dependencies() {
                return artifactDescriptorsByModuleName.values().stream()
                    .map(descriptor -> new Dependency() {
                        @Override
                        public JDKModuleDescriptor moduleDescriptor() {
                            return moduleDescriptors.get(descriptor.reference());
                        }

                        @Override
                        public ArtifactDescriptor artifactDescriptor() {
                            return descriptor;
                        }
                    });
            }

            @Override
            public Stream<String> unknownModules() {
                return unknownModules.stream();
            }

            @Override
            public Path modulePath() {
                return modulePath;
            }
        };
    }

    /**
     * Extracts the module name from a {@code jdeps --list-deps} output line.
     *
     * <p>{@code jdeps} prints {@code <module>/<package>} instead of plain {@code <module>}
     * whenever the package isn't part of the module's default (unconditional) export surface —
     * either because it's a qualified export ({@code exports pkg to other.module;}, as with two
     * workspace modules like {@code com.example.library}/{@code com.example.app}) or a fully
     * internal, unexported package (e.g. JDK internal APIs). The module name is always the
     * portion before the first {@code /}.
     *
     * @param line a single trimmed, space-free line from {@code jdeps --list-deps} output
     * @return the module name, with any {@code /package} suffix removed
     */
    static String moduleNameFromListDepsLine(final String line) {
        final int slash = line.indexOf('/');
        return slash < 0 ? line : line.substring(0, slash);
    }

    /**
     * Resolves the {@link Version} to use for a compiled {@code requires} clause discovered while
     * walking an already-published dependency's {@code module-info.class}, deferring to {@link
     * RequiredVersionResolution} for the catalog-wins-over-bytecode-hint precedence shared with
     * {@link AbstractDetectResolution#resolveExternalArtifact}, then falling back further to an
     * already-resolved {@link ArtifactDescriptor} when neither source has an answer — the case for a
     * workspace sibling, whose source {@code module-info.java} carries no compiled version at all.
     *
     * @param r                   the compiled {@code requires} clause
     * @param name                {@code r}'s required module name
     * @param requiringModuleName the name of the module declaring {@code r}, for diagnostics
     * @param versioning          the workspace-wide {@link ModuleVersioning}
     * @param artifactDescriptors already-resolved {@link ArtifactDescriptor}s to fall back to when
     *                            neither the catalog nor the bytecode hint has an answer
     * @param recorder            the {@link TelemetryRecorder} for diagnostics
     * @return the resolved {@link Version}, if one could be determined from any source
     */
    // Visible for testing.
    static Optional<Version> resolveRequiredVersion(final RequiresModuleDescriptor r,
                                                    final String name,
                                                    final String requiringModuleName,
                                                    final ModuleVersioning versioning,
                                                    final Collection<ArtifactDescriptor> artifactDescriptors,
                                                    final TelemetryRecorder recorder) {

        return RequiredVersionResolution.resolve(r, name, requiringModuleName, versioning, recorder)
            .or(() -> artifactDescriptors.stream()
                .filter(d -> d.reference().name().equals(name))
                .findFirst()
                .flatMap(d -> d.reference().version()));
    }

    /**
     * Returns {@code true} if the module with the given {@code name} and {@code version} should be
     * processed during jdeps traversal.
     *
     * <p>A module is processed if it has never been seen before, or if the incoming version is
     * strictly higher than the version that was previously processed — this ensures the higher-version
     * jar's transitive dependencies are walked rather than silently inheriting the lower-version walk.
     * Versions are compared with {@link VersionOrder#MAVEN}, not {@link Version#compareTo}, so that
     * Maven qualifiers (e.g. {@code rc}, {@code snapshot}) rank the way Maven itself ranks them rather
     * than lexicographically — matching every other version-comparison call site.
     *
     * @param name    the JPMS module name
     * @param version the version of the candidate, if known
     * @param seen    the map of already-processed module names to their processed versions
     * @return {@code true} iff the module should be (re-)processed
     */
    static boolean shouldProcess(final String name,
                                 final Optional<Version> version,
                                 final Map<String, Optional<Version>> seen) {
        if (!seen.containsKey(name)) {
            return true;
        }
        final Optional<Version> seenVersion = seen.get(name);
        if (seenVersion.isEmpty()) {
            return version.isPresent();
        }
        return version.isPresent() && VersionOrder.MAVEN.compare(version.get(), seenVersion.get()) > 0;
    }

    /**
     * Dedupe a collection of {@link ArtifactDescriptor}s by Maven coordinate, keeping the highest
     * version when duplicates are present. Delegates to the same {@link MavenCoordinateDedupe}
     * logic {@link AbstractDetectResolution#dedupeByMavenCoordinate} uses, keyed off each
     * descriptor's resolved artifact {@link ArtifactDescriptor#path()} rather than a
     * {@code groupId:artifactId} string.
     *
     * <p>This is a strictly-stronger signal than module-name dedupe: two versions of the same
     * Maven artifact may publish under <em>different</em> JPMS module names (the slf4j-api 1.x
     * → 2.x rename being the canonical example), so a downstream module-name dedupe pass cannot
     * eliminate them. Coordinate-based dedupe is the only thing that can.
     *
     * @param descriptors the descriptors to dedupe; iteration order is preserved
     * @param onDuplicate invoked as {@code (kept, dropped)} for each duplicate pair
     * @return the winning descriptors, in first-seen order
     */
    static List<ArtifactDescriptor> dedupeByMavenCoordinates(
        final Collection<ArtifactDescriptor> descriptors,
        final BiConsumer<ArtifactDescriptor, ArtifactDescriptor> onDuplicate) {

        return MavenCoordinateDedupe.dedupeByMavenCoordinate(
            new ArrayList<>(descriptors), ArtifactDescriptor::path, onDuplicate);
    }
}
