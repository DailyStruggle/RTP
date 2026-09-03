package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.RTP;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Manages pre-calculated teleport location queues for a region:
 * <ul>
 *   <li><b>Hot Queue (keptLocations):</b> Active chunk tickets held for immediate use.</li>
 *   <li><b>Cold Queue (unkeptLocations):</b> Safe coordinates with released chunk tickets.</li>
 *   <li><b>Per-Player Queue:</b> Dedicated coordinates reserved for individual players.</li>
 * </ul>
 */
public class RegionQueueManager {
    private final Region region;

    // Hot Queue: Chunks are loaded, verified, and actively have keep(true) applied
    public final LockFreeLocationBuffer keptLocations;

    // Cold Queue: Chunks are verified and safe, but have been released to save RAM
    public final LockFreeLocationBuffer unkeptLocations;

    /**
     * Cross-server sibling of {@link #keptLocations} for network reservations.
     *
     * <p>Allocated only when {@code settings.networkReserveSize() > 0};
     * clamped to {@code min(networkReserveSize, cacheCap)}.
     */
    public final LockFreeLocationBuffer networkKeptLocations;

    /**
     * In-flight cross-server reservations keyed by proxy-issued {@code networkTokenId}.
     *
     * <p>Pinned between {@link #reserveFromNetworkKept(UUID, String)} and
     * {@link #redeemReserved(UUID)} or {@link #releaseToNetworkKept(UUID)}.
     */
    public final ConcurrentHashMap<UUID, RTPLocation> networkReservedLocations =
            new ConcurrentHashMap<>();

    /**
     * Backlog cache (ADR-028): shape-only candidate FIFO prior to chunk I/O (S-005).
     *
     * <p>Anvil bins are verified per {@link Region#execute(long)} pulse, and validated
     * entries are promoted to {@link #unkeptLocations}. Null when {@code backlogCacheCap <= 0}.
     */
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
     * Login Reserve Queue (ADR-023): kept-cache for join-time teleports.
     *
     * <p>Allocated only on default-world region when {@code PerformanceKeys.loginCacheEnabled=true}.
     * Decoupled from {@code Region.execute()} refill loops.
     */
    public LockFreeLocationBuffer loginLocations;

    /** When reserving/recycling locations for specific players, I want to guard against */
    public final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<RTPLocation>>
            perPlayerLocationQueue = new ConcurrentHashMap<>();

    /** */
    public final ConcurrentHashMap<UUID, CompletableFuture<RTPLocation>> fastLocations =
            new ConcurrentHashMap<>();

    public final ConcurrentLinkedQueue<UUID> playerQueue = new ConcurrentLinkedQueue<>();

    /**
     * Per-uuid in-flight guard for personal-bucket push-on-open fills (ADR-043).
     * When {@link #openPersonalQueue(UUID)} schedules a {@link RegionCacheTask}
     * for a uuid, the uuid is added to this set; the task removes it on
     * completion. Re-opening an already-tracked uuid does not schedule a
     * duplicate fill, preventing reservation amplification on flapping joins.
     */
    public final java.util.Set<UUID> perPlayerInFlight =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public RegionQueueManager(Region region) {
        this.region = region;
        RegionSettings settings = region.getSettings();
        if(settings!=null) {
            // Size unkeptLocations to fit both kept+unkept rows on hydration: the database
            // persists both queues' contents (kept is restored as unkept stubs since reservations
            // can't be re-acquired synchronously per S-005), so a reboot can momentarily hold up
            // to cacheCap+activeChunkCap rows here before steady-state promotion to keptLocations
            // drains the surplus back below cacheCap. Sizing to only cacheCap caused dropped rows
            // on every restart (default 10 lost).
            long unkeptCapacity = (long) settings.cacheCap() + (long) settings.activeChunkCap();
            if (unkeptCapacity > Integer.MAX_VALUE) unkeptCapacity = Integer.MAX_VALUE;
            this.unkeptLocations = new LockFreeLocationBuffer((int) unkeptCapacity);
            this.keptLocations = new LockFreeLocationBuffer(settings.activeChunkCap());
            long backlogCap = settings.backlogCacheCap();
            this.backlogLocations = (backlogCap > 0)
                    ? new BacklogLocationBuffer((int) Math.min(backlogCap, Integer.MAX_VALUE))
                    : null;
            // networkKeptLocations is a sibling pool capped by
            // min(networkReserveSize, cacheCap). 0 disables the network split
            // for this region (no allocation; regionKeptCounts heartbeat field omitted).
            long networkReserve = settings.networkReserveSize();
            if (networkReserve > 0) {
                long networkCap = Math.min(networkReserve, settings.cacheCap());
                if (networkCap > Integer.MAX_VALUE) networkCap = Integer.MAX_VALUE;
                this.networkKeptLocations = new LockFreeLocationBuffer((int) networkCap);
            } else {
                this.networkKeptLocations = null;
            }
        } else {
            this.unkeptLocations = new LockFreeLocationBuffer(1024);
            this.keptLocations = new LockFreeLocationBuffer(1024);
            this.backlogLocations = null;
            this.networkKeptLocations = null;
        }

        installDatabaseCallbacks();
    }

