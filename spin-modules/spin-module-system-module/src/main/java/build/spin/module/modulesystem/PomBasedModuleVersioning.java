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
import build.codemodel.dependency.injection.PostInject;
import build.codemodel.foundation.CodeModel;
import build.codemodel.foundation.naming.NonCachingNameProvider;
import build.codemodel.jdk.JDKCodeModel;
import build.spin.Project;
import build.spin.Resource;
import build.spin.Workspace;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * A {@link ModuleVersioning} {@link Resource} that derives dependency versions by parsing the
 * {@code pom.xml} files present in a Maven workspace, for use when no {@code version.properties}
 * is available.
 * <p>
 * Workspace-walking and transitive-dependency traversal is delegated to {@link PomDependencyGraphWalker};
 * this class only provides the visitor that records each discovered coordinate's version under
 * every JPMS module-name candidate.
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public class PomBasedModuleVersioning
    implements ModuleVersioning, Resource {

    private static final String POM_FILENAME = "pom.xml";
    private static final String VERSION_PROPERTIES_FILENAME = "version.properties";

    @Inject
    private TelemetryRecorder recorder;

    @Inject
    private Project project;

    @Inject
    private CodeModel codeModel;

    @Inject
    private LocalMavenRepository localMavenRepository;

    private ModuleVersioning versioning;

    @PostInject
    private void onInjected() {
        this.versioning = buildFromWorkspace(
            this.project.path(), this.localMavenRepository.path(), this.codeModel, this.recorder,
            this.project::isIgnored);
    }

    /**
     * Builds a {@link ModuleVersioning} from a Maven workspace. Package-private to allow direct
     * testing without the DI container.
     */
    static ModuleVersioning buildFromWorkspace(final Path workspacePath, final TelemetryRecorder recorder) {
        final Path localRepo = LocalMavenRepository.fromEnvironment().path();
        return buildFromWorkspace(workspacePath, localRepo, new JDKCodeModel(new NonCachingNameProvider()), recorder);
    }

    /**
     * Builds a {@link ModuleVersioning} from a Maven workspace using the given local repository
     * root. Package-private to allow direct testing with a temporary local repository.
     */
    static ModuleVersioning buildFromWorkspace(final Path workspacePath,
                                               final Path localRepo,
                                               final CodeModel codeModel,
                                               final TelemetryRecorder recorder) {
        return buildFromWorkspace(workspacePath, localRepo, codeModel, recorder, path -> false);
    }

    /**
     * Builds a {@link ModuleVersioning} from a Maven workspace, pruning any directory for which
     * {@code isIgnored} returns {@code true} (e.g. one excluded via {@code .spinignore}) from the
     * pom walk. Package-private to allow direct testing.
     */
    static ModuleVersioning buildFromWorkspace(final Path workspacePath,
                                               final Path localRepo,
                                               final CodeModel codeModel,
                                               final TelemetryRecorder recorder,
                                               final Predicate<Path> isIgnored) {
        final Map<String, Version> versions = new LinkedHashMap<>();

        PomDependencyGraphWalker.walk(workspacePath, localRepo, recorder, codeModel, isIgnored,
            (names, groupId, artifactId, rawVersion) -> {
                try {
                    final Version version = Version.parse(rawVersion);
                    // keep the highest version registered for a name, not merely the first one
                    // visited: the walker can (and, for a name reachable via more than one
                    // transitive path, does) invoke this visitor more than once for the same name at
                    // different versions -- an earlier, lower-version visit must not permanently pin
                    // a name once a later visit discovers a higher one.
                    names.forEach(name -> versions.merge(name, version,
                        (existing, incoming) -> VersionOrder.MAVEN.compare(incoming, existing) > 0
                            ? incoming : existing));
                } catch (final Exception e) {
                    recorder.warn(e, "PomBasedModuleVersioning failed to parse version [%s] for [%s:%s]",
                        rawVersion, groupId, artifactId);
                }
            });

        recorder.diagnostic("PomBasedModuleVersioning loaded %d version mappings for [%s]",
            versions.size(), workspacePath.getFileName());
        return new MapBackedVersioning(versions);
    }

    @Override
    public Optional<Version> getVersion(final String moduleName) {
        return this.versioning.getVersion(moduleName);
    }

    /**
     * The {@link Resource.MetaClass} for {@link PomBasedModuleVersioning}.
     */
    public static class MetaClass
        implements Resource.MetaClass {

        @Override
        public boolean isWorkspace(final Path path) {
            return PomWorkspaces.isMavenWorkspaceRoot(path)
                && !Files.exists(path.resolve(VERSION_PROPERTIES_FILENAME));
        }

        @Override
        public boolean isDetectedIn(final Project project) {
            return project instanceof Workspace
                && Files.exists(project.path().resolve(POM_FILENAME))
                && !Files.exists(project.path().resolve(VERSION_PROPERTIES_FILENAME));
        }
    }

    /**
     * Simple {@link ModuleVersioning} backed by a {@link Map}.
     */
    private static final class MapBackedVersioning implements ModuleVersioning {

        private final Map<String, Version> versions;

        private MapBackedVersioning(final Map<String, Version> versions) {
            this.versions = versions;
        }

        @Override
        public Optional<Version> getVersion(final String moduleName) {
            return Optional.ofNullable(this.versions.get(moduleName));
        }

    }
}
