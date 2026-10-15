package io.github.dailystruggle.rtp.common.predict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("REQ-PRED-F-002: BernoulliEstimator Binary Outcome Probability Tracking")
class BernoulliEstimatorTest {

    @Test
    @DisplayName("Cold-start: First outcome initializes rate exactly to 1.0 or 0.0")
    void coldStart_firstOutcome() {
        BernoulliEstimator estTrue = BernoulliEstimator.ofWindow(32);
        assertEquals(0.0, estTrue.rate());
        assertEquals(0L, estTrue.sampleCount());

        estTrue.record(true);
        assertEquals(1.0, estTrue.rate(), 1e-9);
        assertEquals(1L, estTrue.sampleCount());

        BernoulliEstimator estFalse = BernoulliEstimator.ofWindow(32);
        estFalse.record(false);
        assertEquals(0.0, estFalse.rate(), 1e-9);
        assertEquals(1L, estFalse.sampleCount());
    }

    @Test
    @DisplayName("Convergence: Alternating outcomes converge to 0.5")
    void alternatingOutcomes_convergeToHalf() {
        BernoulliEstimator estimator = BernoulliEstimator.ofWindow(64);
        for (int i = 0; i < 200; i++) {
            estimator.record(i % 2 == 0);
        }

        assertEquals(0.5, estimator.rate(), 0.05);
        assertEquals(200L, estimator.sampleCount());
    }

    @Test
    @DisplayName("Initialized rate: Uses prior rate without early unbiasing")
    void initializedRate_respectsPrior() {
        BernoulliEstimator estimator = BernoulliEstimator.ofWindow(64, 0.20);
        assertEquals(0.20, estimator.rate(), 1e-9);
        assertEquals(0L, estimator.sampleCount());

        estimator.record(true);
        assertEquals(1L, estimator.sampleCount());
        // alpha = 2 / 65 ~ 0.030769
        // rate = 0.20 + (2/65) * (1.0 - 0.20) = 0.20 + 0.024615 = 0.224615
        double expected = 0.20 + (2.0 / 65.0) * (1.0 - 0.20);
        assertEquals(expected, estimator.rate(), 1e-6);
    }

    @Test
    @DisplayName("Bounds: Rate stays strictly clamped within [0.0, 1.0]")
    void rateStaysWithinBounds() {
        BernoulliEstimator estimator = BernoulliEstimator.ofWindow(8, 0.99);
        for (int i = 0; i < 50; i++) {
            estimator.record(true);
        }
        assertTrue(estimator.rate() <= 1.0);
        assertTrue(estimator.rate() >= 0.99);

        for (int i = 0; i < 100; i++) {
            estimator.record(false);
        }
        assertTrue(estimator.rate() >= 0.0);
        assertTrue(estimator.rate() < 0.05);
    }
}
