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
import build.codemodel.foundation.CodeModel;
import build.codemodel.foundation.descriptor.RequiresModuleDescriptor;
import build.codemodel.foundation.naming.ModuleName;
import build.codemodel.jdk.descriptor.JDKModuleDescriptor;
import build.codemodel.jdk.descriptor.ModuleModifier;
import build.codemodel.jdk.descriptor.OpenModule;
import build.spin.Project;
import build.spin.Resource;
import build.spin.module.modulesystem.TestModuleDescriptor;
import build.spin.module.modulesystem.pom.Dependency;
import build.spin.module.modulesystem.pom.Pom;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.Optional;

/**
 * A {@link TestModuleDescriptor} {@link Resource} that derives test module requirements from
 * test-scoped {@code <dependency>} entries in a project's {@code pom.xml}, for use when no
 * {@code src/test/java/module-info.java} is present.
 * <p>
 * This resource is workspace-level. The {@link #get(Project)} method reads the specific
 * sub-project's effective pom on each call via the injected {@link ProjectPom} — parent
 * inheritance, {@code <dependencyManagement>}, and active-profile evaluation all apply, so only
 * dependencies that are actually part of the project's dependency graph contribute a
 * {@code requires}. Dependencies without an explicit (or dependency-management-filled) version
 * are still registered; their versions are resolved later via {@code ModuleVersioning}.
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public class PomBasedTestModuleDescriptor
    implements TestModuleDescriptor, Resource {

    @Inject
    private TelemetryRecorder recorder;

    @Inject
    private CodeModel codeModel;

    @Inject
    private ProjectPom projectPom;

    @Override
    public JDKModuleDescriptor get(final Project project) {
        final String name = project.name().replace("-", ".");
        final ModuleName moduleName = this.codeModel.getNameProvider().getModuleName(name).orElseThrow();
        // Use of() rather than createModuleDescriptor() — this is a transient descriptor used only
        // to carry pom-derived requires into the caller's merge; it must not occupy the shared
        // CodeModel registry slot that parse() needs for the real module descriptor.
        final JDKModuleDescriptor descriptor = JDKModuleDescriptor.of(this.codeModel, moduleName);
        descriptor.addTrait(OpenModule.OPEN);
        descriptor.addTrait(ModuleModifier.AUTOMATIC);

        try {
            registerTestRequires(project, descriptor);
        } catch (final Exception e) {
            this.recorder.warn(e, "PomBasedTestModuleDescriptor failed for [%s]", project.name());
        }

        return descriptor;
    }

    private void registerTestRequires(final Project project, final JDKModuleDescriptor descriptor) {
        try {
            final Optional<Pom> pom = this.projectPom.get(project);
            if (pom.isEmpty()) {
                return;
            }

            for (final Dependency dep : pom.get().dependencies()) {
                // exclude provided and system deps; include compile, runtime, and test
                final String scope = dep.scope();
                if ("provided".equals(scope) || "system".equals(scope)) {
                    continue;
                }

                // this synthetic descriptor has no jar to read a ground-truth module name from,
                // so every naming-convention candidate must be tried — MavenModuleNaming.deriveNames
                // is the single canonical set of those, shared with PomDependencyGraphWalker.
                for (final String candidate : MavenModuleNaming.deriveNames(dep.groupId(), dep.artifactId())) {
                    final ModuleName moduleName =
                        this.codeModel.getNameProvider().getModuleName(candidate).orElseThrow();
                    descriptor.addTrait(RequiresModuleDescriptor.of(this.codeModel, moduleName));
                }
            }
        } catch (final Exception e) {
            this.recorder.warn(e, "PomBasedTestModuleDescriptor failed to read test deps for [%s]", project.name());
        }
    }

    /**
     * The {@link Resource.MetaClass} for {@link PomBasedTestModuleDescriptor}.
     */
    public static class MetaClass
        implements Resource.MetaClass {

        @Override
        public boolean isWorkspace(final Path path) {
            return PomWorkspaces.isMavenWorkspaceRoot(path);
        }

        @Override
        public boolean isDetectedIn(final Project project) {
            return PomWorkspaces.isMavenWorkspaceProject(project);
        }
    }
}
