package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.selection.ILocationGenerator;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.predict.BudgetPacer;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.tasks.CompletionPaced;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tools.CfDiag;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;
import io.github.dailystruggle.rtp.common.tools.PerformanceTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Asynchronously generates and caches safe teleport locations for a region.
 * Pre-warms landing chunks on private queues to prevent main-thread chunk I/O (ADR-016).
 */
public class RegionCacheTask extends RTPRunnable implements CompletionPaced {
    private final Region region;
    private final UUID playerId;
    private final long selectRadius;
    /**
     * Observational mode flag. When true, runs only when pregen cache is at/above
     * cacheCap and drops safe candidates, retaining generator side effects.
     */
    private final boolean observationalOnly;

    /**
     * Idempotency latch for {@link #releaseInFlight()} across multiple terminal branches.
     */
    private final AtomicBoolean released = new AtomicBoolean(false);

    /**
     * Fill deadline in ticks (30 s); matches the per-player chunk-set failsafe. Releases the slot
     * only: a per-player result whose generation finished is still delivered when its preload
     * completes after the deadline (computed work is not discarded; cache-deficit overshoot accepted).
     */
    static final long FILL_DEADLINE_TICKS = 600L;

    /** Owning pipe's pacer; charged dispatch-to-release wall time once per fill. */
    private volatile BudgetPacer pacer;
    private long dispatchNanos;

    private static long resolveSelectRadius() {
        if (ActiveConfigs.get() == null) return 0L;
        var parser = ActiveConfigs.get().getParser(PerformanceKeys.class);
        if (parser == null) return 0L;
        Number num = parser.getNumber(PerformanceKeys.viewDistanceSelect, 0L);
        return num != null ? num.longValue() : 0L;
    }

    /**
     * Creates a new cache task for the general region queue.
     *
     * @param region   The region to cache a location for.
     * @param maxNanos Ignored. One run is one selection attempt plus an async chunk load; the
     *                 owning pipe ({@code Region.cachePipeline}) enforces the pulse budget.
     */
    public RegionCacheTask(Region region, long maxNanos) {
        super(600000L);
        this.region = region;
        this.playerId = null;
        this.selectRadius = resolveSelectRadius();
        this.observationalOnly = false;
    }

    /**
     * Creates a new cache task for a specific player's private queue.
     *
     * @param region   The region to cache a location for.
     * @param playerId The UUID of the player.
     * @param maxNanos Ignored; see {@link #RegionCacheTask(Region, long)}.
     */
    public RegionCacheTask(Region region, UUID playerId, long maxNanos) {
        super(600000L);
        this.region = region;
        this.playerId = playerId;
        this.selectRadius = resolveSelectRadius();
        this.observationalOnly = false;
    }

    private RegionCacheTask(Region region, boolean observationalOnly) {
        super(600000L);
        this.region = region;
        this.playerId = null;
        this.selectRadius = resolveSelectRadius();
        this.observationalOnly = observationalOnly;
    }

    /**
     * Observational-mode factory. Constructs task running only when cache is at/above
     * cacheCap, discarding safe candidates while retaining bad-location/biome side effects.
     */
    public static RegionCacheTask observe(Region region) {
        return new RegionCacheTask(region, true);
    }

    /** Spark-profiler frame tag (diagram 02). See {@link RTPRunnable#sparkFrameName()}. */
    @Override
    protected String sparkFrameName() { return "rtp_cache_generator"; }

    @Override
    public void bindPacer(BudgetPacer pacer) {
        this.pacer = pacer;
    }

