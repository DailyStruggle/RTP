package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.network.NetworkCommandHook;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BukkitNetworkCommandHook} translates the
 * {@link NetworkRouter}'s {@link RoutingDecision} into the rtp-api
 * {@link NetworkCommandHook.RoutingResult} SPI shapes. Covers the
 * `server:region` parse path, hard-pin reject, silent fallback for
 * non-explicit reasons, and the enrolment-buffer side effect.
 */
class BukkitNetworkCommandHookTest {

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

    private static NetworkRouter router(NetworkRouter.Mode mode, NetworkSnapshot s) {
        return new NetworkRouter(
                "local-1", mode,
                () -> s,
                () -> 0, () -> 0, 50,
                100, 100,
                System::currentTimeMillis);
    }

    private static NetworkEnrolmentBuffer freshBuffer() {
        return new NetworkEnrolmentBuffer(batch -> { /* test sink: drop */ }, 100);
    }

    @Test
    void unqualified_region_with_no_peers_falls_through_to_Local_silently() {
        // No peers in the snapshot under CROSS_SERVER => NO_LIVE_PEER
        // fallback => silent Local per "simple for users" UX rule.
        var r = router(NetworkRouter.Mode.CROSS_SERVER, snap());
        var buffer = freshBuffer();
        var hook = new BukkitNetworkCommandHook(r, buffer);
        var result = hook.route(UUID.randomUUID(),
                Map.of("region", List.of("default")));
        assertInstanceOf(NetworkCommandHook.RoutingResult.Local.class, result);
        assertEquals(0, buffer.pendingDepth());
    }

    @Test
    void qualified_server_region_routes_CrossServer_and_enrols() {
        var peer = hb("backend-a", Set.of("default"), false);
        var r = router(NetworkRouter.Mode.CROSS_SERVER, snap(peer));
        var buffer = freshBuffer();
        var hook = new BukkitNetworkCommandHook(r, buffer);
        UUID player = UUID.randomUUID();

        var result = hook.route(player,
                Map.of("region", List.of("backend-a:default")));

        var cs = assertInstanceOf(NetworkCommandHook.RoutingResult.CrossServer.class, result);
        assertEquals("default", cs.regionKey().orElseThrow());
        assertEquals("backend-a", cs.serverHint().orElseThrow());
        // Side effect: an EnrolmentRecord was offered to the buffer.
        assertEquals(1, buffer.pendingDepth());
    }

    @Test
    void qualified_server_region_with_unreachable_peer_rejects_with_networkRegionUnavailable() {
        // Peer exists but is killSwitched; hard-pin must reject.
        var dead = hb("backend-a", Set.of("default"), true);
        var r = router(NetworkRouter.Mode.CROSS_SERVER, snap(dead));
        var hook = new BukkitNetworkCommandHook(r, freshBuffer());

        var result = hook.route(UUID.randomUUID(),
                Map.of("region", List.of("backend-a:default")));

        var reject = assertInstanceOf(NetworkCommandHook.RoutingResult.Reject.class, result);
        assertEquals(MessagesKeys.networkRegionUnavailable.name(), reject.messageKey());
        assertEquals("backend-a:default", reject.placeholder());
    }

    @Test
    void malformed_server_colon_region_rejects_with_raw_input_as_placeholder() {
        var peer = hb("backend-a", Set.of("default"), false);
        var r = router(NetworkRouter.Mode.CROSS_SERVER, snap(peer));
        var hook = new BukkitNetworkCommandHook(r, freshBuffer());

        var result = hook.route(UUID.randomUUID(),
                Map.of("region", List.of("a:b:c")));

        var reject = assertInstanceOf(NetworkCommandHook.RoutingResult.Reject.class, result);
        assertEquals(MessagesKeys.networkRegionUnavailable.name(), reject.messageKey());
        assertEquals("a:b:c", reject.placeholder());
    }

    @Test
    void unqualified_unreachable_region_rejects_loudly() {
        // Player typed `region=missing` and no peer hosts it.
        var peer = hb("backend-a", Set.of("east"), false);
        var r = router(NetworkRouter.Mode.CROSS_SERVER, snap(peer));
        var hook = new BukkitNetworkCommandHook(r, freshBuffer());

        var result = hook.route(UUID.randomUUID(),
                Map.of("region", List.of("missing")));

        var reject = assertInstanceOf(NetworkCommandHook.RoutingResult.Reject.class, result);
        assertEquals(MessagesKeys.networkRegionUnavailable.name(), reject.messageKey());
        assertEquals("missing", reject.placeholder());
    }

