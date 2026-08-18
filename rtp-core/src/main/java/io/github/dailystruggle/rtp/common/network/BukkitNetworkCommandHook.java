package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.api.configuration.enums.NetworkMessages;
import io.github.dailystruggle.rtp.api.network.NetworkCommandHook;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Bukkit implementation of the {@link NetworkCommandHook} SPI (rtp-proxy-ADR-014).
 * Wires cross-server routing, enrolment buffering, and peer regions into command pre-dispatch.
 */
public final class BukkitNetworkCommandHook implements NetworkCommandHook {

    private final NetworkRouter router;
    private final NetworkEnrolmentBuffer enrolmentBuffer;
    /** Live peer-region view used by lobby-mode no-arg dispatch. */
    private final PeerRegionRegistry peerRegionRegistry;
    /** When true, no-arg {@code /rtp} auto-routes to a remote peer. */
    private final boolean lobbyMode;
    /**
     * Optional. When non-null, every successful cross-server dispatch
     * triggers an off-cadence heartbeat publish so peers see this lobby's
     * fresh {@code keptCount}/{@code regionKeptCounts} (decremented locally
     * for the chosen peer via {@link PeerRegionRegistry#recordDispatch})
     * without waiting for the next scheduled tick. Null is tolerated for
     * test paths and for non-network deployments.
     */
    private final BackendStatePublisher backendStatePublisher;
    /**
     * Optional retry queue. When non-null in lobbyMode, transient fallbacks park here
     * with synthetic {@link RoutingResult#crossServer} instead of dead-ending locally.
     */
    private final LobbyDispatchRetryQueue lobbyRetryQueue;
    /**
     * Optional lobby enrolment seeder. Pre-seeds sticky {@code QUEUED} in status cache
     * so proxy dropouts time out and release the {@code processingPlayers} lock.
     */
    private volatile java.util.function.Consumer<UUID> enrolmentSeeder = u -> {};

    /**
     * Install the lobby-side enrolment seeder. See {@link #enrolmentSeeder}.
     * Null collapses to a no-op.
     *
     * @param seeder the seeder to install; {@code null} installs a no-op
     */
    public void setEnrolmentSeeder(java.util.function.Consumer<UUID> seeder) {
        this.enrolmentSeeder = seeder == null ? u -> {} : seeder;
    }

    /**
     * Legacy 2-arg ctor. Equivalent to {@code lobbyMode = false}
     * and a null {@code peerRegionRegistry}; the hook will not synthesise a
     * lobby target for no-arg {@code /rtp}.
     *
     * @param router           the network router; never {@code null}
     * @param enrolmentBuffer  the enrolment buffer; never {@code null}
     */
    public BukkitNetworkCommandHook(NetworkRouter router, NetworkEnrolmentBuffer enrolmentBuffer) {
        this(router, enrolmentBuffer, null, false, null, null);
    }

    /**
     * When {@code lobbyMode == true} AND the player invoked
     * {@code /rtp} with no {@code region=} argument, the hook consults
     * {@code peerRegionRegistry.pickMostKept()} to synthesise a
     * {@code (serverHint, regionKey)} pair and routes through
     * {@link NetworkRouter#route(UUID, String, String)} with hard-pin
     * semantics. When the registry returns empty (no peer advertises any
     * region) the hook emits the localized {@code networkRegionUnavailable}
     * message so the player learns that the lobby has nothing to dispatch
     * to - silently falling through to the local pipeline would be wrong
     * because on a true lobby backend the local pipeline has no regions to
     * serve from either.
     *
     * @param peerRegionRegistry registry view; may be {@code null} when
     *                           {@code lobbyMode == false}
     * @param lobbyMode          true to enable no-arg lobby dispatch
     * @param router             the network router; never {@code null}
     * @param enrolmentBuffer    the enrolment buffer; never {@code null}
     */
    public BukkitNetworkCommandHook(NetworkRouter router,
                                    NetworkEnrolmentBuffer enrolmentBuffer,
                                    PeerRegionRegistry peerRegionRegistry,
                                    boolean lobbyMode) {
        this(router, enrolmentBuffer, peerRegionRegistry, lobbyMode, null, null);
    }

