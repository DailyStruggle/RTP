package io.github.dailystruggle.rtp.common.configuration.yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RtpYamlWriter emitter (idempotent round-trip)")
class RtpYamlWriterTest {

    private static String reemit(String yaml) {
        return RtpYamlWriter.emit(RtpYamlReader.parse(yaml));
    }

    @Test
    @DisplayName("A simple mapping round-trips to the same text")
    void simpleRoundTrip() {
        String src = "a: 1\nb: hello\n";
        String once = reemit(src);
        assertEquals(src, once);
    }

    @Test
    @DisplayName("emit(parse(emit(parse(x)))) is byte-for-byte idempotent")
    void idempotence() {
        String src = "# file header\n\n# entry comment\nkey: value\nsection:\n  nested: 1\n  list:\n"
                + "    - a\n    - b\nquoted: \"needs \\\" escape\"\nsingle: 'it''s'\n\n# trailing\n";
        String once = reemit(src);
        String twice = reemit(once);
        assertEquals(once, twice);
    }

    @Test
    @DisplayName("Scalar quoting styles are preserved on emit")
    void quotingPreserved() {
        String out = reemit("p: plain\ns: 'sng'\nd: \"dbl\"\n");
        assertTrue(out.contains("p: plain"));
        assertTrue(out.contains("s: 'sng'"));
        assertTrue(out.contains("d: \"dbl\""));
    }

    @Test
    @DisplayName("Double-quoted scalars are re-escaped on emit")
    void doubleQuoteEscape() {
        RtpYamlMapping root = new RtpYamlMapping();
        root.put("k", new RtpYamlScalar("tab\there\nnl\"q\\b", RtpYamlScalar.Style.DOUBLE));
        String out = RtpYamlWriter.emit(root);
        assertTrue(out.contains("\\t"));
        assertTrue(out.contains("\\n"));
        assertTrue(out.contains("\\\""));
        assertTrue(out.contains("\\\\"));
    }

    @Test
    @DisplayName("Empty scalar values emit a bare key line")
    void emptyScalar() {
        RtpYamlMapping root = new RtpYamlMapping();
        root.put("empty", new RtpYamlScalar("", RtpYamlScalar.Style.PLAIN));
        assertEquals("empty:\n", RtpYamlWriter.emit(root));
    }

    @Test
    @DisplayName("Keys with special characters are quoted on emit")
    void keyQuoting() {
        RtpYamlMapping root = new RtpYamlMapping();
        root.put("has:colon", new RtpYamlScalar("v", RtpYamlScalar.Style.PLAIN));
        root.put("", new RtpYamlScalar("v", RtpYamlScalar.Style.PLAIN));
        root.put(" spaced ", new RtpYamlScalar("v", RtpYamlScalar.Style.PLAIN));
        String out = RtpYamlWriter.emit(root);
        assertTrue(out.contains("\"has:colon\":"));
        assertTrue(out.contains("\"\":"));
        assertTrue(out.contains("\" spaced \":"));
    }

    @Test
    @DisplayName("A programmatically-built commented scalar gets a separator blank line")
    void separatorBlankLine() {
        RtpYamlMapping root = new RtpYamlMapping();
        root.put("first", new RtpYamlScalar("1", RtpYamlScalar.Style.PLAIN));
        RtpYamlScalar second = new RtpYamlScalar("2", RtpYamlScalar.Style.PLAIN);
        second.addBlockComment("about second");
        root.put("second", second);
        String out = RtpYamlWriter.emit(root);
        assertTrue(out.contains("first: 1\n\n# about second\nsecond: 2\n"));
    }

    @Test
    @DisplayName("Nested sequences and mappings round-trip")
    void nestedStructures() {
        String src = "outer:\n  items:\n    - one\n    - two\n  child:\n    k: v\n";
        assertEquals(src, reemit(src));
    }

    @Test
    @DisplayName("Sequence of mappings with continuation entries round-trips")
    void sequenceOfMappings() {
        String src = "items:\n  - name: a\n    qty: 2\n  - name: b\n";
        assertEquals(src, reemit(src));
    }

