package io.github.dailystruggle.helpers.stresstestrtp;

import java.util.UUID;

/** Single attempt record. Fields mirror the CSV header. */
public final class Attempt {
    public final UUID attemptId;
    public final String player;
    public final String world;
    public final String targetLabel;
    public final long dispatchEpochMs;
    /** Wall-clock epoch (ms) at which the command was actually handed to
     *  {@code Bukkit.dispatchCommand} - i.e. <em>after</em> the
     *  {@code Sched.runOnPlayer} hop. On Spigot/Paper that hop is a
     *  {@code BukkitScheduler.runTask} which defers to the next tick
     *  boundary (0-50 ms, avg ~25 ms); attributing that wait to the
     *  target plugin would inflate every measurement by up to one tick.
     *  Set by {@code Runner#dispatchOne}. {@code -1} until the runnable
     *  fires; {@link #latencyMs()} falls back to {@link #dispatchEpochMs}
     *  in that window so an attempt that completes before the hop runs
     *  (impossible in practice, but defensive) still gets a finite
     *  latency. */
    public volatile long commandDispatchedEpochMs = -1L;
    public volatile long teleportEpochMs = -1L;
    /** Which observation completed this attempt. Recorded because the
     *  channels have different floors: on Folia the external ones
     *  ({@code TELEPORT_EVENT}, {@code POSITION_POLL}) land on the
     *  destination region's tick and read 1-2 ticks after the plugin's own
     *  completion, while {@code PLUGIN_EVENT} (LeafRTP's PostTeleportEvent)
     *  is the plugin's completion instant. Never blank once written. */
    public volatile AttributionSource attributionSource = AttributionSource.NONE;
    /** LeafRTP PreTeleportEvent / PostTeleportEvent wall timestamps, set
     *  by {@link DirectTeleportProbe}. {@code -1L} on every other arm. */
    public volatile long pluginPreTeleportEpochMs = -1L;
    public volatile long pluginPostTeleportEpochMs = -1L;
    /** Folia 5 s TPS of the region owning the dispatching player at
     *  dispatch, from {@link RegionTpsSampler}; {@code -1} off Folia. */
    public volatile double regionTps5sAtDispatch = -1.0;
    public volatile boolean success = false;
    public volatile String failReason = "";
    public volatile double fromX, fromZ, toX, toZ;
    public volatile double distance = -1.0;
    public volatile double tpsAtDispatch = -1.0;
    public volatile double msptAtDispatch = -1.0;
    public volatile long heapUsedMbAtDispatch = -1L;
    /** Chunk loads attributed to this attempt by
     *  {@link ChunkLoadCounter}'s per-attempt attribution chain (Paper
     *  plugin-ticket lookup, then main-thread temporal fallback). Set by
     *  {@link ChunkLoadCounter#endAttempt} on completion or timeout.
     *  Remains {@code -1L} when no counter is wired or the attempt was
     *  never registered. Replaces the old
     *  {@code chunkLoadsAtDispatch}/{@code chunkLoadsAtComplete} snapshot
     *  pair, which double-counted concurrent attempts because the
     *  underlying counter was global. */
    public volatile long attributedChunkLoads = -1L;
    /** Chunk loads attributed to this attempt after removing the
     *  post-teleport arrival ring (the render-distance square the server
     *  loads around the player on arrival, which is plugin-independent and
     *  scales with view distance). Computed by {@link ChunkLoadCounter#endAttempt}
     *  from the recorded load coordinates and the destination. Reflects the
     *  plugin's destination-selection work (typically ~1). Falls back to
     *  {@link #attributedChunkLoads} for timeouts / failures where the
     *  destination is unknown, and remains {@code -1L} when no counter is
     *  wired. */
    public volatile long selectionChunkLoads = -1L;
    /** Inferred serving mode, derived at row-write time from this attempt's
     *  latency and {@link #selectionChunkLoads} against the phase-derived
     *  threshold in effect at that instant. Never reads plugin internals. */
    public volatile ModeClassifier.Mode servedMode = ModeClassifier.Mode.UNKNOWN;
    /** Threshold (ms) the row-write-time classification used, {@code -1L}
     *  when the phase population had not yet reached
     *  {@link ModeClassifier#MIN_SAMPLES}. Recorded per row so the
     *  classification is re-derivable from the CSV alone. */
    public volatile long servedModeThresholdMs = -1L;
    /** Direct mode reading, available only for this plugin's own arm (a
     *  competitor's cache state is not observable). {@code null} means no
     *  direct source was wired for this attempt. Reported alongside the
     *  inferred value; deliberately never used to calibrate it. */
    public volatile ModeClassifier.Mode directServedMode = null;
    /** Chunk loads attributed to this attempt that fired on a server tick
     *  thread - on Spigot/Paper the single tick thread, on Folia the region
     *  thread that owns the loaded chunk (see {@link TickThreadDetector}).
     *  This is the foreground half of the split: these loads are charged to
     *  the tick budget and are what produces the MSPT tail. Set by
     *  {@link ChunkLoadCounter#endAttempt}; {@code -1L} means NOT MEASURED. */
    public volatile long onTickChunkLoads = -1L;
    /** Chunk loads attributed to this attempt that fired off every tick
     *  thread (chunk-system / async threads). The background half of the
     *  split. {@code onTickChunkLoads + offTickChunkLoads} equals
     *  {@link #attributedChunkLoads} whenever all three are measured.
     *  {@code -1L} means NOT MEASURED. */
    public volatile long offTickChunkLoads = -1L;
    /** Folia region-context acquisitions booked against this attempt: the
     *  number of distinct occasions on which harness code for it executed
     *  on a region-owning thread (the entity-scheduler dispatch hop, and
     *  the PlayerTeleportEvent delivered on the owning region thread).
     *  Maintained by {@link FoliaRegionMonitor}; stays {@code -1L} on every
     *  non-Folia platform, where the concept does not exist, so a blank can
     *  never be read as "zero region hops". */
    public volatile long regionContextAcquisitions = -1L;
    /** Distinct region files this attempt's selection loads implied - one
     *  per 32x32 chunk bin touched, because a chunk cannot materialise
     *  without its region file being read and repeated chunks inside one
     *  bin cost one read. Set by {@link ChunkLoadCounter#endAttempt};
     *  {@code -1L} means NOT MEASURED, never "no reads". */
    public volatile long regionFileReads = -1L;
    /** Selection candidates counted into those bins. Paired with
     *  {@link #regionFileReads} in the same row so reads-per-teleport can
     *  be regressed against occupancy offline instead of assuming a batch
     *  size. {@code -1L} means NOT MEASURED. */
    public volatile long binCandidates = -1L;
    /** Largest single-bin occupancy in this attempt, so the mean is never
     *  read without its peak. {@code -1L} means NOT MEASURED. */
    public volatile long binOccupancyMax = -1L;

