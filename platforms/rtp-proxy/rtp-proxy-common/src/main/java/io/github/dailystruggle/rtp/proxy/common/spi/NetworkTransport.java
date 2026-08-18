package io.github.dailystruggle.rtp.proxy.common.spi;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Pluggable shared-store binding (Redis / Postgres / generic-SQL / in-memory /
 * dev-only plugin-message). Async-only; rtp-proxy-ADR-001 section 3.
 *
 * <p><strong>Threading contract:</strong> all returned futures complete on a
 * transport-owned executor; consumers must {@code thenAcceptAsync(...,
 * hostScheduler)} before touching world / player state (S-005,
 * REQ-RTP-PROXY-COMMON-001).</p>
 *
 * <p><strong>Atomicity contract:</strong> {@link #claim} must guarantee
 * row-count atomicity for the PENDING→CLAIMED transition (REQ-RTP-PROXY-004,
 * ADR-036 section 5 Multi-Proxy Idempotency).</p>
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

    /**
     * Region-aware overload of {@link #claim(String, UUID, Duration)}. When
     * {@code regionKey} is present it is carried verbatim onto the issued
     * {@link ReservationToken#regionKey()} and persisted with the row, so the
     * destination backend's {@code JoinTriggerSource} can re-attach it as
     * {@code rtp region=<regionKey>} on arrival (cross-server
     * {@code /rtp region=<server>:<region>} support).
     *
     * <p>Default implementation delegates to the region-agnostic
     * {@link #claim(String, UUID, Duration)} so legacy bindings continue to
     * compile and simply drop the region constraint. Production bindings
     * (in-memory, Redis, SQL) override this to persist {@code regionKey}.</p>
     */
    default CompletableFuture<ReservationToken> claim(String serverId, UUID playerId,
                                                      Duration ttl, Optional<String> regionKey) {
        return claim(serverId, playerId, ttl);
    }

    /** Release a previously-issued token; idempotent. */
    CompletableFuture<Void> release(String tokenId, ReleaseReason reason);

    /**
     * Atomically transition a CLAIMED reservation to CONSUMED on the backend
     * that owns it. Called by the backend's {@code JoinTriggerSource} when a
     * cross-server player arrives carrying an outstanding token.
     *
     * <p>Pinned by L2 of the cross-server RTP checklist; structurally distinct
     * from {@link #release(String, ReleaseReason)} because the caller needs to
     * branch on the precise reason (race lost vs expired vs HMAC invalid vs
     * wrong-server) in order to choose between silent no-op, configurable
     * player message, and S-004 WARNING. The release path collapses all of
     * those into "idempotent no-op", which loses information at the join hot
     * path.</p>
     *
     * <p>Implementations <strong>must</strong> guarantee row-count atomicity
     * for the CLAIMED -> CONSUMED transition: a backend that loses the
     * transition to a racing peer must observe {@link RedeemOutcome#ALREADY_CONSUMED}
     * (or {@link RedeemOutcome#NOT_FOUND} if the row was reaped between
     * lookup and redeem), never {@link RedeemOutcome#REDEEMED}.</p>
     *
     * <p>Implementations <strong>must</strong> validate that the row's
     * {@code serverId} matches {@code expectedServerId} before transitioning;
     * a mismatch returns {@link RedeemOutcome#WRONG_SERVER} without mutating
     * the row. This guards against a misconfigured cluster where two
     * backends share a player connection by accident.</p>
     *
     * <p>Default implementation degrades to a best-effort
     * {@link #release(String, ReleaseReason) release(tokenId, CONSUMED)} and
     * returns {@link RedeemOutcome#REDEEMED}, so legacy bindings continue to
     * compile. Production bindings (in-memory, Redis, SQL) override this to
     * supply the structured outcome.</p>
     *
     * @param tokenId          token id observed via {@link #findReservation(java.util.UUID)}
     * @param playerId         joining player's UUID (cross-checked against the row)
     * @param expectedServerId this backend's {@code serverId} (cross-checked)
     */
    default CompletableFuture<RedeemOutcome> redeem(String tokenId, UUID playerId, String expectedServerId) {
        return release(tokenId, ReleaseReason.CONSUMED).thenApply(v -> RedeemOutcome.REDEEMED);
    }

    /**
     * Find the active (non-released, non-expired) reservation token for
     * {@code playerId}, if one exists. Used by the proxy-side
     * {@code ServerPreConnectEvent} listener (REQ-RTP-PROXY-VELOCITY-002) to
     * route a connecting player to the backend that holds their pre-allocated
     * coordinate.
     *
     * <p>Default returns {@code Optional.empty()} so legacy bindings that
     * have not been migrated continue to compile and degrade to "no
     * reservation found, fall through to default routing" rather than
     * throwing. Bindings that participate in cross-process state (SQL,
     * Redis, in-memory) must override this to consult the shared store.</p>
     *
     * <p>"Active" means {@code state IN (PENDING, CLAIMED)} and
     * {@code expiresEpochMs > now}. CONSUMED / RELEASED / expired tokens are
     * never returned; the connect-time listener must treat their absence as
     * "no reservation" and fall through to default routing, never as a fatal
     * error (REQ-RTP-S-004).</p>
     */
    default CompletableFuture<Optional<ReservationToken>> findReservation(UUID playerId) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    /**
     * Bulk-transition every active reservation token whose
     * {@code expiresEpochMs <= now} from {@code PENDING}/{@code CLAIMED} to
     * {@code RELEASED} (REQ-RTP-NET-011 / REQ-RTP-PROXY-004). Returns the
     * IDs of the tokens that this call actually transitioned, so the caller
     * (typically {@code ReservationTokenReaper}) can dispatch a
     * {@link ReleaseReason#TTL_EXPIRED} release notification per token and
     * the originating backend can return the earmarked coordinate to its
     * buffer.
     *
     * <p>Implementations must preserve row-count atomicity for the
     * transition: a token that loses the reap race to another proxy must
     * not appear in the returned list of <em>this</em> proxy's call.</p>
     *
     * <p>Default returns an empty list so legacy bindings that have not
     * been migrated continue to compile and degrade to "no reaping" rather
     * than throwing. Bindings that participate in cross-process state
     * (SQL, Redis, in-memory) must override this.</p>
     */
    default CompletableFuture<List<String>> reapExpired(Instant now) {
        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * List every active (non-released, non-expired) reservation token whose
     * {@code serverId} matches {@code serverId}. Used by a backend's one-shot
     * boot-time reconcile to discover reservations
     * the proxy claimed against this backend while the backend was down, so
     * the backend can call {@code RegionQueueManager.reserveFromNetworkKept}
     * for each outstanding token and bring its in-memory
     * {@code networkReservedLocations} map back into sync with the shared
     * store.
     *
     * <p>"Active" means {@code state IN (PENDING, CLAIMED)} and
     * {@code expiresEpochMs > now}; CONSUMED / RELEASED / expired tokens are
     * never returned (parity with {@link #findReservation(UUID)}). The
     * caller treats the returned list as a steady-state snapshot at call
     * time; further mutations are observed via subsequent calls or the
     * normal claim / redeem flow.</p>
     *
     * <p>Default returns an empty list so legacy bindings that have not
     * been migrated continue to compile and degrade to "no outstanding
     * reservations, nothing to reconcile" rather than throwing. Bindings
     * that participate in cross-process state (in-memory, Redis, SQL) must
     * override this to consult the shared store.</p>
     *
     * <p>S-004: implementations must not throw on missing / malformed rows;
     * a corrupt row is filtered out and logged at WARNING by the
     * implementation, never propagated to the caller.</p>
     */
    default CompletableFuture<List<ReservationToken>> listActiveForServer(String serverId) {
        return CompletableFuture.completedFuture(List.of());
    }

    /** Publish this proxy's heartbeat row. */
    CompletableFuture<Void> publishProxyHeartbeat(ProxyHeartbeat row);

    /**
     * Publish a backend heartbeat row. Bindings that participate in
     * cross-process state (SQL, Redis) must override this to UPSERT the row
     * into the shared store and notify subscribers; the in-memory binding
     * supplies the same semantics for tests. Default implementation throws
     * {@link UnsupportedOperationException} so that any binding which has not
     * been migrated to expose this method fails loudly rather than silently
     * dropping heartbeats on the floor.
     *
     * <p>Backend-side publishers (rtp-core {@code BackendStatePublisher})
     * call this exactly once per heartbeat interval. Returns a future that
     * completes once the row has been persisted to the underlying store.</p>
     */
    default CompletableFuture<Void> publishBackendHeartbeat(BackendHeartbeat row) {
        throw new UnsupportedOperationException(
                getClass().getName() + " does not implement publishBackendHeartbeat; "
                        + "override required for backend-side use.");
    }

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
