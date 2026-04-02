package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class RegionCacheTask extends RTPRunnable {
    private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_PREFERRED;
    private static final int[] IOTA_ARRAY = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
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
                region.chunkManager.addTicket(loc.getCoords());
            }
        }

        if (isCancelled()) return;
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
                    Shape<?> shape = region.getShape();
                    RTPWorld<?> world = region.getWorld();

                    if (shape instanceof MemoryShape<?> memoryShape) {
                        IntVector iota = IntVector.fromArray(SPECIES, IOTA_ARRAY, 0);

                        for (int i = -radius; i <= radius; i++) {
                            IntVector xVec = IntVector.broadcast(SPECIES, cx + i);

                            for (int j = -radius; j <= radius; j += SPECIES.length()) {
                                VectorMask<Integer> loopMask = SPECIES.indexInRange(j, radius + 1);
                                IntVector zVec = iota.add(cz + j);

                                VectorMask<Integer> mask = memoryShape.contains(xVec, zVec, loopMask);

                                if (mask.anyTrue()) {
                                    for (int k = 0; k < SPECIES.length(); k++) {
                                        if (mask.laneIsSet(k)) {
                                            chunks.add(RTP.serverAccessor.getChunkManager().getChunkAtAsync(world, cx + i, cz + j + k));
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        for (int i = -radius; i <= radius; i++) {
                            for (int j = -radius; j <= radius; j += SPECIES.length()) {
                                VectorMask<Integer> loopMask = SPECIES.indexInRange(j, radius + 1);
                                for (int k = 0; k < SPECIES.length(); k++) {
                                    if (loopMask.laneIsSet(k) && shape.contains(cx + i, cz + j + k)) {
                                        chunks.add(RTP.serverAccessor.getChunkManager().getChunkAtAsync(world, cx + i, cz + j + k));
                                    }
                                }
                            }
                        }
                    }

                    chunkSet = new ChunkSet(chunks, new CompletableFuture<>());
                    ChunkSet.register(world, cx, cz, chunkSet);
                    region.chunkManager.ticketCounts.compute(chunkKey, (k, v) -> (v == null) ? 1 : v + 1);
                    region.chunkManager.putChunkSet(coords, chunkSet);
                    chunkSet.keep(true, world);
                }
            }

            chunkSet.whenComplete(
                    aBoolean -> {
                        if (isCancelled()) {
                            RTP.scheduler.runTask(region.getWorld(), coords.x() >> 4, coords.z() >> 4, () -> {
                                chunkSet.keep(false, region.getWorld());
                            });
                            region.chunkManager.removeChunkSet(coords);
                            CachedLocationPool.release(pair);
                            region.inFlightCalculations.decrementAndGet();
                            return;
                        }
                        if (aBoolean) {
                            if (playerId == null) {
                                region.queueManager.locationQueue.offer(pair);
                                if (region.queueManager.locationQueue.size() > region.getSettings().activeChunkCap()) {
                                    RTP.scheduler.runTask(region.getWorld(), coords.x() >> 4, coords.z() >> 4, () -> {
                                        chunkSet.keep(false, region.getWorld());
                                    });
                                }
                            } else if (region.queueManager.fastLocations.containsKey(playerId)
                                    && !region.queueManager.fastLocations.get(playerId).isDone()) {
                                region.queueManager.fastLocations.get(playerId).complete(pair);
                            } else {
                                region.queueManager.perPlayerLocationQueue.putIfAbsent(
                                        playerId, new java.util.concurrent.ConcurrentLinkedQueue<>());
                                java.util.concurrent.ConcurrentLinkedQueue<CachedLocation> q = region.queueManager.perPlayerLocationQueue.get(playerId);
                                q.offer(pair);
                                if (q.size() > region.getSettings().activeChunkCap()) {
                                    RTP.scheduler.runTask(region.getWorld(), coords.x() >> 4, coords.z() >> 4, () -> {
                                        chunkSet.keep(false, region.getWorld());
                                    });
                                }
                            }
                        } else {
                            RTP.scheduler.runTask(region.getWorld(), coords.x() >> 4, coords.z() >> 4, () -> {
                                chunkSet.keep(false, region.getWorld());
                            });
                            region.chunkManager.removeChunkSet(coords);
                            CachedLocationPool.release(pair);
                        }
                        region.inFlightCalculations.decrementAndGet();
                    });
        } else {
            region.inFlightCalculations.decrementAndGet();
        }
    }
}