    /**
     * Extended ctor. Adds the optional {@code backendStatePublisher}
     * so cross-server dispatches can force an immediate heartbeat publish,
     * propagating the local {@code recordDispatch} decrement to peers before
     * the next scheduled tick. The publisher is allowed to be {@code null}
     * (test paths, non-network deployments); the hook silently skips the
     * forced publish in that case.
     *
     * @param router                the network router; never {@code null}
     * @param enrolmentBuffer       the enrolment buffer; never {@code null}
     * @param peerRegionRegistry    peer region registry; may be {@code null}
     * @param lobbyMode             true to enable no-arg lobby dispatch
     * @param backendStatePublisher optional publisher for forced heartbeats; may be {@code null}
     */
    public BukkitNetworkCommandHook(NetworkRouter router,
                                    NetworkEnrolmentBuffer enrolmentBuffer,
                                    PeerRegionRegistry peerRegionRegistry,
                                    boolean lobbyMode,
                                    BackendStatePublisher backendStatePublisher) {
        this(router, enrolmentBuffer, peerRegionRegistry, lobbyMode, backendStatePublisher, null);
    }

    /**
     * Lobby-auto-retry ctor. Adds the optional {@code lobbyRetryQueue}
     * so a transient {@link RoutingDecision.LocalFallback} in lobbyMode
     * parks the player on the queue (which retries on its own pulse)
     * instead of silently falling through to a dead local pipeline.
     * Allowed to be {@code null}; when null, the hook preserves the
     * prior behaviour of returning {@link RoutingResult#local()} for
     * transient fallbacks.
     *
     * @param router                the network router; never {@code null}
     * @param enrolmentBuffer       the enrolment buffer; never {@code null}
     * @param peerRegionRegistry    peer region registry; may be {@code null}
     * @param lobbyMode             true to enable no-arg lobby dispatch
     * @param backendStatePublisher optional publisher for forced heartbeats; may be {@code null}
     * @param lobbyRetryQueue       optional retry queue for transient fallbacks; may be {@code null}
     */
    public BukkitNetworkCommandHook(NetworkRouter router,
                                    NetworkEnrolmentBuffer enrolmentBuffer,
                                    PeerRegionRegistry peerRegionRegistry,
                                    boolean lobbyMode,
                                    BackendStatePublisher backendStatePublisher,
                                    LobbyDispatchRetryQueue lobbyRetryQueue) {
        this.router = Objects.requireNonNull(router, "router");
        this.enrolmentBuffer = Objects.requireNonNull(enrolmentBuffer, "enrolmentBuffer");
        this.peerRegionRegistry = peerRegionRegistry;
        this.lobbyMode = lobbyMode;
        this.backendStatePublisher = backendStatePublisher;
        this.lobbyRetryQueue = lobbyRetryQueue;
        if (lobbyMode && peerRegionRegistry == null) {
            throw new IllegalArgumentException(
                    "lobbyMode=true requires a non-null peerRegionRegistry");
        }
    }

    @Override
    public RoutingResult route(UUID playerId, Map<String, List<String>> args) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(args, "args");

        // Extract the raw region= arg, if any. commands-api parses
        // `region=backend-a:default` into args.get("region") = ["backend-a:default"].
        String regionArg = null;
        List<String> regionList = args.get("region");
        if (regionList != null && !regionList.isEmpty()) {
            regionArg = regionList.get(0);
        }

        String regionKey;
        String serverHint;