    /**
     * Executes the location generation and caching logic.
     */
    @Override
    public void run() {
        if (isCancelled()) return;

        if (playerId == null) {
            long cacheCap = region.getSettings().cacheCap();
            boolean cacheFull = region.queueManager.unkeptLocations.size() >= cacheCap;
            if (observationalOnly) {
                // Observational mode runs only when the cold stage is full - i.e. when the
                // default-mode deficit loop has nothing to do, the visitor walks
                // LocationGenerator
                // for its bad-location/biome side-effects and discards the
                // candidate. Skip when the cold stage has headroom; the default-mode
                // deficit loop will fill that headroom on this pulse.
                if (!cacheFull) {
                    MemoryTracker.untrack(this);
                    return;
                }
            } else if (cacheFull) {
                MemoryTracker.untrack(this);
                return;
            }
        }

        // Fills in flight are capped by the Region.execute deficit; the start rate by the pipe
        // pacer, charged the wall time from here to releaseInFlight(). Not every chunk is
        // loadable and not every adapter bounds its load, so a fill that has not finished by
        // FILL_DEADLINE_TICKS releases its slot and charges the deadline to the pacer; a generation
        // result arriving later is dropped (see processResult), a pending player preload is not.
        dispatchNanos = System.nanoTime();

        // Diagnostic counter: bumped only on the actual issuance path (after
        // throttle / cache-full short-circuits). The CFDIAG dump pairs this
        // with locationGenPregenEntry to expose the multiplier between
        // "cache-fill issuance attempts" and "PregenTask attempts".
        CfDiag.regionCacheTaskIssue.increment();
        CfDiag.ensureStarted();
        region.inFlightCalculations.incrementAndGet();
        // ADR-028: the public fill takes the backlog's UNKNOWN entries (no on-disk data) one at a
        // time and checks them with a chunk load; it draws from the shape only when the backlog is
        // off or empty. Validated entries reach cold through the backlog drain, not here.
        BacklogLocationBuffer backlog =
                (playerId == null && !observationalOnly) ? region.queueManager.backlogLocations : null;
        try {
            // ADR-110: fill is speculative - verify from resident chunks / region files first; native
            // loads pin into the kept queue when it has room, else stay unpinned (no ring) for the
            // cold queue. Third-party generators keep their own contract.
            ILocationGenerator generator = RTPRuntime.serverAccessor().getLocationGenerator();
            CompletableFuture<GenerationResult> locationFuture = (generator instanceof LocationGenerator)
                    ? LocationGenerator.getLocationFuture(region, null, LoadPurpose.SPECULATIVE, backlog)
                    : generator.getLocation(region, (java.util.Set<String>) null);

            locationFuture = locationFuture.exceptionally(e -> {
                if (playerId != null) {
                    RTPRuntime.log(Level.SEVERE, "Failed to generate location for player " + playerId, e);
                } else {
                    RTPRuntime.log(Level.SEVERE, "Failed to generate location", e);
                }
                // Cleanup is owned by processResult(null) below - keeping the
                // decrement here as well would race with processResult on a future
                // that completes after recovery, leaking or double-counting the
                // counter depending on dispatch order. The releaseInFlight() CAS
                // makes "who decrements?" unambiguous.
                return null;
            });

            if (locationFuture.isDone()) {
                processResult(locationFuture.join());
            } else {
                scheduleDeadline();
                locationFuture.thenAccept(res -> {
                    long asyncStart = System.nanoTime();
                    try {
                        processResult(res);
                    } finally {
                        PerformanceTracker.totalNanosecondsConsumed.add(System.nanoTime() - asyncStart);
                    }
                });
            }
        } catch (Throwable t) {
            RTPRuntime.log(Level.SEVERE, "Failed to dispatch location generation", t);
            releaseInFlight();
        }
    }

    private void scheduleDeadline() {
        if (RTPRuntime.scheduler() == null) return;
        RTPRuntime.scheduler().runTaskLater(() -> {
            if (released.get()) return;
            RTPRuntime.log(playerId != null ? Level.WARNING : Level.FINE,
                    "[Region:" + region.name + "] cache fill"
                            + (playerId != null ? " for player " + playerId : "")
                            + " did not finish within " + FILL_DEADLINE_TICKS + " ticks; slot released");
            // Charge at least the nominal deadline: tick length varies, the cost to refill does not.
            releaseInFlight(FILL_DEADLINE_TICKS * 50_000_000L);
        }, FILL_DEADLINE_TICKS);
    }

