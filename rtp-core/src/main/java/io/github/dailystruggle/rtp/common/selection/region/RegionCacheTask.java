package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.Map;
import java.util.UUID;

public class RegionCacheTask extends RTPRunnable {
    private final Region region;
    private final UUID playerId;

    public RegionCacheTask(Region region) {
        this.region = region;
        this.playerId = null;
    }

    public RegionCacheTask(Region region, UUID playerId) {
        this.region = region;
        this.playerId = playerId;
    }

    @Override
    public void run() {
        final long cacheCap = region.getSettings().cacheCap();
        final long playerQueueSize = region.queueManager.playerQueue.size();
        final long totalCap = Math.max(cacheCap, playerQueueSize);
        GenerationResult res = LocationGenerator.getLocation(region, (java.util.Set<String>) null);
        if (res != null) {
            final RTPCoords coords = res.coords();
            final Map.Entry<RTPCoords, Long> pair = new java.util.AbstractMap.SimpleEntry<>(coords, res.attempts());
            if (coords == null) {
                region.inFlightCalculations.decrementAndGet();
                if (region.cachePipeline.size() + region.queueManager.locationQueue.size() + region.inFlightCalculations.get() < totalCap) {
                    region.cachePipeline.add(new RegionCacheTask(region));
                    region.inFlightCalculations.incrementAndGet();
                }
                return;
            }

            final ChunkSet chunkSet;
            if (res.verifiedChunks() != null) {
                chunkSet = res.verifiedChunks();
                region.chunkManager.locAssChunks.put(coords, chunkSet);
            } else {
                ConfigParser<PerformanceKeys> perf =
                        (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
                long radius = perf.getNumber(PerformanceKeys.viewDistanceSelect, 0L).longValue();
                chunkSet = region.chunkManager.chunks(coords, radius);
            }

            chunkSet.whenComplete(
                    aBoolean -> {
                        region.inFlightCalculations.decrementAndGet();
                        if (aBoolean) {
                            if (playerId == null) {
                                region.queueManager.locationQueue.offer(pair);
                            } else if (region.queueManager.fastLocations.containsKey(playerId)
                                    && !region.queueManager.fastLocations.get(playerId).isDone()) {
                                region.queueManager.fastLocations.get(playerId).complete(pair);
                            } else {
                                region.queueManager.perPlayerLocationQueue.putIfAbsent(
                                        playerId, new java.util.concurrent.ConcurrentLinkedQueue<>());
                                region.queueManager.perPlayerLocationQueue.get(playerId).offer(pair);
                            }
                        } else {
                            chunkSet.keep(false, region.getWorld());
                            region.chunkManager.locAssChunks.remove(coords);
                        }
                    });
        } else {
            region.inFlightCalculations.decrementAndGet();
        }
        if (region.cachePipeline.size() + region.queueManager.locationQueue.size() + region.inFlightCalculations.get() < totalCap) {
            region.cachePipeline.add(new RegionCacheTask(region));
            region.inFlightCalculations.incrementAndGet();
        }
    }
}
