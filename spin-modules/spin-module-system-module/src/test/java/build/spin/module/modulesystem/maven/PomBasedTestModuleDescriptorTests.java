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
import build.codemodel.foundation.naming.NonCachingNameProvider;
import build.codemodel.foundation.descriptor.RequiresModuleDescriptor;
import build.codemodel.jdk.JDKCodeModel;
import build.codemodel.jdk.descriptor.JDKModuleDescriptor;
import build.spin.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link PomBasedTestModuleDescriptor} derives test module requires from a pom's
 * <em>actually-effective</em> {@code <dependency>} entries — mirroring the same
 * {@code <dependencies>}-block-and-active-profile semantics that {@link PomDependencyGraphWalker} and
 * {@link build.spin.module.modulesystem.pom.PomReader} already honor — rather than {@code
 * getElementsByTagName("dependency")} over the whole document, which also matches {@code
 * <dependencyManagement>} declarations, every {@code <profile>} regardless of activation, and even
 * plugin-scoped {@code <dependencies>}.
 */
class PomBasedTestModuleDescriptorTests {

    private static final TelemetryRecorder RECORDER = mock(TelemetryRecorder.class);
    private static final CodeModel CODE_MODEL = new JDKCodeModel(new NonCachingNameProvider());

    /**
     * These tests derive {@code requires} purely from the pom's {@code <dependency>} coordinates via
     * naming conventions, so the local repository is never actually read — an empty temp directory
     * stands in for it.
     */
    @TempDir
    private Path localRepo;

    /**
     * A {@code <dependencyManagement>}-only entry is a version pin, not an actual dependency of the
     * project — it must not contribute a test {@code requires}.
     */
    @Test
    void get_doesNotRegisterDependencyManagementOnlyEntry(@TempDir final Path workspace) throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>org.assertj</groupId>
                    <artifactId>assertj-core</artifactId>
                    <version>3.25.0</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);

        assertThat(requiredModuleNames(workspace)).noneMatch(name -> name.contains("assertj"));
    }

    /**
     * A dependency declared only inside a {@code <profile>} that is never activated (no {@code
     * activeByDefault}, and its activation property is never set) must not contribute a test
     * {@code requires} — the profile never actually applies.
     */
    @Test
    void get_doesNotRegisterDependencyFromInactiveProfile(@TempDir final Path workspace) throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <profiles>
                <profile>
                  <id>never-active</id>
                  <activation>
                    <property>
                      <name>flag-that-is-never-set</name>
                    </property>
                  </activation>
                  <dependencies>
                    <dependency>
                      <groupId>org.assertj</groupId>
                      <artifactId>assertj-core</artifactId>
                      <version>3.25.0</version>
                    </dependency>
                  </dependencies>
                </profile>
              </profiles>
            </project>
            """);

        assertThat(requiredModuleNames(workspace)).noneMatch(name -> name.contains("assertj"));
    }

    /**
     * A {@code <dependency>} declared inside a build plugin's own {@code <dependencies>} (e.g. an
     * extra classpath entry for {@code maven-antrun-plugin}) is a plugin classpath concern, not a
     * project dependency — it must not contribute a test {@code requires}.
     */
    @Test
    void get_doesNotRegisterPluginDependency(@TempDir final Path workspace) throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <build>
                <plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-antrun-plugin</artifactId>
                    <dependencies>
                      <dependency>
                        <groupId>org.assertj</groupId>
                        <artifactId>assertj-core</artifactId>
                        <version>3.25.0</version>
                      </dependency>
                    </dependencies>
                  </plugin>
                </plugins>
              </build>
            </project>
            """);

        assertThat(requiredModuleNames(workspace)).noneMatch(name -> name.contains("assertj"));
    }

    /**
     * Sanity control: an ordinary direct dependency must still contribute a test {@code requires}
     * under its groupId — confirms the three bug tests above are actually exercising real
     * dependency-derivation logic, not merely a no-op setup.
     */
    @Test
    void get_registersOrdinaryDirectDependency(@TempDir final Path workspace) throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>org.assertj</groupId>
                  <artifactId>assertj-core</artifactId>
                  <version>3.25.0</version>
                </dependency>
              </dependencies>
            </project>
            """);

        assertThat(requiredModuleNames(workspace)).contains("org.assertj");
    }

    /**
     * This is the regression this test exists to catch: a test-scoped dependency whose artifactId
     * has more than one hyphen segment past the groupId-matching prefix (e.g. {@code
     * base-transport-json}, whose real module name is {@code build.base.transport.json}) was
     * silently dropped — the "combined" fallback here only ever appends the artifactId's *last*
     * hyphen segment to the groupId ({@code build.base.json}), discarding the middle segment
     * ({@code transport}) entirely, and none of the other two candidates matches either. The
     * result: the dependency never becomes a synthetic {@code requires}, so
     * AbstractDetectResolution never resolves it, and test compilation fails with "package
     * ... does not exist" even though the pom declares the dependency correctly.
     */
    @Test
    void get_registersDependencyWithMultiSegmentGroupPrefixedArtifactId(@TempDir final Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), """
            <project>
              <groupId>build.base</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>build.base</groupId>
                  <artifactId>base-transport-json</artifactId>
                  <version>0.29.1-SNAPSHOT</version>
                </dependency>
              </dependencies>
            </project>
            """);

        assertThat(requiredModuleNames(workspace)).contains("build.base.transport.json");
    }

    private List<String> requiredModuleNames(final Path workspace) throws Exception {
        final Project project = mock(Project.class);
        when(project.path()).thenReturn(workspace);
        when(project.name()).thenReturn("root");

        final ProjectPom projectPom = new ProjectPom(LocalMavenRepository.of(this.localRepo), RECORDER);

        final PomBasedTestModuleDescriptor descriptor = new PomBasedTestModuleDescriptor();
        inject(descriptor, "recorder", RECORDER);
        inject(descriptor, "codeModel", CODE_MODEL);
        inject(descriptor, "projectPom", projectPom);

        final JDKModuleDescriptor result = descriptor.get(project);
        return result.requiresClauses()
            .map(RequiresModuleDescriptor::requiresModuleName)
            .map(Object::toString)
            .toList();
    }

    private static void inject(final Object target, final String fieldName, final Object value) throws Exception {
        final Field field = PomBasedTestModuleDescriptor.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
