package build.spin.module.modulesystem.pom;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Targeted unit tests for {@link PomReader}.
 */
class PomReaderTests {

    private static final TelemetryRecorder RECORDER = mock(TelemetryRecorder.class);

    /**
     * An explicit {@code <scope>} on a {@code <dependency>} must win over
     * {@code <dependencyManagement>}, even when that explicit value happens to equal the Maven
     * default ({@code compile}).
     * <p>
     * Here the dependency explicitly declares {@code <scope>compile</scope>} while
     * {@code dependencyManagement} manages the same GA at {@code provided}. A prior version of
     * {@code PomReader} decided whether to apply the managed scope by comparing the
     * already-defaulted scope to the literal string {@code "compile"}, which conflated "no scope
     * declared" with "scope explicitly declared as compile" and incorrectly overrode the explicit
     * {@code compile} with the managed {@code provided}. {@code PomReader#toEffectiveDependency}
     * now checks the raw, pre-default scope text instead, so the explicit value is preserved.
     */
    @Test
    void read_honorsExplicitCompileScopeOverManagedScope(@TempDir final Path dir) throws Exception {
        final Path pomXml = dir.resolve("pom.xml");
        Files.writeString(pomXml, """
            <project>
              <groupId>com.example</groupId>
              <artifactId>consumer</artifactId>
              <version>1.0.0</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.example</groupId>
                    <artifactId>lib</artifactId>
                    <version>2.0.0</version>
                    <scope>provided</scope>
                  </dependency>
                </dependencies>
              </dependencyManagement>
              <dependencies>
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>lib</artifactId>
                  <scope>compile</scope>
                </dependency>
              </dependencies>
            </project>
            """);

        final PomReader reader = new PomReader(dir, RECORDER);
        final Optional<Pom> pom = reader.read(pomXml);

        assertThat(pom).isPresent();
        final Dependency lib = pom.get().dependencies().stream()
            .filter(d -> "lib".equals(d.artifactId()))
            .findFirst()
            .orElseThrow();

        assertThat(lib.scope()).isEqualTo(DependencyScope.COMPILE);
    }

    /**
     * Same as {@link #read_honorsExplicitCompileScopeOverManagedScope} but for {@code <type>}: an
     * explicit {@code <type>jar</type>} must win over a differing managed type, even though
     * {@code "jar"} is also the Maven default applied when no {@code <type>} is declared at all.
     */
    @Test
    void read_honorsExplicitJarTypeOverManagedType(@TempDir final Path dir) throws Exception {
        final Path pomXml = dir.resolve("pom.xml");
        Files.writeString(pomXml, """
            <project>
              <groupId>com.example</groupId>
              <artifactId>consumer</artifactId>
              <version>1.0.0</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.example</groupId>
                    <artifactId>lib</artifactId>
                    <version>2.0.0</version>
                    <type>test-jar</type>
                  </dependency>
                </dependencies>
              </dependencyManagement>
              <dependencies>
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>lib</artifactId>
                  <type>jar</type>
                </dependency>
              </dependencies>
            </project>
            """);

        final PomReader reader = new PomReader(dir, RECORDER);
        final Optional<Pom> pom = reader.read(pomXml);

        assertThat(pom).isPresent();
        final Dependency lib = pom.get().dependencies().stream()
            .filter(d -> "lib".equals(d.artifactId()))
            .findFirst()
            .orElseThrow();

        assertThat(lib.type()).isEqualTo(PackagingType.Standard.JAR);
    }

    /**
     * {@code <artifactId>} must be interpolated like {@code <groupId>} and {@code <version>} are —
     * a pom that writes {@code <artifactId>${module.name}</artifactId>} should resolve it against
     * its own {@code <properties>}, not carry the literal {@code ${module.name}} through into the
     * effective pom's {@code artifactId()} and {@code project.artifactId} property.
     */
    @Test
    void read_interpolatesArtifactIdProperty(@TempDir final Path dir) throws Exception {
        final Path pomXml = dir.resolve("pom.xml");
        Files.writeString(pomXml, """
            <project>
              <groupId>com.example</groupId>
              <artifactId>${module.name}</artifactId>
              <version>1.0.0</version>
              <properties>
                <module.name>my-module</module.name>
              </properties>
            </project>
            """);

        final PomReader reader = new PomReader(dir, RECORDER);
        final Optional<Pom> pom = reader.read(pomXml);

        assertThat(pom).isPresent();
        assertThat(pom.get().artifactId()).isEqualTo("my-module");
        assertThat(pom.get().properties()).containsEntry("project.artifactId", "my-module");
    }

