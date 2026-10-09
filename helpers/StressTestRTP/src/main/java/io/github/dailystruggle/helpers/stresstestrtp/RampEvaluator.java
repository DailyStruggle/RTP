package io.github.dailystruggle.helpers.stresstestrtp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure decision logic for the fixed-rate ramp: stage verdicts, stress-point
 * selection, stop rules, low-TPS abort timing, dispatch pacing and failure
 * classification. No Bukkit types, so it is unit-testable in isolation.
 *
 * <p>Stress point = highest PASSING stage. A stage passes iff achieved
 * throughput is at least {@code minAchievedFraction} of offered, MSPT p95 is
 * at most {@code maxMsptP95}, and (timeouts + errors) is at most
 * {@code maxFailFraction} of attempts. Busy rejections are shed load: they
 * lower achieved throughput but are not failures.
 */
public final class RampEvaluator {

    /** Pass thresholds and stop rules. */
    public record Thresholds(double minAchievedFraction, double maxMsptP95,
                             double maxFailFraction, int maxConsecutiveFails,
                             double lowTpsThreshold, long lowTpsWindowMs) {
        public static final Thresholds DEFAULTS = new Thresholds(0.95, 50.0, 0.01, 2, 5.0, 10_000L);
    }

    /** Criteria a stage can fail on; written literally to the CSV. */
    public enum Criterion {
        /** Server TPS stayed under the threshold for the whole window; stage aborted. */
        LOW_TPS_ABORT,
        /** achieved_tps below minAchievedFraction x offered_tps. */
        ACHIEVED_BELOW_OFFERED,
        /** Shortfall explained by harness-side shedding (no idle player): a harness bound, not a plugin one. */
        HARNESS_SATURATED,
        /** MSPT p95 above maxMsptP95. */
        MSPT_P95,
        /** (timeouts + errors) / attempts above maxFailFraction. */
        FAILURE_RATE,
        /** Zero attempts dispatched; nothing measurable. */
        NO_ATTEMPTS
    }

    /** Raw per-stage counts. {@code stageSeconds} is the actual dispatch window. */
    public record StageResult(int index, double offeredTps, double stageSeconds,
                              int attempts, int successes, int timeouts, int errors,
                              int busyRejections, int harnessShed,
                              double msptP95, boolean aborted) {
        public double achievedTps() {
            return stageSeconds > 0 ? successes / stageSeconds : 0.0;
        }
        public double achievedFraction() {
            return offeredTps > 0 ? achievedTps() / offeredTps : 0.0;
        }
        public double failFraction() {
            return attempts > 0 ? (double) (timeouts + errors) / attempts : 0.0;
        }
    }

    /** Verdict for one stage; {@code failed} empty iff passed. */
    public record Evaluation(StageResult stage, boolean passed, List<Criterion> failed) {
        public String criteriaString() {
            if (failed.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (Criterion c : failed) {
                if (sb.length() > 0) sb.append('+');
                sb.append(c.name());
            }
            return sb.toString();
        }
    }

    public enum Decision { CONTINUE, STOP }

    public enum StopReason {
        NONE, CONSECUTIVE_FAILURES, LOW_TPS_ABORT, STAGES_EXHAUSTED, OPERATOR_STOP
    }

    private final Thresholds t;
    private final List<Evaluation> history = new ArrayList<>();
    private int consecutiveFails = 0;
    private int stressPointIdx = -1;
    private double stressPointTps = -1.0;
    private Evaluation firstFail = null;
    private StopReason stopReason = StopReason.NONE;

    public RampEvaluator(Thresholds thresholds) {
        this.t = thresholds == null ? Thresholds.DEFAULTS : thresholds;
    }

    public Thresholds thresholds() { return t; }

