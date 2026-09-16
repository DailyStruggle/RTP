package io.github.dailystruggle.rtp.proxy.common.dispatch;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.spi.DispatchOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpDispatcher;
import io.github.dailystruggle.rtp.proxy.common.spi.WaitlistLeaderLease;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NetworkWaitlistDrainerEdgeTest {

    @Test
    void ctor_nullChecks() {
        NetworkWaitlist waitlist = mock(NetworkWaitlist.class);
        NetworkTransport transport = mock(NetworkTransport.class);
        RtpDispatcher dispatcher = mock(RtpDispatcher.class);
        WaitlistLeaderLease lease = mock(WaitlistLeaderLease.class);

        assertThrows(NullPointerException.class, () -> new NetworkWaitlistDrainer(null, transport, dispatcher, lease));
        assertThrows(NullPointerException.class, () -> new NetworkWaitlistDrainer(waitlist, null, dispatcher, lease));
        assertThrows(NullPointerException.class, () -> new NetworkWaitlistDrainer(waitlist, transport, null, lease));
        assertThrows(NullPointerException.class, () -> new NetworkWaitlistDrainer(waitlist, transport, dispatcher, null));
    }

    @Test
    void runPulse_drainBatchReturnsEmptyMap_resolvesNoCapacity() throws Exception {
        NetworkWaitlist waitlist = mock(NetworkWaitlist.class);
        NetworkTransport transport = mock(NetworkTransport.class);
        RtpDispatcher dispatcher = mock(RtpDispatcher.class);
        WaitlistLeaderLease lease = mock(WaitlistLeaderLease.class);

        when(lease.tryAcquire(any())).thenReturn(CompletableFuture.completedFuture(true));
        when(waitlist.size()).thenReturn(CompletableFuture.completedFuture(5));

        BackendHeartbeat hb = new BackendHeartbeat(
                "srv-1", 1, PluginState.READY, true, System.currentTimeMillis(),
                20.0, 0, 100, 10, 100, 0, List.of(), List.of(), false, 5, 0,
                Collections.singleton("r1"), Collections.singletonMap("r1", 5));

        NetworkSnapshot snap = new NetworkSnapshot(1L, Collections.singletonMap("srv-1", hb));
        when(transport.readSnapshot()).thenReturn(CompletableFuture.completedFuture(snap));
        when(waitlist.drainBatch(any(), anyInt())).thenReturn(CompletableFuture.completedFuture(Collections.emptyMap()));

        NetworkWaitlistDrainer drainer = new NetworkWaitlistDrainer(waitlist, transport, dispatcher, lease);
        NetworkWaitlistDrainer.PulseResult res = drainer.runPulse().get();
        assertEquals(NetworkWaitlistDrainer.PulseResult.NO_CAPACITY, res);
    }

    @Test
    void runPulse_dispatcherThrowsSynchronously_treatedAsFailed() throws Exception {
        NetworkWaitlist waitlist = mock(NetworkWaitlist.class);
        NetworkTransport transport = mock(NetworkTransport.class);
        RtpDispatcher dispatcher = mock(RtpDispatcher.class);
        WaitlistLeaderLease lease = mock(WaitlistLeaderLease.class);

        when(lease.tryAcquire(any())).thenReturn(CompletableFuture.completedFuture(true));
        when(waitlist.size()).thenReturn(CompletableFuture.completedFuture(1));

        BackendHeartbeat hb = new BackendHeartbeat(
                "srv-1", 1, PluginState.READY, true, System.currentTimeMillis(),
                20.0, 0, 100, 10, 100, 0, List.of(), List.of(), false, 5, 0,
                Collections.singleton("r1"), Collections.singletonMap("r1", 5));

        NetworkSnapshot snap = new NetworkSnapshot(1L, Collections.singletonMap("srv-1", hb));
        when(transport.readSnapshot()).thenReturn(CompletableFuture.completedFuture(snap));

        NetworkWaitlist.WaitEnvelope env = new NetworkWaitlist.WaitEnvelope(
                UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), Optional.empty(), "proxy-1", System.currentTimeMillis());
        when(waitlist.drainBatch(any(), anyInt())).thenReturn(CompletableFuture.completedFuture(Map.of("srv-1", List.of(env))));
        when(waitlist.refreshAllTtl()).thenReturn(CompletableFuture.completedFuture(1));

        when(dispatcher.dispatch(any())).thenThrow(new RuntimeException("Immediate dispatch boom"));

        NetworkWaitlistDrainer drainer = new NetworkWaitlistDrainer(waitlist, transport, dispatcher, lease);
        NetworkWaitlistDrainer.PulseResult res = drainer.runPulse().get();
        assertEquals(NetworkWaitlistDrainer.PulseResult.TOTAL_FAILURE, res);
    }
}
