package io.github.dailystruggle.rtp.common.predict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("REQ-PRED-F-004: ComparativeCostGovernor Branch Evaluation and Threshold Gating")
class ComparativeCostGovernorTest {

    enum Decision {
        PREFILTER,
        BYPASS
    }

    @Test
    @DisplayName("Branch Evaluation: Evaluates threshold policy correctly from CostContext")
    void evaluate_thresholdPolicy() {
        // Model deduplication threshold: pReject > cFile / (cNative + wTick * cSync)
        ComparativeCostGovernor<Decision> governor = ComparativeCostGovernor.of(ctx -> {
            double pReject = ctx.get("pReject", 0.0);
            double cFile = ctx.get("cFile", 2.5);
            double cNative = ctx.get("cNative", 15.0);
            double cSync = ctx.get("cSync", 1.0);
            double wTick = ctx.get("wTick", 5.0);

            double denom = cNative + wTick * cSync;
            double threshold = (denom <= 0.0) ? 0.0 : (cFile / denom);

            return pReject > threshold ? Decision.PREFILTER : Decision.BYPASS;
        });

        // Scenario 1: Safe terrain (pReject = 0.05). Denom = 15 + 5 = 20. Threshold = 2.5 / 20 = 0.125.
        // 0.05 <= 0.125 -> BYPASS
        CostContext safeContext = CostContext.builder()
                .with("pReject", 0.05)
                .with("cFile", 2.5)
                .with("cNative", 15.0)
                .with("cSync", 1.0)
                .with("wTick", 5.0)
                .build();
        assertEquals(Decision.BYPASS, governor.evaluate(safeContext));

        // Scenario 2: Hazardous terrain (pReject = 0.30 > 0.125) -> PREFILTER
        CostContext hazardContext = CostContext.builder()
                .with("pReject", 0.30)
                .with("cFile", 2.5)
                .with("cNative", 15.0)
                .with("cSync", 1.0)
                .with("wTick", 5.0)
                .build();
        assertEquals(Decision.PREFILTER, governor.evaluate(hazardContext));

        // Scenario 3: Safe terrain but high tick distress (wTick = 100.0).
        // Denom = 15 + 100 = 115. Threshold = 2.5 / 115 ~ 0.0217.
        // pReject = 0.05 > 0.0217 -> PREFILTER
        CostContext distressContext = CostContext.builder()
                .with("pReject", 0.05)
                .with("cFile", 2.5)
                .with("cNative", 15.0)
                .with("cSync", 1.0)
                .with("wTick", 100.0)
                .build();
        assertEquals(Decision.PREFILTER, governor.evaluate(distressContext));
    }

    @Test
    @DisplayName("CostContext Builder: Stores and retrieves metrics with defaults")
    void costContext_builderAndFallbacks() {
        CostContext ctx = CostContext.builder()
                .with("alpha", 1.23)
                .with("beta", 4.56)
                .build();

        assertEquals(1.23, ctx.get("alpha"));
        assertEquals(4.56, ctx.get("beta"));
        assertTrue(ctx.has("alpha"));
        assertFalse(ctx.has("gamma"));
        assertTrue(Double.isNaN(ctx.get("gamma")));
        assertEquals(99.0, ctx.get("gamma", 99.0));
    }

    @Test
    @DisplayName("Contract: Null policy throws IllegalArgumentException")
    void nullPolicy_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> ComparativeCostGovernor.of(null));
    }
}
