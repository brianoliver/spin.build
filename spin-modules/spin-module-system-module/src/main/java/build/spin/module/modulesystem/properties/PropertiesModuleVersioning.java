package build.spin.module.modulesystem.properties;

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

import build.base.parsing.ParseException;
import build.base.telemetry.TelemetryRecorder;
import build.base.version.Version;
import build.codemodel.dependency.injection.PostInject;
import build.spin.Project;
import build.spin.Resource;
import build.spin.common.util.Globs;
import build.spin.module.modulesystem.ModuleVersioning;
import jakarta.inject.Inject;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A {@link Resource} providing {@link ModuleVersioning} for a {@link Project} and its descendants.
 *
 * @author brian.oliver
 * @since May-2020
 */
public class PropertiesModuleVersioning
    implements ModuleVersioning, Resource {

    /**
     * The name of the file defining {@link PropertiesModuleVersioning} information.
     */
    public static final String VERSION_PROPERTIES_FILENAME = "version.properties";

    /**
     * The {@link TelemetryRecorder} for the {@link Resource}.
     */
    private final TelemetryRecorder recorder;

    /**
     * The {@link Project} for the {@link Resource}.
     */
    private final Project project;

    /**
     * The {@link Version} information defined for the {@link Project}.
     */
    private final LinkedHashMap<Pattern, Version> versions;

    /**
     * Constructs the {@link PropertiesModuleVersioning}.
     *
     * @param project  the {@link Project}
     * @param recorder the {@link TelemetryRecorder}
     */
    @Inject
    private PropertiesModuleVersioning(final Project project, final TelemetryRecorder recorder) {
        this.project = project;
        this.recorder = recorder;
        this.versions = new LinkedHashMap<>();
    }

    /**
     * Once the {@link PropertiesModuleVersioning} is created, initialize (and cache) the available {@link Version}s.
     */
    @PostInject
    private void onInjected() {
        // attempt to read an parse the version information
        final Path versionInformation = this.project.path().resolve(VERSION_PROPERTIES_FILENAME);
        if (Files.exists(versionInformation)) {
            try (BufferedReader reader = Files.newBufferedReader(versionInformation)) {
                final Properties properties = new Properties();
                properties.load(reader);

                properties.stringPropertyNames()
                    .forEach(moduleName -> {
                        try {
                            final String moduleVersion = properties.getProperty(moduleName);

                            final Pattern pattern = Globs.toPattern(moduleName);
                            final Version version = Version.parse(moduleVersion);

                            this.versions.put(pattern, version);
                        } catch (final ParseException | PatternSyntaxException e) {
                            this.recorder.warn(e, "Failed to parse version information for [%s] in [%s]",
                                moduleName,
                                versionInformation);
                        }
                    });

                this.recorder.info("Loaded Versioning Information from [%s] for [%s]",
                    versionInformation,
                    this.project.name());
            } catch (final IOException e) {
                this.recorder.warn(e, "Failed to read versions from [%s]. Defaulting to no version information",
                    versionInformation);
            }
        }
    }

    @Override
    public Optional<Version> getVersion(final String moduleName) {
        // attempt to determine the version defined specifically for this project
        final Optional<Version> projectVersion = this.versions.entrySet().stream()
            .filter(entry -> entry.getKey().matcher(moduleName).matches())
            .map(Map.Entry::getValue)
            .findFirst();

        if (projectVersion.isPresent()) {
            return projectVersion;
        }

        // attempt to determine a version defined by a parent project
        return this.project.parent()
            .map(parent -> parent.getResource(PropertiesModuleVersioning.class))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(versioning -> versioning.getVersion(moduleName))
            .filter(Optional::isPresent)
            .map(Optional::get);
    }

    /**
     * The {@link Resource.MetaClass} for {@link PropertiesModuleVersioning}.
     */
    public static class MetaClass
        implements Resource.MetaClass {

        @Override
        public boolean isDetectedIn(final Project project) {
            return Files.exists(project.path().resolve(VERSION_PROPERTIES_FILENAME));
        }
    }
}
