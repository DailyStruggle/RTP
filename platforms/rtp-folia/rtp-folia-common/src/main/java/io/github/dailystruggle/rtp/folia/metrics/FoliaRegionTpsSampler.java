package io.github.dailystruggle.rtp.folia.metrics;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Per-region TPS and MSPT estimator for Folia regional threads.
 *
 * <p>Folia runs tick loops per region without a single global TPS counter.
 * A sampler instance is bound to one region thread. Every tick recorded
 * via {@link #tick()} measures the elapsed wall-clock nanoseconds since
 * the previous tick, clamps it to non-negative values, and folds it into
 * three exponential moving averages (1m, 5m, 15m) using standard Paper
 * smoothing coefficients.
 *
 * <p>Thread safety: {@link #tick()} is called strictly from the region's
 * own thread. Read methods ({@link #tps1m()}, {@link #mspt()}) are safe
 * to invoke from any thread and return the latest snapshot.
 */
final class FoliaRegionTpsSampler {

    /** Nominal Minecraft tick rate in ticks per second. */
    static final double NOMINAL_TPS = 20.0;
    /** Nominal tick interval in nanoseconds (50 ms). */
    static final long NOMINAL_TICK_NANOS = 50_000_000L;

    /**
     * Inter-tick interval threshold in nanoseconds above which the elapsed time
     * represents an idle pause, chunk unload, or rescheduling gap rather than a
     * continuous tick (500 ms = 2.0 TPS). Gaps above this threshold re-seed the
     * previous tick timestamp without blending into the EMA.
     */
    static final long GAP_THRESHOLD_NANOS = 500_000_000L;

    /**
     * Maximum tick delta nanoseconds folded into the EMA (250 ms = 4.0 TPS floor).
     * Prevents transient individual frame overruns from disproportionately depressing
     * the moving average.
     */
    static final double MAX_TICK_DELTA_NANOS = 250_000_000.0;

    /** EMA window in ticks for the 1-minute average ({@code 60 * 20 = 1200}). */
    private static final double WINDOW_1M_TICKS = 60.0 * NOMINAL_TPS;
    /** EMA window in ticks for the 5-minute average. */
    private static final double WINDOW_5M_TICKS = 5.0 * 60.0 * NOMINAL_TPS;
    /** EMA window in ticks for the 15-minute average. */
    private static final double WINDOW_15M_TICKS = 15.0 * 60.0 * NOMINAL_TPS;

    private final LongSupplier nanoClock;
    private final AtomicLong lastNanos = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong sampleCount = new AtomicLong(0L);
    private final long createdWallNanos;

    private volatile double ema1m = Double.NaN;
    private volatile double ema5m = Double.NaN;
    private volatile double ema15m = Double.NaN;

    /** Wall-clock nano of the most recent {@link #tick()}; used for idle eviction. */
    private volatile long lastTickWallNanos = Long.MIN_VALUE;

    FoliaRegionTpsSampler() {
        this(System::nanoTime);
    }

    /** Test seam. */
    FoliaRegionTpsSampler(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.createdWallNanos = nanoClock.getAsLong();
    }

    /**
     * Record one tick of the region this sampler is bound to. Must be
     * called from the owning region's thread. First call seeds only.
     */
    void tick() {
        long now = nanoClock.getAsLong();
        lastTickWallNanos = now;
        long prev = lastNanos.getAndSet(now);
        if (prev == Long.MIN_VALUE) return;
        double deltaNanos = (double) (now - prev);
        if (deltaNanos <= 0.0) return;
        if (deltaNanos > GAP_THRESHOLD_NANOS) {
            // Region unloaded, was idle, or experienced a major rescheduling gap.
            // Treat this tick as a re-seed so the gap duration is not folded into the EMA.
            return;
        }
        sampleCount.incrementAndGet();
        deltaNanos = Math.min(deltaNanos, MAX_TICK_DELTA_NANOS);
        ema1m = blend(ema1m, deltaNanos, WINDOW_1M_TICKS);
        ema5m = blend(ema5m, deltaNanos, WINDOW_5M_TICKS);
        ema15m = blend(ema15m, deltaNanos, WINDOW_15M_TICKS);
    }

    /** Re-seed this sampler on the next tick, discarding any inter-tick gap. */
    void reseed() {
        lastNanos.set(Long.MIN_VALUE);
    }

    private static double blend(double previous, double sampleNanos, double windowTicks) {
        if (Double.isNaN(previous)) return sampleNanos;
        double alpha = 1.0 / windowTicks;
        return previous + alpha * (sampleNanos - previous);
    }

    double tps1m() { return toTps(ema1m); }
    double tps5m() { return toTps(ema5m); }
    double tps15m() { return toTps(ema15m); }

    private static double toTps(double nanosPerTick) {
        if (Double.isNaN(nanosPerTick) || nanosPerTick <= 0.0) return MetricsSnapshot.UNSAMPLED;
        double tps = 1e9 / nanosPerTick;
        return Math.min(NOMINAL_TPS, Math.max(0.0, tps));
    }

    double mspt() {
        double v = ema1m;
        if (Double.isNaN(v) || v <= 0.0) return MetricsSnapshot.UNSAMPLED;
        return v / 1e6;
    }

    /** Wall-clock nano of the most recent {@link #tick()}, or {@link Long#MIN_VALUE} if never ticked. */
    long lastTickWallNanos() { return lastTickWallNanos; }

    /** Valid tick delta samples recorded for this region. */
    long sampleCount() { return sampleCount.get(); }

    /** Wall-clock nano when this sampler was constructed. */
    long createdWallNanos() { return createdWallNanos; }

    /** Age of this sampler in milliseconds. */
    long ageMillis(long nowNanos) {
        if (nowNanos <= createdWallNanos) return 0L;
        return (nowNanos - createdWallNanos) / 1_000_000L;
    }
}