    /**
     * (Re)installs database save/delete callbacks on all active location buffers.
     *
     * <p>S-005: all hydrated locations restore as unkept stubs regardless of original queue.
     * Order is shuffled on hydration.
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
        if (this.networkKeptLocations != null) {
            this.networkKeptLocations.setCallbacks(saveCallback, deleteCallback);
        }
    }

    // -------------------------------------------------------------------------
    // Network reservation API: coordinate allocation under proxy-issued tokens.
    // S-005: no chunk I/O here; reservations are pre-acquired in networkKeptLocations.
    // -------------------------------------------------------------------------

    /**
     * Reserve a coordinate from {@link #networkKeptLocations} for an outstanding
     * cross-server transfer identified by {@code networkTokenId}.
     *
     * @param networkTokenId proxy-issued token id; must be non-null
     * @param regionKey      logical region key for selector-symmetry; may be {@code null}
     * @return the reserved location, or {@code null} if no network coord is available
     */
    public RTPLocation reserveFromNetworkKept(UUID networkTokenId, String regionKey) {
        if (networkTokenId == null) return null;
        if (this.networkKeptLocations == null) return null;
        // Disallow double-reserve on the same token id (idempotency carve-out:
        // re-reserving with the same token returns the already-pinned coord,
        // which is what the pulse retry path needs).
        RTPLocation existing = networkReservedLocations.get(networkTokenId);
        if (existing != null) return existing;
        RTPLocation loc = this.networkKeptLocations.poll();
        if (loc == null) return null;
        RTPLocation prior = networkReservedLocations.putIfAbsent(networkTokenId, loc);
        if (prior != null) {
            // Lost the race: another caller already populated this token.
            // Return the polled coord to the pool and surface the winner.
            this.networkKeptLocations.offer(loc);
            return prior;
        }
        return loc;
    }

    /**
     * Redeem a reserved coordinate, removing it from {@link #networkReservedLocations}.
     *
     * @param networkTokenId proxy-issued token id
     * @return reserved location or {@code null} if absent
     */
    public RTPLocation redeemReserved(UUID networkTokenId) {
        if (networkTokenId == null) return null;
        return networkReservedLocations.remove(networkTokenId);
    }

    /**
     * Release a reserved coordinate back to {@link #networkKeptLocations} or {@link #unkeptLocations}.
     *
     * @param networkTokenId proxy-issued token id
     * @return {@code true} if a reservation was released, {@code false} otherwise
     */
    public boolean releaseToNetworkKept(UUID networkTokenId) {
        if (networkTokenId == null) return false;
        RTPLocation loc = networkReservedLocations.remove(networkTokenId);
        if (loc == null) return false;
        if (this.networkKeptLocations != null) {
            // Kept -> kept hand-back: the reservation stays attached, since the
            // coordinate remains hot and the next reserve hands it straight out.
            // offer is bounded; if the sibling pool happens to be full (capacity
            // shrunk by reload, or operator manually drained), demote to unkept so
            // the coordinate is not silently lost (S-004 attribution rule).
            if (!this.networkKeptLocations.offer(loc)) {
                demoteToUnkept(loc);
            }
        } else {
            demoteToUnkept(loc);
        }
        return true;
    }

