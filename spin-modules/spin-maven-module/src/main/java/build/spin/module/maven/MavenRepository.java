package build.spin.module.maven;

/*-
 * #%L
 * Spin Maven Module
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

import build.base.configuration.Configuration;
import build.base.foundation.Exceptional;
import build.base.telemetry.TelemetryRecorder;
import build.base.version.Version;
import build.codemodel.dependency.injection.PostInject;
import build.codemodel.foundation.CodeModel;
import build.codemodel.foundation.descriptor.RequiresModuleDescriptor;
import build.codemodel.foundation.naming.ModuleName;
import build.codemodel.jdk.descriptor.JDKModuleDescriptor;
import build.codemodel.jdk.descriptor.ModuleModifier;
import build.codemodel.jdk.descriptor.RequiresModifier;
import build.codemodel.jdk.descriptor.RequiresVersionTrait;
import build.codemodel.jdk.descriptor.VersionTrait;
import build.spin.Service;
import build.spin.module.modulesystem.Artifact;
import build.spin.module.modulesystem.ModuleCatalog;
import build.spin.module.modulesystem.ModuleReference;
import build.spin.module.modulesystem.ModuleVersioning;
import jakarta.inject.Inject;

import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An {@link Artifact.Resolver} {@link Service} for an Apache Maven-based Repository, backed by the
 * pure-JDK {@link MavenFacade}/{@link PomResolver}.
 *
 * @author brian.oliver
 * @since Sep-2019
 */
