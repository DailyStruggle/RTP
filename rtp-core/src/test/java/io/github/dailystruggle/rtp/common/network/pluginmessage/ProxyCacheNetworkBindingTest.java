package io.github.dailystruggle.rtp.common.network.pluginmessage;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.Subscription;
import io.github.dailystruggle.rtp.proxy.common.transport.codec.BackendHeartbeatCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the proxy-cache tier transport
 * (PROPOSAL-proxy-as-availability-store). The fake bridge stands in for the
 * proxy companion: {@code pushHeartbeatToProxy} stores the row in a shared
 * cache, and {@code requestSnapshot} replays every cached row back to the
 * caller's inbound sink (exactly what the Velocity companion will do).
 */
class ProxyCacheNetworkBindingTest {

    /** In-JVM proxy-cache double shared by every binding pointed at it. */
    private static final class FakeProxyBridge implements NetworkBridge {
        final Map<String, byte[]> cache; // serverId -> encoded heartbeat
        final List<Consumer<byte[]>> sinks = new CopyOnWriteArrayList<>();
        boolean available = true;
        UUID carrier = UUID.randomUUID();

        FakeProxyBridge(Map<String, byte[]> sharedCache) {
            this.cache = sharedCache;
        }

        @Override public boolean isAvailable() { return available; }
        @Override public Optional<UUID> anyOnlinePlayer() { return Optional.ofNullable(carrier); }
        @Override public void broadcastHeartbeat(byte[] payload) { /* unused on this tier */ }
        @Override public void connect(UUID player, String targetServerId) { }
        @Override public void registerInbound(Consumer<byte[]> heartbeatSink) { sinks.add(heartbeatSink); }
        @Override public ProxyProbe passiveProbe() { return ProxyProbe.ARMED; }

        @Override public void pushHeartbeatToProxy(byte[] payload) {
            if (carrier == null) throw new IllegalStateException("no carrier player online");
            BackendHeartbeat hb = BackendHeartbeatCodec.decode(new String(payload, StandardCharsets.UTF_8));
            cache.put(hb.serverId(), payload);
        }

        @Override public void requestSnapshot() {
            if (carrier == null) throw new IllegalStateException("no carrier player online");
            // Companion replays each cached row to this binding's inbound sink.
            for (byte[] payload : cache.values()) {
                for (Consumer<byte[]> s : sinks) s.accept(payload);
            }
        }
    }

    private static BackendHeartbeat hb(String serverId, long now) {
        return new BackendHeartbeat(serverId, 1, PluginState.READY, true, now,
                10.0, 0, 100, 0L, 0L, 1,
                List.of("regionA"), List.of("world"), false);
    }

    @Test
    @DisplayName("a lobby's snapshot request observes another backend's pushed heartbeat via the proxy cache")
    void pushThenSnapshotRoundTrip() throws Exception {
        Map<String, byte[]> proxyCache = new LinkedHashMap<>();
        FakeProxyBridge backendBus = new FakeProxyBridge(proxyCache);
        FakeProxyBridge lobbyBus = new FakeProxyBridge(proxyCache);

        AtomicLong clock = new AtomicLong(1000L);
        ProxyCacheNetworkBinding backend = new ProxyCacheNetworkBinding(backendBus, 5000L, clock::get);
        ProxyCacheNetworkBinding lobby = new ProxyCacheNetworkBinding(lobbyBus, 5000L, clock::get);

        // Backend pushes its heartbeat (and refreshes its own view).
        backend.publishBackendHeartbeat(hb("backend-a", clock.get())).get();

        // Lobby pushes its own + refreshes -> sees backend-a from the cache,
        // even though backend-a's player is not "on" the lobby.
        lobby.publishBackendHeartbeat(hb("lobby-a", clock.get())).get();

        NetworkSnapshot snap = lobby.readSnapshot().get();
        assertTrue(snap.backend("backend-a").isPresent(), "lobby should see backend-a from the proxy cache");
        assertEquals(List.of("regionA"), snap.backend("backend-a").get().regionsAvailable());
        backend.close();
        lobby.close();
    }

