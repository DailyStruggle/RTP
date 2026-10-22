package io.github.dailystruggle.rtp.common.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConfigDirectives}, the runtime parser for the
 * {@code @…} config-comment directive lines (ADR-064 amendment). Covers the
 * finite-domain detection path the in-game config editor relies on.
 */
@DisplayName("ConfigDirectives (@type / @options / @source runtime parser)")
class ConfigDirectivesTest {

    @Test
    @DisplayName("@options list is parsed (double-quoted, bracketed)")
    void parsesQuotedOptions() {
        String comment = "# Which database to use.\n"
                + "# @type: enum\n"
                + "# @options: [\"yaml\", \"sqlite\", \"mysql\", \"postgresql\"]\n"
                + "# @default: \"sqlite\"";
        ConfigDirectives d = ConfigDirectives.parse(comment);
        assertEquals("enum", d.type());
        assertEquals(List.of("yaml", "sqlite", "mysql", "postgresql"), d.options());
        assertNull(d.source());
        assertTrue(d.hasFiniteDomain());
    }

    @Test
    @DisplayName("@options tolerates unquoted bare tokens")
    void parsesUnquotedOptions() {
        ConfigDirectives d = ConfigDirectives.parse("# @options: [a, b, c]");
        assertEquals(List.of("a", "b", "c"), d.options());
        assertTrue(d.hasFiniteDomain());
    }

    @Test
    @DisplayName("@source is parsed and yields a finite domain")
    void parsesSource() {
        ConfigDirectives d = ConfigDirectives.parse("# @type: enum\n# @source: shape");
        assertEquals("enum", d.type());
        assertEquals("shape", d.source());
        assertTrue(d.options().isEmpty());
        assertTrue(d.hasFiniteDomain());
    }

    @Test
    @DisplayName("no directive lines yield an empty, non-finite result")
    void noDirectives() {
        ConfigDirectives d = ConfigDirectives.parse("# just a plain description\n# with two lines");
        assertNull(d.type());
        assertNull(d.source());
        assertTrue(d.options().isEmpty());
        assertFalse(d.hasFiniteDomain());
    }

    @Test
    @DisplayName("null / blank comment is handled gracefully")
    void nullComment() {
        assertFalse(ConfigDirectives.parse(null).hasFiniteDomain());
        assertFalse(ConfigDirectives.parse("").hasFiniteDomain());
    }

    @Test
    @DisplayName("a non-finite directive (@range only) is not treated as a finite domain")
    void rangeOnlyIsNotFinite() {
        ConfigDirectives d = ConfigDirectives.parse("# @type: integer\n# @range: [0, 65535]");
        assertEquals("integer", d.type());
        assertFalse(d.hasFiniteDomain());
    }

    @Test
    @DisplayName("malformed @options degrades to no finite domain")
    void malformedOptions() {
        ConfigDirectives d = ConfigDirectives.parse("# @options:");
        assertTrue(d.options().isEmpty());
        assertFalse(d.hasFiniteDomain());
    }

    @Test
    @DisplayName("ADR-121 @range parses inclusive bounds; null is an open end")
    void parsesRange() {
        ConfigDirectives closed = ConfigDirectives.parse("# @type: number\n# @range: [0.1, 20]");
        assertEquals(0.1, closed.rangeMin());
        assertEquals(20.0, closed.rangeMax());
        assertTrue(closed.hasRange());
        assertFalse(closed.rangeMalformed());

        ConfigDirectives open = ConfigDirectives.parse("# @range: [-1, null]   # integer only");
        assertEquals(-1.0, open.rangeMin());
        assertNull(open.rangeMax());
        assertTrue(open.hasRange());
        assertFalse(open.rangeMalformed());

        ConfigDirectives unbounded = ConfigDirectives.parse("# @range: [null, ~]");
        assertFalse(unbounded.hasRange());
        assertFalse(unbounded.rangeMalformed());
    }

    @Test
    @DisplayName("ADR-121 malformed @range is flagged and yields no bounds")
    void malformedRange() {
        for (String raw : new String[] {"[a, 5]", "[1, 2, 3]", "[5]", "[10, 1]", "[]"}) {
            ConfigDirectives d = ConfigDirectives.parse("# @type: integer\n# @range: " + raw);
            assertTrue(d.rangeMalformed(), raw);
        }
        assertFalse(ConfigDirectives.parse("# @type: integer").rangeMalformed());
    }

    @Test
    @DisplayName("ADR-121 @default and @unit are parsed; quotes are stripped, empty stays empty")
    void parsesDefaultAndUnit() {
        ConfigDirectives d = ConfigDirectives.parse("# @type: integer\n# @unit: seconds\n# @default: 2");
        assertEquals("2", d.defaultValue());
        assertEquals("seconds", d.unit());
        assertEquals("sqlite", ConfigDirectives.parse("# @default: \"sqlite\"").defaultValue());
        assertEquals("", ConfigDirectives.parse("# @default: \"\"").defaultValue());
        assertNull(ConfigDirectives.parse("# @type: string").defaultValue());
        assertEquals("#minecraft:logs", ConfigDirectives.parse("# @default: \"#minecraft:logs\"").defaultValue());
    }

    @Test
    @DisplayName("ADR-121 @options keeps commas inside quoted items and mixed scalars")
    void parsesQuotedCommaAndMixedOptions() {
        assertEquals(List.of("a, b", "c"), ConfigDirectives.parse("# @options: [\"a, b\", 'c']").options());
        assertEquals(List.of("off", "30", "45", "60"),
                ConfigDirectives.parse("# @options: [\"off\", 30, 45, 60]").options());
    }
}
