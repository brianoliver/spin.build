package build.spin.module.maven;

/*-
 * #%L
 * Spin Maven Module
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

import build.spin.common.telemetry.TelemetryPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link MavenSettingsReader}.
 */
class MavenSettingsReaderTests {

    /**
     * A {@code <mirror>} substitutes a repository's id/url/credentials, but Maven preserves the
     * original {@code <repository>}'s {@code <releases>}/{@code <snapshots>} {@code <updatePolicy>} —
     * the mirror only replaces the connection endpoint. Dropping the policy here would silently fall
     * every mirrored repo back to the daily default, the common case since most {@code settings.xml}
     * route everything through a single mirror.
     */
    @Test
    void shouldPreserveUpdatePoliciesThroughMirrorSubstitution(@TempDir final Path tempDir) throws IOException {
        final Path settingsPath = tempDir.resolve("settings.xml");
        Files.writeString(settingsPath, """
            <settings>
              <profiles>
                <profile>
                  <id>default</id>
                  <activation><activeByDefault>true</activeByDefault></activation>
                  <repositories>
                    <repository>
                      <id>central</id>
                      <url>https://repo.maven.apache.org/maven2</url>
                      <releases><updatePolicy>never</updatePolicy></releases>
                      <snapshots><updatePolicy>interval:5</updatePolicy></snapshots>
                    </repository>
                  </repositories>
                </profile>
              </profiles>
              <mirrors>
                <mirror>
                  <id>internal</id>
                  <url>https://internal.example.com/maven2</url>
                  <mirrorOf>*</mirrorOf>
                </mirror>
              </mirrors>
            </settings>
            """);

        final List<RemoteRepo> repos = MavenSettingsReader.read(settingsPath,
            new TelemetryPublisher(URI.create("maven://settings-reader-test"), telemetry -> {
            }));

        assertThat(repos).hasSize(1);
        final RemoteRepo repo = repos.get(0);
        assertThat(repo.id()).isEqualTo("internal");
        assertThat(repo.url()).isEqualTo("https://internal.example.com/maven2");
        assertThat(repo.releaseUpdatePolicy()).contains("never");
        assertThat(repo.snapshotUpdatePolicy()).contains("interval:5");
    }

    /**
     * A plain, unmirrored {@code <repository><releases><updatePolicy>} must be parsed onto
     * {@link RemoteRepo#releaseUpdatePolicy()}, independently of the {@code <snapshots>} policy (or
     * lack thereof).
     */
    @Test
    void shouldParseReleaseUpdatePolicyFromSettingsXml(@TempDir final Path tempDir) throws IOException {
        final Path settingsPath = tempDir.resolve("settings.xml");
        Files.writeString(settingsPath, """
            <settings>
              <profiles>
                <profile>
                  <id>default</id>
                  <activation><activeByDefault>true</activeByDefault></activation>
                  <repositories>
                    <repository>
                      <id>central</id>
                      <url>https://repo.maven.apache.org/maven2</url>
                      <releases><updatePolicy>always</updatePolicy></releases>
                    </repository>
                  </repositories>
                </profile>
              </profiles>
            </settings>
            """);

        final List<RemoteRepo> repos = MavenSettingsReader.read(settingsPath,
            new TelemetryPublisher(URI.create("maven://settings-reader-test"), telemetry -> {
            }));

        assertThat(repos).hasSize(1);
        final RemoteRepo repo = repos.get(0);
        assertThat(repo.id()).isEqualTo("central");
        assertThat(repo.releaseUpdatePolicy()).contains("always");
        assertThat(repo.snapshotUpdatePolicy()).isEmpty();
    }
}
