package io.github.dailystruggle.rtp.proxy.common.dispatch;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendSelector;
import io.github.dailystruggle.rtp.proxy.common.spi.DispatchOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueState;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxySender;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpDispatcher;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpRequest;
import io.github.dailystruggle.rtp.proxy.common.spi.TransferOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.TriggerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultRtpDispatcherEdgeTest {

    @Test
    void ctor_nullChecks() {
        BackendSelector selector = mock(BackendSelector.class);
        NetworkTransport transport = mock(NetworkTransport.class);
        ProxySender sender = mock(ProxySender.class);

        assertThrows(NullPointerException.class, () -> new DefaultRtpDispatcher(null, transport, sender, Runnable::run));
        assertThrows(NullPointerException.class, () -> new DefaultRtpDispatcher(selector, null, sender, Runnable::run));
        assertThrows(NullPointerException.class, () -> new DefaultRtpDispatcher(selector, transport, null, Runnable::run));
        assertThrows(NullPointerException.class, () -> new DefaultRtpDispatcher(selector, transport, sender, (java.util.concurrent.Executor) null));
    }

    @Test
    void dispatch_nullRequest_throws() {
        BackendSelector selector = mock(BackendSelector.class);
        NetworkTransport transport = mock(NetworkTransport.class);
        ProxySender sender = mock(ProxySender.class);
        DefaultRtpDispatcher d = new DefaultRtpDispatcher(selector, transport, sender, Runnable::run);

        assertThrows(NullPointerException.class, () -> d.dispatch(null));
    }

    @Test
    void dispatch_transportThrowsOrFailsFuture_returnsInternalError() throws Exception {
        BackendSelector selector = mock(BackendSelector.class);
        NetworkTransport transport = mock(NetworkTransport.class);
        ProxySender sender = mock(ProxySender.class);
        when(sender.isConnected(any())).thenReturn(true);
        when(transport.readSnapshot()).thenReturn(CompletableFuture.failedFuture(new RuntimeException("DB down")));

        DefaultRtpDispatcher d = new DefaultRtpDispatcher(selector, transport, sender, Runnable::run);
        RtpRequest req = new RtpRequest(UUID.randomUUID(), TriggerType.COMMAND, Optional.empty(), Optional.empty(), Optional.empty(), UUID.randomUUID());

        DispatchOutcome outcome = d.dispatch(req).get();
        assertInstanceOf(DispatchOutcome.Failed.class, outcome);
        assertEquals(DefaultRtpDispatcher.MSG_INTERNAL.key(), ((DispatchOutcome.Failed) outcome).messageKey());
    }

    @Test
    void dispatch_claimThrowsOrFails_releasesAndFails() throws Exception {
        BackendSelector selector = mock(BackendSelector.class);
        NetworkTransport transport = mock(NetworkTransport.class);
        ProxySender sender = mock(ProxySender.class);
        when(sender.isConnected(any())).thenReturn(true);

        NetworkSnapshot snap = new NetworkSnapshot(1L, Collections.emptyMap());
        when(transport.readSnapshot()).thenReturn(CompletableFuture.completedFuture(snap));
        when(selector.choose(any(), any())).thenReturn(Optional.of("srv-1"));
        // Production calls the 4-arg region-aware claim(server, player, ttl, regionKey)
        // default overload; stubbing only the 3-arg overload on a Mockito mock
        // leaves the 4-arg call returning null (Mockito stubs default methods).
        when(transport.claim(any(), any(), any(), any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("Claim error")));

        DefaultRtpDispatcher d = new DefaultRtpDispatcher(selector, transport, sender, Runnable::run);
        RtpRequest req = new RtpRequest(UUID.randomUUID(), TriggerType.COMMAND, Optional.empty(), Optional.empty(), Optional.empty(), UUID.randomUUID());

        DispatchOutcome outcome = d.dispatch(req).get();
        assertInstanceOf(DispatchOutcome.Failed.class, outcome);
        // A claim future that completes exceptionally (vs. resolving to a null
        // token) is an internal error: the top-level exceptionally branch fires
        // MSG_INTERNAL before the thenApply that would map a null token to
        // MSG_CLAIM_FAILED. A null token (CLAIM_RACE) is covered separately in
        // DefaultRtpDispatcherTest / StatusSinkTest.
        assertEquals(DefaultRtpDispatcher.MSG_INTERNAL.key(), ((DispatchOutcome.Failed) outcome).messageKey());
    }

    @Test
    void dispatch_transferThrowsOrFails_releasesTokenAndReturnsFailed() throws Exception {
        BackendSelector selector = mock(BackendSelector.class);
        NetworkTransport transport = mock(NetworkTransport.class);
        ProxySender sender = mock(ProxySender.class);
        when(sender.isConnected(any())).thenReturn(true);

        NetworkSnapshot snap = new NetworkSnapshot(1L, Collections.emptyMap());
        when(transport.readSnapshot()).thenReturn(CompletableFuture.completedFuture(snap));
        when(selector.choose(any(), any())).thenReturn(Optional.of("srv-1"));

        ReservationToken token = new ReservationToken("tok-1", "srv-1", UUID.randomUUID(), System.currentTimeMillis() + 10000, ReservationToken.State.CLAIMED);
        when(transport.claim(any(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(token));
        when(transport.release(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(sender.sendTo(any(), any(), any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("Network pipe broken")));

        DefaultRtpDispatcher d = new DefaultRtpDispatcher(selector, transport, sender, Runnable::run);
        RtpRequest req = new RtpRequest(UUID.randomUUID(), TriggerType.COMMAND, Optional.empty(), Optional.empty(), Optional.empty(), UUID.randomUUID());

        DispatchOutcome outcome = d.dispatch(req).get();
        assertInstanceOf(DispatchOutcome.Failed.class, outcome);
        assertEquals(DefaultRtpDispatcher.MSG_TRANSFER_FAILED.key(), ((DispatchOutcome.Failed) outcome).messageKey());
        verify(transport).release(token.tokenId(), ReleaseReason.BACKEND_REJECTED);
    }

    @Test
    void dispatch_waitlistEnrolFull_failsWithNoBackend() throws Exception {
        BackendSelector selector = mock(BackendSelector.class);
        NetworkTransport transport = mock(NetworkTransport.class);
        ProxySender sender = mock(ProxySender.class);
        when(sender.isConnected(any())).thenReturn(true);
        NetworkWaitlist waitlist = mock(NetworkWaitlist.class);

        NetworkSnapshot snap = new NetworkSnapshot(1L, Collections.emptyMap());
        when(transport.readSnapshot()).thenReturn(CompletableFuture.completedFuture(snap));
        when(selector.choose(any(), any())).thenReturn(Optional.empty());

        when(waitlist.enrol(any())).thenReturn(CompletableFuture.completedFuture(NetworkWaitlist.EnrolOutcome.REJECTED_FULL));

        DefaultRtpDispatcher d = new DefaultRtpDispatcher(selector, transport, sender, Runnable::run,
                Duration.ofSeconds(30), StatusSink.NO_OP, waitlist, "proxy-1");

        RtpRequest req = new RtpRequest(UUID.randomUUID(), TriggerType.COMMAND, Optional.empty(), Optional.empty(), Optional.empty(), UUID.randomUUID());
        DispatchOutcome outcome = d.dispatch(req).get();

        assertInstanceOf(DispatchOutcome.Failed.class, outcome);
        assertEquals(DefaultRtpDispatcher.MSG_NO_BACKEND.key(), ((DispatchOutcome.Failed) outcome).messageKey());
    }

    @Test
    void dispatch_waitlistEnrolError_failsWithNoBackend() throws Exception {
        BackendSelector selector = mock(BackendSelector.class);
        NetworkTransport transport = mock(NetworkTransport.class);
        ProxySender sender = mock(ProxySender.class);
        when(sender.isConnected(any())).thenReturn(true);
        NetworkWaitlist waitlist = mock(NetworkWaitlist.class);

        NetworkSnapshot snap = new NetworkSnapshot(1L, Collections.emptyMap());
        when(transport.readSnapshot()).thenReturn(CompletableFuture.completedFuture(snap));
        when(selector.choose(any(), any())).thenReturn(Optional.empty());

        when(waitlist.enrol(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("Redis waitlist down")));

        DefaultRtpDispatcher d = new DefaultRtpDispatcher(selector, transport, sender, Runnable::run,
                Duration.ofSeconds(30), StatusSink.NO_OP, waitlist, "proxy-1");

        RtpRequest req = new RtpRequest(UUID.randomUUID(), TriggerType.COMMAND, Optional.empty(), Optional.empty(), Optional.empty(), UUID.randomUUID());
        DispatchOutcome outcome = d.dispatch(req).get();

        assertInstanceOf(DispatchOutcome.Failed.class, outcome);
        assertEquals(DefaultRtpDispatcher.MSG_NO_BACKEND.key(), ((DispatchOutcome.Failed) outcome).messageKey());
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: dispatcher logs only a redacted reservation token id")
    void tokenIdsAreRedactedInLogs() throws Exception {
        String tokenId = "tok-0123456789abcdef-SECRET";
        Logger log = Logger.getLogger(DefaultRtpDispatcher.class.getName());
        List<String> lines = new CopyOnWriteArrayList<>();
        SimpleFormatter fmt = new SimpleFormatter();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord r) { lines.add(fmt.formatMessage(r)); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Level prevLevel = log.getLevel();
        log.setLevel(Level.ALL);
        log.addHandler(capture);
        try {
            // Transfer refused, transfer threw, disconnect after claim; release throws (FINE path).
            CompletableFuture<?>[] transfers = {
                    CompletableFuture.completedFuture(TransferOutcome.BACKEND_REFUSED),
                    CompletableFuture.failedFuture(new RuntimeException("pipe broken")),
                    null};
            for (CompletableFuture<?> transfer : transfers) {
                BackendSelector selector = mock(BackendSelector.class);
                NetworkTransport transport = mock(NetworkTransport.class);
                ProxySender sender = mock(ProxySender.class);
                AtomicBoolean claimed = new AtomicBoolean(false);
                boolean disconnectAfterClaim = transfer == null;
                when(sender.isConnected(any())).thenAnswer(inv -> !(disconnectAfterClaim && claimed.get()));
                when(transport.readSnapshot()).thenReturn(CompletableFuture.completedFuture(
                        new NetworkSnapshot(1L, Collections.emptyMap())));
                when(selector.choose(any(), any())).thenReturn(Optional.of("srv-1"));
                ReservationToken token = new ReservationToken(tokenId, "srv-1", UUID.randomUUID(),
                        System.currentTimeMillis() + 10000, ReservationToken.State.CLAIMED);
                when(transport.claim(any(), any(), any(), any())).thenAnswer(inv -> {
                    claimed.set(true);
                    return CompletableFuture.completedFuture(token);
                });
                when(transport.release(any(), any())).thenThrow(new RuntimeException("release down"));
                if (transfer != null) {
                    @SuppressWarnings("unchecked")
                    CompletableFuture<TransferOutcome> t = (CompletableFuture<TransferOutcome>) transfer;
                    when(sender.sendTo(any(), any(), any())).thenReturn(t);
                }
                DefaultRtpDispatcher d = new DefaultRtpDispatcher(selector, transport, sender, Runnable::run);
                RtpRequest req = new RtpRequest(UUID.randomUUID(), TriggerType.COMMAND, Optional.empty(),
                        Optional.empty(), Optional.empty(), UUID.randomUUID());
                assertInstanceOf(DispatchOutcome.Failed.class, d.dispatch(req).get());
            }
        } finally {
            log.removeHandler(capture);
            log.setLevel(prevLevel);
        }
        List<String> tokenLines = lines.stream().filter(l -> l.contains("tok-0123")).toList();
        assertTrue(tokenLines.size() >= 4, "expected token-bearing log lines, got: " + lines);
        for (String line : lines) {
            assertFalse(line.contains(tokenId), "full token id logged: " + line);
        }
    }

    @Test
    void statusSink_throwingException_doesNotAbortPipeline() throws Exception {
        BackendSelector selector = mock(BackendSelector.class);
        NetworkTransport transport = mock(NetworkTransport.class);
        ProxySender sender = mock(ProxySender.class);
        when(sender.isConnected(any())).thenReturn(true);

        StatusSink crashingSink = (playerId, state, reason) -> {
            throw new RuntimeException("boom");
        };

        NetworkSnapshot snap = new NetworkSnapshot(1L, Collections.emptyMap());
        when(transport.readSnapshot()).thenReturn(CompletableFuture.completedFuture(snap));
        when(selector.choose(any(), any())).thenReturn(Optional.of("srv-1"));

        ReservationToken token = new ReservationToken("tok-1", "srv-1", UUID.randomUUID(), System.currentTimeMillis() + 10000, ReservationToken.State.CLAIMED);
        when(transport.claim(any(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(token));
        when(sender.sendTo(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(TransferOutcome.SUCCESS));

        DefaultRtpDispatcher d = new DefaultRtpDispatcher(selector, transport, sender, Runnable::run,
                Duration.ofSeconds(30), crashingSink);
        RtpRequest req = new RtpRequest(UUID.randomUUID(), TriggerType.COMMAND, Optional.empty(), Optional.empty(), Optional.empty(), UUID.randomUUID());

        DispatchOutcome outcome = d.dispatch(req).get();
        assertInstanceOf(DispatchOutcome.Routed.class, outcome);
    }
}
