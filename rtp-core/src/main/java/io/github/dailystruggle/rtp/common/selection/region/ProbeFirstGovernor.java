package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.predict.ComparativeCostGovernor;
import io.github.dailystruggle.rtp.common.predict.EwmaEstimator;
import io.github.dailystruggle.rtp.common.predict.EwmaLinearFit;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.logging.Level;

/**
 * Measured-cost gate deciding whether a candidate runs the center-column probe before the full
 * chunk load (ADR-109). One instance per (region, path).
 *
 * <p>Probe first costs {@code Tp + (1 - p) * Tf|probe}; skipping costs {@code Tf|direct}, where
 * {@code p} is the probe reject rate and all terms are wall-clock moving averages (~256 samples).
 * The mode flips only when the gap exceeds {@link #Z_SWITCH} standard errors for {@link #SUSTAIN}
 * consecutive samples (one EWMA correlation time), and never within {@link #WINDOW} samples of
 * the last flip: the gap is re-tested after every sample and successive EWMA estimates are
 * correlated, so a brief crossing is routine noise, not evidence.
 * Starts in PROBE (the historical behaviour); the losing arm keeps running on a share of
 * candidates that shrinks with confidence but never below {@link #MIN_EXPLORE}, so disk, cache or
 * terrain drift can flip the decision back. No thresholds in config: everything is measured.</p>
 *
 * <p>Probe cost depends on grouping: one coalesced drain pays one region-file open for {@code g}
 * chunks. Samples are per-chunk drain shares (queue wait excluded), fitted as
 * {@code Tp(g) = perChunk + open / g} and evaluated at the group size probes see while probing is
 * the default, so sparse SKIP-mode trials are not charged a full open each. SKIP-mode trials run
 * a region file at a time ({@link #shouldProbe(long)}) so their groups span a range of sizes.</p>
 *
 * <p>Safety: the probe may only reject; the full check stays authoritative (S-001), so the gate
 * changes cost, not outcome validity. Thread-safe; callers on async pool threads only.</p>
 */
public final class ProbeFirstGovernor {

    /** Candidate path owning a governor. */
    public enum Path {
        /** Location generation ({@code PregenTask}): queue fill and the empty-queue fallback. */
        FILL("fill"),
        /** Queued-location consumption ({@code QueueTask}). */
        CONSUME("consume");

        final String label;

        Path(String label) {
            this.label = label;
        }
    }

    /** Current decision. */
    public enum Mode { PROBE, SKIP }

    static final int WINDOW = 256;
    static final int MIN_SAMPLES = 32;
    static final double Z_SWITCH = 3.0;
    static final int SUSTAIN = WINDOW / 2;
    static final double MIN_EXPLORE = 1.0 / 32.0;
    static final double MAX_EXPLORE = 0.25;
    static final long SUMMARY_NANOS = TimeUnit.SECONDS.toNanos(60);
    /** Probes per SKIP-mode trial in one region file. */
    static final int TRIAL_RUN = 8;
    /** Decisions after which an unfinished trial ends. */
    static final int TRIAL_WINDOW = 64;
    /** Bin key for candidates without a region-file bin: trials are single probes. */
    public static final long NO_BIN = Long.MIN_VALUE;
    /** Minimum variance of {@code 1/g} before the open term is fitted. */
    static final double MIN_X_VAR = 1e-3;
    private static final double ALPHA = 2.0 / (WINDOW + 1);

    private static final ConcurrentHashMap<String, ProbeFirstGovernor> REGISTRY = new ConcurrentHashMap<>();

    /** Shared governor for {@code (region, path)}. */
    public static ProbeFirstGovernor of(String region, Path path) {
        String r = (region == null) ? "" : region;
        return REGISTRY.computeIfAbsent(r + "|" + path.label,
                k -> new ProbeFirstGovernor(r, path, System::nanoTime));
    }