    /** First external observation of this attempt's teleport
     *  ({@code PlayerTeleportEvent} or the position watch), recorded on
     *  EVERY arm including the direct one. This is the channel all arms
     *  share, so {@code external_latency_ms} is the cross-plugin latency;
     *  {@code latency_ms} on the direct arm is the plugin's own instant.
     *  {@code -1L} when no external channel saw the teleport. */
    public volatile long externalSeenEpochMs = -1L;
    /** Landing column inspection ({@link LandingInspector}); null until
     *  a completion channel with a location inspected it. */
    public volatile LandingInspector.Landing landing = null;

    /** Observed tick-thread occupancy intervals for this attempt,
     *  summarised to count / total / max rather than retained as a list.
     *  Totals alone cannot validate queue discipline - a plugin that spends
     *  8 ms once and one that spends 0.5 ms sixteen times have the same
     *  total and completely different tick tails - so the count and the
     *  widest single interval are kept alongside it.
     *
     *  <p>Guarded by {@code this} rather than atomics: the monitor is
     *  per-attempt and effectively uncontended, and it keeps the accumulator
     *  allocation-free (no {@code AtomicLong} triplet per attempt). A count
     *  of {@code 0} at row-write time is reported as the {@code -1}
     *  not-measured sentinel, since "no interval was ever observed" is not
     *  the same claim as "the plugin used no tick time". */
    private long tickIntervalCount = 0L;
    private long tickIntervalTotalNs = 0L;
    private long tickIntervalMaxNs = 0L;
    private final Object intervalLock = new Object();

    /**
     * Records one observed span of plugin work on a tick thread. Callers
     * must already have established that they are on a tick thread
     * ({@link TickThreadDetector#onTickThread()}); this method does not
     * re-check, so it stays usable from a closing burst whose thread
     * identity was captured earlier.
     *
     * <p>Zero-width and negative spans are ignored rather than clamped, so
     * a clock hiccup cannot manufacture occupancy.
     */
    public void recordTickInterval(long startNs, long endNs) {
        long width = endNs - startNs;
        if (width <= 0L) return;
        synchronized (intervalLock) {
            tickIntervalCount++;
            tickIntervalTotalNs += width;
            if (width > tickIntervalMaxNs) tickIntervalMaxNs = width;
        }
    }

    public long tickIntervalCount() {
        synchronized (intervalLock) {
            return tickIntervalCount;
        }
    }

    public long tickIntervalTotalNs() {
        synchronized (intervalLock) {
            return tickIntervalTotalNs;
        }
    }

    public long tickIntervalMaxNs() {
        synchronized (intervalLock) {
            return tickIntervalMaxNs;
        }
    }

    public Attempt(UUID id, String player, String world, String targetLabel, long dispatchEpochMs,
                   double fromX, double fromZ,
                   double tps, double mspt, long heapMb) {
        this.attemptId = id;
        this.player = player;
        this.world = world;
        this.targetLabel = targetLabel == null ? "" : targetLabel;
        this.dispatchEpochMs = dispatchEpochMs;
        this.fromX = fromX;
        this.fromZ = fromZ;
        this.tpsAtDispatch = tps;
        this.msptAtDispatch = mspt;
        this.heapUsedMbAtDispatch = heapMb;
    }

    long latencyMs() {
        if (teleportEpochMs <= 0) return -1L;
        // Prefer the post-scheduler-hop timestamp so we measure the
        // target plugin's work, not the 1-tick BukkitScheduler.runTask
        // wait that {@code Sched.runOnPlayer} adds on Spigot/Paper.
        long base = commandDispatchedEpochMs > 0 ? commandDispatchedEpochMs : dispatchEpochMs;
        return teleportEpochMs - base;
    }

    /** Post minus Pre from the plugin's own events: the teleport call
     *  alone, without the selection work before it. {@code -1L} unless
     *  both were observed. */
    long pluginLatencyMs() {
        if (pluginPreTeleportEpochMs < 0 || pluginPostTeleportEpochMs < 0) return -1L;
        return Math.max(0L, pluginPostTeleportEpochMs - pluginPreTeleportEpochMs);
    }

    /** Dispatch to the first external observation; see
     *  {@link #externalSeenEpochMs}. */
    long externalLatencyMs() {
        if (externalSeenEpochMs <= 0) return -1L;
        long base = commandDispatchedEpochMs > 0 ? commandDispatchedEpochMs : dispatchEpochMs;
        return Math.max(0L, externalSeenEpochMs - base);
    }
}