public class MavenRepository
    implements Service, Artifact.Resolver {

    /**
     * The {@link TelemetryRecorder} for the {@link Service}.
     */
    private final TelemetryRecorder recorder;

    /**
     * The {@link MavenFacade} to actually interact with Apache Maven and Apache Maven-based Repositories.
     */
    private MavenFacade maven;

    /**
     * The {@link ModuleReference}s by {@link Artifact}.
     */
    private final ConcurrentHashMap<Artifact, Exceptional<ModuleReference>> moduleReferences;

    @Inject
    private CodeModel codeModel;

    /**
     * The {@link JDKModuleDescriptor}s extracted by {@link Artifact}, unmodified from the underlying JAR.
     */
    private final ConcurrentHashMap<Artifact, Exceptional<JDKModuleDescriptor>> extractedDescriptors;

    /**
     * The {@link JDKModuleDescriptor}s resolved by {@link Artifact}, enhanced with pom.xml data where needed.
     */
    private final ConcurrentHashMap<Artifact, Exceptional<JDKModuleDescriptor>> resolvedDescriptors;

    /**
     * Constructs the {@link MavenRepository}.
     *
     * @param recorder the {@link TelemetryRecorder}
     */
    @Inject
    private MavenRepository(final TelemetryRecorder recorder) {
        this.recorder = recorder;
        this.moduleReferences = new ConcurrentHashMap<>();
        this.extractedDescriptors = new ConcurrentHashMap<>();
        this.resolvedDescriptors = new ConcurrentHashMap<>();
    }

    /**
     * Initialize {@link MavenRepository} {@link Service} after creation.
     */
    @PostInject
    public void onInjected() {
        // establish the MavenFacade to interact with Maven Repositories
        this.maven = new MavenFacade(this.recorder, Configuration.empty());
    }

    @Override
    public Exceptional<Path> resolve(final Artifact artifact) {
        return this.maven.resolveArtifact(artifact);
    }

    @Override
    public Exceptional<List<Path>> resolveTransitive(final Artifact artifact) {
        return this.maven.resolveTransitiveDependencies(artifact);
    }

    @Override
    public Exceptional<List<Path>> resolveTransitive(final Artifact artifact, final Set<String> exclusions) {
        return this.maven.resolveTransitiveDependencies(artifact, exclusions);
    }

    @Override
    public Exceptional<ModuleReference> getModuleReference(final Artifact artifact,
                                                           final ModuleCatalog catalog) {

        final Exceptional<ModuleReference> existingReference = this.moduleReferences.get(artifact);

        if (existingReference != null
            && existingReference.map(reference -> reference.version().isPresent()).orElse(true)) {
            return existingReference;
        }

        Exceptional<JDKModuleDescriptor> extractedDescriptor = this.extractedDescriptors.get(artifact);

        if (extractedDescriptor == null) {
            extractedDescriptor = Exceptional.empty();
        }

        // JDKModuleDescriptor.extract reads the version from module-info.class, which most third-party
        // JARs don't set (javac requires --module-version explicitly). Fall back to the Maven artifact
        // version so the ModuleReference is always versioned and can be matched against the catalog.
        Exceptional<ModuleReference> reference = extractedDescriptor.map(descriptor ->
            ModuleReference.of(descriptor.moduleName().toString(),
                descriptor.version().or(() -> Optional.of(artifact.version()))));

        if (reference.isEmpty()) {
            final Exceptional<Path> path = resolve(artifact);

            // Use extractFresh (not extract) to avoid registering artifact descriptors in the shared
            // CodeModel — workspace projects use parse() for their canonical registration, and a prior
            // extract() call for the same module name would cause parse() to fail with a duplicate trait.
            extractedDescriptor = path.flatMap(p ->
                Exceptional.call(() -> JDKModuleDescriptor.extractFresh(this.codeModel, p))
                    .flatMap(Exceptional::ofOptional));

            final Exceptional<JDKModuleDescriptor> existingDescriptor =
                this.extractedDescriptors.putIfAbsent(artifact, extractedDescriptor);

            if (existingDescriptor != null) {
                extractedDescriptor = existingDescriptor;
            }

            reference = extractedDescriptor.map(descriptor ->
                ModuleReference.of(descriptor.moduleName().toString(),
                    descriptor.version().or(() -> Optional.of(artifact.version()))));
        }

        final Optional<ModuleReference> catalogedReference = catalog.getModuleReference(artifact);

        if (catalogedReference.isPresent()) {
            if (reference.isPresent()) {
                if (reference.orElseThrow().version().isPresent()) {
                    this.moduleReferences.putIfAbsent(artifact, reference);
                }
            } else {
                reference = Exceptional.ofOptional(catalogedReference);
            }
        } else if (reference.isPresent()) {
            if (reference.orElseThrow().version().isPresent()) {
                reference.ifPresent(r -> catalog.add(r.name(), artifact));
                this.moduleReferences.putIfAbsent(artifact, reference);
            }
        } else {
            this.recorder.warn("Module Name for [%s] is not resolvable by the Artifact.Resolver or Module Catalog",
                artifact.toString());
        }

        return reference;
    }

    @Override
    public Exceptional<JDKModuleDescriptor> getModuleDescriptor(final Artifact artifact,
                                                                 final ModuleCatalog catalog,
                                                                 final ModuleVersioning versioning) {

        final var existing = this.resolvedDescriptors.get(artifact);
        if (existing != null) {
            return existing;
        }

        var extracted = this.extractedDescriptors.get(artifact);
        if (extracted == null) {
            final Exceptional<Path> path = resolve(artifact);
            extracted = path.flatMap(p ->
                Exceptional.call(() -> JDKModuleDescriptor.extractFresh(this.codeModel, p))
                    .flatMap(Exceptional::ofOptional));
        }

        final var extractable = extracted;
        extracted = this.extractedDescriptors.compute(artifact,
            (__, current) -> current == null || current.isException() ? extractable : current);

        if (extracted.isException()) {
            final var resolved = this.resolvedDescriptors.putIfAbsent(artifact, extracted);
            return resolved == null ? extracted : resolved;
        }

        // fully resolved: named, versioned, and all requires carry version info
        if (extracted.map(descriptor -> !descriptor.isAutomatic()
                && !descriptor.isSynthetic()
                && descriptor.version().isPresent()
                && descriptor.requiresClauses()
                    .map(JDKModuleDescriptor::requiresVersion)
                    .allMatch(Optional::isPresent))
            .orElse(false)) {
            final var resolved = extracted;
            return this.resolvedDescriptors.compute(artifact, (__, current) -> current == null ? resolved : current);
        }

        // enhancement path: enrich the extracted descriptor in-place with pom.xml data.
        // For jars with no module-info.class and no Automatic-Module-Name, synthesise a
        // descriptor from the catalog so POM-based requires can be discovered.
        final JDKModuleDescriptor enhanced = extracted.map(descriptor -> descriptor)
            .orElseGet(() -> {
                final String moduleName = catalog.getModuleName(artifact)
                    .orElseGet(() -> {
                        final var derivedName = catalog.getDerivedModuleName(artifact);
                        this.recorder.warn(
                            "[%s] is not found in the Module Catalog.  Defaulting to the derived module name [%s])",
                            artifact.toString(), derivedName);
                        return derivedName;
                    });
                final ModuleName mn = this.codeModel.getNameProvider().getModuleName(moduleName).orElseThrow();
                final var descriptor = JDKModuleDescriptor.of(this.codeModel, mn);
                descriptor.addTrait(ModuleModifier.AUTOMATIC);
                descriptor.addTrait(ModuleModifier.SYNTHETIC);
                return descriptor;
            });

        enhanced.getTrait(VersionTrait.class).ifPresent(enhanced::removeTrait);
        enhanced.addTrait(VersionTrait.of(artifact.version()));

        final var resolved = this.maven.resolveArtifactDescriptor(artifact)
            .flatMap(dependencies -> {
                dependencies.stream()
                    .filter(d -> (d.scope().equals("compile")
                        || d.scope().equals("runtime")
                        || d.scope().equals("provided"))
                        && !d.optional())
                    .forEach(d -> {
                        final boolean isStatic = d.scope().equals("provided") || d.optional();

                        final Artifact requiredArtifact = Artifact.create(
                            d.groupId(),
                            d.artifactId(),
                            d.version().orElse(null),
                            d.type(),
                            d.classifier().orElse(null));

                        final var reference = getModuleReference(requiredArtifact, catalog)
                            .filter(r -> r.version().isPresent())
                            .orElseGet(() -> catalog.getDerivedModuleReference(requiredArtifact));

                        final Version parsed = reference.version().get();
                        final Optional<Version> overridden = versioning.getVersion(reference.name());
                        if (!parsed.equals(overridden.orElse(null))) {
                            overridden.ifPresent(v -> this.recorder.warn(
                                "Overriding %s version %s with %s", reference.name(), parsed, v));
                        }
                        final Version version = overridden.orElse(parsed);

                        final String reqModuleName = reference.name();
                        final var bytecodeReq = enhanced.requiresClauses()
                            .filter(r -> r.requiresModuleName().toString().equals(reqModuleName))
                            .findFirst();

                        if (bytecodeReq.isPresent()) {
                            // bytecode already declared this require with the correct modifier;
                            // only fill in the version if bytecode didn't carry one
                            if (JDKModuleDescriptor.requiresVersion(bytecodeReq.get()).isEmpty()) {
                                bytecodeReq.get().addTrait(RequiresVersionTrait.of(version));
                            }
                        } else {
                            // not in bytecode (automatic module or missing module-info); derive from POM scope
                            final ModuleName reqName =
                                this.codeModel.getNameProvider().getModuleName(reqModuleName).orElseThrow();
                            final RequiresModuleDescriptor req = RequiresModuleDescriptor.of(this.codeModel, reqName);
                            req.addTrait(isStatic ? RequiresModifier.STATIC : RequiresModifier.TRANSITIVE);
                            req.addTrait(RequiresVersionTrait.of(version));
                            enhanced.addTrait(req);
                        }

                        if (catalog.getModuleName(requiredArtifact).isEmpty()) {
                            catalog.add(reference.name(), requiredArtifact);
                        }
                    });

                return Exceptional.of(enhanced);
            });

        this.resolvedDescriptors.putIfAbsent(artifact, resolved);
        return resolved;
    }

    /**
     * The {@link Service.MetaClass} for {@link MavenRepository}.
     */
    public static class MetaClass
        implements Service.MetaClass {

        @Override
        public boolean isDetectedIn(final FileSystem fileSystem) {
            // obtain the user home (in which to detect a .m2 directory)
            final Path home = fileSystem.getPath(System.getProperty("user.home"));

            return Files.exists(home.resolve(".m2"));
        }
    }
}
