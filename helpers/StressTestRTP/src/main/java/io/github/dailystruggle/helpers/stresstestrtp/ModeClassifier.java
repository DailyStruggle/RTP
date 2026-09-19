package io.github.dailystruggle.helpers.stresstestrtp;

import java.util.Arrays;

/**
 * Per-phase teleport-mode classifier.
 *
 * <p>An RTP-style plugin either serves a request from a pre-computed location
 * (fast mode) or selects and verifies a destination on demand (cold mode). The
 * resulting latency population is bimodal, so a bare mean over it is
 * meaningless and a p50 taken across a near-50/50 mixture is maximally
 * unstable. This class splits the population and reports each mode separately.
 *
 * <p><b>Observable signals only.</b> Classification uses exactly two per-attempt
 * measurements that are already recorded in the per-attempt CSV - the attempt
 * latency and the attributed <em>selection</em> chunk loads (attributed loads
 * minus the post-teleport arrival ring). No competitor-internal cache state is
 * read or guessed, so every {@code served_mode} value in the CSV is
 * re-derivable from the same file without re-running the benchmark.
 *
 * <p><b>Threshold is derived, not hardcoded.</b> The fast/cold boundary is an
 * Otsu two-class split over log10 latency, computed from the phase's own
 * attempt population and written into the phases CSV
 * ({@code mode_threshold_ms}) so it is inspectable. Rows written before the
 * population reaches {@link #MIN_SAMPLES} carry {@code UNKNOWN} and a row-local
 * threshold of {@link #NO_DATA}; their latency and selection-chunk columns are
 * still present, so they can be reclassified offline against the final
 * threshold.
 *
 * <p><b>Direct readings never calibrate inferred ones.</b> A direct mode read
 * is available only for this plugin's own arm; it is recorded in its own
 * column and is deliberately excluded from threshold estimation and from the
 * inferred per-mode percentiles.
 *
 * <p><b>Off-path.</b> Recording an attempt touches primitive fields and two
 * amortized-growth primitive arrays. Nothing is allocated per attempt, no
 * snapshot is copied, and the Otsu recomputation is throttled to once every
 * {@link #RECOMPUTE_EVERY} attempts over a fixed-size histogram.
 */
public final class ModeClassifier {

    /** Explicit "not available" value for every numeric column this class
     *  feeds. Never write a blank or a zero for a missing measurement: a
     *  competitor arm that could not be classified must not read as an arm
     *  whose fast-mode fraction was measured to be zero. */
    public static final long NO_DATA = -1L;

    /** Classified serving mode of one attempt. */
    public enum Mode {
        /** Completed inside the fast lobe with ~no selection chunk work. */
        FAST,
        /** Completed outside the fast lobe, or did selection chunk work. */
        COLD,
        /** Not classifiable yet (population below {@link #MIN_SAMPLES}) or no
         *  finite latency. Distinct from both modes; never counted as either. */
        UNKNOWN;

        /** CSV token. {@link #UNKNOWN} is written literally, not blank, so a
         *  reader cannot mistake it for a missing column. */
        public String token() { return name(); }
    }

    /** Provenance of a {@code served_mode} value. */
    public enum Source {
        /** Read directly from the plugin under test (this plugin's arm only). */
        DIRECT,
        /** Derived from latency + attributed selection chunk loads. */
        INFERRED,
        /** No reading available. */
        NONE;

        public String token() { return name(); }
    }

    /** Fixed before any measurement was read (see checklist gate G-MODE-1):
     *  below this many attempts in a phase the split is not estimated and
     *  every row is UNKNOWN. */
    public static final int MIN_SAMPLES = 32;
    /** Fixed before any measurement was read: an attempt is only eligible for
     *  FAST if its attributed selection chunk loads are at or below this.
     *  Cold selection costs ~1 selection load; a cache-served attempt costs 0. */
    public static final long FAST_MAX_SELECTION_CHUNKS = 0L;
    /** Otsu recomputation cadence, in attempts. */
    private static final int RECOMPUTE_EVERY = 32;

    /** log10(ms) histogram: 512 bins over [0, 5) i.e. 1 ms .. 100 s. */
    private static final int BINS = 512;
    private static final double LOG_LO = 0.0d;
    private static final double LOG_HI = 5.0d;

