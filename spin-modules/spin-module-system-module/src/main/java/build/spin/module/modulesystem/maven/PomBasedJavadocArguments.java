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
import build.spin.module.modulesystem.JavadocArguments;
import build.spin.module.modulesystem.pom.ConfigNode;
import build.spin.module.modulesystem.pom.GA;

import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A {@link JavadocArguments} {@link Resource} that derives {@code javadoc} argument tokens from
 * the {@code maven-javadoc-plugin} configuration in a project's {@code pom.xml}, with full
 * parent-pom inheritance and {@code <pluginManagement>} merging.
 * <p>
 * Recognized configuration elements:
 * <ul>
 *   <li>{@code <release>N</release>} → {@code --release N}</li>
 *   <li>{@code <source>N</source>} → {@code -source N} (only when {@code <release>} is absent)</li>
 *   <li>{@code <doclint>VALUE</doclint>} → {@code -Xdoclint:VALUE}</li>
 *   <li>{@code <additionalOptions>} — accepts both shapes used in real-world poms:
 *       a list of {@code <additionalOption>} children (each child's text becomes one token), or
 *       a flat text value that is whitespace-split into tokens</li>
 * </ul>
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public class PomBasedJavadocArguments
    extends AbstractPomBasedArguments
    implements JavadocArguments {

    private static final GA JAVADOC = new GA("org.apache.maven.plugins", "maven-javadoc-plugin");

    @Override
    protected GA pluginGA() {
        return JAVADOC;
    }

    @Override
    protected Stream<String> toArgs(final ConfigNode config) {
        final boolean hasRelease = config.textChild("release").isPresent();
        return Stream.of(
            config.flagIfPresent("release", "--release"),
            hasRelease ? Stream.<String>empty() : config.flagIfPresent("source", "-source"),
            config.textChild("doclint").map(v -> "-Xdoclint:" + v).stream(),
            additionalOptions(config)
        ).flatMap(s -> s);
    }

    /**
     * Reads {@code <additionalOptions>} in both shapes used in real-world poms: a list of
     * {@code <additionalOption>} children (each child's text becomes one token), or a flat text
     * value that is whitespace-split into tokens.
     */
    private static Stream<String> additionalOptions(final ConfigNode config) {
        return config.child("additionalOptions").stream().flatMap(opts -> {
            final Stream<String> childForm = opts.children().stream()
                .filter(c -> "additionalOption".equals(c.name()))
                .map(ConfigNode::text)
                .flatMap(Optional::stream);
            if (!opts.children().isEmpty()) {
                return childForm;
            }
            return opts.text().stream()
                .flatMap(text -> Stream.of(text.trim().split("\\s+")))
                .filter(s -> !s.isEmpty());
        });
    }

    /**
     * The {@link Resource.MetaClass} for {@link PomBasedJavadocArguments}.
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
