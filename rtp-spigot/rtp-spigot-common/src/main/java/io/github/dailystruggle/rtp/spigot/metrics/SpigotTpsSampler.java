package io.github.dailystruggle.rtp.spigot.metrics;

import io.github.dailystruggle.rtp.common.metrics.MetricsBinding;
import io.github.dailystruggle.rtp.common.metrics.MetricsSnapshot;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Local TPS / MSPT sampler for raw Spigot 1.20.1 — the lowest-supported MC
 * version that does <strong>not</strong> expose {@code Server#getTPS()}
 * (Paper-only addition). This {@link MetricsBinding} measures inter-tick
 * deltas itself and reports rolling {@code tps1m / tps5m / tps15m} and
 * {@code mspt}.
 *
 * <p>Algorithm (per {@code METRICS_PLAN.md > Spigot TPS Fallback}):
 * <ul>
 *   <li>{@link #tick()} is invoked once per server tick from a 1-tick
 *       repeating task scheduled on {@code RTP.scheduler}. Each call
 *       records a {@link System#nanoTime()} sample.</li>
 *   <li>The inter-fire delta (in nanoseconds) feeds three exponential
 *       moving averages with windows of 1m / 5m / 15m worth of ticks
 *       (1200 / 6000 / 18000 ticks at the nominal 20 TPS).</li>
 *   <li>TPS is reported as {@code 1e9 / movingAverageNanos}, clamped to
 *       {@code [0.0, 20.0]}. MSPT is reported as the 1m EMA delta in
 *       milliseconds.</li>
 *   <li>Until the very first inter-tick delta is observed (i.e. before
 *       the second {@link #tick()} call), all four getters return
 *       {@link MetricsSnapshot#UNSAMPLED}.</li>
 * </ul>
 *
 * <p>The sampler is lock-free for the read path: getters perform plain
 * volatile reads of three {@code double} EMA fields. {@link #tick()} is
 * intended to be called on a single thread (the platform's main / global
 * scheduler) and updates the EMAs sequentially.
 *
 * <p>The class is testable without Bukkit: the package-private
 * {@link #SpigotTpsSampler(LongSupplier)} constructor accepts an
 * injectable {@link System#nanoTime()} clock.
 */
public final class SpigotTpsSampler implements MetricsBinding {

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
    public SpigotTpsSampler() {
        this(System::nanoTime);
    }

    /** Test seam. */
    SpigotTpsSampler(LongSupplier nanoClock) {
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
        // Raw 1-minute EMA delta in milliseconds. No cap — overruns surface as
        // tickBudgetUtilisation > 1.0 via the snapshot derivation.
        return v / 1e6;
    }

    /**
     * Visible for diagnostics / tests: nominal nanoseconds per tick
     * ({@code 5e7}, i.e. 50 ms at 20 TPS).
     */
    static double nominalTickNanos() { return NOMINAL_TICK_NANOS; }
}
