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
import build.base.flow.RecordingSubscriber;
import build.base.option.JDKVersion;
import build.base.telemetry.TelemetryRecorder;
import build.base.template.TextOut;
import build.codemodel.jdk.descriptor.JDKModuleDescriptor;
import build.percolate.core.ModuleGraphClassifier;
import build.spawn.application.Application;
import build.spawn.application.option.Argument;
import build.spawn.application.option.Name;
import build.spawn.application.option.StandardOutputSubscriber;
import build.spawn.jdk.JDK;
import build.spawn.platform.local.LocalMachine;
import build.spin.Project;
import build.spin.Task;
import build.spin.annotation.System;
import build.spin.common.JDKTools;
import build.spin.common.ProcessFailedException;
import build.spin.common.ProcessRunner;
import build.spin.module.configuration.Source;
import build.spin.module.modulesystem.Artifact;
import build.spin.module.modulesystem.ModuleReference;
import build.spin.option.JlinkTargets;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * An abstract {@link Task} to perform Java Linking using the Java Platform
 * <a href="https://docs.oracle.com/en/java/javase/25/docs/specs/man/jlink.html">jlink</a> tool
 * on the compiled and packaged {@link Artifact} for a {@link Project}.
 * <p>
 * A {@code ScriptTemplate} (generated from {@code ScriptTemplate.jt}) generates a unix-based script to execute the linked application.
 *
 * @author brian.oliver
 * @since Jan-2023
 */
