package build.spin.module.configuration;

/*-
 * #%L
 * Spin Configuration Module
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

import build.base.foundation.Strings;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compiles {@code .spinignore} lines into a single {@link Predicate} over {@link Path}, following
 * {@code .gitignore}'s own conventions rather than requiring every pattern to be pre-anchored with
 * a leading {@code **}{@code /}:
 * <ul>
 *     <li>a pattern with no {@code /} (other than a single trailing one) matches a path segment
 *     at any depth beneath {@code root}, e.g. {@code .worktrees} or {@code .worktrees/} ignores
 *     a {@code .worktrees} directory anywhere in the tree;</li>
 *     <li>a pattern containing a {@code /} elsewhere (including a leading one) is anchored to
 *     {@code root}, matching only that exact relative path;</li>
 *     <li>a leading {@code **}{@code /} means "at any depth, including the root itself", even when
 *     the rest of the pattern contains further slashes;</li>
 *     <li>a single {@code *} matches any run of characters <em>within one path segment</em> — it
 *     never crosses a {@code /} — while {@code **} matches across segments; a {@code /**}{@code /}
 *     between two other segments matches zero or more intervening directories, so
 *     {@code a/**}{@code /b} matches {@code a/b} as well as {@code a/x/b}, exactly as in
 *     {@code .gitignore};</li>
 *     <li>a leading {@code !} negates the pattern, as in {@code .gitignore}: whichever pattern in
 *     the file matches a given path <em>last</em> decides whether it's ignored, so a later {@code !}
 *     line can re-include something an earlier line ignored, but a bare {@code !} line with nothing
 *     preceding it to un-ignore has no effect.</li>
 * </ul>
 *
 * @author reed.vonredwitz
 * @since Sep-2026
 */
final class SpinIgnorePatterns {

    private SpinIgnorePatterns() {
    }

    /**
     * Compiles each non-blank, non-comment line of a {@code .spinignore} file into a single
     * {@link Predicate}, testing paths relative to {@code root} using last-match-wins semantics
     * (the same rule {@code .gitignore} itself uses): later lines override earlier ones for any
     * path they also match.
     *
     * @param root  the {@link Path} the {@code .spinignore} patterns are relative to
     * @param lines the raw lines of a {@code .spinignore} file
     * @return a {@link Predicate} that is {@code true} for every ignored {@link Path}
     */
    static Predicate<Path> compile(final Path root, final Stream<String> lines) {
        final List<Rule> rules = lines
            .map(String::trim)
            .filter(line -> !Strings.isEmpty(line))
            .filter(line -> !line.startsWith("#"))
            .map(line -> compileLine(root, line))
            .collect(Collectors.toUnmodifiableList());

        return candidate -> {
            final Path relative = candidate.startsWith(root) ? root.relativize(candidate) : candidate;
            final String test = relative.toString();

            boolean ignored = false;
            for (final Rule rule : rules) {
                if (rule.pattern.asMatchPredicate().test(test)) {
                    ignored = !rule.negate;
                }
            }
            return ignored;
        };
    }

    private static Rule compileLine(final Path root, final String line) {
        final boolean negate = line.startsWith("!");
        String glob = negate ? line.substring(1) : line;

        if (glob.endsWith("/")) {
            glob = glob.substring(0, glob.length() - 1);
        }

        // as in .gitignore: a leading "**/" explicitly means "at any depth, including the root
        // itself", regardless of any slashes later in the pattern; a leading "/" or any other
        // internal slash anchors the pattern to the root; a bare name (no slash at all) matches a
        // path segment at any depth
        final String body;
        final boolean anyDepth;
        if (glob.startsWith("**/")) {
            body = glob.substring("**/".length());
            anyDepth = true;
        } else if (glob.startsWith("/")) {
            body = glob.substring(1);
            anyDepth = false;
        } else if (glob.contains("/")) {
            body = glob;
            anyDepth = false;
        } else {
            body = glob;
            anyDepth = true;
        }

        final String regex = (anyDepth ? "(?:.*/)?" : "") + toSegmentAwareRegex(body);
        return new Rule(Pattern.compile(regex), negate);
    }

    /**
     * Translates a glob into a regular expression fragment where, as in {@code .gitignore}, a
     * single {@code *} matches any run of characters within one path segment (never crossing a
     * {@code /}) and {@code **} matches across segments. A {@code /**}{@code /} occurring between
     * two other segments matches zero or more intervening directories -- exactly as {@code .gitignore}
     * defines it -- so {@code a/**}{@code /b} matches {@code a/b} as well as {@code a/x/b}.
     */
    private static String toSegmentAwareRegex(final String glob) {
        final String[] segments = glob.split("/", -1);
        final StringBuilder regex = new StringBuilder(glob.length());
        boolean skipSeparator = true;
        for (int i = 0; i < segments.length; i++) {
            final String segment = segments[i];
            if (segment.equals("**") && i > 0 && i < segments.length - 1) {
                // a "**" segment with a real segment on either side matches zero or more
                // directories, absorbing both surrounding slashes into the optional group
                regex.append("(?:.*/)?");
                skipSeparator = true;
                continue;
            }

            if (!skipSeparator) {
                regex.append('/');
            }
            regex.append(toSegmentRegex(segment));
            skipSeparator = false;
        }
        return regex.toString();
    }

    /**
     * Translates a single path segment (containing no {@code /}) of a glob into a regular
     * expression fragment, where {@code *} never crosses a segment boundary and a lone {@code **}
     * segment (only possible at the very start or end of the pattern here; a middle {@code **} is
     * handled by {@link #toSegmentAwareRegex}) matches any run of characters, slashes included.
     */
    private static String toSegmentRegex(final String segment) {
        final StringBuilder regex = new StringBuilder(segment.length());
        int i = 0;
        while (i < segment.length()) {
            final char c = segment.charAt(i);
            if (c == '*') {
                if (i + 1 < segment.length() && segment.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i += 2;
                } else {
                    regex.append("[^/]*");
                    i += 1;
                }
            } else if (c == '?') {
                regex.append("[^/]");
                i += 1;
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
                i += 1;
            } else {
                regex.append(c);
                i += 1;
            }
        }
        return regex.toString();
    }

    private record Rule(Pattern pattern, boolean negate) {
    }
}
