package io.github.dailystruggle.rtp.proxy.common.spi;

import java.util.Objects;

/**
 * One {@code proxy_state} row published by a proxy adapter. Mirrors
 * {@link BackendHeartbeat} but describes the proxy itself; consumed by
 * other proxies in a multi-proxy deployment to negotiate which proxy
 * authoritatively owns a reservation (see ADR-036 section 5 Multi-Proxy
 * Idempotency).
 *
 * @param proxyId           stable proxy id (operator-assigned)
 * @param schemaVersion     transport schema version (REQ-RTP-NET-009)
 * @param lastSeenEpochMs   publication epoch milliseconds
 * @param playerCount       online player count seen by this proxy
 * @param inFlightRequests  RTP requests currently in dispatcher.dispatch()
 * @param killSwitch        operator-asserted kill switch (rtp-proxy-ADR-010 section Kill
 *                          Switch); when {@code true}, peers reject this proxy's
 *                          published RTP requests and return
 *                          {@link DispatchOutcome.Failed} with reason
 *                          {@code KILL_SWITCH}. Default {@code false}.
 */
public record ProxyHeartbeat(
        String proxyId,
        int schemaVersion,
        long lastSeenEpochMs,
        int playerCount,
        int inFlightRequests,
        boolean killSwitch
) {
    public ProxyHeartbeat {
        Objects.requireNonNull(proxyId, "proxyId");
    }

    /**
     * Convenience constructor for callers that have not opted into the kill
     * switch field; defaults {@code killSwitch} to {@code false}. Retained for
     * source compatibility while transport bindings learn the new field.
     */
    public ProxyHeartbeat(String proxyId,
                          int schemaVersion,
                          long lastSeenEpochMs,
                          int playerCount,
                          int inFlightRequests) {
        this(proxyId, schemaVersion, lastSeenEpochMs, playerCount, inFlightRequests, false);
    }
}
