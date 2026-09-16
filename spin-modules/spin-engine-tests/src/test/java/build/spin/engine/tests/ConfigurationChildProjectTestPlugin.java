package build.spin.engine.tests;

/*-
 * #%L
 * Spin Engine Tests
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

import build.spin.Plugin;
import build.spin.Task;
import build.spin.module.configuration.Configuration;
import build.spin.module.configuration.Source;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test plugin, detected only in a child {@link build.spin.Project} nested beneath the
 * {@link build.spin.Program}'s root {@link build.spin.Project}, whose {@link MainTask} injects a
 * {@link Configuration} value defined only in that child {@link build.spin.Project}'s own
 * {@code .spin/test.configuration.child.properties}. Used to verify that {@code DefaultProgram}
 * resolves such {@code Configuration} against the {@link Task}'s own (child) {@link build.spin.Project},
 * not the root {@link build.spin.Project} the {@link build.spin.Program} was created for.
 */
public class ConfigurationChildProjectTestPlugin implements Plugin {

    /**
     * Set to {@code true} by {@link MainTask} when it runs.
     */
    public static final AtomicBoolean MAIN_TASK_RAN = new AtomicBoolean(false);

    /**
     * The value {@link MainTask} observed for its {@link Configuration}-qualified injection, or
     * {@code null} if it never ran.
     */
    public static volatile String OBSERVED_VALUE;

    @Named("configuration-child-main")
    @Source("test.configuration.child")
    public static class MainTask implements Task<String> {

        @Inject
        @Configuration
        @Named("value")
        private Optional<String> value;

        public String compute() {
            MAIN_TASK_RAN.set(true);
            OBSERVED_VALUE = this.value.orElse(null);
            return this.value.orElse(null);
        }
    }

    public static class MetaClass implements Plugin.MetaClass {
        @Override
        public boolean isDetectedIn(final Path path) {
            return Files.exists(path.resolve("configuration-child.marker"));
        }
    }
}
