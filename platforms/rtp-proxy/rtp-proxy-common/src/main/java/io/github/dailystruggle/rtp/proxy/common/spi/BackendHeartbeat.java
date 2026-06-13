package io.github.dailystruggle.rtp.proxy.common.spi;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
 * @param killSwitch         operator-asserted kill switch (rtp-proxy-ADR-010 §Kill
 *                           Switch); when {@code true}, the {@link BackendSelector}
 *                           must exclude this backend from candidate scoring.
 *                           Default {@code false}.
 * @param keptCount          total kept-pool location count across all regions on this
 *                           backend (local hot queue). Used by the selector to
 *                           bias toward backends with warm caches when no
 *                           {@code regionKey} is requested. Default {@code 0}.
 * @param networkReservedCount count of in-flight cross-server reservations
 *                           held against {@code networkKeptLocations} on this
 *                           backend (one extra HSET field per
 *                           PROPOSAL §12.3). Default {@code 0}.
 * @param regions            region keys this backend hosts as a {@link Set}
 *                           (superset of {@link #regionsAvailable()},
 *                           kept distinct so the selector can filter by exact
 *                           region containment without list-order surprises).
 *                           Default empty.
 * @param regionKeptCounts   per-region count of locations in
 *                           {@code networkKeptLocations} (NOT local
 *                           {@code keptLocations}). Used by
 *                           the selector to filter peers that actually have a
 *                           coordinate ready for cross-server reservation.
 *                           Default empty.
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
        List<String> worldsLoaded,
        boolean killSwitch,
        int keptCount,
        int networkReservedCount,
        Set<String> regions,
        Map<String, Integer> regionKeptCounts
) {
    public BackendHeartbeat {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(pluginState, "pluginState");
        Objects.requireNonNull(regionsAvailable, "regionsAvailable");
        Objects.requireNonNull(worldsLoaded, "worldsLoaded");
        Objects.requireNonNull(regions, "regions");
        Objects.requireNonNull(regionKeptCounts, "regionKeptCounts");
        regionsAvailable = List.copyOf(regionsAvailable);
        worldsLoaded = List.copyOf(worldsLoaded);
        regions = Set.copyOf(regions);
        regionKeptCounts = Map.copyOf(regionKeptCounts);
    }

    /**
     * Convenience constructor for callers that have not opted into the kill
     * switch field; defaults {@code killSwitch} to {@code false} and all
     * fields to zero / empty. Retained for source compatibility while
     * transport bindings learn the new fields.
     */
    public BackendHeartbeat(String serverId,
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
                            List<String> worldsLoaded) {
        this(serverId, schemaVersion, pluginState, acceptingRequests, lastSeenEpochMs,
                mspt, queueDepth, softCap, heapUsedBytes, heapMaxBytes, playerCount,
                regionsAvailable, worldsLoaded, false,
                0, 0, Set.of(), Map.of());
    }

    /**
     * Convenience constructor for callers that have opted into
     * {@code killSwitch} but not yet into the cross-server fields; defaults
     * {@code keptCount}, {@code networkReservedCount}, {@code regions}, and
     * {@code regionKeptCounts} to zero / empty.
     */
    public BackendHeartbeat(String serverId,
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
                            List<String> worldsLoaded,
                            boolean killSwitch) {
        this(serverId, schemaVersion, pluginState, acceptingRequests, lastSeenEpochMs,
                mspt, queueDepth, softCap, heapUsedBytes, heapMaxBytes, playerCount,
                regionsAvailable, worldsLoaded, killSwitch,
                0, 0, Set.of(), Map.of());
    }

    /** Plugin lifecycle state on the backend at heartbeat publication time. */
    public enum PluginState {
        STARTING,
        READY,
        SHUTTING_DOWN
    }
}
