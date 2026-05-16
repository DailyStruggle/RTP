package io.github.dailystruggle.rtp.common.configuration.yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One case per YAML feature rejected by the in-house parser
 * (ADR-025 §Migration step 5). Asserts the {@code messageKey} so the
 * stable identifiers stay stable across refactors.
 */
class RtpYamlUnsupportedFeatureTest {

    private static RtpYamlParseException assertRejected(String src) {
        return assertThrows(RtpYamlParseException.class, () -> RtpYamlReader.parse(src));
    }

    @Test @DisplayName("rejects anchors with rtpYaml.unsupported.anchor")
    void rejectsAnchor() {
        RtpYamlParseException e = assertRejected("a: &anchor 1\n");
        assertEquals("rtpYaml.unsupported.anchor", e.messageKey());
    }

    @Test @DisplayName("rejects aliases with rtpYaml.unsupported.alias")
    void rejectsAlias() {
        RtpYamlParseException e = assertRejected("a: *anchor\n");
        assertEquals("rtpYaml.unsupported.alias", e.messageKey());
    }

    @Test @DisplayName("rejects merge keys with rtpYaml.unsupported.mergeKey")
    void rejectsMergeKey() {
        RtpYamlParseException e = assertRejected("<<: x\n");
        assertEquals("rtpYaml.unsupported.mergeKey", e.messageKey());
    }

    @Test @DisplayName("rejects flow mappings with rtpYaml.unsupported.flowMap")
    void rejectsFlowMap() {
        RtpYamlParseException e = assertRejected("a: {b: 1}\n");
        assertEquals("rtpYaml.unsupported.flowMap", e.messageKey());
    }

    @Test @DisplayName("rejects flow sequences with rtpYaml.unsupported.flowSeq")
    void rejectsFlowSeq() {
        RtpYamlParseException e = assertRejected("a: [1, 2]\n");
        assertEquals("rtpYaml.unsupported.flowSeq", e.messageKey());
    }

    @Test @DisplayName("rejects tags with rtpYaml.unsupported.tag")
    void rejectsTag() {
        RtpYamlParseException e = assertRejected("a: !!str 1\n");
        assertEquals("rtpYaml.unsupported.tag", e.messageKey());
    }

    @Test @DisplayName("rejects document separators with rtpYaml.unsupported.docSep")
    void rejectsDocSep() {
        RtpYamlParseException e = assertRejected("---\na: 1\n");
        assertEquals("rtpYaml.unsupported.docSep", e.messageKey());
    }

    @Test @DisplayName("rejects block scalars with rtpYaml.unsupported.blockScalar")
    void rejectsBlockScalar() {
        RtpYamlParseException e = assertRejected("a: |\n  hello\n");
        assertEquals("rtpYaml.unsupported.blockScalar", e.messageKey());
    }

    @Test @DisplayName("rejects tab-indented lines with rtpYaml.syntax.tabIndent")
    void rejectsTabIndent() {
        RtpYamlParseException e = assertRejected("a:\n\tb: 1\n");
        assertEquals("rtpYaml.syntax.tabIndent", e.messageKey());
    }
}
