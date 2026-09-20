package build.spin.module.java;

/*-
 * #%L
 * Spin Java Module
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

import build.codemodel.foundation.descriptor.RequiresModuleDescriptor;
import build.codemodel.jdk.descriptor.JDKModuleDescriptor;
import build.codemodel.jdk.descriptor.RequiresModifier;
import build.spawn.jdk.JDK;
import build.spin.Project;
import build.spin.module.modulesystem.Artifact;
import build.spin.module.modulesystem.ModuleCatalog;
import build.spin.module.modulesystem.ModuleReference;
import build.spin.module.modulesystem.ModuleVersioning;
import build.spin.option.BuildDirectoryName;
import build.spin.option.TargetDirectoryName;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds the {@code --processor-module-path} string for javac and javadoc invocations.
 * Discovers annotation processors from two sources:
 * <ol>
 *   <li>Workspace sibling projects that {@code provides javax.annotation.processing.Processor}</li>
 *   <li>External modules declared with {@code requires static} in the project's module descriptor</li>
 * </ol>
 */
final class AnnotationProcessorPaths {

    private static final String ANNOTATION_PROCESSOR_SERVICE = "javax.annotation.processing.Processor";

    private AnnotationProcessorPaths() {
    }

    static String build(final JDKModuleDescriptor moduleDescriptor,
                        final Project project,
                        final ModuleCatalog catalog,
                        final ModuleVersioning versioning,
                        final Artifact.Resolver resolver,
                        final BuildDirectoryName buildDirectoryName,
                        final TargetDirectoryName targetDirectoryName,
                        final JDK jdk) {

        if (moduleDescriptor.annotationClauses().findAny().isEmpty()) {
            return "";
        }

        final List<Path> paths = new ArrayList<>();
        final Set<String> visited = new HashSet<>();

        // workspace sibling projects providing javax.annotation.processing.Processor
        annotationProcessorProjects(moduleDescriptor, project)
            .forEach(prj -> collectProcessorDeps(prj, paths, visited,
                catalog, versioning, resolver, buildDirectoryName, targetDirectoryName, jdk));

        // requires static → external annotation processors resolved from catalog
        moduleDescriptor.requiresClauses()
            .filter(r -> r.traits(RequiresModifier.class).anyMatch(m -> m == RequiresModifier.STATIC))
            .filter(r -> !JavaPlatform.isJavaPlatformModule(jdk, r.requiresModuleName().toString()))
            .forEach(r -> {
                final String name = r.requiresModuleName().toString();
                if (visited.add(name)) {
                    versioning.getVersion(name)
                        .or(() -> JDKModuleDescriptor.requiresVersion(r))
                        .flatMap(v -> catalog.getArtifact(ModuleReference.of(name, v)))
                        .ifPresent(artifact -> resolver.resolveTransitive(artifact)
                            .ifPresent(resolved -> resolved.forEach(p -> {
                                if (visited.add(p.toString())) {
                                    paths.add(p);
                                }
                            })));
                }
            });

        return paths.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }

    static Stream<Project> annotationProcessorProjects(final JDKModuleDescriptor moduleDescriptor,
                                                        final Project project) {
        if (moduleDescriptor.annotationClauses().findAny().isEmpty()) {
            return Stream.empty();
        }
        return project.workspace().stream()
            .filter(prj -> prj.plugins(JavaCompilerPlugin.class)
                .findFirst()
                .map(JavaCompilerPlugin::getModuleDescriptor)
                .map(d -> d.providesClauses().anyMatch(
                    p -> ANNOTATION_PROCESSOR_SERVICE.equals(p.serviceType().toString())))
                .orElse(false));
    }

    private static void collectProcessorDeps(final Project prj,
                                             final List<Path> paths,
                                             final Set<String> visited,
                                             final ModuleCatalog catalog,
                                             final ModuleVersioning versioning,
                                             final Artifact.Resolver resolver,
                                             final BuildDirectoryName buildDirectoryName,
                                             final TargetDirectoryName targetDirectoryName,
                                             final JDK jdk) {
        prj.plugins(JavaCompilerPlugin.class).findFirst().ifPresent(plugin -> {
            final JDKModuleDescriptor desc = plugin.getModuleDescriptor();
            if (!visited.add(desc.moduleName().toString())) {
                return;
            }
            paths.add(outputPath(prj, buildDirectoryName, targetDirectoryName));
            collectTransitiveRequires(desc, paths, visited, prj, catalog, versioning, resolver,
                buildDirectoryName, targetDirectoryName, jdk);
        });
    }

    private static void collectTransitiveRequires(final JDKModuleDescriptor descriptor,
                                                  final List<Path> paths,
                                                  final Set<String> visited,
                                                  final Project project,
                                                  final ModuleCatalog catalog,
                                                  final ModuleVersioning versioning,
                                                  final Artifact.Resolver resolver,
                                                  final BuildDirectoryName buildDirectoryName,
                                                  final TargetDirectoryName targetDirectoryName,
                                                  final JDK jdk) {
        final LinkedList<RequiresModuleDescriptor> frontier = descriptor.requiresClauses()
            .filter(r -> !JavaPlatform.isJavaPlatformModule(jdk, r.requiresModuleName().toString()))
            .filter(r -> r.traits(RequiresModifier.class).noneMatch(m -> m == RequiresModifier.STATIC))
            .collect(Collectors.toCollection(LinkedList::new));

        while (!frontier.isEmpty()) {
            final RequiresModuleDescriptor req = frontier.remove();
            final String name = req.requiresModuleName().toString();
            if (!visited.add(name)) {
                continue;
            }

            final Optional<Project> sibling = findSiblingByModuleName(name, project);
            if (sibling.isPresent()) {
                paths.add(outputPath(sibling.get(), buildDirectoryName, targetDirectoryName));
                sibling.get().plugins(JavaCompilerPlugin.class).findFirst()
                    .ifPresent(p -> p.getModuleDescriptor().requiresClauses()
                        .filter(r -> !JavaPlatform.isJavaPlatformModule(jdk, r.requiresModuleName().toString()))
                        .filter(r -> !visited.contains(r.requiresModuleName().toString()))
                        .forEach(frontier::add));
            } else {
                versioning.getVersion(name).or(() -> JDKModuleDescriptor.requiresVersion(req))
                    .flatMap(v -> catalog.getArtifact(ModuleReference.of(name, v)))
                    .ifPresent(artifact -> resolver.resolveTransitive(artifact)
                        .ifPresent(paths::addAll));
            }
        }
    }

    private static Optional<Project> findSiblingByModuleName(final String moduleName,
                                                              final Project project) {
        return project.workspace().stream()
            .filter(p -> p.plugins(JavaCompilerPlugin.class).findFirst()
                .map(JavaCompilerPlugin::getModuleDescriptor)
                .map(d -> moduleName.equals(d.moduleName().toString()))
                .orElse(false))
            .findFirst();
    }

    private static Path outputPath(final Project prj,
                                   final BuildDirectoryName buildDirectoryName,
                                   final TargetDirectoryName targetDirectoryName) {
        return prj.path().resolve(buildDirectoryName.get() + "/main/" + targetDirectoryName.get());
    }
}
