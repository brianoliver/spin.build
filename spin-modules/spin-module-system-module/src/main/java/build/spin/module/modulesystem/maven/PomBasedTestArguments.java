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
import build.spin.module.modulesystem.TestArguments;
import build.spin.module.modulesystem.pom.ConfigNode;
import build.spin.module.modulesystem.pom.GA;

import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * A {@link TestArguments} {@link Resource} that derives test JVM arguments from the
 * {@code maven-surefire-plugin} {@code <argLine>} declared in a project's {@code pom.xml}, with
 * full parent-pom inheritance and {@code <pluginManagement>} merging.
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public class PomBasedTestArguments
    extends AbstractPomBasedArguments
    implements TestArguments {

    private static final GA SUREFIRE = new GA("org.apache.maven.plugins", "maven-surefire-plugin");

    @Override
    protected GA pluginGA() {
        return SUREFIRE;
    }

    @Override
    protected Stream<String> toArgs(final ConfigNode configuration) {
        return configuration.textChild("argLine").stream()
            .flatMap(raw -> Stream.of(raw.trim().split("\\s+")))
            .filter(s -> !s.isEmpty());
    }

    /**
     * The {@link Resource.MetaClass} for {@link PomBasedTestArguments}.
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
