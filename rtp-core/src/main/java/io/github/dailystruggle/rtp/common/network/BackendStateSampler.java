package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;

/**
 * Platform adapter sampler producing {@link BackendHeartbeat} state snapshots (rtp-proxy-ADR-011).
 * Implementations must be safe and non-blocking when called from scheduler threads.
 */
public interface BackendStateSampler {

    /**
     * Samples host state and produces a fresh heartbeat row stamped with the current timestamp.
     *
     * @param serverId configured backend server ID from {@code network.yml}
     * @return current {@link BackendHeartbeat} snapshot; never {@code null}
     */
    BackendHeartbeat sample(String serverId);
}
