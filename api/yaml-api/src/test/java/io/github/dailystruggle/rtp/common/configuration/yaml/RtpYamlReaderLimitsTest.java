package io.github.dailystruggle.rtp.common.configuration.yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RtpYamlReader size/depth limits and flow-sequence rejections (ADR-025 subset)")
class RtpYamlReaderLimitsTest {

    private static String expectKey(String src) {
        RtpYamlParseException e = assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse(src));
        return e.messageKey();
    }

    /** Keys {@code k0..k(n-1)}, each nested one level (two spaces) deeper, each with no inline value. */
    private static StringBuilder nestedKeys(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(" ".repeat(2 * i)).append('k').append(i).append(":\n");
        }
        return sb;
    }

    @Test
    @DisplayName("Source longer than MAX_SOURCE_BYTES is rejected before lexing")
    void oversizedSource() {
        String big = "a".repeat(RtpYamlReader.MAX_SOURCE_BYTES + 1);
        assertEquals("rtpYaml.syntax.maxSize", expectKey(big));
    }

    @Test
    @DisplayName("Source exactly MAX_SOURCE_BYTES long passes the size guard")
    void sourceAtLimit() {
        String atLimit = "a: " + "b".repeat(RtpYamlReader.MAX_SOURCE_BYTES - 3);
        RtpYamlMapping root = RtpYamlReader.parse(atLimit);
        assertTrue(root.containsKey("a"));
    }

    @Test
    @DisplayName("Child block beyond MAX_DEPTH at end of input is rejected")
    void childBlockDepthAtEof() {
        StringBuilder sb = nestedKeys(65);
        sb.setLength(sb.length() - 1); // no trailing newline: the deepest key is the last line
        assertEquals("rtpYaml.syntax.maxDepth", expectKey(sb.toString()));
    }

    @Test
    @DisplayName("Sequence-item continuation mapping beyond MAX_DEPTH is rejected")
    void mappingBodyDepth() {
        StringBuilder sb = nestedKeys(64);
        sb.append(" ".repeat(128)).append("- a: 1\n");
        sb.append(" ".repeat(130)).append("b: 2\n");
        assertEquals("rtpYaml.syntax.maxDepth", expectKey(sb.toString()));
    }

    @Test
    @DisplayName("Sequence-item continuation mapping beyond MAX_DEPTH at end of input is rejected")
    void mappingBodyDepthAtEof() {
        StringBuilder sb = nestedKeys(64);
        sb.append(" ".repeat(128)).append("- a: 1");
        assertEquals("rtpYaml.syntax.maxDepth", expectKey(sb.toString()));
    }

    @Test
    @DisplayName("Block sequence beyond MAX_DEPTH is rejected")
    void sequenceBodyDepth() {
        StringBuilder sb = nestedKeys(63);
        sb.append(" ".repeat(126)).append("- a: 1\n");
        sb.append(" ".repeat(128)).append("b:\n");
        sb.append(" ".repeat(130)).append("- x\n");
        assertEquals("rtpYaml.syntax.maxDepth", expectKey(sb.toString()));
    }

    @Test
    @DisplayName("Aliases, tags and block scalars inside a flow sequence are rejected")
    void flowItemIndicators() {
        assertEquals("rtpYaml.unsupported.alias", expectKey("k: [*a]\n"));
        assertEquals("rtpYaml.unsupported.tag", expectKey("k: [!t]\n"));
        assertEquals("rtpYaml.unsupported.blockScalar", expectKey("k: [>x]\n"));
        assertEquals("rtpYaml.unsupported.blockScalar", expectKey("k: [|x]\n"));
    }

    @Test
    @DisplayName("Braces inside a plain flow entry are rejected")
    void flowPlainBraces() {
        assertEquals("rtpYaml.syntax.flowSeq", expectKey("k: [a{b]\n"));
        assertEquals("rtpYaml.syntax.flowSeq", expectKey("k: [a}b]\n"));
        assertEquals("rtpYaml.syntax.flowSeq", expectKey("k: [a[b]\n"));
    }

    @Test
    @DisplayName("A ':' ending a flow entry signals an unsupported flow mapping")
    void flowPlainColon() {
        assertEquals("rtpYaml.unsupported.flowMap", expectKey("k: [a:]\n"));
        assertEquals("rtpYaml.unsupported.flowMap", expectKey("k: [a:, b]\n"));
        assertEquals("rtpYaml.unsupported.flowMap", expectKey("k: [a: b]\n"));
        assertEquals("rtpYaml.unsupported.flowMap", expectKey("k: [a:"));
        RtpYamlSequence ok = (RtpYamlSequence) RtpYamlReader.parse("k: [a:b, c]\n").get("k");
        assertEquals("a:b", ((RtpYamlScalar) ok.get(0)).rawValue());
    }

    @Test
    @DisplayName("Unclosed flow sequences are reported as unterminated")
    void flowUnterminated() {
        assertEquals("rtpYaml.syntax.unterminatedFlowSeq", expectKey("k: ["));
        assertEquals("rtpYaml.syntax.unterminatedFlowSeq", expectKey("k: [a,"));
        assertEquals("rtpYaml.syntax.unterminatedFlowSeq", expectKey("k: ['ab'"));
    }

    @Test
    @DisplayName("Quoted flow entries honour escapes and doubled single quotes")
    void flowQuotedEntries() {
        RtpYamlSequence seq = (RtpYamlSequence) RtpYamlReader.parse(
                "k: [\"a\\\"b\", 'it''s', 'x']\n").get("k");
        assertEquals(3, seq.size());
        assertEquals("a\"b", ((RtpYamlScalar) seq.get(0)).rawValue());
        assertEquals("it's", ((RtpYamlScalar) seq.get(1)).rawValue());
        assertEquals("x", ((RtpYamlScalar) seq.get(2)).rawValue());
    }

    @Test
    @DisplayName("Tabs between flow entries are skipped as whitespace")
    void flowTabWhitespace() {
        RtpYamlSequence seq = (RtpYamlSequence) RtpYamlReader.parse("k: [a,\tb]\n").get("k");
        assertEquals(2, seq.size());
        assertEquals("b", ((RtpYamlScalar) seq.get(1)).rawValue());
    }
}
