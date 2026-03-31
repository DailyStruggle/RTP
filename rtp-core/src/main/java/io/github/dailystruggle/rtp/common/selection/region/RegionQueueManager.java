package io.github.dailystruggle.rtp.common.selection.region;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class RegionQueueManager {
    private final Region region;

    public final LockFreeLocationBuffer locationQueue = new LockFreeLocationBuffer(1024);

    /** When reserving/recycling locations for specific players, I want to guard against */
    public final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<CachedLocation>>
            perPlayerLocationQueue = new ConcurrentHashMap<>();

    /** */
    public final ConcurrentHashMap<UUID, CompletableFuture<CachedLocation>> fastLocations =
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
    public CompletableFuture<CachedLocation> fastQueue(UUID id) {
        if (fastLocations.containsKey(id)) return fastLocations.get(id);
        CompletableFuture<CachedLocation> res = new CompletableFuture<>();
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
     * onPlayerPop - logic to run after a player is popped from the queue
     */
    public void onPlayerPop() {

    }

    /**
     * poll - get a location for a player from the queue, prioritizing fastLocations, then perPlayerLocationQueue, then locationQueue
     * @param uuid player uuid
     * @return location or null if none available
     */
    public CompletableFuture<CachedLocation> poll(UUID uuid) {
        if (fastLocations.containsKey(uuid)) {
            return fastLocations.remove(uuid);
        }

        ConcurrentLinkedQueue<CachedLocation> playerQueue = perPlayerLocationQueue.get(uuid);
        if (playerQueue != null && !playerQueue.isEmpty()) {
            CachedLocation loc = playerQueue.poll();
            if (loc != null) return CompletableFuture.completedFuture(loc);
        }

        if (!locationQueue.isEmpty()) {
            CachedLocation loc = locationQueue.poll();
            if (loc != null) return CompletableFuture.completedFuture(loc);
        }

        return null;
    }

    /**
     * getTotalQueueLength - get combined length of public and private queues
     *
     * @param uuid player uuid
     * @return combined queue length
     */
    public long getTotalQueueLength(UUID uuid) {
        long res = locationQueue.size();
        ConcurrentLinkedQueue<CachedLocation> queue =
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
        ConcurrentLinkedQueue<CachedLocation> queue =
                perPlayerLocationQueue.get(uuid);
        if (queue != null) res += queue.size();
        if (fastLocations.containsKey(uuid)) res++;
        return res;
    }

    public void shutDown() {
        locationQueue.clear();
        perPlayerLocationQueue.clear();
        fastLocations.clear();
        playerQueue.clear();
    }
}
