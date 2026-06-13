package io.github.dailystruggle.rtp.proxy.common.spi;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Shared cross-proxy waitlist for cross-server {@code /rtp} enrolments that
 * cannot be dispatched immediately.
 * Design in `rtp-proxy-ADR-015-shared-network-waitlist-and-dynamic-batched-dispatch.md`.
 *
 * <p><strong>Why this is a sibling SPI to {@link NetworkRequestQueue}.</strong>
 * {@code NetworkRequestQueue} is a strict FIFO whose hot path is BLPOP-style
 * one-at-a-time dequeue. The waitlist needs three operations it cannot model:
 * <ul>
 *   <li>{@link #position(UUID)} - per-player visibility ("you are #N") for
 *       the lobby-side notifier and command-lock predicate.</li>
 *   <li>{@link #remove(UUID, CancelReason)} - point-removal on
 *       {@code PlayerQuitEvent} regardless of where in the queue the entry
 *       sits.</li>
 *   <li>{@link #drainBatch(Map, int)} - take up to {@code networkKeptCount}
 *       envelopes <em>per backend</em> in one round trip, sized by the
 *       caller from the heartbeat snapshot.</li>
 * </ul>
 *
 * <p>The waitlist's FIFO ordering is independent of
 * {@link NetworkRequestQueue}'s; an envelope that is rejected by the
 * dispatcher (no backend qualifies right now) is parked here, then the
 * proxy-side drainer reinjects it into the dispatch path when capacity
 * appears. The two SPIs together cover the "notify, lock, batch" UX the
 * local {@code RegionQueueManager.playerQueue} provides on a single
 * backend.
 *
 * <p><strong>S-004 contract.</strong> Every failure path resolves with a
 * meaningful outcome - never a silent swallow. Transport errors complete
 * the future exceptionally with a typed throwable; full-waitlist rejections
 * resolve with {@link EnrolOutcome#REJECTED_FULL}; missing entries resolve with
 * {@link Optional#empty()} or {@code false}.
 *
 * <p>All operations are async and non-blocking on the caller thread;
 * binding implementations must hop work onto their own executor.
 *
 * @see NetworkRequestQueue
 */
public interface NetworkWaitlist {

    /** Outcome of an enrolment into the waitlist. */
    enum EnrolOutcome {
        /** Envelope durably parked; {@link #position(UUID)} now resolves to a non-zero rank. */
        ACCEPTED,
        /** Envelope rejected because the waitlist is at {@code maxSize}. */
        REJECTED_FULL,
        /** Envelope rejected because the same playerId is already on the waitlist. */
        REJECTED_DUPLICATE,
        /** Envelope rejected because the waitlist binding is shutting down. */
        REJECTED_CLOSED
    }

    /** Why an entry left the waitlist without dispatch. */
    enum CancelReason {
        PLAYER_DISCONNECT,
        TTL_EXPIRED,
        EXPLICIT_REQUEST,
        BACKEND_UNAVAILABLE,
        TRANSPORT_ERROR
    }

    /**
     * Park request. Mirrors {@link NetworkRequestQueue.EnrolmentEnvelope}
     * shape so the dispatcher can reuse the same record without
     * conversion.
     */
    record WaitEnvelope(
            UUID playerId,
            UUID correlationId,
            Optional<String> regionKey,
            Optional<String> serverHint,
            String originServerId,
            long enrolledAtMs
    ) {
        public WaitEnvelope {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(originServerId, "originServerId");
            if (regionKey == null) regionKey = Optional.empty();
            if (serverHint == null) serverHint = Optional.empty();
        }
    }

    /**
     * Park {@code envelope} at the tail of the shared waitlist. Idempotent
     * by {@code correlationId} (replays return {@link EnrolOutcome#ACCEPTED}
     * without inserting). Duplicate {@code playerId} (different
     * correlationId) is rejected with {@link EnrolOutcome#REJECTED_DUPLICATE}
     * so the lobby-side command lock can rely on at-most-one entry per
     * UUID.
     */
    CompletableFuture<EnrolOutcome> enrol(WaitEnvelope envelope);

    /**
     * Drain up to {@code globalCap} envelopes total, allocated across
     * backends by the per-backend caps in {@code perBackendCaps}. A backend
     * with cap {@code 0} or missing from the map will not receive any
     * envelope from this call.
     *
     * <p>Allocation is FIFO-preserving: the head of the waitlist is
     * inspected first and assigned to a backend whose remaining cap is
     * non-zero and whose region (if the envelope pins one) is in the
     * backend's served set. The selection function is supplied by the
     * caller via the {@code perBackendCaps} keys - the SPI does not own
     * region knowledge.
     *
     * <p>An envelope whose pinned region is served by no qualifying backend
     * stays at the head of the waitlist and is retried on the next drain;
     * this is the head-blocking discipline. Callers that want to skip
     * head-blocked entries should remove them explicitly via
     * {@link #remove(UUID, CancelReason)} after a configurable head-age
     * threshold.
     *
     * @param perBackendCaps backend serverId -> max envelopes this backend
     *                       can accept right now (typically
     *                       {@code BackendHeartbeat.networkKeptCount}).
     * @param globalCap      upper bound on the total drained; the sum of
     *                       per-backend caps is the effective ceiling.
     * @return a map of {@code backendServerId -> envelopes} (FIFO order
     *         within each backend's list). Backends with no take are
     *         omitted from the returned map.
     */
    CompletableFuture<Map<String, List<WaitEnvelope>>> drainBatch(
            Map<String, Integer> perBackendCaps, int globalCap);

    /**
     * Look up the 1-indexed FIFO rank of {@code playerId} on the
     * waitlist. Returns {@link Optional#empty()} when the player has no
     * live entry. Used by the lobby-side notifier and command-lock.
     */
    CompletableFuture<Optional<Integer>> position(UUID playerId);

    /**
     * Point-remove {@code playerId} from the waitlist. Idempotent; returns
     * {@code true} if a live entry was removed, {@code false} if the
     * player was not on the waitlist. Used by {@code PlayerQuitEvent} on
     * the lobby and by the proxy reaper for TTL expiry.
     */
    CompletableFuture<Boolean> remove(UUID playerId, CancelReason reason);

    /**
     * Reap entries older than {@code maxAge} (computed against
     * {@link WaitEnvelope#enrolledAtMs()}). Returns the count reaped.
     * Implementations may schedule this internally; exposing it is for
     * tests and operator-driven sweeps.
     */
    CompletableFuture<Integer> reap(Duration maxAge);

    /**
     * Total live entries. O(1) on every impl. Used by the drainer to
     * short-circuit when the waitlist is empty and by the proxy heartbeat
     * for observability.
     */
    CompletableFuture<Integer> size();

    /**
     * Refresh every live entry's {@code enrolledAtMs} to the current wall
     * clock, preserving FIFO order. Used by the proxy-side drainer when an
     * entire drain pulse fails to place its envelopes (every qualifying
     * backend rejected the claim, transport partition, etc.) so the
     * waitlist does not silently {@link #reap(Duration)} entries that were
     * "old" only because the network was unable to serve them. Returns the
     * count of entries refreshed. No-op (returns {@code 0}) on an empty
     * waitlist. See `rtp-proxy-ADR-015` Section "Total-failure backoff".
     */
    CompletableFuture<Integer> refreshAllTtl();
}