    /**
     * A plugin {@code <dependency>} redeclared under {@code <build><plugins>} with only its GA (no
     * {@code <version>}), to inherit the version from the matching
     * {@code <pluginManagement><plugins>} entry, should end up with that managed version —
     * mirroring real Maven's field-level dependency merge (see
     * {@link #read_honorsExplicitCompileScopeOverManagedScope} for the project-dependency
     * equivalent).
     */
    @Test
    void read_pluginDependencyShouldInheritManagedVersion(@TempDir final Path dir) throws Exception {
        final Path pomXml = dir.resolve("pom.xml");
        Files.writeString(pomXml, """
            <project>
              <groupId>com.example</groupId>
              <artifactId>consumer</artifactId>
              <version>1.0.0</version>
              <build>
                <pluginManagement>
                  <plugins>
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-checkstyle-plugin</artifactId>
                      <dependencies>
                        <dependency>
                          <groupId>com.example</groupId>
                          <artifactId>custom-checks</artifactId>
                          <version>2.0.0</version>
                        </dependency>
                      </dependencies>
                    </plugin>
                  </plugins>
                </pluginManagement>
                <plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-checkstyle-plugin</artifactId>
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>custom-checks</artifactId>
                      </dependency>
                    </dependencies>
                  </plugin>
                </plugins>
              </build>
            </project>
            """);

        final PomReader reader = new PomReader(dir, RECORDER);
        final Optional<Pom> pom = reader.read(pomXml);

        assertThat(pom).isPresent();
        final Plugin checkstylePlugin = pom.get()
            .plugin(new GA("org.apache.maven.plugins", "maven-checkstyle-plugin"))
            .orElseThrow();
        final Dependency customChecks = checkstylePlugin.dependencies().stream()
            .filter(d -> "custom-checks".equals(d.artifactId()))
            .findFirst()
            .orElseThrow();

        assertThat(customChecks.version()).contains("2.0.0");
    }

    /**
     * {@code PomReader} must seed Maven's own {@code <build>} defaults —
     * {@code ${project.build.directory}} ({@code <basedir>/target}) and
     * {@code ${project.build.finalName}} ({@code ${artifactId}-${version}}) — into the effective
     * properties, exactly as it already seeds {@code project.basedir}, whenever the pom does not
     * declare its own {@code <build><directory>} / {@code <build><finalName>}.
     */
    @Test
    void read_seedsMavenBuildDirectoryAndFinalNameDefaults(@TempDir final Path dir) throws Exception {
        final Path pomXml = dir.resolve("pom.xml");
        Files.writeString(pomXml, """
            <project>
              <groupId>com.example</groupId>
              <artifactId>widget</artifactId>
              <version>1.2.3</version>
            </project>
            """);

        final PomReader reader = new PomReader(dir, RECORDER);
        final Optional<Pom> pom = reader.read(pomXml);

        assertThat(pom).isPresent();
        assertThat(pom.get().properties())
            .containsEntry("project.build.directory", dir.resolve("target").toString())
            .containsEntry("project.build.finalName", "widget-1.2.3");
    }

    /**
     * When the pom declares its own {@code <build><directory>} / {@code <build><finalName>}, that
     * explicit value wins over Maven's default — the seeding must not clobber it. A relative
     * {@code <directory>} resolves against {@code <basedir>} and both interpolate against the
     * effective properties.
     */
    @Test
    void read_explicitBuildDirectoryAndFinalNameWinOverDefaults(@TempDir final Path dir) throws Exception {
        final Path pomXml = dir.resolve("pom.xml");
        Files.writeString(pomXml, """
            <project>
              <groupId>com.example</groupId>
              <artifactId>widget</artifactId>
              <version>1.2.3</version>
              <build>
                <directory>${project.basedir}/build/out</directory>
                <finalName>${project.artifactId}-final</finalName>
              </build>
            </project>
            """);

        final PomReader reader = new PomReader(dir, RECORDER);
        final Optional<Pom> pom = reader.read(pomXml);

        assertThat(pom).isPresent();
        assertThat(pom.get().properties())
            .containsEntry("project.build.directory", dir.resolve("build/out").toString())
            .containsEntry("project.build.finalName", "widget-final");
    }

