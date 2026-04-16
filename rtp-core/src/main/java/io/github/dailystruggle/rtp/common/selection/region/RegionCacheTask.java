package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;
import io.github.dailystruggle.rtp.common.tools.PerformanceTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class RegionCacheTask extends RTPRunnable {
    private final Region region;
    private final UUID playerId;
    private final long selectRadius;
    private final long maxNanos;

    public RegionCacheTask(Region region, long maxNanos) {
        super(600000L);
        this.region = region;
        this.playerId = null;
        this.selectRadius = RTP.configs.getParser(PerformanceKeys.class).getNumber(PerformanceKeys.viewDistanceSelect, 0L).longValue();
        this.maxNanos = maxNanos;
    }

    public RegionCacheTask(Region region, UUID playerId, long maxNanos) {
        super(600000L);
        this.region = region;
        this.playerId = playerId;
        this.selectRadius = RTP.configs.getParser(PerformanceKeys.class).getNumber(PerformanceKeys.viewDistanceSelect, 0L).longValue();
        this.maxNanos = maxNanos;
    }

    @Override
    public void run() {
        if (isCancelled()) return;

        if (playerId == null) {
            long cacheCap = region.getSettings().cacheCap();
            if (region.queueManager.unkeptLocations.size() >= cacheCap) {
                MemoryTracker.untrack(this);
                return;
            }

            // 2. THROTTLE: Cap the number of concurrent asynchronous generations allowed per region.
            // When the TaskPipe rapidly loops 60 times in a tick, 58 of them will cleanly abort here,
            // naturally pacing the generation to 2 chunks at a time.
            if (region.inFlightCalculations.get() > 2) {
                MemoryTracker.untrack(this);
                return;
            }
        }

        region.inFlightCalculations.incrementAndGet();
        CompletableFuture<GenerationResult> locationFuture = RTP.serverAccessor.getLocationGenerator().getLocation(region, (java.util.Set<String>) null);

        locationFuture = locationFuture.exceptionally(e -> {
            if (playerId != null) {
                RTP.log(java.util.logging.Level.SEVERE, "Failed to generate location for player " + playerId, e);
            } else {
                RTP.log(java.util.logging.Level.SEVERE, "Failed to generate location", e);
            }
            region.inFlightCalculations.decrementAndGet();
            MemoryTracker.untrack(this);
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
        if (res == null) return;
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
                                chunks.add(world.getChunkAt(cx + i, cz + j));
                            }
                        }
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
                            region.inFlightCalculations.decrementAndGet();
                            MemoryTracker.untrack(this);
                        }
                    });
                } else {
                    region.inFlightCalculations.decrementAndGet();
                    MemoryTracker.untrack(this);
                }
            } else {
                region.inFlightCalculations.decrementAndGet();
                if (res.coords() != null) {
                    if (res.reservation() != null) res.reservation().close();
                    RTPLocation coldLoc = new RTPLocation(res.coords(), res.attempts(), null);
                    region.queueManager.unkeptLocations.offer(coldLoc);
                }
                MemoryTracker.untrack(this);
            }
        } catch (Exception e) {
            RTP.log(java.util.logging.Level.SEVERE, "Error in processResult", e);
        }
    }
}
