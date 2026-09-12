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

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.nio.file.Path;

/**
 * The filesystem location of the local Maven repository — the {@code ~/.m2/repository} artifact
 * cache that {@link build.spin.module.modulesystem.pom.PomReader} and
 * {@link PomDependencyGraphWalker} read parent and dependency poms out of.
 * <p>
 * Resolution mirrors Maven's own precedence: the {@code maven.repo.local} system property when it
 * is set to a non-blank value, otherwise {@code ${user.home}/.m2/repository}. (Honoring
 * {@code <localRepository>} from {@code settings.xml} is left to a future cross-module
 * consolidation with {@code spin-maven-module}'s settings reader.)
 * <p>
 * Injected as a {@link Singleton} so the {@code PomBased*} resources — and
 * {@code spin-java-module}'s {@code AbstractDetectResolution}, which reads project {@code pom.xml}
 * {@code <exclusions>} — share one definition rather than each re-deriving the path inline from
 * {@link System#getProperty}. Tests point it at a temporary directory via {@link #of(Path)}.
 *
 * @author reed.vonredwitz
 * @since Sep-2026
 */
@Singleton
public final class LocalMavenRepository {

    private static final String REPO_LOCAL_PROPERTY = "maven.repo.local";

    private final Path path;

    @Inject
    LocalMavenRepository() {
        this(resolveFromEnvironment());
    }

    private LocalMavenRepository(final Path path) {
        this.path = path;
    }

    /**
     * A {@link LocalMavenRepository} rooted at the given path, for tests that supply a temporary
     * local repository.
     *
     * @param path the local repository root
     * @return the {@link LocalMavenRepository}
     */
    public static LocalMavenRepository of(final Path path) {
        return new LocalMavenRepository(path);
    }

    /**
     * A {@link LocalMavenRepository} resolved from the environment, for the static workspace-build
     * helpers that run outside the DI container.
     *
     * @return the {@link LocalMavenRepository}
     */
    public static LocalMavenRepository fromEnvironment() {
        return new LocalMavenRepository(resolveFromEnvironment());
    }

    /**
     * The local repository root directory.
     *
     * @return the path
     */
    public Path path() {
        return this.path;
    }

    private static Path resolveFromEnvironment() {
        final String override = System.getProperty(REPO_LOCAL_PROPERTY);

        return override != null && !override.isBlank()
            ? Path.of(override.trim())
            : Path.of(System.getProperty("user.home"), ".m2", "repository");
    }
}
