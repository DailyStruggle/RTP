package io.github.dailystruggle.helpers.stresstestrtp;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Folia region-context accounting and region-freeze detection.
 */
public final class FoliaRegionMonitor {

    /** Fixed freeze threshold (ms). See class Javadoc; never tuned to data. */
    public static final long FREEZE_THRESHOLD_MS = 5000L;

    /** Nominal Folia/Bukkit tick period (ms), for the tick-stall channel. */
    private static final long NOMINAL_TICK_MS = 50L;

    private final boolean folia = Sched.isFolia();

    private long lastTickWallNs = -1L;

    private final AtomicLong tickStalls = new AtomicLong();
    private final AtomicLong hopStalls = new AtomicLong();
    private final AtomicLong worstFreezeMs = new AtomicLong();
    private final AtomicLong acquisitions = new AtomicLong();

    private volatile long baselineTickStalls = 0L;
    private volatile long baselineHopStalls = 0L;
    private volatile long baselineAcquisitions = 0L;

    @SuppressWarnings("java:S3077")
    private volatile Object tickTaskHandle = null;

    /** True iff this monitor is measuring, i.e. the server is Folia. */
    public boolean isActive() { return folia; }

    /**
     * Starts the tick-stall channel on the global region scheduler. No-op off
     * Folia and idempotent.
     */
    public void start(StressHost host) {
        if (!folia || tickTaskHandle != null) return;
        tickTaskHandle = Sched.runGlobalTimer(host, this::tick, 1L);
    }

    /** Cancels the tick-stall channel. Null-safe and idempotent. */
    public void stop() {
        Sched.cancel(tickTaskHandle);
        tickTaskHandle = null;
    }

    private void tick() {
        long now = System.nanoTime();
        long prev = lastTickWallNs;
        lastTickWallNs = now;
        if (prev <= 0L) return;
        long ms = (now - prev) / 1_000_000L;
        if (ms >= FREEZE_THRESHOLD_MS) {
            tickStalls.incrementAndGet();
            recordWorst(ms);
        }
    }

    public void noteDispatchHop(Attempt a) {
        if (!folia || a == null) return;
        noteAcquisition(a);
        long landed = a.commandDispatchedEpochMs;
        if (landed <= 0L || a.dispatchEpochMs <= 0L) return;
        long waitedMs = landed - a.dispatchEpochMs;
        if (waitedMs >= FREEZE_THRESHOLD_MS) {
            hopStalls.incrementAndGet();
            recordWorst(waitedMs);
        }
    }

    private void recordWorst(long ms) {
        worstFreezeMs.accumulateAndGet(ms, Math::max);
    }

    public void beginAttempt(Attempt a) {
        if (!folia || a == null) return;
        if (a.regionContextAcquisitions < 0L) a.regionContextAcquisitions = 0L;
    }

    public void noteAcquisition(Attempt a) {
        if (!folia || a == null) return;
        long cur = a.regionContextAcquisitions;
        a.regionContextAcquisitions = (cur < 0L ? 1L : cur + 1L);
        acquisitions.incrementAndGet();
    }

    public void resetPhase() {
        if (!folia) return;
        baselineTickStalls = tickStalls.get();
        baselineHopStalls = hopStalls.get();
        baselineAcquisitions = acquisitions.get();
        worstFreezeMs.set(0L);
    }

    public long phaseTickStalls() {
        if (!folia) return -1L;
        return Math.max(0L, tickStalls.get() - baselineTickStalls);
    }

    public long phaseHopStalls() {
        if (!folia) return -1L;
        return Math.max(0L, hopStalls.get() - baselineHopStalls);
    }

    public long phaseFreezes() {
        if (!folia) return -1L;
        return phaseTickStalls() + phaseHopStalls();
    }

    public long phaseWorstFreezeMs() {
        if (!folia) return -1L;
        return worstFreezeMs.get();
    }

    public long phaseAcquisitions() {
        if (!folia) return -1L;
        return Math.max(0L, acquisitions.get() - baselineAcquisitions);
    }

    public static long nominalTickMs() { return NOMINAL_TICK_MS; }
}
