package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
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
        this.selectRadius = RTP.configs.getParser(PerformanceKeys.class).getNumber(PerformanceKeys.viewDistanceSelect, 0L).longValue();
    }

    public RegionCacheTask(Region region, UUID playerId) {
        super(600000L);
        this.region = region;
        this.playerId = playerId;
        this.selectRadius = RTP.configs.getParser(PerformanceKeys.class).getNumber(PerformanceKeys.viewDistanceSelect, 0L).longValue();
    }

    @Override
    public void run() {
        if (isCancelled()) return;

        int activeChunkCap = region.getSettings().activeChunkCap();
        if (activeChunkCap > 0) {
            for (int i = 0; i < activeChunkCap; i++) {
                if (isCancelled()) return;
                RTPLocation loc = region.queueManager.keptLocations.get(i);
                if (loc == null) break;
                if (loc.reservation() == null) {
                    ChunkSet chunkSet = region.chunkManager.getChunkSet(loc.coords());
                    if (chunkSet != null) {
                        ChunkReservation reservation = new ChunkReservation(chunkSet, region.getWorld(), RTP.serverAccessor.getChunkManager());
                        region.queueManager.keptLocations.set(i, new RTPLocation(loc.coords(), loc.attempts(), reservation));
                    }
                }
            }
        }

        int queueSize = region.queueManager.keptLocations.size();
        for (int i = Math.max(0, activeChunkCap); i < queueSize; i++) {
            if (isCancelled()) return;
            RTPLocation loc = region.queueManager.keptLocations.get(i);
            if (loc == null) continue;

            if (loc.reservation() != null) {
                loc.reservation().close();
                region.queueManager.keptLocations.set(i, new RTPLocation(loc.coords(), loc.attempts(), null));
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
            final RTPLocation pair = new RTPLocation(coords, res.attempts());
            if (coords == null) {
                region.inFlightCalculations.decrementAndGet();
                return;
            }

            final ChunkSet chunkSet;
            if (res.verifiedChunks() != null) {
                chunkSet = res.verifiedChunks();
                region.chunkManager.putChunkSet(coords, chunkSet);

                // Force-fail the chunk load if the server hangs for more than 30 seconds
                final ChunkSet finalSet = chunkSet;
                RTP.scheduler.runTaskLater(() -> {
                    if (finalSet.complete() != null && !finalSet.complete().isDone()) {
                        finalSet.complete().complete(false);
                    }
                }, 600L); // 600 ticks = 30 seconds
            } else {
                int cx = coords.x() >> 4;
                int cz = coords.z() >> 4;
                long chunkKey = region.chunkManager.getChunkKey(cx, cz);
                int radius = (int) this.selectRadius;
                long sz = (radius * 2L + 1) * (radius * 2L + 1);

                ChunkSet existing = region.chunkManager.getChunkSet(coords);
                if (existing != null && existing.chunks().size() >= sz) {
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
                    region.chunkManager.ticketCounts.compute(chunkKey, (k, v) -> (v == null) ? 1 : v + 1);
                    region.chunkManager.putChunkSet(coords, chunkSet);

                    // Force-fail the chunk load if the server hangs for more than 30 seconds.
                    // This breaks the reference chain, drops the tickets, and frees the inFlight counter.
                    final ChunkSet finalSet = chunkSet;
                    RTP.scheduler.runTaskLater(() -> {
                        if (finalSet.complete() != null && !finalSet.complete().isDone()) {
                            finalSet.complete().complete(false);
                        }
                    }, 600L); // 600 ticks = 30 seconds
                }
            }

            RTP.serverAccessor.getChunkManager().whenComplete(chunkSet, aBoolean -> {
                try {
                    if (isCancelled() || !aBoolean) {
                        region.chunkManager.removeTicket(coords); // Release entire radius
                        return;
                    }

                    region.chunkManager.removeTicket(coords);

                    if (playerId == null) {
                        if (region.queueManager.keptLocations.size() < region.getSettings().cacheCap()) {
                            RTPLocation finalPair = pair;
                            if (region.queueManager.keptLocations.size() < region.getSettings().activeChunkCap()) {
                                ChunkReservation reservation = new ChunkReservation(chunkSet, region.getWorld(), RTP.serverAccessor.getChunkManager());
                                finalPair = new RTPLocation(coords, res.attempts(), reservation);
                            }
                            region.queueManager.keptLocations.offer(finalPair);
                        }
                    } else {
                        ChunkReservation reservation = new ChunkReservation(chunkSet, region.getWorld(), RTP.serverAccessor.getChunkManager());
                        RTPLocation finalPair = new RTPLocation(coords, res.attempts(), reservation);
                        region.queueManager.enqueuePlayerLocation(playerId, finalPair);
                    }
                } finally {
                    region.inFlightCalculations.decrementAndGet();
                    io.github.dailystruggle.rtp.common.tools.MemoryTracker.untrack(RegionCacheTask.this);
                }
            });
        } else {
            region.inFlightCalculations.decrementAndGet();
            io.github.dailystruggle.rtp.common.tools.MemoryTracker.untrack(this);
        }
    }
}
