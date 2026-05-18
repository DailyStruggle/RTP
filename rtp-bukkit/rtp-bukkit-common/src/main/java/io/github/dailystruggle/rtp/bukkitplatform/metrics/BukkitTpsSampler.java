package io.github.dailystruggle.rtp.bukkitplatform.metrics;

import io.github.dailystruggle.metrics.api.MetricsBinding;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Server;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Local TPS/MSPT sampler for raw Spigot 1.20.1 (no {@code Server#getTPS()}).
 * Per METRICS_PLAN > Spigot TPS Fallback: {@link #tick()} feeds inter-tick
 * deltas into 1m/5m/15m EMAs (1200/6000/18000 ticks @ 20 TPS); TPS = 1e9/ema
 * clamped to [0,20], MSPT = 1m EMA in ms. Returns {@link MetricsSnapshot#UNSAMPLED}
 * before the second tick. Lock-free reads; {@link #tick()} is single-threaded.
 */
public final class BukkitTpsSampler implements MetricsBinding {

    /** Nominal Minecraft tick rate. */
    private static final double NOMINAL_TPS = 20.0;
    /** Nominal nanoseconds per tick at 20 TPS (50 ms). */
    private static final double NOMINAL_TICK_NANOS = 1e9 / NOMINAL_TPS;

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

    /** Production constructor — uses {@link System#nanoTime()}. */
    public BukkitTpsSampler() {
        this(System::nanoTime);
    }

    /** Test seam. */
    BukkitTpsSampler(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /**
     * Record one tick. Must be called from a single tick-thread (the
     * platform's main scheduler). The first call only seeds the timestamp
     * — no EMA is produced until the second call.
     */
    public void tick() {
        long now = nanoClock.getAsLong();
        long prev = lastNanos.getAndSet(now);
        if (prev == Long.MIN_VALUE) return; // first sample: seed only

        double deltaNanos = (double) (now - prev);
        if (deltaNanos <= 0.0) return; // monotonic guard; ignore non-progress

        // Bootstrap each EMA on first valid delta, otherwise blend.
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

    @Override
    public double tps1m() { return tpsFromNanos(ema1m); }

    @Override
    public double tps5m() { return tpsFromNanos(ema5m); }

    @Override
    public double tps15m() { return tpsFromNanos(ema15m); }

    @Override
    public double mspt() {
        double v = ema1m;
        if (Double.isNaN(v) || v <= 0.0) return MetricsSnapshot.UNSAMPLED;
        // Raw 1-minute EMA delta in milliseconds. No cap - overruns surface as
        // tickBudgetUtilisation > 1.0 via the snapshot derivation.
        return v / 1e6;
    }

    @Override
    public int playerCount() {
        try {
            Server s = Bukkit.getServer();
            return s == null ? 0 : s.getOnlinePlayers().size();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @Override
    public int softCap() {
        try {
            Server s = Bukkit.getServer();
            return s == null ? 0 : s.getMaxPlayers();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Visible for diagnostics / tests: nominal nanoseconds per tick
     * ({@code 5e7}, i.e. 50 ms at 20 TPS).
     */
    static double nominalTickNanos() { return NOMINAL_TICK_NANOS; }
}