    /** Rows for every governor, keyed {@code region|path}, sorted. */
    public static Map<String, ProbeGovernorRow> snapshotAll() {
        if (REGISTRY.isEmpty()) return Collections.emptyMap();
        Map<String, ProbeGovernorRow> out = new TreeMap<>();
        REGISTRY.forEach((k, g) -> out.put(k, g.snapshot()));
        return Collections.unmodifiableMap(out);
    }

    /** Region-file bin of a chunk: the coalescer's grouping key. */
    public static long binKey(int cx, int cz) {
        return ((long) (cx >> 5) << 32) | ((cz >> 5) & 0xFFFFFFFFL);
    }

    /** Drops all governors for the given region. */
    public static void reset(String region) {
        if (region == null) return;
        REGISTRY.entrySet().removeIf(e -> e.getKey().startsWith(region + "|"));
    }

    /** Drops all governors. */
    public static void resetAll() {
        REGISTRY.clear();
    }

    /** Immutable decision state exposed to selection threads without synchronization. */
    record DecisionState(Mode mode, double z, double exploreShare) {
        static final DecisionState INITIAL = new DecisionState(Mode.PROBE, Double.NaN, MAX_EXPLORE);
    }

    private final String region;
    private final Path path;
    private final LongSupplier clock;
    private final ReentrantLock updateLock = new ReentrantLock();
    private final EwmaEstimator probe = EwmaEstimator.ofWindow(WINDOW);
    private final EwmaLinearFit probeFit = EwmaLinearFit.ofCoalescedCost(WINDOW, MIN_SAMPLES, MIN_X_VAR);
    /** {@code 1/g} of probes recorded while probing is the default; frozen in SKIP. */
    private final EwmaEstimator defaultInvG = EwmaEstimator.ofWindow(WINDOW);
    private final EwmaEstimator reject = EwmaEstimator.ofWindow(WINDOW);
    private final EwmaEstimator loadAfterProbe = EwmaEstimator.ofWindow(WINDOW);
    private final EwmaEstimator loadDirect = EwmaEstimator.ofWindow(WINDOW);
    private final ComparativeCostGovernor<Mode> comparativeGovernor =
            ComparativeCostGovernor.ofVarianceHysteresis(new ComparativeCostGovernor.HysteresisConfig<>(
                    Mode.PROBE, Mode.SKIP, Z_SWITCH, SUSTAIN, WINDOW, MIN_EXPLORE, MAX_EXPLORE));
    private final ComparativeCostGovernor.CoalescedTrialAccrual trialAccrual =
            new ComparativeCostGovernor.CoalescedTrialAccrual(NO_BIN, TRIAL_RUN, TRIAL_WINDOW);
    private final AtomicLong skipCount = new AtomicLong();
    private final AtomicLong skipMeanBits = new AtomicLong(Double.doubleToRawLongBits(Double.NaN));
    private final AtomicLong decisions = new AtomicLong();
    private final AtomicLong probeSamples = new AtomicLong();
    private final AtomicLong directSamples = new AtomicLong();
    private volatile DecisionState decisionState = DecisionState.INITIAL;
    private Mode mode = Mode.PROBE;
    private long flips;
    private long lastSummary;
    private double costProbe = Double.NaN;
    private double costDirect = Double.NaN;
    private double z = Double.NaN;

    ProbeFirstGovernor(String region, Path path, LongSupplier clock) {
        this.region = region;
        this.path = path;
        this.clock = clock;
        this.lastSummary = clock.getAsLong();
    }

    /** Decide for one candidate without a bin: {@code true} = run the column probe first. */
    public boolean shouldProbe() {
        return shouldProbe(NO_BIN);
    }

