package build.spin;

/*-
 * #%L
 * Spin API
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

import jakarta.inject.Named;

import java.util.Objects;

/**
 * Defines a reference to a {@link Class} of {@link Task} in a specific {@link Project}.
 *
 * @author brian.oliver
 * @since Jun-2019
 */
public interface Reference {

    /**
     * Creates a {@link Reference} for a {@link Class} of {@link Task} in a specific {@link Project}.
     *
     * @param project the {@link Project}
     * @param taskClass the {@link Class} of {@link Task}
     *
     * @return a new {@link Reference}
     */
    static Reference of(final Project project, final Class<? extends Task<?>> taskClass) {
        return new Reference() {
            @Override
            public Project project() {
                return project;
            }

            @Override
            public Class<? extends Task<?>> getTaskClass() {
                return taskClass;
            }

            @Override
            public String toString() {
                return project.qualifiedName() + "/" + taskDisplayName();
            }

            @Override
            public int hashCode() {
                return project.name().hashCode() + Objects.hashCode(getPluginClass()) + taskClass.hashCode();
            }

            @Override
            public boolean equals(final Object object) {
                return object instanceof Reference
                    && project().equals(((Reference) object).project())
                    && Objects.equals(getPluginClass(), ((Reference) object).getPluginClass())
                    && getTaskClass().equals(((Reference) object).getTaskClass());
            }
        };
    }

    /**
     * Obtains the {@link Project} in which the {@link Task} is defined.
     *
     * @return the {@link Project}
     */
    Project project();

    /**
     * Obtains the {@link Class} of the {@link Task}.
     *
     * @return the {@link Class} of the {@link Task}
     */
    Class<? extends Task<?>> getTaskClass();

    /**
     * Obtains the {@link Class} of {@link Plugin} that defines the {@link Task}.
     *
     * @return the {@link Class} of {@link Plugin}
     */
    @SuppressWarnings("unchecked")
    default Class<? extends Plugin> getPluginClass() {
        return (Class<? extends Plugin>) getTaskClass().getDeclaringClass();
    }

    /**
     * Obtains the display name of the {@link Task}, qualified by its declaring {@link Plugin}'s display
     * name (see {@link Engine#pluginDisplayName}) rather than its fully-qualified {@link Class} name -
     * the {@link Plugin}'s simple name is unique enough in virtually every invocation and reads far more
     * cleanly than its fully-qualified name.
     * <p>
     * The {@link Task} name portion honors a {@code @Named} annotation on the {@link Task} {@link Class},
     * matching {@link Invocable#getTaskName()} - falling back to the simple name of the {@link Class} when
     * no {@code @Named} annotation is defined.
     *
     * @return the display name of the {@link Task}, eg: {@code JavaPlugin.Compile}
     */
    default String taskDisplayName() {
        final String pluginName = project().engine().pluginDisplayName(getPluginClass());
        final Named named = getTaskClass().getAnnotation(Named.class);
        final String taskName = named == null ? getTaskClass().getSimpleName() : named.value().trim();

        return pluginName + "." + taskName;
    }
}
