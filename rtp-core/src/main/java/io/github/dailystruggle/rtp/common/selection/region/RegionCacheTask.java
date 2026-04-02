package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class RegionCacheTask extends RTPRunnable {
    private final Region region;
    private final UUID playerId;
    private final long selectRadius;

    public RegionCacheTask(Region region) {
        super(600000L);
        this.region = region;
        this.playerId = null;
        this.selectRadius = ((ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class)).getNumber(PerformanceKeys.viewDistanceSelect, 0L).longValue();
    }

    public RegionCacheTask(Region region, UUID playerId) {
        super(600000L);
        this.region = region;
        this.playerId = playerId;
        this.selectRadius = ((ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class)).getNumber(PerformanceKeys.viewDistanceSelect, 0L).longValue();
    }

    @Override
    public void run() {
        if (isCancelled()) return;

        int activeChunkCap = region.getSettings().activeChunkCap();
        if (activeChunkCap > 0) {
            for (int i = 0; i < activeChunkCap; i++) {
                if (isCancelled()) return;
                CachedLocation loc = region.queueManager.locationQueue.get(i);
                if (loc == null) break;
                ChunkSet chunkSet = region.chunkManager.getChunkSet(loc.getCoords());
                if (chunkSet == null || !chunkSet.keep()) {
                    region.chunkManager.addTicket(loc.getCoords());
                }
            }
        }

        region.inFlightCalculations.incrementAndGet();
        GenerationResult res = LocationGenerator.getLocation(region, (java.util.Set<String>) null);

        if (res != null) {
            if (isCancelled()) {
                region.inFlightCalculations.decrementAndGet();
                return;
            }
            final RTPCoords coords = res.coords();
            final CachedLocation pair = CachedLocationPool.acquire(coords, res.attempts());
            if (coords == null) {
                region.inFlightCalculations.decrementAndGet();
                return;
            }

            final ChunkSet chunkSet;
            if (res.verifiedChunks() != null) {
                chunkSet = res.verifiedChunks();
                region.chunkManager.putChunkSet(coords, chunkSet);
            } else {
                int cx = coords.x() >> 4;
                int cz = coords.z() >> 4;
                long chunkKey = region.chunkManager.getChunkKey(cx, cz);
                int radius = (int) this.selectRadius;
                long sz = (radius * 2L + 1) * (radius * 2L + 1);

                ChunkSet existing = region.chunkManager.locAssChunks.get(chunkKey);
                if (existing != null && existing.chunks.size() >= sz) {
                    region.chunkManager.ticketCounts.compute(chunkKey, (k, v) -> (v == null) ? 1 : v + 1);
                    chunkSet = existing;
                } else {
                    if (existing != null) {
                        region.chunkManager.removeChunks(coords);
                    }

                    List<CompletableFuture<Long>> chunks = new ArrayList<>((int) sz);
                    RTPWorld<?> world = region.getWorld();

                    for (int i = -radius; i <= radius; i++) {
                        for (int j = -radius; j <= radius; j ++) {
                            chunks.add(RTP.serverAccessor.getChunkManager().getChunkAtAsync(world, cx + i, cz + j));
                        }
                    }

                    chunkSet = new ChunkSet(chunks, new CompletableFuture<>());
                    ChunkSet.register(world, cx, cz, chunkSet);
                    region.chunkManager.ticketCounts.compute(chunkKey, (k, v) -> (v == null) ? 1 : v + 1);
                    region.chunkManager.putChunkSet(coords, chunkSet);
                    chunkSet.keep(true, world);
                }
            }

            chunkSet.whenComplete(aBoolean -> {
                try {
                    if (isCancelled() || !aBoolean) {
                        region.chunkManager.removeTicket(coords); // Release entire radius
                        CachedLocationPool.release(pair);
                        return;
                    }

                    region.chunkManager.removeTicket(coords);

                    if (playerId == null) {
                        if (region.queueManager.locationQueue.size() < region.getSettings().cacheCap()) {
                            if (region.queueManager.locationQueue.size() < region.getSettings().activeChunkCap()) {
                                region.chunkManager.addTicket(coords); // Center only
                            }
                            region.queueManager.locationQueue.offer(pair);
                        } else {
                            CachedLocationPool.release(pair);
                        }
                    } else {
                        region.chunkManager.addTicket(coords);
                        region.queueManager.enqueuePlayerLocation(playerId, pair);
                    }
                } finally {
                    region.inFlightCalculations.decrementAndGet();
                }
            });
        } else {
            region.inFlightCalculations.decrementAndGet();
        }
    }
}
