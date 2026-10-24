package io.github.dailystruggle.rtp.common.predict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ComparativeCostGovernor} and {@link CostContext} (REQ-PRED-F-004, ADR-109, ADR-114).
 */
@DisplayName("REQ-PRED-F-004: ComparativeCostGovernor & CostContext")
class ComparativeCostGovernorTest {

    enum TestBranch {
        PREFILTER,
        BYPASS
    }

    @Test
    @DisplayName("Context builder stores and retrieves metrics with fallback defaults")
    void contextBuilderAndFallbacks() {
        CostContext ctx = CostContext.builder()
                .with("mspt", 24.5)
                .with("pReject", 0.15)
                .build();

        assertTrue(ctx.contains("mspt"));
        assertTrue(ctx.has("mspt"));
        assertEquals(24.5, ctx.get("mspt"), 1e-6);
        assertEquals(0.15, ctx.get("pReject"), 1e-6);
        assertFalse(ctx.contains("missingKey"));
        assertTrue(Double.isNaN(ctx.get("missingKey")));
        assertEquals(10.0, ctx.get("missingKey", 10.0), 1e-6);
    }

    @Test
    @DisplayName("Governor evaluates comparative cost threshold policy accurately")
    void governorEvaluatesPolicy() {
        ComparativeCostGovernor<TestBranch> gov = ComparativeCostGovernor.of(ctx -> {
            if (ctx.get("mspt", 20.0) > 35.0) return TestBranch.PREFILTER;
            double pReject = ctx.get("pReject", 0.0);
            double cFile = ctx.get("cFile", 2.5);
            double cNative = ctx.get("cNative", 15.0);
            double cSync = ctx.get("cSync", 1.0);
            double wTick = ctx.get("wTick", 5.0);
            double threshold = cFile / (cNative + wTick * cSync);
            return pReject > threshold ? TestBranch.PREFILTER : TestBranch.BYPASS;
        });

        CostContext safeContext = CostContext.builder()
                .with("mspt", 20.0)
                .with("pReject", 0.05)
                .with("cFile", 2.5)
                .with("cNative", 15.0)
                .with("cSync", 1.0)
                .with("wTick", 5.0)
                .build();
        assertEquals(TestBranch.BYPASS, gov.evaluate(safeContext));

        CostContext hazardContext = CostContext.builder()
                .with("mspt", 20.0)
                .with("pReject", 0.30)
                .with("cFile", 2.5)
                .with("cNative", 15.0)
                .with("cSync", 1.0)
                .with("wTick", 5.0)
                .build();
        assertEquals(TestBranch.PREFILTER, gov.evaluate(hazardContext));

        CostContext stressedContext = CostContext.builder()
                .with("mspt", 40.0)
                .with("pReject", 0.01)
                .build();
        assertEquals(TestBranch.PREFILTER, gov.evaluate(stressedContext));
    }

    @Test
    @DisplayName("Null policy or context throws IllegalArgumentException")
    void validationGuards() {
        assertThrows(IllegalArgumentException.class, () -> ComparativeCostGovernor.of(null));

        ComparativeCostGovernor<TestBranch> gov = ComparativeCostGovernor.of(ctx -> TestBranch.PREFILTER);
        assertThrows(IllegalArgumentException.class, () -> gov.evaluate(null));
    }

    @Test
    @DisplayName("Variance-gated hysteresis requires sustained z-score gap and respects flip cooldown")
    void varianceGatedHysteresisAndExploreFloor() {
        ComparativeCostGovernor.HysteresisConfig<TestBranch> cfg =
                new ComparativeCostGovernor.HysteresisConfig<>(
                        TestBranch.PREFILTER, TestBranch.BYPASS,
                        3.0, 4, 8L, 1.0 / 32.0, 0.25);
        ComparativeCostGovernor<TestBranch> gov = ComparativeCostGovernor.ofVarianceHysteresis(cfg);

        for (int i = 0; i < 3; i++) {
            var d = gov.evaluateVarianceGated(15.0, 10.0, 1.0);
            assertEquals(TestBranch.PREFILTER, d.mode());
            assertFalse(d.flipped());
        }
        assertEquals(TestBranch.PREFILTER, gov.evaluateVarianceGated(11.0, 10.0, 1.0).mode());

        ComparativeCostGovernor.VarianceDecision<TestBranch> flip1 = null;
        for (int i = 0; i < 4; i++) {
            flip1 = gov.evaluateVarianceGated(15.0, 10.0, 1.0);
        }
        assertNotNull(flip1);
        assertTrue(flip1.flipped());
        assertEquals(TestBranch.BYPASS, flip1.mode());
        assertEquals(1L, flip1.flips());

        for (int i = 0; i < 7; i++) {
            var d = gov.evaluateVarianceGated(5.0, 15.0, 1.0);
            assertEquals(TestBranch.BYPASS, d.mode());
        }
        var flip2 = gov.evaluateVarianceGated(5.0, 15.0, 1.0);
        assertTrue(flip2.flipped());
        assertEquals(TestBranch.PREFILTER, flip2.mode());
        assertEquals(2L, flip2.flips());

        assertEquals(0.25, ComparativeCostGovernor.computeExploreShare(0.0, 1.0 / 32.0, 0.25), 1e-9);
        assertEquals(1.0 / 32.0, ComparativeCostGovernor.computeExploreShare(100.0, 1.0 / 32.0, 0.25), 1e-9);
    }

    @Test
    @DisplayName("CoalescedTrialAccrual batches same-bin exploration trials while preserving long-run share")
    void coalescedTrialAccrual() {
        ComparativeCostGovernor.CoalescedTrialAccrual accrual =
                new ComparativeCostGovernor.CoalescedTrialAccrual(Long.MIN_VALUE, 4, 16);
        int trials = 0;
        for (long d = 1; d <= 64; d++) {
            if (accrual.shouldTrial(42L, 0.25, d)) {
                trials++;
            }
        }
        assertEquals(16, trials);
    }
}
