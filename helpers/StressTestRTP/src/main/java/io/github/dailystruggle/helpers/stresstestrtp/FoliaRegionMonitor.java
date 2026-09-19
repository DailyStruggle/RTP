package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Folia region-context accounting and region-freeze detection.
 *
 * <p>Gated entirely on {@link Sched#isFolia()} runtime detection - there is no
 * build variant and no config toggle for something the JVM can be asked
 * directly. On a non-Folia server every accessor returns the {@code -1}
 * no-data sentinel rather than {@code 0}, and the CSV columns are still
 * written, so the schema is platform-independent and a Paper/Spigot arm's
 * blanks can never be read as "measured zero freezes".
 *
 * <p><b>Freeze detection criterion (fixed in advance; see ADR-080 and the
 * G-FOLIA gates in the checklist).</b> A <em>region freeze</em> is a wall-clock
 * stall of at least {@link #FREEZE_THRESHOLD_MS} milliseconds observed on a
 * Folia region thread through one of exactly two channels:
 * <ol>
 *   <li><b>Tick stall.</b> The gap between two consecutive invocations of a
 *       1-tick timer on the global region scheduler. Nominal gap is 50 ms;
 *       a gap at or above the threshold means that region thread did not run
 *       a tick for that long.</li>
 *   <li><b>Hop stall.</b> The wall time an attempt's dispatch spent waiting
 *       for the player-owning region's entity scheduler to execute it
 *       ({@code commandDispatchedEpochMs - dispatchEpochMs}). The owning
 *       region thread not draining its task queue for that long is the same
 *       phenomenon observed from the other side.</li>
 * </ol>
 * The threshold is 5000 ms: two orders of magnitude above every entry in the
 * measured latency table, so a freeze cannot be the same distribution's tail,
 * and 100x the nominal tick period, so it cannot be ordinary tick jitter or a
 * GC pause. It is a compile-time constant and is never adjusted to make a run
 * agree with an expectation - a detector that fails to reproduce an archived
 * 0-versus-N split is a broken detector, not a mis-tuned one.
 *
 * <p><b>Off-path.</b> All state is primitive or {@link AtomicLong}. Nothing
 * allocates per attempt or per tick, nothing copies a snapshot, and no path
 * blocks a tick thread. This tier has twice had its own apparatus become the
 * headline; the tick channel is deliberately a single {@code long} subtraction
 * plus at most two uncontended atomic updates, taken only when a stall is
 * already known to have happened.
 */
public final class FoliaRegionMonitor {

    /** Fixed freeze threshold (ms). See class Javadoc; never tuned to data. */
    public static final long FREEZE_THRESHOLD_MS = 5000L;

    /** Nominal Folia/Bukkit tick period (ms), for the tick-stall channel. */
    private static final long NOMINAL_TICK_MS = 50L;

    private final boolean folia = Sched.isFolia();

    /** Tick-channel bookkeeping. Written only from the global region timer
     *  thread, which is single-threaded per invocation, so a plain field is
     *  sufficient and an atomic would only add cost on the tick path. */
    private long lastTickWallNs = -1L;

    private final AtomicLong tickStalls = new AtomicLong();
    private final AtomicLong hopStalls = new AtomicLong();
    private final AtomicLong worstFreezeMs = new AtomicLong();
    private final AtomicLong acquisitions = new AtomicLong();

    /** Phase baselines, mirroring {@link ChunkLoadCounter}'s pattern. The
     *  worst-freeze figure is per phase by construction, so it is reset rather
     *  than baselined (a max cannot be differenced). */
    private volatile long baselineTickStalls = 0L;
    private volatile long baselineHopStalls = 0L;
    private volatile long baselineAcquisitions = 0L;

    private volatile Object tickTaskHandle = null;

    /** True iff this monitor is measuring, i.e. the server is Folia. */
    public boolean isActive() { return folia; }

    /**
     * Starts the tick-stall channel on the global region scheduler. No-op off
     * Folia and idempotent.
     */
    public void start(Plugin plugin) {
        if (!folia || tickTaskHandle != null) return;
        tickTaskHandle = Sched.runGlobalTimer(plugin, this::tick, 1L);
    }

    /** Cancels the tick-stall channel. Null-safe and idempotent. */
    public void stop() {
        Sched.cancel(tickTaskHandle);
        tickTaskHandle = null;
    }

    /** Tick-stall channel: one subtraction per tick, no allocation. */
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

    /**
     * Hop-stall channel. Called once per attempt from the entity-scheduler
     * runnable that dispatches the command, i.e. on the thread that owns the
     * player's region. Also books the region-context acquisition that the hop
     * itself represents.
     */
    public void noteDispatchHop(MetricsRecorder.Attempt a) {
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

    /**
     * Registers an attempt for region-context accounting. Off Folia the
     * attempt keeps its {@code -1} sentinel; on Folia it starts at 0 so a
     * genuine zero is distinguishable from "not measured".
     */
    public void beginAttempt(MetricsRecorder.Attempt a) {
        if (!folia || a == null) return;
        if (a.regionContextAcquisitions < 0L) a.regionContextAcquisitions = 0L;
    }

    /**
     * Books one region-context acquisition against an attempt: a distinct
     * occasion on which harness code for that attempt executed on a
     * Folia region-owning thread (the entity-scheduler dispatch hop, and the
     * PlayerTeleportEvent delivery on the owning region thread). Increments a
     * plain volatile long - the two call sites are ordered by the attempt's
     * own lifecycle (hop strictly before completion), so there is no
     * read-modify-write race to lose.
     */
    public void noteAcquisition(MetricsRecorder.Attempt a) {
        if (!folia || a == null) return;
        long cur = a.regionContextAcquisitions;
        a.regionContextAcquisitions = (cur < 0L ? 1L : cur + 1L);
        acquisitions.incrementAndGet();
    }

    /** Resets the phase window. Called from {@link MetricsRecorder#beginPhase}. */
    public void resetPhase() {
        if (!folia) return;
        baselineTickStalls = tickStalls.get();
        baselineHopStalls = hopStalls.get();
        baselineAcquisitions = acquisitions.get();
        worstFreezeMs.set(0L);
    }

    /** Tick-channel freezes in this phase, or {@code -1} off Folia. */
    public long phaseTickStalls() {
        if (!folia) return -1L;
        return Math.max(0L, tickStalls.get() - baselineTickStalls);
    }

    /** Hop-channel freezes in this phase, or {@code -1} off Folia. */
    public long phaseHopStalls() {
        if (!folia) return -1L;
        return Math.max(0L, hopStalls.get() - baselineHopStalls);
    }

    /** Union of both channels for this phase, or {@code -1} off Folia. */
    public long phaseFreezes() {
        if (!folia) return -1L;
        return phaseTickStalls() + phaseHopStalls();
    }

    /**
     * Longest single freeze observed in this phase (ms), or {@code -1} off
     * Folia. A Folia phase with no freeze reports 0, which is a measurement.
     */
    public long phaseWorstFreezeMs() {
        if (!folia) return -1L;
        return worstFreezeMs.get();
    }

    /** Region-context acquisitions booked in this phase, or {@code -1} off Folia. */
    public long phaseAcquisitions() {
        if (!folia) return -1L;
        return Math.max(0L, acquisitions.get() - baselineAcquisitions);
    }

    /** Nominal tick period the tick-stall channel is measured against (ms). */
    public static long nominalTickMs() { return NOMINAL_TICK_MS; }
}
