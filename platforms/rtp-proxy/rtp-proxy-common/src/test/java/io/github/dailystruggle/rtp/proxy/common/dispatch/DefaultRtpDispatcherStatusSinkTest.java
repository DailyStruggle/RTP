package io.github.dailystruggle.rtp.proxy.common.dispatch;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendSelector;
import io.github.dailystruggle.rtp.proxy.common.spi.DispatchOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.MessageKey;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueState;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxyHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxySender;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpRequest;
import io.github.dailystruggle.rtp.proxy.common.spi.Subscription;
import io.github.dailystruggle.rtp.proxy.common.spi.TransferOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.TriggerType;
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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice D row D6 + REQ-RTP-S-004 guard for the {@link StatusSink} integration
 * in {@link DefaultRtpDispatcher}. Verifies that every terminal outcome path
 * fires exactly one {@code StatusSink.emit(...)} with the matching
 * {@link QueueState} so the cross-server queue can push the player's queue
 * state without coupling the dispatcher to the
 * {@code NetworkRequestQueue} SPI.
 */
final class DefaultRtpDispatcherStatusSinkTest {

    /** Captures sink invocations for assertion. */
    private static final class CapturingSink implements StatusSink {
        record Event(UUID playerId, QueueState state, Optional<String> reason) { }
        final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();
        @Override
        public void emit(UUID playerId, QueueState state, Optional<String> reason) {
            events.add(new Event(playerId, state, reason));
        }
    }

    private static BackendHeartbeat backend(String id) {
        return new BackendHeartbeat(id, 1, PluginState.READY, true,
                System.currentTimeMillis(),
                30.0, 0, 100,
                64L * 1024 * 1024, 512L * 1024 * 1024,
                0, List.of(), List.of(), false);
    }

    private static RtpRequest request(UUID p) {
        return new RtpRequest(p, TriggerType.COMMAND,
                Optional.empty(), Optional.empty(), Optional.empty(),
                UUID.randomUUID());
    }

    private static DefaultRtpDispatcher dispatcher(BackendSelector picker,
                                                   FakeTransport t, FakeSender s,
                                                   StatusSink sink) {
        return new DefaultRtpDispatcher(picker, t, s, Runnable::run,
                DefaultRtpDispatcher.DEFAULT_RESERVATION_TTL, sink);
    }

    @Test
    void routed_path_emits_RESERVED_then_COMPLETED() throws Exception {
        UUID p = UUID.randomUUID();
        CapturingSink sink = new CapturingSink();
        DispatchOutcome outcome = dispatcher(
                (req, snap) -> Optional.of("b1"),
                new FakeTransport(List.of(backend("b1"))),
                new FakeSender(p), sink).dispatch(request(p)).get();

        assertInstanceOf(DispatchOutcome.Routed.class, outcome);
        List<CapturingSink.Event> ev = List.copyOf(sink.events);
        assertEquals(2, ev.size(), "expected RESERVED then COMPLETED, got: " + ev);
        assertEquals(QueueState.RESERVED, ev.get(0).state());
        assertEquals(Optional.of("b1"), ev.get(0).reason(),
                "RESERVED emit carries the picked serverId as reason");
        assertEquals(QueueState.COMPLETED, ev.get(1).state());
        assertEquals(Optional.empty(), ev.get(1).reason());
        assertEquals(p, ev.get(0).playerId());
    }

    @Test
    void no_eligible_backend_emits_FAILED_with_NO_BACKEND_key() throws Exception {
        UUID p = UUID.randomUUID();
        CapturingSink sink = new CapturingSink();
        DispatchOutcome outcome = dispatcher(
                (req, snap) -> Optional.empty(),
                new FakeTransport(List.of(backend("b1"))),
                new FakeSender(p), sink).dispatch(request(p)).get();

        assertInstanceOf(DispatchOutcome.Failed.class, outcome);
        List<CapturingSink.Event> ev = List.copyOf(sink.events);
        assertEquals(1, ev.size());
        assertEquals(QueueState.FAILED, ev.get(0).state());
        assertEquals(Optional.of(DefaultRtpDispatcher.MSG_NO_BACKEND.key()), ev.get(0).reason());
    }