    /** Applies the pass criteria to one stage. Order is reporting order. */
    public static Evaluation evaluate(StageResult s, Thresholds t) {
        List<Criterion> failed = new ArrayList<>(3);
        if (s.aborted()) failed.add(Criterion.LOW_TPS_ABORT);
        if (s.attempts() <= 0 && s.harnessShed() <= 0) {
            failed.add(Criterion.NO_ATTEMPTS);
        } else {
            if (s.achievedFraction() < t.minAchievedFraction()) {
                failed.add(Criterion.ACHIEVED_BELOW_OFFERED);
                // Shed slots alone exceed the allowed shortfall: the roster,
                // not the plugin, capped this stage.
                int offeredSlots = s.attempts() + s.harnessShed();
                if (s.harnessShed() > (1.0 - t.minAchievedFraction()) * offeredSlots) {
                    failed.add(Criterion.HARNESS_SATURATED);
                }
            }
            if (s.msptP95() > t.maxMsptP95()) failed.add(Criterion.MSPT_P95);
            if (s.failFraction() > t.maxFailFraction()) failed.add(Criterion.FAILURE_RATE);
        }
        return new Evaluation(s, failed.isEmpty(), Collections.unmodifiableList(failed));
    }

    /**
     * Records a completed stage and returns whether the ramp continues.
     * {@code lastStage} true means no further stage is configured.
     */
    public Decision record(StageResult s, boolean lastStage) {
        Evaluation e = evaluate(s, t);
        history.add(e);
        if (e.passed()) {
            consecutiveFails = 0;
            if (s.offeredTps() > stressPointTps) {
                stressPointTps = s.offeredTps();
                stressPointIdx = s.index();
            }
        } else {
            consecutiveFails++;
            if (firstFail == null) firstFail = e;
        }
        if (s.aborted()) {
            stopReason = StopReason.LOW_TPS_ABORT;
            return Decision.STOP;
        }
        if (consecutiveFails >= Math.max(1, t.maxConsecutiveFails())) {
            stopReason = StopReason.CONSECUTIVE_FAILURES;
            return Decision.STOP;
        }
        if (lastStage) {
            stopReason = StopReason.STAGES_EXHAUSTED;
            return Decision.STOP;
        }
        return Decision.CONTINUE;
    }

    /** Marks an operator-initiated end; keeps any earlier rule-based reason. */
    public void markOperatorStop() {
        if (stopReason == StopReason.NONE) stopReason = StopReason.OPERATOR_STOP;
    }

    /** Index of the highest passing stage, or -1 when none passed. */
    public int stressPointIndex() { return stressPointIdx; }
    /** Offered rate of the highest passing stage, or -1 when none passed. */
    public double stressPointTps() { return stressPointTps; }
    /** First failing stage's evaluation, or null. */
    public Evaluation firstFailure() { return firstFail; }
    public StopReason stopReason() { return stopReason; }
    public int consecutiveFails() { return consecutiveFails; }
    public List<Evaluation> history() { return Collections.unmodifiableList(history); }

    /** One-line human summary for the log and the summary sidecar. */
    public String summaryLine(String target) {
        String sp = stressPointIdx < 0
                ? "none (no stage passed)"
                : String.format(Locale.ROOT, "stage %d @ %s TP/s", stressPointIdx, fmtRate(stressPointTps));
        String ff = firstFail == null
                ? "none"
                : String.format(Locale.ROOT, "stage %d @ %s TP/s failed on %s (achieved=%.2f TP/s, mspt_p95=%.2f, fail=%.2f%%)",
                        firstFail.stage().index(), fmtRate(firstFail.stage().offeredTps()),
                        firstFail.criteriaString(), firstFail.stage().achievedTps(),
                        firstFail.stage().msptP95(), firstFail.stage().failFraction() * 100.0);
        return String.format(Locale.ROOT, "ramp %s: stress point %s; first failure %s; stop=%s; stages=%d",
                target, sp, ff, stopReason.name(), history.size());
    }

    static String fmtRate(double r) {
        return r == Math.rint(r) ? Long.toString((long) r) : String.format(Locale.ROOT, "%.2f", r);
    }

    // ---------------------------------------------------------------------
    // Low-TPS abort

