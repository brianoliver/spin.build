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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LocalMavenRepository}'s environment resolution — the {@code maven.repo.local}
 * override precedence and the {@code ${user.home}/.m2/repository} fallback.
 */
class LocalMavenRepositoryTests {

    private static final String REPO_LOCAL_PROPERTY = "maven.repo.local";

    private final Optional<String> originalOverride =
        Optional.ofNullable(System.getProperty(REPO_LOCAL_PROPERTY));

    @AfterEach
    void restoreProperty() {
        this.originalOverride.ifPresentOrElse(
            value -> System.setProperty(REPO_LOCAL_PROPERTY, value),
            () -> System.clearProperty(REPO_LOCAL_PROPERTY));
    }

    @Test
    void fromEnvironment_honorsMavenRepoLocalWhenSet() {
        System.setProperty(REPO_LOCAL_PROPERTY, "/custom/repo");

        assertThat(LocalMavenRepository.fromEnvironment().path())
            .isEqualTo(Path.of("/custom/repo"));
    }

    @Test
    void fromEnvironment_trimsSurroundingWhitespaceFromOverride() {
        System.setProperty(REPO_LOCAL_PROPERTY, "  /custom/repo  ");

        assertThat(LocalMavenRepository.fromEnvironment().path())
            .isEqualTo(Path.of("/custom/repo"));
    }

    @Test
    void fromEnvironment_ignoresBlankOverrideAndFallsBackToUserHome() {
        System.setProperty(REPO_LOCAL_PROPERTY, "   ");

        assertThat(LocalMavenRepository.fromEnvironment().path())
            .isEqualTo(Path.of(System.getProperty("user.home"), ".m2", "repository"));
    }

    @Test
    void fromEnvironment_fallsBackToUserHomeWhenOverrideAbsent() {
        System.clearProperty(REPO_LOCAL_PROPERTY);

        assertThat(LocalMavenRepository.fromEnvironment().path())
            .isEqualTo(Path.of(System.getProperty("user.home"), ".m2", "repository"));
    }

    @Test
    void of_usesTheGivenPathVerbatim() {
        final Path repo = Path.of("/tmp/some-temp-repo");

        assertThat(LocalMavenRepository.of(repo).path()).isEqualTo(repo);
    }
}
