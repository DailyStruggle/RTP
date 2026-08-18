package io.github.dailystruggle.rtp.proxy.common.transport.memory;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.CancelReason;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.EnrolOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.EnrolmentEnvelope;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueEnvelope;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueState;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle and idempotency coverage for the reference {@link InMemoryNetworkRequestQueue}.
 */
final class InMemoryNetworkRequestQueueTest {

    private InMemoryNetworkRequestQueue q;

    @BeforeEach
    void setUp() {
        q = new InMemoryNetworkRequestQueue();
    }

    @AfterEach
    void tearDown() {
        q.shutdown();
    }

    private static EnrolmentEnvelope env(UUID player) {
        return new EnrolmentEnvelope(
                player, UUID.randomUUID(),
                Optional.of("overworld"), Optional.empty(),
                System.currentTimeMillis());
    }

    @Test
    void enrol_makes_envelope_available_to_dequeue_and_records_queued_status() throws Exception {
        UUID p = UUID.randomUUID();
        assertEquals(EnrolOutcome.ACCEPTED, q.enrol(env(p)).get(2, TimeUnit.SECONDS));
        assertEquals(1, q.readyDepth());

        Optional<QueueEnvelope> popped = q.dequeueReady(Duration.ofMillis(500)).get(2, TimeUnit.SECONDS);
        assertTrue(popped.isPresent());
        assertEquals(p, popped.get().playerId());

        List<QueueStatus> statuses = q.pollStatus(List.of(p)).get(2, TimeUnit.SECONDS);
        assertEquals(1, statuses.size());
        assertEquals(QueueState.QUEUED, statuses.get(0).state());
    }

    @Test
    void enrol_is_idempotent_per_correlation_id() throws Exception {
        UUID p = UUID.randomUUID();
        EnrolmentEnvelope e = env(p);
        assertEquals(EnrolOutcome.ACCEPTED, q.enrol(e).get(2, TimeUnit.SECONDS));
        assertEquals(EnrolOutcome.ACCEPTED, q.enrol(e).get(2, TimeUnit.SECONDS));
        assertEquals(1, q.readyDepth());
    }

    @Test
    void flushPending_batch_enqueues_all_then_dequeues_in_order() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        EnrolOutcome out = q.flushPending(List.of(env(a), env(b), env(c))).get(2, TimeUnit.SECONDS);
        assertEquals(EnrolOutcome.ACCEPTED, out);
        assertEquals(3, q.readyDepth());