    /** Decide for one candidate in region-file bin {@code bin} ({@link #binKey}): {@code true} = probe first. */
    public boolean shouldProbe(long bin) {
        long d = decisions.incrementAndGet();
        if (probeSamples.get() < MIN_SAMPLES) {
            recordSkip(false);
            return true;
        }
        if (directSamples.get() < MIN_SAMPLES) {
            boolean skipNow = ComparativeCostGovernor.shouldExploreDeterministic(d, MAX_EXPLORE);
            recordSkip(skipNow);
            return !skipNow;
        }
        DecisionState state = this.decisionState;
        boolean skipNow;
        if (state.mode() == Mode.PROBE) {
            skipNow = ComparativeCostGovernor.shouldExploreDeterministic(d, state.exploreShare());
        } else {
            skipNow = !trialAccrual.shouldTrial(bin, state.exploreShare(), d);
        }
        recordSkip(skipNow);
        return !skipNow;
    }

    private void recordSkip(boolean skipped) {
        double x = skipped ? 1.0 : 0.0;
        long n = skipCount.incrementAndGet();
        if (n == 1) {
            skipMeanBits.set(Double.doubleToRawLongBits(x));
            return;
        }
        double a = Math.max(1.0 / n, ALPHA);
        skipMeanBits.getAndUpdate(prevBits -> {
            double prev = Double.longBitsToDouble(prevBits);
            if (Double.isNaN(prev)) return Double.doubleToRawLongBits(x);
            double next = prev + a * (x - prev);
            return Double.doubleToRawLongBits(next);
        });
    }

    private double skipMean() {
        return Double.longBitsToDouble(skipMeanBits.get());
    }

    /** One finished, uncoalesced probe: wall time from dispatch to verdict, and whether it rejected. */
    public void recordProbe(long nanos, boolean rejected) {
        recordProbe(nanos, 1, rejected);
    }

    /**
     * One finished probe. {@code nanos}: its per-chunk share of the coalesced drain (or wall time when
     * uncoalesced); {@code groupSize}: requests served by that drain ({@code < 1} = uncoalesced, one open).
     */
    public void recordProbe(long nanos, int groupSize, boolean rejected) {
        if (nanos < 0L) return;
        double invG = 1.0 / Math.max(1, groupSize);
        String log;
        updateLock.lock();
        try {
            probe.recordSample(nanos);
            probeSamples.set(probe.sampleCount());
            probeFit.record(invG, nanos);
            if (decisionState.mode() == Mode.PROBE) defaultInvG.recordSample(invG);
            reject.recordSample(rejected ? 1.0 : 0.0);
            log = evaluate();
        } finally {
            updateLock.unlock();
        }
        if (log != null) RTPRuntime.log(Level.INFO, log);
    }

    /** One finished full load: wall time from request to chunk; {@code afterProbe} = a probe ran first. */
    public void recordLoad(boolean afterProbe, long nanos) {
        if (nanos < 0L) return;
        String log;
        updateLock.lock();
        try {
            (afterProbe ? loadAfterProbe : loadDirect).recordSample(nanos);
            if (!afterProbe) {
                directSamples.set(loadDirect.sampleCount());
            }
            log = evaluate();
        } finally {
            updateLock.unlock();
        }
        if (log != null) RTPRuntime.log(Level.INFO, log);
    }

    public Mode mode() {
        return decisionState.mode();
    }

    public ProbeGovernorRow snapshot() {
        updateLock.lock();
        try {
            DecisionState state = this.decisionState;
            return new ProbeGovernorRow(region, path.label, state.mode().name(), !warm(),
                    reject.mean(), ms(probeAtDefault()), ms(loadAfterProbe.mean()), ms(loadDirect.mean()),
                    ms(costProbe), ms(costDirect), state.z(), skipMean(), flips,
                    probe.sampleCount(), loadDirect.sampleCount());
        } finally {
            updateLock.unlock();
        }
    }

    private boolean warm() {
        return probe.sampleCount() >= MIN_SAMPLES && loadDirect.sampleCount() >= MIN_SAMPLES;
    }

