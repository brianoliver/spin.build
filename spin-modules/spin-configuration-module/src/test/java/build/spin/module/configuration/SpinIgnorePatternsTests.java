package build.spin.module.configuration;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SpinIgnorePatterns}.
 *
 * @author reed.vonredwitz
 * @since Sep-2026
 */
class SpinIgnorePatternsTests {

    private static final Path ROOT = Paths.get("/workspace/project");

    private boolean isIgnored(final String relativePath, final String... lines) {
        final Predicate<Path> predicate = SpinIgnorePatterns.compile(ROOT, Stream.of(lines));
        return predicate.test(ROOT.resolve(relativePath));
    }

    @Test
    void bareNameMatchesAtRoot() {
        assertThat(isIgnored(".worktrees", ".worktrees")).isTrue();
    }

    @Test
    void bareNameMatchesAtAnyDepth() {
        assertThat(isIgnored("nested/deep/.worktrees", ".worktrees")).isTrue();
    }

    @Test
    void bareNameWithTrailingSlashMatchesAtAnyDepth() {
        assertThat(isIgnored("nested/deep/.worktrees", ".worktrees/")).isTrue();
    }

    @Test
    void bareNameDoesNotMatchDifferentName() {
        assertThat(isIgnored("nested/deep/other", ".worktrees")).isFalse();
    }

    @Test
    void anchoredPatternMatchesOnlyFromRoot() {
        assertThat(isIgnored("build/output", "build/output")).isTrue();
        assertThat(isIgnored("nested/build/output", "build/output")).isFalse();
    }

    @Test
    void leadingSlashAnchorsToRoot() {
        assertThat(isIgnored("target", "/target")).isTrue();
        assertThat(isIgnored("nested/target", "/target")).isFalse();
    }

    @Test
    void globWildcardStillWorksWithinASegment() {
        assertThat(isIgnored("nested/foo.tmp", "*.tmp")).isTrue();
    }

    @Test
    void singleStarDoesNotCrossASegmentBoundary() {
        assertThat(isIgnored("src/Foo.java", "src/*.java")).isTrue();
        assertThat(isIgnored("src/nested/Foo.java", "src/*.java")).isFalse();
    }

    @Test
    void doubleStarCrossesSegmentBoundaries() {
        assertThat(isIgnored("src/nested/Foo.java", "src/**/Foo.java")).isTrue();
        assertThat(isIgnored("src/nested/deep/Foo.java", "src/**/Foo.java")).isTrue();
    }

    @Test
    void middleDoubleStarAlsoMatchesZeroIntermediateDirectories() {
        // as in real .gitignore: "a/**/b" matches "a/b" too, not just deeper paths
        assertThat(isIgnored("src/Foo.java", "src/**/Foo.java")).isTrue();
    }

    @Test
    void leadingDoubleStarSlashMatchesAtRootDespiteLaterSlash() {
        // the real workspace .spinignore relies on exactly this: "**/fixtures/**" must match a
        // top-level "fixtures" directory, not just a nested one
        assertThat(isIgnored("fixtures/maven/some-module", "**/fixtures/**")).isTrue();
    }

    @Test
    void leadingDoubleStarSlashStillMatchesNested() {
        assertThat(isIgnored("nested/fixtures/maven/some-module", "**/fixtures/**")).isTrue();
    }

    // -------------------------------------------------------------------------
    // negation: last matching line wins, as in .gitignore
    // -------------------------------------------------------------------------

    @Test
    void laterNegationReIncludesAnEarlierIgnoredPath() {
        assertThat(isIgnored("Keep.class", "*.class", "!Keep.class")).isFalse();
        assertThat(isIgnored("Main.class", "*.class", "!Keep.class")).isTrue();
    }

    @Test
    void laterPositiveLineOverridesAnEarlierNegation() {
        assertThat(isIgnored("Keep.class", "!Keep.class", "*.class")).isTrue();
    }

    @Test
    void aLoneNegationWithNothingToReIncludeIgnoresNothing() {
        // a bare "!foo" line must never cause every OTHER path to read as ignored -- it can only
        // re-include a path some earlier line already ignored
        assertThat(isIgnored(".worktrees", "!.worktrees")).isFalse();
        assertThat(isIgnored("other", "!.worktrees")).isFalse();
    }
}
