package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for regex token expansion security in {@link TreeCommand}.
 * Verifies that expansions cannot bypass {@code CommandParameter#isRelevant}.
 */
public class RegexParameterSecurityTest {

    /** Minimal {@link CommandParameter} with a configurable value set. */
    private static final class StubParameter extends CommandParameter {
        private final Set<String> values;

        StubParameter(Set<String> values, BiFunction<UUID, String, Boolean> isRelevant) {
            super("test.perm", "stub", isRelevant);
            this.values = values;
        }

        @Override
        public Set<String> values() {
            return values;
        }
    }

    private static Set<String> setOf(String... names) {
        return new LinkedHashSet<>(Arrays.asList(names));
    }

    // ---------------------------------------------------------------------
    // Permission-bypass attempts
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("S-INJ-1: greedy '.*' cannot leak isRelevant-rejected values")
    void greedyMatchAll_doesNotBypassIsRelevant() {
        // Mimics rtp.notme: every 'Hidden*' value is invisible to this caller.
        StubParameter p = new StubParameter(
                setOf("Visible_A", "Visible_B", "Hidden_X", "Hidden_Y", "Hidden_Z"),
                (u, s) -> !s.startsWith("Hidden_"));

        Set<String> result = TreeCommand.expandRegexToken("reg:.*", p, UUID.randomUUID())
                .collect(Collectors.toSet());

        assertTrue(result.contains("Visible_A"));
        assertTrue(result.contains("Visible_B"));
        for (String hidden : Arrays.asList("Hidden_X", "Hidden_Y", "Hidden_Z")) {
            assertFalse(result.contains(hidden),
                    "regex '.*' must not surface " + hidden + " (rejected by isRelevant)");
        }
    }

    @Test
    @DisplayName("S-INJ-2: targeted pattern cannot reach an isRelevant-rejected value")
    void targetedPattern_doesNotBypassIsRelevant() {
        // Direct attack: caller knows the hidden name and tries to hit it.
        StubParameter p = new StubParameter(
                setOf("Alice", "Mallory"),
                (u, s) -> !s.equals("Mallory"));

        // Exact-name regex
        Set<String> exact = TreeCommand.expandRegexToken("reg:Mallory", p, UUID.randomUUID())
                .collect(Collectors.toSet());
        assertFalse(exact.contains("Mallory"),
                "exact-name regex must not bypass isRelevant");

        // Character-class regex
        Set<String> classy = TreeCommand.expandRegexToken("reg:M[a-z]+", p, UUID.randomUUID())
                .collect(Collectors.toSet());
        assertFalse(classy.contains("Mallory"));

        // Anchored
        Set<String> anchored = TreeCommand.expandRegexToken("reg:^Mallory$", p, UUID.randomUUID())
                .collect(Collectors.toSet());
        assertFalse(anchored.contains("Mallory"));
    }

    @Test
    @DisplayName("S-INJ-3: caller with isRelevant=false for everyone gets empty expansion")
    void fullyDeniedCaller_getsEmptyExpansion() {
        StubParameter p = new StubParameter(
                setOf("a", "b", "c", "d"),
                (u, s) -> false);

        long count = TreeCommand.expandRegexToken("reg:.*", p, UUID.randomUUID()).count();
        assertEquals(0, count, "denied caller must not see any value via regex");
    }

    // ---------------------------------------------------------------------
    // Edge-case patterns
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("S-INJ-4: empty pattern 'reg:' matches only empty-string values")
    void emptyPattern_onlyMatchesEmptyString() {
        // No empty value -> empty result. Pattern.compile("") is legal.
        StubParameter p = new StubParameter(setOf("a", "b"), (u, s) -> true);
        long count = TreeCommand.expandRegexToken("reg:", p, UUID.randomUUID()).count();
        assertEquals(0, count);

        // Add an empty value -> only that one matches via Matcher.matches().
        StubParameter p2 = new StubParameter(setOf("", "a"), (u, s) -> true);
        Set<String> r = TreeCommand.expandRegexToken("reg:", p2, UUID.randomUUID())
                .collect(Collectors.toSet());
        assertEquals(Set.of(""), r);
    }

    @Test
    @DisplayName("S-INJ-5: pattern containing ':' is preserved (limit=2 split contract)")
    void patternWithColon_isPreservedByCallerSplit() {
        // expandRegexToken receives the post-split token. The parameterDelimiter
        // split happens upstream in onCommand; this test pins the contract that
        // a colon inside the pattern half is a legal regex character (literal
        // colon) and matches values that contain ':'.
        StubParameter p = new StubParameter(
                setOf("ns:foo", "ns:bar", "other"),
                (u, s) -> true);

        Set<String> r = TreeCommand.expandRegexToken("reg:ns:.*", p, UUID.randomUUID())
                .collect(Collectors.toSet());
        assertEquals(Set.of("ns:foo", "ns:bar"), r);
    }

    @Test
    @DisplayName("S-INJ-6: only the first 'reg:' prefix is stripped")
    void doubleRegPrefix_stripsOnlyFirst() {
        // 'reg:reg:foo' -> compile pattern 'reg:foo'. The literal value
        // "reg:foo" matches; the literal "foo" alone does not.
        StubParameter p = new StubParameter(
                setOf("reg:foo", "foo", "bar"),
                (u, s) -> true);

        Set<String> r = TreeCommand.expandRegexToken("reg:reg:foo", p, UUID.randomUUID())
                .collect(Collectors.toSet());
        assertEquals(Set.of("reg:foo"), r);
    }

    @Test
    @DisplayName("S-INJ-7: anchors are no-ops because Matcher.matches() requires full match")
    void anchorsAreNoOps() {
        StubParameter p = new StubParameter(setOf("foo", "foobar", "barfoo"), (u, s) -> true);

        Set<String> a = TreeCommand.expandRegexToken("reg:foo", p, UUID.randomUUID())
                .collect(Collectors.toSet());
        Set<String> b = TreeCommand.expandRegexToken("reg:^foo$", p, UUID.randomUUID())
                .collect(Collectors.toSet());

        assertEquals(Set.of("foo"), a);
        assertEquals(a, b, "matches() implies full-string anchoring; ^...$ must be redundant");
    }

    @Test
    @DisplayName("S-INJ-8: '.' does not match newline by default and does not over-match")
    void dotMetacharBehavesAsExpected() {
        StubParameter p = new StubParameter(
                setOf("ab", "a", "abc", "a\nb"),
                (u, s) -> true);

        // 'reg:..' matches exactly two chars, so 'ab' yes, 'a' no, 'abc' no, 'a\nb' no (\n excluded by default).
        Set<String> r = TreeCommand.expandRegexToken("reg:..", p, UUID.randomUUID())
                .collect(Collectors.toSet());
        assertEquals(Set.of("ab"), r);
    }

    @Test
    @DisplayName("S-INJ-9: escape sequences (\\Q...\\E) do not cause exceptions and behave literally")
    void quoteMeta_treatsContentLiterally() {
        StubParameter p = new StubParameter(setOf(".*", "abc"), (u, s) -> true);
        // \Q.*\E should match the literal value ".*" but NOT "abc".
        Set<String> r = TreeCommand.expandRegexToken("reg:\\Q.*\\E", p, UUID.randomUUID())
                .collect(Collectors.toSet());
        assertEquals(Set.of(".*"), r);
    }

    @Test
    @DisplayName("S-INJ-10: unicode and case-folding flags do not bypass isRelevant")
    void unicodeCaseFolding_respectsIsRelevant() {
        StubParameter p = new StubParameter(
                setOf("Ångström", "angstrom", "Mallory"),
                (u, s) -> !s.equals("Mallory"));

        // Aggressive case-insensitive + unicode-case pattern still cannot reach Mallory.
        Set<String> r = TreeCommand.expandRegexToken("reg:(?iu).*", p, UUID.randomUUID())
                .collect(Collectors.toSet());
        assertTrue(r.contains("Ångström"));
        assertTrue(r.contains("angstrom"));
        assertFalse(r.contains("Mallory"));
    }

    @Test
    @DisplayName("S-INJ-11: empty value set -> empty expansion (no NPE / no exception)")
    void emptyValueSet_yieldsEmpty() {
        StubParameter p = new StubParameter(setOf(), (u, s) -> true);
        long count = TreeCommand.expandRegexToken("reg:.*", p, UUID.randomUUID()).count();
        assertEquals(0, count);
    }

    // ---------------------------------------------------------------------
    // Second-order / value-as-pattern injection
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("S-INJ-12: values containing regex metacharacters are treated as plain strings")
    void valuesAreNotInterpretedAsPatterns() {
        // If a value were ever fed back through Pattern.compile, this test
        // would explode. It does not: values are only ever the *target* of a
        // Matcher, never compiled.
        StubParameter p = new StubParameter(
                setOf("[unclosed", "(?bad", "literal"),
                (u, s) -> true);

        // Caller sends a pattern that matches anything; values' meta chars must be irrelevant.
        Set<String> r = TreeCommand.expandRegexToken("reg:.*", p, UUID.randomUUID())
                .collect(Collectors.toSet());
        assertEquals(Set.of("[unclosed", "(?bad", "literal"), r);
    }

    @Test
    @DisplayName("S-INJ-13: a value literally named 'reg:foo' is still routable as a literal token")
    void literalTokenWithRegPrefixIsAmbiguousButDocumented() {
        // The plan section 6 documents this ambiguity: a literal value beginning with
        // 'reg:' is reinterpreted as a pattern. This test pins the documented
        // behaviour so any future change requires a conscious update.
        StubParameter p = new StubParameter(setOf("reg:foo"), (u, s) -> true);

        // Sending the literal "reg:foo" -> compiles "foo" as a pattern -> no match
        // because Matcher.matches() needs the *value* to equal "foo".
        Set<String> r = TreeCommand.expandRegexToken("reg:foo", p, UUID.randomUUID())
                .collect(Collectors.toSet());
        assertTrue(r.isEmpty(),
                "documented ambiguity: literal values starting with 'reg:' cannot be addressed without escape syntax");
    }

    // ---------------------------------------------------------------------
    // Resource-exhaustion / ReDoS (best-effort, bounded)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("S-INJ-14: pathological pattern against bounded value set still completes promptly")
    void redosCandidate_completesWithinBudget() {
        // Catastrophic-backtracking shape '(a+)+$' applied to short values.
        // Value lengths are capped (typical command parameter values are short
        // identifiers like player names), so even an evil pattern terminates.
        // We assert a generous timeout so this is not flaky on slow CI but
        // still catches an accidental switch to unbounded matching.
        Set<String> values = setOf(
                "aaaaaaaaaaaaaaaaaaaaX",
                "aaaaaaaaaaaaaaaaaaaaaY",
                "Steve",
                "Alex");
        StubParameter p = new StubParameter(values, (u, s) -> true);

        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            long count = TreeCommand.expandRegexToken("reg:(a+)+$", p, UUID.randomUUID()).count();
            // Whatever count is, the call must terminate. We don't pin the
            // exact number because the regex engine's behaviour on edge cases
            // is implementation-defined; the security claim is "bounded".
            assertTrue(count >= 0);
        });
    }

    @Test
    @DisplayName("S-INJ-15: very long pattern compiles or fails closed, never throws past expandRegexToken")
    void veryLongPattern_doesNotPropagateException() {
        StubParameter p = new StubParameter(setOf("foo"), (u, s) -> true);
        StringBuilder big = new StringBuilder("reg:");
        for (int i = 0; i < 5_000; i++) big.append("a?");
        big.append("a".repeat(5_000));
        // A legal but huge pattern. Should compile and either match or not -
        // the security contract is just "no exception escapes".
        Stream<String> s = TreeCommand.expandRegexToken(big.toString(), p, UUID.randomUUID());
        assertNotNull(s);
        // Force evaluation
        s.count();
    }

    // ---------------------------------------------------------------------
    // De-duplication
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("S-INJ-16: expandRegexToken itself does not de-dup; distinct() in onCommand handles overlap")
    void expandRegexToken_streamsRawMatches() {
        // Documents the contract: dedup is the caller's responsibility (the
        // onCommand pipeline applies .distinct() after flatMap). This test
        // pins that fact so the pipeline contract isn't accidentally moved
        // into expandRegexToken without updating callers.
        StubParameter p = new StubParameter(setOf("Anna", "Adam"), (u, s) -> true);
        long count = TreeCommand.expandRegexToken("reg:.*", p, UUID.randomUUID()).count();
        assertEquals(2, count, "value set is itself a Set, so each value appears once");
    }

    @Test
    @DisplayName("S-INJ-17: combining literal + regex via two calls produces the union with no duplicate logic")
    void literalAndRegex_combineCleanly() {
        StubParameter p = new StubParameter(
                setOf("Steve", "Anna", "Adam"),
                (u, s) -> true);

        // Simulate the onCommand pipeline: stream of tokens -> flatMap(expand) -> distinct()
        Set<String> combined = Stream.of("Steve", "reg:A.*")
                .flatMap(t -> TreeCommand.expandRegexToken(t, p, UUID.randomUUID()))
                .distinct()
                .collect(Collectors.toSet());

        assertEquals(Set.of("Steve", "Anna", "Adam"), combined);
    }

    // ---------------------------------------------------------------------
    // Sub-parameter parity
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("S-INJ-18: same security guarantee holds for sub-parameter regex expansion")
    void subParameter_inheritsSameInvariant() {
        // expandRegexToken is the single shared helper used by both the
        // top-level and sub-parameter pipelines, so behavioural parity is
        // structural. This test pins it explicitly: a hidden value remains
        // hidden regardless of which loop calls in.
        StubParameter sub = new StubParameter(
                setOf("public_a", "public_b", "secret"),
                (u, s) -> !s.equals("secret"));

        Set<String> r = TreeCommand.expandRegexToken("reg:.*", sub, UUID.randomUUID())
                .collect(Collectors.toSet());
        assertEquals(Set.of("public_a", "public_b"), r);
    }

    // ---------------------------------------------------------------------
    // Whitespace / control characters
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("S-INJ-19: pattern of whitespace only matches whitespace values, not all values")
    void whitespacePattern_doesNotOverMatch() {
        StubParameter p = new StubParameter(setOf("Steve", " ", "  "), (u, s) -> true);
        Set<String> r = TreeCommand.expandRegexToken("reg: ", p, UUID.randomUUID())
                .collect(Collectors.toSet());
        assertEquals(Set.of(" "), r);
    }

    // ---------------------------------------------------------------------
    // Malformed-pattern fallback: must NOT match isRelevant-rejected literal
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("S-INJ-20: malformed-pattern literal fallback is still subject to isRelevant downstream")
    void malformedFallbackLiteral_doesNotBypassDownstreamFilter() {
        // expandRegexToken's literal fallback emits the original token. The
        // onCommand pipeline then applies isRelevant. This test models that
        // pipeline: a malformed pattern token should NOT smuggle a hidden
        // value name through, because isRelevant runs after flatMap.
        StubParameter p = new StubParameter(
                setOf("Mallory"),
                (u, s) -> !s.equals("Mallory"));

        // PatternSyntaxException -> falls back to literal
        String evil = "reg:Mallory[";

        // Direct expansion just emits the literal token (security boundary is
        // upstream/downstream; expandRegexToken does not consult isRelevant
        // for literal tokens - that's the caller's job and is verified by the
        // pipeline test below).
        List<String> raw = TreeCommand.expandRegexToken(evil, p, UUID.randomUUID())
                .collect(Collectors.toList());
        assertEquals(List.of("reg:Mallory["), raw);

        // Pipeline-shape test: flatMap then isRelevant - Mallory must not appear.
        UUID caller = UUID.randomUUID();
        Set<String> filtered = Stream.of(evil)
                .flatMap(t -> TreeCommand.expandRegexToken(t, p, caller))
                .filter(s -> p.isRelevant.apply(caller, s))
                .collect(Collectors.toSet());
        assertFalse(filtered.contains("Mallory"));
        // The literal fallback token "reg:Mallory[" is not a real value name
        // either; isRelevant for it returns true under our stub (which only
        // rejects "Mallory"), but it's not in values() so nothing is teleported
        // to it downstream. We simply pin that "Mallory" is unreachable.
    }
}
