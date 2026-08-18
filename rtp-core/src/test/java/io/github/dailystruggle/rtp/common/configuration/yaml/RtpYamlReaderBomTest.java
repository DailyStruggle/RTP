package io.github.dailystruggle.rtp.common.configuration.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests UTF-8 BOM (U+FEFF) handling in {@link RtpYamlReader#parse(String)}.
 *
 * <p>Verifies BOM stripping at position 0 without corrupting mid-document characters.
 */
class RtpYamlReaderBomTest {

    private static final String BOM = "\uFEFF";

    @Test
    @DisplayName("BOM at position 0 is stripped before lexing (top-level mapping parses cleanly)")
    void parse_bomAtPositionZero_doesNotReject() {
        String src = BOM + "network:\n  enabled: true\nrouting:\n  lobbyMode: true\n";
        RtpYamlMapping root = RtpYamlReader.parse(src);
        assertNotNull(root, "root mapping");
        RtpYamlMapping network = (RtpYamlMapping) root.entries().get("network");
        assertNotNull(network, "network section");
        RtpYamlScalar enabled = (RtpYamlScalar) network.entries().get("enabled");
        assertEquals("true", enabled.rawValue());
        RtpYamlMapping routing = (RtpYamlMapping) root.entries().get("routing");
        assertNotNull(routing, "routing section");
        RtpYamlScalar lobbyMode = (RtpYamlScalar) routing.entries().get("lobbyMode");
        assertEquals("true", lobbyMode.rawValue());
    }

    @Test
    @DisplayName("BOM followed by leading comment then mapping parses correctly")
    void parse_bomThenCommentThenMapping_parses() {
        String src = BOM + "# Devstack lobby network.yml.\nnetwork:\n  enabled: true\n";
        RtpYamlMapping root = RtpYamlReader.parse(src);
        RtpYamlMapping network = (RtpYamlMapping) root.entries().get("network");
        assertNotNull(network);
        assertEquals("true", ((RtpYamlScalar) network.entries().get("enabled")).rawValue());
    }

    @Test
    @DisplayName("No BOM: existing behaviour unchanged")
    void parse_noBom_unchanged() {
        String src = "network:\n  enabled: true\n";
        RtpYamlMapping root = RtpYamlReader.parse(src);
        RtpYamlMapping network = (RtpYamlMapping) root.entries().get("network");
        assertEquals("true", ((RtpYamlScalar) network.entries().get("enabled")).rawValue());
    }

    @Test
    @DisplayName("Mid-document U+FEFF is NOT stripped (only position 0 is a BOM)")
    void parse_feffMidDocument_notStripped() {
        // A U+FEFF inside a quoted value should round-trip as data, not get eaten by the BOM
        // guard. The guard is position-0-only by design.
        String src = "network:\n  serverId: \"hello\uFEFFworld\"\n";
        RtpYamlMapping root = RtpYamlReader.parse(src);
        RtpYamlMapping network = (RtpYamlMapping) root.entries().get("network");
        RtpYamlScalar id = (RtpYamlScalar) network.entries().get("serverId");
        assertTrue(String.valueOf(id.value()).contains("\uFEFF"),
                "mid-document U+FEFF must survive parse");
    }

    @Test
    @DisplayName("Empty BOM-only source: no NPE, empty root mapping")
    void parse_bomOnly_emptyMapping() {
        RtpYamlMapping root = RtpYamlReader.parse(BOM);
        assertNotNull(root);
        assertTrue(root.entries().isEmpty(), "BOM-only input produces empty mapping");
    }
}