    @Test
    void no_region_arg_defers_to_router_and_routes_or_falls_through() {
        // With no region= at all, router should route under CROSS_SERVER
        // mode with a live peer.
        var peer = hb("backend-a", Set.of("anything"), false);
        var r = router(NetworkRouter.Mode.CROSS_SERVER, snap(peer));
        var buffer = freshBuffer();
        var hook = new BukkitNetworkCommandHook(r, buffer);
        var result = hook.route(UUID.randomUUID(), Map.of());
        assertInstanceOf(NetworkCommandHook.RoutingResult.CrossServer.class, result);
        assertEquals(1, buffer.pendingDepth());
    }

    @Test
    void mode_LOCAL_with_unqualified_region_returns_Local_silently() {
        var peer = hb("backend-a", Set.of("default"), false);
        var r = router(NetworkRouter.Mode.LOCAL, snap(peer));
        var hook = new BukkitNetworkCommandHook(r, freshBuffer());
        var result = hook.route(UUID.randomUUID(),
                Map.of("region", List.of("default")));
        assertSame(NetworkCommandHook.RoutingResult.LOCAL, result);
    }

    @Test
    void mode_LOCAL_with_qualified_pin_overrides_LOCAL_and_routes() {
        var peer = hb("backend-a", Set.of("default"), false);
        var r = router(NetworkRouter.Mode.LOCAL, snap(peer));
        var buffer = freshBuffer();
        var hook = new BukkitNetworkCommandHook(r, buffer);
        var result = hook.route(UUID.randomUUID(),
                Map.of("region", List.of("backend-a:default")));
        assertInstanceOf(NetworkCommandHook.RoutingResult.CrossServer.class, result);
        assertEquals(1, buffer.pendingDepth());
    }

    @Test
    void ctor_rejects_null_router_or_buffer() {
        assertThrows(NullPointerException.class,
                () -> new BukkitNetworkCommandHook(null, freshBuffer()));
        var r = router(NetworkRouter.Mode.LOCAL, snap());
        assertThrows(NullPointerException.class,
                () -> new BukkitNetworkCommandHook(r, null));
    }

    @Test
    void route_rejects_null_args_per_SPI_contract() {
        var r = router(NetworkRouter.Mode.LOCAL, snap());
        var hook = new BukkitNetworkCommandHook(r, freshBuffer());
        UUID player = UUID.randomUUID();
        assertThrows(NullPointerException.class, () -> hook.route(player, null));
        assertThrows(NullPointerException.class, () -> hook.route(null, Map.of()));
    }

    @Test
    void enrolment_carries_correlationId_and_regionKey() {
        var peer = hb("backend-a", Set.of("default"), false);
        var r = router(NetworkRouter.Mode.CROSS_SERVER, snap(peer));
        var buffer = freshBuffer();
        var hook = new BukkitNetworkCommandHook(r, buffer);
        var result = hook.route(UUID.randomUUID(),
                Map.of("region", List.of("backend-a:default")));
        var cs = assertInstanceOf(NetworkCommandHook.RoutingResult.CrossServer.class, result);
        assertTrue(buffer.pendingDepth() > 0);
        // Drain via flushOnce - the record should pop with matching correlationId.
        java.util.concurrent.atomic.AtomicReference<List<NetworkEnrolmentBuffer.EnrolmentRecord>> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        // Build a separate buffer with a capturing sink and re-issue.
        var buffer2 = new NetworkEnrolmentBuffer(seen::set, 100);
        var hook2 = new BukkitNetworkCommandHook(r, buffer2);
        var result2 = hook2.route(UUID.randomUUID(),
                Map.of("region", List.of("backend-a:default")));
        var cs2 = assertInstanceOf(NetworkCommandHook.RoutingResult.CrossServer.class, result2);
        buffer2.flushOnce();
        var captured = seen.get();
        assertEquals(1, captured.size());
        assertEquals(cs2.correlationId(), captured.get(0).correlationId());
        assertEquals("default", captured.get(0).regionKey().orElseThrow());
        assertEquals("backend-a", captured.get(0).serverHint().orElseThrow());
        // Silence the unused-variable lint warning on `cs`.
        assertSame(cs, cs);
    }
}
