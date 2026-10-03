package io.github.dailystruggle.rtp.common.network;

import java.util.UUID;

/**
 * Optional cross-server messaging and ephemeral coordination contract (ADR-024).
 *
 * <p>Provides TTL-based player cooldowns and pub/sub fan-out across server instances.
 */
public interface RTPNetworkManager {

    /**
     * Record a per-player last teleport timestamp (epoch milliseconds).
     *
     * @param playerId    player UUID
     * @param epochMillis epoch millisecond timestamp
     */
    void setLastTeleportTime(UUID playerId, long epochMillis);

    /**
     * Read the last teleport timestamp (epoch milliseconds) for a player.
     *
     * @param playerId player UUID
     * @return epoch milliseconds, or 0 if absent
     */
    long getLastTeleportTime(UUID playerId);

    /**
     * @deprecated use {@link #setLastTeleportTime(UUID, long)}
     */
    @Deprecated
    default void setCooldown(UUID playerId, long expirationTimeSeconds) {
        setLastTeleportTime(playerId, System.currentTimeMillis() + expirationTimeSeconds * 1000L);
    }

    /**
     * @deprecated use {@link #getLastTeleportTime(UUID)}
     */
    @Deprecated
    default long getCooldown(UUID playerId) {
        long t = getLastTeleportTime(playerId);
        if (t <= 0L) return 0L;
        long diff = (t - System.currentTimeMillis()) / 1000L;
        return Math.max(0L, diff);
    }

    /**
     * Best-effort fan-out of an opaque JSON payload on the named channel.
     * Delivery is fire-and-forget; subscribers may be on other backends.
     */
    void publish(String channel, String jsonPayload);

    /**
     * Start any long-lived subscriber loops asynchronously. Must not block the
     * calling thread. Safe to call once after construction; calling more than once
     * is implementation-defined.
     */
    void initializeAsync();

    /**
     * Release all owned resources (connection pools, subscriber threads, etc.).
     * Called from {@link io.github.dailystruggle.rtp.common.RTP#stop()} on every
     * shutdown path; implementations must be idempotent.
     */
    void shutdown();
}
