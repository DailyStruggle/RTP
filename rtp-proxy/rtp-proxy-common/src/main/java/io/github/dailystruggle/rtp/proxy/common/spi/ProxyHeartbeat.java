package io.github.dailystruggle.rtp.proxy.common.spi;

import java.util.Objects;

/**
 * One {@code proxy_state} row published by a proxy adapter. Mirrors
 * {@link BackendHeartbeat} but describes the proxy itself; consumed by
 * other proxies in a multi-proxy deployment to negotiate which proxy
 * authoritatively owns a reservation (see ADR-036 §5 Multi-Proxy
 * Idempotency).
 *
 * @param proxyId           stable proxy id (operator-assigned)
 * @param schemaVersion     transport schema version (REQ-RTP-NET-009)
 * @param lastSeenEpochMs   publication epoch milliseconds
 * @param playerCount       online player count seen by this proxy
 * @param inFlightRequests  RTP requests currently in dispatcher.dispatch()
 */
public record ProxyHeartbeat(
        String proxyId,
        int schemaVersion,
        long lastSeenEpochMs,
        int playerCount,
        int inFlightRequests
) {
    public ProxyHeartbeat {
        Objects.requireNonNull(proxyId, "proxyId");
    }
}