    /** {@code 1/g} at which the probe arm is judged: the default-probing group size once seen. */
    private double invGAtDefault() {
        return (defaultInvG.sampleCount() > 0) ? defaultInvG.mean() : probeFit.meanX();
    }

    /** Fitted per-chunk probe cost at the default-probing group size. */
    double probeAtDefault() {
        updateLock.lock();
        try {
            return (probeFit.sampleCount() == 0) ? Double.NaN : probeFit.predict(invGAtDefault());
        } finally {
            updateLock.unlock();
        }
    }

    /** Group size the probe arm is judged at ({@code 1 / E[1/g]}). */
    double groupSizeAtDefault() {
        updateLock.lock();
        try {
            double x = invGAtDefault();
            return (Double.isNaN(x) || x <= 0.0) ? Double.NaN : 1.0 / x;
        } finally {
            updateLock.unlock();
        }
    }

    /** Losing-arm share: ~1/(1+|z|), within [MIN_EXPLORE, MAX_EXPLORE]. */
    static double computeExploreShare(double zScore) {
        return ComparativeCostGovernor.computeExploreShare(zScore, MIN_EXPLORE, MAX_EXPLORE);
    }

    /** Recompute costs; flip on a significant gap. Returns a log line to emit, or null. */
    private String evaluate() {
        if (!warm()) return summaryIfDue();
        double p = clamp01(reject.mean());
        // Rare after-probe loads (p near 1) give a noisy mean; borrow the direct arm until sampled.
        EwmaEstimator tf = (loadAfterProbe.sampleCount() >= 8) ? loadAfterProbe : loadDirect;
        double x = invGAtDefault();
        costProbe = probeFit.predict(x) + (1.0 - p) * tf.mean();
        costDirect = loadDirect.mean();
        double se2 = probeFit.standardErrorSquaredAt(x)
                + (1.0 - p) * (1.0 - p) * tf.standardErrorSquared()
                + tf.mean() * tf.mean() * p * (1.0 - p) / reject.effectiveSampleCount()
                + loadDirect.standardErrorSquared();
        ComparativeCostGovernor.VarianceDecision<Mode> decision =
                comparativeGovernor.evaluateVarianceGated(costProbe, costDirect, se2);
        z = decision.zScore();
        mode = decision.mode();
        flips = decision.flips();
        this.decisionState = new DecisionState(decision.mode(), decision.zScore(), decision.exploreShare());
        if (decision.flipped()) {
            trialAccrual.reset();
            lastSummary = clock.getAsLong();
            return "[RTP][probe-gov] " + region + "/" + path.label + " flip "
                    + decision.previousMode() + "->" + decision.mode() + " " + describe();
        }
        return summaryIfDue();
    }

    private String summaryIfDue() {
        long now = clock.getAsLong();
        if (now - lastSummary < SUMMARY_NANOS) return null;
        lastSummary = now;
        return "[RTP][probe-gov] " + region + "/" + path.label + " summary " + describe();
    }

    private String describe() {
        return String.format(Locale.ROOT,
                "mode=%s%s p=%.3f Tp=%.3fms (raw=%.3fms g=%.1f open=%.3fms) Tf|probe=%.3fms Tf|direct=%.3fms"
                        + " cost probe=%.3fms direct=%.3fms z=%.2f skip=%.3f n(probe/direct)=%d/%d flips=%d",
                mode, warm() ? "" : "(warmup)", reject.mean(), ms(probeAtDefault()), ms(probe.mean()),
                groupSizeAtDefault(), ms(probeFit.slope()), ms(loadAfterProbe.mean()),
                ms(loadDirect.mean()), ms(costProbe), ms(costDirect), z, skipMean(),
                probe.sampleCount(), loadDirect.sampleCount(), flips);
    }

    private static double ms(double nanos) {
        return nanos / 1_000_000.0;
    }

    private static double clamp01(double v) {
        return Double.isNaN(v) ? 0.0 : Math.max(0.0, Math.min(1.0, v));
    }
}
