package io.github.dailystruggle.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.System.Logger.Level;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-API-F-009 BIT severities use System.Logger.Level")
class BitLevelsTest {

    @Test
    void reportHealthFollowsHighestLevel() {
        assertEquals(BitHealth.HEALTHY, BitReport.of(List.of(finding(Level.TRACE), finding(Level.DEBUG),
                finding(Level.INFO))).health());
        assertEquals(BitHealth.DEGRADED, BitReport.of(List.of(finding(Level.INFO), finding(Level.WARNING))).health());
        assertEquals(BitHealth.FAILED, BitReport.of(List.of(finding(Level.ERROR), finding(Level.WARNING))).health());
        assertEquals(BitHealth.HEALTHY, BitReport.of(List.of()).health());
    }

    @Test
    void filterLevelsAreNotSeverities() {
        assertThrows(IllegalArgumentException.class, () -> finding(Level.ALL));
        assertThrows(IllegalArgumentException.class, () -> finding(Level.OFF));
        assertThrows(NullPointerException.class, () -> finding(null));
        assertThrows(IllegalArgumentException.class, () -> new BitCode(5000, "X_Y", Level.OFF, BitTier.PBIT,
                "", "", ""));
    }

    @Test
    void bridgesToJulLevels() {
        assertEquals(java.util.logging.Level.SEVERE, BitLevels.toJul(Level.ERROR));
        assertEquals(java.util.logging.Level.WARNING, BitLevels.toJul(Level.WARNING));
        assertEquals(java.util.logging.Level.INFO, BitLevels.toJul(Level.INFO));
        assertEquals(java.util.logging.Level.FINE, BitLevels.toJul(Level.DEBUG));
        assertEquals(java.util.logging.Level.FINER, BitLevels.toJul(Level.TRACE));
        assertThrows(IllegalArgumentException.class, () -> BitLevels.toJul(Level.OFF));
    }

    @Test
    void jsonWritesLevelName() {
        assertEquals(true, BitReport.of(List.of(finding(Level.WARNING))).toJson()
                .contains("\"severity\":\"WARNING\""));
    }

    private static BitFinding finding(Level level) {
        return new BitFinding(level, "TEST_CODE", "src", "a.b", "msg", "", "");
    }
}
