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

import build.spin.Project;
import build.spin.Resource;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * A {@link Resource} that supplies the {@code javac} argument tokens a {@link Project} should be
 * compiled with. Project-shape-agnostic — implementations exist per build descriptor format.
 * <p>
 * Returned tokens are <em>resolved</em> with respect to the project's own configuration (property
 * interpolation, parent inheritance, plugin-management merging) and ready to be passed to javac
 * verbatim.
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public interface CompilerArguments
    extends Resource {

    /**
     * Returns the {@code javac} argument tokens for the given {@link Project}, in the order they
     * should be passed to the compiler. Returns an empty stream when the project declares no
     * compiler arguments.
     */
    Stream<String> get(Project project);

    /**
     * Returns whether the given {@link Project} declares an explicit preference for
     * {@code --enable-preview}, independent of {@link #get(Project)}'s opaque token stream — callers
     * that also have their own (e.g. spin-native config-driven) opinion on preview features need this
     * as one input to combine, not silently baked into a stream they can't selectively override.
     * Empty when the project declares no preference either way.
     */
    default Optional<Boolean> enablePreview(final Project project) {
        return Optional.empty();
    }
}
