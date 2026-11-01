package io.github.dailystruggle.rtp.proxy.velocity;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.transport.codec.BackendHeartbeatCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link VelocityProxyAvailabilityCache}, the proxy-cache tier's
 * merge of operator-declared {@code server -> regions} (direction B) with live
 * backend heartbeat pushes. This is what populates cross-server
 * {@code /rtp region=} tab-completion on a player-empty network.
 */
@DisplayName("VelocityProxyAvailabilityCache config/live merge")
class VelocityProxyAvailabilityCacheTest {

    @Test
    @DisplayName("Configured servers surface as synthetic READY rows with zero live pushes")
    void configuredServersSurfaceWithoutPushes() {
        VelocityProxyAvailabilityCache cache = new VelocityProxyAvailabilityCache(
                Map.of("backend-a", List.of("default"),
                        "backend-b", List.of("default", "nether")),
                5000L, () -> 1000L);

        List<BackendHeartbeat> snap = cache.snapshot();
        assertEquals(2, snap.size(), "both configured servers must be present");
        BackendHeartbeat a = snap.stream().filter(h -> h.serverId().equals("backend-a")).findFirst().orElseThrow();
        assertEquals(PluginState.READY, a.pluginState());
        assertTrue(a.acceptingRequests());
        assertEquals(List.of("default"), a.regionsAvailable());
        BackendHeartbeat b = snap.stream().filter(h -> h.serverId().equals("backend-b")).findFirst().orElseThrow();
        assertEquals(List.of("default", "nether"), b.regionsAvailable());
    }

    @Test
    @DisplayName("Fresh live push supersedes the configured synthetic row for the same server")
    void liveSupersedesConfigured() {
        AtomicLong now = new AtomicLong(1000L);
        VelocityProxyAvailabilityCache cache = new VelocityProxyAvailabilityCache(
                Map.of("backend-a", List.of("default")), 5000L, now::get);

        cache.onPush(new BackendHeartbeat(
                "backend-a", 1, PluginState.READY, true, 1000L,
                0.0, 0, 0, 0L, 0L, 0, List.of("arena", "sky"), List.of()));

        List<BackendHeartbeat> snap = cache.snapshot();
        assertEquals(1, snap.size());
        assertEquals(List.of("arena", "sky"), snap.get(0).regionsAvailable(),
                "live regions must win over the configured declaration");
    }

    @Test
    @DisplayName("Stale live push falls back to the configured synthetic row")
    void staleLiveFallsBackToConfigured() {
        AtomicLong now = new AtomicLong(1000L);
        VelocityProxyAvailabilityCache cache = new VelocityProxyAvailabilityCache(
                Map.of("backend-a", List.of("default")), 5000L, now::get);

        cache.onPush(new BackendHeartbeat(
                "backend-a", 1, PluginState.READY, true, 1000L,
                0.0, 0, 0, 0L, 0L, 0, List.of("arena"), List.of()));
        now.set(1000L + 6000L); // advance past staleAfterMs

        List<BackendHeartbeat> snap = cache.snapshot();
        assertEquals(1, snap.size());
        assertEquals(List.of("default"), snap.get(0).regionsAvailable(),
                "stale live row must fall back to the configured declaration");
    }

    @Test
    @DisplayName("Live-only server (no config) appears while fresh")
    void liveOnlyServerAppears() {
        VelocityProxyAvailabilityCache cache = new VelocityProxyAvailabilityCache(
                Map.of(), 5000L, () -> 1000L);
        cache.onPush(new BackendHeartbeat(
                "backend-c", 1, PluginState.READY, true, 1000L,
                0.0, 0, 0, 0L, 0L, 0, List.of("default"), List.of()));
        assertEquals(1, cache.snapshot().size());
    }

    @Test
    @DisplayName("Topology servers (proxy's own registered list) surface with zero config and zero pushes")
    void topologyServersSurfaceWithoutConfigOrPushes() {
        VelocityProxyAvailabilityCache cache = new VelocityProxyAvailabilityCache(
                Map.of(),                                   // no operator config
                () -> List.of("backend-a", "backend-b", "backend-c"), // proxy's velocity.toml servers
                List.of("default"),
                5000L, () -> 1000L);

        List<BackendHeartbeat> snap = cache.snapshot();
        assertEquals(3, snap.size(), "every proxy-registered server must surface");
        BackendHeartbeat a = snap.stream().filter(h -> h.serverId().equals("backend-a")).findFirst().orElseThrow();
        assertEquals(PluginState.READY, a.pluginState());
        assertTrue(a.acceptingRequests());
        assertEquals(List.of("default"), a.regionsAvailable(), "topology rows assume the default region");
    }

    @Test
    @DisplayName("Operator config overrides the topology row's assumed default region")
    void configuredOverridesTopologyDefault() {
        VelocityProxyAvailabilityCache cache = new VelocityProxyAvailabilityCache(
                Map.of("backend-a", List.of("arena", "sky")),
                () -> List.of("backend-a", "backend-b"),
                List.of("default"),
                5000L, () -> 1000L);

        List<BackendHeartbeat> snap = cache.snapshot();
        assertEquals(2, snap.size());
        BackendHeartbeat a = snap.stream().filter(h -> h.serverId().equals("backend-a")).findFirst().orElseThrow();
        assertEquals(List.of("arena", "sky"), a.regionsAvailable(), "operator config wins over assumed default");
        BackendHeartbeat b = snap.stream().filter(h -> h.serverId().equals("backend-b")).findFirst().orElseThrow();
        assertEquals(List.of("default"), b.regionsAvailable(), "un-configured topology server keeps the default");
    }

    @Test
    @DisplayName("Fresh live push supersedes the topology-seeded default row")
    void liveSupersedesTopology() {
        VelocityProxyAvailabilityCache cache = new VelocityProxyAvailabilityCache(
                Map.of(),
                () -> List.of("backend-a"),
                List.of("default"),
                5000L, () -> 1000L);
        cache.onPush(new BackendHeartbeat(
                "backend-a", 1, PluginState.READY, true, 1000L,
                0.0, 0, 0, 0L, 0L, 0, List.of("real1", "real2"), List.of()));

        List<BackendHeartbeat> snap = cache.snapshot();
        assertEquals(1, snap.size());
        assertEquals(List.of("real1", "real2"), snap.get(0).regionsAvailable(),
                "a real push must supersede the proxy's assumed default");
    }

    @Test
    @DisplayName("onPushPayload decodes a codec payload and snapshotPayloads round-trips")
    void payloadRoundTrip() {
        VelocityProxyAvailabilityCache cache = new VelocityProxyAvailabilityCache(
                Map.of(), 5000L, () -> 1000L);
        BackendHeartbeat hb = new BackendHeartbeat(
                "backend-d", 1, PluginState.READY, true, 1000L,
                0.0, 0, 0, 0L, 0L, 0, List.of("default", "nether"), List.of());
        byte[] encoded = BackendHeartbeatCodec.encode(hb).getBytes(StandardCharsets.UTF_8);

        cache.onPushPayload(encoded);

        List<byte[]> out = cache.snapshotPayloads();
        assertEquals(1, out.size());
        BackendHeartbeat decoded = BackendHeartbeatCodec.decode(
                new String(out.get(0), StandardCharsets.UTF_8));
        assertEquals("backend-d", decoded.serverId());
        assertEquals(List.of("default", "nether"), decoded.regionsAvailable());
    }
}
