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
import build.spin.module.modulesystem.CompilerArguments;
import build.spin.module.modulesystem.pom.ConfigNode;
import build.spin.module.modulesystem.pom.GA;

import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A {@link CompilerArguments} {@link Resource} that derives {@code javac} argument tokens from
 * the {@code maven-compiler-plugin} configuration in a project's {@code pom.xml}, with full
 * parent-pom inheritance and {@code <pluginManagement>} merging.
 * <p>
 * Recognized configuration elements:
 * <ul>
 *   <li>{@code <release>N</release>} → {@code --release N}</li>
 *   <li>{@code <source>N</source>} → {@code -source N} (only when {@code <release>} is absent)</li>
 *   <li>{@code <target>N</target>} → {@code -target N} (only when {@code <release>} is absent)</li>
 *   <li>{@code <enablePreview>true</enablePreview>} → {@code --enable-preview}</li>
 *   <li>{@code <compilerArgs>} children: each {@code <arg>} or {@code <compilerArg>} text content
 *       is emitted as a single token</li>
 * </ul>
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public class PomBasedCompilerArguments
    extends AbstractPomBasedArguments
    implements CompilerArguments {

    private static final GA COMPILER = new GA("org.apache.maven.plugins", "maven-compiler-plugin");

    @Override
    protected GA pluginGA() {
        return COMPILER;
    }

    @Override
    protected Stream<String> toArgs(final ConfigNode config) {
        final boolean hasRelease = config.textChild("release").isPresent();
        final Stream<String> compilerArgs = config.child("compilerArgs").stream()
            .flatMap(args -> args.children().stream())
            .filter(c -> "arg".equals(c.name()) || "compilerArg".equals(c.name()))
            .map(ConfigNode::text)
            .flatMap(Optional::stream);

        return Stream.of(
            config.flagIfPresent("release", "--release"),
            hasRelease ? Stream.<String>empty() : config.flagIfPresent("source", "-source"),
            hasRelease ? Stream.<String>empty() : config.flagIfPresent("target", "-target"),
            config.booleanFlag("enablePreview", "--enable-preview"),
            compilerArgs
        ).flatMap(s -> s);
    }

    /**
     * The {@link Resource.MetaClass} for {@link PomBasedCompilerArguments}.
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
