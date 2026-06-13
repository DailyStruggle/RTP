package io.github.dailystruggle.rtp.proxy.common.dispatch;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendSelector;
import io.github.dailystruggle.rtp.proxy.common.spi.DispatchOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.MessageKey;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueState;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxyHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxySender;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpRequest;
import io.github.dailystruggle.rtp.proxy.common.spi.Subscription;
import io.github.dailystruggle.rtp.proxy.common.spi.TransferOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.TriggerType;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkWaitlist;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-NET-015 unit guards for the waitlist rewiring of
 * {@link DefaultRtpDispatcher}: when {@link BackendSelector#choose} returns
 * empty, the dispatcher parks the envelope on the configured
 * {@link NetworkWaitlist} and surfaces
 * {@link DispatchOutcome.Queued} + {@link QueueState#WAITLISTED} instead of
 * the no-waitlist terminal {@link DispatchOutcome.Failed}.
 */
class DefaultRtpDispatcherWaitlistTest {

    private InMemoryNetworkWaitlist waitlist;

    @AfterEach
    void tearDown() {
        if (waitlist != null) waitlist.shutdown();
    }

    private static BackendHeartbeat backend(String serverId) {
        return new BackendHeartbeat(
                serverId, 1, PluginState.READY, true,
                System.currentTimeMillis(),
                30.0, 0, 100,
                64L * 1024 * 1024, 512L * 1024 * 1024,
                0,
                List.of(), List.of(),
                false);
    }

    private static RtpRequest request(UUID playerId) {
        return new RtpRequest(playerId, TriggerType.COMMAND,
                Optional.empty(), Optional.empty(), Optional.empty(),
                UUID.randomUUID());
    }

    @Test
    void no_eligible_backend_parks_on_waitlist_and_returns_Queued() throws Exception {
        waitlist = new InMemoryNetworkWaitlist();
        UUID player = UUID.randomUUID();
        FakeTransport transport = new FakeTransport(List.of(backend("b1")));
        FakeSender sender = new FakeSender(player);
        RecordingSink sink = new RecordingSink();
        BackendSelector picker = (req, snap) -> Optional.empty();

        DefaultRtpDispatcher d = new DefaultRtpDispatcher(picker, transport, sender,
                Runnable::run, Duration.ofSeconds(30), sink, waitlist, "proxy-A");

        DispatchOutcome outcome = d.dispatch(request(player)).get();

        assertInstanceOf(DispatchOutcome.Queued.class, outcome);
        assertEquals(1, waitlist.sizeNow(),
                "Envelope must be parked on the waitlist when no backend qualifies");
        // Sink saw WAITLISTED, NOT FAILED.
        assertTrue(sink.states.stream().anyMatch(s -> s == QueueState.WAITLISTED),
                "StatusSink must observe WAITLISTED on no-backend with waitlist configured");
        assertTrue(sink.states.stream().noneMatch(s -> s == QueueState.FAILED),
                "StatusSink must NOT observe FAILED on the happy waitlist path");
        // Player got the queued message, not no-backend.
        assertTrue(sender.messages.stream()
                        .anyMatch(m -> DefaultRtpDispatcher.MSG_QUEUED.key().equals(m.key.key())),
                "Player must receive the configurable rtp.network.queued message");
        // Transport.claim must NOT have run.
        assertEquals(0, transport.claimCount,
                "Claim must not run when the request is parked on the waitlist");
    }

    @Test
    void no_waitlist_configured_keeps_NO_BACKEND_path() throws Exception {
        UUID player = UUID.randomUUID();
        FakeTransport transport = new FakeTransport(List.of(backend("b1")));
        FakeSender sender = new FakeSender(player);
        BackendSelector picker = (req, snap) -> Optional.empty();

        // No-waitlist ctor.
        DefaultRtpDispatcher d = new DefaultRtpDispatcher(picker, transport, sender, Runnable::run);

        DispatchOutcome outcome = d.dispatch(request(player)).get();

        DispatchOutcome.Failed failed = assertInstanceOf(DispatchOutcome.Failed.class, outcome);
        assertEquals(DispatchOutcome.Failed.Reason.NO_BACKEND, failed.reason());
        assertEquals(DefaultRtpDispatcher.MSG_NO_BACKEND.key(), failed.messageKey());
    }

    @Test
    void duplicate_player_returns_Queued_without_double_enrol() throws Exception {
        waitlist = new InMemoryNetworkWaitlist();
        UUID player = UUID.randomUUID();
        FakeTransport transport = new FakeTransport(List.of(backend("b1")));
        FakeSender sender = new FakeSender(player);
        RecordingSink sink = new RecordingSink();
        BackendSelector picker = (req, snap) -> Optional.empty();

        DefaultRtpDispatcher d = new DefaultRtpDispatcher(picker, transport, sender,
                Runnable::run, Duration.ofSeconds(30), sink, waitlist, "proxy-A");

        DispatchOutcome first = d.dispatch(request(player)).get();
        DispatchOutcome second = d.dispatch(request(player)).get();

        assertInstanceOf(DispatchOutcome.Queued.class, first);
        // Second call: same UUID, different correlationId. Waitlist returns
        // REJECTED_DUPLICATE; dispatcher still surfaces Queued so the trigger
        // source converges on one pending request per UUID.
        assertInstanceOf(DispatchOutcome.Queued.class, second);
        assertEquals(1, waitlist.sizeNow(),
                "Duplicate enrol must not insert a second entry for the same UUID");
    }

    @Test
    void full_waitlist_falls_back_to_NO_BACKEND_with_S004_message() throws Exception {
        waitlist = new InMemoryNetworkWaitlist(1); // capacity 1
        // Pre-fill the single slot with an unrelated player.
        UUID other = UUID.randomUUID();
        waitlist.enrol(new NetworkWaitlist.WaitEnvelope(
                other, UUID.randomUUID(), Optional.empty(), Optional.empty(),
                "proxy-A", System.currentTimeMillis())).get();
        // Wait until the in-memory waitlist actually has the entry.
        long deadline = System.currentTimeMillis() + 1000;
        while (waitlist.sizeNow() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }

        UUID player = UUID.randomUUID();
        FakeTransport transport = new FakeTransport(List.of(backend("b1")));
        FakeSender sender = new FakeSender(player);
        RecordingSink sink = new RecordingSink();
        BackendSelector picker = (req, snap) -> Optional.empty();

        DefaultRtpDispatcher d = new DefaultRtpDispatcher(picker, transport, sender,
                Runnable::run, Duration.ofSeconds(30), sink, waitlist, "proxy-A");

        DispatchOutcome outcome = d.dispatch(request(player)).get();

        DispatchOutcome.Failed failed = assertInstanceOf(DispatchOutcome.Failed.class, outcome);
        assertEquals(DispatchOutcome.Failed.Reason.NO_BACKEND, failed.reason(),
                "Full waitlist must fall back to NO_BACKEND, never silently swallow");
        // Sink saw FAILED, not WAITLISTED, on the fallback path.
        assertTrue(sink.states.stream().anyMatch(s -> s == QueueState.FAILED),
                "StatusSink must observe FAILED on the full-waitlist fallback");
        // Player got the configurable NO_BACKEND message.
        assertTrue(sender.messages.stream()
                .anyMatch(m -> DefaultRtpDispatcher.MSG_NO_BACKEND.key().equals(m.key.key())));
    }

    // ---- fakes ----

    private static final class RecordingSink implements StatusSink {
        final ConcurrentLinkedQueue<QueueState> states = new ConcurrentLinkedQueue<>();

        @Override
        public void emit(UUID playerId, QueueState state, Optional<String> reason) {
            states.add(state);
        }
    }

    private record SendToCall(UUID playerId, String serverId, ReservationToken token) { }

    private record SentMessage(UUID playerId, MessageKey key, Map<String, String> placeholders) { }

    private static final class FakeTransport implements NetworkTransport {
        final NetworkSnapshot snapshot;
        int claimCount = 0;
        int releaseCount = 0;
        final AtomicReference<ReleaseReason> lastReleaseReason = new AtomicReference<>();

        FakeTransport(List<BackendHeartbeat> backends) {
            LinkedHashMap<String, BackendHeartbeat> map = new LinkedHashMap<>();
            for (BackendHeartbeat b : backends) map.put(b.serverId(), b);
            this.snapshot = new NetworkSnapshot(Instant.now().toEpochMilli(), map);
        }

        @Override
        public CompletableFuture<NetworkSnapshot> readSnapshot() {
            return CompletableFuture.completedFuture(snapshot);
        }

        @Override
        public CompletableFuture<ReservationToken> claim(String serverId, UUID playerId, Duration ttl) {
            claimCount++;
            String tokenId = UUID.randomUUID().toString();
            ReservationToken t = new ReservationToken(tokenId, serverId, playerId,
                    System.currentTimeMillis() + ttl.toMillis(),
                    ReservationToken.State.CLAIMED);
            return CompletableFuture.completedFuture(t);
        }

        @Override
        public CompletableFuture<Void> release(String tokenId, ReleaseReason reason) {
            releaseCount++;
            lastReleaseReason.set(reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> publishProxyHeartbeat(ProxyHeartbeat row) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public Subscription subscribeBackendHeartbeats(Consumer<BackendHeartbeat> sink) {
            return new Subscription() {
                @Override public void close() { }
                @Override public boolean isClosed() { return false; }
            };
        }

        @Override public void close() { }
    }

    private static final class FakeSender implements ProxySender {
        final UUID self;
        volatile boolean connected = true;
        final ConcurrentLinkedQueue<SendToCall> sendToCalls = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<SentMessage> messages = new ConcurrentLinkedQueue<>();

        FakeSender(UUID self) { this.self = self; }

        @Override
        public CompletableFuture<TransferOutcome> sendTo(UUID playerId, String serverId, ReservationToken token) {
            sendToCalls.add(new SendToCall(playerId, serverId, token));
            return CompletableFuture.completedFuture(TransferOutcome.SUCCESS);
        }

        @Override
        public boolean isConnected(UUID playerId) {
            return connected && self.equals(playerId);
        }

        @Override
        public void sendMessage(UUID playerId, MessageKey key, Map<String, String> placeholders) {
            messages.add(new SentMessage(playerId, key, placeholders));
        }
    }
}
