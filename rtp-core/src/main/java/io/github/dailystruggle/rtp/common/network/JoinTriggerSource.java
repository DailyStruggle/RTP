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
 * Backend-side join trigger that redeems cross-server reservation tokens.
 * Handles async token validation via {@link NetworkTransport#redeem} and
 * dispatches local {@code /rtp} on the player's owning thread (REQ-RTP-S-004).
 */
public final class JoinTriggerSource {

    private final NetworkTransport transport;
    private final String serverId;

    /** Live join/quit subscription handles, or {@code null} when unregistered. */
    private AutoCloseable joinSubscription;
    private AutoCloseable quitSubscription;

    /**
     * Local status cache for waitlist-guard eviction on REDEEMED (REQ-RTP-S-004 / REQ-RTP-NET-015).
     * Null when constructing without a status cache.
     */
    private volatile NetworkStatusCache statusCache;

    /**
     * Tracks tokens this backend has redeemed but not yet released, keyed by
     * playerId. Populated in {@link #handleRedeem} on a REDEEMED outcome so the
     * {@link #onQuit(UUID)} handler can drive
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
                    "[RTP] JoinTriggerSource: findReservation failed for " + id
                            + ": " + err.getMessage(), err);
            return;
        }
        if (opt == null || opt.isEmpty()) {
            // Phase B trace (2026-05-23): explicit no-reservation path. The
            // overwhelming majority of joins land here; logged at INFO
            // because it is the diagnostic anchor for "why did /rtp not run".
            RTP.log(Level.FINE,
                    "[RTP][trace] JoinTriggerSource.handleLookup: no reservation found for " + id
                            + " (standard join; no cross-server /rtp will be dispatched)");
            return;
        }
        ReservationToken token = opt.get();
        if (!serverId.equals(token.serverId())) {
            // The reservation is for another backend (e.g. the proxy
            // routed by player count and the player landed here via a
            // hub override). Silent: no S-004 attribution warranted.
            RTP.log(Level.FINE,
                    "[RTP][trace] JoinTriggerSource.handleLookup: reservation token.serverId="
                            + token.serverId() + " does not match this backend serverId=" + serverId
                            + " for " + id + " (token=" + token.tokenId() + "); not redeeming on this backend");
            return;
        }
        RTP.log(Level.FINE,
                "[RTP][trace] JoinTriggerSource.handleLookup: matched reservation for " + id
                        + " token=" + token.tokenId() + " serverId=" + serverId
                        + "; calling transport.redeem(...)");
        transport.redeem(token.tokenId(), id, serverId)
                .whenComplete((outcome, redeemErr) -> handleRedeem(id, token, outcome, redeemErr));
    }

    private void handleRedeem(UUID id, ReservationToken token, RedeemOutcome outcome, Throwable err) {
        if (err != null) {
            RTP.log(Level.WARNING,
                    "[RTP] JoinTriggerSource: redeem dispatch failed for " + id
                            + " token=" + token.tokenId() + ": " + err.getMessage(), err);
            return;
        }
        if (outcome == null) return;
        RTP.log(Level.FINE,
                "[RTP][trace] JoinTriggerSource.handleRedeem: outcome=" + outcome
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
                        "[RTP] JoinTriggerSource: redeem outcome=" + outcome
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
                        "[RTP] JoinTriggerSource: redeem outcome=" + outcome
                                + " for " + id + " token=" + token.tokenId()
                                + " (REQ-RTP-S-004)");
        }
    }

    private void dispatchRtp(UUID id, Optional<String> regionKey) {
        // Re-attach the cross-server region intent (carried on the reservation
        // token from the player's `/rtp region=<server>:<region>` command) so
        // the local pipeline teleports into the requested region rather than
        // the backend default. Regionless requests dispatch a bare `rtp`.
        final String command = (regionKey != null && regionKey.isPresent()
                && !regionKey.get().isEmpty())
                ? "rtp region=" + regionKey.get()
                : "rtp";
        // Hop to the player's owning thread (entity scheduler on Folia, main thread on Bukkit).
        Runnable hop = () -> {
            RTPPlayer player = (RTP.serverAccessor != null) ? RTP.serverAccessor.getPlayer(id) : null;
            if (player == null || !player.isOnline()) {
                RTP.log(Level.FINE,
                        "[RTP][trace] JoinTriggerSource.dispatchRtp: player offline at hop time for " + id
                                + "; /rtp NOT dispatched");
                return; // disconnected between join and hop
            }
            RTP.log(Level.FINE,
                    "[RTP][trace] JoinTriggerSource.dispatchRtp: invoking performCommand(player, \""
                            + command + "\") for " + id);
            try {
                player.performCommand(player, command);
                RTP.log(Level.FINE,
                        "[RTP][trace] JoinTriggerSource.dispatchRtp: performCommand dispatched"
                                + " for " + id);
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "[RTP] JoinTriggerSource: /rtp dispatch threw for " + id
                                + ": " + t.getMessage(), t);
            }
        };
        // RTPScheduler.runTaskForPlayer routes player-owned dispatch to the owning thread/scheduler.
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
                    "[RTP] JoinTriggerSource: scheduler unavailable for /rtp hop ("
                            + t.getMessage() + "); attempting inline dispatch");
            hop.run();
        }
    }

    /**
     * Redeems reserved coordinate across regions and pins it to the player's queue.
     * Dispatches /rtp or falls back to regular dispatch if no reservation is held.
     */
    private void onRedeemed(UUID id, ReservationToken token) {
        // REQ-RTP-S-004 / REQ-RTP-NET-015: evict local status cache row so post-arrival
        // command dispatch is not blocked by NetworkWaitlistGuard.
        RTP.log(Level.FINE,
                "[RTP][trace] JoinTriggerSource.onRedeemed: entered for " + id
                        + " token=" + token.tokenId() + " statusCache=" + (statusCache != null));
        if (statusCache != null) {
            try {
                statusCache.evictLocal(id);
                RTP.log(Level.FINE,
                        "[RTP][trace] JoinTriggerSource.onRedeemed: statusCache.evictLocal succeeded for " + id);
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "[RTP] JoinTriggerSource: statusCache.evictLocal threw for "
                                + id + ": " + t.getMessage(), t);
            }
        }
        UUID networkTokenId = parseTokenId(token);
        RTPLocation redeemed = null;
        if (networkTokenId != null) {
            redeemed = redeemAcrossRegions(networkTokenId);
        }
        RTP.log(Level.FINE,
                "[RTP][trace] JoinTriggerSource.onRedeemed: networkTokenId=" + networkTokenId
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
                            "[RTP] JoinTriggerSource: acceptRedeemedReservation threw for "
                                    + id + " token=" + token.tokenId() + ": " + t.getMessage(), t);
                }
            }
            RTP.log(Level.FINE,
                    "[RTP][trace] JoinTriggerSource.onRedeemed: acceptRedeemedReservation accepted="
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
                "[RTP][trace] JoinTriggerSource.onRedeemed: dispatching /rtp for " + id
                        + (token.regionKey().isPresent()
                                ? " region=" + token.regionKey().get() : ""));
        dispatchRtp(id, token.regionKey());
    }

    /**
     * On disconnect, if a cross-server reservation is still
     * bound for this player (the /rtp dispatch hadn't drained the personal
     * queue yet), call {@code releaseToNetworkKept} so the earmarked coord
     * returns to the network sibling pool and CAS-transition the proxy-side
     * token to {@code CONSUMED} via {@code transport.release(...,
     * PLAYER_DISCONNECTED)}.
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
                        "[RTP] JoinTriggerSource: releaseToNetworkKept threw for " + id
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
                                    "[RTP] JoinTriggerSource: transport.release failed for "
                                            + id + " token=" + networkTokenId + ": "
                                            + err.getMessage(), err);
                        }
                    });
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP] JoinTriggerSource: transport.release dispatch threw for "
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
                    "[RTP] JoinTriggerSource: redeem sweep threw: " + t.getMessage(), t);
        }
        return null;
    }

    /**
     * Finds the region associated with {@code networkTokenId} for reservation release.
     * Falls back to first active region when the reservation mapping was already removed.
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