    @Test
    @DisplayName("ADR-034: Chunky-style - [x, z] vertex items round-trip byte-for-byte")
    void flowPairItemsRoundTrip() {
        String src = "shape:\n"
                + "  name: POLYGON\n"
                + "  vertices:\n"
                + "    - [-125c, 187c]\n"
                + "    - [2000b, 3000b]\n"
                + "    - [10, -4]\n";
        String once = reemit(src);
        assertEquals(src, once);
        assertEquals(once, reemit(once));
    }

    @Test
    @DisplayName("ADR-034: inline nested flow sequence re-emits inline, whitespace normalised")
    void inlineFlowRoundTrip() {
        assertEquals("vertices: [[1, 2], [3, 4]]\n", reemit("vertices: [[1, 2], [3, 4]]\n"));
        assertEquals("a: [1, 2, 3]\nempty: []\nq: [\"x y\", 'it''s']\n",
                reemit("a: [ 1 ,2,3 ]\nempty: [ ]\nq: [\"x y\", 'it''s']\n"));
        // Inline flow under a sequence-of-mappings entry.
        String nested = "items:\n  - name: a\n    pos: [1, 2]\n";
        assertEquals(nested, reemit(nested));
    }

    @Test
    @DisplayName("ADR-034: vertex pairs rebuilt from Java lists on save stay compact [x, z] items")
    void javaListRebuildEmitsFlowPairs() {
        RtpYamlConfig cfg = RtpYamlConfig.parse("shape:\n  name: POLYGON\n  vertices:\n    - [-125c, 187c]\n    - [10, -4]\n");
        // Updater/migration path: read as Java lists, write the same lists back.
        java.util.List<?> vertices = cfg.getList("shape.vertices");
        cfg.set("shape.vertices", vertices);
        String out = cfg.saveToString();
        assertEquals("shape:\n  name: POLYGON\n  vertices:\n    - [\"-125c\", \"187c\"]\n    - [10, -4]\n", out);
        // And the rewritten file loads back to the same values.
        assertEquals(vertices, RtpYamlConfig.parse(out).getList("shape.vertices"));
    }

    @Test
    @DisplayName("Unflagged top-level scalar lists stay block style; unsafe flow entries fall back to block")
    void flowFallbacks() {
        // A Java list set directly under a key keeps the historical block layout.
        RtpYamlConfig cfg = RtpYamlConfig.parse("k: 1\n");
        cfg.set("list", java.util.List.of(1, 2));
        assertEquals("k: 1\nlist:\n  - 1\n  - 2\n", cfg.saveToString());

        // A flow-flagged sequence whose plain entry contains a flow indicator.
        RtpYamlMapping root = new RtpYamlMapping();
        RtpYamlSequence seq = new RtpYamlSequence();
        seq.setFlowStyle(true);
        seq.add(new RtpYamlScalar("a, b", RtpYamlScalar.Style.PLAIN));
        seq.add(new RtpYamlScalar("c", RtpYamlScalar.Style.PLAIN));
        root.put("s", seq);
        String out = RtpYamlWriter.emit(root);
        assertEquals("s:\n  - a, b\n  - c\n", out);
        assertEquals(out, reemit(out));

        // A commented item cannot live inside brackets.
        RtpYamlMapping root2 = new RtpYamlMapping();
        RtpYamlSequence outer = new RtpYamlSequence();
        RtpYamlSequence pair = new RtpYamlSequence();
        pair.add(new RtpYamlScalar("1", RtpYamlScalar.Style.PLAIN));
        RtpYamlScalar commented = new RtpYamlScalar("2", RtpYamlScalar.Style.PLAIN);
        commented.addBlockComment("note");
        pair.add(commented);
        outer.add(pair);
        root2.put("v", outer);
        assertEquals("v:\n  -\n    - 1\n    # note\n    - 2\n", RtpYamlWriter.emit(root2));
    }
}
