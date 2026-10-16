package io.github.dailystruggle.rtp.common.configuration.yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RtpYamlWriter flow-vs-block decisions (ADR-025 subset)")
class RtpYamlWriterFlowSafetyTest {

    private static RtpYamlScalar plain(String v) {
        return new RtpYamlScalar(v, RtpYamlScalar.Style.PLAIN);
    }

    private static RtpYamlSequence seq(boolean flow, RtpYamlNode... items) {
        RtpYamlSequence s = new RtpYamlSequence();
        s.setFlowStyle(flow);
        for (RtpYamlNode n : items) s.add(n);
        return s;
    }

    private static String emit(RtpYamlNode value) {
        RtpYamlMapping root = new RtpYamlMapping();
        root.put("k", value);
        return RtpYamlWriter.emit(root);
    }

    /** True when a flagged single-item sequence holding {@code sc} stays in flow style. */
    private static boolean flowSafe(RtpYamlScalar sc) {
        return emit(seq(true, sc)).startsWith("k: [");
    }

    @Test
    @DisplayName("PLAIN entries with flow indicators, quotes or control chars fall back to block")
    void plainIndicatorChars() {
        for (String bad : List.of("a,b", "a[b", "a]b", "a{b", "a}b", "a\"b", "a'b", "a\nb", "a\rb", "a\tb")) {
            assertFalse(flowSafe(plain(bad)), "expected block output for " + bad.replace("\n", "\\n")
                    .replace("\r", "\\r").replace("\t", "\\t"));
        }
        assertTrue(flowSafe(plain("abc")));
    }

    @Test
    @DisplayName("PLAIN entries that are empty, padded or start with an indicator fall back to block")
    void plainHeadRules() {
        assertFalse(flowSafe(plain("")));
        assertFalse(flowSafe(plain(" a")));
        assertFalse(flowSafe(plain("a ")));
        assertFalse(flowSafe(plain("&a")));
        assertFalse(flowSafe(plain("%a")));
        assertFalse(flowSafe(plain("-")));
        assertFalse(flowSafe(plain("- a")));
        assertFalse(flowSafe(plain("?")));
        assertFalse(flowSafe(plain("? a")));
        assertTrue(flowSafe(plain("-a")));
        assertTrue(flowSafe(plain("?a")));
    }

    @Test
    @DisplayName("PLAIN '#' and ':' are flow-safe only when they cannot start a comment or key")
    void plainCommentAndKeyMarkers() {
        assertTrue(flowSafe(plain("a#b")));
        assertFalse(flowSafe(plain("a #b")));
        assertTrue(flowSafe(plain("a:b")));
        assertFalse(flowSafe(plain("a:")));
        assertFalse(flowSafe(plain("a: b")));
    }

    @Test
    @DisplayName("SINGLE entries are flow-safe unless they contain a raw line break")
    void singleQuoted() {
        assertTrue(flowSafe(new RtpYamlScalar("it's", RtpYamlScalar.Style.SINGLE)));
        assertFalse(flowSafe(new RtpYamlScalar("a\nb", RtpYamlScalar.Style.SINGLE)));
        assertFalse(flowSafe(new RtpYamlScalar("a\rb", RtpYamlScalar.Style.SINGLE)));
        assertTrue(flowSafe(new RtpYamlScalar("a\nb", RtpYamlScalar.Style.DOUBLE)));
    }

    @Test
    @DisplayName("Flagged sequences holding a mapping, directly or nested, fall back to block")
    void flaggedWithMapping() {
        RtpYamlMapping m = new RtpYamlMapping();
        m.put("x", plain("1"));
        assertTrue(emit(seq(true, plain("a"), m)).startsWith("k:\n"));

        RtpYamlMapping m2 = new RtpYamlMapping();
        m2.put("y", plain("2"));
        assertTrue(emit(seq(true, seq(true, m2))).startsWith("k:\n"));

        assertEquals("k: [a, [b, c]]\n", emit(seq(true, plain("a"), seq(true, plain("b"), plain("c")))));
    }

    @Test
    @DisplayName("Unflagged nested sequences auto-flow only when non-empty, scalar-only and short")
    void unflaggedNested() {
        String shortOut = emit(seq(false, seq(false, plain("a"), plain("b"))));
        assertTrue(shortOut.contains("- [a, b]"), shortOut);

        String emptyOut = emit(seq(false, seq(false)));
        assertFalse(emptyOut.contains("["), emptyOut);

        RtpYamlMapping m = new RtpYamlMapping();
        m.put("x", plain("1"));
        String mapOut = emit(seq(false, seq(false, plain("a"), m)));
        assertFalse(mapOut.contains("["), mapOut);

        String longItem = "v".repeat(90);
        String longOut = emit(seq(false, seq(false, plain(longItem))));
        assertFalse(longOut.contains("["), longOut);

        String flaggedLong = emit(seq(true, plain(longItem)));
        assertEquals("k: [" + longItem + "]\n", flaggedLong);
    }
}
