package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Manages the generation and distribution of pre-calculated teleport locations for a specific region.
 *
 * <p>This class is the core of the plugin's "instant teleport" feature. Instead of finding a location
 * when a player executes a command, this manager maintains queues of valid locations.
 *
 * <ul>
 *   <li><b>Hot Queue (keptLocations):</b> Chunks are currently loaded in server memory and ready for immediate use.</li>
 *   <li><b>Cold Queue (unkeptLocations):</b> Locations have been verified as safe, but their chunks have been released to save RAM.</li>
 *   <li><b>Per-Player Queue:</b> Specific locations reserved or recycled for individual players.</li>
 * </ul>
 *
 * <p>By asynchronously replenishing these queues, the plugin guarantees zero-latency teleports.
 */
public class RegionQueueManager {
    private final Region region;

    // Hot Queue: Chunks are loaded, verified, and actively have keep(true) applied
    public final LockFreeLocationBuffer keptLocations;

    // Cold Queue: Chunks are verified and safe, but have been released to save RAM
    public final LockFreeLocationBuffer unkeptLocations;

    /**
     * L3 backlog cache (ADR-028). Order-preserving FIFO of unverified candidate
     * locations produced by shape-only picks (no chunk I/O — S-005 safe). Each
     * entry carries a tri-state validity flag
     * ({@code UNVERIFIED}/{@code VALIDATED}/{@code INVALIDATED}); per
     * {@link Region#execute(long)} pulse, exactly one Anvil-region-file bin is
     * verified via the bound {@link io.github.dailystruggle.rtp.api.hooks.AnvilPrefilterRegistry}
     * provider, and the contiguous-{@code VALIDATED} head is drained into
     * {@link #unkeptLocations}.
     *
     * <p>Allocated only when {@code backlogCacheCap > 0}; {@code null} otherwise
     * (lite default per ADR-028 / lite YAML overlay). Storage of truth lives
     * here; the world-level {@link WorldBacklogBinIndex} only holds weak
     * references for cross-RTP-region Anvil amortization.
     */
    @org.jetbrains.annotations.Nullable
    public final BacklogLocationBuffer backlogLocations;

    /**
     * World-keyed registry of {@link WorldBacklogBinIndex} instances shared
     * across every {@link Region} that targets the same world. Lazily allocated
     * on first access by {@link #binIndexFor(String)}.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, WorldBacklogBinIndex>
            WORLD_BIN_INDEX_BY_WORLD_NAME = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * @param worldName canonical world name; never {@code null}
     * @return the world-scoped backlog bin index, creating it on first call
     */
    public static WorldBacklogBinIndex binIndexFor(String worldName) {
        return WORLD_BIN_INDEX_BY_WORLD_NAME.computeIfAbsent(
                worldName, k -> new WorldBacklogBinIndex());
    }

    /**
     * Login Reserve Queue (ADR-023): a reserved kept-cache of safe locations
     * promoted from {@link #unkeptLocations} solely for join-time teleports
     * routed through {@code rtp.onevent.firstjoin}/{@code rtp.onevent.join}.
     *
     * <p>Allocated only on the default-world region (the first world configured
     * for RTP) and only when {@code PerformanceKeys.loginCacheEnabled=true}.
     * On all other regions and when the toggle is off, this field is
     * {@code null}.
     *
     * <p>Fill loop is event-driven and decoupled from {@code Region.execute()}:
     * a startup burst tops it up to {@code loginCacheCap}, then
     * {@code PlayerQuitEvent} triggers a single-slot lazy refill. This avoids
     * sharing budget with the regular hot/cold deficit loop.
     */
    @org.jetbrains.annotations.Nullable
    public LockFreeLocationBuffer loginLocations;

    /** When reserving/recycling locations for specific players, I want to guard against */
    public final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<RTPLocation>>
            perPlayerLocationQueue = new ConcurrentHashMap<>();

    /** */
    public final ConcurrentHashMap<UUID, CompletableFuture<RTPLocation>> fastLocations =
            new ConcurrentHashMap<>();