    @Test
    void null_claim_emits_FAILED_with_CLAIM_FAILED_key() throws Exception {
        UUID p = UUID.randomUUID();
        CapturingSink sink = new CapturingSink();
        FakeTransport t = new FakeTransport(List.of(backend("b1")));
        t.claimOverride = (s, pl, ttl) -> CompletableFuture.completedFuture(null);

        DispatchOutcome outcome = dispatcher(
                (req, snap) -> Optional.of("b1"),
                t, new FakeSender(p), sink).dispatch(request(p)).get();

        assertInstanceOf(DispatchOutcome.Failed.class, outcome);
        List<CapturingSink.Event> ev = List.copyOf(sink.events);
        assertEquals(1, ev.size());
        assertEquals(QueueState.FAILED, ev.get(0).state());
        assertEquals(Optional.of(DefaultRtpDispatcher.MSG_CLAIM_FAILED.key()), ev.get(0).reason());
    }

    @Test
    void disconnected_player_pre_snapshot_emits_CANCELLED_with_PLAYER_GONE_key() throws Exception {
        UUID p = UUID.randomUUID();
        CapturingSink sink = new CapturingSink();
        FakeSender s = new FakeSender(p);
        s.connected = false;
        DispatchOutcome outcome = dispatcher(
                (req, snap) -> Optional.of("b1"),
                new FakeTransport(List.of(backend("b1"))),
                s, sink).dispatch(request(p)).get();

        assertInstanceOf(DispatchOutcome.Failed.class, outcome);
        List<CapturingSink.Event> ev = List.copyOf(sink.events);
        assertEquals(1, ev.size());
        assertEquals(QueueState.CANCELLED, ev.get(0).state());
        assertEquals(Optional.of(DefaultRtpDispatcher.MSG_PLAYER_GONE.key()), ev.get(0).reason());
    }

    @Test
    void transfer_failure_emits_RESERVED_then_FAILED() throws Exception {
        UUID p = UUID.randomUUID();
        CapturingSink sink = new CapturingSink();
        FakeSender s = new FakeSender(p);
        s.transferResult = TransferOutcome.BACKEND_REFUSED;

        DispatchOutcome outcome = dispatcher(
                (req, snap) -> Optional.of("b1"),
                new FakeTransport(List.of(backend("b1"))),
                s, sink).dispatch(request(p)).get();

        assertInstanceOf(DispatchOutcome.Failed.class, outcome);
        List<CapturingSink.Event> ev = List.copyOf(sink.events);
        assertEquals(2, ev.size(), "expected RESERVED then FAILED, got: " + ev);
        assertEquals(QueueState.RESERVED, ev.get(0).state());
        assertEquals(QueueState.FAILED, ev.get(1).state());
        assertEquals(Optional.of(DefaultRtpDispatcher.MSG_TRANSFER_FAILED.key()), ev.get(1).reason());
    }

    @Test
    void transfer_player_disconnect_during_send_emits_CANCELLED() throws Exception {
        UUID p = UUID.randomUUID();
        CapturingSink sink = new CapturingSink();
        FakeSender s = new FakeSender(p);
        s.transferResult = TransferOutcome.PLAYER_DISCONNECTED;

        DispatchOutcome outcome = dispatcher(
                (req, snap) -> Optional.of("b1"),
                new FakeTransport(List.of(backend("b1"))),
                s, sink).dispatch(request(p)).get();

        assertInstanceOf(DispatchOutcome.Failed.class, outcome);
        List<CapturingSink.Event> ev = List.copyOf(sink.events);
        assertEquals(2, ev.size());
        assertEquals(QueueState.RESERVED, ev.get(0).state());
        assertEquals(QueueState.CANCELLED, ev.get(1).state(),
                "PLAYER_DISCONNECTED transfer outcome must map to CANCELLED, not FAILED");
    }

    @Test
    void transport_exception_emits_FAILED_with_INTERNAL_key() throws Exception {
        UUID p = UUID.randomUUID();
        CapturingSink sink = new CapturingSink();
        FakeTransport t = new FakeTransport(List.of(backend("b1")));
        t.claimOverride = (s, pl, ttl) -> CompletableFuture.failedFuture(new RuntimeException("redis down"));

        DispatchOutcome outcome = dispatcher(
                (req, snap) -> Optional.of("b1"),
                t, new FakeSender(p), sink).dispatch(request(p)).get();

        assertInstanceOf(DispatchOutcome.Failed.class, outcome);
        List<CapturingSink.Event> ev = List.copyOf(sink.events);
        // exceptionally branch fires MSG_INTERNAL FAILED; no RESERVED emitted
        // because claim future failed before the thenApply could run.
        assertTrue(ev.stream().anyMatch(e -> e.state() == QueueState.FAILED
                        && e.reason().equals(Optional.of(DefaultRtpDispatcher.MSG_INTERNAL.key()))),
                "expected one FAILED/INTERNAL emit, got: " + ev);
    }

