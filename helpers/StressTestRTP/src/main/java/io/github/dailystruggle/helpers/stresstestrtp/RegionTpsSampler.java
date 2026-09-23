package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Folia per-region TPS sampler.
 *
 * <p><b>Why.</b> Folia has no server-wide TPS: every region ticks on its own
 * thread and keeps its own counters. {@code Server#getTPS()} throws there, so
 * {@link TpsMsptHeapSampler} falls back to a wall-clock timer on the
 * <em>global region</em> scheduler. That timer measures only the global
 * region (weather, time, console), which is never where a teleport lands,
 * so a run can saturate every player region while the {@code tps} column
 * reads 20.0. The dips it does show are global-region hiccups, not load.
 *
 * <p><b>Method.</b> Folia exposes {@code Server#getRegionTPS(World, int, int)}
 * returning {@code [5s, 15s, 1m, 5m, 15m]} for the region that owns a chunk,
 * or {@code null} when no region exists there. On a fixed async period this
 * sampler reads each online player's block position (a plain field read,
 * the same read the position-poll fallback already performs off-region) and
 * asks for the owning region's TPS. Regions are identified by the sample
 * value's identity only - the API gives no region id - so a phase aggregate
 * is over <em>player-region samples</em>, not distinct regions. That is the
 * right population for a teleport benchmark: it is the set of regions the
 * dispatched players actually occupy.
 *
 * <p>Off Folia (or when the method is absent, which is any Folia build
 * before the region-TPS API) nothing is scheduled and every accessor returns
 * {@link #NO_DATA}, so the columns stay at the not-measured sentinel rather
 * than duplicating the server-wide figure under a misleading name.
 *
 * <p>Nothing here touches a region thread, allocates per tick, or blocks.
 */
public final class RegionTpsSampler {

    /** Sentinel for every unmeasured numeric this class publishes. */
    public static final double NO_DATA = -1.0;

    /** Index into the Folia array: 5-second window. Short enough to see a
     *  dip inside a 60 s phase, long enough not to alias a single slow tick. */
    private static final int WINDOW_5S = 0;
    /** Index into the Folia array: 1-minute window. */
    private static final int WINDOW_1M = 2;

    private final Plugin plugin;
    private final long periodMs;
    private final Method getRegionTps;

    private Object taskHandle = null;

    /** Latest 5 s reading per player, refreshed every period. Read on the
     *  dispatch path via {@link #latest5s(UUID)}; never iterated on-tick. */
    private final Map<UUID, Double> latest5s = new ConcurrentHashMap<>();

    // Phase aggregates over player-region samples. Doubles are stored as
    // raw long bits so the accumulators stay lock-free; the min is kept as
    // a bit pattern too because TPS is non-negative and finite, for which
    // Double.compare order equals raw-bit order.
    private final AtomicLong phaseSamples = new AtomicLong();
    private final AtomicLong phaseSum5sBits = new AtomicLong(Double.doubleToRawLongBits(0.0));
    private final AtomicLong phaseMin5sBits = new AtomicLong(Double.doubleToRawLongBits(Double.MAX_VALUE));
    private final AtomicLong phaseMin1mBits = new AtomicLong(Double.doubleToRawLongBits(Double.MAX_VALUE));
    /** Samples in the phase at or below the threshold in
     *  {@link #BELOW_TARGET_TPS}; a count, so a brief dip cannot hide in a mean. */
    private final AtomicLong phaseBelowTarget = new AtomicLong();

    /** A 5 s region TPS at or under this is counted as a below-target sample.
     *  Fixed before any run was read; stated in the schema rather than tuned. */
    public static final double BELOW_TARGET_TPS = 19.0;

    public RegionTpsSampler(Plugin plugin, long periodMs) {
        this.plugin = plugin;
        this.periodMs = Math.max(50L, periodMs);
        this.getRegionTps = resolve();
    }

    /** Resolves {@code Server#getRegionTPS(World,int,int)} once. Null off
     *  Folia or on a Folia build predating the API. */
    private static Method resolve() {
        if (!Sched.isFolia()) return null;
        try {
            return Bukkit.getServer().getClass().getMethod("getRegionTPS", World.class, int.class, int.class);
        } catch (NoSuchMethodException | SecurityException e) {
            return null;
        }
    }

    /** True iff per-region TPS can be read on this server. */
    public boolean available() { return getRegionTps != null; }

    public void start() {
        if (taskHandle != null || getRegionTps == null) return;
        taskHandle = Sched.runAsyncTimer(plugin, this::sample, periodMs);
    }

    public void stop() {
        Sched.cancel(taskHandle);
        taskHandle = null;
    }

    /** Clears the phase accumulators. Called from {@code beginPhase}. */
    public void resetPhase() {
        phaseSamples.set(0L);
        phaseSum5sBits.set(Double.doubleToRawLongBits(0.0));
        phaseMin5sBits.set(Double.doubleToRawLongBits(Double.MAX_VALUE));
        phaseMin1mBits.set(Double.doubleToRawLongBits(Double.MAX_VALUE));
        phaseBelowTarget.set(0L);
    }

    private void sample() {
        Method m = getRegionTps;
        if (m == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            double[] tps = readRegionTps(m, p);
            if (tps == null || tps.length <= WINDOW_1M) continue;
            double t5 = tps[WINDOW_5S];
            double t1m = tps[WINDOW_1M];
            if (!(t5 >= 0.0) || !(t1m >= 0.0)) continue; // NaN or negative: unusable
            latest5s.put(p.getUniqueId(), t5);
            phaseSamples.incrementAndGet();
            addToSum(t5);
            minInto(phaseMin5sBits, t5);
            minInto(phaseMin1mBits, t1m);
            if (t5 <= BELOW_TARGET_TPS) phaseBelowTarget.incrementAndGet();
        }
    }

    private static double[] readRegionTps(Method m, Player p) {
        try {
            Location loc = p.getLocation();
            World w = loc.getWorld();
            if (w == null) return null;
            Object res = m.invoke(Bukkit.getServer(), w, loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
            return res instanceof double[] arr ? arr : null;
        } catch (Throwable t) {
            return null; // player mid-teleport or region gone: skip this sample
        }
    }

    private void addToSum(double v) {
        long prev, next;
        do {
            prev = phaseSum5sBits.get();
            next = Double.doubleToRawLongBits(Double.longBitsToDouble(prev) + v);
        } while (!phaseSum5sBits.compareAndSet(prev, next));
    }

    private static void minInto(AtomicLong bits, double v) {
        long candidate = Double.doubleToRawLongBits(v);
        long prev;
        do {
            prev = bits.get();
            if (Double.longBitsToDouble(prev) <= v) return;
        } while (!bits.compareAndSet(prev, candidate));
    }

    // -----------------------------------------------------------------
    // Readings
    // -----------------------------------------------------------------

    /** Latest 5 s TPS of the region owning {@code playerId}, or {@link #NO_DATA}. */
    public double latest5s(UUID playerId) {
        if (getRegionTps == null || playerId == null) return NO_DATA;
        Double v = latest5s.get(playerId);
        return v == null ? NO_DATA : v;
    }

    /** Player-region samples taken in the phase; -1 when not measured. */
    public long phaseSamples() {
        return getRegionTps == null ? -1L : phaseSamples.get();
    }

    /** Lowest 5 s region TPS seen in the phase, or {@link #NO_DATA}. */
    public double phaseMin5s() {
        if (getRegionTps == null || phaseSamples.get() == 0L) return NO_DATA;
        return Double.longBitsToDouble(phaseMin5sBits.get());
    }

    /** Mean 5 s region TPS over the phase's samples, or {@link #NO_DATA}. */
    public double phaseMean5s() {
        long n = phaseSamples.get();
        if (getRegionTps == null || n == 0L) return NO_DATA;
        return Double.longBitsToDouble(phaseSum5sBits.get()) / n;
    }

    /** Lowest 1 m region TPS seen in the phase, or {@link #NO_DATA}. */
    public double phaseMin1m() {
        if (getRegionTps == null || phaseSamples.get() == 0L) return NO_DATA;
        return Double.longBitsToDouble(phaseMin1mBits.get());
    }

    /** Fraction of phase samples at or under {@link #BELOW_TARGET_TPS}, or
     *  {@link #NO_DATA}. A dip that a mean would absorb shows up here. */
    public double phaseBelowTargetFraction() {
        long n = phaseSamples.get();
        if (getRegionTps == null || n == 0L) return NO_DATA;
        return (double) phaseBelowTarget.get() / n;
    }
}
