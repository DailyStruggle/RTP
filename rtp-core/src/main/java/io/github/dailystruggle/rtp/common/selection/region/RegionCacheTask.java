package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;

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

        long startNanos = System.nanoTime();

        // 1. Handle Private/Player-Specific Tasks (Bypass Backlog)
        if (playerId != null) {
            region.inFlightCalculations.incrementAndGet();
            GenerationResult res;
            try {
                res = LocationGenerator.getLocation(region, (java.util.Set<String>) null);
            } catch (Exception e) {
                region.inFlightCalculations.decrementAndGet();
                MemoryTracker.untrack(this);
                return;
            }

            if (res != null && res.coords() != null) {
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
                        for (int j = -radius; j <= radius; j ++) {
                            chunks.add(RTP.serverAccessor.getChunkManager().getChunkAtAsync(world, cx + i, cz + j));
                        }
                    }
                    chunkSet = new ChunkSet(chunks, new CompletableFuture<>());
                    region.chunkManager.putChunkSet(coords, chunkSet);

                    final ChunkSet finalSet = chunkSet;
                    RTP.scheduler.runTaskLater(() -> {
                        if (finalSet.complete() != null && !finalSet.complete().isDone()) {
                            finalSet.complete().complete(false);
                        }
                    }, 600L); // 30-second failsafe
                }

                ChunkSet finalChunkSet = chunkSet;
                RTP.serverAccessor.getChunkManager().whenComplete(chunkSet, success -> {
                    try {
                        if (isCancelled() || success == null || !success) {
                            region.chunkManager.removeTicket(coords);
                            return;
                        }
                        region.chunkManager.removeTicket(coords);
                        ChunkReservation reservation = new ChunkReservation(finalChunkSet, region.getWorld(), RTP.serverAccessor.getChunkManager());
                        RTPLocation finalPair = new RTPLocation(coords, res.attempts(), reservation);
                        region.queueManager.enqueuePlayerLocation(playerId, finalPair);
                    } finally {
                        region.inFlightCalculations.decrementAndGet();
                        MemoryTracker.untrack(this);
                    }
                });
            } else {
                region.inFlightCalculations.decrementAndGet();
                MemoryTracker.untrack(this);
            }
            return;
        }

        // 2. Handle Public Tasks: Rapidly generate unkept locations
        try {
            long cacheCap = region.getSettings().cacheCap();

            while (!isCancelled()) {
                long currentTotal = region.queueManager.unkeptLocations.size();
                if (currentTotal >= cacheCap) {
                    break;
                }

                region.inFlightCalculations.incrementAndGet();
                GenerationResult res;
                try {
                    res = LocationGenerator.getLocation(region, (java.util.Set<String>) null);
                } finally {
                    region.inFlightCalculations.decrementAndGet();
                }

                if (res != null && res.coords() != null) {
                    RTPLocation coldLoc = new RTPLocation(res.coords(), res.attempts(), null);
                    boolean added = region.queueManager.unkeptLocations.offer(coldLoc);
                    if (!added) {
                        break; // Backlog physical bounds reached
                    }
                } else {
                    break; // Failed to generate a valid coordinate (e.g., bad config)
                }

                if (System.nanoTime() - startNanos >= maxNanos) {
                    break; // Time budget exceeded, yield thread back to pipeline
                }
            }
        } finally {
            MemoryTracker.untrack(this);
        }
    }
}
