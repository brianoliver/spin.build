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

import build.base.configuration.ConfigurationBuilder;
import build.base.foundation.Exceptional;
import build.base.foundation.Introspection;
import build.base.foundation.Strings;
import build.base.foundation.stream.Streams;
import build.base.io.PathSet;
import build.base.io.PathSetBuilder;
import build.base.option.JDKVersion;
import build.base.telemetry.TelemetryRecorder;
import build.base.version.Version;
import build.codemodel.foundation.CodeModel;
import build.codemodel.foundation.descriptor.RequiresModuleDescriptor;
import build.codemodel.foundation.naming.ModuleName;
import build.codemodel.jdk.descriptor.JDKModuleDescriptor;
import build.codemodel.jdk.descriptor.RequiresModifier;
import build.spawn.application.Application;
import build.spawn.application.option.Argument;
import build.spawn.application.option.Name;
import build.spawn.jdk.JDK;
import build.spawn.jdk.option.ClassPath;
import build.spawn.jdk.option.JDKHome;
import build.spawn.platform.local.LocalMachine;
import build.spin.Invocable;
import build.spin.Plugin;
import build.spin.Project;
import build.spin.Task;
import build.spin.annotation.System;
import build.spin.common.JDKTools;
import build.spin.common.ProcessRunner;
import build.spin.common.task.SourcePathKind;
import build.spin.common.util.Invocables;
import build.spin.module.modulesystem.Artifact;
import build.spin.module.modulesystem.ModuleCatalog;
import build.spin.module.modulesystem.ModuleVersioning;
import build.spin.option.BuildDirectoryName;
import build.spin.option.ReuseExternalBuildOutput;
import build.spin.option.TargetDirectoryName;
import jakarta.inject.Inject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.module.Configuration;
import java.lang.module.ResolvedModule;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A {@link Plugin} supporting the dynamic compilation and execution of custom Java-based {@link Task}s
 * for a {@link Project}.
 *
 * @author brian.oliver
 * @since Apr-2020
 */
