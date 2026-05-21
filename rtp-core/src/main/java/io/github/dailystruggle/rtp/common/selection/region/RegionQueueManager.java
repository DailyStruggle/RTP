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
     * Cross-server sibling of {@link #keptLocations} for L6 (PROPOSAL
     * §12.2, CHECKLIST Slice A). Identical lifecycle (kept-with-ticket
     * semantics, fed by the scan/verify pipeline, drained by the network
     * reservation pulse) but isolated from local {@code /rtp} consumption so
     * local players draining {@code keptLocations} cannot starve cross-server
     * requests and vice versa.
     *
     * <p>Allocated only when {@code settings.networkReserveSize() > 0};
     * {@code null} otherwise (the L6 D2 default = local routing means most
     * deployments will leave this off until they opt in). Capacity is
     * clamped to {@code min(networkReserveSize, cacheCap)}.
     */
    @org.jetbrains.annotations.Nullable
    public final LockFreeLocationBuffer networkKeptLocations;

    /**
     * In-flight pinned reservations for cross-server transfers (L6
     * PROPOSAL §12, CHECKLIST Slice A row A3). Keyed by the proxy-issued
     * {@code networkTokenId} (see {@code ReservationToken} in
     * {@code rtp-proxy-common}). An entry exists between
     * {@link #reserveFromNetworkKept(UUID, String)} (called by the backend
     * reservation pulse, Slice F row F1) and
     * {@link #redeemReserved(UUID)} (called by {@code JoinTriggerSource}
     * post-transfer, Slice F row F2) or {@link #releaseToNetworkKept(UUID)}
     * (TTL reaper / disconnect, Slice F row F3 + Slice D row D7).
     *
     * <p>Never {@code null}; empty when no reservations are outstanding.
     */
    public final ConcurrentHashMap<UUID, RTPLocation> networkReservedLocations =
            new ConcurrentHashMap<>();

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
            // L6 PROPOSAL §12.2: networkKeptLocations is a sibling pool capped
            // by min(networkReserveSize, cacheCap). 0 disables the network split
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
        if (this.networkKeptLocations != null) {
            this.networkKeptLocations.setCallbacks(saveCallback, deleteCallback);
        }
    }

    // -------------------------------------------------------------------------
    // L6 network reservation API (CHECKLIST Slice A row A4).
    //
    // Reserve a coordinate from networkKeptLocations under a proxy-issued
    // networkTokenId. Returns the reserved RTPLocation on success, null when
    // the network sibling pool is absent or empty. On success the location is
    // removed from networkKeptLocations and pinned in networkReservedLocations
    // until redeemReserved/releaseToNetworkKept is called.
    //
    // regionKey is accepted for API symmetry with the cross-server selector
    // predicate (Slice B); current single-region scope ignores it but
    // implementers MUST keep the parameter so future per-region routing does
    // not require an API churn.
    //
    // S-005 note: this method does no chunk I/O. The chunk reservation is
    // already pinned on the candidate location at the moment it landed in
    // networkKeptLocations (same lifecycle as keptLocations).
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
     * Redeem a previously reserved coordinate. Removes the entry from
     * {@link #networkReservedLocations} and returns the bound location.
     * The caller is expected to consume it (e.g. teleport on join, Slice F row F2).
     * Subsequent calls with the same token return {@code null}.
     *
     * @param networkTokenId proxy-issued token id
     * @return the reserved location, or {@code null} if no reservation is bound
     */
    public RTPLocation redeemReserved(UUID networkTokenId) {
        if (networkTokenId == null) return null;
        return networkReservedLocations.remove(networkTokenId);
    }

    /**
     * Release a previously reserved coordinate back to {@link #networkKeptLocations}.
     * Used by the TTL reaper (Slice D row D7), disconnect listener (Slice F
     * row F3), and any failed-transfer cleanup path. Idempotent: a second call
     * with the same token id is a no-op.
     *
     * @param networkTokenId proxy-issued token id
     * @return {@code true} if a reservation was released, {@code false} otherwise
     */
    public boolean releaseToNetworkKept(UUID networkTokenId) {
        if (networkTokenId == null) return false;
        RTPLocation loc = networkReservedLocations.remove(networkTokenId);
        if (loc == null) return false;
        if (this.networkKeptLocations != null) {
            // offer is bounded; if the sibling pool happens to be full (capacity
            // shrunk by reload, or operator manually drained), drop to unkept so
            // the coordinate is not silently lost (S-004 attribution rule).
            if (!this.networkKeptLocations.offer(loc)) {
                this.unkeptLocations.offer(new RTPLocation(loc.coords(), loc.attempts(), null));
            }
        } else {
            this.unkeptLocations.offer(new RTPLocation(loc.coords(), loc.attempts(), null));
        }
        return true;
    }

    /**
     * Pin a previously-redeemed cross-server reservation coordinate into
     * {@code playerId}'s personal queue so the immediately-following local
     * {@code /rtp} dispatch preferentially polls that coord. Used by the
     * backend-side {@code JoinTriggerSource} on REDEEMED hit (Slice F row F2):
     * the proxy already chose this backend at /rtp time and the coord was
     * earmarked via {@link #reserveFromNetworkKept(UUID, String)}; on join
     * the redeemed coord must reach the player without re-running the
     * region selection that produced it.
     *
     * <p>S-004 contract: returns {@code true} when the coord was accepted
     * into the personal queue (the player's /rtp will see it first);
     * {@code false} when {@code playerId} or {@code loc} is null. Internal
     * delegation to {@link #enqueuePlayerLocation(UUID, RTPLocation)}
     * preserves the per-player single-slot invariant.</p>
     *
     * @param playerId joining player's UUID; must be non-null
     * @param loc      redeemed reservation coordinate; must be non-null
     * @return {@code true} if the coord was pinned to the personal queue
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
     * Open a personal coordinate bucket for {@code uuid} in this region (ADR-043).
     *
     * <p>Idempotent. This is the {@code rtp.personalqueue} opt-in proper: it
     * declares "subsequent pregen output for this region MAY earmark a
     * coordinate into a bucket dedicated to {@code uuid}, instead of (or in
     * addition to) the shared {@code keptLocations} pool." It does
     * <strong>NOT</strong> request a teleport, does NOT enroll {@code uuid} in
     * {@link #playerQueue}, and does NOT add {@code uuid} to
     * {@link RTP#queuedPlayers}.
     *
     * <p>Schedules a single push-on-open {@link RegionCacheTask} to fill the
     * bucket when no fill is already in flight for {@code uuid} (per-uuid
     * in-flight guard via {@link #perPlayerInFlight}).
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
     * Close the personal coordinate bucket for {@code uuid} in this region
     * (ADR-043). Returns any banked coordinates to {@link #unkeptLocations}
     * (closing their reservations first), so the pregen budget is not stranded
     * by player churn. Called on disconnect / world change away from this
     * region's world. Idempotent.
     *
     * @param uuid player uuid
     */
    public void closePersonalQueue(UUID uuid) {
        if (uuid == null) return;
        ConcurrentLinkedQueue<RTPLocation> bucket = perPlayerLocationQueue.remove(uuid);
        if (bucket != null) {
            RTPLocation loc;
            while ((loc = bucket.poll()) != null) {
                if (loc.reservation() != null) {
                    try {
                        loc.reservation().close();
                    } catch (Throwable ignored) {
                        // best-effort close
                    }
                }
                unkeptLocations.offer(new RTPLocation(loc.coords(), loc.attempts(), null));
            }
        }
        perPlayerInFlight.remove(uuid);
        // A close call does NOT remove uuid from playerQueue or
        // RTP.queuedPlayers — those are the teleport-intent state and have
        // their own lifecycle in Region.execute / RTPTeleportCancel.
    }

    /**
     * Enroll {@code uuid} at the tail of {@link #playerQueue} so
     * {@link Region#execute(long)} pairs them with the next available
     * coordinate (ADR-043 concern (2)+(3)). The caller must already have
     * populated {@code latestTeleportData} for {@code uuid}; stale entries are
     * purged defensively by {@code Region.execute}.
     *
     * @param uuid player uuid
     */
    public void requestTeleport(UUID uuid) {
        if (uuid == null) return;
        playerQueue.add(uuid);
        RTP.getInstance().queuedPlayers.add(uuid);
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
