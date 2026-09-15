/*-
 * #%L
 * Spin Integration Tests
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

import java.nio.file.Path;

/**
 * Prints the basename of the executable that launched {@code spin exec} itself (this process's
 * parent, not this process) -- used to prove spin's own self-hosted jlink image execs under its
 * configured {@code process-name} (see
 * {@code spin/.spin/build.spin.module.jlink.properties}), not plain {@code java}.
 */
public class HelloWorld {

    public static void main(final String[] args) {
        final String comm = ProcessHandle.current().parent()
            .flatMap(parent -> parent.info().command())
            .map(Path::of)
            .map(Path::getFileName)
            .map(Path::toString)
            .orElse("");
        System.out.println(comm);
    }
}