public class CustomizationPlugin
    implements Plugin {

    @Inject
    private TelemetryRecorder recorder;

    @Inject
    private Project project;

    @Inject
    private Path path;

    @Inject
    private BuildDirectoryName buildDirectoryName;

    @Inject
    private LocalMachine machine;

    @Inject
    private Artifact.Resolver resolver;

    @Inject
    private ModuleCatalog catalog;

    @Inject
    private JDKVersion javaVersion;

    @Inject
    @System
    private JDKVersion systemJavaVersion;

    @Inject
    private TargetDirectoryName target;

    @Inject
    private ReuseExternalBuildOutput reuseExternalBuildOutput;

    @Inject
    private ModuleVersioning versioning;

    @Inject
    private CodeModel codeModel;

    @Inject
    private JavaPlatform platform;

    /**
     * The custom created {@link Invocable}s.
     */
    private final AtomicReference<ArrayList<Invocable<?>>> taskDefinitions;

    /**
     * Constructs a {@link CustomizationPlugin} {@link Plugin}.
     */
    public CustomizationPlugin() {
        this.taskDefinitions = new AtomicReference<>();
    }

    /**
     * Constructs a {@link PathSetBuilder} containing the resolved dependencies required for the compilation and
     * use of the Java-based customized {@link Task}s.
     *
     * @param stream         the {@link Stream} of initial {@link RequiresModuleDescriptor} declarations
     * @param thisModuleName the name of the module declaring {@code stream}'s requirements, used to detect cycles
     * @return the {@link PathSetBuilder}
     */
    public PathSetBuilder getDependencies(final Stream<RequiresModuleDescriptor> stream,
                                          final ModuleName thisModuleName) {

        final PathSetBuilder builder = PathSetBuilder.create();

        // the modules that have been visited (for transitive inclusion)
        final HashSet<String> includedModules = new HashSet<>();

        // establish a stack of modules to resolve and include in the ClassPath
        final Stack<RequiresModuleDescriptor> stack = new Stack<>();
        Streams.reverse(stream).forEach(stack::push);

        while (!stack.isEmpty()) {

            // obtain the next required module to resolve
            final RequiresModuleDescriptor requires = stack.pop();

            // attempt to find the required module within the root project
            final Optional<Project> dependency = this.project.workspace()
                .stream()
                .filter(prj ->
                        prj.getPlugin(Java25CompilerPlugin.class)
                            .map(java -> {
                                // include the required modules of the required module for processing when it is
                                // "transitive" and we've not already included them

                                // obtain the JDKModuleDescriptor for the project
                                final JDKModuleDescriptor moduleDescriptor = java.getModuleDescriptor();

                                // include the project iff is the required module
                                if (requires.requiresModuleName().toString().equals(moduleDescriptor.moduleName().toString())) {

                                    ModuleCycles.checkNotCyclic(moduleDescriptor, thisModuleName);

                                    if (requires.traits(RequiresModifier.class).anyMatch(m -> m == RequiresModifier.TRANSITIVE)) {

                                        if (!includedModules.contains(requires.requiresModuleName().toString())) {

                                            // place the required module back in the queue
                                            // (as the transitive requires must occur before the required module)
                                            stack.push(requires);

                                            // include all of the required modules of the required module
                                            // (if not already processed)
                                            Streams.reverse(moduleDescriptor.requiresClauses())
                                                .filter(r -> !includedModules.contains(r.requiresModuleName().toString()))
                                                .forEach(stack::push);

                                            // we've now included the transitive dependencies
                                            includedModules.add(requires.requiresModuleName().toString());

                                            return false;
                                        }
                                        else {
                                            return true;
                                        }
                                    }
                                    else {
                                        return true;
                                    }
                                }
                                else {
                                    return false;
                                }
                            })
                            .orElse(false)
                       ).findFirst();

            if (dependency.isPresent()) {
                // include the path to the dependency classes and resources -- resolved the same way
                // AbstractCompile itself decides "is this sibling already built" (spin's own .build/,
                // or an already-valid Maven/Gradle build), rather than assuming the sibling's Compile
                // task always wrote to the conventional .build/main/<target> location: it may have
                // reused an already-valid build from elsewhere instead
                final Path dependencyPath = dependency.get().path();

                AbstractDetectResolution.resolveCompiledOutput(dependencyPath, this.buildDirectoryName.get(),
                        this.target.get(), SourcePathKind.MAIN, this.reuseExternalBuildOutput)
                    .ifPresent(builder::add);
            }
            else if (!includedModules.contains(requires.requiresModuleName().toString())) {

                // determine the Version of the Module to be used
                final Version moduleVersion = JDKModuleDescriptor.requiresVersion(requires)
                    .orElseGet(() -> this.versioning.getVersion(requires.requiresModuleName().toString())
                        .orElseThrow(() -> new IllegalArgumentException(
                            "The version of module [" + requires.requiresModuleName().toString()
                                + " is not defined in Versioning (version.properties)")));

                // use the Catalog to locate the Artifact
                final build.spin.module.modulesystem.ModuleReference requiresRef =
                    build.spin.module.modulesystem.ModuleReference.of(
                        requires.requiresModuleName().toString(),
                        Optional.of(moduleVersion));
                final Optional<Artifact> optional = this.catalog.getArtifact(requiresRef, Optional.of(this.recorder));

                if (optional.isPresent()) {
                    final Artifact artifact = optional.get();

                    this.recorder.info("Discovered External Dependency [%s] to be resolved", artifact);

                    // and then the Resolver to resolve the path to it. Exceptional#ifPresent() does
                    // not behave like Optional#ifPresent() -- it returns an empty() Exceptional once
                    // the action has run successfully, so chaining .orElseThrow() after it would
                    // unconditionally throw even on success. Resolve the Path directly instead.
                    final Path resolvedPath = this.resolver.resolve(artifact)
                        .orElseThrow(() -> new IllegalStateException("Failed to resolve artifact [" + artifact + "]"));

                    this.recorder.info("External Dependency [%s] resolved to [%s]", artifact, resolvedPath);

                    // include the path to the artifact
                    builder.add(resolvedPath);

                    // attempt to resolve the ModuleDescriptor for the external dependency
                    final Exceptional<JDKModuleDescriptor> optionalDescriptor =
                        this.resolver.getModuleDescriptor(artifact, this.catalog, this.versioning);

                    // include the transitive dependencies of the external dependency
                    optionalDescriptor.ifPresent(descriptor -> {
                        ModuleCycles.checkNotCyclic(descriptor, thisModuleName);

                        Streams.reverse(descriptor.requiresClauses())
                            .filter(r -> r.traits(RequiresModifier.class)
                                .anyMatch(m -> m == RequiresModifier.TRANSITIVE))
                            .filter(r -> !includedModules.contains(r.requiresModuleName().toString()))
                            .peek(r -> this.recorder.info("Including transitive dependency [%s] for [%s]",
                                r.requiresModuleName().toString(), artifact))
                            .forEach(r -> stack.push(
                                RequiresModuleDescriptor.of(this.codeModel, r.requiresModuleName())));
                    });
                }
                else {
                    this.recorder.warn(
                        "Failed to locate [%s] in Module Catalog.  It won't be included in the classpath",
                        requires.requiresModuleName().toString());
                }
            }
        }

        return builder;
    }

    /**
     * Locates the artifacts backing Spin's own runtime, for inclusion on the customization compile
     * classpath so that {@code Build.java} can be compiled against the Spin API regardless of how
     * Spin itself was launched.
     * <ul>
     *   <li>Spin on a module path (a {@code java --module-path} launch, or a modular test runner):
     *       this plugin's {@link ModuleLayer} resolves every module to a {@code file:} location --
     *       those jars/directories are returned directly.</li>
     *   <li>Spin on a flat classpath (the Maven self-hosting bridge, an IDE run): this plugin is in
     *       the unnamed module with no layer -- fall back to {@code java.class.path}.</li>
     *   <li>Spin as a jlink runtime image ({@code -m build.spin/...}): every module resolves to a
     *       {@code jrt:} location, which cannot go on a {@code -classpath}, so this falls through to
     *       {@code java.class.path} -- empty on such a launch. {@link #spinRuntimeImage()} instead
     *       points {@code javac --system} at Spin's own image so the customization resolves the Spin
     *       API from it as system modules.</li>
     * </ul>
     *
     * @return the {@link Stream} of {@link Path}s backing Spin's runtime
     */
    private Stream<Path> spinRuntimePath() {
        final ModuleLayer layer = getClass().getModule().getLayer();

        if (layer != null) {
            final List<Path> located = resolvedModules(layer.configuration())
                .map(resolved -> resolved.reference().location())
                .flatMap(Optional::stream)
                .filter(uri -> "file".equals(uri.getScheme()))
                .map(Path::of)
                .distinct()
                .toList();

            if (!located.isEmpty()) {
                return located.stream();
            }
        }

        return classPathEntries();
    }

    /**
     * When Spin itself is running from a jlink runtime image -- its own modules linked in and
     * resolved from {@code jrt:} rather than a {@code file:} module path -- the {@link Path} of that
     * image, for {@code javac --system}. This lets a customization compile resolve the Spin API
     * ({@code build.spin.Task}, {@code Project}, ...) and {@code jakarta.inject} straight from Spin's
     * own image, so a {@code Build.java} needs no hand-written {@code module-info.java} declaring
     * them. Empty for a module-path or flat-classpath launch, where {@link #spinRuntimePath()}
     * already yields real jars.
     *
     * <p>The trade-off: {@code --system} pins the customization's system modules to exactly what
     * Spin linked into its own image, so a {@code Build.java} compiled this way sees only the JDK
     * modules Spin itself needs (plus the Spin API and {@code jakarta.inject}). A customization that
     * needs an external library must declare a {@code module-info.java} with the matching
     * {@code requires}; those clauses drive {@link #getDependencies} and land on the
     * {@code -classpath} alongside {@code --system}.
     *
     * @return the running Spin runtime image, or empty when Spin is not running from one
     */
    private Optional<Path> spinRuntimeImage() {
        final ModuleLayer layer = getClass().getModule().getLayer();

        if (layer == null) {
            return Optional.empty();
        }

        return layer.configuration()
            .findModule(getClass().getModule().getName())
            .flatMap(resolved -> resolved.reference().location())
            .filter(uri -> "jrt".equals(uri.getScheme()))
            .map(uri -> Path.of(java.lang.System.getProperty("java.home")));
    }

    /**
     * Streams every {@link ResolvedModule} of a {@link Configuration} and, transitively, of its
     * parents -- so a customization compiled from within a child {@link ModuleLayer} still sees the
     * modules resolved into the boot layer.
     *
     * @param configuration the {@link Configuration}
     * @return the {@link Stream} of {@link ResolvedModule}
     */
    private static Stream<ResolvedModule> resolvedModules(final Configuration configuration) {
        return Stream.concat(
            configuration.modules().stream(),
            configuration.parents().stream().flatMap(CustomizationPlugin::resolvedModules));
    }

    /**
     * Parses {@code java.class.path} into {@link Path}s, dropping the entries an IDE launch injects
     * (its own jars, a bundled JRE) that must never leak onto a build's classpath.
     *
     * @return the {@link Stream} of classpath {@link Path}s
     */
    private static Stream<Path> classPathEntries() {
        return Arrays.stream(java.lang.System.getProperty("java.class.path", "").split(File.pathSeparator))
            .filter(entry -> !entry.isBlank())
            .map(Path::of)
            .filter(path -> {
                final String string = path.toString();
                return !string.contains("jre") && !string.contains("idea");
            });
    }

    @Override
    @SuppressWarnings("unchecked")
    public Stream<Invocable<?>> invocables() {
        // only compile the customizations once, regardless of how many times we're asked for the Task.Definitions
        return this.taskDefinitions.updateAndGet(existing -> {
            if (existing == null) {

                // detect the source code to compile
                final PathSetBuilder builder = PathSetBuilder.create();

                final Path sourceCodePath = this.path.resolve("src/build/java");
                if (Files.exists(sourceCodePath)) {
                    try {
                        Files.walk(sourceCodePath)
                            .sorted(Comparator.reverseOrder())
                            .filter(p -> p.getFileName().toString().endsWith(".java")
                                && !p.getFileName().toString().equals("module-info.java"))
                            .forEach(builder::add);
                    }
                    catch (final IOException e) {
                        throw new RuntimeException(
                            "Failed to walk the Build Customization Source Code at " + sourceCodePath, e);
                    }
                }

                // include the Build.java in the project root
                final Path buildJavaPath = this.path.resolve("Build.java");
                if (Files.exists(buildJavaPath)) {
                    builder.add(buildJavaPath);
                }

                final PathSet sourceCode = builder.build();

                // establish the build output Path
                final Path buildPath = this.path.resolve(this.buildDirectoryName.get()).resolve("build/");
                try {
                    Files.createDirectories(buildPath);
                }
                catch (final IOException e) {
                    throw new RuntimeException("Failed to create 'build' directory [" + buildPath + "]", e);
                }

                // establish the target output path
                final Path target = buildPath.resolve(this.target.get() + "/");
                try {
                    Files.createDirectories(target);
                }
                catch (final IOException e) {
                    throw new RuntimeException("Failed to create 'target' directory [" + target + "]", e);
                }

                // create the "sources" file for "javac"
                final Path sources = buildPath.resolve("sources");
                try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(sources))) {
                    sourceCode.stream()
                        .peek(p -> this.recorder.diagnostic("Preparing customization [%s] for compilation", p))
                        .forEach(writer::println);
                }
                catch (final IOException e) {
                    throw new RuntimeException("Failed to create 'source' configuration to compile customizations", e);
                }

                // establish the JDKModuleDescriptor for the custom build
                final JDKModuleDescriptor moduleDescriptor;
                final Path moduleInfoPath = sourceCodePath.resolve("module-info.java");

                if (Files.exists(moduleInfoPath)) {

                    try (BufferedReader reader = Files.newBufferedReader(moduleInfoPath)) {
                        // the JDKModuleDescriptor contains the prerequisites
                        moduleDescriptor = JDKModuleDescriptor.parse(this.codeModel, reader);
                    }
                    catch (final IOException e) {
                        throw new RuntimeException("Failed to read [" + moduleInfoPath + "]", e);
                    }
                }
                else {
                    // establish a default JDKModuleDescriptor
                    final ModuleName buildName =
                        this.codeModel.getNameProvider().getModuleName("build").orElseThrow();
                    moduleDescriptor = this.codeModel.createModuleDescriptor(buildName, JDKModuleDescriptor::of);
                    final ModuleName engineName =
                        this.codeModel.getNameProvider().getModuleName("build.spin.engine").orElseThrow();
                    final RequiresModuleDescriptor engineReq =
                        RequiresModuleDescriptor.of(this.codeModel, engineName);
                    engineReq.addTrait(RequiresModifier.TRANSITIVE);
                    moduleDescriptor.addTrait(engineReq);
                }

                // resolve the ClassPath from the JDKModuleDescriptor Requires
                //  (we don't need the entire JDKModuleDescriptor)
                final PathSetBuilder pathSetBuilder = getDependencies(moduleDescriptor.requiresClauses()
                    .filter(requires -> !requires.requiresModuleName().toString().equals("build.spin.engine")),
                    moduleDescriptor.moduleName());

                // include the artifacts backing Spin's own runtime, so a customization can be compiled
                // against the Spin API (build.spin.Task, Project, ...) and jakarta.inject no matter how
                // Spin itself was launched. This replaces scraping System.getProperty("java.class.path"),
                // which is only populated on a flat-classpath launch and drags the launcher's incidental
                // jars (an IDE's, the Maven self-hosting bridge's, a bundled JRE) along with it. Empty
                // when Spin runs from its own jlink image -- spinRuntimeImage() covers that launch by
                // pointing javac --system at the image instead.
                final List<Path> spinRuntimePath = spinRuntimePath().toList();
                pathSetBuilder.addAll(spinRuntimePath.stream());

                final Optional<Path> spinRuntimeImage = spinRuntimeImage();

                final ClassPath classPath = ClassPath.of(pathSetBuilder.build().stream());

                this.recorder.diagnostic("Discovered Customization ClassPath");
                classPath.stream()
                    .forEach(p -> this.recorder.diagnostic("Path [%s]", p));

                // create the "options" file for "javac"
                final Path options = buildPath.resolve("options");
                try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(options))) {

                    // when Spin runs from its own jlink image, point javac at that image as its system
                    // modules -- the customization then resolves the Spin API and jakarta.inject from
                    // it directly, with no hand-written module-info.java (spinRuntimePath() is empty on
                    // such a launch: every module is jrt:, unusable on a -classpath)
                    spinRuntimeImage.ifPresent(image -> writer.println(
                        "--system " + Strings.doubleQuoteIfContainsWhiteSpace(image.toString())));

                    // include the ClassPath
                    if (!classPath.isEmpty()) {
                        final String cp = classPath.stream()
                            .map(Path::toString)
                            .collect(Collectors.joining(File.pathSeparator));

                        writer.println("-classpath " + Strings.doubleQuoteIfContainsWhiteSpace(cp));
                    }
                }
                catch (final IOException e) {
                    throw new RuntimeException("Failed to create 'options' configuration to compile customizations", e);
                }

                // detect the JDK to use for compilation. The compiled Build class is loaded straight
                // back into *this* JVM and cast to its build.spin.Task, so it has to be compiled for
                // the running JVM's version (systemJavaVersion), not the project's configured target.
                // JDK.current() would give exactly that, but it is not safe here: when this process
                // is a self-hosted, host-only-linked spin runtime image, its own "JDK" is
                // application-only and has no javac. Prefer a JavaPlatform-discovered JDK of the
                // running version, and fall back to JDK.current() only when discovery finds nothing.
                final JDK javaDevelopmentKit = this.platform
                    .getVersion(this.systemJavaVersion.major())
                    .orElseGet(JDK::current);

                // establish the "javac" executable based on the Java Development Kit
                final JDKHome javaHome = javaDevelopmentKit.home();

                // launch "javac"
                final ErrorCapture captured = new ErrorCapture();
                final ConfigurationBuilder javacConfiguration = ConfigurationBuilder.create()
                    .add(JDKTools.executable(javaHome.path(), "javac"))
                    .add(javaHome)
                    .add(Name.of("javac " + javaDevelopmentKit.version().toString()))
                    .add(Argument.of("@" + Strings.doubleQuoteIfContainsWhiteSpace(options.toString())))
                    .add(Argument.of("@" + Strings.doubleQuoteIfContainsWhiteSpace(sources.toString())))
                    .add(Argument.of("-g")) // always compile with debugging information
                    .add(Argument.of("-d"))
                    .add(Argument.of(Strings.doubleQuoteIfContainsWhiteSpace(target.toString())))
                    .add(captured.triageSubscriber(ErrorCapture::isJavacWarning, this.recorder::warn, this.recorder::error));

                try (Application javac = this.machine.launch(Application.class, javacConfiguration)) {
                    ProcessRunner.await(javac, "Customization Compilation", captured::output);
                }

                // establish a custom ClassLoader to load the compiled customizations
                final URLClassLoader classLoader;

                final PathSetBuilder urlsBuilder = PathSetBuilder.create();

                // include only the customization's own resolved dependencies -- Spin's runtime is
                // reached through this plugin's own ClassLoader (the parent of the loader built
                // below), so re-adding it here would define a second, incompatible copy of
                // build.spin.Task et al. and the cast in invocables() would fail with a ClassCastException
                urlsBuilder.addAll(classPath.stream().filter(p -> !spinRuntimePath.contains(p)));

                // include the compiled classes
                urlsBuilder.add(target);

                final URL[] urls = urlsBuilder.build().stream()
                    .map(p -> {
                        try {
                            return p.toUri().toURL();
                        }
                        catch (final MalformedURLException e) {
                            throw new RuntimeException("Failed to create custom URLClassLoader for [" + p + "]", e);
                        }
                    })
                    .toArray(URL[]::new);

                classLoader = new URLClassLoader(urls, getClass().getClassLoader());

                // obtain the Build class using the custom ClassLoader
                final Class<?> buildClass;
                try {
                    buildClass = classLoader.loadClass("Build");
                }
                catch (final ClassNotFoundException e) {
                    throw new RuntimeException("Failed to load Build.class for customizations", e);
                }

                // create Task.Definitions defined by the Build class
                return Introspection.getAll(buildClass, Class::getDeclaredClasses)
                    .filter(c -> Modifier.isPublic(c.getModifiers()))
                    .filter(c -> !Modifier.isAbstract(c.getModifiers()))
                    .filter(c -> Modifier.isStatic(c.getModifiers()))
                    .filter(Task.class::isAssignableFrom)
                    .map(c -> (Class<Task<Object>>) c)
                    .map(c -> Invocables.create(this.project, this, c))
                    .collect(Collectors.toCollection(ArrayList::new));
            }

            return existing;
        }).stream();
    }

    /**
     * The {@link Plugin.MetaClass} for {@link CustomizationPlugin}.
     */
    public static class MetaClass
        implements Plugin.MetaClass {

        @Override
        public boolean isDetectedIn(final Path path) {
            final boolean containsBuildModule = Files.exists(path.resolve("src/build/java/Build.java"));
            final boolean containsBuildPath = Files.exists(path.resolve("Build.java"))
                && !path.resolve("Build.java").toAbsolutePath().toString().endsWith("src/build/java/Build.java");

            return containsBuildModule || containsBuildPath;
        }
    }
}
