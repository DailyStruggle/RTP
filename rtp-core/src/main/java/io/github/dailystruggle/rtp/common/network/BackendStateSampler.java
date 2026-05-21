package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;

/**
 * Producer-side abstraction: each platform adapter (Bukkit/Paper/Folia/
 * Fabric/...) supplies a sampler that knows how to read live host state
 * (TPS / MSPT / heap / player count / loaded worlds / etc.) and assemble a
 * {@link BackendHeartbeat} row for {@link BackendStatePublisher} to ship.
 *
 * <p>Pinned by rtp-proxy-ADR-011 §Backend Wiring. Lives in {@code rtp-core}
 * (not {@code rtp-proxy-common}) so the publisher composes with the host's
 * scheduler and {@code AbstractSQLDatabaseAccessor}, but it consumes the
 * vendor-neutral {@link BackendHeartbeat} record so the network state
 * surface stays in {@code rtp-proxy-common}.</p>
 *
 * <p>Implementations must be safe to call from a scheduler thread (the
 * publisher tick). Sampling should be cheap (less than ~1 ms typical); if a
 * value is genuinely expensive to compute (loaded worlds enumeration with
 * thousands of entries), implementations should cache it between samples.</p>
 */
public interface BackendStateSampler {

    /**
     * Sample the current backend state and produce a heartbeat row stamped
     * with {@code lastSeenEpochMs == System.currentTimeMillis()}. The
     * {@code serverId} parameter is the operator-configured identity from
     * {@code network.yml}; samplers must echo it back so heartbeats are
     * trivially routable to the right backend row.
     */
    BackendHeartbeat sample(String serverId);
}