    /**
     * Demotes a hot entry to {@link #unkeptLocations}: closes the chunk
     * reservation, then offers the bare coordinate. The ticket follows the tier,
     * so an unkept entry never carries a reservation and none is left unclosed
     * (S-002). Sole disposal path for hot-to-cold transfers.
     *
     * @param loc hot entry being demoted; may carry a {@code null} reservation
     */
    private void demoteToUnkept(RTPLocation loc) {
        if (loc == null) return;
        if (loc.reservation() != null) {
            try {
                loc.reservation().close();
            } catch (Throwable t) {
                RTP.log(java.util.logging.Level.WARNING,
                        "[RTP] demoteToUnkept: reservation close failed at "
                                + loc.coords() + ": " + t, t);
            }
        }
        unkeptLocations.offer(new RTPLocation(loc.coords(), loc.attempts(), null));
    }

    /**
     * Pins a redeemed reservation coordinate into {@code playerId}'s personal queue.
     * S-004: returns {@code true} on successful enqueuing, {@code false} if inputs are null.
     *
     * @param playerId joining player's UUID
     * @param loc      redeemed reservation coordinate
     * @return {@code true} if pinned to personal queue
     */
    public boolean acceptRedeemedReservation(UUID playerId, RTPLocation loc) {
        if (playerId == null || loc == null) return false;
        enqueuePlayerLocation(playerId, loc);
        return true;
    }

    /** @return the number of locations currently in {@link #keptLocations}. */
    public long keptCount() {
        return keptLocations.size();
    }

    /**
     * @param regionKey logical region key; currently ignored (single-region scope)
     * @return the number of locations currently in {@link #networkKeptLocations},
     *         or {@code 0} when the network split is disabled for this region.
     */
    public long networkKeptCount(String regionKey) {
        LockFreeLocationBuffer buf = this.networkKeptLocations;
        return buf == null ? 0L : buf.size();
    }

    /** @return the number of in-flight cross-server reservations bound on this region. */
    public long networkReservedCount() {
        return networkReservedLocations.size();
    }

