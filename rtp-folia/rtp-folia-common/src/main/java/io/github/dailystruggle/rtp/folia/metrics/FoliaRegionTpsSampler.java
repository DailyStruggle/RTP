package io.github.dailystruggle.rtp.folia.metrics;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Per-region tick sampler for Folia, mirroring {@code BukkitTpsSampler}'s EMA
 * math (1m / 5m / 15m windows @ 20 nominal TPS) but specialised for a single
 * Folia region thread.
 *
 * <p>Unlike {@code BukkitTpsSampler}, this sampler does not expose
 * {@code playerCount} / {@code softCap} - those are server-wide and are
 * filled by {@code FoliaMetricsBinding} directly from {@code Bukkit}. This
 * type is purely the EMA carrier.
 *
 * <p>Threading: {@link #tick()} is expected to be driven from exactly one
 * Folia region thread (the one owning the region this sampler was created
 * for). The EMA fields are {@code volatile} so reads from other threads
 * (e.g. {@code /rtp info} on the main / global thread) see a recent value
 * without a fence. The {@link #lastNanos} {@link AtomicLong} guards against
 * the (theoretical) case where the region scheduler re-fires the same
 * runnable on a different thread mid-migration.
 *
 * <p>Returns {@link MetricsSnapshot#UNSAMPLED} for {@link #tps1m()} /
 * {@link #mspt()} until the second {@link #tick()} call - matches the
 * Bukkit sampler's documented contract.
 */
final class FoliaRegionTpsSampler {

    /** Nominal Minecraft tick rate. */
    private static final double NOMINAL_TPS = 20.0;
    /** EMA window in ticks for the 1-minute average ({@code 60s * 20tps}). */
    private static final double WINDOW_1M_TICKS = 60.0 * NOMINAL_TPS;
    /** EMA window in ticks for the 5-minute average. */
    private static final double WINDOW_5M_TICKS = 5.0 * 60.0 * NOMINAL_TPS;
    /** EMA window in ticks for the 15-minute average. */
    private static final double WINDOW_15M_TICKS = 15.0 * 60.0 * NOMINAL_TPS;

    private final LongSupplier nanoClock;
    private final AtomicLong lastNanos = new AtomicLong(Long.MIN_VALUE);

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
        ema1m = blend(ema1m, deltaNanos, WINDOW_1M_TICKS);
        ema5m = blend(ema5m, deltaNanos, WINDOW_5M_TICKS);
        ema15m = blend(ema15m, deltaNanos, WINDOW_15M_TICKS);
    }

    private static double blend(double previous, double sampleNanos, double windowTicks) {
        if (Double.isNaN(previous)) return sampleNanos;
        double alpha = 1.0 / windowTicks;
        return previous + alpha * (sampleNanos - previous);
    }

    private static double tpsFromNanos(double emaNanos) {
        if (Double.isNaN(emaNanos) || emaNanos <= 0.0) return MetricsSnapshot.UNSAMPLED;
        double tps = 1e9 / emaNanos;
        if (tps < 0.0) return 0.0;
        if (tps > NOMINAL_TPS) return NOMINAL_TPS;
        return tps;
    }

    double tps1m() { return tpsFromNanos(ema1m); }
    double tps5m() { return tpsFromNanos(ema5m); }
    double tps15m() { return tpsFromNanos(ema15m); }

    double mspt() {
        double v = ema1m;
        if (Double.isNaN(v) || v <= 0.0) return MetricsSnapshot.UNSAMPLED;
        return v / 1e6;
    }

    /** Wall-clock nano of the most recent {@link #tick()}, or {@link Long#MIN_VALUE} if never ticked. */
    long lastTickWallNanos() { return lastTickWallNanos; }
}