    private void processResult(GenerationResult res) {
        // Ownership of the pin moves to a queue (or the chunk-set callback) on success, so it
        // cannot be scoped by try-with-resources; every non-handoff exit closes it (REQ-RTP-S-002).
        //noinspection resource
        final ChunkReservation reservation = res != null ? res.reservation() : null;
        if (released.get()) {
            // Past the fill deadline: the slot is gone, so drop the result and free its pin
            // (REQ-RTP-S-002) rather than overshoot the cache deficit.
            if (reservation != null) reservation.close();
            return;
        }
        if (res == null) {
            // Normal-null completion paths: malformed region, pregen dispatch
            // failure (LocationGenerator.getLocationFuture lines 105/181/188),
            // PregenTask attempts exhausted, or recovery from exceptionally
            // returning null. All of them previously leaked
            // inFlightCalculations (visible as `[cached]` freezing one short
            // of cacheCap+activeCap forever).
            releaseInFlight();
            return;
        }
        // True once the reservation is handed off or already closed on this path.
        boolean pinSettled = false;
        try {
            if (playerId != null) {
                if (res.coords() != null) {
                    RTPCoords coords = res.coords();
                    ChunkSet chunkSet = res.verifiedChunks();
                    if (reservation != null) {
                        LiveLoadGate.of(region.name).onPinnedHandoff(true);
                    }

                    if (chunkSet == null && reservation == null) {
                        // ADR-110: unpinned result - no ring preload (it would unload before use).
                        try {
                            region.queueManager.enqueuePlayerLocation(playerId,
                                    new RTPLocation(coords, res.attempts(), null));
                        } finally {
                            releaseInFlight();
                        }
                        return;
                    }

                    if (chunkSet == null) {
                        int cx = coords.x() >> 4;
                        int cz = coords.z() >> 4;
                        int radius = (int) this.selectRadius;
                        long sz = (radius * 2L + 1) * (radius * 2L + 1);

                        List<CompletableFuture<Long>> chunks = new ArrayList<>((int) sz);
                        RTPWorld<?> world = region.getWorld();

                        for (int i = -radius; i <= radius; i++) {
                            for (int j = -radius; j <= radius; j++) {
                                world.recordChunkLoadOrigin("RegionCacheTask.viewDistance");
                                chunks.add(world.getChunkAt(cx + i, cz + j));
                            }
                        }
                        CfDiag.chunkSetRegionCache.increment();
                        chunkSet = new ChunkSet(world, cx, cz, chunks, new CompletableFuture<>());

                        final ChunkSet finalSet = chunkSet;
                        RTPRuntime.scheduler().runTaskLater(() -> {
                            if (finalSet.complete() != null && !finalSet.complete().isDone()) {
                                finalSet.complete().complete(false);
                            }
                        }, 600L); // 30-second failsafe
                    }

                    // Capture wall-clock start for the chunk-load wait; used below as a rough
                    // proxy for chunk-loading cost since actual I/O time is server-internal.
                    final long chunkLoadStart = System.nanoTime();
                    // whenComplete: a failed set must still release the slot.
                    chunkSet.complete().whenComplete((success, ex) -> {
                        // Use elapsed wall-clock time as a rough estimate of chunk loading cost.
                        long elapsed = System.nanoTime() - chunkLoadStart;
                        try {
                            if (ex != null) {
                                RTPRuntime.log(Level.WARNING, "Chunk preload failed for player " + playerId, ex);
                                if (reservation != null) reservation.close();
                                return;
                            }
                            RTPLocation finalPair = new RTPLocation(coords, res.attempts(), reservation);
                            region.queueManager.enqueuePlayerLocation(playerId, finalPair);
                        } finally {
                            PerformanceTracker.totalNanosecondsConsumed.add(elapsed);
                            releaseInFlight();
                        }
                    });
                    // Callback registered: it now owns the reservation.
                    pinSettled = true;
                } else {
                    releaseInFlight();
                }
            } else {
                if (res.coords() != null) {
                    if (reservation != null) {
                        if (!observationalOnly) {
                            // ADR-110 pinned fill: ownership of the reservation moves into the
                            // kept (pinned) queue; this task no longer closes it.
                            boolean kept = region.queueManager.keptLocations.offer(
                                    new RTPLocation(res.coords(), res.attempts(), reservation));
                            pinSettled = kept;
                            LiveLoadGate.of(region.name).onPinnedHandoff(kept);
                            if (kept) {
                                releaseInFlight();
                                return;
                            }
                        }
                        // Not handed off: released here (REQ-RTP-S-002).
                        reservation.close();
                        pinSettled = true;
                    }
                    // Observational mode: drop the safe candidate instead of
                    // enqueuing it. All biome / bad-location side effects already
                    // fired inside LocationGenerator; the winning candidate
                    // itself is eligible for immediate GC.
                    if (!observationalOnly) {
                        RTPLocation coldLoc = new RTPLocation(res.coords(), res.attempts(), null);
                        region.queueManager.unkeptLocations.offer(coldLoc);
                    }
                }
                releaseInFlight();
            }
        } catch (Exception e) {
            RTPRuntime.log(Level.SEVERE, "Error in processResult", e);
            // Fail-safe: a thrown exception inside processResult must still
            // release the in-flight slot, otherwise a single buggy reservation
            // close or buffer offer can permanently freeze the cache deficit.
            releaseInFlight();
        } finally {
            // Coordinate-less results and exceptional exits before handoff must not strand the pin.
            if (!pinSettled && reservation != null) {
                try {
                    reservation.close();
                } catch (RuntimeException e) {
                    RTPRuntime.log(Level.SEVERE, "Failed to release chunk reservation in processResult", e);
                }
            }
        }
    }

    /**
     * Decrements {@code region.inFlightCalculations}, charges the fill's wall time to the bound
     * pacer, and untracks this task in {@link MemoryTracker} exactly once across all terminal
     * branches via CAS.
     */
    private void releaseInFlight() {
        releaseInFlight(0L);
    }

    private void releaseInFlight(long minChargeNanos) {
        if (released.compareAndSet(false, true)) {
            region.inFlightCalculations.decrementAndGet();
            BudgetPacer p = pacer;
            if (p != null) p.recordDuration(Math.max(minChargeNanos, System.nanoTime() - dispatchNanos));
            // ADR-043: clear the per-uuid push-on-open guard on the personal
            // fill path so a future openPersonalQueue(uuid) is allowed to
            // schedule another fill once the previous one terminates
            // (success, drop, or exception).
            if (playerId != null) {
                region.queueManager.perPlayerInFlight.remove(playerId);
            }
            MemoryTracker.untrack(this);
        }
    }
}