        // Lobby-mode no-arg dispatch. When the operator opted
        // into lobbyMode AND the player typed a bare `/rtp` with no
        // `region=` argument, synthesise a (serverHint, regionKey) target
        // from PeerRegionRegistry.pickMostKept() and route as if the
        // player had typed `rtp region=<server>:<region>`. Hard-pin
        // semantics fall out for free via the existing router path.
        //
        // No peers picking? Reject visibly - a lobby backend has nothing
        // to serve locally either, so falling through to local would just
        // produce an opaque "no regions defined" error from the local
        // pipeline. The localized message is more honest.
        if (lobbyMode && (regionArg == null || regionArg.isEmpty())) {
            java.util.Optional<io.github.dailystruggle.rtp.proxy.common.selector.ServerRegion> pick =
                    peerRegionRegistry.pickMostKept();
            if (pick.isEmpty()) {
                return RoutingResult.reject(
                        NetworkMessages.networkRegionUnavailable.name(), "");
            }
            regionKey = pick.get().regionKey();
            serverHint = pick.get().serverId();
        } else {
            NetworkRouter.ParsedRegion parsed;
            try {
                parsed = NetworkRouter.parseRegionArgQualified(regionArg);
            } catch (IllegalArgumentException malformed) {
                // Reject malformed `server:region` syntax with the same message
                // that "no live backend hosts that region" uses. We give the
                // raw input as the [region] placeholder so the player sees
                // exactly what they typed.
                return RoutingResult.reject(
                        NetworkMessages.networkRegionUnavailable.name(),
                        regionArg == null ? "" : regionArg);
            }
            regionKey = (parsed == null) ? null : parsed.regionKey();
            serverHint = (parsed == null) ? null : parsed.serverHint();
        }

        RoutingDecision decision = router.route(playerId, regionKey, serverHint);

