package io.github.dailystruggle.rtp.common.configuration.yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RtpYamlReader parser (ADR-025 subset)")
class RtpYamlReaderTest {

    @Test
    @DisplayName("Empty source yields an empty root mapping")
    void emptySource() {
        RtpYamlMapping root = RtpYamlReader.parse("");
        assertTrue(root.entries().isEmpty());
        assertEquals(1, root.sourceLine());
    }

    @Test
    @DisplayName("Plain scalar mapping entries are parsed with primitive coercion")
    void plainScalars() {
        RtpYamlMapping root = RtpYamlReader.parse("a: 1\nb: hello\nc: true\nd: 1.5\n");
        assertEquals(1, ((RtpYamlScalar) root.get("a")).value());
        assertEquals("hello", ((RtpYamlScalar) root.get("b")).value());
        assertEquals(Boolean.TRUE, ((RtpYamlScalar) root.get("c")).value());
        assertEquals(1.5, ((RtpYamlScalar) root.get("d")).value());
    }

    @Test
    @DisplayName("Single- and double-quoted scalars retain style and unescape")
    void quotedScalars() {
        RtpYamlMapping root = RtpYamlReader.parse("s: 'it''s'\nd: \"line\\nbreak\"\n");
        RtpYamlScalar s = (RtpYamlScalar) root.get("s");
        RtpYamlScalar d = (RtpYamlScalar) root.get("d");
        assertEquals(RtpYamlScalar.Style.SINGLE, s.style());
        assertEquals("it's", s.rawValue());
        assertEquals(RtpYamlScalar.Style.DOUBLE, d.style());
        assertEquals("line\nbreak", d.rawValue());
    }

    @Test
    @DisplayName("Quoted keys are unquoted")
    void quotedKeys() {
        RtpYamlMapping root = RtpYamlReader.parse("\"a:b\": 1\n'plain': 2\n");
        assertTrue(root.containsKey("a:b"));
        assertTrue(root.containsKey("plain"));
    }

    @Test
    @DisplayName("Nested mappings are parsed by indentation")
    void nestedMapping() {
        RtpYamlMapping root = RtpYamlReader.parse("parent:\n  child: value\n  n:\n    deep: 9\n");
        RtpYamlMapping parent = (RtpYamlMapping) root.get("parent");
        assertEquals("value", ((RtpYamlScalar) parent.get("child")).value());
        RtpYamlMapping n = (RtpYamlMapping) parent.get("n");
        assertEquals(9, ((RtpYamlScalar) n.get("deep")).value());
    }

    @Test
    @DisplayName("Block sequences of scalars are parsed")
    void scalarSequence() {
        RtpYamlMapping root = RtpYamlReader.parse("list:\n  - a\n  - b\n  - c\n");
        RtpYamlSequence seq = (RtpYamlSequence) root.get("list");
        assertEquals(3, seq.size());
        assertEquals("a", ((RtpYamlScalar) seq.get(0)).value());
        assertEquals("c", ((RtpYamlScalar) seq.get(2)).value());
    }

    @Test
    @DisplayName("Sequence of mappings parses inline key plus continuation")
    void sequenceOfMappings() {
        RtpYamlMapping root = RtpYamlReader.parse("items:\n  - name: a\n    qty: 2\n  - name: b\n");
        RtpYamlSequence seq = (RtpYamlSequence) root.get("items");
        assertEquals(2, seq.size());
        RtpYamlMapping first = (RtpYamlMapping) seq.get(0);
        assertEquals("a", ((RtpYamlScalar) first.get("name")).value());
        assertEquals(2, ((RtpYamlScalar) first.get("qty")).value());
    }

    @Test
    @DisplayName("Empty-valued key with no child becomes an empty scalar")
    void emptyValueScalar() {
        RtpYamlMapping root = RtpYamlReader.parse("empty:\nnext: 1\n");
        RtpYamlScalar empty = (RtpYamlScalar) root.get("empty");
        assertEquals("", empty.rawValue());
        assertEquals(1, ((RtpYamlScalar) root.get("next")).value());
    }

    @Test
    @DisplayName("Empty-valued key at EOF becomes an empty scalar")
    void emptyValueAtEof() {
        RtpYamlMapping root = RtpYamlReader.parse("empty:\n");
        assertEquals("", ((RtpYamlScalar) root.get("empty")).rawValue());
    }

    @Test
    @DisplayName("A document header separated by a blank line lifts onto the root")
    void documentHeaderLift() {
        RtpYamlMapping root = RtpYamlReader.parse("# file header\n\n# key comment\nkey: 1\n");
        assertTrue(root.blockComments().contains("file header"));
        assertTrue(root.get("key").blockComments().contains("key comment"));
    }

