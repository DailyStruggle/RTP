package io.github.dailystruggle.rtp.bukkit.network;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.network.NetworkCommandHook;
import io.github.dailystruggle.rtp.common.network.BackendStatePublisher;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bukkit-platform implementation of the {@link NetworkCommandHook} SPI.
 * L6 Slice H2 (rtp-proxy-ADR-014).
 *
 * <p>Wires the cross-server router + enrolment buffer + peer-region registry
 * into the {@code /rtp} command pre-dispatch path defined by Slice H1's
 * {@code RTPCmd.compute(...)}. Responsibilities, in order on each call:</p>
 *
 * <ol>
 *   <li>Extract {@code region=<arg>} from the parsed args map (if any).</li>
 *   <li>Parse via {@link NetworkRouter#parseRegionArgQualified(String)} to
 *       split out an optional {@code server:region} qualifier. Malformed
 *       input rejects with the localized
 *       {@link MessagesKeys#networkRegionUnavailable} message; the player's
 *       region argument never silently falls through to the local pipeline.</li>
 *   <li>Ask {@link NetworkRouter#route(UUID, String, String)} for a routing
 *       decision (hard-pin to {@code serverHint} when present).</li>
 *   <li>Translate the {@link RoutingDecision} into the SPI's
 *       {@link NetworkCommandHook.RoutingResult}:
 *       <ul>
 *         <li>{@link RoutingDecision.Local} -&gt; {@link NetworkCommandHook.RoutingResult#local()}
 *             (fall through to local pipeline).</li>
 *         <li>{@link RoutingDecision.CrossServer} -&gt; offer an
 *             {@link NetworkEnrolmentBuffer.EnrolmentRecord} to the buffer
 *             and return {@link NetworkCommandHook.RoutingResult#crossServer}.</li>
 *         <li>{@link RoutingDecision.LocalFallback} -&gt; mostly silent
 *             local fallback per the user-confirmed UX (see Slice H sign-off
 *             notes); the one exception is
 *             {@link RoutingDecision.FallbackReason#REGION_UNAVAILABLE},
 *             which fires the {@code networkRegionUnavailable} reject so the
 *             player learns that the explicitly-named region/server they
 *             asked for is not actually reachable.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>S-004: any internal exception propagates so {@code RTPCmd.compute}'s
 * outer try/catch can degrade to local with a WARNING log. We never
 * swallow the error silently.</p>
 *
 * <p>Threading: invoked from the {@code /rtp} command thread. The router
 * call is non-blocking (pure-function read of the cached snapshot); the
 * enrolment buffer's {@link NetworkEnrolmentBuffer#offer} is lock-free.</p>
 */
public final class BukkitNetworkCommandHook implements NetworkCommandHook {

    private final NetworkRouter router;
    private final NetworkEnrolmentBuffer enrolmentBuffer;
    /** L6 Slice I: live peer-region view used by lobby-mode no-arg dispatch. */
    private final PeerRegionRegistry peerRegionRegistry;
    /** L6 Slice I: when true, no-arg {@code /rtp} auto-routes to a remote peer. */
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
     * Legacy 2-arg ctor (pre-Slice-I). Equivalent to {@code lobbyMode = false}
     * and a null {@code peerRegionRegistry}; the hook will not synthesise a
     * lobby target for no-arg {@code /rtp}, preserving Slice H2 behaviour.
     */
    public BukkitNetworkCommandHook(NetworkRouter router, NetworkEnrolmentBuffer enrolmentBuffer) {
        this(router, enrolmentBuffer, null, false, null);
    }

    /**
     * Slice I ctor. When {@code lobbyMode == true} AND the player invoked
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
     */
    public BukkitNetworkCommandHook(NetworkRouter router,
                                    NetworkEnrolmentBuffer enrolmentBuffer,
                                    PeerRegionRegistry peerRegionRegistry,
                                    boolean lobbyMode) {
        this(router, enrolmentBuffer, peerRegionRegistry, lobbyMode, null);
    }

    /**
     * Slice I follow-up ctor. Adds the optional {@code backendStatePublisher}
     * so cross-server dispatches can force an immediate heartbeat publish,
     * propagating the local {@code recordDispatch} decrement to peers before
     * the next scheduled tick. The publisher is allowed to be {@code null}
     * (test paths, non-network deployments); the hook silently skips the
     * forced publish in that case.
     */
    public BukkitNetworkCommandHook(NetworkRouter router,
                                    NetworkEnrolmentBuffer enrolmentBuffer,
                                    PeerRegionRegistry peerRegionRegistry,
                                    boolean lobbyMode,
                                    BackendStatePublisher backendStatePublisher) {
        this.router = Objects.requireNonNull(router, "router");
        this.enrolmentBuffer = Objects.requireNonNull(enrolmentBuffer, "enrolmentBuffer");
        this.peerRegionRegistry = peerRegionRegistry;
        this.lobbyMode = lobbyMode;
        this.backendStatePublisher = backendStatePublisher;
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

        // L6 Slice I: lobby-mode no-arg dispatch. When the operator opted
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
            java.util.Optional<PeerRegionRegistry.ServerRegion> pick =
                    peerRegionRegistry.pickMostKept();
            if (pick.isEmpty()) {
                return RoutingResult.reject(
                        MessagesKeys.networkRegionUnavailable.name(), "");
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
                        MessagesKeys.networkRegionUnavailable.name(),
                        regionArg == null ? "" : regionArg);
            }
            regionKey = (parsed == null) ? null : parsed.regionKey();
            serverHint = (parsed == null) ? null : parsed.serverHint();
        }

        RoutingDecision decision = router.route(playerId, regionKey, serverHint);

        if (decision instanceof RoutingDecision.Local) {
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
            // Slice I follow-up: bump our local view of the chosen peer's
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
            // for. Other fallback reasons (queue full, rate limit, no
            // peers, network disabled) silently fall through to local
            // so the teleport "just works" - simple-for-users.
            if (fallback.reason() == RoutingDecision.FallbackReason.REGION_UNAVAILABLE) {
                String placeholder = formatPlaceholder(serverHint, regionKey, regionArg);
                return RoutingResult.reject(
                        MessagesKeys.networkRegionUnavailable.name(),
                        placeholder);
            }
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

    /** Visible for tests. */
    public NetworkRouter router() { return router; }

    /** Visible for tests. */
    public NetworkEnrolmentBuffer enrolmentBuffer() { return enrolmentBuffer; }
}