        if (decision instanceof RoutingDecision.Local) {
            RTP.log(Level.FINE,
                    "[RTP][state] route: playerId=" + playerId
                            + " regionArg=" + regionArg + " -> LOCAL");
            return RoutingResult.local();
        }
        if (decision instanceof RoutingDecision.CrossServer cs) {
            UUID correlationId = UUID.randomUUID();
            long now = System.currentTimeMillis();
            enrolmentBuffer.offer(new NetworkEnrolmentBuffer.EnrolmentRecord(
                    playerId,
                    correlationId,
                    cs.regionKey(),
                    cs.serverHint(),
                    now));
            // Pre-seed the local status cache with a sticky QUEUED row so
            // anti-spam guards see the player as non-terminal immediately
            // (before the first proxy roundtrip) AND so a never-responding
            // proxy times out via the cache's sticky TTL and releases the
            // processingPlayers lock. Defensive try: a seeder bug must
            // never break the dispatch.
            try { enrolmentSeeder.accept(playerId); } catch (Throwable ignored) {}
            // Record the original (args, messageMethod) on the lobby
            // retry queue so that if the proxy never produces a
            // terminal transition (sticky-TTL FAILED), the bootstrap's
            // terminal listener can auto-re-park the player via
            // LobbyDispatchRetryQueue.onTerminalFailure(...) instead of
            // forcing them to retype /rtp. No-op when the queue is null
            // (non-lobby topology).
            if (lobbyRetryQueue != null) {
                try { lobbyRetryQueue.recordEnrolment(playerId, args, null); }
                catch (Throwable ignored) {}
            }
            // Bump our local view of the chosen peer's
            // kept count so the next pickMostKept() on this JVM does not
            // pile the next /rtp burst onto the same backend before the
            // peer's next heartbeat arrives. Then force an immediate
            // heartbeat publish so peers also see our fresh decrement.
            // Both calls are no-ops when their dependency is null (legacy
            // 2-arg ctor; non-lobby topology).
            String resolvedServer = cs.serverHint().orElse(null);
            String resolvedRegion = cs.regionKey().orElse(null);
            if (peerRegionRegistry != null && resolvedServer != null && resolvedRegion != null) {
                try {
                    peerRegionRegistry.recordDispatch(resolvedServer, resolvedRegion);
                } catch (Throwable ignored) {
                    // Defensive: recordDispatch must never break dispatch.
                }
            }
            if (backendStatePublisher != null) {
                try {
                    backendStatePublisher.publishNow();
                } catch (Throwable ignored) {
                    // Defensive: an off-cadence publish failure is fine -
                    // the next scheduled tick (1s default) will catch up.
                }
            }
            // The SPI's CrossServer record carries the *display* values
            // (regionKey + serverHint) which the caller substitutes into
            // the `networkQueued` template. We forward whatever the router
            // resolved, NOT the raw player input (so e.g. mode=auto's
            // resolved hint shows up; in v1 these are usually equal).
            RTP.log(Level.FINE,
                    "[RTP][state] route: playerId=" + playerId
                            + " -> CROSS_SERVER correlationId=" + correlationId
                            + " region=" + cs.regionKey().orElse("")
                            + " server=" + cs.serverHint().orElse(""));
            return RoutingResult.crossServer(
                    correlationId,
                    cs.regionKey().orElse(null),
                    cs.serverHint().orElse(null));
        }
        if (decision instanceof RoutingDecision.LocalFallback fallback) {
            // Hard-pin UX rule (user-confirmed H2 sign-off, choice 3a):
            // when the player explicitly asked for a region/server that
            // is not reachable, we reject loudly so they don't get
            // silently teleported to a different region than they asked
            // for.
            if (fallback.reason() == RoutingDecision.FallbackReason.REGION_UNAVAILABLE) {
                String placeholder = formatPlaceholder(serverHint, regionKey, regionArg);
                RTP.log(Level.FINE,
                        "[RTP][state] route: playerId=" + playerId
                                + " regionArg=" + regionArg
                                + " -> REJECT(REGION_UNAVAILABLE)");
                return RoutingResult.reject(
                        NetworkMessages.networkRegionUnavailable.name(),
                        placeholder);
            }
            // Lobby auto-retry (user-confirmed, this issue): on a lobby
            // backend the local pipeline has no RTP regions to fall
            // back to, so silently returning RoutingResult.local() lands
            // the player in a "non-processing" state (no message, no
            // teleport, lock dropped, must retype /rtp). For the
            // transient gates (NETWORK_DISABLED snapshot warm-up,
            // NO_LIVE_PEER heartbeat lapse, QUEUE_FULL spike,
            // TOKEN_BUCKET_EXHAUSTED burst), park the player on the
            // LobbyDispatchRetryQueue. The queue's pulse will re-invoke
            // router.route() every ~500ms (bounded by maxAttempts and
            // maxTotalMs) and either enrol the player on the cross-
            // server queue (transient cleared) or emit a terminal
            // rejection (TTL exhausted). We return a synthetic
            // RoutingResult.crossServer here so RTPCmd.compute sends
            // the networkQueued message ONCE and retains the
            // processingPlayers lock for the duration of the retry.
            // The synthetic correlationId is never sent over the wire;
            // the queue allocates a real one on success.
            if (lobbyMode && lobbyRetryQueue != null) {
                RTP.log(Level.FINE,
                        "[RTP][state] route: playerId=" + playerId
                                + " regionArg=" + regionArg
                                + " -> SYNTHETIC_CROSS_SERVER(parked, reason=" + fallback.reason() + ")");
                lobbyRetryQueue.enqueue(playerId, args, fallback.reason(), null);
                return RoutingResult.crossServer(
                        UUID.randomUUID(),
                        regionKey,
                        serverHint);
            }
            // Non-lobby (dual-role backend) OR retry queue not wired:
            // graceful silent local fall-through is correct because the
            // local pipeline genuinely can serve /rtp.
            RTP.log(Level.FINE,
                    "[RTP][state] route: playerId=" + playerId
                            + " regionArg=" + regionArg
                            + " -> LOCAL_FALLBACK(reason=" + fallback.reason() + ")");
            return RoutingResult.local();
        }

        // Defensive: unknown subtype. Per S-004, never silently swallow -
        // throwing degrades to local with a WARNING log via RTPCmd's outer
        // try/catch.
        throw new IllegalStateException(
                "unhandled RoutingDecision subtype: " + decision.getClass().getName());
    }

    /**
     * Build the {@code [region]} placeholder value for the
     * {@code networkRegionUnavailable} message. Prefers the qualified form
     * {@code server:region} when both halves are present, falls back to the
     * raw player input otherwise.
     */
    private static String formatPlaceholder(String serverHint, String regionKey, String rawArg) {
        if (serverHint != null && !serverHint.isEmpty()
                && regionKey != null && !regionKey.isEmpty()) {
            return serverHint + ":" + regionKey;
        }
        if (regionKey != null && !regionKey.isEmpty()) return regionKey;
        return rawArg == null ? "" : rawArg;
    }

    /**
     * Visible for tests.
     *
     * @return the network router
     */
    public NetworkRouter router() { return router; }

    /**
     * Visible for tests.
     *
     * @return the enrolment buffer
     */
    public NetworkEnrolmentBuffer enrolmentBuffer() { return enrolmentBuffer; }
}