    @Test
    @DisplayName("Trailing comments after the last entry are preserved on the root")
    void trailingComments() {
        RtpYamlMapping root = RtpYamlReader.parse("key: 1\n\n# trailing note\n");
        assertTrue(root.trailingComments().contains("trailing note"));
    }

    @Test
    @DisplayName("Inline trailing comments are stripped on parse")
    void inlineCommentStripped() {
        RtpYamlMapping root = RtpYamlReader.parse("key: value # trailing\n");
        assertEquals("value", ((RtpYamlScalar) root.get("key")).rawValue());
    }

    @Test
    @DisplayName("A UTF-8 BOM at position 0 is stripped")
    void bomStripped() {
        RtpYamlMapping root = RtpYamlReader.parse("\uFEFFkey: 1\n");
        assertTrue(root.containsKey("key"));
    }

    @Test
    @DisplayName("CRLF and CR line endings are normalised")
    void mixedLineEndings() {
        RtpYamlMapping root = RtpYamlReader.parse("a: 1\r\nb: 2\rc: 3\n");
        assertEquals(1, ((RtpYamlScalar) root.get("a")).value());
        assertEquals(2, ((RtpYamlScalar) root.get("b")).value());
        assertEquals(3, ((RtpYamlScalar) root.get("c")).value());
    }

    @Test
    @DisplayName("parse(Reader) reads the whole stream")
    void parseReader() throws IOException {
        RtpYamlMapping root = RtpYamlReader.parse(new StringReader("a: 1\n"));
        assertEquals(1, ((RtpYamlScalar) root.get("a")).value());
        assertTrue(RtpYamlReader.parseFromReader(new StringReader("x: y\n")).containsKey("x"));
        assertTrue(RtpYamlReader.parseString("z: 1\n").containsKey("z"));
    }

    /* -------------------- rejected constructs -------------------- */

    @Test
    @DisplayName("Tabs in indentation are rejected")
    void tabIndentRejected() {
        RtpYamlParseException ex = assertThrows(RtpYamlParseException.class,
                () -> RtpYamlReader.parse("a:\n\tb: 1\n"));
        assertEquals("rtpYaml.syntax.tabIndent", ex.messageKey());
    }