    public final ConcurrentLinkedQueue<UUID> playerQueue = new ConcurrentLinkedQueue<>();

    public RegionQueueManager(Region region) {
        this.region = region;
        RegionSettings settings = region.getSettings();
        if(settings!=null) {
            this.unkeptLocations = new LockFreeLocationBuffer((int) settings.cacheCap());
            this.keptLocations = new LockFreeLocationBuffer(settings.activeChunkCap());
            long backlogCap = settings.backlogCacheCap();
            this.backlogLocations = (backlogCap > 0)
                    ? new BacklogLocationBuffer((int) Math.min(backlogCap, Integer.MAX_VALUE))
                    : null;
        } else {
            this.unkeptLocations = new LockFreeLocationBuffer(1024);
            this.keptLocations = new LockFreeLocationBuffer(1024);
            this.backlogLocations = null;
        }

        installDatabaseCallbacks();
    }

    /**
     * (Re)install the database save/delete callbacks on both location buffers.
     *
     * <p>Persist both queues. On startup nothing is loaded as "kept" (kept requires a live
     * chunk reservation, which only the async deficit loop in Region.execute() can produce
     * safely per REQ-RTP-S-005). The distinction between kept and unkept is therefore
     * irrelevant to persistence: every cached location is restored as an unkept stub
     * regardless of which queue it was saved from. Order does not matter — hydration
     * shuffles the list on load.
     *
     * <p>Exposed publicly so that {@link Region#hydrateCacheFromDatabase} can temporarily
     * disable the save callback during hydration (the rows are already in the DB and are
     * deleted-after-consumption) and restore the normal persistence behaviour afterwards.
     */
    public void installDatabaseCallbacks() {
        java.util.function.Consumer<RTPLocation> saveCallback = location -> {
            if (RTP.getInstance().databaseAccessor != null) {
                RTP.getInstance().databaseAccessor.saveCachedLocation(region.name, location, null);
            }
        };
        java.util.function.Consumer<RTPLocation> deleteCallback = location -> {
            if (RTP.getInstance().databaseAccessor != null) {
                RTP.getInstance().databaseAccessor.deleteCachedLocation(region.name, location);
            }
        };
        this.keptLocations.setCallbacks(saveCallback, deleteCallback);
        this.unkeptLocations.setCallbacks(saveCallback, deleteCallback);
        if (this.loginLocations != null) {
            this.loginLocations.setCallbacks(saveCallback, deleteCallback);
        }
    }

    /**
     * Allocate the {@link #loginLocations} buffer for ADR-023 (Login Reserve Cache).
     * Idempotent: a second call with the same capacity is a no-op; a call with a
     * different non-zero capacity reallocates and drains the prior contents back
     * to {@link #unkeptLocations} (closing reservations).
     *
     * <p>Should only be invoked on the default-world region when
     * {@code PerformanceKeys.loginCacheEnabled=true}. Capacity is the snapshotted
     * {@code loginCacheCap} (or server max-players if {@code loginCacheCap=0}).
     *
     * @param capacity buffer capacity; values &lt;= 0 disable the buffer.
     */
    public void enableLoginCache(int capacity) {
        if (capacity <= 0) {
            disableLoginCache();
            return;
        }
        if (this.loginLocations != null) {
            return; // already enabled; reload-time changes go through disable+enable.
        }
        this.loginLocations = new LockFreeLocationBuffer(capacity);
        installDatabaseCallbacks();
    }

    /**
     * Drain {@link #loginLocations} back to {@link #unkeptLocations} (closing
     * reservations) and null the buffer reference. Safe to call multiple times.
     */
    public void disableLoginCache() {
        LockFreeLocationBuffer login = this.loginLocations;
        if (login == null) return;
        this.loginLocations = null;
        RTPLocation loc;
        while ((loc = login.poll()) != null) {
            if (loc.reservation() != null) loc.reservation().close();
            // Re-offer to unkept so the location persists (without reservation)
            // for the next startup burst.
            unkeptLocations.offer(new RTPLocation(loc.coords(), loc.attempts(), null));
        }
        login.clear();
    }

