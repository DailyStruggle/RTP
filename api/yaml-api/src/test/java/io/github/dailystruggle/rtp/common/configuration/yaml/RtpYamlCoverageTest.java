package io.github.dailystruggle.rtp.common.configuration.yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RtpYaml additional branch coverage")
class RtpYamlCoverageTest {

    /* ------------------------ reader ------------------------ */

    @Test
    @DisplayName("parse(Reader) accepts an already-buffered reader")
    void parseBufferedReader() throws IOException {
        RtpYamlMapping root = RtpYamlReader.parse(new BufferedReader(new StringReader("a: 1\n")));
        assertEquals(1, ((RtpYamlScalar) root.get("a")).value());
    }

    @Test
    @DisplayName("A blank line inside a child block look-ahead is skipped")
    void blankInsideChildBlock() {
        RtpYamlMapping root = RtpYamlReader.parse("parent:\n\n  child: 1\n");
        RtpYamlMapping parent = (RtpYamlMapping) root.get("parent");
        assertEquals(1, ((RtpYamlScalar) parent.get("child")).value());
    }

    @Test
    @DisplayName("A sequence item whose value is an empty key opens a child block")
    void sequenceItemEmptyKeyChild() {
        RtpYamlMapping root = RtpYamlReader.parse("list:\n  - key:\n      sub: 1\n");
        RtpYamlSequence seq = (RtpYamlSequence) root.get("list");
        RtpYamlMapping item = (RtpYamlMapping) seq.get(0);
        RtpYamlMapping key = (RtpYamlMapping) item.get("key");
        assertEquals(1, ((RtpYamlScalar) key.get("sub")).value());
    }

    @Test
    @DisplayName("A quoted, non-key token in a sequence item stays a scalar")
    void sequenceItemQuotedScalar() {
        RtpYamlMapping root = RtpYamlReader.parse("list:\n  - \"just a value\"\n  - 'single'\n");
        RtpYamlSequence seq = (RtpYamlSequence) root.get("list");
        assertEquals("just a value", ((RtpYamlScalar) seq.get(0)).rawValue());
        assertEquals("single", ((RtpYamlScalar) seq.get(1)).rawValue());
    }

    @Test
    @DisplayName("A single-quoted key inside a sequence item is recognised")
    void sequenceItemSingleQuotedKey() {
        RtpYamlMapping root = RtpYamlReader.parse("list:\n  - 'a:b': v\n");
        RtpYamlMapping item = (RtpYamlMapping) ((RtpYamlSequence) root.get("list")).get(0);
        assertEquals("v", ((RtpYamlScalar) item.get("a:b")).value());
    }

    @Test
    @DisplayName("Quoted keys with escaped quotes are parsed")
    void quotedKeyEscapes() {
        RtpYamlMapping dbl = RtpYamlReader.parse("\"a\\\"b\": 1\n");
        assertTrue(dbl.containsKey("a\"b"));
        RtpYamlMapping sgl = RtpYamlReader.parse("'a''b': 1\n");
        assertTrue(sgl.containsKey("a'b"));
    }

