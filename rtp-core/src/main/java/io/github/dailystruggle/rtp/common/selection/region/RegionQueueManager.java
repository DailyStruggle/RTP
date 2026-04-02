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

    /**
     * @param location location to add to the public queue
     */
    void enqueueLocation(CachedLocation location) {
        locationQueue.add(location);
    }

    /**
     * @param uuid player uuid
     * @return true if fast locations contains the player
     */
    boolean hasFastLocation(UUID uuid) {
        return fastLocations.containsKey(uuid);
    }

    /**
     * @param uuid player uuid
     * @return fast location future for the player
     */
    CompletableFuture<CachedLocation> getFastLocation(UUID uuid) {
        return fastLocations.get(uuid);
    }

    /**
     * @param uuid player uuid
     * @param location location to add to the player's private queue
     */
    void enqueuePlayerLocation(UUID uuid, CachedLocation location) {
        perPlayerLocationQueue.putIfAbsent(uuid, new ConcurrentLinkedQueue<>());
        perPlayerLocationQueue.get(uuid).add(location);
    }

    /**
     * @param index index of the location in the public queue
     * @return location at the specified index or null
     */
    CachedLocation getLocation(int index) {
        return locationQueue.get(index);
    }

    /**
     * @return collection of all per-player location queues
     */
    java.util.Collection<ConcurrentLinkedQueue<CachedLocation>> getPerPlayerQueues() {
        return perPlayerLocationQueue.values();
    }

    /**
     * @return set of entries for per-player location queues
     */
    java.util.Set<java.util.Map.Entry<UUID, ConcurrentLinkedQueue<CachedLocation>>> getPerPlayerQueueEntries() {
        return perPlayerLocationQueue.entrySet();
    }

    /**
     * Clear all per-player location queues.
     */
    void clearPerPlayerQueues() {
        perPlayerLocationQueue.clear();
    }

    /**
     * @param uuid player uuid
     * @return true if the player has any reserved locations
     */
    boolean hasPerPlayerQueue(UUID uuid) {
        return perPlayerLocationQueue.containsKey(uuid);
    }

    /**
     * @param uuid player uuid
     * @return the player's private queue or null
     */
    ConcurrentLinkedQueue<CachedLocation> getPerPlayerQueue(UUID uuid) {
        return perPlayerLocationQueue.get(uuid);
    }

    /**
     * Offer a location to the public queue.
     * @param location location to offer
     * @return true if successful
     */
    boolean offerLocation(CachedLocation location) {
        return locationQueue.offer(location);
    }
}