    /**
     * fastQueue - get a location as fast as possible for a player
     *
     * @param id player uuid
     * @return future location and number of attempts
     */
    public CompletableFuture<RTPLocation> fastQueue(UUID id) {
        if (fastLocations.containsKey(id)) return fastLocations.get(id);
        CompletableFuture<RTPLocation> res = new CompletableFuture<>();
        fastLocations.put(id, res);
        return res;
    }

    /**
     * queue - add a player to the queue for this region
     *
     * @param id player uuid
     */
    public void queue(UUID id) {
        playerQueue.add(id);
        RTP.getInstance().queuedPlayers.add(id);
        perPlayerLocationQueue.putIfAbsent(id, new ConcurrentLinkedQueue<>());
    }

    /**
     * poll - get a location for a player from the queue, prioritizing fastLocations, then perPlayerLocationQueue, then locationQueue
     * @param uuid player uuid
     * @return location or null if none available
     */
    public CompletableFuture<RTPLocation> poll(UUID uuid) {
        if (fastLocations.containsKey(uuid)) {
            return fastLocations.remove(uuid);
        }

        ConcurrentLinkedQueue<RTPLocation> playerLocationQueue = perPlayerLocationQueue.get(uuid);
        if (playerLocationQueue != null && !playerLocationQueue.isEmpty()) {
            RTPLocation loc = playerLocationQueue.poll();
            if (loc != null) {
                if (RTP.getInstance().databaseAccessor != null) {
                    RTP.getInstance().databaseAccessor.deleteCachedLocation(region.name, loc);
                }
                RTP.getInstance().queuedPlayers.remove(uuid);
                RTP.getInstance().invulnerablePlayers.remove(uuid);
                RTP.getInstance().processingPlayers.remove(uuid);

                TeleportData data = RTP.getInstance().latestTeleportData.get(uuid);
                if (data != null && !data.completed) {
                    if (data.nextTask instanceof TeleportPipelineTask task) {
                        task.setCancelled(true);
                        if (task.coords() != null) {
                            RTP.scheduler.runTask(
                                    task.region().getWorld(), task.coords().x() >> 4, task.coords().z() >> 4, task);
                        } else {
                            RTP.scheduler.runTask(task);
                        }
                    }
                }

                new RTPTeleportCancel(uuid).run();
                return CompletableFuture.completedFuture(loc);
            }
        }

        if (!keptLocations.isEmpty()) {
            RTPLocation loc = keptLocations.poll();
            if (loc != null) {
                return CompletableFuture.completedFuture(loc);
            }
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
        long res = keptLocations.size() + unkeptLocations.size();
        ConcurrentLinkedQueue<RTPLocation> queue =
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
        return keptLocations.size() + unkeptLocations.size();
    }

    /**
     * getPersonalQueueLength - get number of locations reserved for a specific player
     *
     * @param uuid player uuid
     * @return personal queue length
     */
    public long getPersonalQueueLength(UUID uuid) {
        long res = 0;
        ConcurrentLinkedQueue<RTPLocation> queue =
                perPlayerLocationQueue.get(uuid);
        if (queue != null) res += queue.size();
        if (fastLocations.containsKey(uuid)) res++;
        return res;
    }

    public void shutDown() {
        // Disable DB removal during shutdown so the persisted rows survive for the next boot.
        keptLocations.setCallbacks(null, null);
        unkeptLocations.setCallbacks(null, null);
        keptLocations.clear();
        unkeptLocations.clear();
        // ADR-028 Phase 4.1: drop L3 backlog on shutdown. Entries are unverified candidate
        // locations with no chunk tickets and no DB rows, so a clear() is sufficient.
        // The world-level WorldBacklogBinIndex holds only weak references to per-bin lists
        // and becomes GC-eligible automatically once the BacklogEntry strong-pins are gone.
        if (backlogLocations != null) backlogLocations.clear();
        perPlayerLocationQueue.forEach((uuid, queue) -> {
            RTPLocation loc;
            while ((loc = queue.poll()) != null) {
                if (loc.reservation() != null) loc.reservation().close();
            }
        });
        perPlayerLocationQueue.clear();
        fastLocations.forEach((uuid, future) -> {
            if (future.isDone()) {
                try {
                    RTPLocation loc = future.get();
                    if (loc != null && loc.reservation() != null) loc.reservation().close();
                } catch (Exception ignored) {}
            } else {
                future.complete(null);
            }
        });
        fastLocations.clear();
        playerQueue.clear();
        RTP.getInstance().queuedPlayers.clear();
    }

    /**
     * @param location location to add to the public queue
     */
    void enqueueLocation(RTPLocation location) {
        unkeptLocations.add(location);
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
    CompletableFuture<RTPLocation> getFastLocation(UUID uuid) {
        return fastLocations.get(uuid);
    }

    /**
     * @param uuid player uuid
     * @param location location to add to the player's private queue
     */
    void enqueuePlayerLocation(UUID uuid, RTPLocation location) {
        perPlayerLocationQueue.putIfAbsent(uuid, new ConcurrentLinkedQueue<>());
        ConcurrentLinkedQueue<RTPLocation> queue = perPlayerLocationQueue.get(uuid);

        // Enforce max 1 location per player: drain any existing extras before adding the new one
        RTPLocation excess;
        while ((excess = queue.poll()) != null) {
            if (excess.reservation() != null) excess.reservation().close();
            unkeptLocations.offer(excess);
        }

        queue.add(location);
        if (RTP.getInstance().databaseAccessor != null) {
            RTP.getInstance().databaseAccessor.saveCachedLocation(region.name, location, uuid);
        }
    }

    /**
     * @param index index of the location in the public queue
     * @return location at the specified index or null
     */
    RTPLocation getLocation(int index) {
        return keptLocations.get(index);
    }

    /**
     * @return collection of all per-player location queues
     */
    public java.util.Collection<ConcurrentLinkedQueue<RTPLocation>> getPerPlayerQueues() {
        return perPlayerLocationQueue.values();
    }

    /**
     * @return set of entries for per-player location queues
     */
    java.util.Set<java.util.Map.Entry<UUID, ConcurrentLinkedQueue<RTPLocation>>> getPerPlayerQueueEntries() {
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
    ConcurrentLinkedQueue<RTPLocation> getPerPlayerQueue(UUID uuid) {
        return perPlayerLocationQueue.get(uuid);
    }

    /**
     * Offer a location to the public queue.
     * @param location location to offer
     * @return true if successful
     */
    boolean offerLocation(RTPLocation location) {
        return unkeptLocations.offer(location);
    }

    public void validateTickets(io.github.dailystruggle.rtp.api.world.RTPWorld<?> world) {
        if (world == null) return;

        // Sweep the fast queue to recover any dropped tickets
        for (int i = 0; i < keptLocations.size(); i++) {
            io.github.dailystruggle.rtp.common.selection.region.RTPLocation loc = keptLocations.get(i);
            if (loc == null || loc.reservation() == null) continue;

            int cx = loc.coords().x() >> 4;
            int cz = loc.coords().z() >> 4;

            // Asynchronously guarantee the chunk is still loaded.
            // Re-calling refresh() is idempotent and will restore the plugin ticket
            // if an admin command stripped it, without blocking Folia tick threads.
            world.recordChunkLoadOrigin("RegionQueueManager.validateTickets");
            world.getChunkAtAsync(cx, cz).thenAccept(chunkSet -> {
                try {
                    loc.reservation().refresh();
                } catch (Exception e) {
                    io.github.dailystruggle.rtp.common.RTP.log(java.util.logging.Level.WARNING, "Failed to recover chunk ticket: " + e.getMessage(), e);
                }
            });
        }
    }
}