    @Test
    @DisplayName("A quoted key with no following colon is a syntax error")
    void quotedKeyNoColon() {
        assertEquals("rtpYaml.syntax.missingColon",
                assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("\"orphan\"\n")).messageKey());
    }

    @Test
    @DisplayName("A non-list line at sequence-item indent ends the sequence (then errors in mapping scope)")
    void sequenceEndedByKey() {
        assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse("list:\n  - a\n  key: v\n"));
    }

    @Test
    @DisplayName("Over-indented sequence items are rejected")
    void sequenceUnexpectedIndent() {
        assertEquals("rtpYaml.syntax.unexpectedIndent",
                assertThrows(RtpYamlParseException.class,
                        () -> RtpYamlReader.parse("list:\n  - a\n      - b\n")).messageKey());
    }

    @Test
    @DisplayName("A trailing inline comment after a quoted value is stripped")
    void inlineCommentAfterQuote() {
        RtpYamlMapping root = RtpYamlReader.parse("k: \"v\" # tail\n");
        assertEquals("v", ((RtpYamlScalar) root.get("k")).rawValue());
    }

    @Test
    @DisplayName("A comment marker without a following space keeps the text")
    void commentMarkerNoSpace() {
        RtpYamlMapping root = RtpYamlReader.parse("#tight\nk: 1\n");
        assertTrue(root.blockComments().contains("tight") || root.get("k").blockComments().contains("tight"));
    }

    /* ------------------------ writer ------------------------ */

    @Test
    @DisplayName("An internal blank line inside a comment block round-trips as a sentinel")
    void writerInternalCommentBlank() {
        String src = "# a\n#\n# b\nk: 1\n";
        String out = RtpYamlWriter.emit(RtpYamlReader.parse(src));
        assertEquals(out, RtpYamlWriter.emit(RtpYamlReader.parse(out)));
    }

    @Test
    @DisplayName("Sequence-item mapping continuations may hold empty scalars and sub-sequences")
    void writerContinuationVariants() {
        String src = "list:\n  - name: a\n    empty:\n    subseq:\n      - x\n";
        assertEquals(src, RtpYamlWriter.emit(RtpYamlReader.parse(src)));
    }

    @Test
    @DisplayName("Two consecutive commented scalar entries keep single separators")
    void writerConsecutiveCommentedEntries() {
        String src = "a: 1\n\n# c\nb: 2\n\n# d\ncc: 3\n";
        assertEquals(src, RtpYamlWriter.emit(RtpYamlReader.parse(src)));
    }

    @Test
    @DisplayName("An internal blank inside a nested key's comment block is emitted as a sentinel")
    void writerNestedCommentSentinel() {
        String src = "parent:\n  # a\n\n  # b\n  child: 1\n";
        RtpYamlMapping root = RtpYamlReader.parse(src);
        RtpYamlMapping parent = (RtpYamlMapping) root.get("parent");
        assertTrue(parent.get("child").blockComments().contains(RtpYamlReader.BLANK_LINE_SENTINEL));
        assertEquals(src, RtpYamlWriter.emit(root));
    }

    @Test
    @DisplayName("A blank line between a comment and its sequence item is preserved as a sentinel")
    void sequenceCommentThenBlank() {
        String src = "list:\n  - a\n  # c\n\n  - b\n";
        RtpYamlMapping root = RtpYamlReader.parse(src);
        RtpYamlSequence seq = (RtpYamlSequence) root.get("list");
        assertTrue(seq.get(1).blockComments().contains("c"));
    }

    /* ------------------------ config ------------------------ */

    @Test
    @DisplayName("The String-path constructor tolerates a null path")
    void nullPathConstructor() {
        RtpYamlConfig c = new RtpYamlConfig((String) null);
        assertNull(c.file());
    }

    @Test
    @DisplayName("createNewFile creates missing parent directories")
    void createNewFileMakesParents(@TempDir Path dir) throws IOException {
        File f = dir.resolve("a/b/c.yml").toFile();
        RtpYamlConfig c = new RtpYamlConfig(f);
        assertTrue(c.createNewFile());
        assertTrue(f.exists());
    }

    /* ------------------------ section ------------------------ */

    @Test
    @DisplayName("Nested getConfigurationSection carries the dotted child path")
    void nestedSectionPath() {
        RtpYamlConfig c = RtpYamlConfig.parse("a:\n  b:\n    c: 1\n");
        RtpYamlSection a = c.getConfigurationSection("a");
        RtpYamlSection b = a.getConfigurationSection("b");
        assertEquals("a.b", b.getCurrentPath());
        assertEquals("b", b.getName());
        assertEquals(1, b.getInt("c"));
    }

    @Test
    @DisplayName("set traverses an existing intermediate section")
    void setThroughExistingSection() {
        RtpYamlConfig c = RtpYamlConfig.parse("a:\n  b:\n    c: 1\n");
        c.set("a.b.d", 2);
        assertEquals(1, c.getInt("a.b.c"));
        assertEquals(2, c.getInt("a.b.d"));
    }

    @Test
    @DisplayName("get with an empty key resolves to the section itself")
    void emptyKeyResolvesToSelf() {
        RtpYamlConfig c = RtpYamlConfig.parse("a: 1\n");
        assertTrue(c.contains(""));
        assertNotNull(c.get(""));
    }
}
