package io.github.dailystruggle.rtp.proxy.common.trigger;

import io.github.dailystruggle.rtp.proxy.common.spi.DispatchOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpDispatcher;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpRequest;
import io.github.dailystruggle.rtp.proxy.common.spi.TriggerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TransportRequestTriggerSource}.
 */
class TransportRequestTriggerSourceTest {

    /** Minimal in-test queue stub. Returns scripted envelopes then empties. */
    private static final class ScriptedQueue implements NetworkRequestQueue {
        private final ConcurrentLinkedQueue<QueueEnvelope> scripted = new ConcurrentLinkedQueue<>();
        volatile boolean failNext;
        volatile RuntimeException throwNext;

        void offer(UUID playerId, UUID correlationId) {
            scripted.add(new QueueEnvelope(playerId, correlationId,
                    Optional.empty(), Optional.empty(),
                    System.currentTimeMillis(), System.currentTimeMillis()));
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
            if (throwNext != null) {
                RuntimeException ex = throwNext;
                throwNext = null;
                return CompletableFuture.failedFuture(ex);
            }
            if (failNext) {
                failNext = false;
                return CompletableFuture.failedFuture(new RuntimeException("scripted-fail"));
            }
            QueueEnvelope e = scripted.poll();
            if (e != null) {
                return CompletableFuture.completedFuture(Optional.of(e));
            }
            // Simulate the blocking wait without actually blocking the test
            // thread. The worker awaits this future with `blockFor + 1s`; we
            // resolve to empty after a short sleep so the worker loops.
            CompletableFuture<Optional<QueueEnvelope>> fut = new CompletableFuture<>();
            Thread t = new Thread(() -> {
                try { Thread.sleep(Math.min(50L, blockFor.toMillis())); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                fut.complete(Optional.empty());
            }, "scripted-queue-empty");
            t.setDaemon(true);
            t.start();
            return fut;
        }
        @Override public CompletableFuture<Optional<QueueStatus>> transition(UUID p, QueueState s, Optional<String> r) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        @Override public CompletableFuture<Void> cancel(UUID p, CancelReason r) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Minimal dispatcher stub: records every request and completes the future. */
    private static final class RecordingDispatcher implements RtpDispatcher {
        final ConcurrentLinkedQueue<RtpRequest> seen = new ConcurrentLinkedQueue<>();
        final java.util.concurrent.atomic.AtomicInteger throwCount =
                new java.util.concurrent.atomic.AtomicInteger();
        volatile RuntimeException throwOn;
        @Override public CompletableFuture<DispatchOutcome> dispatch(RtpRequest r) {
            if (throwOn != null) {
                throwCount.incrementAndGet();
                throw throwOn;
            }
            seen.add(r);
            return CompletableFuture.completedFuture(
                    new DispatchOutcome.Failed(DispatchOutcome.Failed.Reason.INTERNAL, "test"));
        }
    }

    @Test
    @DisplayName("popped envelope is forwarded to dispatcher with matching playerId + correlationId")
    void poppedEnvelopeReachesDispatcher() throws Exception {
        ScriptedQueue q = new ScriptedQueue();
        RecordingDispatcher d = new RecordingDispatcher();
        UUID player = UUID.randomUUID();
        UUID corr = UUID.randomUUID();
        q.offer(player, corr);

        TransportRequestTriggerSource src = new TransportRequestTriggerSource(
                q, d, 1, Duration.ofMillis(200), null);
        src.start();
        try {
            long deadline = System.currentTimeMillis() + 3_000L;
            while (d.seen.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
        } finally {
            src.stop();
        }
        assertEquals(1, d.seen.size(), "exactly one request should reach the dispatcher");
        RtpRequest req = d.seen.peek();
        assertEquals(player, req.playerId());
        assertEquals(corr, req.correlationId());
        assertEquals(TriggerType.COMMAND, req.triggerType());
        assertTrue(req.regionKey().isEmpty());
        assertTrue(req.worldKey().isEmpty());
    }

    @Test
    @DisplayName("multiple envelopes drain to the dispatcher in order")
    void multipleEnvelopesDrain() throws Exception {
        ScriptedQueue q = new ScriptedQueue();
        RecordingDispatcher d = new RecordingDispatcher();
        UUID c1 = UUID.randomUUID(), c2 = UUID.randomUUID(), c3 = UUID.randomUUID();
        q.offer(UUID.randomUUID(), c1);
        q.offer(UUID.randomUUID(), c2);
        q.offer(UUID.randomUUID(), c3);

        TransportRequestTriggerSource src = new TransportRequestTriggerSource(
                q, d, 1, Duration.ofMillis(150), null);
        src.start();
        try {
            long deadline = System.currentTimeMillis() + 3_000L;
            while (d.seen.size() < 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
        } finally {
            src.stop();
        }
        assertEquals(3, d.seen.size());
        assertEquals(3L, src.dispatchedCount());
    }

    @Test
    @DisplayName("dequeueReady failure is logged and the worker continues")
    void dequeueFailureContinues() throws Exception {
        ScriptedQueue q = new ScriptedQueue();
        RecordingDispatcher d = new RecordingDispatcher();
        q.failNext = true;
        UUID player = UUID.randomUUID();
        UUID corr = UUID.randomUUID();
        q.offer(player, corr);

        TransportRequestTriggerSource src = new TransportRequestTriggerSource(
                q, d, 1, Duration.ofMillis(150), null);
        src.start();
        try {
            long deadline = System.currentTimeMillis() + 3_000L;
            while (d.seen.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
        } finally {
            src.stop();
        }
        assertEquals(1, d.seen.size(), "worker must keep draining past a dequeue failure");
    }

    @Test
    @DisplayName("dispatcher throw does not crash the worker")
    void dispatcherThrowSurvived() throws Exception {
        ScriptedQueue q = new ScriptedQueue();
        RecordingDispatcher d = new RecordingDispatcher();
        d.throwOn = new RuntimeException("simulated dispatcher boom");
        UUID p1 = UUID.randomUUID();
        UUID c1 = UUID.randomUUID();
        q.offer(p1, c1);

        TransportRequestTriggerSource src = new TransportRequestTriggerSource(
                q, d, 1, Duration.ofMillis(150), null);
        src.start();
        try {
            // Wait until the worker has actually popped p1 and attempted the
            // dispatch (which throws) before clearing throwOn. Using a fixed
            // sleep here is racy under heavy parallel test load: if the worker
            // is slow to start, p1 could be dispatched *after* throwOn is
            // cleared, leaving seen.size()==2 instead of 1.
            long boomDeadline = System.currentTimeMillis() + 3_000L;
            while (d.throwCount.get() == 0 && System.currentTimeMillis() < boomDeadline) {
                Thread.sleep(20L);
            }
            assertTrue(d.throwCount.get() >= 1, "worker must have attempted (and thrown) on the first envelope");
            d.throwOn = null;
            UUID p2 = UUID.randomUUID();
            UUID c2 = UUID.randomUUID();
            q.offer(p2, c2);
            long deadline = System.currentTimeMillis() + 3_000L;
            while (d.seen.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            assertEquals(1, d.seen.size(), "second envelope reaches the dispatcher after the first throws");
            assertEquals(c2, d.seen.peek().correlationId());
        } finally {
            src.stop();
        }
    }

    @Test
    @DisplayName("stop() interrupts the worker promptly and is idempotent")
    void stopIsIdempotent() throws Exception {
        ScriptedQueue q = new ScriptedQueue();
        RecordingDispatcher d = new RecordingDispatcher();
        TransportRequestTriggerSource src = new TransportRequestTriggerSource(
                q, d, 2, Duration.ofMillis(500), null);
        src.start();
        assertTrue(src.isRunning());
        long t0 = System.currentTimeMillis();
        src.stop();
        long elapsed = System.currentTimeMillis() - t0;
        assertFalse(src.isRunning());
        assertTrue(elapsed < 1_500L,
                "stop() should return well within SHUTDOWN_TIMEOUT_MS, got " + elapsed + "ms");
        // Second call must be a clean no-op.
        assertDoesNotThrow(src::stop);
    }

    @Test
    @DisplayName("double-start throws IllegalStateException")
    void doubleStartRejected() {
        ScriptedQueue q = new ScriptedQueue();
        RecordingDispatcher d = new RecordingDispatcher();
        TransportRequestTriggerSource src = new TransportRequestTriggerSource(
                q, d, 1, Duration.ofMillis(150), null);
        src.start();
        try {
            assertThrows(IllegalStateException.class, src::start);
        } finally {
            src.stop();
        }
    }

    @Test
    @DisplayName("ctor null-rejects queue / dispatcher and clamps worker count / poll timeout")
    void ctorValidatesArgs() {
        ScriptedQueue q = new ScriptedQueue();
        RecordingDispatcher d = new RecordingDispatcher();
        assertThrows(NullPointerException.class,
                () -> new TransportRequestTriggerSource(null, d, 1, Duration.ofMillis(150), null));
        assertThrows(NullPointerException.class,
                () -> new TransportRequestTriggerSource(q, null, 1, Duration.ofMillis(150), null));

        TransportRequestTriggerSource clamped = new TransportRequestTriggerSource(
                q, d, -5, Duration.ofMillis(1L), null);
        assertEquals(1, clamped.workerThreads(), "workerThreads must clamp to >= 1");
        assertEquals(100L, clamped.pollTimeout().toMillis(), "pollTimeout must clamp to >= 100ms");

        TransportRequestTriggerSource huge = new TransportRequestTriggerSource(
                q, d, 1, Duration.ofMinutes(5), null);
        assertEquals(30_000L, huge.pollTimeout().toMillis(), "pollTimeout must clamp to <= 30s");
    }

    @Test
    @DisplayName("workers run concurrently when workerThreads > 1")
    void multipleWorkersDrainConcurrently() throws Exception {
        // Use a queue that blocks each pop briefly so we can observe parallelism.
        ConcurrentLinkedQueue<UUID> emitted = new ConcurrentLinkedQueue<>();
        CountDownLatch concurrencyObserved = new CountDownLatch(2);
        AtomicReference<NetworkRequestQueue> queueRef = new AtomicReference<>();
        AtomicBoolean exhausted = new AtomicBoolean(false);

        NetworkRequestQueue q = new NetworkRequestQueue() {
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
                if (exhausted.get()) {
                    return CompletableFuture.completedFuture(Optional.empty());
                }
                concurrencyObserved.countDown();
                try { concurrencyObserved.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.completedFuture(Optional.empty());
                }
                UUID id = UUID.randomUUID();
                emitted.add(id);
                if (emitted.size() >= 2) exhausted.set(true);
                return CompletableFuture.completedFuture(Optional.of(
                        new QueueEnvelope(id, id, Optional.empty(), Optional.empty(),
                                System.currentTimeMillis(), System.currentTimeMillis())));
            }
            @Override public CompletableFuture<Optional<QueueStatus>> transition(UUID p, QueueState s, Optional<String> r) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            @Override public CompletableFuture<Void> cancel(UUID p, CancelReason r) {
                return CompletableFuture.completedFuture(null);
            }
        };
        queueRef.set(q);
        RecordingDispatcher d = new RecordingDispatcher();
        TransportRequestTriggerSource src = new TransportRequestTriggerSource(
                q, d, 2, Duration.ofMillis(150), null);
        src.start();
        try {
            assertTrue(concurrencyObserved.await(2, TimeUnit.SECONDS),
                    "two workers should reach dequeueReady concurrently");
        } finally {
            src.stop();
        }
        assertEquals(2, src.workerThreads());
    }
}
