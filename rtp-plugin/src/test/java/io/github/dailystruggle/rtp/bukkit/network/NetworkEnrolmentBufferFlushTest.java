package io.github.dailystruggle.rtp.bukkit.network;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice C row C8 / C9: behaviour of {@link NetworkEnrolmentBuffer} when
 * exercised via {@link NetworkEnrolmentBuffer#flushOnce()} (no scheduler
 * dependency in tests).
 */
class NetworkEnrolmentBufferFlushTest {

    private static NetworkEnrolmentBuffer.EnrolmentRecord rec() {
        return new NetworkEnrolmentBuffer.EnrolmentRecord(
                UUID.randomUUID(), UUID.randomUUID(),
                Optional.of("safe"), Optional.empty(), System.currentTimeMillis());
    }

    @Test
    void offer_grows_pending_depth_lock_free() {
        List<NetworkEnrolmentBuffer.EnrolmentRecord> received = new ArrayList<>();
        NetworkEnrolmentBuffer buf = new NetworkEnrolmentBuffer(received::addAll, 0);
        for (int i = 0; i < 5; i++) buf.offer(rec());
        assertEquals(5, buf.pendingDepth());
    }

    @Test
    void flushOnce_drains_and_invokes_sink_in_FIFO_order() {
        List<UUID> sinkOrder = new ArrayList<>();
        NetworkEnrolmentBuffer buf = new NetworkEnrolmentBuffer(
                batch -> batch.forEach(r -> sinkOrder.add(r.correlationId())),
                0);
        List<UUID> producerOrder = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            NetworkEnrolmentBuffer.EnrolmentRecord r = rec();
            producerOrder.add(r.correlationId());
            buf.offer(r);
        }
        buf.flushOnce();
        assertEquals(0, buf.pendingDepth());
        assertEquals(producerOrder, sinkOrder);
    }

    @Test
    void flushOnce_respects_maxBatchSize_cap() {
        AtomicInteger flushes = new AtomicInteger(0);
        AtomicInteger totalDrained = new AtomicInteger(0);
        NetworkEnrolmentBuffer buf = new NetworkEnrolmentBuffer(batch -> {
            flushes.incrementAndGet();
            totalDrained.addAndGet(batch.size());
        }, /*maxBatchSize=*/3);
        for (int i = 0; i < 7; i++) buf.offer(rec());
        buf.flushOnce(); // drains 3, leaves 4
        assertEquals(1, flushes.get());
        assertEquals(3, totalDrained.get());
        assertEquals(4, buf.pendingDepth());
        buf.flushOnce(); buf.flushOnce(); // drains 3 + 1
        assertEquals(0, buf.pendingDepth());
        assertEquals(7, totalDrained.get());
    }

    @Test
    void sink_failure_re_enqueues_batch_at_head_preserving_order() {
        List<UUID> sinkOrder = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger(0);
        NetworkEnrolmentBuffer buf = new NetworkEnrolmentBuffer(batch -> {
            int n = calls.incrementAndGet();
            if (n == 1) throw new RuntimeException("simulated transport failure");
            batch.forEach(r -> sinkOrder.add(r.correlationId()));
        }, 0);
        List<UUID> producerOrder = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            NetworkEnrolmentBuffer.EnrolmentRecord r = rec();
            producerOrder.add(r.correlationId());
            buf.offer(r);
        }
        buf.flushOnce();
        // S-004: failed batch must remain pending, not silently dropped.
        assertEquals(3, buf.pendingDepth());
        buf.flushOnce();
        assertEquals(0, buf.pendingDepth());
        assertEquals(producerOrder, sinkOrder, "FIFO must be preserved after re-enqueue");
    }

    @Test
    void empty_flush_is_a_noop_and_does_not_invoke_sink() {
        AtomicInteger calls = new AtomicInteger(0);
        NetworkEnrolmentBuffer buf = new NetworkEnrolmentBuffer(batch -> calls.incrementAndGet(), 0);
        buf.flushOnce();
        assertEquals(0, calls.get());
    }

    @Test
    void shutdown_is_idempotent_without_scheduler() {
        NetworkEnrolmentBuffer buf = new NetworkEnrolmentBuffer(batch -> {}, 0);
        buf.shutdown(); // first call
        buf.shutdown(); // second call must not throw
        assertTrue(true);
    }

    @Test
    void enrolment_record_rejects_null_required_fields() {
        try {
            new NetworkEnrolmentBuffer.EnrolmentRecord(
                    null, UUID.randomUUID(), Optional.empty(), Optional.empty(), 0L);
            assertFalse(true, "expected NPE for null playerId");
        } catch (NullPointerException expected) { /* ok */ }
    }
}
