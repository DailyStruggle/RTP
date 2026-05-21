package io.github.dailystruggle.rtp.bukkit.network;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice C row C8 / C9: gate-by-gate coverage of {@link NetworkRouter} plus
 * the {@link NetworkRouter#parseRegionArg(String)} stub.
 */
class NetworkRouterTest {

    private static BackendHeartbeat hb(String id, Set<String> regions, Map<String, Integer> kept, boolean kill) {
        return new BackendHeartbeat(
                id, 1, BackendHeartbeat.PluginState.READY, true,
                System.currentTimeMillis(), 5.0, 0, 100, 0L, 1L, 0,
                List.of(), List.of(),
                kill, 0, 0, regions, kept);
    }

    private static NetworkSnapshot snap(BackendHeartbeat... rows) {
        java.util.LinkedHashMap<String, BackendHeartbeat> m = new java.util.LinkedHashMap<>();
        for (BackendHeartbeat r : rows) m.put(r.serverId(), r);
        return new NetworkSnapshot(System.currentTimeMillis(), m);
    }

    private static NetworkRouter routerWith(NetworkRouter.Mode mode,
                                            NetworkSnapshot s,
                                            int localKept,
                                            int qDepth,
                                            int qMax) {
        return new NetworkRouter(
                "local-1", mode,
                () -> s,
                () -> localKept,
                () -> qDepth,
                qMax,
                100, 100, // rps/burst high so we don't trip the token bucket
                System::currentTimeMillis);
    }

    @Test
    void mode_local_always_returns_Local() {
        BackendHeartbeat peer = hb("peer-1", Set.of("safe"), Map.of("safe", 5), false);
        NetworkRouter r = routerWith(NetworkRouter.Mode.LOCAL, snap(peer), 0, 0, 50);
        RoutingDecision d = r.route(UUID.randomUUID(), "safe");
        assertSame(RoutingDecision.Local.INSTANCE, d);
    }

    @Test
    void null_snapshot_returns_NetworkDisabled_LocalFallback() {
        NetworkRouter r = new NetworkRouter("local-1", NetworkRouter.Mode.CROSS_SERVER,
                () -> null, () -> 0, () -> 0, 50, 100, 100, System::currentTimeMillis);
        RoutingDecision d = r.route(UUID.randomUUID(), null);
        var fb = assertInstanceOf(RoutingDecision.LocalFallback.class, d);
        assertEquals(RoutingDecision.FallbackReason.NETWORK_DISABLED, fb.reason());
    }

    @Test
    void auto_with_local_able_serves_local() {
        BackendHeartbeat local = hb("local-1", Set.of("safe"), Map.of("safe", 0), false);
        BackendHeartbeat peer = hb("peer-1", Set.of("safe"), Map.of("safe", 3), false);
        NetworkRouter r = routerWith(NetworkRouter.Mode.AUTO, snap(local, peer), /*localKept=*/4, 0, 50);
        assertSame(RoutingDecision.Local.INSTANCE, r.route(UUID.randomUUID(), "safe"));
    }

    @Test
    void cross_server_with_live_peer_enrols() {
        BackendHeartbeat peer = hb("peer-1", Set.of("safe"), Map.of("safe", 3), false);
        NetworkRouter r = routerWith(NetworkRouter.Mode.CROSS_SERVER, snap(peer), 0, 0, 50);
        RoutingDecision d = r.route(UUID.randomUUID(), "safe");
        var cs = assertInstanceOf(RoutingDecision.CrossServer.class, d);
        assertEquals("safe", cs.regionKey().orElseThrow());
    }

    @Test
    void no_live_peer_returns_NoLivePeer_fallback() {
        // Only peer is kill-switched.
        BackendHeartbeat dead = hb("peer-dead", Set.of("safe"), Map.of("safe", 3), true);
        NetworkRouter r = routerWith(NetworkRouter.Mode.CROSS_SERVER, snap(dead), 0, 0, 50);
        var fb = assertInstanceOf(RoutingDecision.LocalFallback.class, r.route(UUID.randomUUID(), "safe"));
        assertEquals(RoutingDecision.FallbackReason.NO_LIVE_PEER, fb.reason());
    }

    @Test
    void region_unavailable_returns_RegionUnavailable_fallback() {
        BackendHeartbeat peer = hb("peer-1", Set.of("other"), Map.of("other", 3), false);
        NetworkRouter r = routerWith(NetworkRouter.Mode.CROSS_SERVER, snap(peer), 0, 0, 50);
        var fb = assertInstanceOf(RoutingDecision.LocalFallback.class, r.route(UUID.randomUUID(), "missing"));
        assertEquals(RoutingDecision.FallbackReason.REGION_UNAVAILABLE, fb.reason());
    }

    @Test
    void queue_full_returns_QueueFull_fallback() {
        BackendHeartbeat peer = hb("peer-1", Set.of("safe"), Map.of("safe", 3), false);
        NetworkRouter r = routerWith(NetworkRouter.Mode.CROSS_SERVER, snap(peer), 0, /*depth=*/50, /*max=*/50);
        var fb = assertInstanceOf(RoutingDecision.LocalFallback.class, r.route(UUID.randomUUID(), "safe"));
        assertEquals(RoutingDecision.FallbackReason.QUEUE_FULL, fb.reason());
    }

    @Test
    void token_bucket_exhaustion_returns_TokenBucketExhausted_fallback() {
        BackendHeartbeat peer = hb("peer-1", Set.of("safe"), Map.of("safe", 3), false);
        AtomicLong clock = new AtomicLong(1_000_000L);
        NetworkRouter r = new NetworkRouter(
                "local-1", NetworkRouter.Mode.CROSS_SERVER,
                () -> snap(peer), () -> 0, () -> 0, 50,
                /*rps*/1, /*burst*/2, clock::get);
        // Burst 2 lets two requests through; the third lands inside the
        // refill window (no clock advance) and must fall back.
        assertInstanceOf(RoutingDecision.CrossServer.class, r.route(UUID.randomUUID(), "safe"));
        assertInstanceOf(RoutingDecision.CrossServer.class, r.route(UUID.randomUUID(), "safe"));
        var fb = assertInstanceOf(RoutingDecision.LocalFallback.class, r.route(UUID.randomUUID(), "safe"));
        assertEquals(RoutingDecision.FallbackReason.TOKEN_BUCKET_EXHAUSTED, fb.reason());
    }

    @Test
    void parseRegionArg_rejects_equals_and_colon_per_D7() {
        assertThrows(IllegalArgumentException.class, () -> NetworkRouter.parseRegionArg("east=safe"));
        assertThrows(IllegalArgumentException.class, () -> NetworkRouter.parseRegionArg("east:safe"));
        assertEquals("safe", NetworkRouter.parseRegionArg("safe"));
        assertEquals("safe", NetworkRouter.parseRegionArg("  safe  "));
        assertSame(null, NetworkRouter.parseRegionArg(null));
        assertSame(null, NetworkRouter.parseRegionArg("   "));
    }

    @Test
    void mode_parse_accepts_dashed_and_underscored_variants() {
        assertSame(NetworkRouter.Mode.LOCAL, NetworkRouter.Mode.parse(null));
        assertSame(NetworkRouter.Mode.LOCAL, NetworkRouter.Mode.parse(""));
        assertSame(NetworkRouter.Mode.LOCAL, NetworkRouter.Mode.parse("local"));
        assertSame(NetworkRouter.Mode.CROSS_SERVER, NetworkRouter.Mode.parse("cross-server"));
        assertSame(NetworkRouter.Mode.CROSS_SERVER, NetworkRouter.Mode.parse("cross_server"));
        assertSame(NetworkRouter.Mode.AUTO, NetworkRouter.Mode.parse("auto"));
        assertSame(NetworkRouter.Mode.LOCAL, NetworkRouter.Mode.parse("nonsense"));
    }

    @Test
    void pre_L6_peer_with_only_regionsAvailable_still_qualifies() {
        // Legacy peer: empty regions Set, but regionsAvailable list populated.
        BackendHeartbeat legacy = new BackendHeartbeat(
                "legacy-1", 1, BackendHeartbeat.PluginState.READY, true,
                System.currentTimeMillis(), 5.0, 0, 100, 0L, 1L, 0,
                List.of("safe"), List.of(), false);
        NetworkRouter r = routerWith(NetworkRouter.Mode.CROSS_SERVER, snap(legacy), 0, 0, 50);
        assertInstanceOf(RoutingDecision.CrossServer.class, r.route(UUID.randomUUID(), "safe"));
    }

    @Test
    void empty_region_argument_clears_constraint() {
        BackendHeartbeat peer = hb("peer-1", Set.of(), Map.of(), false);
        NetworkRouter r = routerWith(NetworkRouter.Mode.CROSS_SERVER, snap(peer), 0, 0, 50);
        RoutingDecision d = r.route(UUID.randomUUID(), null);
        var cs = assertInstanceOf(RoutingDecision.CrossServer.class, d);
        assertTrue(cs.regionKey().isEmpty());
    }

    @Test
    void suppliers_are_invoked_each_call_not_cached() {
        // Confirms the router does not snapshot any of its suppliers in the
        // constructor: changing the queue depth between two invocations
        // flips the decision from CrossServer -> LocalFallback(QUEUE_FULL).
        BackendHeartbeat peer = hb("peer-1", Set.of("safe"), Map.of("safe", 3), false);
        AtomicInteger depth = new AtomicInteger(0);
        NetworkRouter r = new NetworkRouter(
                "local-1", NetworkRouter.Mode.CROSS_SERVER,
                () -> snap(peer), () -> 0, depth::get, 1,
                100, 100, System::currentTimeMillis);
        assertInstanceOf(RoutingDecision.CrossServer.class, r.route(UUID.randomUUID(), "safe"));
        depth.set(1);
        var fb = assertInstanceOf(RoutingDecision.LocalFallback.class, r.route(UUID.randomUUID(), "safe"));
        assertEquals(RoutingDecision.FallbackReason.QUEUE_FULL, fb.reason());
        assertNotNull(r.tokenBucket());
    }
}
