package build.spin.module.modulesystem.maven;

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

import build.spin.module.modulesystem.pom.ConfigNode;
import build.spin.module.modulesystem.pom.DefaultConfigNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PomBasedCompilerArgumentsTests {

    private final PomBasedCompilerArguments subject = new PomBasedCompilerArguments();

    // -------------------------------------------------------------------------
    // release / source / target
    // -------------------------------------------------------------------------

    @Test
    void toArgs_emptyConfig_producesNoArgs() {
        assertThat(subject.toArgs(ConfigNode.empty()).toList()).isEmpty();
    }

    @Test
    void toArgs_release_emitsReleaseFlagPair() {
        final ConfigNode config = container(leaf("release", "25"));
        assertThat(subject.toArgs(config).toList())
            .containsExactly("--release", "25");
    }

    @Test
    void toArgs_sourceAndTarget_emitBothWhenReleaseAbsent() {
        final ConfigNode config = container(leaf("source", "17"), leaf("target", "17"));
        assertThat(subject.toArgs(config).toList())
            .containsExactly("-source", "17", "-target", "17");
    }

    @Test
    void toArgs_release_suppressesSourceAndTargetWhenBothPresent() {
        final ConfigNode config = container(leaf("release", "25"), leaf("source", "17"), leaf("target", "17"));
        assertThat(subject.toArgs(config).toList())
            .containsExactly("--release", "25");
    }

    // -------------------------------------------------------------------------
    // compilerArgs
    // -------------------------------------------------------------------------

    @Test
    void toArgs_compilerArgs_argAndCompilerArgChildrenBothEmitted() {
        final ConfigNode compilerArgs = container("compilerArgs",
            leaf("arg", "-Xlint:all"),
            leaf("compilerArg", "-Werror"));
        assertThat(subject.toArgs(container(compilerArgs)).toList())
            .containsExactly("-Xlint:all", "-Werror");
    }

    @Test
    void toArgs_doesNotEmitEnablePreviewFromConfigurationElement() {
        // <enablePreview> is deliberately NOT one of this method's opaque tokens -- it's surfaced
        // separately via enablePreview(ConfigNode)/enablePreview(Project) so AbstractCompile can
        // combine it with its own .spin/-config opinion rather than have it silently forced in.
        final ConfigNode config = container(leaf("enablePreview", "true"));
        assertThat(subject.toArgs(config).toList()).isEmpty();
    }

    @Test
    void toArgs_compilerArgsCanStillCarryARawEnablePreviewToken() {
        // the escape hatch: a project that spells --enable-preview out explicitly in <compilerArgs>
        // (rather than via the dedicated <enablePreview> element) still gets it passed through verbatim.
        final ConfigNode compilerArgs = container("compilerArgs", leaf("arg", "--enable-preview"));
        assertThat(subject.toArgs(container(compilerArgs)).toList())
            .containsExactly("--enable-preview");
    }

    // -------------------------------------------------------------------------
    // enablePreview(ConfigNode)
    // -------------------------------------------------------------------------

    @Test
    void enablePreview_absent_isEmpty() {
        assertThat(PomBasedCompilerArguments.enablePreview(ConfigNode.empty())).isEmpty();
    }

    @Test
    void enablePreview_declaredTrue_isTrue() {
        final ConfigNode config = container(leaf("enablePreview", "true"));
        assertThat(PomBasedCompilerArguments.enablePreview(config)).contains(true);
    }

    @Test
    void enablePreview_declaredFalse_isFalse() {
        final ConfigNode config = container(leaf("enablePreview", "false"));
        assertThat(PomBasedCompilerArguments.enablePreview(config)).contains(false);
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static ConfigNode leaf(final String name, final String text) {
        return new DefaultConfigNode(name, Map.of(), Optional.of(text), List.of());
    }

    private static ConfigNode container(final ConfigNode... children) {
        return new DefaultConfigNode("configuration", Map.of(), Optional.empty(), List.of(children));
    }

    private static ConfigNode container(final String name, final ConfigNode... children) {
        return new DefaultConfigNode(name, Map.of(), Optional.empty(), List.of(children));
    }
}