    private final long[] hist = new long[BINS];
    private long[] latencies = new long[1024];
    private long[] selections = new long[1024];
    private int n = 0;
    private int unknownCount = 0;
    private int directFast = 0;
    private int directCold = 0;

    /** Current inferred boundary in ms; {@link #NO_DATA} until estimated. */
    private long thresholdMs = NO_DATA;

    /** Resets to an empty population. Called at phase start. */
    public synchronized void reset() {
        Arrays.fill(hist, 0L);
        n = 0;
        unknownCount = 0;
        directFast = 0;
        directCold = 0;
        thresholdMs = NO_DATA;
    }

    /**
     * Records one finished attempt and returns its inferred mode against the
     * threshold known at this instant. The returned value is what goes into
     * the per-attempt CSV; the phase row carries the final threshold, and the
     * per-attempt latency / selection columns make any disagreement between
     * the two auditable.
     *
     * @param latencyMs  attempt latency, {@link #NO_DATA} when unknown
     * @param selectionChunks attributed selection chunk loads,
     *                        {@link #NO_DATA} when no counter was wired
     */
    public synchronized Mode record(long latencyMs, long selectionChunks) {
        if (latencyMs < 0) {
            unknownCount++;
            return Mode.UNKNOWN;
        }
        if (n == latencies.length) {
            latencies = Arrays.copyOf(latencies, n * 2);
            selections = Arrays.copyOf(selections, n * 2);
        }
        latencies[n] = latencyMs;
        selections[n] = selectionChunks;
        n++;
        hist[binOf(latencyMs)]++;
        if (thresholdMs < 0 ? n >= MIN_SAMPLES : n % RECOMPUTE_EVERY == 0) {
            thresholdMs = otsuThresholdMs();
        }
        Mode m = classify(latencyMs, selectionChunks, thresholdMs);
        if (m == Mode.UNKNOWN) unknownCount++;
        return m;
    }

    /**
     * Records a direct mode reading for this plugin's own arm. Kept apart from
     * {@link #record}: a direct reading is reported, never used to place or
     * validate the inferred boundary.
     */
    public synchronized void recordDirect(Mode direct) {
        if (direct == Mode.FAST) directFast++;
        else if (direct == Mode.COLD) directCold++;
    }

    /** Threshold in effect right now, {@link #NO_DATA} before estimation. */
    public synchronized long thresholdMs() { return thresholdMs; }

    /** Pure classification rule, exposed so offline re-derivation from the CSV
     *  can reproduce a row exactly. A negative threshold means "unestimated". */
    public static Mode classify(long latencyMs, long selectionChunks, long thresholdMs) {
        if (latencyMs < 0 || thresholdMs < 0) return Mode.UNKNOWN;
        if (latencyMs > thresholdMs) return Mode.COLD;
        // Inside the fast lobe. Selection chunk work contradicts a cache hit;
        // an unavailable count (NO_DATA) leaves the latency reading to stand
        // alone, which the CSV records explicitly as -1 so it is not read as 0.
        if (selectionChunks > FAST_MAX_SELECTION_CHUNKS) return Mode.COLD;
        return Mode.FAST;
    }

    /** Immutable per-phase summary. All counts use {@link #NO_DATA} for
     *  "not available"; fractions use {@code -1.0}. */
    public static final class Summary {
        public final long thresholdMs;
        public final String thresholdMethod;
        public final long classified;
        public final long fastCount;
        public final long coldCount;
        public final long unknownCount;
        public final double fastFraction;
        public final long fastP50, fastP95, fastP99;
        public final long coldP50, coldP95, coldP99;
        public final long directFastCount;
        public final long directColdCount;
        public final double directFastFraction;

        Summary(long thresholdMs, String thresholdMethod, long classified,
                long fastCount, long coldCount, long unknownCount, double fastFraction,
                long fastP50, long fastP95, long fastP99,
                long coldP50, long coldP95, long coldP99,
                long directFastCount, long directColdCount, double directFastFraction) {
            this.thresholdMs = thresholdMs;
            this.thresholdMethod = thresholdMethod;
            this.classified = classified;
            this.fastCount = fastCount;
            this.coldCount = coldCount;
            this.unknownCount = unknownCount;
            this.fastFraction = fastFraction;
            this.fastP50 = fastP50; this.fastP95 = fastP95; this.fastP99 = fastP99;
            this.coldP50 = coldP50; this.coldP95 = coldP95; this.coldP99 = coldP99;
            this.directFastCount = directFastCount;
            this.directColdCount = directColdCount;
            this.directFastFraction = directFastFraction;
        }
    }

