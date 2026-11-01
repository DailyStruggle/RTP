package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Hard-pin semantics on the 3-arg
 * {@link NetworkRouter#route(UUID, String, String)}. The named
 * {@code serverHint} must exist, be alive, be non-killSwitch, and advertise
 * the requested region; any failure returns
 * {@link RoutingDecision.LocalFallback} with
 * {@link RoutingDecision.FallbackReason#REGION_UNAVAILABLE}.
 */
class NetworkRouterServerHintTest {

    private static BackendHeartbeat hb(String id, Set<String> regions, boolean kill) {
        return new BackendHeartbeat(
                id, 1, BackendHeartbeat.PluginState.READY, true,
                System.currentTimeMillis(), 5.0, 0, 100, 0L, 1L, 0,
                List.of(), List.of(),
                kill, 0, 0, regions, Map.of());
    }

    private static NetworkSnapshot snap(BackendHeartbeat... rows) {
        LinkedHashMap<String, BackendHeartbeat> m = new LinkedHashMap<>();
        for (BackendHeartbeat r : rows) m.put(r.serverId(), r);
        return new NetworkSnapshot(System.currentTimeMillis(), m);
    }

    private static NetworkRouter routerWith(NetworkRouter.Mode mode, NetworkSnapshot s) {
        return new NetworkRouter(
                "local-1", mode,
                () -> s,
                () -> 0, () -> 0, 50,
                100, 100,
                System::currentTimeMillis);
    }

    @Test
    void hardpin_to_live_peer_with_region_routes_CrossServer_with_hint() {
        var peer = hb("backend-a", Set.of("default"), false);
        var r = routerWith(NetworkRouter.Mode.CROSS_SERVER, snap(peer));
        var d = r.route(UUID.randomUUID(), "default", "backend-a");
        var cs = assertInstanceOf(RoutingDecision.CrossServer.class, d);
        assertEquals("backend-a", cs.serverHint().orElseThrow());
        assertEquals("default", cs.regionKey().orElseThrow());
    }

    @Test
    void hardpin_to_unknown_server_returns_RegionUnavailable() {
        var peer = hb("backend-a", Set.of("default"), false);
        var r = routerWith(NetworkRouter.Mode.CROSS_SERVER, snap(peer));
        var d = r.route(UUID.randomUUID(), "default", "backend-z");
        var fb = assertInstanceOf(RoutingDecision.LocalFallback.class, d);
        assertEquals(RoutingDecision.FallbackReason.REGION_UNAVAILABLE, fb.reason());
    }

    @Test
    void hardpin_to_killSwitched_server_returns_RegionUnavailable() {
        var dead = hb("backend-a", Set.of("default"), true);
        var r = routerWith(NetworkRouter.Mode.CROSS_SERVER, snap(dead));
        var d = r.route(UUID.randomUUID(), "default", "backend-a");
        var fb = assertInstanceOf(RoutingDecision.LocalFallback.class, d);
        assertEquals(RoutingDecision.FallbackReason.REGION_UNAVAILABLE, fb.reason());
    }

    @Test
    void hardpin_to_live_server_but_wrong_region_returns_RegionUnavailable() {
        var peer = hb("backend-a", Set.of("east"), false);
        var r = routerWith(NetworkRouter.Mode.CROSS_SERVER, snap(peer));
        var d = r.route(UUID.randomUUID(), "default", "backend-a");
        var fb = assertInstanceOf(RoutingDecision.LocalFallback.class, d);
        assertEquals(RoutingDecision.FallbackReason.REGION_UNAVAILABLE, fb.reason());
    }

    @Test
    void hardpin_overrides_mode_LOCAL_so_named_server_still_routes() {
        // User-confirmed UX: an explicit server pick should not be silently
        // served locally just because mode=LOCAL. Either route or REJECT.
        var peer = hb("backend-a", Set.of("default"), false);
        var r = routerWith(NetworkRouter.Mode.LOCAL, snap(peer));
        var d = r.route(UUID.randomUUID(), "default", "backend-a");
        assertInstanceOf(RoutingDecision.CrossServer.class, d);
    }

    @Test
    void unpinned_route_still_works_unchanged() {
        // Backward-compat with the 2-arg route() behaviour.
        var peer = hb("backend-a", Set.of("default"), false);
        var r = routerWith(NetworkRouter.Mode.CROSS_SERVER, snap(peer));
        var d2 = r.route(UUID.randomUUID(), "default");
        var d3 = r.route(UUID.randomUUID(), "default", null);
        assertInstanceOf(RoutingDecision.CrossServer.class, d2);
        assertInstanceOf(RoutingDecision.CrossServer.class, d3);
        // The 3-arg variant with empty hint must also work (covers the
        // "" branch in the parser).
        var d3empty = r.route(UUID.randomUUID(), "default", "");
        assertInstanceOf(RoutingDecision.CrossServer.class, d3empty);
    }

    @Test
    void hardpin_under_AUTO_skips_local_first_optimisation() {
        // Even if local "could serve", an explicit hint must cross the
        // network to the named peer.
        var localHb = hb("local-1", Set.of("default"), false);
        var peer = hb("backend-a", Set.of("default"), false);
        // Set localKeptCountSupplier > 0 so the AUTO local-first branch
        // WOULD fire if not for the hint.
        var r = new NetworkRouter(
                "local-1", NetworkRouter.Mode.AUTO,
                () -> snap(localHb, peer),
                () -> 5, () -> 0, 50,
                100, 100, System::currentTimeMillis);
        var d = r.route(UUID.randomUUID(), "default", "backend-a");
        var cs = assertInstanceOf(RoutingDecision.CrossServer.class, d);
        assertEquals("backend-a", cs.serverHint().orElseThrow());
        // Confirm unpinned AUTO call still picks local.
        assertSame(RoutingDecision.Local.INSTANCE, r.route(UUID.randomUUID(), "default", null));
    }
}
