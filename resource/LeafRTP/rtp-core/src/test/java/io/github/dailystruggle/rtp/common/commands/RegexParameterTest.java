package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the regex token expansion added to {@link TreeCommand}.
 * Exercises {@link TreeCommand#expandRegexToken} directly so the behaviour is
 * validated without spinning up the full RTP command pipeline.
 */
public class RegexParameterTest {

    /** Minimal {@link CommandParameter} with a fixed value set. */
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
        return new LinkedHashSet<>(java.util.Arrays.asList(names));
    }

    @Test
    void literalToken_passesThroughUnchanged() {
        StubParameter p = new StubParameter(setOf("Steve", "Alex"), (u, s) -> true);
        List<String> result = TreeCommand.expandRegexToken("Steve", p, UUID.randomUUID())
                .collect(Collectors.toList());
        assertEquals(List.of("Steve"), result);
    }

    @Test
    void regexToken_expandsAgainstRelevantValues() {
        StubParameter p = new StubParameter(
                setOf("Anna", "Adam", "Bob", "Albert"),
                (u, s) -> true);
        Set<String> result = TreeCommand.expandRegexToken("reg:.*A.*", p, UUID.randomUUID())
                .collect(Collectors.toSet());
        // Matcher.matches() requires full match; "Anna", "Adam", "Albert" all match .*A.*
        assertTrue(result.contains("Anna"));
        assertTrue(result.contains("Adam"));
        assertTrue(result.contains("Albert"));
        assertFalse(result.contains("Bob"));
    }

    @Test
    void regexExpansion_respectsIsRelevantFilter() {
        // Bob has the "rtp.notme"-style block: isRelevant rejects him.
        StubParameter p = new StubParameter(
                setOf("Anna", "Bob", "Albert"),
                (u, s) -> !s.equals("Bob"));
        Set<String> result = TreeCommand.expandRegexToken("reg:.*", p, UUID.randomUUID())
                .collect(Collectors.toSet());
        assertTrue(result.contains("Anna"));
        assertTrue(result.contains("Albert"));
        assertFalse(result.contains("Bob"),
                "values rejected by isRelevant must not appear in regex expansion");
    }

    @Test
    void invalidRegex_fallsBackToLiteral() {
        StubParameter p = new StubParameter(setOf("Anna", "Adam"), (u, s) -> true);
        // Unbalanced bracket -> PatternSyntaxException -> literal fallback
        List<String> result = TreeCommand.expandRegexToken("reg:[unclosed", p, UUID.randomUUID())
                .collect(Collectors.toList());
        assertEquals(List.of("reg:[unclosed"), result,
                "malformed regex must fall back to the literal token, not throw");
    }

    @Test
    void regexToken_withNoMatches_yieldsEmptyStream() {
        StubParameter p = new StubParameter(setOf("Anna", "Adam"), (u, s) -> true);
        List<String> result = TreeCommand.expandRegexToken("reg:Z.*", p, UUID.randomUUID())
                .collect(Collectors.toList());
        assertTrue(result.isEmpty());
    }

    @Test
    void caseInsensitiveFlag_works() {
        StubParameter p = new StubParameter(setOf("Anna", "Bob"), (u, s) -> true);
        Set<String> result = TreeCommand.expandRegexToken("reg:(?i).*a.*", p, UUID.randomUUID())
                .collect(Collectors.toSet());
        assertTrue(result.contains("Anna"));
        assertFalse(result.contains("Bob"));
    }

    @Test
    void nullToken_yieldsEmptyStream() {
        StubParameter p = new StubParameter(setOf("Anna"), (u, s) -> true);
        long count = TreeCommand.expandRegexToken(null, p, UUID.randomUUID()).count();
        assertEquals(0, count);
    }
}
