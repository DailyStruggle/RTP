package io.github.dailystruggle.rtp.proxy.common.spi;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Pluggable shared-store binding (Redis / Postgres / generic-SQL / in-memory /
 * dev-only plugin-message). Async-only; rtp-proxy-ADR-001 §3.
 *
 * <p><strong>Threading contract:</strong> all returned futures complete on a
 * transport-owned executor; consumers must {@code thenAcceptAsync(...,
 * hostScheduler)} before touching world / player state (S-005,
 * REQ-RTP-PROXY-COMMON-001).</p>
 *
 * <p><strong>Atomicity contract:</strong> {@link #claim} must guarantee
 * row-count atomicity for the PENDING→CLAIMED transition (REQ-RTP-PROXY-004,
 * ADR-036 §5 Multi-Proxy Idempotency).</p>
 */
public interface NetworkTransport extends AutoCloseable {

    /** Read the latest assembled {@link NetworkSnapshot}. */
    CompletableFuture<NetworkSnapshot> readSnapshot();

    /**
     * Atomically allocate a reservation for {@code playerId} against
     * {@code serverId} with the supplied {@code ttl}. Implementations must
     * guarantee that at most one proxy in a multi-proxy deployment wins the
     * PENDING→CLAIMED transition for a given {@code playerId} at a time.
     */
    CompletableFuture<ReservationToken> claim(String serverId, UUID playerId, Duration ttl);

    /** Release a previously-issued token; idempotent. */
    CompletableFuture<Void> release(String tokenId, ReleaseReason reason);

    /** Publish this proxy's heartbeat row. */
    CompletableFuture<Void> publishProxyHeartbeat(ProxyHeartbeat row);

    /**
     * Subscribe to backend heartbeat fan-out.
     *
     * <p>Listener callbacks may arrive on arbitrary transport threads; the
     * consumer is responsible for hopping to its host scheduler before
     * touching shared state.</p>
     */
    Subscription subscribeBackendHeartbeats(Consumer<BackendHeartbeat> sink);

    @Override
    void close();
}
