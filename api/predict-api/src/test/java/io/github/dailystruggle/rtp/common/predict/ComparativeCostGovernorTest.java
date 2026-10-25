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

    @Test
    @DisplayName("HysteresisConfig validation, z-score edge cases, deterministic explore stride, and context evaluation")
    void hysteresisValidationAndEdgeCases() {
        assertThrows(IllegalArgumentException.class,
                () -> new ComparativeCostGovernor.HysteresisConfig<>(null, TestBranch.BYPASS, 3.0, 4, 8L, 0.05, 0.25));
        assertThrows(IllegalArgumentException.class,
                () -> new ComparativeCostGovernor.HysteresisConfig<>(TestBranch.PREFILTER, null, 3.0, 4, 8L, 0.05, 0.25));
        assertThrows(IllegalArgumentException.class,
                () -> new ComparativeCostGovernor.HysteresisConfig<>(TestBranch.PREFILTER, TestBranch.PREFILTER, 3.0, 4, 8L, 0.05, 0.25));
        assertThrows(IllegalArgumentException.class,
                () -> new ComparativeCostGovernor.HysteresisConfig<>(TestBranch.PREFILTER, TestBranch.BYPASS, 0.0, 4, 8L, 0.05, 0.25));
        assertThrows(IllegalArgumentException.class,
                () -> new ComparativeCostGovernor.HysteresisConfig<>(TestBranch.PREFILTER, TestBranch.BYPASS, Double.NaN, 4, 8L, 0.05, 0.25));
        assertThrows(IllegalArgumentException.class,
                () -> new ComparativeCostGovernor.HysteresisConfig<>(TestBranch.PREFILTER, TestBranch.BYPASS, 3.0, 0, 8L, 0.05, 0.25));
        assertThrows(IllegalArgumentException.class,
                () -> new ComparativeCostGovernor.HysteresisConfig<>(TestBranch.PREFILTER, TestBranch.BYPASS, 3.0, 4, -1L, 0.05, 0.25));
        assertThrows(IllegalArgumentException.class,
                () -> new ComparativeCostGovernor.HysteresisConfig<>(TestBranch.PREFILTER, TestBranch.BYPASS, 3.0, 4, 8L, -0.1, 0.25));
        assertThrows(IllegalArgumentException.class,
                () -> new ComparativeCostGovernor.HysteresisConfig<>(TestBranch.PREFILTER, TestBranch.BYPASS, 3.0, 4, 8L, 0.05, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> new ComparativeCostGovernor.HysteresisConfig<>(TestBranch.PREFILTER, TestBranch.BYPASS, 3.0, 4, 8L, 0.5, 0.25));
        assertThrows(IllegalArgumentException.class,
                () -> new ComparativeCostGovernor.HysteresisConfig<>(TestBranch.PREFILTER, TestBranch.BYPASS, 3.0, 4, 8L, Double.NaN, 0.25));
        assertThrows(IllegalArgumentException.class,
                () -> new ComparativeCostGovernor.HysteresisConfig<>(TestBranch.PREFILTER, TestBranch.BYPASS, 3.0, 4, 8L, 0.05, Double.NaN));

        assertThrows(IllegalArgumentException.class, () -> ComparativeCostGovernor.ofVarianceHysteresis(null));
        assertThrows(IllegalArgumentException.class, () -> new ComparativeCostGovernor.CoalescedTrialAccrual(0L, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new ComparativeCostGovernor.CoalescedTrialAccrual(0L, 4, 0));

        // Stateless governor throws on evaluateVarianceGated
        ComparativeCostGovernor<TestBranch> stateless = ComparativeCostGovernor.of(ctx -> TestBranch.PREFILTER);
        assertNotNull(stateless.policy());
        assertThrows(IllegalStateException.class, () -> stateless.evaluateVarianceGated(1.0, 2.0, 1.0));

        // Variance governor evaluated via CostContext and latestDecision
        ComparativeCostGovernor.HysteresisConfig<TestBranch> cfg =
                new ComparativeCostGovernor.HysteresisConfig<>(
                        TestBranch.PREFILTER, TestBranch.BYPASS, 3.0, 1, 0L, 0.05, 0.25);
        ComparativeCostGovernor<TestBranch> varGov = ComparativeCostGovernor.ofVarianceHysteresis(cfg);
        assertNotNull(varGov.latestDecision());
        CostContext ctx = CostContext.builder().with("costA", 20.0).with("costB", 5.0).with("se2", 1.0).build();
        assertEquals(TestBranch.BYPASS, varGov.evaluate(ctx));
        assertEquals(TestBranch.BYPASS, varGov.latestDecision().mode());

        // computeZScore and computeExploreShare edge cases
        assertTrue(Double.isNaN(ComparativeCostGovernor.computeZScore(Double.NaN, 1.0)));
        assertTrue(Double.isNaN(ComparativeCostGovernor.computeZScore(1.0, Double.NaN)));
        assertEquals(Double.POSITIVE_INFINITY, ComparativeCostGovernor.computeZScore(5.0, 0.0));
        assertEquals(Double.NEGATIVE_INFINITY, ComparativeCostGovernor.computeZScore(-5.0, 0.0));
        assertEquals(0.0, ComparativeCostGovernor.computeZScore(0.0, 0.0));
        assertEquals(0.05, ComparativeCostGovernor.computeExploreShare(Double.POSITIVE_INFINITY, 0.05, 0.25), 1e-9);
        assertEquals(0.25, ComparativeCostGovernor.computeExploreShare(Double.NaN, 0.05, 0.25), 1e-9);

        // shouldExploreDeterministic
        assertFalse(ComparativeCostGovernor.shouldExploreDeterministic(1L, 0.0));
        assertFalse(ComparativeCostGovernor.shouldExploreDeterministic(1L, -0.1));
        assertFalse(ComparativeCostGovernor.shouldExploreDeterministic(3L, 0.25));
        assertTrue(ComparativeCostGovernor.shouldExploreDeterministic(4L, 0.25));

        // CoalescedTrialAccrual window expiry, noBinKey, and reset
        ComparativeCostGovernor.CoalescedTrialAccrual accrual =
                new ComparativeCostGovernor.CoalescedTrialAccrual(-1L, 3, 5);
        assertTrue(accrual.shouldTrial(10L, 1.0, 1L)); // starts run on bin 10, until = 6
        assertTrue(accrual.shouldTrial(10L, 0.0, 10L) == false); // expired because d=10 > until=6
        assertTrue(accrual.shouldTrial(-1L, 1.0, 11L)); // noBinKey leaves left=0
        accrual.reset();
        assertFalse(accrual.shouldTrial(10L, 0.1, 12L));
    }
}
