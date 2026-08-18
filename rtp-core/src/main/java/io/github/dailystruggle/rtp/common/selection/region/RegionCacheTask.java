package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
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
public class RegionCacheTask extends RTPRunnable {
    private final Region region;
    private final UUID playerId;
    private final long selectRadius;
    private final long maxNanos;
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
     * Creates a new cache task for the general region queue.
     *
     * @param region   The region to cache a location for.
     * @param maxNanos The maximum time in nanoseconds this task is allowed to run.
     */
    public RegionCacheTask(Region region, long maxNanos) {
        super(600000L);
        this.region = region;
        this.playerId = null;
        this.selectRadius = RTP.configs.getParser(PerformanceKeys.class).getNumber(PerformanceKeys.viewDistanceSelect, 0L).longValue();
        this.maxNanos = maxNanos;
        this.observationalOnly = false;
    }

    /**
     * Creates a new cache task for a specific player's private queue.
     *
     * @param region   The region to cache a location for.
     * @param playerId The UUID of the player.
     * @param maxNanos The maximum time in nanoseconds this task is allowed to run.
     */
    public RegionCacheTask(Region region, UUID playerId, long maxNanos) {
        super(600000L);
        this.region = region;
        this.playerId = playerId;
        this.selectRadius = RTP.configs.getParser(PerformanceKeys.class).getNumber(PerformanceKeys.viewDistanceSelect, 0L).longValue();
        this.maxNanos = maxNanos;
        this.observationalOnly = false;
    }

    private RegionCacheTask(Region region, long maxNanos, boolean observationalOnly) {
        super(600000L);
        this.region = region;
        this.playerId = null;
        this.selectRadius = RTP.configs.getParser(PerformanceKeys.class).getNumber(PerformanceKeys.viewDistanceSelect, 0L).longValue();
        this.maxNanos = maxNanos;
        this.observationalOnly = observationalOnly;
    }

    /**
     * Observational-mode factory. Constructs task running only when cache is at/above
     * cacheCap, discarding safe candidates while retaining bad-location/biome side effects.
     */
    public static RegionCacheTask observe(Region region, long maxNanos) {
        return new RegionCacheTask(region, maxNanos, true);
    }

    /** Spark-profiler frame tag (diagram 02). See {@link RTPRunnable#sparkFrameName()}. */
    @Override
    protected String sparkFrameName() { return "rtp_cache_generator"; }

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
                // Observational mode runs only when L2 is full - i.e. when the
                // default-mode deficit loop has nothing to do, the visitor walks
                // LocationGenerator
                // for its bad-location/biome side-effects and discards the
                // candidate. Skip when L2 has headroom; the default-mode
                // deficit loop will fill that headroom on this pulse.
                if (!cacheFull) {
                    MemoryTracker.untrack(this);
                    return;
                }
            } else if (cacheFull) {
                MemoryTracker.untrack(this);
                return;
            }

            // Throttle: single-flight in-flight public-cache generation per region.
            // Collapses concurrent retry fan-out into one active train.
            // Per-player tasks (playerId != null) bypass this gate and are rate-limited upstream.
            if (region.inFlightCalculations.get() >= 1) {
                MemoryTracker.untrack(this);
                return;
            }
        }

        // Diagnostic counter: bumped only on the actual issuance path (after
        // throttle / cache-full short-circuits). The CFDIAG dump pairs this
        // with locationGenPregenEntry to expose the multiplier between
        // "cache-fill issuance attempts" and "PregenTask attempts".
        CfDiag.regionCacheTaskIssue.increment();
        CfDiag.ensureStarted();
        region.inFlightCalculations.incrementAndGet();
        CompletableFuture<GenerationResult> locationFuture = RTP.serverAccessor.getLocationGenerator().getLocation(region, (java.util.Set<String>) null);

        locationFuture = locationFuture.exceptionally(e -> {
            if (playerId != null) {
                RTP.log(Level.SEVERE, "Failed to generate location for player " + playerId, e);
            } else {
                RTP.log(Level.SEVERE, "Failed to generate location", e);
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
            locationFuture.thenAccept(res -> {
                long asyncStart = System.nanoTime();
                try {
                    processResult(res);
                } finally {
                    PerformanceTracker.totalNanosecondsConsumed.add(System.nanoTime() - asyncStart);
                }
            });
        }
    }

    private void processResult(GenerationResult res) {
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
        try {
            if (playerId != null) {
                if (res.coords() != null) {
                    RTPCoords coords = res.coords();
                    ChunkSet chunkSet = res.verifiedChunks();

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
                        RTP.scheduler.runTaskLater(() -> {
                            if (finalSet.complete() != null && !finalSet.complete().isDone()) {
                                finalSet.complete().complete(false);
                            }
                        }, 600L); // 30-second failsafe
                    }

                    // Capture wall-clock start for the chunk-load wait; used below as a rough
                    // proxy for chunk-loading cost since actual I/O time is server-internal.
                    final long chunkLoadStart = System.nanoTime();
                    chunkSet.complete().thenAccept(success -> {
                        // Use elapsed wall-clock time as a rough estimate of chunk loading cost.
                        long elapsed = System.nanoTime() - chunkLoadStart;
                        try {
                            RTPLocation finalPair = new RTPLocation(coords, res.attempts(), res.reservation());
                            region.queueManager.enqueuePlayerLocation(playerId, finalPair);
                        } finally {
                            PerformanceTracker.totalNanosecondsConsumed.add(elapsed);
                            releaseInFlight();
                        }
                    });
                } else {
                    releaseInFlight();
                }
            } else {
                if (res.coords() != null) {
                    if (res.reservation() != null) res.reservation().close();
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
            RTP.log(Level.SEVERE, "Error in processResult", e);
            // Fail-safe: a thrown exception inside processResult must still
            // release the in-flight slot, otherwise a single buggy reservation
            // close or buffer offer can permanently freeze the cache deficit.
            releaseInFlight();
        }
    }

    /**
     * Decrements {@code region.inFlightCalculations} and untracks this task in
     * {@link MemoryTracker} exactly once across all terminal branches via CAS.
     */
    private void releaseInFlight() {
        if (released.compareAndSet(false, true)) {
            region.inFlightCalculations.decrementAndGet();
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
