package io.github.dailystruggle.rtp.common.network;

import java.util.UUID;

/**
 * Optional cross-server messaging + ephemeral coordination contract (ADR-024).
 * TTL-based player cooldowns and pub/sub fan-out;
 * {@link io.github.dailystruggle.rtp.common.RTP#networkManager} is {@code null}
 * unless network YAML enables a backend. Keeping the static type at this
 * interface lets the lite assembly resolve {@code RTP.class} without ever
 * touching driver classes ({@code redis.clients.jedis.*}, ...).
 *
 * <p>Distinct from {@link io.github.dailystruggle.rtp.common.database.DatabaseAccessor}:
 * no TTL primitive, no pub/sub there. Implementations must not import
 * {@code org.bukkit.*} and must log via
 * {@link io.github.dailystruggle.rtp.common.RTP#log}.
 */
public interface RTPNetworkManager {

    /**
     * Record a per-player cooldown that auto-expires server-side.
     *
     * @param playerId               player UUID
     * @param expirationTimeSeconds  TTL in seconds; the implementation is responsible
     *                               for atomic write+expire semantics
     */
    void setCooldown(UUID playerId, long expirationTimeSeconds);

    /**
     * Read the remaining TTL for a previously-set cooldown.
     *
     * @return remaining seconds, or a non-positive value if absent / expired (the
     *         exact sentinel mirrors the underlying backend; callers must treat
     *         {@code <= 0} as "no active cooldown")
     */
    long getCooldown(UUID playerId);

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
