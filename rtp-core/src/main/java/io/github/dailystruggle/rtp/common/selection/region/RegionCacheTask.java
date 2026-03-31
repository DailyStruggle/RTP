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
    private final long selectRadius;

    public RegionCacheTask(Region region) {
        this.region = region;
        this.playerId = null;
        this.selectRadius = ((ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class)).getNumber(PerformanceKeys.viewDistanceSelect, 0L).longValue();
    }

    public RegionCacheTask(Region region, UUID playerId) {
        this.region = region;
        this.playerId = playerId;
        this.selectRadius = ((ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class)).getNumber(PerformanceKeys.viewDistanceSelect, 0L).longValue();
    }

    @Override
    public void run() {
        region.inFlightCalculations.incrementAndGet();
        GenerationResult res = LocationGenerator.getLocation(region, (java.util.Set<String>) null);
        if (res != null) {
            final RTPCoords coords = res.coords();
            final Map.Entry<RTPCoords, Long> pair = new java.util.AbstractMap.SimpleEntry<>(coords, res.attempts());
            if (coords == null) {
                region.inFlightCalculations.decrementAndGet();
                return;
            }

            final ChunkSet chunkSet;
            if (res.verifiedChunks() != null) {
                chunkSet = res.verifiedChunks();
                region.chunkManager.putChunkSet(coords, chunkSet);
            } else {
                chunkSet = region.chunkManager.chunks(coords, this.selectRadius);
            }

            chunkSet.whenComplete(
                    aBoolean -> {
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
                            region.chunkManager.removeChunkSet(coords);
                        }
                        region.inFlightCalculations.decrementAndGet();
                    });
        } else {
            region.inFlightCalculations.decrementAndGet();
        }
    }
}
