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

import build.base.expression.compat.Processor;
import build.base.expression.compat.Variable;
import build.base.foundation.Capture;
import build.base.io.PathSet;
import build.base.option.JDKVersion;
import build.base.telemetry.TelemetryRecorder;
import build.base.version.Version;
import build.codemodel.dependency.injection.PostInject;
import build.codemodel.jdk.descriptor.JDKModuleDescriptor;
import build.codemodel.jdk.descriptor.RequiresModifier;
import build.spin.Plugin;
import build.spin.Project;
import build.spin.Task;
import build.spin.annotation.After;
import build.spin.annotation.Before;
import build.spin.annotation.Description;
import build.spin.annotation.From;
import build.spin.module.configuration.Configuration;
import build.spin.module.gpg.GpgPlugin;
import build.spin.module.gpg.SignableResource;
import build.spin.module.jar.JarPlugin;
import build.spin.module.java.AbstractJavaPlugin;
import build.spin.module.java.JavaCompilerPlugin;
import build.spin.module.java.JavaPlatform;
import build.spin.module.java.ResourcePlugin;
import build.spin.module.modulesystem.Artifact;
import build.spin.module.modulesystem.ArtifactDescriptor;
import build.spin.module.modulesystem.ModuleCatalog;
import build.spin.module.modulesystem.ModuleReference;
import build.spin.module.modulesystem.ModuleVersioning;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.traversal.DocumentTraversal;
import org.w3c.dom.traversal.NodeFilter;
import org.w3c.dom.traversal.NodeIterator;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * A {@link Plugin} providing {@link Task}s for integration with <a href="https://maven.apache.org">Apache Maven</a>.
 *
 * @author brian.oliver
 * @since Dec-2020
 */
