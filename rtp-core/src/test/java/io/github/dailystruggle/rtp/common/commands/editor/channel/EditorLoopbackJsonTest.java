package io.github.dailystruggle.rtp.common.commands.editor.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Escaping, literal coverage and fail-closed rejection of the strict loopback JSON reader (ADR-104). */
@DisplayName("ADR-104: EditorLoopbackJson quoting and strict parsing")
class EditorLoopbackJsonTest {

    private static void assertRejected(String json, String messagePart) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EditorLoopbackJson.parse(json), json);
        assertTrue(e.getMessage().contains(messagePart), json + " -> " + e.getMessage());
    }

    @Test
    @DisplayName("quote escapes every control character, both JS line separators and the two JSON metacharacters")
    void quoteEscapesEverything() {
        String raw = "q\"b\\n\nr\rt\tb\bf\fc\u0001s\u2028p\u2029\u00e9";
        String quoted = EditorLoopbackJson.quote(raw);
        assertEquals("\"q\\\"b\\\\n\\nr\\rt\\tb\\bf\\fc\\u0001s\\u2028p\\u2029\u00e9\"", quoted);
        assertEquals(raw, EditorLoopbackJson.parse(quoted), "round-trips through the reader");
        assertEquals("\"\"", EditorLoopbackJson.quote(""));
    }

    @Test
    @DisplayName("String escapes \\b \\f \\r \\t \\/ \\\\ and \\uXXXX decode")
    void stringEscapesDecode() {
        assertEquals("\b\f\r\t/\\\"A\u00e9", EditorLoopbackJson.parse("\"\\b\\f\\r\\t\\/\\\\\\\"\\u0041\\u00E9\""));
    }

    @Test
    @DisplayName("Numbers: integral as Long, fractions and exponents as Double, overflow falls back to Double")
    void numbers() {
        assertEquals(0L, EditorLoopbackJson.parse("0"));
        assertEquals(-12L, EditorLoopbackJson.parse("-12"));
        assertEquals(-0.5, EditorLoopbackJson.parse("-0.5"));
        assertEquals(1e5, EditorLoopbackJson.parse("1e5"));
        assertEquals(1.5e12, EditorLoopbackJson.parse("1.5E+12"));
        assertEquals(2e-3, EditorLoopbackJson.parse("2e-3"));
        assertEquals(1e19, EditorLoopbackJson.parse("10000000000000000000"), "beyond Long range");
    }

    @Test
    @DisplayName("All whitespace kinds are skipped around values and structural characters")
    void whitespace() {
        Object v = EditorLoopbackJson.parse(" \t\r\n{ \"a\" :\t[ 1 ,\n2 ] ,\r\"b\" : false }\n");
        Map<?, ?> m = assertInstanceOf(Map.class, v);
        assertEquals(List.of(1L, 2L), m.get("a"));
        assertEquals(Boolean.FALSE, m.get("b"));
        assertEquals(List.of(), EditorLoopbackJson.parse("[ ]"));
        assertEquals(Arrays.asList(true, null), EditorLoopbackJson.parse("[true,null]"));
    }

    @Test
    @DisplayName("Nesting is accepted up to MAX_DEPTH and rejected beyond it")
    void depthLimit() {
        String ok = "[".repeat(EditorLoopbackJson.MAX_DEPTH + 1) + "]".repeat(EditorLoopbackJson.MAX_DEPTH + 1);
        assertInstanceOf(List.class, EditorLoopbackJson.parse(ok));
        String deep = "[".repeat(EditorLoopbackJson.MAX_DEPTH + 2) + "]".repeat(EditorLoopbackJson.MAX_DEPTH + 2);
        assertRejected(deep, "nesting deeper than");
    }

    @Test
    @DisplayName("Malformed documents fail closed with an offset-bearing IllegalArgumentException")
    void malformedRejected() {
        assertThrows(IllegalArgumentException.class, () -> EditorLoopbackJson.parse(null));
        assertRejected("", "unexpected end of input");
        assertRejected("   ", "unexpected end of input");
        assertRejected("@", "unexpected character '@'");
        assertRejected("[1;2]", "expected ',' or ']'");
        assertRejected("[1,2", "unexpected end of input");
        assertRejected("{\"a\":1;}", "expected ',' or '}'");
        assertRejected("{1:2}", "expected object key");
        assertRejected("{\"a\" 1}", "expected ':'");
        assertRejected("\"a\u0001\"", "raw control character");
        assertRejected("\"abc", "unexpected end of input");
        assertRejected("\"\\u12\"", "truncated \\u escape");
        assertRejected("\"\\u12zz\"", "bad \\u escape");
        assertRejected("\"\\x\"", "bad escape");
        assertRejected("-", "bad number");
        assertRejected("-a", "bad number");
        assertRejected("1.", "bad fraction");
        assertRejected("1.e3", "bad fraction");
        assertRejected("1e", "bad exponent");
        assertRejected("1e+", "bad exponent");
        assertRejected("1E-x", "bad exponent");
        assertRejected("nul", "bad literal");
        assertRejected("tru", "bad literal");
        assertRejected("fals", "bad literal");
        assertRejected("1 2", "trailing characters");
    }
}
