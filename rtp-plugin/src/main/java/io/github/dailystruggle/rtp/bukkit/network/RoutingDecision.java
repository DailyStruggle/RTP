package io.github.dailystruggle.rtp.bukkit.network;

import java.util.Optional;

/**
 * Sealed result of {@link NetworkRouter#route}. One of three outcomes:
 *
 * <ul>
 *   <li>{@link Local} - serve the request from local {@code keptLocations}.</li>
 *   <li>{@link CrossServer} - enrol the request on the cross-server queue,
 *       optionally pinned to a {@code serverHint} and / or {@code regionKey}.</li>
 *   <li>{@link LocalFallback} - cross-server was preferred but is not currently
 *       safe (queue full, token bucket exhausted, no live peer, regionKey not
 *       advertised by any backend); fall back to local with {@code reason}
 *       recorded for logging / status emission per S-004.</li>
 * </ul>
 *
 * <p>L6 of {@code CHECKLIST-cross-server-rtp.md}. Pinned by D1 ADR addendum
 * (rtp-proxy-ADR-NNN-backend-owned-rtp-with-network-queue) and PROPOSAL
 * {@code §3 Router Decision Matrix}.</p>
 */
public sealed interface RoutingDecision
        permits RoutingDecision.Local,
                RoutingDecision.CrossServer,
                RoutingDecision.LocalFallback {

    /** Serve from local {@code keptLocations}. */
    record Local() implements RoutingDecision {
        public static final Local INSTANCE = new Local();
    }

    /**
     * Enrol on the cross-server wait queue.
     *
     * @param serverHint optional pinned backend serverId (D6 / D7); when empty
     *                   the selector picks per snapshot
     * @param regionKey  optional region constraint forwarded to the selector
     */
    record CrossServer(Optional<String> serverHint,
                       Optional<String> regionKey) implements RoutingDecision {
        public CrossServer {
            if (serverHint == null) serverHint = Optional.empty();
            if (regionKey == null) regionKey = Optional.empty();
        }
    }

    /**
     * Wanted to go cross-server but cannot right now. Caller serves locally
     * and emits the {@code network.fallback} message keyed by {@link #reason()}.
     */
    record LocalFallback(FallbackReason reason) implements RoutingDecision {
        public LocalFallback {
            if (reason == null) reason = FallbackReason.UNKNOWN;
        }
    }

    /**
     * Why a cross-server route degraded to local. Each value maps to a
     * stable, configurable {@code messages.yml} reason token under
     * {@code network.fallback.<reason>} (G4).
     */
    enum FallbackReason {
        /** {@code routing.mode = local} (D2 default). */
        ROUTING_MODE_LOCAL,
        /** {@code routing.mode = auto} and local backend can serve this region. */
        LOCAL_PREFERRED,
        /** Local rate-limiter for cross-server requests exhausted. */
        TOKEN_BUCKET_EXHAUSTED,
        /** Wait-queue depth at or above {@code network.queueMaxDepth}. */
        QUEUE_FULL,
        /** No backend in the snapshot advertises this {@code regionKey}. */
        REGION_UNAVAILABLE,
        /** No live (non-killSwitch, recent heartbeat) peer in the snapshot. */
        NO_LIVE_PEER,
        /** Network mode disabled or bootstrap did not complete. */
        NETWORK_DISABLED,
        /** Catch-all; should never appear in normal operation. */
        UNKNOWN
    }
}
