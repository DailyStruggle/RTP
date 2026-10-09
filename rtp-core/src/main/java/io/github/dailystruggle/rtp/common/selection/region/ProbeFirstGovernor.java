package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.metrics.ProbeGovernorRow;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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

    /** EWMA mean/variance; the first samples use 1/n so early estimates are unbiased. */
    static final class Ewma {
        long n;
        double mean = Double.NaN;
        double var;

        void add(double x) {
            n++;
            if (n == 1) {
                mean = x;
                var = 0.0;
                return;
            }
            double a = Math.max(1.0 / n, ALPHA);
            double d = x - mean;
            double inc = a * d;
            mean += inc;
            var = (1.0 - a) * (var + d * inc);
        }

        double neff() {
            return Math.min(n, WINDOW);
        }
    }

    /** EWMA least-squares fit of per-chunk probe cost {@code y} on {@code x = 1/g}: {@code y = perChunk + open * x}. */
    static final class Fit {
        long n;
        double mx = Double.NaN;
        double my = Double.NaN;
        double vxx;
        double vyy;
        double cxy;

        void add(double x, double y) {
            n++;
            if (n == 1) {
                mx = x;
                my = y;
                vxx = vyy = cxy = 0.0;
                return;
            }
            double a = Math.max(1.0 / n, ALPHA);
            double dx = x - mx;
            double dy = y - my;
            mx += a * dx;
            my += a * dy;
            vxx = (1.0 - a) * (vxx + a * dx * dx);
            vyy = (1.0 - a) * (vyy + a * dy * dy);
            cxy = (1.0 - a) * (cxy + a * dx * dy);
        }

        /** Open term is identifiable only once group sizes vary. */
        boolean identified() {
            return n >= MIN_SAMPLES && vxx > MIN_X_VAR;
        }

        /** Open cost per {@code 1/g}; never negative (an open cannot save time). */
        double slope() {
            return identified() ? Math.max(0.0, cxy / vxx) : 0.0;
        }

        double at(double x) {
            return Math.max(0.0, my + slope() * (x - mx));
        }

        /** Squared standard error of {@link #at}: residual variance, widened by extrapolation distance. */
        double se2At(double x) {
            double neff = Math.min(n, WINDOW);
            if (!identified()) return vyy / neff;
            double b = slope();
            double resid = Math.max(0.0, vyy - 2.0 * b * cxy + b * b * vxx);
            double dx = x - mx;
            return resid / neff * (1.0 + dx * dx / vxx);
        }
    }

    private final String region;
    private final Path path;
    private final LongSupplier clock;
    private final Ewma probe = new Ewma();
    private final Fit probeFit = new Fit();
    /** {@code 1/g} of probes recorded while probing is the default; frozen in SKIP. */
    private final Ewma defaultInvG = new Ewma();
    private final Ewma reject = new Ewma();
    private final Ewma loadAfterProbe = new Ewma();
    private final Ewma loadDirect = new Ewma();
    private final Ewma skip = new Ewma();
    private Mode mode = Mode.PROBE;
    private long flips;
    private long decisions;
    private long lastSummary;
    private long sinceFlip;
    private int beyond;
    private double trialCredit;
    private long trialBin = NO_BIN;
    private int trialLeft;
    private long trialUntil;
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
        boolean skipNow;
        synchronized (this) {
            decisions++;
            if (probe.n < MIN_SAMPLES) {
                skipNow = false;
            } else if (loadDirect.n < MIN_SAMPLES) {
                skipNow = every(MAX_EXPLORE);
            } else if (mode == Mode.PROBE) {
                skipNow = every(exploreShare());
            } else {
                skipNow = !trialProbe(bin, exploreShare());
            }
            skip.add(skipNow ? 1.0 : 0.0);
        }
        return !skipNow;
    }

    /**
     * SKIP-mode trial. The explore share accrues as credit; a trial spends one credit per probe on
     * up to {@link #TRIAL_RUN} candidates of one bin within {@link #TRIAL_WINDOW} decisions, so trial
     * probes can share a drain as default probes would while the long-run probe share stays the same.
     */
    private boolean trialProbe(long bin, double share) {
        trialCredit = Math.min(trialCredit + share, 1.0);
        if (trialLeft > 0 && decisions > trialUntil) trialLeft = 0;
        if (trialLeft > 0 && bin != NO_BIN && bin == trialBin) {
            trialLeft--;
            trialCredit -= 1.0;
            return true;
        }
        if (trialCredit >= 1.0) {
            trialCredit -= 1.0;
            trialBin = bin;
            trialLeft = (bin == NO_BIN) ? 0 : TRIAL_RUN - 1;
            trialUntil = decisions + TRIAL_WINDOW;
            return true;
        }
        return false;
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
        synchronized (this) {
            probe.add(nanos);
            probeFit.add(invG, nanos);
            if (mode == Mode.PROBE) defaultInvG.add(invG);
            reject.add(rejected ? 1.0 : 0.0);
            log = evaluate();
        }
        if (log != null) RTP.log(Level.INFO, log);
    }

    /** One finished full load: wall time from request to chunk; {@code afterProbe} = a probe ran first. */
    public void recordLoad(boolean afterProbe, long nanos) {
        if (nanos < 0L) return;
        String log;
        synchronized (this) {
            (afterProbe ? loadAfterProbe : loadDirect).add(nanos);
            log = evaluate();
        }
        if (log != null) RTP.log(Level.INFO, log);
    }

    public synchronized Mode mode() {
        return mode;
    }

    public synchronized ProbeGovernorRow snapshot() {
        return new ProbeGovernorRow(region, path.label, mode.name(), !warm(),
                reject.mean, ms(probeAtDefault()), ms(loadAfterProbe.mean), ms(loadDirect.mean),
                ms(costProbe), ms(costDirect), z, skip.mean, flips, probe.n, loadDirect.n);
    }

    private boolean warm() {
        return probe.n >= MIN_SAMPLES && loadDirect.n >= MIN_SAMPLES;
    }

    /** {@code 1/g} at which the probe arm is judged: the default-probing group size once seen. */
    private double invGAtDefault() {
        return (defaultInvG.n > 0) ? defaultInvG.mean : probeFit.mx;
    }

    /** Fitted per-chunk probe cost at the default-probing group size. */
    synchronized double probeAtDefault() {
        return (probeFit.n == 0) ? Double.NaN : probeFit.at(invGAtDefault());
    }

    /** Group size the probe arm is judged at ({@code 1 / E[1/g]}). */
    synchronized double groupSizeAtDefault() {
        double x = invGAtDefault();
        return (Double.isNaN(x) || x <= 0.0) ? Double.NaN : 1.0 / x;
    }

    /** Losing-arm share: ~1/(1+|z|), within [MIN_EXPLORE, MAX_EXPLORE]. */
    private double exploreShare() {
        double az = Double.isNaN(z) ? 0.0 : Math.abs(z);
        return Math.max(MIN_EXPLORE, Math.min(MAX_EXPLORE, 1.0 / (1.0 + az)));
    }

    /** Deterministic stride: true on every round(1/share)-th decision. */
    private boolean every(double share) {
        long stride = Math.max(1L, Math.round(1.0 / share));
        return decisions % stride == 0L;
    }

    /** Recompute costs; flip on a significant gap. Returns a log line to emit, or null. */
    private String evaluate() {
        if (!warm()) return summaryIfDue();
        double p = clamp01(reject.mean);
        // Rare after-probe loads (p near 1) give a noisy mean; borrow the direct arm until sampled.
        Ewma tf = (loadAfterProbe.n >= 8) ? loadAfterProbe : loadDirect;
        double x = invGAtDefault();
        costProbe = probeFit.at(x) + (1.0 - p) * tf.mean;
        costDirect = loadDirect.mean;
        double se2 = probeFit.se2At(x)
                + (1.0 - p) * (1.0 - p) * tf.var / tf.neff()
                + tf.mean * tf.mean * p * (1.0 - p) / reject.neff()
                + loadDirect.var / loadDirect.neff();
        double gap = costProbe - costDirect;
        if (se2 > 0.0) {
            z = gap / Math.sqrt(se2);
        } else {
            z = (gap > 0.0) ? Double.POSITIVE_INFINITY : (gap < 0.0) ? Double.NEGATIVE_INFINITY : 0.0;
        }
        sinceFlip++;
        boolean losing = (mode == Mode.PROBE) ? z > Z_SWITCH : z < -Z_SWITCH;
        beyond = losing ? beyond + 1 : 0;
        if (beyond >= SUSTAIN && (flips == 0 || sinceFlip >= WINDOW)) {
            Mode prev = mode;
            Mode next = (mode == Mode.PROBE) ? Mode.SKIP : Mode.PROBE;
            mode = next;
            flips++;
            sinceFlip = 0;
            beyond = 0;
            trialCredit = 0.0;
            trialLeft = 0;
            lastSummary = clock.getAsLong();
            return "[RTP][probe-gov] " + region + "/" + path.label + " flip " + prev + "->" + next + " " + describe();
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
                mode, warm() ? "" : "(warmup)", reject.mean, ms(probeAtDefault()), ms(probe.mean),
                groupSizeAtDefault(), ms(probeFit.slope()), ms(loadAfterProbe.mean),
                ms(loadDirect.mean), ms(costProbe), ms(costDirect), z, skip.mean, probe.n, loadDirect.n, flips);
    }

    private static double ms(double nanos) {
        return nanos / 1_000_000.0;
    }

    private static double clamp01(double v) {
        return Double.isNaN(v) ? 0.0 : Math.max(0.0, Math.min(1.0, v));
    }
}
