package io.github.dailystruggle.rtp.common.predict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("REQ-PRED-F-003: BudgetPacer Execution Pacing and Progress Invariants")
class BudgetPacerTest {

    @Test
    @DisplayName("Boundary: Zero or negative budget never executes any task")
    void zeroOrNegativeBudget_neverExecutes() {
        BudgetPacer pacer = BudgetPacer.ofTaskPipe(50_000_000L);

        assertFalse(pacer.canExecute(0L, 0L, false), "Zero budget with ranAny=false must yield");
        assertFalse(pacer.canExecute(0L, 0L, true), "Zero budget with ranAny=true must yield");
        assertFalse(pacer.canExecute(0L, -100L, false), "Negative budget with ranAny=false must yield");
        assertFalse(pacer.canExecute(0L, -100L, true), "Negative budget with ranAny=true must yield");
    }

    @Test
    @DisplayName("Boundary: Micro-budget guarantees forward progress on first attempt (ranAny == false)")
    void positiveBudget_guaranteesForwardProgress() {
        BudgetPacer pacer = BudgetPacer.ofTaskPipe(50_000_000L); // 50ms predicted cost

        // Budget of 1ns: way smaller than predicted 50ms, but ranAny=false guarantees progress
        assertTrue(pacer.canExecute(0L, 1L, false), "First task must always be admitted on positive budget");

        // Subsequent task with ranAny=true must yield
        assertFalse(pacer.canExecute(1_000L, 1L, true), "Subsequent task must yield when budget exceeded");
    }

    @Test
    @DisplayName("Admit vs Yield: Admits when task fits within budget, yields when overrun predicted")
    void admitVsYield_basedOnPredictedDuration() {
        BudgetPacer pacer = BudgetPacer.ofTaskPipe(10_000_000L); // 10ms prediction

        // Budget is 50ms. Elapsed is 20ms. 20ms + 10ms <= 50ms -> admitted!
        assertTrue(pacer.canExecute(20_000_000L, 50_000_000L, true));

        // Elapsed is 45ms. 45ms + 10ms > 50ms -> yields!
        assertFalse(pacer.canExecute(45_000_000L, 50_000_000L, true));
    }

    @Test
    @DisplayName("Safety Margin: Multiplier scales up predicted duration to provide headroom")
    void safetyMargin_scalesPrediction() {
        EwmaEstimator estimator = EwmaEstimator.ofWindow(64, 10_000_000.0); // 10ms
        BudgetPacer pacerWithMargin = BudgetPacer.ofEstimator(estimator, 1.25); // +25% margin

        assertEquals(12_500_000L, pacerWithMargin.predictedCostNanos(), "10ms * 1.25 must equal 12.5ms");

        // With 20ms budget and 10ms elapsed:
        // Raw 10ms fits (10ms + 10ms <= 20ms), but with 1.25 margin: 10ms + 12.5ms = 22.5ms > 20ms -> yields!
        assertFalse(pacerWithMargin.canExecute(10_000_000L, 20_000_000L, true));
    }

    @Test
    @DisplayName("Record duration: Updates underlying estimator moving average")
    void recordDuration_updatesPrediction() {
        BudgetPacer pacer = BudgetPacer.ofTaskPipe(50_000_000L);
        assertEquals(50_000_000L, pacer.predictedCostNanos());

        pacer.recordDuration(10_000_000L);
        long expected = ((50_000_000L * 7L) / 8L) + (10_000_000L / 8L);
        assertEquals(expected, pacer.predictedCostNanos());
    }
}