    /**
     * Regression: a plugin {@code <configuration>} that references {@code ${project.build.directory}}
     * (the way {@code spin-java-module-tests}' surefire {@code <argLine>} did, via
     * {@code @${project.build.directory}/...args}) must come back fully interpolated, not carrying
     * the literal {@code ${project.build.directory}} token through to consumers — where it would
     * later blow up as {@code PropertyNotFoundException: Base object is null for property: build}
     * when re-evaluated by an EL processor that has no {@code project} binding.
     */
    @Test
    void read_interpolatesProjectBuildDirectoryInPluginConfiguration(@TempDir final Path dir) throws Exception {
        final Path pomXml = dir.resolve("pom.xml");
        Files.writeString(pomXml, """
            <project>
              <groupId>com.example</groupId>
              <artifactId>consumer</artifactId>
              <version>1.0.0</version>
              <build>
                <plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <configuration>
                      <argLine>@${project.build.directory}/test.args</argLine>
                    </configuration>
                  </plugin>
                </plugins>
              </build>
            </project>
            """);

        final PomReader reader = new PomReader(dir, RECORDER);
        final Optional<Pom> pom = reader.read(pomXml);

        assertThat(pom).isPresent();
        final Plugin surefire = pom.get()
            .plugin(new GA("org.apache.maven.plugins", "maven-surefire-plugin"))
            .orElseThrow();

        assertThat(surefire.configuration().textChild("argLine"))
            .contains("@" + dir.resolve("target") + "/test.args");
    }

    /**
     * The seeded {@code project.build.directory} / {@code project.build.finalName} must be this
     * pom's own — a child module resolves {@code ${project.build.directory}} to <em>its</em>
     * {@code target}, not the parent's. The parent's already-computed values flow in via the
     * effective-properties merge, so these must be re-seeded with an unconditional {@code put}
     * (like {@code project.basedir}), never {@code putIfAbsent}.
     */
    @Test
    void read_childPomBuildDirectoryIsNotShadowedByParent(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
            </project>
            """);

        final Path childDir = Files.createDirectory(dir.resolve("child"));
        final Path childPom = childDir.resolve("pom.xml");
        Files.writeString(childPom, """
            <project>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>1.0.0</version>
                <relativePath>../pom.xml</relativePath>
              </parent>
              <artifactId>child</artifactId>
              <build>
                <plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <configuration>
                      <argLine>@${project.build.directory}/test.args</argLine>
                    </configuration>
                  </plugin>
                </plugins>
              </build>
            </project>
            """);

        final PomReader reader = new PomReader(dir, RECORDER);
        final Optional<Pom> pom = reader.read(childPom);

        assertThat(pom).isPresent();
        assertThat(pom.get().properties())
            .containsEntry("project.build.directory", childDir.resolve("target").toString())
            .containsEntry("project.build.finalName", "child-1.0.0");

        final Plugin surefire = pom.get()
            .plugin(new GA("org.apache.maven.plugins", "maven-surefire-plugin"))
            .orElseThrow();
        assertThat(surefire.configuration().textChild("argLine"))
            .contains("@" + childDir.resolve("target") + "/test.args");
    }

    /**
     * A child pom's effective properties must expose its resolved parent's coordinates as
     * {@code project.parent.groupId} / {@code project.parent.artifactId} /
     * {@code project.parent.version}, mirroring the {@code project.groupId} /
     * {@code project.artifactId} / {@code project.version} seeded for the pom itself.
     */
    @Test
    void read_seedsProjectParentPropertiesWhenParentPresent(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
            </project>
            """);

        final Path childDir = Files.createDirectory(dir.resolve("child"));
        final Path childPom = childDir.resolve("pom.xml");
        Files.writeString(childPom, """
            <project>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>1.0.0</version>
                <relativePath>../pom.xml</relativePath>
              </parent>
              <artifactId>child</artifactId>
            </project>
            """);

