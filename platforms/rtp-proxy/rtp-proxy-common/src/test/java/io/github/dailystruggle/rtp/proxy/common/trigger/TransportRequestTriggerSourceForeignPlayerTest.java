package io.github.dailystruggle.rtp.proxy.common.trigger;

import io.github.dailystruggle.rtp.proxy.common.spi.DispatchOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpDispatcher;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-S-004: a proxy that dequeues a request for a player
 * held by another proxy must not destroy it.
 */
@DisplayName("REQ-RTP-S-004: foreign-player envelopes are handed back, not cancelled")
class TransportRequestTriggerSourceForeignPlayerTest {

    /** Shared-store stand-in: requeue puts the envelope back at the tail. */
    private static final class SharedQueue implements NetworkRequestQueue {
        final ConcurrentLinkedQueue<QueueEnvelope> ready = new ConcurrentLinkedQueue<>();
        final boolean requeueSupported;
        final AtomicInteger requeues = new AtomicInteger();
        final List<CancelReason> cancels = new CopyOnWriteArrayList<>();

        SharedQueue(boolean requeueSupported) {
            this.requeueSupported = requeueSupported;
        }

        void offer(UUID player) {
            long now = System.currentTimeMillis();
            ready.add(new QueueEnvelope(player, UUID.randomUUID(), Optional.empty(), Optional.empty(), now, now));
        }

        @Override public CompletableFuture<EnrolOutcome> enrol(EnrolmentEnvelope e) {
            return CompletableFuture.completedFuture(EnrolOutcome.ACCEPTED);
        }
        @Override public CompletableFuture<EnrolOutcome> flushPending(List<EnrolmentEnvelope> b) {
            return CompletableFuture.completedFuture(EnrolOutcome.ACCEPTED);
        }
        @Override public CompletableFuture<List<QueueStatus>> pollStatus(List<UUID> ids) {
            return CompletableFuture.completedFuture(List.of());
        }
        @Override public CompletableFuture<Optional<QueueEnvelope>> dequeueReady(Duration blockFor) {
            QueueEnvelope e = ready.poll();
            if (e != null) return CompletableFuture.completedFuture(Optional.of(e));
            try { Thread.sleep(20L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            return CompletableFuture.completedFuture(Optional.empty());
        }
        @Override public boolean supportsRequeue() {
            return requeueSupported;
        }
        @Override public CompletableFuture<Boolean> requeue(QueueEnvelope envelope) {
            requeues.incrementAndGet();
            ready.add(envelope);
            return CompletableFuture.completedFuture(true);
        }
        @Override public CompletableFuture<Optional<QueueStatus>> transition(UUID p, QueueState s, Optional<String> r) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        @Override public CompletableFuture<Void> cancel(UUID p, CancelReason r) {
            cancels.add(r);
            ready.removeIf(e -> e.playerId().equals(p));
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class RecordingDispatcher implements RtpDispatcher {
        final ConcurrentLinkedQueue<RtpRequest> seen = new ConcurrentLinkedQueue<>();
        @Override public CompletableFuture<DispatchOutcome> dispatch(RtpRequest r) {
            seen.add(r);
            return CompletableFuture.completedFuture(
                    new DispatchOutcome.Failed(DispatchOutcome.Failed.Reason.INTERNAL, "test"));
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
    }

    @Test
    @DisplayName("shared queue: envelope for a player held elsewhere is requeued, never cancelled or dispatched")
    void foreignEnvelopeIsHandedBack() throws Exception {
        SharedQueue q = new SharedQueue(true);
        RecordingDispatcher d = new RecordingDispatcher();
        q.offer(UUID.randomUUID());
        TransportRequestTriggerSource src = new TransportRequestTriggerSource(
                q, d, 1, Duration.ofMillis(200), null, "proxy-a", id -> false, 60_000L);
        src.start();
        try {
            await(() -> q.requeues.get() >= 2);
        } finally {
            src.stop();
        }
        assertTrue(q.requeues.get() >= 2, "foreign envelope must keep being handed back");
        assertTrue(q.cancels.isEmpty(), "a request held by another proxy must not be cancelled");
        assertTrue(d.seen.isEmpty(), "a foreign player must never be dispatched");
        assertEquals(1, q.ready.size(), "the envelope must still be in the shared queue for its owner");
    }

    @Test
    @DisplayName("shared queue: owning proxy dispatches the envelope another proxy handed back")
    void owningProxyDispatchesHandedBackEnvelope() throws Exception {
        SharedQueue q = new SharedQueue(true);
        RecordingDispatcher d = new RecordingDispatcher();
        UUID player = UUID.randomUUID();
        q.offer(player);
        TransportRequestTriggerSource foreign = new TransportRequestTriggerSource(
                q, new RecordingDispatcher(), 1, Duration.ofMillis(200), null, "proxy-a", id -> false, 60_000L);
        foreign.start();
        try {
            await(() -> q.requeues.get() >= 1);
        } finally {
            foreign.stop();
        }
        TransportRequestTriggerSource owner = new TransportRequestTriggerSource(
                q, d, 1, Duration.ofMillis(200), null, "proxy-b", player::equals, 60_000L);
        owner.start();
        try {
            await(() -> !d.seen.isEmpty());
        } finally {
            owner.stop();
        }
        assertEquals(1, d.seen.size());
        assertEquals(player, d.seen.peek().playerId());
        assertTrue(q.cancels.isEmpty());
    }

    @Test
    @DisplayName("shared queue: envelope still foreign after the grace window is cancelled as orphaned")
    void orphanCancelledAfterGrace() throws Exception {
        SharedQueue q = new SharedQueue(true);
        q.offer(UUID.randomUUID());
        TransportRequestTriggerSource src = new TransportRequestTriggerSource(
                q, new RecordingDispatcher(), 1, Duration.ofMillis(200), null, "proxy-a", id -> false, 150L);
        src.start();
        try {
            await(() -> !q.cancels.isEmpty());
        } finally {
            src.stop();
        }
        assertEquals(List.of(NetworkRequestQueue.CancelReason.TTL_EXPIRED), q.cancels);
        assertEquals(0, src.trackedForeignCount());
    }

    @Test
    @DisplayName("JVM-local queue: a player not on the only proxy is gone, so the entry is cancelled")
    void localQueueCancels() throws Exception {
        SharedQueue q = new SharedQueue(false);
        q.offer(UUID.randomUUID());
        TransportRequestTriggerSource src = new TransportRequestTriggerSource(
                q, new RecordingDispatcher(), 1, Duration.ofMillis(200), null, null, id -> false);
        src.start();
        try {
            await(() -> !q.cancels.isEmpty());
        } finally {
            src.stop();
        }
        assertEquals(List.of(NetworkRequestQueue.CancelReason.PLAYER_DISCONNECT), q.cancels);
        assertEquals(0, q.requeues.get());
    }
}
