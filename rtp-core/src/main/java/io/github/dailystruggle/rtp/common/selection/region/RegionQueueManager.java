package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class RegionQueueManager {
    private final Region region;

    public final ConcurrentLinkedQueue<Map.Entry<RTPCoords, Long>> locationQueue = new ConcurrentLinkedQueue<>();

    /** When reserving/recycling locations for specific players, I want to guard against */
    public final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<Map.Entry<RTPCoords, Long>>>
            perPlayerLocationQueue = new ConcurrentHashMap<>();

    /** */
    public final ConcurrentHashMap<UUID, CompletableFuture<Map.Entry<RTPCoords, Long>>> fastLocations =
            new ConcurrentHashMap<>();

    public final ConcurrentLinkedQueue<UUID> playerQueue = new ConcurrentLinkedQueue<>();

    public RegionQueueManager(Region region) {
        this.region = region;
    }

    /**
     * fastQueue - get a location as fast as possible for a player
     *
     * @param id player uuid
     * @return future location and number of attempts
     */
    public CompletableFuture<Map.Entry<RTPCoords, Long>> fastQueue(UUID id) {
        if (fastLocations.containsKey(id)) return fastLocations.get(id);
        CompletableFuture<Map.Entry<RTPCoords, Long>> res = new CompletableFuture<>();
        fastLocations.put(id, res);
        region.miscPipeline.add(new RegionCacheTask(region, id));
        return res;
    }

    /**
     * queue - add a player to the queue for this region
     *
     * @param id player uuid
     */
    public void queue(UUID id) {
        perPlayerLocationQueue.putIfAbsent(id, new ConcurrentLinkedQueue<>());
        region.miscPipeline.add(new RegionCacheTask(region, id));
    }

    /**
     * getTotalQueueLength - get combined length of public and private queues
     *
     * @param uuid player uuid
     * @return combined queue length
     */
    public long getTotalQueueLength(UUID uuid) {
        long res = locationQueue.size();
        ConcurrentLinkedQueue<Map.Entry<RTPCoords, Long>> queue =
                perPlayerLocationQueue.get(uuid);
        if (queue != null) res += queue.size();
        if (fastLocations.containsKey(uuid)) res++;
        return res;
    }

    /**
     * getPublicQueueLength - get number of locations available to everyone
     *
     * @return public queue length
     */
    public long getPublicQueueLength() {
        return locationQueue.size();
    }

    /**
     * getPersonalQueueLength - get number of locations reserved for a specific player
     *
     * @param uuid player uuid
     * @return personal queue length
     */
    public long getPersonalQueueLength(UUID uuid) {
        long res = 0;
        ConcurrentLinkedQueue<Map.Entry<RTPCoords, Long>> queue =
                perPlayerLocationQueue.get(uuid);
        if (queue != null) res += queue.size();
        if (fastLocations.containsKey(uuid)) res++;
        return res;
    }
}
