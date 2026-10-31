package io.github.dailystruggle.rtp.common.predict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("REQ-PRED-F-006: EwmaLinearFit Online Weighted Linear Regression for Amortised Batch Costs")
class EwmaLinearFitTest {

    @Test
    @DisplayName("Validation: Constructor rejects invalid windowSize, minSamples, or minXVariance")
    void constructor_validatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> new EwmaLinearFit(0, 8, 1e-4, true));
        assertThrows(IllegalArgumentException.class, () -> new EwmaLinearFit(32, 0, 1e-4, true));
        assertThrows(IllegalArgumentException.class, () -> new EwmaLinearFit(32, 8, -1e-4, true));
        assertThrows(IllegalArgumentException.class, () -> new EwmaLinearFit(32, 8, Double.NaN, true));
    }

    @Test
    @DisplayName("Cold-start & Unidentified Fallback: Falls back to meanY until minSamples and minXVariance are met")
    void coldStart_andUnidentifiedFallback() {
        EwmaLinearFit fit = EwmaLinearFit.ofCoalescedCost(32, 8, 1e-4);
        assertEquals(0L, fit.sampleCount());
        assertFalse(fit.isIdentified());
        assertEquals(0.0, fit.slope(), 1e-9);
        assertTrue(Double.isNaN(fit.predict(1.0)));
        assertEquals(0.0, fit.standardErrorSquaredAt(1.0), 1e-9);

        // Ignore NaN and Infinite inputs
        fit.record(Double.NaN, 10.0);
        fit.record(1.0, Double.POSITIVE_INFINITY);
        assertEquals(0L, fit.sampleCount());

        // Record 10 samples at constant x = 0.5 -> varX == 0 -> not identified
        for (int i = 0; i < 10; i++) {
            fit.record(0.5, 12.0);
        }
        assertEquals(10L, fit.sampleCount());
        assertFalse(fit.isIdentified());
        assertEquals(0.0, fit.slope(), 1e-9);
        assertEquals(12.0, fit.predict(1.0), 1e-6);
        assertEquals(12.0, fit.predict(0.1), 1e-6);
    }

    @Test
    @DisplayName("Coalesced Batch Fit: Recovers per-item intercept and shared open cost from T(g) = perUnit + open / g")
    void coalescedCost_identifiesSlopeAndPredictions() {
        EwmaLinearFit fit = EwmaLinearFit.ofCoalescedCost(64, 8, 1e-4);
        double truePerItem = 2.0;
        double trueOpen = 10.0;
        int[] batchSizes = {1, 2, 4, 8, 16};

        for (int round = 0; round < 40; round++) {
            int g = batchSizes[round % batchSizes.length];
            double x = 1.0 / g;
            double y = truePerItem + trueOpen * x;
            fit.record(x, y);
        }

        assertTrue(fit.isIdentified(), "Fit must be identified across varied batch sizes");
        assertEquals(trueOpen, fit.slope(), 0.25);
        assertEquals(truePerItem + trueOpen, fit.predict(1.0), 0.25); // g = 1 -> 12.0
        assertEquals(truePerItem + trueOpen / 8.0, fit.predict(0.125), 0.25); // g = 8 -> 3.25
        assertEquals(truePerItem, fit.predict(0.0), 0.25); // asymptotic per-item cost -> 2.0
    }

    @Test
    @DisplayName("Slope Sign Constraint: Clamps negative slope when nonNegativeSlope is enabled")
    void nonNegativeSlope_clampsNegativeCorrelation() {
        EwmaLinearFit clampedFit = EwmaLinearFit.ofCoalescedCost(32, 4, 1e-4);
        EwmaLinearFit unclampedFit = new EwmaLinearFit(32, 4, 1e-4, false);

        for (int i = 0; i < 20; i++) {
            double x = (i % 2 == 0) ? 0.1 : 1.0;
            double y = 20.0 - 10.0 * x; // negative slope -10.0
            clampedFit.record(x, y);
            unclampedFit.record(x, y);
        }

        assertTrue(clampedFit.isIdentified());
        assertEquals(0.0, clampedFit.slope(), 1e-9, "Coalesced cost fit must clamp negative slope to 0.0");

        assertTrue(unclampedFit.isIdentified());
        assertEquals(-10.0, unclampedFit.slope(), 0.5);
        assertEquals(0.0, unclampedFit.predict(10.0), 1e-9, "Predictions must remain non-negative");
    }

    @Test
    @DisplayName("Standard Error: Extrapolation further from meanX increases standardErrorSquaredAt")
    void standardErrorSquaredAt_increasesWithExtrapolationDistance() {
        EwmaLinearFit fit = EwmaLinearFit.ofCoalescedCost(64, 6, 1e-4);
        for (int i = 0; i < 30; i++) {
            double x = (i % 3 == 0) ? 0.25 : ((i % 3 == 1) ? 0.5 : 1.0);
            double noise = (i % 2 == 0) ? 0.5 : -0.5;
            double y = 3.0 + 8.0 * x + noise;
            fit.record(x, y);
        }

        assertTrue(fit.isIdentified());
        double seAtMean = fit.standardErrorSquaredAt(fit.meanX());
        double seFar = fit.standardErrorSquaredAt(fit.meanX() + 2.0);
        assertTrue(seAtMean > 0.0);
        assertTrue(seFar > seAtMean, "Extrapolating away from meanX must increase standard error squared");
    }

    @Test
    @DisplayName("WindowedOlsSlope: Enforces exclusion window, minimum sample count, relative X range, and slope clamping")
    void windowedOlsSlope_enforcesWindowExclusionRangeAndClamping() {
        long sec = 1_000_000_000L;
        double prior = 256.0 * 1024.0;
        EwmaLinearFit.WindowedOlsSlope ols = new EwmaLinearFit.WindowedOlsSlope(
                600 * sec, 60 * sec, 20, 0.25, prior, prior, 4.0 * prior);

        assertEquals(prior, ols.estimate(), 1e-6);
        assertEquals(prior, ols.priorSlope(), 1e-6);

        // Exclusion window ignores samples within 60 s of event
        ols.recordExclusionEvent(10 * sec);
        for (int i = 0; i < 25; i++) {
            double est = ols.recordSample(10 + i * 5, (10 + i * 5) * prior * 2.0, (20 + i) * sec);
            assertEquals(prior, est, 1e-6);
        }
        assertEquals(0, ols.windowSampleCount());

        // Unvaried X (< 25% range) retains prior
        for (int i = 0; i < 25; i++) {
            double est = ols.recordSample(100, 100 * prior * 2.5, (100 + i) * sec);
            assertEquals(prior, est, 1e-6);
        }

        // Identified linear slope 2.5 * prior within [prior, 4 * prior]
        ols.reset();
        for (int i = 0; i < 25; i++) {
            int tickets = 20 + i * 10;
            double bytes = 50_000_000.0 + tickets * (2.5 * prior);
            ols.recordSample(tickets, bytes, i * sec);
        }
        assertEquals(2.5 * prior, ols.estimate(), 1e-3);

        // Steep slope clamps to maxSlope (4 * prior)
        ols.reset();
        for (int i = 0; i < 25; i++) {
            int tickets = 20 + i * 10;
            ols.recordSample(tickets, tickets * (10.0 * prior), i * sec);
        }
        assertEquals(4.0 * prior, ols.estimate(), 1e-6);

        // High-frequency burst within windowNanos is capped at MAX_WINDOW_SAMPLES (256)
        ols.reset();
        for (int i = 0; i < 1_000; i++) {
            int tickets = 20 + (i % 50) * 5;
            ols.recordSample(tickets, tickets * (2.0 * prior), i * 1_000_000L);
        }
        assertEquals(EwmaLinearFit.WindowedOlsSlope.MAX_WINDOW_SAMPLES, ols.windowSampleCount(),
                "WindowedOlsSlope must cap retained samples at MAX_WINDOW_SAMPLES under burst loads");
    }
}