@Source(AbstractJavaLinker.CONFIGURATION_SOURCE)
public abstract class AbstractJavaLinker
    implements Task<Set<Path>> {

    static final String CONFIGURATION_SOURCE = "build.spin.module.jlink";

    private static final String MAIN_CLASS_KEY = "main-class";

    @Inject
    private TelemetryRecorder recorder;

    @Inject
    @build.spin.module.configuration.Configuration
    @Named("enable-native-access")
    private Optional<String> enableNativeAccess;

    @Inject
    @build.spin.module.configuration.Configuration
    @Named(MAIN_CLASS_KEY)
    private Optional<String> mainClassOverride;

    @Inject
    private JavaPlatform platform;

    @Inject
    private LocalMachine machine;

    @Inject
    private Project project;

    @Inject
    private JDKModuleDescriptor descriptor;

    @Inject
    @System
    private JDKVersion systemJavaVersion;

    // controls whether jlink() below links every staged JDK target or just the host's own —
    // see JlinkTargets.HOST_ONLY, used by spin's own self-hosting bootstrap to skip cross-target
    // linking it doesn't need
    @Inject
    private JlinkTargets jlinkTargets;

    /**
     * Execute {@code jlink} on this {@link Project}, once per {@link TargetPlatform} a {@link JavaPlatform#targets()}
     * {@link JDK} is available for, i.e. staging a foreign-platform {@link JDK} is sufficient to have a runtime
     * image generated for it — no explicit target selection is required.
     *
     * @param buildPath the build path for the {@link Project}
     * @param analysis  the {@link DependencyAnalysis} containing information for linking
     * @return the {@link Set} of {@link Path}s of the {@code jlink} produced Java Runtimes, one per target platform
     * @throws Exception should the {@link Task} execution fail
     */
    public Set<Path> jlink(final Path buildPath,
                           final DependencyAnalysis analysis)
        throws Exception {

        // jlink only makes sense for executable applications. Skip silently for library modules.
        final Optional<String> mainClass = detectMainClass(this.project.path(), this.mainClassOverride, this.recorder);
        if (mainClass.isEmpty()) {
            this.recorder.diagnostic("Skipping jlink for [%s]: no main class found", this.project.path());
            return Set.of();
        }

        // every target gets its own <packageName>/<os>-<arch>/ sibling path, including the host's own
        // - no special-cased flat path, so targets never collide or nest inside one another
        final var hostTarget = JavaPlatform.hostTarget();

        // HOST_ONLY (spin's own self-hosting bootstrap) skips iterating every staged JDK target and
        // links just the host's own, so a build only needs its own JDK staged, not every cross-target one
        final var targets = this.jlinkTargets == JlinkTargets.HOST_ONLY
            ? List.of(hostTarget)
            : this.platform.targets().toList();
        if (targets.isEmpty()) {
            throw new RuntimeException("No JDKs available for jlink");
        }

        // Classification (which app jars can link straight into the image vs. must stay external)
        // only depends on the target's module set, not on the target itself, so targets sharing an
        // identical module set (the common case — cross-compiled targets are usually built from the
        // same JDK version) share one classification instead of repeating the classification work
        // and its "N module-path jar(s) depend on automatic modules" diagnostic once per target.
        final Map<Set<String>, ClassificationResult> classificationCache = new LinkedHashMap<>();

        final Set<Path> images = new LinkedHashSet<>();
        for (final var target : targets) {
            images.add(linkForTarget(buildPath, analysis, mainClass.get(), target, target.equals(hostTarget),
                classificationCache));
        }
        return images;
    }

    // Package-private (rather than private) so classifyCached()'s test can reference it directly.
    record ClassificationResult(List<Path> linkableJars,
                                Set<Path> tainted,
                                List<Path> classPath,
                                Map<Path, ModuleDescriptor> descriptorsByPath) {
    }

    private Path linkForTarget(final Path buildPath,
                               final DependencyAnalysis analysis,
                               final String mainClass,
                               final TargetPlatform target,
                               final boolean isHostTarget,
                               final Map<Set<String>, ClassificationResult> classificationCache)
        throws Exception {

        // establish the name of the package and script
        final var packageName = this.project.name();
        final var scriptName = packageName + ".sh";

        // establish the path in which to generate the jlink runtime package
        final var packagePath = buildPath.resolve(packageName + "-" + target);

        // ------
        // resolve the JDK whose jmods define the *target* platform's modules.
        final var targetJdk = this.platform.getVersion(this.systemJavaVersion.major(), target)
            .or(() -> this.platform.getLatest(target))
            .orElseThrow(() -> new RuntimeException("No JDK found for target " + target + " and Java "
                + this.systemJavaVersion.major() + ", and no latest JDK available for that target"));
        final var targetJavaHome = targetJdk.home().path();

        // resolve the JDK whose jlink binary can actually be *executed* on this host — a jlink binary
        // built for a foreign target platform (e.g. a different OS or CPU architecture) cannot run here.
        // jlink treats .jmod files as portable data, so running the host's jlink against a foreign
        // target's --module-path (below) is how genuine cross-target linking works.
        final var hostJdk = this.platform.getVersion(this.systemJavaVersion.major())
            .or(this.platform::getLatest)
            .orElseThrow(() -> new RuntimeException(
                "No host-executable JDK found for Java " + this.systemJavaVersion.major()
                    + " to run jlink with, and no latest host JDK available"));

        // Derive the set of module names available in the target JDK by reading the
        // jmods/ directory.  We only need the names for --add-modules filtering; we do
        // NOT use ModuleFinder.of(.jmod files) because the JDK rejects .jmod reads at
        // execution time ("JMOD format not supported at execution time") — .jmod is a
        // link-time-only format.  Filename stripping is sufficient and reliable: the
        // file is always named <module-name>.jmod.
        final var jmodsDir = targetJavaHome.resolve("jmods");
        final Set<String> jdkModuleNames;
        if (Files.isDirectory(jmodsDir)) {
            try (var jmodPaths = Files.list(jmodsDir)) {
                jdkModuleNames = jmodPaths
                    .filter(p -> p.toString().endsWith(".jmod"))
                    .map(p -> {
                        final var n = p.getFileName().toString();
                        return n.substring(0, n.length() - ".jmod".length());
                    })
                    .collect(Collectors.toSet());
            }
        } else {
            jdkModuleNames = ModuleFinder.ofSystem().findAll().stream()
                .map(mr -> mr.descriptor().name())
                .collect(Collectors.toSet());
        }

        // ------
        // create a list of the Java Platform modules to link — these must always be linked
        // regardless of how the application modules below are classified, since the
        // external (unlinked) portion of the app's module graph still needs them at runtime.
        final var platformModuleNames = analysis.platformModules()
            .map(ModuleReference::name)
            .filter(jdkModuleNames::contains)  // only include modules that actually exist in this JDK
            .collect(Collectors.toCollection(LinkedHashSet::new));

        // -----
        // Classify application jars into module-path vs classpath candidates. This runs
        // BEFORE jlink so we know, up front, which application modules can be linked into
        // the image alongside the platform modules above.
        //
        // Classification uses ModuleFinder + Configuration.resolve on the real on-disk
        // jars — the same approach {@code build.spin.application.Launcher} uses for the
        // spin1 Maven-exec launch. Split-package conflicts are iteratively demoted to
        // classpath where the JPMS package-uniqueness rule doesn't apply; automatic
        // modules on --module-path still reach the demoted classes via ALL-UNNAMED.
        //
        // Dependency dedupe (both by Maven (groupId, artifactId) and by JPMS module
        // name) already happened upstream in {@link AbstractJavaDependencyAnalysis}, so
        // analysis.dependencies() is a clean canonical set here.
        final List<Path> candidatePaths = analysis.dependencies()
            .flatMap(dep -> dep.artifactDescriptor().path().stream())
            .toList();

        final var rootModule = this.descriptor.moduleName().toString();

        final var classificationResult = classifyCached(
            classificationCache, jdkModuleNames, candidatePaths, rootModule, jmodsDir);
        final List<Path> linkableJars = classificationResult.linkableJars();
        final Set<Path> tainted = classificationResult.tainted();
        final Map<Path, ModuleDescriptor> descriptorsByPath = classificationResult.descriptorsByPath();

        final var nativePlatform = nativePlatformFor(target);

        // jlink packs module-path JARs directly into lib/modules — there is no way to strip
        // foreign-platform native entries from the packed image format afterward, so we strip
        // a staged copy before jlink ever sees it. analysis.modulePath() is shared across every
        // target's linkForTarget() call, so we copy per-target rather than stripping in place;
        // stripping the shared directory to one target's natives would silently corrupt the
        // module-path for every other target's jlink run.
        final var stagedModulePath = buildPath.resolve(packageName + "-" + target + "-modulepath-staging");
        try {
            // jlink refuses to run at all when --output already exists (even from a prior
            // successful link of this same project) — clear it first so a rerun doesn't fail with
            // "directory already exists".
            deleteDirectory(packagePath);
            // a prior run that was interrupted before reaching the finally block below (Ctrl-C,
            // OOM-kill, crash) can leave stale jars here — clear them first so a stale copy of a
            // since-renamed-or-removed dependency never ends up on jlink's module-path scan
            // alongside its replacement
            deleteDirectory(stagedModulePath);
            Files.createDirectories(stagedModulePath);
            for (final var jar : linkableJars) {
                final var staged = stagedModulePath.resolve(jar.getFileName());
                Files.copy(jar, staged, StandardCopyOption.REPLACE_EXISTING);
                if (nativePlatform.isPresent()) {
                    final var p = nativePlatform.get();
                    if (stripForeignNatives(staged, p.osDir(), p.archDir())) {
                        this.recorder.info("stripped foreign native platforms from %s (kept %s/%s)",
                            staged.getFileName(), p.osDir(), p.archDir());
                    }
                }
            }

            // the host's jlink no longer implicitly resolves platform modules from "itself" the way it did
            // when jlink was always run from within the target JDK — the target's jmods must be on the
            // module path explicitly so jlink can find platform modules like java.base for the target
            final var jlinkModulePath = Files.isDirectory(jmodsDir)
                ? stagedModulePath + File.pathSeparator + jmodsDir
                : stagedModulePath.toString();

            final Set<String> addModules = new LinkedHashSet<>(platformModuleNames);
            linkableJars.stream()
                .map(descriptorsByPath::get)
                .filter(Objects::nonNull)
                .map(ModuleDescriptor::name)
                .forEach(addModules::add);

            final var recordingObserver = new RecordingSubscriber<String>();
            final ErrorCapture captured = new ErrorCapture();

            // jlink's --strip-debug shells out to the host's native objcopy, which can't parse a foreign
            // target's binaries (e.g. running x86_64 objcopy against aarch64 or Mach-O native libraries) —
            // only strip when linking the host's own target.
            //
            // Deliberately NOT using jlink's own --generate-cds-archive plugin: it dumps the base archive
            // generically, with no knowledge of how the image is actually launched, so it records
            // jdk.module.main as unset. Since every generated script launches with -m rootModule/mainClass,
            // that mismatches at runtime and permanently disables CDS's "optimized module handling" / "full
            // module graph" optimization on every single launch (dynamic archive or not) — confirmed via
            // -Xlog:cds. dumpBaseCdsArchive() below re-derives the same archive ourselves, passing -m so the
            // recorded module-main property actually matches the launch script, which measured ~20% faster
            // than the jlink-plugin version. Same foreign-target restriction as --strip-debug applies: it
            // executes the freshly-linked image's own java, which can't run a foreign target's binary.
            // shared by both the in-process and forked launch paths below, so argument-building never
            // diverges between them
            final ConfigurationBuilder jlinkConfiguration = ConfigurationBuilder.create()
                .add(Argument.of("--module-path")).add(Argument.of(jlinkModulePath))
                .add(Argument.of("--output")).add(Argument.of(packagePath))
                .add(Argument.of("--add-modules")).add(Argument.of(String.join(",", addModules)));
            if (isHostTarget) {
                jlinkConfiguration.add(Argument.of("--strip-debug"));
            }
            jlinkConfiguration
                .add(Argument.of("--no-header-files"))
                .add(Argument.of("--no-man-pages"))
                .add(Argument.of("--compress")).add(Argument.of("zip-6"))
                .add(Argument.of("--vm")).add(Argument.of("server"));

            final int exitCode;

            final var linking = this.recorder.commence(
                "linking runtime image for [%s] (target %s, %d module(s))",
                packageName, target, addModules.size());

            if (JDKTools.canRunInProcess(hostJdk)) {
                // the JDK whose jlink binary executes on this host is the exact installation running
                // this Spin process, so "jlink" can run in-process via ToolProvider instead of forking
                // a whole child JVM — this applies even when linking a foreign target, since jlink
                // treats a foreign target's jmods as portable data regardless of how jlink itself runs
                final var errorSubscriber = captured
                    .triageSubscriber(ErrorCapture::isJvmNoise, this.recorder::warn, this.recorder::error)
                    .get();

                exitCode = JDKTools.runInProcess("jlink", recordingObserver, errorSubscriber, jlinkConfiguration);
            } else {
                jlinkConfiguration
                    .add(JDKTools.executable(hostJdk.home().path(), "jlink"))
                    .add(Name.of("jlink"))
                    .add(StandardOutputSubscriber.of(recordingObserver))
                    .add(captured.triageSubscriber(ErrorCapture::isJvmNoise, this.recorder::warn, this.recorder::error));

                try (var jlink = this.machine.launch(Application.class, jlinkConfiguration)) {
                    try {
                        ProcessRunner.await(jlink, "jlink",
                            () -> ErrorCapture.selectOutput(captured.output(), recordingObserver.items()));
                    } catch (final ProcessFailedException e) {
                        linking.completeExceptionally(e);
                        throw e;
                    }
                }

                // await() throws on a failed wait or a non-zero exit, so reaching here means success
                exitCode = 0;
            }

            if (exitCode != 0) {
                final var exception = new ProcessFailedException(
                    "Runtime Image Generation Failed (exit code: " + exitCode + ")",
                    ErrorCapture.selectOutput(captured.output(), recordingObserver.items()));
                linking.completeExceptionally(exception);
                throw exception;
            }

            linking.complete();

            // -----
            // Copy whatever jlink didn't link in: tainted module-path jars go to an external
            // runtime --module-path (modules/) and classpath losers go to classpath/. Linkable
            // jars are already inside packagePath/lib/modules courtesy of jlink above and are
            // not copied again. Directories are created lazily — a fully-linked graph (no
            // tainted or classpath jars) leaves neither directory behind.
            //
            // Note: we use `classpath/` (not `lib/`) because jlink writes its runtime image
            // into packagePath/lib/modules and owns the lib/ directory.
            final var modulePath = packagePath.resolve("modules");
            final var classPathDir = packagePath.resolve("classpath");

            final Set<Path> classPathJars = new LinkedHashSet<>(classificationResult.classPath());
            final List<Path> classPathTargets = new ArrayList<>();
            for (final var source : candidatePaths) {
                final boolean isClassPath = classPathJars.contains(source);
                final boolean isTainted = tainted.contains(source);
                if (!isClassPath && !isTainted) {
                    // linkable — already packed into the image by jlink
                    continue;
                }
                final var targetDir = isClassPath ? classPathDir : modulePath;
                Files.createDirectories(targetDir);
                final var destination = targetDir.resolve(source.getFileName());
                Files.copy(source, destination);
                if (nativePlatform.isPresent()) {
                    final var p = nativePlatform.get();
                    if (stripForeignNatives(destination, p.osDir(), p.archDir())) {
                        this.recorder.info("stripped foreign native platforms from %s (kept %s/%s)",
                            destination.getFileName(), p.osDir(), p.archDir());
                    }
                }
                if (isClassPath) {
                    classPathTargets.add(destination);
                }
            }

            if (isHostTarget) {
                dumpBaseCdsArchive(packagePath, rootModule, mainClass, modulePath, classPathTargets, this.enableNativeAccess);
            }

            // ---------
            // create the script to execute the application
            final var scriptPath = packagePath.resolve("bin");

            // The script template references $MP (modules/) and $LIB (classpath/). Only the
            // classpath entries are listed explicitly; the module-path is a single directory.
            // $MP (and the --module-path argument itself) is only emitted when tainted jars
            // actually exist — a fully-linked graph has no modules/ directory to point at.
            final var classPath = classPathTargets.stream()
                .map(path -> "$LIB/" + path.getFileName())
                .collect(Collectors.joining(":"));

            try (var writer = Files.newBufferedWriter(scriptPath.resolve(scriptName))) {
                new ScriptTemplate(classPath, !tainted.isEmpty(), rootModule, mainClass, packageName, this.enableNativeAccess.orElse(null))
                    .render(new TextOut(writer));
            }

            // make the script executable
            scriptPath.resolve(scriptName).toFile().setExecutable(true);
        } finally {
            // the staged, native-stripped copies were only needed for the jlink invocation above —
            // the real bytes now live inside packagePath/lib/modules; clean up even if jlink or the
            // post-processing above failed, so a retry doesn't inherit a stale staging directory
            deleteDirectory(stagedModulePath);
        }

        return packagePath;
    }

    // Package-private (rather than private) so tests can exercise the cache hit/miss decision
    // directly, without going through linkForTarget()'s real jlink-executable invocation.
    ClassificationResult classifyCached(final Map<Set<String>, ClassificationResult> classificationCache,
                                        final Set<String> jdkModuleNames,
                                        final List<Path> candidatePaths,
                                        final String rootModule,
                                        final Path jmodsDir) throws IOException {
        var result = classificationCache.get(jdkModuleNames);
        if (result == null) {
            result = classify(candidatePaths, rootModule, jmodsDir);
            classificationCache.put(jdkModuleNames, result);
        }
        return result;
    }

    // -----
    // Classify application jars into module-path vs classpath candidates, then partition the
    // module-path jars into "linkable" (jlink can link them straight into lib/modules) and
    // "tainted" (must stay on an external runtime --module-path).
    //
    // Classification uses ModuleFinder + Configuration.resolve on the real on-disk jars — the
    // same approach {@code build.spin.application.Launcher} uses for the spin1 Maven-exec launch.
    // Split-package conflicts are iteratively demoted to classpath where the JPMS
    // package-uniqueness rule doesn't apply; automatic modules on --module-path still reach the
    // demoted classes via ALL-UNNAMED.
    //
    // Dependency dedupe (both by Maven (groupId, artifactId) and by JPMS module name) already
    // happened upstream in {@link AbstractJavaDependencyAnalysis}, so candidatePaths is a clean
    // canonical set here.
    private ClassificationResult classify(final List<Path> candidatePaths, final String rootModule,
                                          final Path jmodsDir) throws IOException {

        // Prefer classifyAndResolve so unreachable jars are pruned from the module-path.
        // The supplemental finder must reflect the *target* JDK's full module set, not
        // ModuleFinder.ofSystem() -- spin itself typically runs from its own jlinked runtime
        // image, which only contains the modules spin needs, so resolving an app that requires
        // JDK modules outside that image would otherwise fail. JmodModuleFinder reads real
        // descriptors straight out of the target's jmods/ (falling back to ModuleFinder.ofSystem()
        // only when there's no jmods/ dir to read, e.g. a JRE).
        //
        // Some JDK distributions (e.g. Eclipse Temurin's linux-x64 "jdk" download) don't ship a
        // jmods/ directory at all -- it's a separate "jmods" download for those vendors -- so this
        // fallback is reachable even for a nominally full JDK, not just a JRE. Left silent, the
        // only symptom downstream is a confusing "Module X not found" resolution failure with no
        // hint that the target JDK itself was the problem, so it's called out here explicitly.
        if (!Files.isDirectory(jmodsDir)) {
            this.recorder.warn("target JDK has no jmods/ directory at [%s] -- resolving against "
                + "this host's own running module set instead, which may be missing modules the "
                + "target JDK actually has", jmodsDir);
        }
        final var targetJdkFinder = Files.isDirectory(jmodsDir)
            ? JmodModuleFinder.of(jmodsDir)
            : ModuleFinder.ofSystem();

        ModuleGraphClassifier.Classification classification;
        try {
            classification = ModuleGraphClassifier.classifyAndResolve(
                candidatePaths,
                Set.of(rootModule),
                rootModule,
                Configuration.empty(),
                targetJdkFinder,
                this.recorder::info);
        } catch (final IllegalStateException e) {
            this.recorder.warn("classifyAndResolve failed (%s) — falling back to classify-only; "
                + "unreachable jars will NOT be pruned from the module-path", e.getMessage());
            classification = ModuleGraphClassifier.classify(
                candidatePaths,
                Set.of(rootModule),
                this.recorder::info);
        }
        final List<Path> modulePathJars = classification.modulePath();

        // jlink cannot link automatic modules into a runtime image at all — any module that
        // is itself automatic, or that transitively requires one, has to be excluded from
        // --add-modules and copied to an external module-path directory instead, exactly like
        // spin did before full-image linking existed. Everything else gets linked in, so an
        // application with a perfectly clean module graph (e.g. spin itself) still gets a
        // fully self-contained image, while one with automatic-module dependencies degrades
        // gracefully instead of failing jlink outright.
        // readDescriptor returns null for jars ModuleFinder can't interpret as a module, so this
        // is built with a plain loop rather than Collectors.toMap (which rejects null values)
        final Map<Path, ModuleDescriptor> descriptorsByPath = new LinkedHashMap<>();
        for (final var jar : modulePathJars) {
            descriptorsByPath.put(jar, ModuleGraphClassifier.readDescriptor(jar));
        }

        final Set<String> automaticNames = descriptorsByPath.values().stream()
            .filter(Objects::nonNull)
            .filter(ModuleDescriptor::isAutomatic)
            .map(ModuleDescriptor::name)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        final Set<Path> tainted = new LinkedHashSet<>();
        for (final var jar : modulePathJars) {
            final var descriptor = descriptorsByPath.get(jar);
            if (descriptor == null || descriptor.isAutomatic()) {
                tainted.add(jar);
                continue;
            }
            final var closure = ModuleGraphClassifier.closeOverRequires(modulePathJars, Set.of(descriptor.name()));
            if (closure.stream().anyMatch(automaticNames::contains)) {
                tainted.add(jar);
            }
        }

        final List<Path> linkableJars = modulePathJars.stream().filter(jar -> !tainted.contains(jar)).toList();
        if (!tainted.isEmpty()) {
            this.recorder.info("%d module-path jar(s) depend on automatic modules and will stay "
                + "external to the image: %s", tainted.size(),
                tainted.stream().map(p -> p.getFileName().toString()).toList());
        }

        return new ClassificationResult(linkableJars, tainted, classification.classPath(), descriptorsByPath);
    }

    // Dumps the base (static) CDS archive for a just-linked image ourselves, instead of using jlink's own
    // --generate-cds-archive plugin.
    //
    // Why: that plugin (jdk.jlink's CDSPlugin, see jdk.jlink/jdk/tools/jlink/internal/plugins/CDSPlugin.java
    // in the JDK source) always dumps with a bare `java -Xshare:dump` — no -m, no module, no main class, and
    // it has no notion of --launcher either, so it can never know that every image spin links gets launched
    // with `-m rootModule/mainClass` (see ScriptTemplate.jt). That leaves the archived jdk.module.main
    // property unset. At every subsequent launch the JVM detects that the (unset) archived value doesn't
    // match the `-m` argument actually supplied, logs a "Mismatched values for property jdk.module.main"
    // warning, and permanently disables CDS's "full module graph" optimization for that run — confirmed
    // directly against JDK 25 and current (post-25) jdk.jlink mainline via -Xlog:cds; this is not fixed
    // upstream. Since spin already knows the exact `-m` invocation the generated script will use, dumping
    // the archive here with that same rootModule/mainClass avoids the mismatch entirely and lets CDS's full
    // optimization kick in — measured ~20% faster than the mismatched jlink-plugin archive in practice.
    //
    // This produces the same lib/server/classes.jsa location and dump mechanism (`-Xshare:dump`) as the
    // jlink plugin — just with -m added — so ScriptTemplate.jt's -XX:+AutoCreateSharedArchive (which builds
    // a dynamic app.jsa layered on top of this base archive at first real run) keeps working unchanged.
    //
    // Best-effort: CDS is a startup-time optimization, not a correctness requirement, so a failure here
    // (e.g. an unusual JDK build without CDS support) is logged and swallowed rather than failing the link.
    //
    // rootModule can itself be a tainted module-path jar (or its main class can live on the classpath) that
    // jlink left external to the image rather than packed into lib/modules -- the same reason ScriptTemplate.jt
    // conditionally adds --module-path $MP / -cp $LIB to the real launch command. Passing modulePath and
    // classPathTargets here mirrors that so the dump's `-m rootModule/mainClass` can actually resolve the
    // module instead of failing with FindException.
    private void dumpBaseCdsArchive(final Path packagePath,
                                    final String rootModule,
                                    final String mainClass,
                                    final Path modulePath,
                                    final List<Path> classPathTargets,
                                    final Optional<String> enableNativeAccess) {
        final var recordingObserver = new RecordingSubscriber<String>();
        final ErrorCapture captured = new ErrorCapture();

        final ConfigurationBuilder configuration = ConfigurationBuilder.create()
            .add(JDKTools.executable(packagePath, "java"))
            .add(Name.of("java"))
            .add(Argument.of("--enable-preview"))
            .add(Argument.of("-Xshare:dump"));
        enableNativeAccess.ifPresent(modules -> configuration.add(Argument.of("--enable-native-access=" + modules)));
        if (Files.isDirectory(modulePath)) {
            configuration.add(Argument.of("--module-path"));
            configuration.add(Argument.of(modulePath.toString()));
        }
        if (!classPathTargets.isEmpty()) {
            configuration.add(Argument.of("-cp"));
            configuration.add(Argument.of(classPathTargets.stream()
                .map(Path::toString)
                .collect(Collectors.joining((File.pathSeparator)))
            ));
        }

        // A GraalVM-linked image roots jdk.internal.vm.ci at every startup -- it is how the Graal JIT
        // plugs into HotSpot via JVMCI -- but -Xshare:dump never executes application code and so never
        // adds it. Left unmatched, the archived jdk.module.addmods differs from every real launch and
        // CDS silently disables its optimized-module-graph handling; worse, ScriptTemplate.jt's
        // -XX:+AutoCreateSharedArchive then rewrites this base archive on the first run that does load
        // it. Stock HotSpot JDKs neither link nor root the module, so only add it when the freshly
        // linked image actually contains it (GraalVM's jlink includes it automatically).
        if (imageContainsModule(packagePath, "jdk.internal.vm.ci")) {
            configuration.add(Argument.of("--add-modules"));
            configuration.add(Argument.of("jdk.internal.vm.ci"));
        }

        configuration
            .add(Argument.of("-m")).add(Argument.of(rootModule + "/" + mainClass))
            .add(StandardOutputSubscriber.of(recordingObserver))
            .add(captured.triageSubscriber(
                ((Predicate<String>) ErrorCapture::isJvmNoise).or(ErrorCapture::isCdsDumpNoise),
                this.recorder::warn, this.recorder::error));

        final var dumping = this.recorder.commence("dumping CDS base archive for [%s]", packagePath);

        try (var dump = this.machine.launch(Application.class, configuration)) {

            ProcessRunner.await(dump, "CDS Base Archive Dump",
                () -> ErrorCapture.selectOutput(captured.output(), recordingObserver.items()));

            dumping.complete();
        } catch (final Exception e) {
            dumping.completeExceptionally(e);
            final Throwable unwrapped = ProcessFailedException.unwrap(e);
            final var detail = unwrapped instanceof ProcessFailedException p && !p.output().isEmpty()
                ? "%n%s".formatted(p.output())
                : "";
            this.recorder.warn("failed to dump CDS base archive for %s — startup will not benefit "
                + "from class data sharing: %s%s", packagePath, e.getMessage(), detail);
        }
    }

    // Determines whether a just-linked image contains the named module, by reading the "MODULES=" line
    // of its release file. The release file is the only reliable source here: GraalVM's jlink injects
    // jdk.internal.vm.ci into the image on its own, so it never appears in the module set spin resolved
    // and asked jlink to link -- only in what jlink actually emitted. Best-effort: a missing or
    // unreadable release file reports false.
    // package-private for testing
    static boolean imageContainsModule(final Path packagePath, final String moduleName) {
        final Path release = packagePath.resolve("release");
        if (!Files.isRegularFile(release)) {
            return false;
        }
        try {
            for (final String line : Files.readAllLines(release)) {
                if (line.startsWith("MODULES=")) {
                    final String modules = line.substring("MODULES=".length()).replace("\"", "");
                    for (final String module : modules.split("\\s+")) {
                        if (module.equals(moduleName)) {
                            return true;
                        }
                    }
                    return false;
                }
            }
        } catch (final IOException e) {
            return false;
        }
        return false;
    }

    private static void deleteDirectory(final Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    // Package-private (rather than private) so tests can exercise the override-validation and
    // multiple-candidate cases directly, without going through jlink()'s full DI-injected path.
    static Optional<String> detectMainClass(final Path projectPath,
                                                    final Optional<String> mainClassOverride,
                                                    final TelemetryRecorder recorder) {
        return MainClassDetection.detect(
            projectPath, mainClassOverride, MAIN_CLASS_KEY, configurationLocation(), recorder);
    }

    // human-readable pointer to where jlink's own configuration lives, e.g.
    // ".spin/build.spin.module.jlink.properties" -- built from the same constants that back the
    // @Source/@Configuration/@Named wiring above so it can't describe a path that doesn't match
    // what ConfigurationResolver actually looks up.
    private static String configurationLocation() {
        return "%s/%s.properties"
            .formatted(build.spin.module.configuration.Configuration.DIRECTORY, CONFIGURATION_SOURCE);
    }

    record NativePlatform(String osDir, String archDir) {
    }

    // The raw (un-normalized) OS/arch pair extracted from a jar entry path by nativeOsArch().
    record NativeJarEntry(String os, String arch) {

        // True if this entry's (normalized) platform differs from the given canonical osDir/archDir,
        // i.e. it's a native library built for some other platform than the one being linked for.
        boolean isForeignTo(final String osDir, final String archDir) {
            return !normalizeEntryOs(os).equals(osDir) || !normalizeEntryArch(arch).equals(archDir);
        }
    }

    static Optional<NativePlatform> nativePlatformFor(final TargetPlatform target) {
        final String osDir = switch (target.operatingSystem()) {
            case MAC -> "Mac";
            case LINUX -> "Linux";
            case WINDOWS -> "Windows";
            case OTHER -> null;
        };
        if (osDir == null) {
            return Optional.empty();
        }

        final String archDir = switch (target.architecture()) {
            case AARCH64 -> "aarch64";
            case X86_64 -> "x86_64";
            case OTHER -> null;
        };
        if (archDir == null) {
            return Optional.empty();
        }

        return Optional.of(new NativePlatform(osDir, archDir));
    }

    // Normalises the arch segment found in a jar entry path to the same canonical values
    // produced by currentNativePlatform(), so the two can be compared directly.
    // Handles known aliases: aarch_64 (Netty), amd64/x64 (x86_64), i*86 (x86).
    static String normalizeEntryArch(final String entryArch) {
        return switch (entryArch.toLowerCase()) {
            case "x86_64", "amd64", "x64" -> "x86_64";
            case "aarch64", "arm64", "aarch_64" -> "aarch64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            default -> entryArch.toLowerCase();
        };
    }

    // Normalizes the OS segment found in a jar entry path to the same canonical values
    // produced by nativePlatformFor(), so the two can be compared directly.
    // Handles the root-native-layout aliases (e.g. zstd-jni: "linux", "darwin", "win").
    static String normalizeEntryOs(final String entryOs) {
        return switch (entryOs.toLowerCase()) {
            case "mac", "darwin", "macos", "osx" -> "Mac";
            case "linux" -> "Linux";
            case "windows", "win" -> "Windows";
            default -> entryOs.toLowerCase();
        };
    }

    // OS tokens recognized by the root-native-layout convention (see nativeOsArch below).
    // Kept narrow so arbitrary two-segment class/resource paths (e.g. "org/example/Foo.class")
    // are never mistaken for a native library entry.
    private static final Set<String> ROOT_NATIVE_OS_TOKENS =
        Set.of("mac", "darwin", "macos", "osx", "linux", "windows", "win", "aix", "freebsd", "sunos", "openbsd");

    // Returns the OS/arch components from a jar entry, recognizing two conventions:
    //   1. <prefix>/native/<OS>/<arch>/<file>  (requires a leading '/' before "native", so
    //      root-level entries like "native/Linux/..." are intentionally excluded from this form)
    //   2. <os>/<arch>/<file> at the jar root, where <os> is a known OS token
    //      (e.g. zstd-jni: "linux/amd64/libzstd-jni-....so")
    // Returns Optional.empty() if the entry matches neither structure.
    static Optional<NativeJarEntry> nativeOsArch(final String entryName) {
        final int idx = entryName.indexOf("/native/");
        if (idx >= 0) {
            final var after = entryName.substring(idx + 8);
            final int first = after.indexOf('/');
            if (first < 0) {
                return Optional.empty();
            }
            final int second = after.indexOf('/', first + 1);
            if (second < 0 || after.indexOf('/', second + 1) >= 0) {
                return Optional.empty();
            }
            return Optional.of(new NativeJarEntry(after.substring(0, first), after.substring(first + 1, second)));
        }

        final int first = entryName.indexOf('/');
        if (first < 0) {
            return Optional.empty();
        }
        final int second = entryName.indexOf('/', first + 1);
        if (second < 0 || entryName.indexOf('/', second + 1) >= 0) {
            return Optional.empty();
        }
        final var os = entryName.substring(0, first);
        if (!ROOT_NATIVE_OS_TOKENS.contains(os.toLowerCase())) {
            return Optional.empty();
        }
        return Optional.of(new NativeJarEntry(os, entryName.substring(first + 1, second)));
    }

    // Returns true if any foreign-platform native entries were stripped from the jar.
    static boolean stripForeignNatives(final Path jar, final String osDir, final String archDir)
        throws IOException {
        boolean hasForeignNatives = false;
        try (var zf = new ZipFile(jar.toFile())) {
            for (final var e = zf.entries(); e.hasMoreElements();) {
                if (nativeOsArch(e.nextElement().getName()).filter(p -> p.isForeignTo(osDir, archDir)).isPresent()) {
                    hasForeignNatives = true;
                    break;
                }
            }
            if (!hasForeignNatives) {
                return false;
            }
            final var tmp = Files.createTempFile(jar.getParent(), jar.getFileName().toString(), ".tmp");
            try {
                try (var out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                    for (final var e = zf.entries(); e.hasMoreElements();) {
                        final var entry = e.nextElement();
                        if (nativeOsArch(entry.getName()).filter(p -> p.isForeignTo(osDir, archDir)).isPresent()) {
                            continue;
                        }
                        out.putNextEntry(new ZipEntry(entry.getName()));
                        try (var is = zf.getInputStream(entry)) {
                            is.transferTo(out);
                        }
                        out.closeEntry();
                    }
                }
                Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
            } catch (final IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
        }
        return true;
    }

}
