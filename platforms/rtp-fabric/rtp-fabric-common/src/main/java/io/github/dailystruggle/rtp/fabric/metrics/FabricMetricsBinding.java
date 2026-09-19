package io.github.dailystruggle.rtp.fabric.metrics;

import io.github.dailystruggle.metrics.api.MetricsBinding;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Fabric-flavoured {@link MetricsBinding}.
 *
 * <p>Unlike Folia, Fabric (vanilla server architecture) runs a single
 * server tick loop, so there is no per-region partitioning. This binding
 * mirrors the EMA approach used by {@code BukkitTpsSampler} in
 * {@code rtp-bukkit-common}: inter-tick deltas are blended into 1m / 5m
 * / 15m exponential moving
 * averages (1200 / 6000 / 18000 ticks at 20 TPS), and TPS = 1e9 / EMA
 * clamped to {@code [0, NOMINAL_TPS]}.
 *
 * <p><b>TPS and MSPT are measured from two different sources.</b> The
 * vanilla server loop sleeps after each tick to hold the nominal rate, so
 * the inter-tick wall-clock interval is {@code max(work, 50ms)} - a valid
 * TPS estimator but floored at 50 ms. Deriving {@link #mspt()} from it
 * pinned the reading at ~50 ms on a healthy server and made it incapable
 * of ever falling below that floor. MSPT therefore blends a separate
 * work-duration sample (the server's own average tick time, supplied via
 * {@code tickWorkNanosSupplier}), and reports
 * {@link MetricsSnapshot#UNSAMPLED} when no work source is reachable
 * rather than the misleading interval. Tick-budget overrun surfaces as
 * {@code tickBudgetUtilisation > 1.0} via the snapshot derivation.
 *
 * <p>Per-region surface ({@link #foliaRegions()}) inherits the default
 * {@link MetricsBinding} empty-list - Fabric is a single-region runtime
 * and its tick rate is fully described by the four scalar fields.
 *
 * <p>{@link #tick(long)} is intended to be driven once per
 * {@code END_SERVER_TICK} from {@code FabricEventBridge.onEndServerTick}.
 * The first call only seeds the timestamp; no EMA is produced until the
 * second call. The clock source is supplied via the test-seam constructor
 * to keep unit tests deterministic; production uses {@link System#nanoTime()}
 * via {@link #tick()}.
 *
 * <p>{@link #playerCount()} and {@link #softCap()} are supplied via
 * {@link IntSupplier} hooks so the binding stays free of any direct
 * {@code MinecraftServer} or {@code net.minecraft} imports - the Fabric
 * event bridge wires reflective accessors at construction time (matching
 * the existing {@code resolveOnlinePlayerCount} / {@code resolveMaxPlayers}
 * helpers in {@code FabricEventBridge}). This also lets the test harness
 * drive the binding without a server runtime.
 *
 * <p>All public accessors are non-blocking and never throw on the calling
 * thread; failures degrade to {@link MetricsSnapshot#UNSAMPLED} (scalars)
 * or {@code 0} (counts).
 */
public final class FabricMetricsBinding implements MetricsBinding {

    /** Nominal Minecraft tick rate. */
    private static final double NOMINAL_TPS = 20.0;

    /** EMA window in ticks for the 1-minute average ({@code 60s * 20tps}). */
    private static final double WINDOW_1M_TICKS = 60.0 * NOMINAL_TPS;
    /** EMA window in ticks for the 5-minute average. */
    private static final double WINDOW_5M_TICKS = 5.0 * 60.0 * NOMINAL_TPS;
    /** EMA window in ticks for the 15-minute average. */
    private static final double WINDOW_15M_TICKS = 15.0 * 60.0 * NOMINAL_TPS;

    /**
     * EMA window in ticks for MSPT (~1s). Deliberately far shorter than the
     * TPS windows: the upstream work sample is already a smoothed mean
     * (Mojang averages over 100 ticks), so a long outer window would only
     * double-smooth and make the reading sluggish to recover.
     */
    private static final double MSPT_WINDOW_TICKS = 20.0;

    /** Sentinel for "no work sample available this tick". */
    private static final long NO_WORK_SAMPLE = -1L;

    private final LongSupplier nanoClock;
    private final IntSupplier playerCountSupplier;
    private final IntSupplier softCapSupplier;
    private final LongSupplier tickWorkNanosSupplier;

    private final AtomicLong lastNanos = new AtomicLong(Long.MIN_VALUE);

    private volatile double ema1m = Double.NaN;
    private volatile double ema5m = Double.NaN;
    private volatile double ema15m = Double.NaN;

    /** EMA of per-tick work duration in nanos; drives {@link #mspt()}. */
    private volatile double msptEmaNanos = Double.NaN;

    /**
     * Production constructor - uses {@link System#nanoTime()} and
     * caller-supplied count suppliers.
     *
     * @param playerCountSupplier {@code () ->} online-player count (e.g.
     *                            reflective {@code MinecraftServer#getPlayerCount}).
     *                            May not be {@code null}.
     * @param softCapSupplier     {@code () ->} configured player cap (e.g.
     *                            reflective {@code MinecraftServer#getMaxPlayers}).
     *                            May not be {@code null}.
     */
    public FabricMetricsBinding(IntSupplier playerCountSupplier, IntSupplier softCapSupplier) {
        this(playerCountSupplier, softCapSupplier, () -> NO_WORK_SAMPLE);
    }

    /**
     * Production constructor with an explicit per-tick work-duration source.
     *
     * @param playerCountSupplier {@code () ->} online-player count.
     * @param softCapSupplier     {@code () ->} configured player cap.
     * @param tickWorkNanosSupplier {@code () ->} average in-tick work duration
     *                              in nanoseconds (e.g. reflective
     *                              {@code MinecraftServer#getAverageTickTimeNanos}).
     *                              Must return {@code <= 0} when no sample is
     *                              available; {@link #mspt()} then reports
     *                              {@link MetricsSnapshot#UNSAMPLED}.
     */
    public FabricMetricsBinding(IntSupplier playerCountSupplier,
                                IntSupplier softCapSupplier,
                                LongSupplier tickWorkNanosSupplier) {
        this(System::nanoTime, playerCountSupplier, softCapSupplier, tickWorkNanosSupplier);
    }

    /** Test seam. */
    FabricMetricsBinding(LongSupplier nanoClock,
                         IntSupplier playerCountSupplier,
                         IntSupplier softCapSupplier) {
        this(nanoClock, playerCountSupplier, softCapSupplier, () -> NO_WORK_SAMPLE);
    }

    /** Test seam. */
    FabricMetricsBinding(LongSupplier nanoClock,
                         IntSupplier playerCountSupplier,
                         IntSupplier softCapSupplier,
                         LongSupplier tickWorkNanosSupplier) {
        if (nanoClock == null) throw new IllegalArgumentException("nanoClock must not be null");
        if (playerCountSupplier == null) throw new IllegalArgumentException("playerCountSupplier must not be null");
        if (softCapSupplier == null) throw new IllegalArgumentException("softCapSupplier must not be null");
        if (tickWorkNanosSupplier == null) throw new IllegalArgumentException("tickWorkNanosSupplier must not be null");
        this.nanoClock = nanoClock;
        this.playerCountSupplier = playerCountSupplier;
        this.softCapSupplier = softCapSupplier;
        this.tickWorkNanosSupplier = tickWorkNanosSupplier;
    }

    /**
     * Record one tick from the server thread (production entry point).
     * Delegates to {@link #tick(long)} with the binding's clock source.
     */
    public void tick() {
        tick(nanoClock.getAsLong());
    }

    /**
     * Record one tick using a caller-supplied timestamp. Visible for the
     * event bridge and tests; production callers should prefer {@link #tick()}.
     */
    public void tick(long nowNanos) {
        long work;
        try {
            work = tickWorkNanosSupplier.getAsLong();
        } catch (Throwable ignored) {
            work = NO_WORK_SAMPLE; // observability must never throw on the tick thread
        }
        tick(nowNanos, work);
    }

    /**
     * Record one tick with an explicit work duration. The interval
     * {@code nowNanos - previous} feeds the TPS EMAs; {@code workNanos} feeds
     * the MSPT EMA independently. Pass {@code <= 0} for {@code workNanos} when
     * no work sample is available - MSPT is then left unsampled rather than
     * being contaminated with the sleep-padded interval.
     *
     * @param nowNanos  monotonic timestamp of this tick boundary
     * @param workNanos in-tick work duration in nanoseconds, or {@code <= 0}
     */
    public void tick(long nowNanos, long workNanos) {
        if (workNanos > 0L) {
            msptEmaNanos = blend(msptEmaNanos, (double) workNanos, MSPT_WINDOW_TICKS);
        }

        long prev = lastNanos.getAndSet(nowNanos);
        if (prev == Long.MIN_VALUE) return; // first sample: seed only

        double deltaNanos = (double) (nowNanos - prev);
        if (deltaNanos <= 0.0) return; // monotonic guard; ignore non-progress

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
        double v = msptEmaNanos;
        if (Double.isNaN(v) || v <= 0.0) return MetricsSnapshot.UNSAMPLED;
        return v / 1e6;
    }

    @Override
    public int playerCount() {
        try {
            int v = playerCountSupplier.getAsInt();
            return v < 0 ? 0 : v;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @Override
    public int softCap() {
        try {
            int v = softCapSupplier.getAsInt();
            return v < 0 ? 0 : v;
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