public class MavenPlugin
    extends AbstractJavaPlugin {

    /**
     * The {@link JDKVersion} to use for the {@link MavenPlugin}, is the highest {@link JDKVersion} of
     * the {@link JavaCompilerPlugin}(s) used by the {@link Project}.
     */
    private JDKVersion javaVersion;

    @PostInject
    private void onInjection() {
        // capture the highest JDKVersion
        final Capture<JDKVersion> capture = Capture.empty();

        this.project.plugins(JavaCompilerPlugin.class)
            .forEach(plugin -> {
                if ((capture.isPresent() && plugin.getJavaVersion().compareTo(capture.get()) > 0)
                    || !capture.isPresent()) {
                    capture.set(plugin.getJavaVersion());
                }
            });

        capture.map(version -> this.javaVersion = version)
            .orElseThrow(() -> new RuntimeException("Failed to determine the JDKVersion for the MavenPlugin"));
    }

    @Override
    public JDKVersion getJavaVersion() {
        return this.javaVersion;
    }

    /**
     * Creates a Maven Project Object Model XML {@link Document} for a {@link Project}, that of which may be
     * updated and saved for packaging with {@link CreatePOMFile}.
     */
    @Named("create.pom.document")
    public static class CreatePOMDocument
        implements Task<Document> {

        @Inject
        private DocumentBuilderFactory documentBuilderFactory;

        @Inject
        private Project project;

        @Inject
        private JDKModuleDescriptor descriptor;

        @Inject
        private ModuleCatalog catalog;

        @Inject
        private ModuleVersioning versioning;

        /**
         * Creates a Maven Project Object Module {@link Document}.
         *
         * @param resourcePaths the {@link Optional} resource paths (from which to load {@link Project} pom.xml)
         * @return a {@link Document} representing the Maven Project Object Model
         * @throws ParserConfigurationException should it not be possible to locate an XML {@link Document} parser
         * @throws SAXException                 should it not be possible to parse the pom.xml {@link Document}
         * @throws IOException                  should it not be possible to read the pom.xml {@link Document}
         */
        public Document create(final @From(ResourcePlugin.DetectModuleResourcePaths.class) Optional<PathSet> resourcePaths)
            throws ParserConfigurationException, SAXException, IOException {

            // determine the Version for this module
            final Version moduleVersion = this.versioning
                .getVersion(this.descriptor.moduleName().toString())
                .orElse(ModuleVersioning.DEFAULT_VERSION);

            final Version artifactVersion = moduleVersion;

            final DocumentBuilder documentBuilder = this.documentBuilderFactory.newDocumentBuilder();

            // attempt to locate a pom.xml in the project root path
            final Path projectPOMPath = this.project.path().resolve("pom.xml");

            // attempt to locate the Artifact.Constraint for this module based on the module name and version
            final Optional<Artifact.Constraint> catalogConstraint =
                this.catalog.constraints(this.descriptor.moduleName().toString())
                    .filter(c -> c.contains(artifactVersion))
                    .findFirst();

            // when no catalog entry exists but a pom.xml is present, return it as-is —
            // no template substitution or dependency rewriting is possible without coordinates
            if (catalogConstraint.isEmpty() && Files.exists(projectPOMPath)) {
                return documentBuilder.parse(Files.newInputStream(projectPOMPath));
            }

            final Artifact.Constraint constraint = catalogConstraint
                .orElseThrow(() -> new IllegalArgumentException("The module [" + this.descriptor.moduleName().toString()
                    + "] does not define an Artifact.Constraint in the Module Catalog"));

            // load the template to use. Use Class.getResourceAsStream (not ClassLoader's) so
            // this works when spin-maven-module is loaded as a named module: same-module
            // resource access via Class.getResourceAsStream has no JPMS encapsulation check,
            // whereas ClassLoader.getResourceAsStream refuses to find resources in a package
            // of a named module unless that package is opened unconditionally — and `maven`
            // looks like a package name even though it's just a resource directory here.
            final Document document = Files.exists(projectPOMPath)
                ? documentBuilder.parse(Files.newInputStream(projectPOMPath))
                : documentBuilder.parse(
                    MavenPlugin.class.getResourceAsStream("/maven/pom-template.xml"));

            // Prune non-essential elements BEFORE running EL substitution. The `<dependencies>` block in
            // particular is discarded and rebuilt from the ModuleDescriptor below, so any ${...} expressions
            // inside it (e.g. ${graphql.version}) must never be evaluated — we don't bind their base objects
            // in the Processor, and a strict EL evaluator throws PropertyNotFoundException on null-base
            // property access.
            final Set<String> required = Stream.of("modelVersion", "groupId", "artifactId", "version", "packaging",
                    "name", "description", "url", "inceptionYear", "inceptionYear", "organization", "developers",
                    "contributors", "issueManagement", "mailingLists", "scm")
                .collect(Collectors.toSet());

            final NodeList childNodes = document.getDocumentElement().getChildNodes();
            final HashSet<Node> nodesToRemove = new HashSet<>();
            for (int i = 0; i < childNodes.getLength(); i++) {
                final Node node = childNodes.item(i);
                if (!required.contains(node.getNodeName())) {
                    nodesToRemove.add(node);
                }
            }

            nodesToRemove.forEach(node -> node.getParentNode().removeChild(node));

            // establish a Java Expression Language Processor to replace elements in the Document
            // (that use Java Expression Language ${..}). `revision` is bound alongside `version` because
            // Maven's CI-friendly versioning convention is <version>${revision}</version> in child poms.
            final Processor processor = Processor.create(
                Variable.of("artifactId", constraint.artifactId()),
                Variable.of("groupId", constraint.groupId()),
                Variable.of("version", artifactVersion.toString()),
                Variable.of("revision", artifactVersion.toString()),
                Variable.of("packaging", "jar"));

            // iterate over the (pruned) Document and attempt to replace elements using ${...}
            final NodeIterator iterator = ((DocumentTraversal) document).createNodeIterator(
                document.getDocumentElement(),
                NodeFilter.SHOW_ELEMENT,
                node -> node.getNodeType() == Node.ELEMENT_NODE && node.getTextContent().trim().startsWith("${")
                    ? NodeFilter.FILTER_ACCEPT
                    : NodeFilter.FILTER_SKIP
                , true);

            for (Node node = iterator.nextNode(); node != null; node = iterator.nextNode()) {
                node.setTextContent(processor.replace(node.getTextContent()));
            }

            // generate the <dependencies> for the project based on the Module Descriptor
            final Node dependenciesNode = document.createElement("dependencies");

            this.descriptor.requiresClauses()
                .filter(requires -> !JavaPlatform.isJavaPlatformModule(
                    requires.requiresModuleName().toString()))  //filter out Java Platform dependencies
                .map(require -> {
                    // determine the Version for the dependency
                    final Version version = JDKModuleDescriptor.requiresVersion(require)
                        .orElseGet(() ->
                            this.versioning.getVersion(require.requiresModuleName().toString())
                                .orElseThrow(() -> new RuntimeException(
                                    "Failed to determine the Artifact Version for [" + require.requiresModuleName().toString() + "]"))
                        );

                    // establish the required dependency
                    final ModuleReference reference =
                        ModuleReference.of(require.requiresModuleName().toString(), version);

                    final Artifact artifact = this.catalog.getArtifact(reference)
                        .orElseThrow(() -> new RuntimeException(
                            "Failed to determine the Artifact for [" + reference.name() + "], Version ["
                                + version + "]"));

                    final Node dependencyNode = document.createElement("dependency");

                    final Node groupIdNode = document.createElement("groupId");
                    groupIdNode.setTextContent(artifact.groupId());
                    dependencyNode.appendChild(groupIdNode);

                    final Node artifactIdNode = document.createElement("artifactId");
                    artifactIdNode.setTextContent(artifact.artifactId());
                    dependencyNode.appendChild(artifactIdNode);

                    final Node versionNode = document.createElement("version");
                    versionNode.setTextContent(artifact.version().get());
                    dependencyNode.appendChild(versionNode);

                    final Node typeNode = document.createElement("type");
                    typeNode.setTextContent(artifact.type());
                    dependencyNode.appendChild(typeNode);

                    artifact.classifier().ifPresent(classifier -> {
                        final Node classifierNode = document.createElement("classifier");
                        classifierNode.setTextContent(classifier);
                        dependencyNode.appendChild(classifierNode);
                    });

                    if (require.traits(RequiresModifier.class).anyMatch(m -> m == RequiresModifier.STATIC)) {
                        final Node optionalNode = document.createElement("optional");
                        optionalNode.setTextContent("true");
                        dependenciesNode.appendChild(optionalNode);
                    }

                    return dependencyNode;
                })
                .forEach(dependenciesNode::appendChild);

            document.getDocumentElement().appendChild(dependenciesNode);

            return document;
        }
    }

    /**
     * Creates a Maven pom.xml based on the POM {@link Document} for a {@link Project}.
     * <p>
     * Marked {@link Before} {@code GpgPlugin.Sign} so, when GPG signing is available, the pom.xml is
     * registered with the {@link SignableResource} prior to signing.
     */
    @Named("create.pom")
    @After(JavaCompilerPlugin.Compile.class)
    @Before(GpgPlugin.Sign.class)
    public static class CreatePOMFile
        implements Task<Path> {

        @Inject
        private Optional<SignableResource> signableResource;

        /**
         * Creates the pom.xml in the provided buildPath, using the specified POM {@link Document}.
         *
         * @param distributionPath the {@link Path} in which the pom.xml is to be created
         * @param document         the XML {@link Document} defining the pom.xml
         * @return the {@link Path} to the newly created pom.xml
         * @throws TransformerException when the POM {@link Document} transformations fail
         * @throws IOException          when failing to create the POM
         */
        public Path create(final @From(JarPlugin.CreateDistributionPath.class) Path distributionPath,
                           final @From(CreatePOMDocument.class) Document document)
            throws TransformerException, IOException {

            final Path pom = distributionPath.resolve("pom.xml");

            final Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.METHOD, "xml");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
            transformer.transform(new DOMSource(document), new StreamResult(Files.newBufferedWriter(pom)));

            // include the pom.xml for signing, when GPG signing is available
            this.signableResource.ifPresent(resource -> resource.include(pom));

            return pom;
        }
    }

    /**
     * Publishes the packaged module jar, sources jar, javadoc jar, and pom.xml (and, when GPG signing is
     * available, their detached signatures) to a Maven repository.
     * <p>
     * This {@link Task} is deliberately not {@link build.spin.annotation.Automatic}: publishing only occurs
     * when explicitly requested (eg: {@code spin publish}), never as part of a regular build.
     * <p>
     * Three configuration keys (resolved from {@code .spin} configuration) are supported:
     * <ul>
     *     <li>{@code repository.url} - the base URL of the Maven repository to publish to (required)</li>
     *     <li>{@code repository.username}/{@code repository.password} - credentials for HTTP Basic
     *     authentication, used only when both are present</li>
     * </ul>
     */
    @Named("publish")
    @Description("Publish packaged artifacts to a Maven repository")
    public static class Publish
        implements Task<PathSet> {

        @Inject
        private TelemetryRecorder recorder;

        @Inject
        @Configuration
        @Named("repository.url")
        private Optional<String> repositoryUrl;

        @Inject
        @Configuration
        @Named("repository.username")
        private Optional<String> repositoryUsername;

        @Inject
        @Configuration
        @Named("repository.password")
        private Optional<String> repositoryPassword;

        /**
         * Publishes the packaged artifacts (and their signatures, if available) to the configured Maven
         * repository.
         *
         * @param jar        the {@link ArtifactDescriptor} for the module jar
         * @param sources    the {@link ArtifactDescriptor} for the sources jar
         * @param javadoc    the {@link ArtifactDescriptor} for the javadoc jar
         * @param pom        the {@link Path} to the pom.xml
         * @param signatures the {@link Optional} {@link PathSet} of detached signatures, present only when
         *                   GPG signing is available
         * @return a {@link PathSet} of the local {@link Path}s successfully published
         * @throws IOException          should reading a file or performing an HTTP request fail
         * @throws InterruptedException should an HTTP request be interrupted
         */
        public PathSet publish(final @From(JarPlugin.PackageModule.class) ArtifactDescriptor jar,
                               final @From(JarPlugin.PackageModuleSource.class) ArtifactDescriptor sources,
                               final @From(JarPlugin.PackageJavaDoc.class) ArtifactDescriptor javadoc,
                               final @From(CreatePOMFile.class) Path pom,
                               final @From(GpgPlugin.Sign.class) Optional<PathSet> signatures)
            throws IOException, InterruptedException {

            final var url = this.repositoryUrl
                .orElseThrow(() -> new IllegalStateException(
                    "No repository.url configured; cannot publish artifacts"));

            final var repo = this.repositoryUsername.isPresent() && this.repositoryPassword.isPresent()
                ? RemoteRepo.of("publish", url, this.repositoryUsername.get(), this.repositoryPassword.get())
                : RemoteRepo.of("publish", url);

            final var mainArtifact = jar.artifact();
            final var pomArtifact = Artifact.create(
                mainArtifact.groupId(), mainArtifact.artifactId(), mainArtifact.version().get(), "pom");

            final var publishables = new ArrayList<Publishable>();
            publishables.add(Publishable.of(jar.artifact(), jar.path().orElseThrow()));
            publishables.add(Publishable.of(sources.artifact(), sources.path().orElseThrow()));
            publishables.add(Publishable.of(javadoc.artifact(), javadoc.path().orElseThrow()));
            publishables.add(new Publishable(pomArtifact,
                mainArtifact.artifactId() + "-" + mainArtifact.version().get() + ".pom", pom));

            signatures.ifPresent(sigs -> List.copyOf(publishables).forEach(publishable ->
                findSignature(publishable.localFile(), sigs)
                    .ifPresent(signatureFile -> publishables.add(publishable.signedBy(signatureFile)))));

            final var publisher = new MavenPublisher(this.recorder, repo);

            final var published = new ArrayList<Path>();
            for (final var publishable : publishables) {
                if (publisher.upload(publishable.relativePath(), publishable.localFile())) {
                    this.recorder.info("Published %s to %s", publishable.remoteFileName(), repo.url());
                    published.add(publishable.localFile());
                } else {
                    throw new IOException(
                        "Failed to publish " + publishable.localFile() + " to " + repo.url());
                }
            }

            return published.stream().collect(PathSet.collector());
        }

        /**
         * Locates the detached signature {@link Path} (within the specified {@link PathSet}) for the specified
         * artifact {@link Path}, based on the signature's filename starting with the artifact's filename (as
         * produced by {@code GpgPlugin.Sign}, eg: {@code artifact.jar.asc}).
         *
         * @param artifact   the artifact {@link Path}
         * @param signatures the {@link PathSet} of detached signatures
         * @return an {@link Optional} containing the matching signature {@link Path}, if any
         */
        private static Optional<Path> findSignature(final Path artifact, final PathSet signatures) {
            final var filename = artifact.getFileName().toString();
            return signatures.stream()
                .filter(signature -> signature.getFileName().toString().startsWith(filename))
                .findFirst();
        }

        /**
         * A file to be published to a Maven repository: the {@link Artifact} coordinates it belongs to, the
         * filename it should be published under, and its local {@link Path}.
         */
        private record Publishable(Artifact artifact, String remoteFileName, Path localFile) {

            /**
             * Creates a {@link Publishable} whose remote filename matches the local file's filename.
             *
             * @param artifact  the {@link Artifact}
             * @param localFile the local {@link Path}
             * @return a new {@link Publishable}
             */
            static Publishable of(final Artifact artifact, final Path localFile) {
                return new Publishable(artifact, localFile.getFileName().toString(), localFile);
            }

            /**
             * Creates a {@link Publishable} for the detached signature of this {@link Publishable}, whose
             * remote filename is this {@link Publishable}'s remote filename with the signature's additional
             * suffix (eg: {@code .asc}) appended.
             *
             * @param signatureFile the local {@link Path} of the detached signature
             * @return a new {@link Publishable} for the signature
             */
            Publishable signedBy(final Path signatureFile) {
                final var localFileName = this.localFile.getFileName().toString();
                final var suffix = signatureFile.getFileName().toString().substring(localFileName.length());
                return new Publishable(this.artifact, this.remoteFileName + suffix, signatureFile);
            }

            /**
             * Obtains the Maven-repository-layout relative path (including filename) for this {@link Publishable}.
             *
             * @return the relative path
             */
            String relativePath() {
                final var groupPath = this.artifact.groupId().replace('.', '/');
                return groupPath + "/" + this.artifact.artifactId() + "/" + this.artifact.version().get()
                    + "/" + this.remoteFileName;
            }
        }
    }

    /**
     * The {@link Plugin.MetaClass} for the {@link MavenPlugin}.
     */
    public static class MetaClass
        implements Plugin.MetaClass {

        @Override
        public boolean isDetectedIn(final Path path) {
            return false;
        }

        @Override
        public boolean isDetectedIn(final Project project) {
            return project.contains(JavaCompilerPlugin.class);
        }
    }
}
