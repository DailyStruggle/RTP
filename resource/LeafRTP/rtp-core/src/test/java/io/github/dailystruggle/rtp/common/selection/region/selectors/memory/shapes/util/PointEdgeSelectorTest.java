package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.util;

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.util.PointEdgeSelector.CandidateEstimate;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.util.PointEdgeSelector.Decision;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.util.PointEdgeSelector.OccupancyOracle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.util.PointEdgeSelector.Transition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("REQ-CORE-F-003: PointEdgeSelector Derivation and Estimation")
class PointEdgeSelectorTest {

    @Test
    @DisplayName("derivePFromRadius respects boundaries and non-positive inputs")
    void derivePFromRadius_boundaries() {
        assertEquals(1, PointEdgeSelector.derivePFromRadius(0));
        assertEquals(1, PointEdgeSelector.derivePFromRadius(-100));

        // When radiusChunks is small, maxAllowedP < 1 -> returns 1
        assertEquals(1, PointEdgeSelector.derivePFromRadius(10)); // 20 / 64 = 0 -> 1
        assertEquals(1, PointEdgeSelector.derivePFromRadius(31)); // 62 / 64 = 0 -> 1

        // (2 * 32) / 64 = 1 -> returns 1
        assertEquals(1, PointEdgeSelector.derivePFromRadius(32));

        // (2 * 64) / 64 = 2 -> candidate 2
        assertEquals(2, PointEdgeSelector.derivePFromRadius(64));

        // (2 * 128) / 64 = 4 -> candidate 4
        assertEquals(4, PointEdgeSelector.derivePFromRadius(128));

        // (2 * 1024) / 64 = 32 -> capped at DEFAULT_P (32) even if 64 or 128 fit
        assertEquals(32, PointEdgeSelector.derivePFromRadius(1024));
        assertEquals(32, PointEdgeSelector.derivePFromRadius(100000L));
    }

    @ParameterizedTest
    @CsvSource({
            "0, 1, false",
            "-10, 2, false",
            "100, 0, false",
            "100, -1, false",
            "31, 1, false",   // (2 * 31) / 1 = 62 < 64
            "32, 1, true",    // (2 * 32) / 1 = 64 >= 64
            "64, 2, true",    // 128 / 2 = 64 >= 64
            "64, 4, false",   // 128 / 4 = 32 < 64
            "1024, 32, true", // 2048 / 32 = 64 >= 64
            "1024, 64, false" // 2048 / 64 = 32 < 64
    })
    @DisplayName("isAdmissible checks minimum cells per edge")
    void isAdmissible_checks(long radiusChunks, int p, boolean expected) {
        assertEquals(expected, PointEdgeSelector.isAdmissible(radiusChunks, p));
    }

    @Test
    @DisplayName("decideTransition handles identical, multiple, and incompatible changes")
    void decideTransition_rules() {
        Transition t1 = PointEdgeSelector.decideTransition(16, 16);
        assertTrue(t1.losslessRatchet());
        assertEquals(16, t1.p());
        assertTrue(t1.reason().contains("P unchanged"));

        // Lossless upward ratchet: targetP > storedP and targetP % storedP == 0
        Transition t2 = PointEdgeSelector.decideTransition(8, 32);
        assertTrue(t2.losslessRatchet());
        assertEquals(32, t2.p());
        assertTrue(t2.reason().contains("Lossless upward ratchet"));

        // Incompatible: targetP < storedP
        Transition t3 = PointEdgeSelector.decideTransition(32, 16);
        assertFalse(t3.losslessRatchet());
        assertEquals(16, t3.p());
        assertTrue(t3.reason().contains("Incompatible"));

        // Incompatible: not a clean multiple
        Transition t4 = PointEdgeSelector.decideTransition(6, 16);
        assertFalse(t4.losslessRatchet());
        assertEquals(16, t4.p());
        assertTrue(t4.reason().contains("Incompatible"));

        // Helper transition(int, Decision)
        Decision d = new Decision(16, 5.0, "reason");
        Transition t5 = PointEdgeSelector.transition(16, d);
        assertTrue(t5.losslessRatchet());
        assertEquals(16, t5.p());
    }

