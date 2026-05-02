package io.github.dailystruggle.rtp.common.network;

import java.util.UUID;

/**
 * Cross-server messaging + ephemeral coordination contract.
 *
 * <p>Defines the surface required for RTP's optional network bus: TTL-based player
 * cooldowns and a pub/sub channel for cross-server RPC. Implementations are an
 * <i>optional</i> runtime concern — the field {@link io.github.dailystruggle.rtp.common.RTP#networkManager}
 * is {@code null} unless network YAML enables a backend.
 *
 * <p>Decoupling rationale (ADR-024): {@code rtp-core} must not carry a hard symbolic
 * reference to any concrete network driver class (e.g. {@code RedisManager} pulls in
 * {@code redis.clients.jedis.*}). The lite assembly excludes those drivers entirely;
 * keeping the field's static type at this interface lets the lite JVM resolve
 * {@code RTP.class} without ever touching the missing driver class.
 *
 * <p>This contract is intentionally narrow: it covers <i>only</i> what {@code rtp-core}
 * itself dispatches today. Driver-specific configuration (host / port / password /
 * pool sizing) is the implementation's concern and is constructed reflectively from
 * config YAML by {@link io.github.dailystruggle.rtp.common.RTP}.
 *
 * <p>This is <b>not</b> a persistence abstraction — see
 * {@link io.github.dailystruggle.rtp.common.database.DatabaseAccessor} for tabular
 * CRUD with cache flushing. The two roles intentionally do not merge:
 * <ul>
 *   <li>{@code DatabaseAccessor} has no TTL primitive (no {@code setex}/{@code ttl}).</li>
 *   <li>{@code DatabaseAccessor} has no pub/sub broadcast.</li>
 *   <li>{@code DatabaseAccessor} is a batched-query store; pub/sub requires a
 *       long-lived async subscriber.</li>
 * </ul>
 * Forcing these methods onto every SQL accessor would be a wrong-abstraction no-op.
 *
 * <p>Implementations must remain free of {@code org.bukkit.*} imports and must route
 * any logging through {@link io.github.dailystruggle.rtp.common.RTP#log}.
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
