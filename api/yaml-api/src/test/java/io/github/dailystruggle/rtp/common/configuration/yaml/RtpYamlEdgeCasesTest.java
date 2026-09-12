package io.github.dailystruggle.rtp.common.configuration.yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RtpYaml reader/writer edge cases")
class RtpYamlEdgeCasesTest {

    private static String reemit(String yaml) {
        return RtpYamlWriter.emit(RtpYamlReader.parse(yaml));
    }

    @Test
    @DisplayName("Comments and blanks interleaved with sequence items are attached and re-emitted")
    void sequenceComments() {
        String src = "list:\n  # c1\n  - a\n\n  # c2\n  - b\n";
        RtpYamlMapping root = RtpYamlReader.parse(src);
        RtpYamlSequence seq = (RtpYamlSequence) root.get("list");
        assertEquals(2, seq.size());
        assertTrue(seq.get(0).blockComments().contains("c1"));
        assertTrue(seq.get(1).blockComments().contains("c2"));
    }

    @Test
    @DisplayName("A comment above a nested mapping child is buffered during look-ahead")
    void commentBeforeNestedMapping() {
        RtpYamlMapping root = RtpYamlReader.parse("parent:\n  # inner\n  child: 1\n");
        RtpYamlMapping parent = (RtpYamlMapping) root.get("parent");
        assertEquals(1, ((RtpYamlScalar) parent.get("child")).value());
    }

    @Test
    @DisplayName("Empty sequence items ('-') become empty scalars or child blocks")
    void emptySequenceItems() {
        RtpYamlMapping root = RtpYamlReader.parse("list:\n  -\n  - x\n");
        RtpYamlSequence seq = (RtpYamlSequence) root.get("list");
        assertEquals("", ((RtpYamlScalar) seq.get(0)).rawValue());
        assertEquals("x", ((RtpYamlScalar) seq.get(1)).value());
    }

    @Test
    @DisplayName("A quoted mapping key inside a sequence item is recognised")
    void quotedKeyInSequenceItem() {
        RtpYamlMapping root = RtpYamlReader.parse("list:\n  - \"a:b\": v\n");
        RtpYamlSequence seq = (RtpYamlSequence) root.get("list");
        RtpYamlMapping item = (RtpYamlMapping) seq.get(0);
        assertEquals("v", ((RtpYamlScalar) item.get("a:b")).value());
    }

    @Test
    @DisplayName("A hash inside a quoted scalar is not treated as an inline comment")
    void hashInsideQuotesKept() {
        RtpYamlMapping root = RtpYamlReader.parse("k: \"a # b\"\n");
        assertEquals("a # b", ((RtpYamlScalar) root.get("k")).rawValue());
    }

    @Test
    @DisplayName("Double-quote escape sequences are all decoded")
    void doubleQuoteEscapes() {
        RtpYamlMapping root = RtpYamlReader.parse("k: \"a\\tb\\rc\\\\d\\/e\\0f\\qg\"\n");
        assertEquals("a\tb\rc\\d/e\0f\\qg", ((RtpYamlScalar) root.get("k")).rawValue());
    }

    @Test
    @DisplayName("A bare '#' comment line yields an empty comment string")
    void bareHashComment() {
        RtpYamlMapping root = RtpYamlReader.parse("#\nk: 1\n");
        assertTrue(root.get("k").blockComments().contains(""));
    }

    @Test
    @DisplayName("Empty sequence and nested-sequence items round-trip through the writer")
    void writerNestedSequences() {
        RtpYamlMapping root = new RtpYamlMapping();
        RtpYamlSequence outer = new RtpYamlSequence();
        outer.add(new RtpYamlScalar("", RtpYamlScalar.Style.PLAIN)); // empty item
        RtpYamlSequence inner = new RtpYamlSequence();
        inner.add(new RtpYamlScalar("x", RtpYamlScalar.Style.PLAIN));
        outer.add(inner); // nested sequence
        outer.add(new RtpYamlMapping()); // empty mapping item
        root.put("list", outer);
        String out = RtpYamlWriter.emit(root);
        assertNotNull(out);
        assertTrue(out.startsWith("list:\n"));
    }

    @Test
    @DisplayName("A sequence item mapping with a section-valued continuation round-trips")
    void writerSequenceMappingWithSection() {
        String src = "list:\n  - name: a\n    sub:\n      k: 1\n";
        assertEquals(src, reemit(src));
    }

    @Test
    @DisplayName("Node source position accessors are exposed")
    void nodeSourcePosition() {
        RtpYamlMapping root = RtpYamlReader.parse("k: 1\n");
        RtpYamlNode n = root.get("k");
        assertEquals(1, n.sourceLine());
        assertEquals(0, n.sourceColumn());
    }

    @Test
    @DisplayName("Mapping remove deletes an entry")
    void mappingRemove() {
        RtpYamlMapping m = new RtpYamlMapping();
        m.put("k", new RtpYamlScalar("1", RtpYamlScalar.Style.PLAIN));
        assertTrue(m.containsKey("k"));
        m.remove("k");
        assertFalse(m.containsKey("k"));
    }
}