    /**
     * Final summary over everything recorded so far. Read-only, so it is safe
     * for both the phase-closing row and the periodic partial-phase snapshot.
     * All attempts are reclassified against the final threshold, so the phase
     * row is internally consistent even though early per-attempt rows were
     * written against a provisional one.
     */
    public synchronized Summary summarise() {
        long finalThreshold = n >= MIN_SAMPLES ? otsuThresholdMs() : NO_DATA;
        String method = finalThreshold >= 0 ? "OTSU_LOG10_LATENCY" : "INSUFFICIENT_SAMPLES";
        long directTotal = (long) directFast + directCold;
        double directFraction = directTotal > 0 ? (double) directFast / directTotal : -1.0d;
        if (finalThreshold < 0) {
            return new Summary(NO_DATA, method, NO_DATA, NO_DATA, NO_DATA,
                    unknownCount, -1.0d,
                    NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA, NO_DATA,
                    directFast, directCold, directFraction);
        }
        int fastN = 0;
        for (int i = 0; i < n; i++) {
            if (classify(latencies[i], selections[i], finalThreshold) == Mode.FAST) fastN++;
        }
        long[] fast = new long[fastN];
        long[] cold = new long[n - fastN];
        int fi = 0, ci = 0;
        for (int i = 0; i < n; i++) {
            if (classify(latencies[i], selections[i], finalThreshold) == Mode.FAST) fast[fi++] = latencies[i];
            else cold[ci++] = latencies[i];
        }
        Arrays.sort(fast);
        Arrays.sort(cold);
        double fastFraction = n > 0 ? (double) fastN / n : -1.0d;
        return new Summary(finalThreshold, method, n, fastN, n - fastN, unknownCount, fastFraction,
                pct(fast, 50), pct(fast, 95), pct(fast, 99),
                pct(cold, 50), pct(cold, 95), pct(cold, 99),
                directFast, directCold, directFraction);
    }

    /** Inclusive percentile over a pre-sorted array; {@link #NO_DATA} if empty. */
    private static long pct(long[] sorted, double p) {
        if (sorted.length == 0) return NO_DATA;
        int idx = (int) Math.min(sorted.length - 1L,
                Math.max(0L, Math.round((p / 100.0d) * (sorted.length - 1))));
        return sorted[idx];
    }

    private static int binOf(long latencyMs) {
        double v = Math.log10(Math.max(1L, latencyMs));
        int b = (int) ((v - LOG_LO) / (LOG_HI - LOG_LO) * BINS);
        if (b < 0) return 0;
        return Math.min(b, BINS - 1);
    }

    /** Upper edge of a bin, in ms - the boundary is inclusive on the fast side. */
    private static long binUpperMs(int bin) {
        double logHi = LOG_LO + ((bin + 1.0d) / BINS) * (LOG_HI - LOG_LO);
        return Math.max(0L, Math.round(Math.pow(10.0d, logHi)));
    }

    /**
     * Otsu two-class split over the log10-latency histogram: picks the bin
     * boundary maximising between-class variance. Degenerate (single-lobe)
     * populations still yield a boundary; the reported fast fraction then
     * sits near 0 or 1, which is the honest reading - not a claim of
     * bimodality.
     */
    private long otsuThresholdMs() {
        long total = 0L;
        double sumAll = 0.0d;
        for (int b = 0; b < BINS; b++) {
            total += hist[b];
            sumAll += (double) b * hist[b];
        }
        if (total <= 0L) return NO_DATA;
        long wB = 0L;
        double sumB = 0.0d;
        double best = -1.0d;
        int bestBin = -1;
        for (int b = 0; b < BINS - 1; b++) {
            wB += hist[b];
            if (wB == 0L) continue;
            long wF = total - wB;
            if (wF == 0L) break;
            sumB += (double) b * hist[b];
            double mB = sumB / wB;
            double mF = (sumAll - sumB) / wF;
            double between = (double) wB * wF * (mB - mF) * (mB - mF);
            if (between > best) {
                best = between;
                bestBin = b;
            }
        }
        if (bestBin < 0) return NO_DATA;
        return binUpperMs(bestBin);
    }
}
