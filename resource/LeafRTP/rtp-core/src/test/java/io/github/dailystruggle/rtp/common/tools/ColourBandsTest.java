package io.github.dailystruggle.rtp.common.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link ColourBands} health colour coding thresholds.
 * Exercises {@link ColourBands#resolveDouble} fallback path without active config.
 */
@DisplayName("/rtp info colour-band thresholds resolve safely with no config loaded")
class ColourBandsTest {

    @Test
    @DisplayName("TPS bands honour the documented defaults (>= 19.5 green, >= 18 yellow, else red)")
    void tpsBandsDefaults() {
        assertEquals(ColourBands.GREEN, ColourBands.forTps(20.0));
        assertEquals(ColourBands.GREEN, ColourBands.forTps(19.5));
        assertEquals(ColourBands.YELLOW, ColourBands.forTps(19.49));
        assertEquals(ColourBands.YELLOW, ColourBands.forTps(18.0));
        assertEquals(ColourBands.RED, ColourBands.forTps(17.99));
        assertEquals(ColourBands.RED, ColourBands.forTps(0.0));
        assertEquals(ColourBands.UNKNOWN, ColourBands.forTps(Double.NaN));
    }

    @Test
    @DisplayName("MSPT bands honour the documented defaults (<= 30 green, <= 45 yellow, else red)")
    void msptBandsDefaults() {
        assertEquals(ColourBands.GREEN, ColourBands.forMspt(5.0));
        assertEquals(ColourBands.GREEN, ColourBands.forMspt(30.0));
        assertEquals(ColourBands.YELLOW, ColourBands.forMspt(30.01));
        assertEquals(ColourBands.YELLOW, ColourBands.forMspt(45.0));
        assertEquals(ColourBands.RED, ColourBands.forMspt(45.01));
        assertEquals(ColourBands.RED, ColourBands.forMspt(100.0));
        assertEquals(ColourBands.UNKNOWN, ColourBands.forMspt(Double.NaN));
    }

    @Test
    @DisplayName("Tick-budget utilisation rides the MSPT bands via the 50ms tick budget")
    void tickBudgetUtilisationBandsDefaults() {
        // 0.10 * 50 = 5ms -> green
        assertEquals(ColourBands.GREEN, ColourBands.forTickBudgetUtilisation(0.10));
        // 0.60 * 50 = 30ms -> green (boundary)
        assertEquals(ColourBands.GREEN, ColourBands.forTickBudgetUtilisation(0.60));
        // 0.61 * 50 = 30.5ms -> yellow
        assertEquals(ColourBands.YELLOW, ColourBands.forTickBudgetUtilisation(0.61));
        // 0.90 * 50 = 45ms -> yellow (boundary)
        assertEquals(ColourBands.YELLOW, ColourBands.forTickBudgetUtilisation(0.90));
        // 0.95 * 50 = 47.5ms -> red
        assertEquals(ColourBands.RED, ColourBands.forTickBudgetUtilisation(0.95));
        assertEquals(ColourBands.UNKNOWN, ColourBands.forTickBudgetUtilisation(Double.NaN));
    }

    @Test
    @DisplayName("Cache-fill bands honour the documented defaults (>= 0.50 green, >= 0.25 yellow, else red)")
    void cacheFillBandsDefaults() {
        assertEquals(ColourBands.GREEN, ColourBands.forCacheFill(1.0));
        assertEquals(ColourBands.GREEN, ColourBands.forCacheFill(0.50));
        assertEquals(ColourBands.YELLOW, ColourBands.forCacheFill(0.49));
        assertEquals(ColourBands.YELLOW, ColourBands.forCacheFill(0.25));
        assertEquals(ColourBands.RED, ColourBands.forCacheFill(0.24));
        assertEquals(ColourBands.RED, ColourBands.forCacheFill(0.0));
        assertEquals(ColourBands.UNKNOWN, ColourBands.forCacheFill(Double.NaN));
    }

    @Test
    @DisplayName("Network-age band is two-state (green when fresh, yellow when stale)")
    void networkAgeBandsDefaults() {
        assertEquals(ColourBands.GREEN, ColourBands.forNetworkAge(0.0));
        assertEquals(ColourBands.GREEN, ColourBands.forNetworkAge(4.99));
        assertEquals(ColourBands.YELLOW, ColourBands.forNetworkAge(5.0));
        assertEquals(ColourBands.YELLOW, ColourBands.forNetworkAge(60.0));
        assertEquals(ColourBands.UNKNOWN, ColourBands.forNetworkAge(Double.NaN));
    }

    @Test
    @DisplayName("parseDouble accepts numbers and numeric strings; everything else falls back")
    void parseDoubleFallbacks() {
        double fallback = 999.0;
        assertEquals(19.5, ColourBands.parseDouble(19.5, fallback));
        assertEquals(20.0, ColourBands.parseDouble(20, fallback));
        assertEquals(20.0, ColourBands.parseDouble(20L, fallback));
        assertEquals(18.5, ColourBands.parseDouble("18.5", fallback));
        assertEquals(18.5, ColourBands.parseDouble("  18.5  ", fallback));
        // Non-numeric / NaN / Infinity / wrong types all fall back without throwing.
        assertEquals(fallback, ColourBands.parseDouble(null, fallback));
        assertEquals(fallback, ColourBands.parseDouble("", fallback));
        assertEquals(fallback, ColourBands.parseDouble("not-a-number", fallback));
        assertEquals(fallback, ColourBands.parseDouble("NaN", fallback));
        assertEquals(fallback, ColourBands.parseDouble("Infinity", fallback));
        assertEquals(fallback, ColourBands.parseDouble(Boolean.TRUE, fallback));
    }

    @Test
    @DisplayName("resolveDouble returns the fallback when no RTP / ConfigParser is initialised")
    void resolveDoubleFallsBackWithoutConfig() {
        // No RTP instance is constructed by this test, so the resolver must take the
        // null-guard path and return the documented default rather than throwing.
        assertEquals(
                ColourBands.DEFAULT_TPS_GREEN,
                ColourBands.resolveDouble(
                        io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages
                                .infoThresholdTpsGreen,
                        ColourBands.DEFAULT_TPS_GREEN));
    }
}
