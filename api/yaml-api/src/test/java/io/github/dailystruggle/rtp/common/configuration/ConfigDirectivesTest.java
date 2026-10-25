package io.github.dailystruggle.rtp.common.configuration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConfigDirectives} (ADR-064 comment-directive parser).
 */
class ConfigDirectivesTest {

    @Test
    void parse_nullOrEmpty_returnsEmptyDirectives() {
        ConfigDirectives d1 = ConfigDirectives.parse((String) null);
        assertNull(d1.type());
        assertNull(d1.source());
        assertTrue(d1.options().isEmpty());
        assertFalse(d1.hasFiniteDomain());

        ConfigDirectives d2 = ConfigDirectives.parse("");
        assertFalse(d2.hasFiniteDomain());

        ConfigDirectives d3 = ConfigDirectives.parse("# plain prose comment\n# second line");
        assertFalse(d3.hasFiniteDomain());
    }

    @Test
    void parse_optionsFlowList_doubleQuoted() {
        String comment = "# Storage backend.\n"
                + "# @type: enum\n"
                + "# @options: [\"yaml\", \"sqlite\", \"mysql\", \"postgres\"]\n"
                + "# @default: \"sqlite\"";
        ConfigDirectives d = ConfigDirectives.parse(comment);
        assertEquals("enum", d.type());
        assertEquals(List.of("yaml", "sqlite", "mysql", "postgres"), d.options());
        assertNull(d.source());
        assertTrue(d.hasFiniteDomain());
    }

    @Test
    void parse_optionsFlowList_unquotedAndSingleQuoted() {
        String comment = "@options: [INFO, 'WARNING', \"SEVERE\", OFF]";
        ConfigDirectives d = ConfigDirectives.parse(comment);
        assertEquals(List.of("INFO", "WARNING", "SEVERE", "OFF"), d.options());
        assertTrue(d.hasFiniteDomain());
    }

    @Test
    void parse_sourceDirective_recognisedAsFiniteDomain() {
        String comment = "# Default region.\n# @type: string\n# @source: region\n# @default: \"default\"";
        ConfigDirectives d = ConfigDirectives.parse(comment);
        assertEquals("string", d.type());
        assertEquals("region", d.source());
        assertTrue(d.options().isEmpty());
        assertTrue(d.hasFiniteDomain());
    }

    @Test
    void parse_rangeUnitAndDefault() {
        String comment = "# Delay.\n# @type: integer\n# @range: [0, 300]\n# @unit: seconds\n# @default: \"2\"";
        ConfigDirectives d = ConfigDirectives.parse(comment);
        assertEquals("integer", d.type());
        assertEquals("seconds", d.unit());
        assertEquals("2", d.defaultValue());
        assertTrue(d.hasRange());
        assertFalse(d.rangeMalformed());
        assertEquals(0.0, d.rangeMin());
        assertEquals(300.0, d.rangeMax());
    }

    @Test
    void parse_rangeOpenBoundsAndMalformed() {
        ConfigDirectives lowerOnly = ConfigDirectives.parse("# @range: [0.0, null]");
        assertTrue(lowerOnly.hasRange());
        assertFalse(lowerOnly.rangeMalformed());
        assertEquals(0.0, lowerOnly.rangeMin());
        assertNull(lowerOnly.rangeMax());

        ConfigDirectives upperOnly = ConfigDirectives.parse("# @range: [~, 1.0]");
        assertTrue(upperOnly.hasRange());
        assertFalse(upperOnly.rangeMalformed());
        assertNull(upperOnly.rangeMin());
        assertEquals(1.0, upperOnly.rangeMax());

        for (String bad : List.of("# @range: [10, 5]", "# @range: [1]", "# @range: [a, b]", "# @range: []")) {
            ConfigDirectives malformed = ConfigDirectives.parse(bad);
            assertFalse(malformed.hasRange(), bad);
            assertTrue(malformed.rangeMalformed(), bad);
        }
    }

    @Test
    void parse_defaultPreservesEmptyQuotedAndIgnoresNullTokens() {
        assertEquals("", ConfigDirectives.parse("# @default: \"\"").defaultValue());
        assertEquals("", ConfigDirectives.parse("# @default: ''").defaultValue());
        assertNull(ConfigDirectives.parse("# @default: null").defaultValue());
        assertNull(ConfigDirectives.parse("# @default: ~").defaultValue());
    }

    @Test
    void parse_patternDirective_validAndMalformed() {
        ConfigDirectives valid = ConfigDirectives.parse("# @pattern: \"^[a-z0-9_]+$\" # identifier");
        assertEquals("^[a-z0-9_]+$", valid.pattern());
        assertNotNull(valid.compiledPattern());
        assertTrue(valid.hasPattern());
        assertFalse(valid.patternMalformed());
        assertTrue(valid.compiledPattern().matcher("region_1").matches());

        ConfigDirectives badRegex = ConfigDirectives.parse("# @pattern: \"[unclosed\"");
        assertEquals("[unclosed", badRegex.pattern());
        assertNull(badRegex.compiledPattern());
        assertFalse(badRegex.hasPattern());
        assertTrue(badRegex.patternMalformed());

        ConfigDirectives emptyRegex = ConfigDirectives.parse("# @pattern: \"\"");
        assertEquals("", emptyRegex.pattern());
        assertNull(emptyRegex.compiledPattern());
        assertFalse(emptyRegex.hasPattern());
        assertTrue(emptyRegex.patternMalformed());
    }

    @Test
    void parse_flowListPreservesCommasInQuotesAndStripsTrailingComments() {
        ConfigDirectives d = ConfigDirectives.parse("# @options: [\"a,b\", 'c,d', e] # inline note");
        assertEquals(List.of("a,b", "c,d", "e"), d.options());
    }
}