    /**
     * Allocates {@link #loginLocations} for ADR-023 (Login Reserve Cache).
     *
     * @param capacity buffer capacity; &lt;= 0 disables the buffer
     */
    public void enableLoginCache(int capacity) {
        if (capacity <= 0) {
            disableLoginCache();
            return;
        }
        if (this.loginLocations != null) {
            // Already enabled; reload changes use disable+enable.
            return;
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
            // Re-offer to unkept so the location persists (without reservation)
            // for the next startup burst.
            demoteToUnkept(loc);
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
     * Opens a personal coordinate bucket for {@code uuid} in this region (ADR-043).
     *
     * <p>Opt-in only: does not request teleport or enroll in {@link #playerQueue}.
     * Triggers a single push-on-open {@link RegionCacheTask} fill if needed.
     *
     * @param uuid player uuid
     */
    public void openPersonalQueue(UUID uuid) {
        if (uuid == null) return;
        // Idempotent bucket allocation.
        perPlayerLocationQueue.putIfAbsent(uuid, new ConcurrentLinkedQueue<>());

        // Push-on-open fill (ADR-043). Skip if a fill is already in flight
        // for this uuid, or if the bucket already holds at least one
        // coordinate (the player has nothing to gain from a second fill
        // until they consume the first one).
        ConcurrentLinkedQueue<RTPLocation> bucket = perPlayerLocationQueue.get(uuid);
        if (bucket != null && !bucket.isEmpty()) return;
        if (!perPlayerInFlight.add(uuid)) return;

        long maxNanos = 50_000_000L; // 50ms cap mirrors other RegionCacheTask budgets
        try {
            RegionCacheTask fill = new RegionCacheTask(region, uuid, maxNanos);
            RTP.scheduler.runTaskAsynchronously(fill);
        } catch (Throwable t) {
            // Never let listener wiring throw: release the guard and log.
            perPlayerInFlight.remove(uuid);
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP] openPersonalQueue: failed to schedule personal fill for "
                            + uuid + ": " + t, t);
        }
    }

    /**
     * Closes personal coordinate bucket for {@code uuid}, returning banked locations to unkept.
     *
     * @param uuid player uuid
     */
    public void closePersonalQueue(UUID uuid) {
        if (uuid == null) return;
        ConcurrentLinkedQueue<RTPLocation> bucket = perPlayerLocationQueue.remove(uuid);
        if (bucket != null) {
            RTPLocation loc;
            while ((loc = bucket.poll()) != null) {
                demoteToUnkept(loc);
            }
        }
        perPlayerInFlight.remove(uuid);
        // A close call does NOT remove uuid from playerQueue or
        // RTP.queuedPlayers - those are the teleport-intent state and have
        // their own lifecycle in Region.execute / RTPTeleportCancel.
    }

    /**
     * Enrolls {@code uuid} at the tail of {@link #playerQueue} for teleport pairing (ADR-043).
     *
     * @param uuid player uuid
     */
    public void requestTeleport(UUID uuid) {
        if (uuid == null) return;
        playerQueue.add(uuid);
        RTP.getInstance().queuedPlayers.add(uuid);
    }

    /**
     * Polls a location for a player: fastLocations -> personalQueue -> kept/unkept.
     *
     * @param uuid player uuid
     * @return future location or null if unavailable
     */
    public CompletableFuture<RTPLocation> poll(UUID uuid) {
        if (fastLocations.containsKey(uuid)) {
            return fastLocations.remove(uuid);
        }

        ConcurrentLinkedQueue<RTPLocation> playerLocationQueue = perPlayerLocationQueue.get(uuid);
        if (playerLocationQueue != null && !playerLocationQueue.isEmpty()) {
            RTPLocation loc = playerLocationQueue.poll();
            if (loc != null) {
                // Consume the personal-queue entry from the cache (it is about to be
                // served), then hand it back to the caller. The poll is driven
                // synchronously by the player's own in-flight TeleportPipelineTask
                // (QueueTask.pollNext -> here); that task IS latestTeleportData's
                // nextTask, so it must NOT be cancelled or re-dispatched here -- doing
                // so aborts the very teleport this poll is serving. The shared
                // keptLocations branch below likewise just returns the location and
                // lets the pipeline carry it through to completion.
                if (RTP.getInstance().databaseAccessor != null) {
                    RTP.getInstance().databaseAccessor.deleteCachedLocation(region.name, loc);
                }
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

    /**
     * Clear the hot (kept), cold (unkept), and backlog caches for this region,
     * releasing any held chunk reservations and dropping persisted rows via the
     * buffers' configured callbacks. Unlike {@link #shutDown()} this keeps the
     * region live: callbacks are left attached so the on-disk cache is purged in
     * step with the in-memory clear, and the scan/queue machinery is free to
     * refill the caches afterwards. Backs {@code /rtp clearcache}.
     */
    public void clearCaches() {
        keptLocations.clear();
        unkeptLocations.clear();
        if (backlogLocations != null) backlogLocations.clear();
    }

    public void shutDown() {
        // Disable DB removal during shutdown so the persisted rows survive for the next boot.
        keptLocations.setCallbacks(null, null);
        unkeptLocations.setCallbacks(null, null);
        keptLocations.clear();
        unkeptLocations.clear();
        // ADR-028: drop the backlog on shutdown. Entries are unverified candidate
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
    public boolean hasFastLocation(UUID uuid) {
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
