package io.github.dailystruggle.helpers.stresstestrtp;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Folia per-region TPS sampler via {@link StressHost#regionTps(String, int, int)}
 * (ADR-122 Decision 1 & 2).
 */
public final class RegionTpsSampler {

    public static final double NO_DATA = -1.0;

    private static final int WINDOW_5S = 0;
    private static final int WINDOW_1M = 2;

    private final StressHost plugin;
    private final long periodMs;
    private final boolean available;

    private Object taskHandle = null;

    private final Map<UUID, Double> latest5s = new ConcurrentHashMap<>();

    private final AtomicLong phaseSamples = new AtomicLong();
    private final AtomicLong phaseSum5sBits = new AtomicLong(Double.doubleToRawLongBits(0.0));
    private final AtomicLong phaseMin5sBits = new AtomicLong(Double.doubleToRawLongBits(Double.MAX_VALUE));
    private final AtomicLong phaseMin1mBits = new AtomicLong(Double.doubleToRawLongBits(Double.MAX_VALUE));
    private final AtomicLong phaseBelowTarget = new AtomicLong();

    public static final double BELOW_TARGET_TPS = 19.0;

    public RegionTpsSampler(StressHost plugin, long periodMs) {
        this.plugin = plugin;
        this.periodMs = Math.max(50L, periodMs);
        this.available = plugin != null && plugin.isFolia();
    }

    public boolean available() { return available; }

    public void start() {
        if (taskHandle != null || !available) return;
        taskHandle = Sched.runAsyncTimer(plugin, this::sample, periodMs);
    }

    public void stop() {
        Sched.cancel(taskHandle);
        taskHandle = null;
    }

    public void resetPhase() {
        phaseSamples.set(0L);
        phaseSum5sBits.set(Double.doubleToRawLongBits(0.0));
        phaseMin5sBits.set(Double.doubleToRawLongBits(Double.MAX_VALUE));
        phaseMin1mBits.set(Double.doubleToRawLongBits(Double.MAX_VALUE));
        phaseBelowTarget.set(0L);
    }

    private void sample() {
        if (!available || plugin == null) return;
        for (StressPlayer p : plugin.onlinePlayers()) {
            double[] tps = readRegionTps(p);
            if (tps == null || tps.length <= WINDOW_1M) continue;
            double t5 = tps[WINDOW_5S];
            double t1m = tps[WINDOW_1M];
            if (Double.isNaN(t5) || t5 < 0.0 || Double.isNaN(t1m) || t1m < 0.0) continue;
            latest5s.put(p.uuid(), t5);
            phaseSamples.incrementAndGet();
            addToSum(t5);
            minInto(phaseMin5sBits, t5);
            minInto(phaseMin1mBits, t1m);
            if (t5 <= BELOW_TARGET_TPS) phaseBelowTarget.incrementAndGet();
        }
    }

    @SuppressWarnings("java:S1168")
    private double[] readRegionTps(StressPlayer p) {
        try {
            StressLocation loc = p.location();
            if (loc == null || loc.world().isEmpty()) return null;
            return plugin.regionTps(loc.world(), loc.chunkX(), loc.chunkZ());
        } catch (Throwable t) {
            return null;
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

    public double latest5s(UUID playerId) {
        if (!available || playerId == null) return NO_DATA;
        Double v = latest5s.get(playerId);
        return v == null ? NO_DATA : v;
    }

    public long phaseSamples() {
        return !available ? -1L : phaseSamples.get();
    }

    public double phaseMin5s() {
        if (!available || phaseSamples.get() == 0L) return NO_DATA;
        return Double.longBitsToDouble(phaseMin5sBits.get());
    }

    public double phaseMean5s() {
        long n = phaseSamples.get();
        if (!available || n == 0L) return NO_DATA;
        return Double.longBitsToDouble(phaseSum5sBits.get()) / n;
    }

    public double phaseMin1m() {
        if (!available || phaseSamples.get() == 0L) return NO_DATA;
        return Double.longBitsToDouble(phaseMin1mBits.get());
    }

    public double phaseBelowTargetFraction() {
        long n = phaseSamples.get();
        if (!available || n == 0L) return NO_DATA;
        return (double) phaseBelowTarget.get() / n;
    }
}
