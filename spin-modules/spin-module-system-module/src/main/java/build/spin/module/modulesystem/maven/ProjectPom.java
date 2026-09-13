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
import build.spin.Project;
import build.spin.module.modulesystem.pom.Pom;
import build.spin.module.modulesystem.pom.PomReader;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Gives any {@link Project} in the workspace access to its own effective {@code pom.xml} — parent
 * chain, {@code <dependencyManagement>}/BOM imports, and active-profile evaluation all applied —
 * backed by a single {@link PomReader} shared across every {@link #get(Project)} call, so a
 * project's pom (and its parent chain) is parsed at most once per build no matter how many callers
 * ask for it.
 * <p>
 * Injected as a {@link Singleton}, exactly like {@link LocalMavenRepository}, rather than modeled
 * as a per-project {@code Resource}: {@code PomBasedCheckstyleArguments} and its siblings are
 * themselves workspace-root singleton {@code Resource}s (see their {@code MetaClass.isWorkspace}),
 * and {@code DefaultEngine.createProject} only wires a project's {@code ProjectResourceResolver}
 * in <em>after</em> that same project's own resources have all been created — so a resource can
 * never {@code @Inject} another resource registered on that identical project. A plain DI
 * singleton has no such ordering constraint.
 *
 * @author reed.vonredwitz
 * @since Sep-2026
 */
@Singleton
public final class ProjectPom {

    private static final String POM_FILENAME = "pom.xml";

    private final PomReader pomReader;

    @Inject
    ProjectPom(final LocalMavenRepository localMavenRepository, final TelemetryRecorder recorder) {
        this.pomReader = new PomReader(localMavenRepository.path(), recorder);
    }

    /**
     * Creates a {@link ProjectPom} backed by {@code localMavenRepository}, for callers outside
     * this package (e.g. tests) that can't reach the package-private constructor DI uses.
     */
    public static ProjectPom of(final LocalMavenRepository localMavenRepository, final TelemetryRecorder recorder) {
        return new ProjectPom(localMavenRepository, recorder);
    }

    /**
     * The effective {@link Pom} for {@code project}.
     *
     * @param project the {@link Project} whose pom to read
     * @return the effective {@link Pom}, or {@link Optional#empty()} if {@code project} has no
     * {@code pom.xml} (a spin-native project)
     */
    public Optional<Pom> get(final Project project) {
        return get(project.path());
    }

    /**
     * The effective {@link Pom} for the project rooted at {@code projectPath}, for callers that
     * only have the project's directory rather than a {@link Project}.
     *
     * @param projectPath the project root
     * @return the effective {@link Pom}, or {@link Optional#empty()} if {@code projectPath} has no
     * {@code pom.xml} (a spin-native project)
     */
    public Optional<Pom> get(final Path projectPath) {
        final Path pomXml = projectPath.resolve(POM_FILENAME);
        if (!Files.exists(pomXml)) {
            return Optional.empty();
        }
        return this.pomReader.read(pomXml);
    }
}