        final PomReader reader = new PomReader(dir, RECORDER);
        final Optional<Pom> pom = reader.read(childPom);

        assertThat(pom).isPresent();
        assertThat(pom.get().properties())
            .containsEntry("project.parent.groupId", "com.example")
            .containsEntry("project.parent.artifactId", "parent")
            .containsEntry("project.parent.version", "1.0.0");
    }

    /**
     * A pom with no {@code <parent>} must not fabricate {@code project.parent.*} properties —
     * unlike {@code project.groupId} etc., which always have a value, these are only meaningful
     * when a parent actually exists.
     */
    @Test
    void read_omitsProjectParentPropertiesWhenNoParent(@TempDir final Path dir) throws Exception {
        final Path pomXml = dir.resolve("pom.xml");
        Files.writeString(pomXml, """
            <project>
              <groupId>com.example</groupId>
              <artifactId>standalone</artifactId>
              <version>1.0.0</version>
            </project>
            """);

        final PomReader reader = new PomReader(dir, RECORDER);
        final Optional<Pom> pom = reader.read(pomXml);

        assertThat(pom).isPresent();
        assertThat(pom.get().properties())
            .doesNotContainKeys("project.parent.groupId", "project.parent.artifactId", "project.parent.version");
    }

    /**
     * Regression companion to {@link #read_interpolatesProjectBuildDirectoryInPluginConfiguration}:
     * a plugin {@code <configuration>} referencing {@code ${project.parent.version}} must come back
     * fully interpolated rather than carrying the literal placeholder through.
     */
    @Test
    void read_interpolatesProjectParentVersionInPluginConfiguration(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>3.4.5</version>
              <packaging>pom</packaging>
            </project>
            """);

        final Path childDir = Files.createDirectory(dir.resolve("child"));
        final Path childPom = childDir.resolve("pom.xml");
        Files.writeString(childPom, """
            <project>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>3.4.5</version>
                <relativePath>../pom.xml</relativePath>
              </parent>
              <artifactId>child</artifactId>
              <build>
                <plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <configuration>
                      <argLine>-Dparent.version=${project.parent.version}</argLine>
                    </configuration>
                  </plugin>
                </plugins>
              </build>
            </project>
            """);

        final PomReader reader = new PomReader(dir, RECORDER);
        final Optional<Pom> pom = reader.read(childPom);

        assertThat(pom).isPresent();
        final Plugin surefire = pom.get()
            .plugin(new GA("org.apache.maven.plugins", "maven-surefire-plugin"))
            .orElseThrow();
        assertThat(surefire.configuration().textChild("argLine"))
            .contains("-Dparent.version=3.4.5");
    }

    /**
     * Maven recognizes the bare {@code ${basedir}} built-in alongside the {@code project.}-prefixed
     * form. A plugin {@code <configuration>} referencing {@code ${basedir}} (as this repo's own root
     * {@code pom.xml} does for {@code maven-resources-plugin}'s {@code <outputDirectory>}) must
     * interpolate to the pom's directory, not carry the literal placeholder through.
     */
    @Test
    void read_interpolatesBareBasedirInPluginConfiguration(@TempDir final Path dir) throws Exception {
        final Path pomXml = dir.resolve("pom.xml");
        Files.writeString(pomXml, """
            <project>
              <groupId>com.example</groupId>
              <artifactId>consumer</artifactId>
              <version>1.0.0</version>
              <build>
                <plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-resources-plugin</artifactId>
                    <configuration>
                      <outputDirectory>${basedir}/target/classes</outputDirectory>
                    </configuration>
                  </plugin>
                </plugins>
              </build>
            </project>
            """);

        final PomReader reader = new PomReader(dir, RECORDER);
        final Optional<Pom> pom = reader.read(pomXml);

        assertThat(pom).isPresent();
        final Plugin resourcesPlugin = pom.get()
            .plugin(new GA("org.apache.maven.plugins", "maven-resources-plugin"))
            .orElseThrow();

        assertThat(resourcesPlugin.configuration().textChild("outputDirectory"))
            .contains(dir.resolve("target/classes").toString());
    }
}
