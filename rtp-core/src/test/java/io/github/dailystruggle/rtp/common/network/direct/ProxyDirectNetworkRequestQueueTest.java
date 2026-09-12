package io.github.dailystruggle.rtp.common.network.direct;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.CancelReason;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.EnrolOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.EnrolmentEnvelope;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueState;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueStatus;
import io.github.dailystruggle.rtp.proxy.common.transport.direct.ProxyDirectWire;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ProxyDirectNetworkRequestQueue tests")
class ProxyDirectNetworkRequestQueueTest {

    @Test
    void nullEnrolment_returnsRejected() throws Exception {
        ProxyDirectNetworkBinding binding = Mockito.mock(ProxyDirectNetworkBinding.class);
        ProxyDirectNetworkRequestQueue queue = new ProxyDirectNetworkRequestQueue(binding);

        EnrolOutcome outcome = queue.enrol(null).get();
        assertEquals(EnrolOutcome.REJECTED, outcome);
    }

    @Test
    void emptyFlushPending_returnsAcceptedImmediately() throws Exception {
        ProxyDirectNetworkBinding binding = Mockito.mock(ProxyDirectNetworkBinding.class);
        ProxyDirectNetworkRequestQueue queue = new ProxyDirectNetworkRequestQueue(binding);

        assertEquals(EnrolOutcome.ACCEPTED, queue.flushPending(null).get());
        assertEquals(EnrolOutcome.ACCEPTED, queue.flushPending(Collections.emptyList()).get());
    }

    @Test
    void flushPending_success_returnsAccepted() throws Exception {
        ProxyDirectNetworkBinding binding = Mockito.mock(ProxyDirectNetworkBinding.class);
        when(binding.rpcCallListReq(eq(ProxyDirectWire.OP_FLUSH_PENDING), anyList()))
                .thenReturn("ACCEPTED");

        ProxyDirectNetworkRequestQueue queue = new ProxyDirectNetworkRequestQueue(binding);
        UUID pid = UUID.randomUUID();
        UUID cid = UUID.randomUUID();
        EnrolmentEnvelope env = new EnrolmentEnvelope(
                pid, cid, Optional.of("survival"), Optional.of("world"), Instant.now().toEpochMilli());

        EnrolOutcome outcome = queue.flushPending(List.of(env)).get();
        assertEquals(EnrolOutcome.ACCEPTED, outcome);
        verify(binding).rpcCallListReq(eq(ProxyDirectWire.OP_FLUSH_PENDING), anyList());
    }

    @Test
    void flushPending_nullResponse_returnsRejected() throws Exception {
        ProxyDirectNetworkBinding binding = Mockito.mock(ProxyDirectNetworkBinding.class);
        when(binding.rpcCallListReq(eq(ProxyDirectWire.OP_FLUSH_PENDING), anyList()))
                .thenReturn(null);

        ProxyDirectNetworkRequestQueue queue = new ProxyDirectNetworkRequestQueue(binding);
        UUID pid = UUID.randomUUID();
        UUID cid = UUID.randomUUID();
        EnrolmentEnvelope env = new EnrolmentEnvelope(
                pid, cid, Optional.empty(), Optional.empty(), Instant.now().toEpochMilli());

        EnrolOutcome outcome = queue.enrol(env).get();
        assertEquals(EnrolOutcome.REJECTED, outcome);
    }

    @Test
    void flushPending_invalidResponse_returnsRejected() throws Exception {
        ProxyDirectNetworkBinding binding = Mockito.mock(ProxyDirectNetworkBinding.class);
        when(binding.rpcCallListReq(eq(ProxyDirectWire.OP_FLUSH_PENDING), anyList()))
                .thenReturn("UNKNOWN_OUTCOME_STRING");

        ProxyDirectNetworkRequestQueue queue = new ProxyDirectNetworkRequestQueue(binding);
        EnrolmentEnvelope env = new EnrolmentEnvelope(
                UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), Optional.empty(), Instant.now().toEpochMilli());

        EnrolOutcome outcome = queue.flushPending(List.of(env)).get();
        assertEquals(EnrolOutcome.REJECTED, outcome);
    }

    @Test
    void pollStatus_emptyOrNull_returnsEmptyList() throws Exception {
        ProxyDirectNetworkBinding binding = Mockito.mock(ProxyDirectNetworkBinding.class);
        ProxyDirectNetworkRequestQueue queue = new ProxyDirectNetworkRequestQueue(binding);

        assertTrue(queue.pollStatus(null).get().isEmpty());
        assertTrue(queue.pollStatus(Collections.emptyList()).get().isEmpty());
    }

    @Test
    void pollStatus_decodesStatusRows() throws Exception {
        ProxyDirectNetworkBinding binding = Mockito.mock(ProxyDirectNetworkBinding.class);
        UUID p1 = UUID.randomUUID();
        QueueStatus status = new QueueStatus(
                p1, QueueState.WAITLISTED, 2, Optional.empty(), Optional.empty(), System.currentTimeMillis());
        String encoded = ProxyDirectWire.encodeStatus(status);

        when(binding.rpcCallListBoth(eq(ProxyDirectWire.OP_POLL_STATUS), anyList()))
                .thenReturn(List.of(encoded, "corrupt_row_not_a_status"));

        ProxyDirectNetworkRequestQueue queue = new ProxyDirectNetworkRequestQueue(binding);
        List<QueueStatus> results = queue.pollStatus(List.of(p1)).get();

        assertEquals(1, results.size());
        assertEquals(p1, results.get(0).playerId());
        assertEquals(QueueState.WAITLISTED, results.get(0).state());
        assertEquals(2, results.get(0).positionInQueue());
    }

    @Test
    void dequeueReady_and_transition_areUnsupportedLocally() throws Exception {
        ProxyDirectNetworkBinding binding = Mockito.mock(ProxyDirectNetworkBinding.class);
        ProxyDirectNetworkRequestQueue queue = new ProxyDirectNetworkRequestQueue(binding);

        assertFalse(queue.dequeueReady(Duration.ofSeconds(1)).get().isPresent());
        assertFalse(queue.transition(UUID.randomUUID(), QueueState.ROUTING, Optional.empty()).get().isPresent());
    }

    @Test
    void cancel_nullPlayer_isNoOp() throws Exception {
        ProxyDirectNetworkBinding binding = Mockito.mock(ProxyDirectNetworkBinding.class);
        ProxyDirectNetworkRequestQueue queue = new ProxyDirectNetworkRequestQueue(binding);

        assertNull(queue.cancel(null, CancelReason.EXPLICIT_REQUEST).get());
        Mockito.verifyNoInteractions(binding);
    }

    @Test
    void cancel_validPlayer_forwardsRpc() throws Exception {
        ProxyDirectNetworkBinding binding = Mockito.mock(ProxyDirectNetworkBinding.class);
        ProxyDirectNetworkRequestQueue queue = new ProxyDirectNetworkRequestQueue(binding);
        UUID pid = UUID.randomUUID();

        queue.cancel(pid, CancelReason.PLAYER_DISCONNECT).get();
        verify(binding).rpcCall(eq(ProxyDirectWire.OP_CANCEL), eq(pid + "\u0001PLAYER_DISCONNECT"));

        // Default to EXPLICIT_REQUEST when reason is null
        queue.cancel(pid, null).get();
        verify(binding).rpcCall(eq(ProxyDirectWire.OP_CANCEL), eq(pid + "\u0001EXPLICIT_REQUEST"));
    }
}
