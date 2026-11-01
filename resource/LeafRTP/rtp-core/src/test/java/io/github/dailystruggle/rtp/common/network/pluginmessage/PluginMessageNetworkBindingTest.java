package io.github.dailystruggle.rtp.common.network.pluginmessage;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.Subscription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * Unit coverage for the tier-1 plugin-message transport (cross-server RTP).
 * Exercises heartbeat gossip -&gt; snapshot, TTL staleness, and the
 * best-effort/no-op reservation contract over an in-JVM loopback bridge.
 */
class PluginMessageNetworkBindingTest {

    /** In-JVM loopback bus: broadcasts fan out to every registered inbound sink. */
    private static final class FakeNetworkBridge implements NetworkBridge {
        final List<Consumer<byte[]>> sinks = new CopyOnWriteArrayList<>();
        final List<byte[]> broadcasts = new ArrayList<>();
        boolean available = true;
        UUID carrier = UUID.randomUUID();
        FakeNetworkBridge peer; // optional sibling bus to receive broadcasts

        @Override public boolean isAvailable() { return available; }
        @Override public Optional<UUID> anyOnlinePlayer() { return Optional.ofNullable(carrier); }
        @Override public void broadcastHeartbeat(byte[] payload) {
            broadcasts.add(payload);
            if (carrier == null) throw new IllegalStateException("no carrier player online");
            FakeNetworkBridge target = peer != null ? peer : this;
            for (Consumer<byte[]> s : target.sinks) s.accept(payload);
        }
        @Override public void connect(UUID player, String targetServerId) { }
        @Override public void registerInbound(Consumer<byte[]> heartbeatSink) { sinks.add(heartbeatSink); }
        @Override public ProxyProbe passiveProbe() { return ProxyProbe.ARMED; }
    }

    private static BackendHeartbeat hb(String serverId, long now) {
        return new BackendHeartbeat(serverId, 1, PluginState.READY, true, now,
                10.0, 0, 100, 0L, 0L, 1,
                List.of("regionA"), List.of("world"), false);
    }

    @Test
    @DisplayName("publish gossips a heartbeat that a peer observes in its snapshot")
    void publishGossipRoundTrip() throws Exception {
        FakeNetworkBridge busA = new FakeNetworkBridge();
        FakeNetworkBridge busB = new FakeNetworkBridge();
        busA.peer = busB; // A broadcasts -> B receives

        AtomicLong clock = new AtomicLong(1000L);
        PluginMessageNetworkBinding a = new PluginMessageNetworkBinding(busA, 1500L, clock::get);
        PluginMessageNetworkBinding b = new PluginMessageNetworkBinding(busB, 1500L, clock::get);

        a.publishBackendHeartbeat(hb("backend-a", clock.get())).get();

        NetworkSnapshot snap = b.readSnapshot().get();
        assertTrue(snap.backend("backend-a").isPresent(), "peer should see backend-a");
        assertEquals(List.of("regionA"), snap.backend("backend-a").get().regionsAvailable());
        a.close();
        b.close();
    }

    @Test
    @DisplayName("subscriber receives inbound heartbeats")
    void subscriberReceivesHeartbeats() throws Exception {
        FakeNetworkBridge busA = new FakeNetworkBridge();
        FakeNetworkBridge busB = new FakeNetworkBridge();
        busA.peer = busB;
        AtomicLong clock = new AtomicLong(0L);
        PluginMessageNetworkBinding a = new PluginMessageNetworkBinding(busA, 1500L, clock::get);
        PluginMessageNetworkBinding b = new PluginMessageNetworkBinding(busB, 1500L, clock::get);
        List<String> seen = new CopyOnWriteArrayList<>();
        Subscription sub = b.subscribeBackendHeartbeats(r -> seen.add(r.serverId()));
        a.publishBackendHeartbeat(hb("backend-a", 0L)).get();
        assertEquals(List.of("backend-a"), seen);
        sub.close();
        assertTrue(sub.isClosed());
        a.close();
        b.close();
    }

    @Test
    @DisplayName("stale peers age out of the snapshot past staleTimeoutMillis")
    void staleEntriesExpire() throws Exception {
        FakeNetworkBridge bus = new FakeNetworkBridge();
        AtomicLong clock = new AtomicLong(0L);
        PluginMessageNetworkBinding binding = new PluginMessageNetworkBinding(bus, 1500L, clock::get);
        binding.publishBackendHeartbeat(hb("backend-a", 0L)).get(); // loopback to self
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
        FakeNetworkBridge bus = new FakeNetworkBridge();
        bus.carrier = null; // no online player -> broadcast throws inside the bridge
        PluginMessageNetworkBinding binding = new PluginMessageNetworkBinding(bus);
        // Must complete normally (the binding swallows-with-FINE, never propagates).
        binding.publishBackendHeartbeat(hb("backend-a", 0L)).get();
        assertEquals(0, binding.livePeerCount());
        binding.close();
    }

    @Test
    @DisplayName("reservation methods are best-effort no-ops and never mint a token")
    void reservationMethodsAreNoOps() throws Exception {
        FakeNetworkBridge bus = new FakeNetworkBridge();
        PluginMessageNetworkBinding binding = new PluginMessageNetworkBinding(bus);
        // release is an idempotent no-op
        binding.release("anything", ReleaseReason.CONSUMED).get();
        // findReservation / reapExpired / listActiveForServer default to empties
        assertTrue(binding.findReservation(UUID.randomUUID()).get().isEmpty());
        assertTrue(binding.reapExpired(java.time.Instant.now()).get().isEmpty());
        assertTrue(binding.listActiveForServer("backend-a").get().isEmpty());
        // claim never mints a token (completes exceptionally)
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> binding.claim("backend-a", UUID.randomUUID(), Duration.ofSeconds(30)).get());
        assertTrue(ex.getCause() instanceof UnsupportedOperationException);
        binding.close();
    }
}
