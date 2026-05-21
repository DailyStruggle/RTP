package io.github.dailystruggle.rtp.proxy.common.dispatch;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendSelector;
import io.github.dailystruggle.rtp.proxy.common.spi.DispatchOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.MessageKey;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-PROXY-COMMON-001 + REQ-RTP-S-004 unit guards for
 * {@link DefaultRtpDispatcher}. Drives the dispatcher with a synthetic
 * snapshot + fake sender; asserts the pipeline produces the expected
 * {@link DispatchOutcome} for each terminal case.
 */
class DefaultRtpDispatcherTest {

    private static BackendHeartbeat backend(String serverId) {
        return new BackendHeartbeat(
                serverId, 1, PluginState.READY, true,
                System.currentTimeMillis(),
                30.0,  // mspt
                0, 100,
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
    void routes_to_an_eligible_peer_and_signs_token() throws Exception {
        UUID player = UUID.randomUUID();
        FakeTransport transport = new FakeTransport(List.of(backend("b1"), backend("b2")));
        FakeSender sender = new FakeSender(player);

        BackendSelector picker = (req, snap) -> snap.backends().keySet().stream().findFirst();
        DefaultRtpDispatcher d = new DefaultRtpDispatcher(picker, transport, sender, Runnable::run);

        DispatchOutcome outcome = d.dispatch(request(player)).get();

        assertInstanceOf(DispatchOutcome.Routed.class, outcome);
        DispatchOutcome.Routed routed = (DispatchOutcome.Routed) outcome;
        // Selector picks the first key of the snapshot; FakeTransport may store
        // backends in either order via NetworkSnapshot's own internal map, so
        // accept either b1 or b2 as long as it is consistent across sender + token.
        assertTrue(List.of("b1", "b2").contains(routed.serverId()),
                "routed.serverId must be one of the snapshot peers, was: " + routed.serverId());
        assertNotNull(routed.tokenId());

        // sender.sendTo was called with the claimed token + correct server.
        assertEquals(1, sender.sendToCalls.size());
        SendToCall call = sender.sendToCalls.peek();
        assertEquals(routed.serverId(), call.serverId,
                "sendTo target must equal the routed serverId");
        assertEquals(player, call.playerId);
        assertEquals(routed.tokenId(), call.token.tokenId());

        // sender.sendMessage was called with the ROUTED key.
        assertTrue(sender.messages.stream()
                .anyMatch(m -> DefaultRtpDispatcher.MSG_ROUTED.key().equals(m.key.key())));
    }

    @Test
    void no_eligible_backend_returns_NO_BACKEND_and_messages_player() throws Exception {
        UUID player = UUID.randomUUID();
        FakeTransport transport = new FakeTransport(List.of(backend("b1")));
        FakeSender sender = new FakeSender(player);
        BackendSelector picker = (req, snap) -> Optional.empty();
        DefaultRtpDispatcher d = new DefaultRtpDispatcher(picker, transport, sender, Runnable::run);

        DispatchOutcome outcome = d.dispatch(request(player)).get();

        assertInstanceOf(DispatchOutcome.Failed.class, outcome);
        DispatchOutcome.Failed failed = (DispatchOutcome.Failed) outcome;
        assertEquals(DispatchOutcome.Failed.Reason.NO_BACKEND, failed.reason());
        assertEquals(DefaultRtpDispatcher.MSG_NO_BACKEND.key(), failed.messageKey());
        assertEquals(0, sender.sendToCalls.size(),
                "transport.claim must not run when selector returns no candidate");
    }

    @Test
    void claim_returning_null_token_yields_CLAIM_RACE() throws Exception {
        UUID player = UUID.randomUUID();
        FakeTransport transport = new FakeTransport(List.of(backend("b1")));
        transport.claimOverride = (s, p, ttl) -> CompletableFuture.completedFuture(null);
        FakeSender sender = new FakeSender(player);
        BackendSelector picker = (req, snap) -> Optional.of("b1");
        DefaultRtpDispatcher d = new DefaultRtpDispatcher(picker, transport, sender, Runnable::run);

        DispatchOutcome outcome = d.dispatch(request(player)).get();

        DispatchOutcome.Failed failed = assertInstanceOf(DispatchOutcome.Failed.class, outcome);
        assertEquals(DispatchOutcome.Failed.Reason.CLAIM_RACE, failed.reason());
        assertEquals(DefaultRtpDispatcher.MSG_CLAIM_FAILED.key(), failed.messageKey());
    }

    @Test
    void disconnected_player_short_circuits_to_PLAYER_GONE() throws Exception {
        UUID player = UUID.randomUUID();
        FakeTransport transport = new FakeTransport(List.of(backend("b1")));
        FakeSender sender = new FakeSender(player);
        sender.connected = false; // not online
        BackendSelector picker = (req, snap) -> Optional.of("b1");
        DefaultRtpDispatcher d = new DefaultRtpDispatcher(picker, transport, sender, Runnable::run);

        DispatchOutcome outcome = d.dispatch(request(player)).get();

        DispatchOutcome.Failed failed = assertInstanceOf(DispatchOutcome.Failed.class, outcome);
        assertEquals(DispatchOutcome.Failed.Reason.PLAYER_GONE, failed.reason());
        assertEquals(0, sender.sendToCalls.size());
        // No claim should have happened either.
        assertEquals(0, transport.claimCount.get());
    }

    @Test
    void transfer_failure_releases_token_and_reports_INTERNAL() throws Exception {
        UUID player = UUID.randomUUID();
        FakeTransport transport = new FakeTransport(List.of(backend("b1")));
        FakeSender sender = new FakeSender(player);
        sender.transferResult = TransferOutcome.BACKEND_REFUSED;
        BackendSelector picker = (req, snap) -> Optional.of("b1");
        DefaultRtpDispatcher d = new DefaultRtpDispatcher(picker, transport, sender, Runnable::run);

        DispatchOutcome outcome = d.dispatch(request(player)).get();

        DispatchOutcome.Failed failed = assertInstanceOf(DispatchOutcome.Failed.class, outcome);
        assertEquals(DispatchOutcome.Failed.Reason.INTERNAL, failed.reason());
        assertEquals(DefaultRtpDispatcher.MSG_TRANSFER_FAILED.key(), failed.messageKey());
        // Token released after failed transfer.
        assertEquals(1, transport.releaseCount.get(),
                "transport.release must be called exactly once after a failed transfer");
        assertEquals(ReleaseReason.BACKEND_REJECTED, transport.lastReleaseReason.get());
    }

    @Test
    void transport_exception_surfaces_as_INTERNAL_with_INTERNAL_message() throws Exception {
        UUID player = UUID.randomUUID();
        FakeTransport transport = new FakeTransport(List.of(backend("b1")));
        transport.claimOverride = (s, p, ttl) ->
                CompletableFuture.failedFuture(new RuntimeException("redis down"));
        FakeSender sender = new FakeSender(player);
        BackendSelector picker = (req, snap) -> Optional.of("b1");
        DefaultRtpDispatcher d = new DefaultRtpDispatcher(picker, transport, sender, Runnable::run);

        DispatchOutcome outcome = d.dispatch(request(player)).get();

        DispatchOutcome.Failed failed = assertInstanceOf(DispatchOutcome.Failed.class, outcome);
        assertEquals(DispatchOutcome.Failed.Reason.INTERNAL, failed.reason());
        assertEquals(DefaultRtpDispatcher.MSG_INTERNAL.key(), failed.messageKey());
    }

    // ---- fakes ----

    private record SendToCall(UUID playerId, String serverId, ReservationToken token) { }

    private record SentMessage(UUID playerId, MessageKey key, Map<String, String> placeholders) { }

    @FunctionalInterface
    interface ClaimFn {
        CompletableFuture<ReservationToken> claim(String serverId, UUID playerId, Duration ttl);
    }

    /** Minimal in-test transport: records calls, supports overrides. */
    private static final class FakeTransport implements NetworkTransport {
        final NetworkSnapshot snapshot;
        final ConcurrentHashMap<String, ReservationToken> tokens = new ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicInteger claimCount = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger releaseCount = new java.util.concurrent.atomic.AtomicInteger();
        final AtomicReference<ReleaseReason> lastReleaseReason = new AtomicReference<>();
        volatile ClaimFn claimOverride;

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
            claimCount.incrementAndGet();
            if (claimOverride != null) return claimOverride.claim(serverId, playerId, ttl);
            String tokenId = UUID.randomUUID().toString();
            ReservationToken t = new ReservationToken(tokenId, serverId, playerId,
                    System.currentTimeMillis() + ttl.toMillis(),
                    ReservationToken.State.CLAIMED);
            tokens.put(tokenId, t);
            return CompletableFuture.completedFuture(t);
        }

        @Override
        public CompletableFuture<Void> release(String tokenId, ReleaseReason reason) {
            releaseCount.incrementAndGet();
            lastReleaseReason.set(reason);
            tokens.remove(tokenId);
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

    /** Minimal in-test sender: captures sendTo + sendMessage calls. */
    private static final class FakeSender implements ProxySender {
        final UUID self;
        volatile boolean connected = true;
        volatile TransferOutcome transferResult = TransferOutcome.SUCCESS;
        final ConcurrentLinkedQueue<SendToCall> sendToCalls = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<SentMessage> messages = new ConcurrentLinkedQueue<>();

        FakeSender(UUID self) { this.self = self; }

        @Override
        public CompletableFuture<TransferOutcome> sendTo(UUID playerId, String serverId, ReservationToken token) {
            sendToCalls.add(new SendToCall(playerId, serverId, token));
            return CompletableFuture.completedFuture(transferResult);
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
