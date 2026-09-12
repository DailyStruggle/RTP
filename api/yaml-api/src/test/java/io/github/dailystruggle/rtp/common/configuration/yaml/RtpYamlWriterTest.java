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
}