    /**
     * Fires once TPS has been continuously below the threshold for the full
     * window. Any sample at or above the threshold (or an unknown, negative
     * sample) re-arms it.
     */
    public static final class LowTpsWatch {
        private final double threshold;
        private final long windowMs;
        private long belowSinceMs = -1L;

        public LowTpsWatch(double threshold, long windowMs) {
            this.threshold = threshold;
            this.windowMs = Math.max(0L, windowMs);
        }

        /** True when the abort condition holds at {@code nowMs}. */
        public boolean observe(long nowMs, double tps) {
            if (threshold <= 0 || tps < 0 || tps >= threshold) {
                belowSinceMs = -1L;
                return false;
            }
            if (belowSinceMs < 0) belowSinceMs = nowMs;
            return nowMs - belowSinceMs >= windowMs;
        }

        public void reset() { belowSinceMs = -1L; }
    }

    /**
     * Wall-clock TPS with a staleness bound. A 20-tick timer that has not
     * fired for {@code sinceLastMs} proves fewer than 20 ticks ran in that
     * span, so TPS is at most {@code 20 / seconds}; without this a fully
     * stalled tick thread would keep reporting its last healthy value.
     */
    public static double effectiveTps(double lastMeasuredTps, long sinceLastMs) {
        if (lastMeasuredTps < 0) return -1.0;
        if (sinceLastMs <= 1000L) return lastMeasuredTps;
        return Math.min(lastMeasuredTps, 20.0 / (sinceLastMs / 1000.0));
    }

    // ---------------------------------------------------------------------
    // Fixed-rate pacing

    /** Dispatch slots due at {@code elapsedMs} into a stage minus those already issued. */
    public static long slotsDue(double rateTps, long elapsedMs, long issued) {
        if (rateTps <= 0 || elapsedMs < 0) return 0L;
        long target = (long) Math.floor(rateTps * elapsedMs / 1000.0);
        return Math.max(0L, target - issued);
    }

    // ---------------------------------------------------------------------
    // Failure classification

    public enum Outcome { SUCCESS, TIMEOUT, BUSY, ERROR }

    /** Default plugin-side busy/cooldown phrases (case-insensitive). */
    public static final List<String> DEFAULT_BUSY_PATTERNS = List.of(
            "already teleporting",
            "already rtp'?ing",
            "have some patience",
            "cool ?down",
            "cooling down",
            "please wait",
            "you must wait",
            "wait \\d+",
            "for another \\d+",
            "can'?t rtp",
            "busy",
            "queue",
            "spot in line",
            "no locations ready",
            "try again"
    );

    // Admin-supplied patterns, compiled once per ramp start.
    @SuppressWarnings("PMD.RegexCompiledPerCall")
    public static List<Pattern> compile(List<String> raw) {
        List<String> src = (raw == null || raw.isEmpty()) ? DEFAULT_BUSY_PATTERNS : raw;
        List<Pattern> out = new ArrayList<>(src.size());
        for (String s : src) {
            try {
                out.add(Pattern.compile(s, Pattern.CASE_INSENSITIVE));
            } catch (Exception ignored) {
                // invalid admin pattern: skipped, the rest still apply
            }
        }
        return out;
    }

    /**
     * Buckets a finished attempt. TIMEOUT is the reaper's literal reason;
     * BUSY needs a console-attributed reason matching a busy pattern; every
     * other failure is an ERROR.
     */
    public static Outcome classify(boolean success, String failReason, List<Pattern> busy) {
        if (success) return Outcome.SUCCESS;
        String r = failReason == null ? "" : failReason;
        if ("TIMEOUT".equals(r)) return Outcome.TIMEOUT;
        if (r.startsWith("CONSOLE_FAIL:") && busy != null) {
            String body = r.substring("CONSOLE_FAIL:".length());
            for (Pattern p : busy) {
                if (p.matcher(body).find()) return Outcome.BUSY;
            }
        }
        return Outcome.ERROR;
    }
}
