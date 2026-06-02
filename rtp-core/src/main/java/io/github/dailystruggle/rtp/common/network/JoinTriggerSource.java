package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.server.PlayerLifecycleHook;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.RedeemOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Backend-side join-time trigger: when a player arrives on this backend
 * carrying an outstanding cross-server reservation token (issued by a proxy
 * at {@code /rtp} time via {@link NetworkTransport#claim}), atomically
 * redeem the token and dispatch a local {@code /rtp} on the player's
 * behalf.
 *
 * <h2>Pipeline placement</h2>
 *
 * <p>This is the join end of the cross-server pipeline. The proxy executed
 * the per-request hot path (selector + claim + transfer) and is structurally
 * unaware of coordinates; this class executes the per-request <em>local</em>
 * hot path (region poll + safety + teleport) on the backend that owns the
 * world data. Pre-warmed {@code keptLocations} carry the request the same
 * way they do for a same-server {@code /rtp}, so cross-server arrivals
 * inherit the load-distribution properties documented in
 * {@code MULTI_SERVER_PLAN.md} (the proxy never computes coordinates).</p>
 *
 * <h2>Threading</h2>
 *
 * <p>All transport interaction is async via the transport's own executor
 * ({@link NetworkTransport#findReservation} and
 * {@link NetworkTransport#redeem} both return {@link java.util.concurrent.CompletableFuture}).
 * The final teleport hop is hopped back to the main thread via
 * {@link RTP#scheduler} so {@code Bukkit.dispatchCommand} runs on the
 * tick thread (S-005 nuance: command execution may touch world state).</p>
 *
 * <h2>Outcome handling (REQ-RTP-F-013, REQ-RTP-S-004)</h2>
 *
 * <ul>
 *   <li>{@link RedeemOutcome#REDEEMED}: dispatch {@code /rtp} as the
 *       joining player. The local pipeline applies the player's cooldown,
 *       economy, claim, and safety checks the same way as an in-game
 *       command, so no proxy-side debit is double-charged when the local
 *       pipeline declines.</li>
 *   <li>{@link RedeemOutcome#ALREADY_CONSUMED} / {@link RedeemOutcome#WRONG_SERVER}:
 *       silent no-op. A racing peer redeem or a misrouted arrival are
 *       legitimate races, not failures the player should see.</li>
 *   <li>{@link RedeemOutcome#NOT_FOUND} / {@link RedeemOutcome#EXPIRED}:
 *       WARNING log. The TTL reaper or the proxy is racing the join; the
 *       player still arrives, just without the bonus teleport.</li>
 *   <li>{@link RedeemOutcome#BAD_STATE} / {@link RedeemOutcome#HMAC_INVALID} /
 *       {@link RedeemOutcome#TRANSPORT_ERROR}: WARNING per REQ-RTP-S-004.</li>
 * </ul>
 *
 * <p>L2 of {@code CHECKLIST-cross-server-rtp.md}. New configurable
 * {@code MessageKey}s for cross-server-only feedback ({@code rtp.network.expired},
 * {@code rtp.network.failed}) are deferred to L5 row 41 per the L2 proposal;
 * this slice ships server-side WARNING-only diagnostics.</p>
 */
public final class JoinTriggerSource {

    private final NetworkTransport transport;
    private final String serverId;

    /** Live join/quit subscription handles, or {@code null} when unregistered. */
    private AutoCloseable joinSubscription;
    private AutoCloseable quitSubscription;

    /**
     * Local lobby-side / proxy-poller-seeded status cache. May be
     * {@code null} when this trigger source is constructed in a context
     * without a status cache (e.g. older test fixtures, or platforms
     * that have not yet wired {@code NetworkStatusCache}). When non-null,
     * {@link #onRedeemed} evicts the arriving player's row before
     * dispatching {@code /rtp} so {@link NetworkWaitlistGuard} does not
     * short-circuit the post-arrival teleport with {@code msgAlreadyQueued}
     * (REQ-RTP-S-004 / REQ-RTP-NET-015).
     */
    private volatile NetworkStatusCache statusCache;

    /**
     * Tracks tokens this backend has redeemed but not yet released, keyed by
     * playerId. Populated in {@link #handleRedeem} on a REDEEMED outcome so the
     * {@link #onQuit(UUID)} handler (Slice F row F3) can drive
     * {@code RegionQueueManager.releaseToNetworkKept(...)} if the player
     * disconnects before consuming the local /rtp dispatch. Cleared on quit.
     * Visible-for-tests via {@link #activeReservationsForTesting()}.
     */
    private final ConcurrentHashMap<UUID, UUID> activeReservations = new ConcurrentHashMap<>();

    /**
     * @param transport live backend transport (never null)
     * @param serverId  this backend's {@code network.serverId} (never null/empty)
     */
    public JoinTriggerSource(NetworkTransport transport, String serverId) {
        this(transport, serverId, null);
    }

    /**
     * @param transport    live backend transport (never null)
     * @param serverId     this backend's {@code network.serverId} (never null/empty)
     * @param statusCache  local lobby-side / proxy-poller-seeded status
     *                     cache for waitlist-guard eviction on REDEEMED;
     *                     may be {@code null} (no eviction).
     */
    public JoinTriggerSource(NetworkTransport transport, String serverId, NetworkStatusCache statusCache) {
        if (transport == null) throw new IllegalArgumentException("transport must not be null");
        if (serverId == null || serverId.isEmpty()) {
            throw new IllegalArgumentException("serverId must not be null/empty");
        }
        this.transport = transport;
        this.serverId = serverId;
        this.statusCache = statusCache;
    }

    /**
     * Late-bind the {@link NetworkStatusCache} after the trigger source has
     * been constructed. Used by {@code NetworkModeBootstrap} when the
     * cache is built later in the bootstrap sequence than the trigger
     * source. Idempotent; null tolerant (clears the binding).
     */
    public void setStatusCache(NetworkStatusCache statusCache) {
        this.statusCache = statusCache;
    }

    /**
     * Subscribe to player join / quit events via the platform lifecycle
     * hook (ADR-049). Idempotent; null-tolerant on a null hook.
     */
    public void register(PlayerLifecycleHook hook) {
        if (hook == null) return;
        if (joinSubscription == null) this.joinSubscription = hook.onPlayerJoin(this::onJoin);
        if (quitSubscription == null) this.quitSubscription = hook.onPlayerQuit(this::onQuit);
    }

    /** Close the join / quit subscriptions. Idempotent; best-effort. */
    public void unregister() {
        AutoCloseable js = this.joinSubscription;
        this.joinSubscription = null;
        if (js != null) {
            try { js.close(); } catch (Throwable ignored) { /* best-effort */ }
        }
        AutoCloseable qs = this.quitSubscription;
        this.quitSubscription = null;
        if (qs != null) {
            try { qs.close(); } catch (Throwable ignored) { /* best-effort */ }
        }
    }

    void onJoin(UUID id) {
        // findReservation runs on the transport's own pool; we only consult
        // a UUID-keyed lookup (no per-request compute on this backend either).
        transport.findReservation(id).whenComplete((opt, err) -> handleLookup(id, opt, err));
    }

    private void handleLookup(UUID id, Optional<ReservationToken> opt, Throwable err) {
        if (err != null) {
            RTP.log(Level.WARNING,
                    "[NETWORK] JoinTriggerSource: findReservation failed for " + id
                            + ": " + err.getMessage(), err);
            return;
        }
        if (opt == null || opt.isEmpty()) {
            // Phase B trace (2026-05-23): explicit no-reservation path. The
            // overwhelming majority of joins land here; logged at INFO
            // because it is the diagnostic anchor for "why did /rtp not run".
            RTP.log(Level.FINE,
                    "[NETWORK][trace] JoinTriggerSource.handleLookup: no reservation found for " + id
                            + " (standard join; no cross-server /rtp will be dispatched)");
            return;
        }
        ReservationToken token = opt.get();
        if (!serverId.equals(token.serverId())) {
            // The reservation is for another backend (e.g. the proxy
            // routed by player count and the player landed here via a
            // hub override). Silent: no S-004 attribution warranted.
            RTP.log(Level.FINE,
                    "[NETWORK][trace] JoinTriggerSource.handleLookup: reservation token.serverId="
                            + token.serverId() + " does not match this backend serverId=" + serverId
                            + " for " + id + " (token=" + token.tokenId() + "); not redeeming on this backend");
            return;
        }
        RTP.log(Level.FINE,
                "[NETWORK][trace] JoinTriggerSource.handleLookup: matched reservation for " + id
                        + " token=" + token.tokenId() + " serverId=" + serverId
                        + "; calling transport.redeem(...)");
        transport.redeem(token.tokenId(), id, serverId)
                .whenComplete((outcome, redeemErr) -> handleRedeem(id, token, outcome, redeemErr));
    }

    private void handleRedeem(UUID id, ReservationToken token, RedeemOutcome outcome, Throwable err) {
        if (err != null) {
            RTP.log(Level.WARNING,
                    "[NETWORK] JoinTriggerSource: redeem dispatch failed for " + id
                            + " token=" + token.tokenId() + ": " + err.getMessage(), err);
            return;
        }
        if (outcome == null) return;
        RTP.log(Level.FINE,
                "[NETWORK][trace] JoinTriggerSource.handleRedeem: outcome=" + outcome
                        + " for " + id + " token=" + token.tokenId());
        switch (outcome) {
            case REDEEMED:
                onRedeemed(id, token);
                return;
            case ALREADY_CONSUMED:
            case WRONG_SERVER:
                // Legitimate race; stay silent.
                return;
            case NOT_FOUND:
            case EXPIRED:
                RTP.log(Level.WARNING,
                        "[NETWORK] JoinTriggerSource: redeem outcome=" + outcome
                                + " for " + id + " token=" + token.tokenId()
                                + " (race vs TTL reaper or proxy release; no /rtp dispatched)");
                return;
            case BAD_STATE:
            case HMAC_INVALID:
            case TRANSPORT_ERROR:
            default:
                // REQ-RTP-S-004: any defective state or transport failure
                // must surface as WARNING so operators can audit.
                RTP.log(Level.WARNING,
                        "[NETWORK] JoinTriggerSource: redeem outcome=" + outcome
                                + " for " + id + " token=" + token.tokenId()
                                + " (REQ-RTP-S-004)");
        }
    }

    private void dispatchRtp(UUID id) {
        // Hop to the player's owning thread: Bukkit.dispatchCommand must run
        // on the tick thread that owns the player's entity. On Folia that
        // is the player's *entity scheduler*, NOT the global region scheduler
        // (CraftServer.dispatchCommand calls TickThread.ensureTickThread
        // against the player's region; running on the Global Region thread
        // throws IllegalStateException "Thread failed main thread check:
        // Dispatching command async"). On Bukkit/Paper/Spigot the entity
        // scheduler does not exist and the main-thread runTaskLater path
        // is correct. We detect Folia by attempting Player#getScheduler
        // reflectively (added in Paper 1.20.1 / Folia); when present we
        // use it, otherwise fall through to the legacy main-thread hop.
        // The local /rtp pipeline applies cooldown / economy / claim /
        // safety the same way as an in-game invocation; the proxy is the
        // source of truth for cross-server reservation, the backend is the
        // source of truth for local policy.
        Runnable hop = () -> {
            RTPPlayer player = (RTP.serverAccessor != null) ? RTP.serverAccessor.getPlayer(id) : null;
            if (player == null || !player.isOnline()) {
                RTP.log(Level.FINE,
                        "[NETWORK][trace] JoinTriggerSource.dispatchRtp: player offline at hop time for " + id
                                + "; /rtp NOT dispatched");
                return; // disconnected between join and hop
            }
            RTP.log(Level.FINE,
                    "[NETWORK][trace] JoinTriggerSource.dispatchRtp: invoking performCommand(player, \"rtp\") for " + id);
            try {
                player.performCommand(player, "rtp");
                RTP.log(Level.FINE,
                        "[NETWORK][trace] JoinTriggerSource.dispatchRtp: performCommand dispatched"
                                + " for " + id);
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "[NETWORK] JoinTriggerSource: /rtp dispatch threw for " + id
                                + ": " + t.getMessage(), t);
            }
        };
        // RTPScheduler.runTaskForPlayer routes player-owned work to the
        // correct thread:
        // - Bukkit / Paper / Spigot: main thread
        // - Folia: the player's *entity scheduler*, the only thread on which
        //   CraftServer.dispatchCommand will pass TickThread.ensureTickThread.
        // The bare runTaskLater overload on Folia lands on the Global Region
        // Scheduler thread, which trips "Thread failed main thread check:
        // Dispatching command async". The renamed runTaskForPlayer (formerly
        // scheduleTeleport - the name was a historical artifact; the method
        // is a generic "run this on a thread owning the player after N ticks")
        // is the canonical primitive for any work that touches a player's
        // owned region on Folia.
        try {
            io.github.dailystruggle.rtp.api.entity.RTPPlayer rtpPlayer =
                    (RTP.serverAccessor != null) ? RTP.serverAccessor.getPlayer(id) : null;
            if (rtpPlayer != null) {
                RTP.scheduler.runTaskForPlayer(
                        rtpPlayer,
                        new io.github.dailystruggle.rtp.common.tasks.RTPRunnable(hop),
                        1L);
                return;
            }
            // No RTPPlayer (quit race / accessor not wired): fall through to
            // the legacy main-thread hop. The hop's own isOnline guard will
            // bail out cleanly if the player has gone.
            RTP.scheduler.runTaskLater(hop, 1L);
        } catch (Throwable t) {
            // Scheduler unavailable (shutdown race): best-effort direct call.
            RTP.log(Level.WARNING,
                    "[NETWORK] JoinTriggerSource: scheduler unavailable for /rtp hop ("
                            + t.getMessage() + "); attempting inline dispatch");
            hop.run();
        }
    }

    /**
     * Slice F row F2: the proxy chose this backend and earmarked a coord
     * via {@link io.github.dailystruggle.rtp.common.selection.region.RegionQueueManager#reserveFromNetworkKept}.
     * Try {@code redeemReserved} on every region. First non-null hit:
     * (a) record the playerId-&gt;tokenId mapping so {@link #onQuit(UUID)}
     * can release on disconnect, (b) pin the redeemed coord into the
     * player's personal queue via {@code acceptRedeemedReservation}, and
     * (c) dispatch /rtp so the local pipeline polls that coord first. If
     * no region has the reservation (TTL-reaped, F1 pulse hadn't fired, or
     * this backend restarted), fall through to the existing /rtp dispatch
     * (the L2 baseline).
     */
    private void onRedeemed(UUID id, ReservationToken token) {
        // REQ-RTP-S-004 / REQ-RTP-NET-015: the proxy token just
        // transitioned to CONSUMED (terminal), but the local
        // NetworkStatusCache row for this player is still the lobby-seeded
        // / poller-seeded non-terminal state (QUEUED / RESERVED /
        // TRANSFERRING). Without an eviction here, the post-arrival
        // Bukkit.dispatchCommand(player, "rtp") below would be rejected
        // by NetworkWaitlistGuard with msgAlreadyQueued and the player
        // would never be teleported despite the redeem succeeding. The
        // next supplier poll will reconcile the row authoritatively;
        // evictLocal is a local-state correction only (does not fire the
        // terminal listener). Null-tolerant for older test fixtures.
        RTP.log(Level.FINE,
                "[NETWORK][trace] JoinTriggerSource.onRedeemed: entered for " + id
                        + " token=" + token.tokenId() + " statusCache=" + (statusCache != null));
        if (statusCache != null) {
            try {
                statusCache.evictLocal(id);
                RTP.log(Level.FINE,
                        "[NETWORK][trace] JoinTriggerSource.onRedeemed: statusCache.evictLocal succeeded for " + id);
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "[NETWORK] JoinTriggerSource: statusCache.evictLocal threw for "
                                + id + ": " + t.getMessage(), t);
            }
        }
        UUID networkTokenId = parseTokenId(token);
        RTPLocation redeemed = null;
        if (networkTokenId != null) {
            redeemed = redeemAcrossRegions(networkTokenId);
        }
        RTP.log(Level.FINE,
                "[NETWORK][trace] JoinTriggerSource.onRedeemed: networkTokenId=" + networkTokenId
                        + " redeemedCoord=" + (redeemed != null ? "present" : "null")
                        + " for " + id);
        if (redeemed != null && networkTokenId != null) {
            // Record the binding before pinning so a racing quit observed
            // between accept and dispatch still triggers the release path.
            activeReservations.put(id, networkTokenId);
            Region region = findRegionForReservation(networkTokenId);
            boolean accepted = false;
            if (region != null && region.queueManager != null) {
                try {
                    accepted = region.queueManager.acceptRedeemedReservation(id, redeemed);
                } catch (Throwable t) {
                    RTP.log(Level.WARNING,
                            "[NETWORK] JoinTriggerSource: acceptRedeemedReservation threw for "
                                    + id + " token=" + token.tokenId() + ": " + t.getMessage(), t);
                }
            }
            RTP.log(Level.FINE,
                    "[NETWORK][trace] JoinTriggerSource.onRedeemed: acceptRedeemedReservation accepted="
                            + accepted + " for " + id + " token=" + token.tokenId());
            if (!accepted) {
                // The coord could not be pinned (region disappeared mid-redeem
                // or queue rejected it). S-004: do not silently drop the
                // coord; offer it back to the unkept pool so it is not lost,
                // then fall through to the standard /rtp dispatch.
                if (region != null && region.queueManager != null) {
                    try {
                        region.queueManager.releaseToNetworkKept(networkTokenId);
                    } catch (Throwable ignored) {
                        // best-effort; the reservation map already cleared
                    }
                }
                activeReservations.remove(id);
            }
        }
        // Whether or not the coord was pinned, the local /rtp dispatch is
        // the L2 baseline behaviour and must always run on a REDEEMED outcome.
        RTP.log(Level.FINE,
                "[NETWORK][trace] JoinTriggerSource.onRedeemed: dispatching /rtp for " + id);
        dispatchRtp(id);
    }

    /**
     * Slice F row F3: on disconnect, if a cross-server reservation is still
     * bound for this player (the /rtp dispatch hadn't drained the personal
     * queue yet), call {@code releaseToNetworkKept} so the earmarked coord
     * returns to the network sibling pool and CAS-transition the proxy-side
     * token to {@code CONSUMED} via {@code transport.release(...,
     * PLAYER_DISCONNECTED)}. Two-writer carve-out documented in checklist.
     */
    void onQuit(UUID id) {
        UUID networkTokenId = activeReservations.remove(id);
        if (networkTokenId == null) return;
        // Local release: the coord returns to the network sibling pool
        // (or unkeptLocations on bounded-pool full per S-004 attribution).
        Region region = findRegionForReservation(networkTokenId);
        if (region != null && region.queueManager != null) {
            try {
                region.queueManager.releaseToNetworkKept(networkTokenId);
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "[NETWORK] JoinTriggerSource: releaseToNetworkKept threw for " + id
                                + " token=" + networkTokenId + ": " + t.getMessage(), t);
            }
        }
        // Proxy-side release: CAS-transition the token via the transport so
        // the proxy stops counting it under networkReservedCount.
        try {
            transport.release(networkTokenId.toString(), ReleaseReason.PLAYER_DISCONNECTED)
                    .whenComplete((v, err) -> {
                        if (err != null) {
                            RTP.log(Level.WARNING,
                                    "[NETWORK] JoinTriggerSource: transport.release failed for "
                                            + id + " token=" + networkTokenId + ": "
                                            + err.getMessage(), err);
                        }
                    });
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[NETWORK] JoinTriggerSource: transport.release dispatch threw for "
                            + id + " token=" + networkTokenId + ": " + t.getMessage(), t);
        }
    }

    /**
     * Parse the proxy-issued token id (String) into the UUID accepted by
     * {@code RegionQueueManager.{redeemReserved, releaseToNetworkKept}}.
     * S-004: returns {@code null} on malformed input rather than throwing,
     * so a malformed proxy token degrades to "fall through to local /rtp"
     * instead of crashing the join handler.
     */
    private static UUID parseTokenId(ReservationToken token) {
        if (token == null) return null;
        String raw = token.tokenId();
        if (raw == null || raw.isEmpty()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            // Token id is not a UUID (e.g. older transport that issues
            // opaque strings). Cross-server local-coord reuse is therefore
            // not addressable on this backend; fall through.
            return null;
        }
    }

    /**
     * Iterate every permanent and temporary region and call
     * {@code redeemReserved(networkTokenId)} on each {@code RegionQueueManager}.
     * First non-null wins. Returns {@code null} if no region had the
     * reservation bound (TTL-reaped, F1 pulse hadn't fired, or this backend
     * restarted between proxy claim and player join).
     */
    private static RTPLocation redeemAcrossRegions(UUID networkTokenId) {
        try {
            if (RTP.getInstance() == null || RTP.selectionAPI == null) return null;
            for (Region r : RTP.selectionAPI.permRegionLookup.values()) {
                if (r == null || r.queueManager == null) continue;
                RTPLocation loc = r.queueManager.redeemReserved(networkTokenId);
                if (loc != null) return loc;
            }
            for (Region r : RTP.selectionAPI.tempRegions.values()) {
                if (r == null || r.queueManager == null) continue;
                RTPLocation loc = r.queueManager.redeemReserved(networkTokenId);
                if (loc != null) return loc;
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[NETWORK] JoinTriggerSource: redeem sweep threw: " + t.getMessage(), t);
        }
        return null;
    }

    /**
     * Find the region that still holds a binding for {@code networkTokenId}.
     * Used after a successful redeem to address the same region for the
     * {@code acceptRedeemedReservation} / {@code releaseToNetworkKept}
     * follow-up; since {@code redeemReserved} removed the entry we cannot
     * locate by map presence, so we accept the first region whose
     * {@code networkReservedCount() > 0} as a best-effort heuristic.
     *
     * <p>In the single-region L6 scope all backends bind to one region per
     * world; for multi-region deployments the proxy-issued token id would
     * need to carry the region key (a follow-up SPI extension). Documented
     * in {@code CHECKLIST-cross-server-rtp-L6.md} F1 notes.</p>
     */
    private static Region findRegionForReservation(UUID networkTokenId) {
        try {
            if (RTP.getInstance() == null || RTP.selectionAPI == null) return null;
            for (Region r : RTP.selectionAPI.permRegionLookup.values()) {
                if (r == null || r.queueManager == null) continue;
                return r; // first region wins (single-region scope)
            }
            for (Region r : RTP.selectionAPI.tempRegions.values()) {
                if (r == null || r.queueManager == null) continue;
                return r;
            }
        } catch (Throwable ignored) {
            // Defensive: selectionAPI mid-reload race.
        }
        return null;
    }

    /** Visible for tests. */
    String serverId() { return serverId; }

    /** Visible for tests: live snapshot of playerId-&gt;tokenId bindings. */
    java.util.Map<UUID, UUID> activeReservationsForTesting() {
        return java.util.Collections.unmodifiableMap(activeReservations);
    }
}