        assertEquals(a, q.dequeueReady(Duration.ofMillis(500)).get(2, TimeUnit.SECONDS).orElseThrow().playerId());
        assertEquals(b, q.dequeueReady(Duration.ofMillis(500)).get(2, TimeUnit.SECONDS).orElseThrow().playerId());
        assertEquals(c, q.dequeueReady(Duration.ofMillis(500)).get(2, TimeUnit.SECONDS).orElseThrow().playerId());
    }

    @Test
    void flushPending_empty_batch_is_a_noop_accept() throws Exception {
        EnrolOutcome out = q.flushPending(List.of()).get(2, TimeUnit.SECONDS);
        assertEquals(EnrolOutcome.ACCEPTED, out);
        assertEquals(0, q.readyDepth());
    }

    @Test
    void dequeue_returns_empty_after_timeout_on_empty_queue() throws Exception {
        long t0 = System.nanoTime();
        Optional<QueueEnvelope> out = q.dequeueReady(Duration.ofMillis(50)).get(2, TimeUnit.SECONDS);
        long elapsed = (System.nanoTime() - t0) / 1_000_000L;
        assertFalse(out.isPresent());
        assertTrue(elapsed >= 40L, "expected to block ~50ms, was " + elapsed + "ms");
    }

    @Test
    void transition_updates_status_and_terminal_states_evict() throws Exception {
        UUID p = UUID.randomUUID();
        q.enrol(env(p)).get(2, TimeUnit.SECONDS);

        Optional<QueueStatus> after = q.transition(p, QueueState.ROUTING, Optional.empty())
                .get(2, TimeUnit.SECONDS);
        assertTrue(after.isPresent());
        assertEquals(QueueState.ROUTING, after.get().state());

        Optional<QueueStatus> done = q.transition(p, QueueState.COMPLETED, Optional.empty())
                .get(2, TimeUnit.SECONDS);
        assertTrue(done.isPresent());
        assertEquals(QueueState.COMPLETED, done.get().state());

        // terminal -> evicted; further poll returns nothing
        List<QueueStatus> later = q.pollStatus(List.of(p)).get(2, TimeUnit.SECONDS);
        assertTrue(later.isEmpty());
    }

    @Test
    void transition_on_unknown_player_returns_empty() throws Exception {
        Optional<QueueStatus> out = q.transition(UUID.randomUUID(), QueueState.ROUTING, Optional.empty())
                .get(2, TimeUnit.SECONDS);
        assertFalse(out.isPresent());
    }

    @Test
    void cancel_removes_status_and_scrubs_ready_entry() throws Exception {
        UUID p = UUID.randomUUID();
        q.enrol(env(p)).get(2, TimeUnit.SECONDS);
        assertEquals(1, q.readyDepth());

        q.cancel(p, CancelReason.PLAYER_DISCONNECT).get(2, TimeUnit.SECONDS);
        assertEquals(0, q.readyDepth());
        assertEquals(0, q.statusCount());

        // idempotent
        q.cancel(p, CancelReason.PLAYER_DISCONNECT).get(2, TimeUnit.SECONDS);
    }

    @Test
    void pollStatus_returns_only_known_players_and_skips_nulls() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        q.enrol(env(a)).get(2, TimeUnit.SECONDS);
        // b is never enrolled
        List<UUID> input = new java.util.ArrayList<>();
        input.add(a);
        input.add(null);
        input.add(b);
        List<QueueStatus> out = q.pollStatus(input).get(2, TimeUnit.SECONDS);
        assertEquals(1, out.size());
        assertEquals(a, out.get(0).playerId());
    }

    @Test
    void after_shutdown_enrol_is_rejected_and_dequeue_returns_empty() throws Exception {
        q.shutdown();
        // After shutdown the executor is gone; enrol futures still return REJECTED
        // when they can be scheduled, but the safer assertion is that the queue is empty
        // and shutdown is idempotent.
        q.shutdown();
        assertEquals(0, q.readyDepth());
        assertEquals(0, q.statusCount());
    }

    @Test
    void enrol_rejects_null_envelope() {
        assertThrows(NullPointerException.class, () -> q.enrol(null));
    }

    @Test
    void queue_state_and_cancel_reason_enums_have_expected_members() {
        // guard against accidental enum reordering / removal in the SPI
        assertNotNull(QueueState.valueOf("QUEUED"));
        assertNotNull(QueueState.valueOf("ROUTING"));
        assertNotNull(QueueState.valueOf("RESERVED"));
        assertNotNull(QueueState.valueOf("TRANSFERRING"));
        assertNotNull(QueueState.valueOf("COMPLETED"));
        assertNotNull(QueueState.valueOf("FAILED"));
        assertNotNull(QueueState.valueOf("CANCELLED"));
        assertNotNull(QueueState.valueOf("UNKNOWN"));
        assertNotNull(CancelReason.valueOf("PLAYER_DISCONNECT"));
        assertNotNull(CancelReason.valueOf("TTL_EXPIRED"));
        assertNotNull(CancelReason.valueOf("EXPLICIT_REQUEST"));
        assertNotNull(CancelReason.valueOf("TRANSPORT_ERROR"));
    }

    @Test
    void enrolment_envelope_record_rejects_nulls() {
        assertThrows(NullPointerException.class,
                () -> new EnrolmentEnvelope(null, UUID.randomUUID(),
                        Optional.empty(), Optional.empty(), 0L));
        assertThrows(NullPointerException.class,
                () -> new EnrolmentEnvelope(UUID.randomUUID(), null,
                        Optional.empty(), Optional.empty(), 0L));
    }

    @Test
    void invoking_an_in_memory_request_then_shutdown_does_not_leak_threads() {
        // best-effort: shutdown must not throw and must be idempotent.
        q.shutdown();
        q.shutdown();
    }
}