    @Test
    @DisplayName("subscriber receives cached heartbeats replayed by the proxy")
    void subscriberReceivesReplayedHeartbeats() throws Exception {
        Map<String, byte[]> proxyCache = new LinkedHashMap<>();
        FakeProxyBridge backendBus = new FakeProxyBridge(proxyCache);
        FakeProxyBridge lobbyBus = new FakeProxyBridge(proxyCache);
        AtomicLong clock = new AtomicLong(0L);
        ProxyCacheNetworkBinding backend = new ProxyCacheNetworkBinding(backendBus, 5000L, clock::get);
        ProxyCacheNetworkBinding lobby = new ProxyCacheNetworkBinding(lobbyBus, 5000L, clock::get);

        backend.publishBackendHeartbeat(hb("backend-a", 0L)).get(); // populate cache

        List<String> seen = new CopyOnWriteArrayList<>();
        Subscription sub = lobby.subscribeBackendHeartbeats(r -> seen.add(r.serverId()));
        lobby.publishBackendHeartbeat(hb("lobby-a", 0L)).get(); // triggers requestSnapshot
        assertTrue(seen.contains("backend-a"), "subscriber should observe the replayed backend-a heartbeat");
        sub.close();
        assertTrue(sub.isClosed());
        backend.close();
        lobby.close();
    }

    @Test
    @DisplayName("stale cached peers age out of the snapshot past staleTimeoutMillis")
    void staleEntriesExpire() throws Exception {
        Map<String, byte[]> proxyCache = new LinkedHashMap<>();
        FakeProxyBridge bus = new FakeProxyBridge(proxyCache);
        AtomicLong clock = new AtomicLong(0L);
        ProxyCacheNetworkBinding binding = new ProxyCacheNetworkBinding(bus, 1500L, clock::get);
        binding.publishBackendHeartbeat(hb("backend-a", 0L)).get(); // push + self snapshot
        assertEquals(1, binding.livePeerCount());
        clock.set(1501L); // advance past TTL
        NetworkSnapshot snap = binding.readSnapshot().get();
        assertFalse(snap.backend("backend-a").isPresent(), "stale peer should be filtered");
        assertEquals(0, binding.livePeerCount());
        binding.close();
    }

    @Test
    @DisplayName("carrier-absent publish is a silent skip, not a thrown error")
    void carrierAbsentPublishIsSilentSkip() throws Exception {
        FakeProxyBridge bus = new FakeProxyBridge(new LinkedHashMap<>());
        bus.carrier = null; // no online player -> push throws inside the bridge
        ProxyCacheNetworkBinding binding = new ProxyCacheNetworkBinding(bus);
        // Must complete normally (the binding swallows-with-FINE, never propagates).
        binding.publishBackendHeartbeat(hb("backend-a", 0L)).get();
        assertEquals(0, binding.livePeerCount());
        binding.close();
    }

    @Test
    @DisplayName("reservation methods are best-effort no-ops and never mint a token")
    void reservationMethodsAreNoOps() throws Exception {
        FakeProxyBridge bus = new FakeProxyBridge(new LinkedHashMap<>());
        ProxyCacheNetworkBinding binding = new ProxyCacheNetworkBinding(bus);
        binding.release("anything", ReleaseReason.CONSUMED).get();
        assertTrue(binding.findReservation(UUID.randomUUID()).get().isEmpty());
        assertTrue(binding.reapExpired(java.time.Instant.now()).get().isEmpty());
        assertTrue(binding.listActiveForServer("backend-a").get().isEmpty());
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> binding.claim("backend-a", UUID.randomUUID(), Duration.ofSeconds(30)).get());
        assertTrue(ex.getCause() instanceof UnsupportedOperationException);
        binding.close();
    }
}
