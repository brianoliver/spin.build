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

import build.base.telemetry.TelemetryRecorder;
import build.codemodel.dependency.injection.PostInject;
import build.codemodel.foundation.CodeModel;
import build.codemodel.foundation.naming.NonCachingNameProvider;
import build.codemodel.jdk.JDKCodeModel;
import build.spin.Project;
import build.spin.Resource;
import build.spin.module.modulesystem.Artifact;
import build.spin.module.modulesystem.ModuleCatalog;
import build.spin.module.modulesystem.ModuleReference;
import build.spin.module.modulesystem.properties.PropertiesModuleCatalog;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * A {@link ModuleCatalog} {@link Resource} that derives module-to-artifact mappings by parsing
 * the {@code pom.xml} files present in a Maven workspace, for use when no
 * {@code module-catalog.properties} is available.
 * <p>
 * Workspace-walking and transitive-dependency traversal is delegated to {@link PomDependencyGraphWalker};
 * this class only provides the visitor that turns each discovered coordinate into an
 * {@link Artifact.Constraint} registered under every JPMS module-name candidate.
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public class PomBasedModuleCatalog
    implements ModuleCatalog, Resource {

    private static final String MODULE_CATALOG_FILENAME = PropertiesModuleCatalog.MODULE_CATALOG_FILENAME;

    @Inject
    private TelemetryRecorder recorder;

    @Inject
    private Project project;

    @Inject
    private CodeModel codeModel;

    @Inject
    private LocalMavenRepository localMavenRepository;

    private ModuleCatalog catalog;
    private Path localRepo;

    @PostInject
    private void onInjected() {
        this.localRepo = this.localMavenRepository.path();
        this.catalog = buildFromWorkspace(
            this.project.path(), this.localRepo, this.codeModel, this.recorder, this.project::isIgnored);
    }

    /**
     * Builds a {@link ModuleCatalog} from a Maven workspace. Package-private to allow direct
     * testing without the DI container.
     */
    static ModuleCatalog buildFromWorkspace(final Path workspacePath, final TelemetryRecorder recorder) {
        final Path localRepo = LocalMavenRepository.fromEnvironment().path();
        return buildFromWorkspace(workspacePath, localRepo, new JDKCodeModel(new NonCachingNameProvider()), recorder);
    }

    /**
     * Builds a {@link ModuleCatalog} from a Maven workspace using the given local repository root.
     * Package-private to allow direct testing with a temporary local repository.
     */
    static ModuleCatalog buildFromWorkspace(final Path workspacePath,
                                            final Path localRepo,
                                            final CodeModel codeModel,
                                            final TelemetryRecorder recorder) {
        return buildFromWorkspace(workspacePath, localRepo, codeModel, recorder, path -> false);
    }

    /**
     * Builds a {@link ModuleCatalog} from a Maven workspace, pruning any directory for which
     * {@code isIgnored} returns {@code true} (e.g. one excluded via {@code .spinignore}) from the
     * pom walk. Package-private to allow direct testing.
     */
    static ModuleCatalog buildFromWorkspace(final Path workspacePath,
                                            final Path localRepo,
                                            final CodeModel codeModel,
                                            final TelemetryRecorder recorder,
                                            final Predicate<Path> isIgnored) {
        final ModuleCatalog result = ModuleCatalog.HeapBased.create();

        PomDependencyGraphWalker.walk(workspacePath, localRepo, recorder, codeModel, isIgnored,
            (names, groupId, artifactId, version) -> {
                try {
                    final Artifact.Constraint constraint = Artifact.Constraint.of(
                        Artifact.create(groupId, artifactId, version, "jar"));
                    names.forEach(name -> result.add(name, constraint));
                } catch (final Exception e) {
                    recorder.warn(e, "PomBasedModuleCatalog failed to register [%s:%s:%s]",
                        groupId, artifactId, version);
                }
            });

        recorder.diagnostic("PomBasedModuleCatalog loaded catalog for [%s]", workspacePath.getFileName());
        return result;
    }

    @Override
    public ModuleCatalog add(final String moduleName, final Artifact.Constraint constraint) {
        return this.catalog.add(moduleName, constraint);
    }

    @Override
    public Stream<Artifact.Constraint> constraints(final String moduleName) {
        return this.catalog.constraints(moduleName);
    }

    @Override
    public Optional<Artifact> getArtifact(final ModuleReference reference) {
        final Optional<Artifact> found = this.catalog.getArtifact(reference, Optional.of(this.recorder));
        if (found.isPresent() || reference.version().isEmpty()) {
            return found;
        }
        // Fallback: infer groupId/artifactId from the module name convention and probe the local repo.
        final String version = reference.version().get().toString();
        return MavenModuleNaming.findJarByModuleName(reference.name(), version, this.localRepo)
            .map(c -> {
                final Artifact artifact = Artifact.create(c[0], c[1], c[2], "jar");
                final Artifact.Constraint constraint = Artifact.Constraint.of(artifact);
                this.catalog.add(reference.name(), constraint);
                return artifact;
            });
    }

    @Override
    public Optional<ModuleReference> getModuleReference(final Artifact artifact) {
        return this.catalog.getModuleReference(artifact);
    }

    /**
     * The {@link Resource.MetaClass} for {@link PomBasedModuleCatalog}.
     */
    public static class MetaClass
        implements Resource.MetaClass {

        @Override
        public boolean isWorkspace(final Path path) {
            return PomWorkspaces.isMavenWorkspaceRootWithoutConfig(path, MODULE_CATALOG_FILENAME);
        }

        @Override
        public boolean isDetectedIn(final Project project) {
            return PomWorkspaces.isMavenWorkspaceProjectWithoutConfig(project, MODULE_CATALOG_FILENAME);
        }
    }
}