    @Test
    @DisplayName("estimate and decide with all-good or all-bad oracle")
    void estimate_and_decide_oracles() {
        OccupancyOracle allGood = (cx, cz) -> false;
        List<CandidateEstimate> goodEstimates = PointEdgeSelector.estimate(allGood, 2048, 4, 12345L);
        assertNotNull(goodEstimates);
        assertEquals(PointEdgeSelector.CANDIDATES.length, goodEstimates.size());

        for (CandidateEstimate est : goodEstimates) {
            assertEquals(0.0, est.runsMean(), 1e-6);
            assertEquals(0.0, est.goodLossMean(), 1e-6);
            assertEquals(4, est.blocksEvaluated());
        }

        Decision decGood = PointEdgeSelector.decide(goodEstimates, 0.05);
        assertNotNull(decGood);
        assertTrue(decGood.chosenP() >= 1);
        assertTrue(decGood.reason().contains("Selected P="));

        // Test with radiusChunks decision
        Decision decGoodRadius = PointEdgeSelector.decide(goodEstimates, 2048);
        assertNotNull(decGoodRadius);
        assertTrue(decGoodRadius.chosenP() >= 1);

        // All bad oracle
        OccupancyOracle allBad = (cx, cz) -> true;
        List<CandidateEstimate> badEstimates = PointEdgeSelector.estimate(allBad, 2048, 4, 12345L);
        assertNotNull(badEstimates);
        for (CandidateEstimate est : badEstimates) {
            assertTrue(est.runsMean() >= 1.0);
            assertEquals(0.0, est.goodLossMean(), 1e-6); // totalGood is 0 so meanLoss is 0.0
        }
    }

    @Test
    @DisplayName("estimate with checkerboard pattern produces runs and gaps")
    void estimate_checkerboard() {
        OccupancyOracle checkerboard = (cx, cz) -> ((cx + cz) & 1) == 0;
        List<CandidateEstimate> estimates = PointEdgeSelector.estimate(checkerboard, 1024, 6, 99999L);
        assertFalse(estimates.isEmpty());

        for (CandidateEstimate est : estimates) {
            assertTrue(est.runsMean() >= 0.0);
            assertTrue(est.runsUpper() >= est.runsMean());
            assertTrue(est.goodLossMean() >= 0.0);
            assertTrue(est.goodLossUpper() >= est.goodLossMean());
        }
    }

    @Test
    @DisplayName("decide fallbacks when budget exceeded or domain too small")
    void decide_fallbacks() {
        // Case 1: Inadmissible estimates (domain too small)
        List<CandidateEstimate> inadmissible = new ArrayList<>();
        for (int p : PointEdgeSelector.CANDIDATES) {
            inadmissible.add(new CandidateEstimate(p, false, 10.0, 12.0, 0.1, 0.2, 5));
        }

        Decision dSmall = PointEdgeSelector.decide(inadmissible, 0.5);
        assertEquals(1, dSmall.chosenP());
        assertTrue(dSmall.reason().contains("Domain too small"));

        Decision dSmallRadius = PointEdgeSelector.decide(inadmissible, 10);
        assertEquals(1, dSmallRadius.chosenP());
        assertTrue(dSmallRadius.reason().contains("fallback to P=1"));

        // Case 2: Admissible but budget exceeded
        List<CandidateEstimate> highLoss = new ArrayList<>();
        highLoss.add(new CandidateEstimate(1, true, 10.0, 12.0, 0.5, 0.6, 5));
        highLoss.add(new CandidateEstimate(2, true, 8.0, 9.0, 0.4, 0.5, 5));

        Decision dFallback = PointEdgeSelector.decide(highLoss, 0.01); // maxGoodLossFraction = 1%
        assertEquals(1, dFallback.chosenP()); // picks minimal admissible P=1
        assertTrue(dFallback.reason().contains("Fallback to minimal admissible P=1"));

        // Case 3: Radius overload where cells per edge check fails
        List<CandidateEstimate> mixedAdmissible = new ArrayList<>();
        mixedAdmissible.add(new CandidateEstimate(32, true, 5.0, 6.0, 0.0, 0.0, 5));
        // with radiusChunks = 32, (2 * 32) / 32 = 2 < 64, so it should reject and fallback to 1
        Decision dRadiusFail = PointEdgeSelector.decide(mixedAdmissible, 32);
        assertEquals(1, dRadiusFail.chosenP());
        assertTrue(dRadiusFail.reason().contains("fallback to P=1"));
    }
}
