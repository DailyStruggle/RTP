package io.github.dailystruggle.rtp.neoforge.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the {@code RTPServerAccessor#getServerIntVersion()} contract on the
 * NeoForge adapter: the headline number is the minor for legacy
 * {@code "1.<minor>.<patch>"} strings and the major for Mojang's 26.x scheme.
 * Regression guard - the previous impl parsed index 1 unconditionally and
 * reported 1 on MC 26.1.x, failing addon compatibility gates closed.
 */
@DisplayName("REQ-RTP-S-006 - NeoForge getServerIntVersion() reports the headline MC version")
class NeoForgeServerIntVersionTest {

    @Test
    @DisplayName("legacy 1.x strings resolve to the minor")
    void legacyStringsUseMinor() {
        assertEquals(21, NeoForgeServerAccessor.parseIntVersion("1.21.4"));
        assertEquals(20, NeoForgeServerAccessor.parseIntVersion("1.20.1"));
        assertEquals(16, NeoForgeServerAccessor.parseIntVersion("1.16.5"));
    }

    @Test
    @DisplayName("26.x strings resolve to the major")
    void modernStringsUseMajor() {
        assertEquals(26, NeoForgeServerAccessor.parseIntVersion("26.1.2"));
        assertEquals(26, NeoForgeServerAccessor.parseIntVersion("26.2"));
    }

    @Test
    @DisplayName("pre-release suffixes parse as their numeric component")
    void preReleaseSuffixesTolerated() {
        assertEquals(26, NeoForgeServerAccessor.parseIntVersion("26.2-rc-2"));
        assertEquals(21, NeoForgeServerAccessor.parseIntVersion("1.21-pre1"));
    }

    @Test
    @DisplayName("unparsable versions fall back to the unknown sentinel")
    void unknownFallsBackToZero() {
        assertEquals(0, NeoForgeServerAccessor.parseIntVersion("unknown"));
        assertEquals(0, NeoForgeServerAccessor.parseIntVersion(""));
        assertEquals(0, NeoForgeServerAccessor.parseIntVersion(null));
    }
}
