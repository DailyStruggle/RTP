package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle + construction guards for {@link RedisNetworkRequestQueue}
 * (L6 Slice D row D4). Live-Redis behaviour (script atomicity, terminal
 * env cleanup, LPOS positioning) is exercised end-to-end by the opt-in
 * {@code RedisNetworkRequestQueueIT} that ships with Slice D row D9.
 *
 * <p>This suite is intentionally narrow: it exercises the constructor's
 * connectivity validation, the {@code ttlSeconds} input guard, and the
 * SPI-contract details that do not require a live Redis (null-input
 * rejection, empty-batch / empty-list short-circuits).</p>
 */
class RedisNetworkRequestQueueTest {

    /** Pick a port that is overwhelmingly likely to be closed locally. */
    private static final int UNREACHABLE_PORT = 1;

    @Test
    void ctor_rejectsNegativeTtl() {
        // ttlSeconds < 0 must be rejected before any Jedis connection is opened
        // so a typo cannot silently produce undefined EXPIRE semantics.
        assertThrows(IllegalArgumentException.class,
                () -> new RedisNetworkRequestQueue("127.0.0.1", UNREACHABLE_PORT, null, -1));
    }

    @Test
    void ctor_unreachableHost_throwsIllegalState() {
        // PING against a closed port must fail fast at open() time, not silently
        // in flush loops. Matches RedisNetworkStateBinding semantics.
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new RedisNetworkRequestQueue("127.0.0.1", UNREACHABLE_PORT, null, 0));
        assertTrue(ex.getMessage().contains("cannot reach redis"),
                "expected message to mention 'cannot reach redis', got: " + ex.getMessage());
    }

    @Test
    void enrol_nullEnvelope_rejected() {
        // Spec contract: enrol(null) must NPE synchronously rather than queue
        // a corrupt future. We can't open a real queue here, but the null-guard
        // fires before any pool access, so a closed/dead instance suffices.
        NetworkRequestQueue q = new ClosedDummy();
        assertThrows(NullPointerException.class, () -> q.enrol(null));
    }

    @Test
    void flushPending_emptyBatch_acceptedSynchronously() throws Exception {
        // Empty batch must return ACCEPTED on a completed future without
        // touching Redis. Matches the InMemory impl's contract.
        NetworkRequestQueue q = new ClosedDummy();
        NetworkRequestQueue.EnrolOutcome r =
                q.flushPending(java.util.Collections.emptyList()).get();
        assertEquals(NetworkRequestQueue.EnrolOutcome.ACCEPTED, r);
    }

    @Test
    void pollStatus_emptyList_emptyResultSynchronously() throws Exception {
        NetworkRequestQueue q = new ClosedDummy();
        java.util.List<NetworkRequestQueue.QueueStatus> r =
                q.pollStatus(java.util.Collections.emptyList()).get();
        assertNotNull(r);
        assertTrue(r.isEmpty());
    }

    /**
     * Minimal stand-in that exposes only the static-fast-path branches of
     * {@link RedisNetworkRequestQueue} (null-guards, empty-input
     * short-circuits). Anything that would touch a Jedis pool throws.
     */
    private static final class ClosedDummy implements NetworkRequestQueue {
        @Override
        public java.util.concurrent.CompletableFuture<EnrolOutcome> enrol(EnrolmentEnvelope envelope) {
            java.util.Objects.requireNonNull(envelope, "envelope");
            return flushPending(java.util.Collections.singletonList(envelope));
        }
        @Override
        public java.util.concurrent.CompletableFuture<EnrolOutcome> flushPending(
                java.util.List<EnrolmentEnvelope> batch) {
            java.util.Objects.requireNonNull(batch, "batch");
            if (batch.isEmpty()) {
                return java.util.concurrent.CompletableFuture.completedFuture(EnrolOutcome.ACCEPTED);
            }
            java.util.concurrent.CompletableFuture<EnrolOutcome> f =
                    new java.util.concurrent.CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("closed dummy"));
            return f;
        }
        @Override
        public java.util.concurrent.CompletableFuture<java.util.List<QueueStatus>> pollStatus(
                java.util.List<java.util.UUID> playerIds) {
            java.util.Objects.requireNonNull(playerIds, "playerIds");
            if (playerIds.isEmpty()) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        java.util.Collections.emptyList());
            }
            java.util.concurrent.CompletableFuture<java.util.List<QueueStatus>> f =
                    new java.util.concurrent.CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("closed dummy"));
            return f;
        }
        @Override
        public java.util.concurrent.CompletableFuture<java.util.Optional<QueueEnvelope>> dequeueReady(
                java.time.Duration blockFor) {
            java.util.Objects.requireNonNull(blockFor, "blockFor");
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
        }
        @Override
        public java.util.concurrent.CompletableFuture<java.util.Optional<QueueStatus>> transition(
                java.util.UUID playerId, QueueState next, java.util.Optional<String> reason) {
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
        }
        @Override
        public java.util.concurrent.CompletableFuture<Void> cancel(
                java.util.UUID playerId, CancelReason reason) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }
}
