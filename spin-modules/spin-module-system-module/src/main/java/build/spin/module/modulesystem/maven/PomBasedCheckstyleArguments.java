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

import build.spin.Project;
import build.spin.Resource;
import build.spin.module.modulesystem.CheckstyleArguments;
import build.spin.module.modulesystem.pom.GA;

import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A {@link CheckstyleArguments} {@link Resource} that derives Checkstyle configuration from the
 * {@code maven-checkstyle-plugin} configuration in a project's {@code pom.xml}, with full
 * parent-pom inheritance and {@code <pluginManagement>} merging.
 * <p>
 * {@code <configLocation>} supplies the configuration file path, resolved relative to the
 * project's own directory when not absolute. The plugin's own {@code <dependencies>} supply
 * extra check artifacts (e.g. a custom checks jar), each requiring an explicit or
 * {@code <pluginManagement>}-inherited version — dependencies with no resolvable version are
 * skipped, since there is no Checkstyle-plugin-level {@code <dependencyManagement>} to fall back
 * to.
 *
 * @author reed.vonredwitz
 * @since Jul-2026
 */
public class PomBasedCheckstyleArguments
    extends AbstractPomBasedResource
    implements CheckstyleArguments {

    private static final GA MAVEN_CHECKSTYLE_PLUGIN = new GA("org.apache.maven.plugins", "maven-checkstyle-plugin");

    @Override
    protected GA pluginGA() {
        return MAVEN_CHECKSTYLE_PLUGIN;
    }

    @Override
    public Optional<Path> configurationPath(final Project project) {
        return plugin(project)
            .flatMap(p -> p.configuration().textChild("configLocation"))
            .map(location -> {
                final Path configuredPath = Path.of(location);
                return configuredPath.isAbsolute() ? configuredPath : project.path().resolve(configuredPath);
            });
    }

    @Override
    public Stream<String> additionalCheckArtifacts(final Project project) {
        return plugin(project).stream()
            .flatMap(p -> p.dependencies().stream())
            .flatMap(dependency -> dependency.version().stream()
                .map(version -> dependency.groupId() + ":" + dependency.artifactId() + ":" + version));
    }

    @Override
    public boolean includeTestSourceDirectory(final Project project) {
        return plugin(project)
            .flatMap(p -> p.configuration().textChild("includeTestSourceDirectory"))
            .filter("true"::equalsIgnoreCase)
            .isPresent();
    }

    /**
     * The {@link Resource.MetaClass} for {@link PomBasedCheckstyleArguments}.
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
