package io.github.dailystruggle.helpers.stresstestrtp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RampEvaluatorTest {

    private static final RampEvaluator.Thresholds T = RampEvaluator.Thresholds.DEFAULTS;

    /** Healthy stage at {@code rate} over 60 s: every attempt succeeds, MSPT 20. */
    private static RampEvaluator.StageResult healthy(int idx, double rate) {
        int n = (int) (rate * 60);
        return new RampEvaluator.StageResult(idx, rate, 60.0, n, n, 0, 0, 0, 0, 20.0, false);
    }

    private static RampEvaluator.StageResult msptBad(int idx, double rate) {
        int n = (int) (rate * 60);
        return new RampEvaluator.StageResult(idx, rate, 60.0, n, n, 0, 0, 0, 0, 80.0, false);
    }

    @Test
    @DisplayName("stage passes when achieved >= 95% of offered, MSPT p95 <= 50 and failures <= 1%")
    void passesAllCriteria() {
        RampEvaluator.Evaluation e = RampEvaluator.evaluate(healthy(0, 10), T);
        assertTrue(e.passed());
        assertTrue(e.failed().isEmpty());
        assertEquals("", e.criteriaString());
    }

    @Test
    @DisplayName("achieved below 95% of offered fails ACHIEVED_BELOW_OFFERED; exactly 95% passes")
    void achievedCriterion() {
        // 10 TP/s x 60 s = 600 offered; 570 ok = 95% (boundary passes), 569 fails.
        var atBoundary = new RampEvaluator.StageResult(0, 10, 60.0, 600, 570, 0, 0, 30, 0, 20.0, false);
        assertTrue(RampEvaluator.evaluate(atBoundary, T).passed());
        var below = new RampEvaluator.StageResult(0, 10, 60.0, 600, 569, 0, 0, 31, 0, 20.0, false);
        var e = RampEvaluator.evaluate(below, T);
        assertFalse(e.passed());
        assertEquals(List.of(RampEvaluator.Criterion.ACHIEVED_BELOW_OFFERED), e.failed());
    }

    @Test
    @DisplayName("busy rejections lower achieved throughput but never count toward the failure rate")
    void busyIsShedNotFailure() {
        var s = new RampEvaluator.StageResult(0, 10, 60.0, 600, 400, 0, 0, 200, 0, 20.0, false);
        var e = RampEvaluator.evaluate(s, T);
        assertEquals(List.of(RampEvaluator.Criterion.ACHIEVED_BELOW_OFFERED), e.failed());
        assertEquals(0.0, s.failFraction());
    }

    @Test
    @DisplayName("harness shed beyond the allowed shortfall is tagged HARNESS_SATURATED")
    void harnessSaturated() {
        // 600 offered slots: 400 dispatched + 200 shed (no idle player).
        var s = new RampEvaluator.StageResult(0, 10, 60.0, 400, 400, 0, 0, 0, 200, 20.0, false);
        var e = RampEvaluator.evaluate(s, T);
        assertEquals(List.of(RampEvaluator.Criterion.ACHIEVED_BELOW_OFFERED,
                RampEvaluator.Criterion.HARNESS_SATURATED), e.failed());
    }

    @Test
    @DisplayName("MSPT p95 above 50 ms fails MSPT_P95; exactly 50 ms passes")
    void msptCriterion() {
        var at = new RampEvaluator.StageResult(0, 10, 60.0, 600, 600, 0, 0, 0, 0, 50.0, false);
        assertTrue(RampEvaluator.evaluate(at, T).passed());
        var e = RampEvaluator.evaluate(msptBad(0, 10), T);
        assertEquals(List.of(RampEvaluator.Criterion.MSPT_P95), e.failed());
    }

    @Test
    @DisplayName("(timeouts + errors) above 1% of attempts fails FAILURE_RATE; exactly 1% passes")
    void failureRateCriterion() {
        // 1000 attempts at 20 TP/s for 50 s; 10 failures = 1% passes.
        var at = new RampEvaluator.StageResult(0, 20, 50.0, 1000, 990, 6, 4, 0, 0, 20.0, false);
        // 990 / 50 = 19.8 = 99% of offered, so only the failure criterion is in play.
        assertTrue(RampEvaluator.evaluate(at, T).passed());
        var over = new RampEvaluator.StageResult(0, 20, 50.0, 1000, 989, 6, 5, 0, 0, 20.0, false);
        assertEquals(List.of(RampEvaluator.Criterion.FAILURE_RATE), RampEvaluator.evaluate(over, T).failed());
    }

    @Test
    @DisplayName("aborted stage fails LOW_TPS_ABORT and stops the ramp immediately")
    void abortedStageStops() {
        var ev = new RampEvaluator(T);
        assertEquals(RampEvaluator.Decision.CONTINUE, ev.record(healthy(0, 5), false));
        var aborted = new RampEvaluator.StageResult(1, 10, 12.0, 120, 120, 0, 0, 0, 0, 20.0, true);
        assertEquals(RampEvaluator.Decision.STOP, ev.record(aborted, false));
        assertEquals(RampEvaluator.StopReason.LOW_TPS_ABORT, ev.stopReason());
        assertTrue(ev.firstFailure().failed().contains(RampEvaluator.Criterion.LOW_TPS_ABORT));
        assertEquals(0, ev.stressPointIndex());
    }

    @Test
    @DisplayName("stress point is the highest passing stage; first failure records its criterion")
    void stressPointSelection() {
        var ev = new RampEvaluator(T);
        ev.record(healthy(0, 5), false);
        ev.record(healthy(1, 10), false);
        ev.record(healthy(2, 20), false);
        ev.record(msptBad(3, 40), false);
        ev.record(msptBad(4, 80), false);
        assertEquals(2, ev.stressPointIndex());
        assertEquals(20.0, ev.stressPointTps());
        assertEquals(3, ev.firstFailure().stage().index());
        assertEquals(List.of(RampEvaluator.Criterion.MSPT_P95), ev.firstFailure().failed());
        assertTrue(ev.summaryLine("rtp").contains("stage 2 @ 20 TP/s"));
        assertTrue(ev.summaryLine("rtp").contains("MSPT_P95"));
    }

    @Test
    @DisplayName("two consecutive failing stages stop the ramp")
    void twoConsecutiveFailuresStop() {
        var ev = new RampEvaluator(T);
        assertEquals(RampEvaluator.Decision.CONTINUE, ev.record(healthy(0, 5), false));
        assertEquals(RampEvaluator.Decision.CONTINUE, ev.record(msptBad(1, 10), false));
        assertEquals(RampEvaluator.Decision.STOP, ev.record(msptBad(2, 20), false));
        assertEquals(RampEvaluator.StopReason.CONSECUTIVE_FAILURES, ev.stopReason());
        assertEquals(0, ev.stressPointIndex());
    }

    @Test
    @DisplayName("a pass after a single failure resets the counter and the ramp continues")
    void passAfterSingleFailureContinues() {
        var ev = new RampEvaluator(T);
        ev.record(healthy(0, 5), false);
        assertEquals(RampEvaluator.Decision.CONTINUE, ev.record(msptBad(1, 10), false));
        assertEquals(RampEvaluator.Decision.CONTINUE, ev.record(healthy(2, 20), false));
        assertEquals(0, ev.consecutiveFails());
        assertEquals(RampEvaluator.Decision.CONTINUE, ev.record(msptBad(3, 40), false));
        assertEquals(2, ev.stressPointIndex(), "a later pass above an earlier failure still raises the stress point");
        assertEquals(1, ev.firstFailure().stage().index(), "first failure is the earliest one, not the latest");
    }

    @Test
    @DisplayName("last configured stage stops with STAGES_EXHAUSTED; no passing stage yields stress point -1")
    void exhaustedAndNoPass() {
        var ev = new RampEvaluator(T);
        assertEquals(RampEvaluator.Decision.STOP, ev.record(healthy(0, 5), true));
        assertEquals(RampEvaluator.StopReason.STAGES_EXHAUSTED, ev.stopReason());
        var none = new RampEvaluator(T);
        none.record(msptBad(0, 5), false);
        none.record(msptBad(1, 10), false);
        assertEquals(-1, none.stressPointIndex());
        assertEquals(-1.0, none.stressPointTps());
        assertTrue(none.summaryLine("x").contains("none (no stage passed)"));
    }

    @Test
    @DisplayName("operator stop is recorded only when no rule ended the ramp")
    void operatorStop() {
        var ev = new RampEvaluator(T);
        ev.record(healthy(0, 5), false);
        ev.markOperatorStop();
        assertEquals(RampEvaluator.StopReason.OPERATOR_STOP, ev.stopReason());
        var ruled = new RampEvaluator(T);
        ruled.record(healthy(0, 5), true);
        ruled.markOperatorStop();
        assertEquals(RampEvaluator.StopReason.STAGES_EXHAUSTED, ruled.stopReason());
    }

    @Test
    @DisplayName("low-TPS watch fires only after TPS stays below 5 for a full 10 s")
    void lowTpsTiming() {
        var w = new RampEvaluator.LowTpsWatch(5.0, 10_000L);
        assertFalse(w.observe(0L, 4.0));
        assertFalse(w.observe(9_999L, 4.0));
        assertTrue(w.observe(10_000L, 4.0));
    }

    @Test
    @DisplayName("low-TPS watch re-arms on any sample at or above the threshold or an unknown sample")
    void lowTpsRearms() {
        var w = new RampEvaluator.LowTpsWatch(5.0, 10_000L);
        assertFalse(w.observe(0L, 2.0));
        assertFalse(w.observe(9_000L, 5.0)); // at threshold: not low
        assertFalse(w.observe(9_500L, 2.0));
        assertFalse(w.observe(19_000L, 2.0));
        assertTrue(w.observe(19_500L, 2.0));
        assertFalse(w.observe(20_000L, -1.0)); // unknown resets
        assertFalse(w.observe(29_000L, 1.0));
    }

    @Test
    @DisplayName("stale wall-clock TPS decays as 20/seconds so a stalled tick thread reads low")
    void effectiveTpsStaleness() {
        assertEquals(20.0, RampEvaluator.effectiveTps(20.0, 900L));
        assertEquals(10.0, RampEvaluator.effectiveTps(20.0, 2_000L), 1e-9);
        assertEquals(2.0, RampEvaluator.effectiveTps(20.0, 10_000L), 1e-9);
        assertEquals(3.0, RampEvaluator.effectiveTps(3.0, 2_000L), 1e-9);
        assertEquals(-1.0, RampEvaluator.effectiveTps(-1.0, 50_000L));
    }

    @Test
    @DisplayName("fixed-rate pacing issues floor(rate x elapsed) slots regardless of completions")
    void pacing() {
        assertEquals(0L, RampEvaluator.slotsDue(5, 199L, 0));
        assertEquals(1L, RampEvaluator.slotsDue(5, 200L, 0));
        assertEquals(320L, RampEvaluator.slotsDue(320, 1_000L, 0));
        assertEquals(10L, RampEvaluator.slotsDue(320, 1_000L, 310));
        assertEquals(0L, RampEvaluator.slotsDue(320, 1_000L, 400));
        assertEquals(0L, RampEvaluator.slotsDue(0, 1_000L, 0));
    }

    @Test
    @DisplayName("failure classification separates timeouts, console busy rejections and errors")
    void classification() {
        List<Pattern> busy = RampEvaluator.compile(List.of());
        assertSame(RampEvaluator.Outcome.SUCCESS, RampEvaluator.classify(true, "", busy));
        assertSame(RampEvaluator.Outcome.TIMEOUT, RampEvaluator.classify(false, "TIMEOUT", busy));
        assertSame(RampEvaluator.Outcome.BUSY,
                RampEvaluator.classify(false, "CONSOLE_FAIL:Whoops! you are already rtp'ing", busy));
        assertSame(RampEvaluator.Outcome.BUSY,
                RampEvaluator.classify(false, "CONSOLE_FAIL:You are on cooldown", busy));
        assertSame(RampEvaluator.Outcome.ERROR,
                RampEvaluator.classify(false, "CONSOLE_FAIL:Invalid command", busy));
        assertSame(RampEvaluator.Outcome.ERROR, RampEvaluator.classify(false, "", busy));
        List<Pattern> custom = RampEvaluator.compile(List.of("saturated", "[bad"));
        assertEquals(1, custom.size(), "an invalid admin pattern is skipped");
        assertSame(RampEvaluator.Outcome.BUSY, RampEvaluator.classify(false, "CONSOLE_FAIL:queue saturated", custom));
        assertSame(RampEvaluator.Outcome.ERROR, RampEvaluator.classify(false, "CONSOLE_FAIL:on cooldown", custom));
    }

    @Test
    @DisplayName("ramp CSV row matches the header column count and is durable after each append")
    void writerRows(@TempDir Path dir) throws Exception {
        RampWriter w = new RampWriter(dir.resolve("20261008-000000.csv"));
        var ev = RampEvaluator.evaluate(msptBad(3, 40), T);
        var x = new RampWriter.Extras(12, 30, 45, 22.5, 600, 18.2, 2048, 64, 17, true, 1000L, 61000L);
        w.appendStage("rtp", ev, x);
        w.appendStage("rtp", RampEvaluator.evaluate(healthy(4, 80), T),
                new RampWriter.Extras(-1, -1, -1, -1, 0, -1, -1, 64, 0, false, 2000L, 3000L));
        List<String> lines = Files.readAllLines(w.csvPath(), StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        assertEquals(RampWriter.HEADER, lines.get(0));
        int cols = RampWriter.HEADER.split(",", -1).length;
        assertEquals(cols, lines.get(1).split(",", -1).length);
        assertTrue(lines.get(1).contains(",FAIL,MSPT_P95,"));
        assertTrue(lines.get(2).contains(",false,PARTIAL,"), "incomplete stage is marked PARTIAL");
        assertTrue(w.csvPath().getFileName().toString().endsWith("-ramp.csv"));
        w.writeSummary("stress_point_stage: 2");
        assertEquals("stress_point_stage: 2", Files.readAllLines(w.summaryPath()).get(0));
    }

    @Test
    @DisplayName("ramp target resolves by label ignoring case; omitted label only resolves a single target")
    void targetResolution() {
        var a = new Targets.Entry("rtp", "rtp:rtp");
        var b = new Targets.Entry("BetterRTP", "betterrtp:betterrtp");
        assertSame(b, StressCommand.resolveTarget(List.of(a, b), "betterrtp"));
        assertNull(StressCommand.resolveTarget(List.of(a, b), "missing"));
        assertNull(StressCommand.resolveTarget(List.of(a, b), null));
        assertSame(a, StressCommand.resolveTarget(List.of(a), null));
    }
}
