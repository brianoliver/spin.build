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

import build.base.option.JDKVersion;
import build.base.telemetry.TelemetryRecorder;
import build.base.version.Version;
import build.codemodel.dependency.injection.Binder;
import build.codemodel.dependency.injection.Provides;
import build.codemodel.foundation.CodeModel;
import build.codemodel.foundation.naming.ModuleName;
import build.codemodel.jdk.descriptor.JDKModuleDescriptor;
import build.codemodel.jdk.descriptor.ModuleModifier;
import build.codemodel.jdk.descriptor.OpenModule;
import build.codemodel.jdk.descriptor.VersionTrait;
import build.spawn.jdk.JDK;
import build.spin.Plugin;
import build.spin.Project;
import build.spin.Task;
import build.spin.common.task.SourcePathKind;
import build.spin.module.modulesystem.Artifact;
import build.spin.module.modulesystem.ModuleVersioning;
import build.spin.option.BuildDirectoryName;
import build.spin.option.TargetDirectoryName;
import jakarta.inject.Inject;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An abstract {@link Plugin} for Java-based {@link Project}s.
 *
 * @author brian.oliver
 * @since Aug-2020
 */
public abstract class AbstractJavaPlugin
    implements JavaPlugin {

    @Inject
    protected TelemetryRecorder recorder;

    @Inject
    protected Project project;

    @Inject
    protected ModuleVersioning versioning;

    @Inject
    protected JavaPlatform platform;

    @Inject
    private CodeModel codeModel;

    @Inject
    private BuildDirectoryName buildDirectoryName;

    @Inject
    private TargetDirectoryName targetDirectoryName;

    /**
     * The cached {@link JDKModuleDescriptor} determined for the {@link Project}.
     */
    private final AtomicReference<JDKModuleDescriptor> moduleDescriptor;

    /**
     * Constructs a {@link AbstractJavaPlugin}.
     */
    public AbstractJavaPlugin() {
        this.moduleDescriptor = new AtomicReference<>();
    }

    /**
     * Obtains the root {@link Path} for source code (including resources) for the {@link JavaPlugin}.
     *
     * @return the root {@link Path}
     */
    protected Path getSourceRootPath() {
        return this.project.path().resolve(sourceScope().sourceRoot().orElseThrow());
    }

    /**
     * The {@link SourcePathKind} this {@link JavaPlugin} compiles — {@link SourcePathKind#MAIN} here,
     * overridden to {@link SourcePathKind#TEST} by {@code AbstractJUnitPlugin}. Drives the scope-gated
     * behavior in {@link AbstractDetectResolution} (e.g. whether a project's own compiled MAIN output
     * is added as an additional candidate) without requiring a {@link Task} subclass per scope.
     *
     * @return the {@link SourcePathKind}
     */
    @Provides
    protected SourcePathKind sourceScope() {
        return SourcePathKind.MAIN;
    }

    /**
     * Contributes no additional infrastructure {@link Artifact}s by default — establishes the
     * {@code Set<Artifact>} multibinding key so it's always injectable (empty here), overridden by
     * {@code AbstractJUnitPlugin} to contribute the JUnit Platform ConsoleLauncher and Jupiter engine.
     *
     * @param binder the {@link Binder} to contribute bindings to
     */
    @Override
    public void contributeBindings(final Binder binder) {
        binder.bindSet(Artifact.class);
    }

    @Override
    @Provides
    public abstract JDKVersion getJavaVersion();

    @Override
    @Provides
    public JDK getJDK() {
        return this.platform
            .getVersion(getJavaVersion().major())
            .orElseThrow(() -> {
                final var jdks = this.platform.stream().toList();
                return new RuntimeException("Failed to obtain Java Development Kit for Java " + getJavaVersion().major() + ". Available Java Development Kits: " + jdks);
            });
    }

    @Override
    @Provides
    public JDKModuleDescriptor getModuleDescriptor() {

        // determine the ModuleDescriptor only once
        return this.moduleDescriptor.updateAndGet(descriptor -> {
            if (descriptor == null) {
                // create a list of places to look for module-info.java (based on priority)
                final List<Path> moduleInfoPaths = new ArrayList<>();

                final Path unversionedModuleInfoPath = getSourceRootPath()
                    .resolve("java/")
                    .resolve(JDKModuleDescriptor.SOURCE_FILENAME);

                if (Files.exists(unversionedModuleInfoPath)) {
                    moduleInfoPaths.add(unversionedModuleInfoPath);
                }

                final Path versionedModuleInfoPath = getSourceRootPath()
                    .resolve("java" + getJavaVersion().major() + "/")
                    .resolve(JDKModuleDescriptor.SOURCE_FILENAME);

                if (Files.exists(versionedModuleInfoPath)) {
                    moduleInfoPaths.add(versionedModuleInfoPath);
                }

                final Path versions = getSourceRootPath()
                    .resolve("resources/META-INF/versions/");
                if (Files.exists(versions)) {
                    try {
                        Files.list(versions)
                            .filter(path -> Files.exists(path.resolve(JDKModuleDescriptor.SOURCE_FILENAME)))
                            .map(path -> {
                                try {
                                    return Integer.parseInt(path.getFileName().toString());
                                } catch (final NumberFormatException e) {
                                    return null;
                                }
                            })
                            .filter(Objects::nonNull)
                            .sorted(Comparator.reverseOrder())
                            .map(version -> versions.resolve(version.toString())
                                .resolve(JDKModuleDescriptor.SOURCE_FILENAME))
                            .forEach(moduleInfoPaths::add);
                    } catch (final IOException e) {
                        this.recorder.warn(e,
                            "Failed to list [%s] while looking for versioned module-info.java files for [%s].",
                            versions, this.project.name());
                    }
                }

                if (moduleInfoPaths.size() > 1) {
                    this.recorder.info(
                        "Project [%s] has %d candidate module-info.java files %s — using [%s]",
                        this.project.qualifiedName(), moduleInfoPaths.size(), moduleInfoPaths, moduleInfoPaths.get(0));
                }

                final String normalizedProjectName = this.project.name().replace("-", ".");

                final JDKModuleDescriptor result = moduleInfoPaths.stream()
                    .findFirst()
                    .map(path -> {
                        try (BufferedReader reader = Files.newBufferedReader(path)) {
                            return JDKModuleDescriptor.parse(this.codeModel, reader);
                        } catch (final IOException e) {
                            this.recorder.warn(e,
                                "Failed to read [%s] for Project [%s]. Defaulting to an empty ModuleDescriptor.",
                                path, this.project.qualifiedName());
                            return automaticDescriptor(normalizedProjectName);
                        }
                    })
                    .orElseGet(() -> {
                        // a missing MAIN module-info.java is notable enough to warrant a warning, but a
                        // missing TEST module-info.java (sourceScope() == TEST, per AbstractJUnitPlugin)
                        // is the routine, common case for a Project with no test-specific module
                        // requirements - not worth a warning on every such Project
                        final String message =
                            "Project [%s] does not define a ModuleDescriptor. Defaulting to an empty ModuleDescriptor.";
                        if (sourceScope() == SourcePathKind.TEST) {
                            this.recorder.diagnostic(message, this.project.qualifiedName());
                        } else {
                            this.recorder.warn(message, this.project.qualifiedName());
                        }
                        return automaticDescriptor(normalizedProjectName);
                    });

                // module-info.java source has no version syntax, so a parsed (or synthesized)
                // descriptor never carries one; attach the Project's actual version here so
                // downstream consumers don't report an unknown version for workspace-local modules.
                stampVersion(result, this.versioning.getVersion(result.moduleName().toString()));

                return result;
            }

            return descriptor;
        });
    }

    /**
     * Stamps {@code version}, if present, onto {@code descriptor} as a {@link VersionTrait}.
     * <p>
     * {@code descriptor} may be a shared instance from the CodeModel registry (multiple sibling
     * plugins for the same module name can each obtain and stamp it), so any existing
     * {@link VersionTrait} is removed first to keep re-stamping idempotent.
     *
     * @param descriptor the {@link JDKModuleDescriptor} to stamp
     * @param version the {@link Version} to stamp, if known
     */
    static void stampVersion(final JDKModuleDescriptor descriptor, final Optional<Version> version) {
        version.ifPresent(v -> {
            descriptor.getTrait(VersionTrait.class).ifPresent(descriptor::removeTrait);
            descriptor.addTrait(VersionTrait.of(v));
        });
    }

    private JDKModuleDescriptor automaticDescriptor(final String name) {
        final ModuleName moduleName = this.codeModel.getNameProvider().getModuleName(name).orElseThrow();
        // Use of() directly rather than createModuleDescriptor() to avoid registering this fallback
        // in the shared CodeModel cache — if a real module-info.java is later parsed for the same
        // module name (e.g. by a sibling plugin), parse() must be able to create the registry entry
        // uncontested.
        final JDKModuleDescriptor descriptor = JDKModuleDescriptor.of(this.codeModel, moduleName);
        descriptor.addTrait(OpenModule.OPEN);
        descriptor.addTrait(ModuleModifier.AUTOMATIC);
        return descriptor;
    }

    /**
     * Obtains the {@link Version} for the {@link Project}
     *
     * @return the {@link Version}
     */
    @Provides
    public Version getVersion() {
        return this.versioning.getVersion(getModuleDescriptor().moduleName().toString())
            .orElse(ModuleVersioning.DEFAULT_VERSION);
    }
}
