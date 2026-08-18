package io.github.dailystruggle.rtp.common.network;

import java.util.Optional;

/**
 * Result of {@link NetworkRouter#route} (Local, CrossServer, or LocalFallback).
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
     *
     * @param reason why the cross-server route was not taken
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
