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

class PomBasedJavadocArgumentsTests {

    private final PomBasedJavadocArguments subject = new PomBasedJavadocArguments();

    // -------------------------------------------------------------------------
    // release / source
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
    void toArgs_source_emitsSourceFlagPairWhenReleaseAbsent() {
        final ConfigNode config = container(leaf("source", "17"));
        assertThat(subject.toArgs(config).toList())
            .containsExactly("-source", "17");
    }

    @Test
    void toArgs_release_suppressesSourceWhenBothPresent() {
        final ConfigNode config = container(leaf("release", "25"), leaf("source", "17"));
        assertThat(subject.toArgs(config).toList())
            .containsExactly("--release", "25");
    }

    // -------------------------------------------------------------------------
    // doclint
    // -------------------------------------------------------------------------

    @Test
    void toArgs_doclint_emitsDoclintFlag() {
        final ConfigNode config = container(leaf("doclint", "none"));
        assertThat(subject.toArgs(config).toList())
            .containsExactly("-Xdoclint:none");
    }

    @Test
    void toArgs_doclint_withRelease_emitsBothInOrder() {
        final ConfigNode config = container(leaf("release", "21"), leaf("doclint", "all,-missing"));
        assertThat(subject.toArgs(config).toList())
            .containsExactly("--release", "21", "-Xdoclint:all,-missing");
    }

    // -------------------------------------------------------------------------
    // additionalOptions
    // -------------------------------------------------------------------------

    @Test
    void toArgs_additionalOptions_childListForm() {
        final ConfigNode additionalOptions = container("additionalOptions",
            leaf("additionalOption", "-quiet"),
            leaf("additionalOption", "-Xmaxwarns 10"));
        assertThat(subject.toArgs(container(additionalOptions)).toList())
            .containsExactly("-quiet", "-Xmaxwarns 10");
    }

    @Test
    void toArgs_additionalOptions_flatTextForm() {
        final ConfigNode additionalOptions = leaf("additionalOptions", "-quiet -Xmaxwarns 10");
        assertThat(subject.toArgs(container(additionalOptions)).toList())
            .containsExactly("-quiet", "-Xmaxwarns", "10");
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
