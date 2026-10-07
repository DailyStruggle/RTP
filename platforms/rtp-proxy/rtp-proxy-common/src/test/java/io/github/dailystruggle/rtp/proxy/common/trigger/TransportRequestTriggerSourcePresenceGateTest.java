package io.github.dailystruggle.rtp.proxy.common.trigger;

import io.github.dailystruggle.rtp.proxy.common.spi.DispatchOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.EnrolmentEnvelope;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpDispatcher;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkRequestQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Local-presence gate on dequeued shared-store envelopes. */
class TransportRequestTriggerSourcePresenceGateTest {

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
    }

    @Test
    @DisplayName("REQ-RTP-S-004: envelope for a player not connected to this proxy is cancelled, never dispatched")
    void nonLocalPlayerSkipped() throws Exception {
        InMemoryNetworkRequestQueue queue = new InMemoryNetworkRequestQueue();
        RtpDispatcher dispatcher = mock(RtpDispatcher.class);
        when(dispatcher.dispatch(any())).thenReturn(
                CompletableFuture.completedFuture(new DispatchOutcome.Queued(0)));
        UUID local = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        TransportRequestTriggerSource src = new TransportRequestTriggerSource(
                queue, dispatcher, 1, Duration.ofMillis(100), null, null, local::equals);
        try {
            queue.flushPending(List.of(
                    new EnrolmentEnvelope(foreign, UUID.randomUUID(), Optional.empty(), Optional.empty(), 1L),
                    new EnrolmentEnvelope(local, UUID.randomUUID(), Optional.empty(), Optional.empty(), 2L)))
                    .get(2, TimeUnit.SECONDS);
            src.start();
            verify(dispatcher, timeout(3000)).dispatch(argThat(r -> local.equals(r.playerId())));
            verify(dispatcher, never()).dispatch(argThat(r -> foreign.equals(r.playerId())));
            // Counter increments after dispatch() returns, so the verify above can win the race.
            await(() -> src.dispatchedCount() == 1L);
            assertEquals(1L, src.dispatchedCount());
            // Foreign player's status row was cancelled rather than left lingering (cancel is async).
            await(() -> queue.pollStatus(List.of(foreign)).join().isEmpty());
            assertTrue(queue.pollStatus(List.of(foreign)).get(2, TimeUnit.SECONDS).isEmpty());
        } finally {
            src.stop();
            queue.shutdown();
        }
    }

    @Test
    @DisplayName("REQ-RTP-S-004: a throwing presence predicate fails closed (no dispatch)")
    void throwingPredicateFailsClosed() throws Exception {
        InMemoryNetworkRequestQueue queue = new InMemoryNetworkRequestQueue();
        RtpDispatcher dispatcher = mock(RtpDispatcher.class);
        TransportRequestTriggerSource src = new TransportRequestTriggerSource(
                queue, dispatcher, 1, Duration.ofMillis(100), null, null,
                p -> { throw new IllegalStateException("boom"); });
        try {
            queue.enrol(new EnrolmentEnvelope(UUID.randomUUID(), UUID.randomUUID(),
                    Optional.empty(), Optional.empty(), 1L)).get(2, TimeUnit.SECONDS);
            src.start();
            Thread.sleep(400L);
            verify(dispatcher, never()).dispatch(any());
            assertEquals(0, queue.readyDepth());
        } finally {
            src.stop();
            queue.shutdown();
        }
    }
}