    @Test
    void sink_throwing_does_not_swallow_dispatcher_outcome() throws Exception {
        UUID p = UUID.randomUUID();
        StatusSink throwingSink = (pl, st, r) -> { throw new IllegalStateException("boom"); };
        DispatchOutcome outcome = dispatcher(
                (req, snap) -> Optional.of("b1"),
                new FakeTransport(List.of(backend("b1"))),
                new FakeSender(p), throwingSink).dispatch(request(p)).get();

        // S-004 contract: sink failure logged, dispatch outcome still delivered.
        assertInstanceOf(DispatchOutcome.Routed.class, outcome);
    }

    @Test
    void default_constructors_use_NO_OP_sink_and_stay_backward_compatible() throws Exception {
        UUID p = UUID.randomUUID();
        // 4-arg constructor must not throw and must dispatch normally without
        // any caller-supplied sink (pre-L6 compat).
        DispatchOutcome outcome4 = new DefaultRtpDispatcher(
                (req, snap) -> Optional.of("b1"),
                new FakeTransport(List.of(backend("b1"))),
                new FakeSender(p), Runnable::run).dispatch(request(p)).get();
        assertInstanceOf(DispatchOutcome.Routed.class, outcome4);

        // 5-arg constructor likewise.
        DispatchOutcome outcome5 = new DefaultRtpDispatcher(
                (req, snap) -> Optional.of("b1"),
                new FakeTransport(List.of(backend("b1"))),
                new FakeSender(p), Runnable::run,
                Duration.ofSeconds(15)).dispatch(request(p)).get();
        assertInstanceOf(DispatchOutcome.Routed.class, outcome5);
    }

    // ---- fakes (parallel to DefaultRtpDispatcherTest; kept local to avoid
    // making the original test's private fakes package-visible) ----

    private record SendToCall(UUID playerId, String serverId, ReservationToken token) { }

    @FunctionalInterface
    interface ClaimFn {
        CompletableFuture<ReservationToken> claim(String serverId, UUID playerId, Duration ttl);
    }

    private static final class FakeTransport implements NetworkTransport {
        final NetworkSnapshot snapshot;
        volatile ClaimFn claimOverride;

        FakeTransport(List<BackendHeartbeat> backends) {
            LinkedHashMap<String, BackendHeartbeat> m = new LinkedHashMap<>();
            for (BackendHeartbeat b : backends) m.put(b.serverId(), b);
            this.snapshot = new NetworkSnapshot(Instant.now().toEpochMilli(), m);
        }

        @Override
        public CompletableFuture<NetworkSnapshot> readSnapshot() {
            return CompletableFuture.completedFuture(snapshot);
        }

        @Override
        public CompletableFuture<ReservationToken> claim(String serverId, UUID playerId, Duration ttl) {
            if (claimOverride != null) return claimOverride.claim(serverId, playerId, ttl);
            ReservationToken t = new ReservationToken(UUID.randomUUID().toString(), serverId,
                    playerId, System.currentTimeMillis() + ttl.toMillis(),
                    ReservationToken.State.CLAIMED);
            assertNotNull(t);
            return CompletableFuture.completedFuture(t);
        }

        @Override
        public CompletableFuture<Void> release(String tokenId, ReleaseReason reason) {
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
        volatile TransferOutcome transferResult = TransferOutcome.SUCCESS;
        final ConcurrentLinkedQueue<SendToCall> sendToCalls = new ConcurrentLinkedQueue<>();

        FakeSender(UUID self) { this.self = self; }

        @Override
        public CompletableFuture<TransferOutcome> sendTo(UUID playerId, String serverId, ReservationToken token) {
            sendToCalls.add(new SendToCall(playerId, serverId, token));
            return CompletableFuture.completedFuture(transferResult);
        }

        @Override
        public boolean isConnected(UUID playerId) { return connected && self.equals(playerId); }

        @Override
        public void sendMessage(UUID playerId, MessageKey key, Map<String, String> placeholders) { }
    }
}
