package io.github.dailystruggle.rtp.common.tools;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ParseStringTest {

    private static final Set<Character> PERCENT = Set.of('%');
    private static final Set<String> PLACEHOLDERS = Set.of("player", "world", "x", "y", "z");

    @Test
    void normalPlaceholderExtraction() {
        Set<String> result = ParseString.keywords("%player% went to %world%", PLACEHOLDERS, PERCENT, PERCENT);
        assertEquals(Set.of("player", "world"), result);
    }

    @Test
    void singlePlaceholder() {
        Set<String> result = ParseString.keywords("%x%", PLACEHOLDERS, PERCENT, PERCENT);
        assertEquals(Set.of("x"), result);
    }

    @Test
    void emptyInput() {
        Set<String> result = ParseString.keywords("", PLACEHOLDERS, PERCENT, PERCENT);
        assertTrue(result.isEmpty());
    }

    @Test
    void noMatchingPlaceholders() {
        Set<String> result = ParseString.keywords("%unknown%", PLACEHOLDERS, PERCENT, PERCENT);
        assertTrue(result.isEmpty());
    }

    @Test
    void noDelimitersInInput() {
        Set<String> result = ParseString.keywords("hello world", PLACEHOLDERS, PERCENT, PERCENT);
        assertTrue(result.isEmpty());
    }

    @Test
    void adjacentPlaceholders() {
        Set<String> result = ParseString.keywords("%x%%y%", PLACEHOLDERS, PERCENT, PERCENT);
        // %x% is found; the closing % also acts as the opening delimiter for %y%, so both are matched
        assertEquals(Set.of("x", "y"), result);
    }

    @Test
    void nestedFrontDelimiterResetsBuilder() {
        // second % resets builder, so "pla" is discarded and "yer" is built
        Set<String> result = ParseString.keywords("%pla%yer%", PLACEHOLDERS, PERCENT, PERCENT);
        // "pla" is not in placeholders; "yer" is not in placeholders
        assertTrue(result.isEmpty());
    }

    @Test
    void differentFrontAndBackDelimiters() {
        Set<Character> front = Set.of('{');
        Set<Character> back = Set.of('}');
        Set<String> result = ParseString.keywords("{player} and {world}", PLACEHOLDERS, front, back);
        assertEquals(Set.of("player", "world"), result);
    }

    @Test
    void placeholderNotInSetIsIgnored() {
        Set<String> result = ParseString.keywords("%player% %notaplaceholder%", PLACEHOLDERS, PERCENT, PERCENT);
        assertEquals(Set.of("player"), result);
    }

    @Test
    void allPlaceholdersFound() {
        Set<String> result = ParseString.keywords("%player% %world% %x% %y% %z%", PLACEHOLDERS, PERCENT, PERCENT);
        assertEquals(PLACEHOLDERS, result);
    }

    @Test
    void onlyDelimitersNoContent() {
        Set<String> result = ParseString.keywords("%%", PLACEHOLDERS, PERCENT, PERCENT);
        // first % opens builder, second % closes with empty string - not in placeholders
        assertTrue(result.isEmpty());
    }

    @Test
    void textBeforeAndAfterPlaceholder() {
        Set<String> result = ParseString.keywords("Hello %player%, welcome!", PLACEHOLDERS, PERCENT, PERCENT);
        assertEquals(Set.of("player"), result);
    }

    @Test
    void duplicatePlaceholderReturnedOnce() {
        Set<String> result = ParseString.keywords("%player% %player% %player%", PLACEHOLDERS, PERCENT, PERCENT);
        assertEquals(Set.of("player"), result);
        assertEquals(1, result.size());
    }
}
