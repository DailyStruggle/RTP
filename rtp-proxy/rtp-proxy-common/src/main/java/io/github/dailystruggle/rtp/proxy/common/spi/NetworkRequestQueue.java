package io.github.dailystruggle.rtp.proxy.common.spi;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Write-batched cross-server `/rtp` wait queue SPI. Slice D row D1 of
 * `CHECKLIST-cross-server-rtp-L6.md`; design in
 * `PROPOSAL-cross-server-rtp-L6.md` Sections 4 and 7.
 *
 * <p>Per-request hot path (per proposal Section 2):
 * <ul>
 *   <li>Backend enrols into a dirty-write buffer (one {@code add}).</li>
 *   <li>A periodic flush ships N enrolments in one batch via
 *       {@link #flushPending(List)}.</li>
 *   <li>The proxy worker {@code BLPOP}s via {@link #dequeueReady(Duration)}.</li>
 *   <li>State transitions and cancellations are explicit single-writer
 *       operations (proposal Section 4.4).</li>
 * </ul>
 *
 * <p>All operations are async and non-blocking on the caller thread; binding
 * implementations must hop work onto their own executor consistent with
 * {@link NetworkTransport}.</p>
 *
 * <p><strong>S-004 contract:</strong> every failure path returns a completed
 * future carrying a typed outcome or a failed future with a meaningful
 * {@link Throwable}. Implementations must never silently swallow.</p>
 *
 * @see InMemoryNetworkRequestQueue
 */
public interface NetworkRequestQueue {

    /** Per-player FIFO state used by both producer and consumer paths. */
    enum QueueState {
        QUEUED,
        /**
         * Parked on the shared cross-proxy {@link NetworkWaitlist} because no
         * backend qualifies right now (all kill-switched, starved
         * {@code networkKeptLocations}, or region not served). The proxy-side
         * drainer reinjects the envelope into dispatch when capacity appears.
         * See `rtp-proxy-ADR-015` and REQ-RTP-NET-015.
         */
        WAITLISTED,
        ROUTING,
        RESERVED,
        TRANSFERRING,
        COMPLETED,
        FAILED,
        CANCELLED,
        UNKNOWN
    }

    /** Why a queue entry was cancelled. */
    enum CancelReason {
        PLAYER_DISCONNECT,
        TTL_EXPIRED,
        EXPLICIT_REQUEST,
        TRANSPORT_ERROR
    }

    /** Result of an enrolment batch flush. */
    enum EnrolOutcome {
        /** All envelopes in the batch were durably enqueued. */
        ACCEPTED,
        /** Some or all envelopes were rejected (queue full, transport down). */
        REJECTED
    }

    /**
     * What the backend buffers locally and ships to the queue. Mirrors the
     * Slice C {@code NetworkEnrolmentBuffer.EnrolmentRecord} shape so the
     * existing dirty-write buffer can adapt-in with a thin wrapper.
     */
    record EnrolmentEnvelope(
            UUID playerId,
            UUID correlationId,
            Optional<String> regionKey,
            Optional<String> serverHint,
            long createdAtMs
    ) {
        public EnrolmentEnvelope {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(correlationId, "correlationId");
            if (regionKey == null) regionKey = Optional.empty();
            if (serverHint == null) serverHint = Optional.empty();
        }
    }

    /**
     * Per-player snapshot returned by {@link #pollStatus(List)}. Mirrors
     * the Slice C {@code NetworkStatusCache.QueueStatus} shape.
     */
    record QueueStatus(
            UUID playerId,
            QueueState state,
            int positionInQueue,
            Optional<String> serverId,
            Optional<String> regionKey,
            long updatedAtMs
    ) {
        public QueueStatus {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(state, "state");
            if (serverId == null) serverId = Optional.empty();
            if (regionKey == null) regionKey = Optional.empty();
            if (positionInQueue < 0) positionInQueue = 0;
        }
    }

    /**
     * Envelope popped by the proxy worker via {@link #dequeueReady(Duration)}.
     */
    record QueueEnvelope(
            UUID playerId,
            UUID correlationId,
            Optional<String> regionKey,
            Optional<String> serverHint,
            long enrolledAtMs,
            long dequeuedAtMs
    ) {
        public QueueEnvelope {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(correlationId, "correlationId");
            if (regionKey == null) regionKey = Optional.empty();
            if (serverHint == null) serverHint = Optional.empty();
        }
    }

    /**
     * Append a single enrolment. Backends usually call this from the
     * dirty-write buffer; the future completes when the envelope has been
     * accepted into the implementation's pending set. The actual durable
     * write to Redis / SQL happens at flush time.
     */
    CompletableFuture<EnrolOutcome> enrol(EnrolmentEnvelope envelope);

    /**
     * Drain a batch of pending envelopes to durable storage in one round
     * trip. Called by the backend flush timer. Idempotent: replays of the
     * same {@link EnrolmentEnvelope#correlationId()} must not double-enqueue.
     *
     * <p>An empty batch resolves with {@link EnrolOutcome#ACCEPTED} and no
     * side effect.</p>
     */
    CompletableFuture<EnrolOutcome> flushPending(List<EnrolmentEnvelope> batch);

    /**
     * One-shot pipelined read of per-player status. Backends typically pass
     * the set of locally-enrolled UUIDs. Players absent from the returned
     * list have either completed, been cancelled, or aged out of storage.
     */
    CompletableFuture<List<QueueStatus>> pollStatus(List<UUID> playerIds);

    /**
     * Proxy worker BLPOP. Blocks the implementation's executor up to
     * {@code blockFor}; returns empty on timeout.
     */
    CompletableFuture<Optional<QueueEnvelope>> dequeueReady(Duration blockFor);

    /**
     * Ownership-aware variant for multi-proxy deployments. Implementations
     * pop only envelopes whose player is currently owned by {@code thisProxyId}
     * (see {@code rtp:net:owner:<uuid>} in Redis) or by no live proxy at all
     * (tag absent -&gt; legitimately disconnected); foreign-owned envelopes
     * remain in their FIFO slot so the owning proxy can pull them next, which
     * preserves enrolment order across the cluster.
     *
     * <p>Default behaviour falls back to {@link #dequeueReady(Duration)} so
     * single-proxy and in-memory transports keep working unchanged. The Redis
     * implementation overrides this with {@code dequeueReadyOwned.lua}; see
     * {@code rtp-proxy-ADR-016}.</p>
     *
     * @param blockFor    max time to wait when the queue is empty
     * @param thisProxyId stable id of the proxy calling; never {@code null}
     */
    default CompletableFuture<Optional<QueueEnvelope>> dequeueReady(
            Duration blockFor, String thisProxyId) {
        return dequeueReady(blockFor);
    }

    /**
     * Single-writer state transition (proposal Section 4.4). Returns the
     * resulting status row, or empty if the player has no live entry.
     */
    CompletableFuture<Optional<QueueStatus>> transition(
            UUID playerId, QueueState next, Optional<String> reason);

    /**
     * Idempotent cancel: removes the player's pending entry and transitions
     * status to {@link QueueState#CANCELLED}. Safe to call when the entry
     * is already gone.
     */
    CompletableFuture<Void> cancel(UUID playerId, CancelReason reason);
}
