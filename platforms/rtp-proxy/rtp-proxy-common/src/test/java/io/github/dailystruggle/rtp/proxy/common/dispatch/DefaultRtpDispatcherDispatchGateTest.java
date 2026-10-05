package io.github.dailystruggle.rtp.proxy.common.dispatch;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendSelector;
import io.github.dailystruggle.rtp.proxy.common.spi.DispatchOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxySender;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpRequest;
import io.github.dailystruggle.rtp.proxy.common.spi.TriggerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Dispatch gates for shared-store-sourced requests: local presence + one in-flight dispatch per player. */
class DefaultRtpDispatcherDispatchGateTest {

    private static RtpRequest request(UUID pid) {
        return new RtpRequest(pid, TriggerType.COMMAND, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), UUID.randomUUID());
    }

    @Test
    @DisplayName("REQ-RTP-S-004: dispatch is skipped (no snapshot, no claim) for a player not connected to this proxy")
    void notConnectedPlayerNotDispatched() throws Exception {
        NetworkTransport transport = mock(NetworkTransport.class);
        ProxySender sender = mock(ProxySender.class);
        when(sender.isConnected(any())).thenReturn(false);
        DefaultRtpDispatcher d = new DefaultRtpDispatcher(mock(BackendSelector.class), transport, sender, Runnable::run);

        DispatchOutcome out = d.dispatch(request(UUID.randomUUID())).get(2, TimeUnit.SECONDS);
        DispatchOutcome.Failed f = assertInstanceOf(DispatchOutcome.Failed.class, out);
        assertEquals(DispatchOutcome.Failed.Reason.PLAYER_GONE, f.reason());
        verify(transport, never()).readSnapshot();
        verify(transport, never()).claim(any(), any(), any(), any());
    }

    @Test
    @DisplayName("REQ-RTP-S-004: a second concurrent dispatch for the same player is refused; slot frees on completion")
    void duplicateInFlightDispatchRefused() throws Exception {
        NetworkTransport transport = mock(NetworkTransport.class);
        ProxySender sender = mock(ProxySender.class);
        when(sender.isConnected(any())).thenReturn(true);
        CompletableFuture<NetworkSnapshot> pending = new CompletableFuture<>();
        when(transport.readSnapshot()).thenReturn(pending);
        BackendSelector selector = mock(BackendSelector.class);
        when(selector.choose(any(), any())).thenReturn(Optional.empty());
        DefaultRtpDispatcher d = new DefaultRtpDispatcher(selector, transport, sender, Runnable::run,
                Duration.ofSeconds(30));

        UUID pid = UUID.randomUUID();
        CompletableFuture<DispatchOutcome> first = d.dispatch(request(pid));
        assertTrue(d.isInFlight(pid));

        DispatchOutcome second = d.dispatch(request(pid)).get(2, TimeUnit.SECONDS);
        DispatchOutcome.Failed f = assertInstanceOf(DispatchOutcome.Failed.class, second);
        assertEquals(DispatchOutcome.Failed.Reason.CLAIM_RACE, f.reason());
        verify(transport, times(1)).readSnapshot();

        pending.complete(new NetworkSnapshot(System.currentTimeMillis(), Map.of()));
        first.get(2, TimeUnit.SECONDS);
        assertFalse(d.isInFlight(pid));
        // Slot released: a fresh request proceeds to the transport again.
        when(transport.readSnapshot()).thenReturn(
                CompletableFuture.completedFuture(new NetworkSnapshot(System.currentTimeMillis(), Map.of())));
        d.dispatch(request(pid)).get(2, TimeUnit.SECONDS);
        verify(transport, times(2)).readSnapshot();
    }
}
