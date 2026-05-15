package io.github.dailystruggle.rtp.proxy.common.spi;

import java.util.List;
import java.util.Objects;

/**
 * One {@code backend_state} row as observed by the proxy. Published
 * periodically by each backend; the latest row per {@code serverId} is the
 * authoritative health and capability snapshot the {@link BackendSelector}
 * scores against.
 *
 * <p>Pinned by rtp-proxy-ADR-001 §3 and rtp-proxy-ADR-004 §Candidate
 * Filtering. Fields map 1:1 to {@code backend_state} schema in
 * rtp-proxy-ADR-002.</p>
 *
 * @param serverId           stable backend id (matches {@code network.yml}
 *                           {@code loadBalancer.backends.<serverId>})
 * @param schemaVersion      transport schema version (REQ-RTP-NET-009)
 * @param pluginState        {@code READY}, {@code STARTING}, or {@code SHUTTING_DOWN}
 * @param acceptingRequests  whether the backend is accepting new RTP requests
 * @param lastSeenEpochMs    when this row was published, in epoch milliseconds
 * @param mspt               recent mean tick time, in milliseconds
 * @param queueDepth         current depth of the RTP teleport waitlist
 * @param softCap            backend-declared soft cap on queue depth (used for normalisation)
 * @param heapUsedBytes      heap usage at publish time
 * @param heapMaxBytes       max heap, used to derive a {@code [0,1]} ratio
 * @param playerCount        online player count (lagging indicator; ships with weight 0 in v1)
 * @param regionsAvailable   region keys this backend can satisfy (may be empty for "any")
 * @param worldsLoaded       world keys currently loaded on this backend
 */
public record BackendHeartbeat(
        String serverId,
        int schemaVersion,
        PluginState pluginState,
        boolean acceptingRequests,
        long lastSeenEpochMs,
        double mspt,
        int queueDepth,
        int softCap,
        long heapUsedBytes,
        long heapMaxBytes,
        int playerCount,
        List<String> regionsAvailable,
        List<String> worldsLoaded
) {
    public BackendHeartbeat {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(pluginState, "pluginState");
        Objects.requireNonNull(regionsAvailable, "regionsAvailable");
        Objects.requireNonNull(worldsLoaded, "worldsLoaded");
        regionsAvailable = List.copyOf(regionsAvailable);
        worldsLoaded = List.copyOf(worldsLoaded);
    }

    /** Plugin lifecycle state on the backend at heartbeat publication time. */
    public enum PluginState {
        STARTING,
        READY,
        SHUTTING_DOWN
    }
}