    @Test
    @DisplayName("Document separators are rejected")
    void docSepRejected() {
        assertEquals("rtpYaml.unsupported.docSep",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("---\na: 1\n")).messageKey());
        assertEquals("rtpYaml.unsupported.docSep",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("... \n")).messageKey());
    }

    @Test
    @DisplayName("Anchors, aliases, and tags are rejected (line start and inline)")
    void anchorAliasTag() {
        assertEquals("rtpYaml.unsupported.anchor",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("&anchor\n")).messageKey());
        assertEquals("rtpYaml.unsupported.alias",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("*alias\n")).messageKey());
        assertEquals("rtpYaml.unsupported.tag",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("!tag\n")).messageKey());
        assertEquals("rtpYaml.unsupported.anchor",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("a: &x 1\n")).messageKey());
        assertEquals("rtpYaml.unsupported.alias",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("a: *x\n")).messageKey());
        assertEquals("rtpYaml.unsupported.tag",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("a: !!str x\n")).messageKey());
    }

    @Test
    @DisplayName("Flow mappings stay rejected; a flow sequence at line start (key / document root) is rejected")
    void flowMapAndRootFlowSeqRejected() {
        assertEquals("rtpYaml.unsupported.flowMap",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("{a: b}\n")).messageKey());
        assertEquals("rtpYaml.unsupported.flowSeq",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("[a, b]\n")).messageKey());
        assertEquals("rtpYaml.unsupported.flowMap",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("a: {x: y}\n")).messageKey());
        assertEquals("rtpYaml.unsupported.flowMap",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("a: [{x: 1}]\n")).messageKey());
        assertEquals("rtpYaml.unsupported.flowMap",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("a: [x: 1]\n")).messageKey());
    }

    @Test
    @DisplayName("ADR-034: inline flow sequence after key parses as a flow-styled sequence")
    void inlineFlowSequence() {
        RtpYamlMapping root = RtpYamlReader.parse("a: [1, two, \"th ree\", 'f,our']\n");
        RtpYamlSequence seq = (RtpYamlSequence) root.get("a");
        assertTrue(seq.isFlowStyle());
        assertEquals(4, seq.size());
        assertEquals(1, ((RtpYamlScalar) seq.get(0)).value());
        assertEquals("two", ((RtpYamlScalar) seq.get(1)).value());
        assertEquals(RtpYamlScalar.Style.DOUBLE, ((RtpYamlScalar) seq.get(2)).style());
        assertEquals("th ree", ((RtpYamlScalar) seq.get(2)).value());
        assertEquals(RtpYamlScalar.Style.SINGLE, ((RtpYamlScalar) seq.get(3)).style());
        assertEquals("f,our", ((RtpYamlScalar) seq.get(3)).value());
    }

    @Test
    @DisplayName("ADR-034: nested inline flow sequence [[1, 2], [3, 4]] parses two levels deep")
    void nestedInlineFlowSequence() {
        RtpYamlMapping root = RtpYamlReader.parse("vertices: [[1, 2], [3, 4]]\n");
        RtpYamlSequence outer = (RtpYamlSequence) root.get("vertices");
        assertTrue(outer.isFlowStyle());
        assertEquals(2, outer.size());
        RtpYamlSequence second = (RtpYamlSequence) outer.get(1);
        assertTrue(second.isFlowStyle());
        assertEquals(3, ((RtpYamlScalar) second.get(0)).value());
        assertEquals(4, ((RtpYamlScalar) second.get(1)).value());
        // Three levels also work.
        RtpYamlSequence deep = (RtpYamlSequence) RtpYamlReader.parse("d: [[[x]]]\n").get("d");
        assertEquals("x", ((RtpYamlScalar) ((RtpYamlSequence) ((RtpYamlSequence) deep.get(0)).get(0)).get(0)).value());
    }

    @Test
    @DisplayName("ADR-034: block items written as - [x, z] pairs parse (Chunky-style vertices)")
    void blockItemFlowPairs() {
        String src = "shape:\n"
                + "  name: POLYGON\n"
                + "  vertices:\n"
                + "    - [-125c, 187c]\n"
                + "    - [2000b, 3000b]\n"
                + "    - [10, -4] # trailing comment\n";
        RtpYamlMapping shape = (RtpYamlMapping) RtpYamlReader.parse(src).get("shape");
        RtpYamlSequence vertices = (RtpYamlSequence) shape.get("vertices");
        assertFalse(vertices.isFlowStyle(), "outer list is block style in the source");
        assertEquals(3, vertices.size());
        RtpYamlSequence first = (RtpYamlSequence) vertices.get(0);
        assertTrue(first.isFlowStyle());
        assertEquals("-125c", ((RtpYamlScalar) first.get(0)).value());
        assertEquals("187c", ((RtpYamlScalar) first.get(1)).value());
        RtpYamlSequence third = (RtpYamlSequence) vertices.get(2);
        assertEquals(10, ((RtpYamlScalar) third.get(0)).value());
        assertEquals(-4, ((RtpYamlScalar) third.get(1)).value());
        // Section view coerces to nested Java lists.
        RtpYamlSection section = new RtpYamlSection(shape);
        assertEquals(java.util.List.of(java.util.List.of("-125c", "187c"),
                java.util.List.of("2000b", "3000b"), java.util.List.of(10, -4)), section.getList("vertices"));
    }

    @Test
    @DisplayName("Flow sequences tolerate whitespace, empty [] and a trailing comma")
    void flowWhitespaceEmptyAndTrailingComma() {
        RtpYamlMapping root = RtpYamlReader.parse("a: [  1 ,2,   3  ]\nb: []\nc: [ ]\nd: [1, 2,]\n");
        RtpYamlSequence a = (RtpYamlSequence) root.get("a");
        assertEquals(3, a.size());
        assertEquals(1, ((RtpYamlScalar) a.get(0)).value());
        assertEquals(3, ((RtpYamlScalar) a.get(2)).value());
        assertEquals(0, ((RtpYamlSequence) root.get("b")).size());
        assertTrue(((RtpYamlSequence) root.get("b")).isFlowStyle());
        assertEquals(0, ((RtpYamlSequence) root.get("c")).size());
        assertEquals(2, ((RtpYamlSequence) root.get("d")).size());
    }

    @Test
    @DisplayName("Quoted scalars that look like brackets stay strings (world: \"[0]\")")
    void quotedBracketStaysString() {
        RtpYamlMapping root = RtpYamlReader.parse("world: \"[0]\"\nother: '[a, b]'\nlist:\n  - \"[1, 2]\"\n");
        RtpYamlScalar world = (RtpYamlScalar) root.get("world");
        assertEquals("[0]", world.value());
        assertEquals(RtpYamlScalar.Style.DOUBLE, world.style());
        assertEquals("[a, b]", ((RtpYamlScalar) root.get("other")).value());
        assertEquals("[1, 2]", ((RtpYamlScalar) ((RtpYamlSequence) root.get("list")).get(0)).value());
        // Brackets that do not open the value are plain text.
        assertEquals("a [b] c", ((RtpYamlScalar) RtpYamlReader.parse("k: a [b] c\n").get("k")).value());
    }

    @Test
    @DisplayName("Malformed flow sequences raise positioned parse errors")
    void malformedFlowSequence() {
        RtpYamlParseException unterminated = assertThrows(RtpYamlParseException.class,
                () -> RtpYamlReader.parse("a: [1, 2\n"));
        assertEquals("rtpYaml.syntax.unterminatedFlowSeq", unterminated.messageKey());
        assertEquals(1, unterminated.line());
        assertEquals(3, unterminated.column(), "column of the opening '['");

        RtpYamlParseException item = assertThrows(RtpYamlParseException.class,
                () -> RtpYamlReader.parse("v:\n  - [1, 2\n"));
        assertEquals("rtpYaml.syntax.unterminatedFlowSeq", item.messageKey());
        assertEquals(2, item.line());
        assertEquals(4, item.column());

        assertEquals("rtpYaml.syntax.unterminatedFlowSeq",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("a: [[1, 2]\n")).messageKey());
        RtpYamlParseException trailing = assertThrows(RtpYamlParseException.class,
                () -> RtpYamlReader.parse("a: [1, 2] extra\n"));
        assertEquals("rtpYaml.syntax.flowTrailing", trailing.messageKey());
        assertEquals(10, trailing.column());
        assertEquals("rtpYaml.syntax.flowSeq",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("a: [1, , 2]\n")).messageKey());
        assertEquals("rtpYaml.syntax.flowSeq",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("a: [[1] 2]\n")).messageKey());
        assertEquals("rtpYaml.syntax.unterminatedQuote",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("a: [\"x, 2]\n")).messageKey());
        assertEquals("rtpYaml.unsupported.anchor",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("a: [&x 1]\n")).messageKey());
    }

    @Test
    @DisplayName("Block scalars and merge keys are rejected")
    void blockScalarAndMergeKey() {
        assertEquals("rtpYaml.unsupported.blockScalar",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("a: |\n")).messageKey());
        assertEquals("rtpYaml.unsupported.mergeKey",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("<<: value\n")).messageKey());
    }

    @Test
    @DisplayName("Missing colon and unterminated quotes are syntax errors")
    void syntaxErrors() {
        assertEquals("rtpYaml.syntax.missingColon",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("noColonHere\n")).messageKey());
        assertEquals("rtpYaml.syntax.unterminatedQuote",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("\"unterminated: 1\n")).messageKey());
        assertEquals("rtpYaml.syntax.unterminatedQuote",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("k: \"unterminated\n")).messageKey());
    }

    @Test
    @DisplayName("A sequence item where a mapping entry is expected is rejected")
    void listAtMappingScope() {
        assertEquals("rtpYaml.syntax.listAtMapping",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("a: 1\nb:\n  - item\nc: 1\n- item\n")).messageKey());
    }

    @Test
    @DisplayName("Unexpected indentation is rejected")
    void unexpectedIndent() {
        RtpYamlParseException ex = assertThrows(RtpYamlParseException.class,
                () -> RtpYamlReader.parse("a: 1\n    b: 2\n"));
        assertEquals("rtpYaml.syntax.unexpectedIndent", ex.messageKey());
    }

    @Test
    @DisplayName("The exception carries 1-based line/column and a readable message")
    void exceptionCoordinates() {
        RtpYamlParseException ex = assertThrows(RtpYamlParseException.class,
                () -> RtpYamlReader.parse("ok: 1\nbad\n"));
        assertEquals(2, ex.line());
        assertEquals(0, ex.column());
        assertTrue(ex.getMessage().contains("line 2"));
    }

    @Test
    @DisplayName("Deeply nested block mappings and flow sequences reject with rtpYaml.syntax.maxDepth")
    void maxDepthRejects() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            sb.append("  ".repeat(i)).append("k").append(i).append(":\n");
        }
        sb.append("  ".repeat(150)).append("v: 1\n");
        RtpYamlParseException ex1 = assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse(sb.toString()));
        assertEquals("rtpYaml.syntax.maxDepth", ex1.messageKey());

        String flowSeq = "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]";
        RtpYamlParseException ex2 = assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("flow: " + flowSeq));
        assertEquals("rtpYaml.syntax.maxDepth", ex2.messageKey());
    }
}
