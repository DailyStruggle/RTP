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
    @DisplayName("Flow mappings and flow sequences are rejected (line start and inline)")
    void flowRejected() {
        assertEquals("rtpYaml.unsupported.flowMap",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("{a: b}\n")).messageKey());
        assertEquals("rtpYaml.unsupported.flowSeq",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("[a, b]\n")).messageKey());
        assertEquals("rtpYaml.unsupported.flowMap",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("a: {x: y}\n")).messageKey());
        assertEquals("rtpYaml.unsupported.flowSeq",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("a: [1, 2]\n")).messageKey());
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
}
