package io.github.dailystruggle.rtp.proxy.common.spi;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Cross-proxy player-ownership tag (rtp-proxy-ADR-016, REQ-RTP-NET-016).
 *
 * <p>Each proxy records the players currently connected to it in a shared
 * store ({@code rtp:net:owner:&lt;uuid&gt;} in Redis; an in-memory map for the
 * {@code in-memory} transport which is single-JVM by definition). The
 * ownership-aware {@link NetworkRequestQueue#dequeueReady(java.time.Duration,
 * String)} consults the tag so foreign-owned envelopes stay in their FIFO
 * slot and only the owning proxy pulls them.</p>
 *
 * <p><strong>TTL contract.</strong> Tags are set with a short TTL (default
 * twice the heartbeat interval) and refreshed by the owning proxy on each
 * heartbeat. A crashed proxy's tags expire within one heartbeat window, at
 * which point the queue's "owner == ''" branch kicks in and the dispatcher
 * legitimately CANCELs the orphaned envelopes.</p>
 *
 * <p><strong>S-004 contract.</strong> Every method returns a completed future
 * or a failed future with a meaningful throwable; no silent swallows.</p>
 */
public interface PlayerOwnershipTracker extends AutoCloseable {

    /**
     * Record (or refresh) that {@code playerId} is currently owned by
     * {@code thisProxyId}. Idempotent and last-writer-wins: a hand-off between
     * two proxies (rare, but possible during a fast disconnect/reconnect) is
     * resolved by whichever {@code claim} call lands last in the shared store.
     *
     * @param playerId    player UUID
     * @param thisProxyId stable proxy node id
     * @param ttlSeconds  tag TTL in seconds; must be {@code &gt; 0}
     */
    CompletableFuture<Void> claim(UUID playerId, String thisProxyId, int ttlSeconds);

    /**
     * Compare-and-DEL: remove the tag for {@code playerId} only if it
     * currently names {@code thisProxyId}. Stops a stale DEL on this proxy
     * from clobbering a fresh tag the other proxy may have just set (player
     * reconnected to the other proxy faster than this DisconnectEvent fired).
     */
    CompletableFuture<Void> release(UUID playerId, String thisProxyId);

    /**
     * Read the current owner of {@code playerId}, or an empty string when
     * the tag is absent. Mainly for diagnostics and tests; the dispatch path
     * never calls this (the Lua script reads the tag atomically).
     */
    CompletableFuture<String> ownerOf(UUID playerId);

    @Override
    void close();

    /**
     * No-op tracker used when ownership tagging is not applicable (e.g.
     * {@code transport.type = in-memory} on a single-JVM devstack). Every
     * call resolves immediately; {@link #ownerOf(UUID)} always returns the
     * empty string, which the Lua script treats as "no owner" and dispatches
     * legitimately. The trigger source recognises this case by being
     * constructed without a {@code thisProxyId} (legacy ctor).
     */
    PlayerOwnershipTracker NO_OP = new PlayerOwnershipTracker() {
        @Override public CompletableFuture<Void> claim(UUID p, String pid, int ttl) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> release(UUID p, String pid) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<String> ownerOf(UUID p) {
            return CompletableFuture.completedFuture("");
        }
        @Override public void close() { /* no-op */ }
    };
}
