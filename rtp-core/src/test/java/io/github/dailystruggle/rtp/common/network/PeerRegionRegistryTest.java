package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PeerRegionRegistry} read-only adapter over the cached
 * {@link NetworkSnapshot}. Covers self/killSwitch exclusion, hard-pin
 * reachability check, and the legacy {@code regionsAvailable} fallback.
 */
class PeerRegionRegistryTest {

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

    @Test
    void peerEntries_lists_qualified_form_for_each_live_peer() {
        var a = hb("backend-a", Set.of("default", "east"), false);
        var b = hb("backend-b", Set.of("pvp"), false);
        var registry = new PeerRegionRegistry(() -> snap(a, b), "local-1");
        Set<String> entries = registry.peerEntries();
        assertEquals(Set.of("backend-a:default", "backend-a:east", "backend-b:pvp"), entries);
    }

    @Test
    void peerEntries_includes_self_for_hard_pin_distinguishability() {
        // On a backend, surfacing {@code <self>:default} alongside the
        // unqualified {@code default} lets operators distinguish the load-
        // balanced unqualified form from a hard-pin to this backend.
        var self = hb("local-1", Set.of("default"), false);
        var peer = hb("backend-b", Set.of("default"), false);
        var registry = new PeerRegionRegistry(() -> snap(self, peer), "local-1");
        Set<String> entries = registry.peerEntries();
        assertEquals(Set.of("local-1:default", "backend-b:default"), entries);
    }

    @Test
    void peerEntries_excludes_killSwitched_peers() {
        var alive = hb("backend-a", Set.of("default"), false);
        var dead = hb("backend-b", Set.of("default"), true);
        var registry = new PeerRegionRegistry(() -> snap(alive, dead), "local-1");
        Set<String> entries = registry.peerEntries();
        assertEquals(Set.of("backend-a:default"), entries);
    }

    @Test
    void peerEntries_empty_when_snapshot_is_null() {
        var registry = new PeerRegionRegistry(() -> null, "local-1");
        assertTrue(registry.peerEntries().isEmpty());
    }

    @Test
    void peerEntries_falls_back_to_regionsAvailable_for_legacy_peer() {
        // Older peer: regions Set empty, legacy regionsAvailable list populated.
        BackendHeartbeat legacy = new BackendHeartbeat(
                "legacy-1", 1, BackendHeartbeat.PluginState.READY, true,
                System.currentTimeMillis(), 5.0, 0, 100, 0L, 1L, 0,
                List.of("default", "east"), List.of(), false);
        var registry = new PeerRegionRegistry(() -> snap(legacy), "local-1");
        assertEquals(Set.of("legacy-1:default", "legacy-1:east"), registry.peerEntries());
    }

    @Test
    void peerEntries_survives_flaky_snapshot_supplier() {
        var registry = new PeerRegionRegistry(() -> { throw new RuntimeException("boom"); }, "local-1");
        assertTrue(registry.peerEntries().isEmpty());
    }

    @Test
    void isReachableHardPin_returns_true_for_live_peer_hosting_region() {
        var peer = hb("backend-a", Set.of("default"), false);
        var registry = new PeerRegionRegistry(() -> snap(peer), "local-1");
        assertTrue(registry.isReachableHardPin("backend-a", "default"));
    }

    @Test
    void isReachableHardPin_returns_false_when_region_missing() {
        var peer = hb("backend-a", Set.of("default"), false);
        var registry = new PeerRegionRegistry(() -> snap(peer), "local-1");
        assertFalse(registry.isReachableHardPin("backend-a", "missing"));
    }

    @Test
    void isReachableHardPin_returns_false_for_unknown_server() {
        var peer = hb("backend-a", Set.of("default"), false);
        var registry = new PeerRegionRegistry(() -> snap(peer), "local-1");
        assertFalse(registry.isReachableHardPin("backend-z", "default"));
    }

    @Test
    void isReachableHardPin_returns_false_for_killSwitched_peer() {
        var dead = hb("backend-a", Set.of("default"), true);
        var registry = new PeerRegionRegistry(() -> snap(dead), "local-1");
        assertFalse(registry.isReachableHardPin("backend-a", "default"));
    }

    @Test
    void isReachableHardPin_returns_true_for_self_hard_pin() {
        // Hard-pinning to self is the documented way to bypass the network
        // load balancer (use this backend's local regions explicitly).
        var self = hb("local-1", Set.of("default"), false);
        var registry = new PeerRegionRegistry(() -> snap(self), "local-1");
        assertTrue(registry.isReachableHardPin("local-1", "default"));
    }

    @Test
    void isReachableHardPin_returns_false_on_null_or_empty_args() {
        var peer = hb("backend-a", Set.of("default"), false);
        var registry = new PeerRegionRegistry(() -> snap(peer), "local-1");
        assertFalse(registry.isReachableHardPin(null, "default"));
        assertFalse(registry.isReachableHardPin("backend-a", null));
        assertFalse(registry.isReachableHardPin("", "default"));
        assertFalse(registry.isReachableHardPin("backend-a", ""));
    }

    @Test
    void ctor_rejects_null_snapshot_supplier() {
        assertThrows(NullPointerException.class,
                () -> new PeerRegionRegistry(null, "local-1"));
    }

    // --- Option 1: topology-seeded candidates (plugin-message tier) ---

    @Test
    void peerEntries_seeds_default_region_for_topology_peer_without_heartbeat() {
        // No snapshot rows, but the proxy advertised backend-a/backend-b via
        // GetServers. With a single test player these backends are
        // player-empty and never gossip, so the snapshot stays empty.
        var registry = new PeerRegionRegistry(() -> snap(), "lobby-a");
        registry.setTopologyPeerSupplier(() -> Set.of("backend-a", "backend-b"));
        assertEquals(Set.of("backend-a:default", "backend-b:default"), registry.peerEntries());
    }

    @Test
    void peerEntries_prefers_heartbeat_over_topology_seed() {
        // backend-a has a real heartbeat (two regions); backend-b is only
        // known via topology -> seeded with the assumed default region.
        var a = hb("backend-a", Set.of("default", "east"), false);
        var registry = new PeerRegionRegistry(() -> snap(a), "lobby-a");
        registry.setTopologyPeerSupplier(() -> Set.of("backend-a", "backend-b"));
        assertEquals(
                Set.of("backend-a:default", "backend-a:east", "backend-b:default"),
                registry.peerEntries());
    }

    @Test
    void peerEntries_honours_assumed_region_override() {
        var registry = new PeerRegionRegistry(() -> snap(), "lobby-a");
        registry.setTopologyPeerSupplier(() -> Set.of("backend-a"));
        registry.setAssumedRegionSupplier(() -> Set.of("default", "pvp"));
        assertEquals(Set.of("backend-a:default", "backend-a:pvp"), registry.peerEntries());
    }

    @Test
    void isReachableHardPin_accepts_topology_peer_without_heartbeat() {
        // The load-bearing fix: a lobby must validate backend-a:default even
        // when no heartbeat has reached it (unknown -> accept; the
        // destination decides on arrival).
        var registry = new PeerRegionRegistry(() -> snap(), "lobby-a");
        registry.setTopologyPeerSupplier(() -> Set.of("backend-a"));
        assertTrue(registry.isReachableHardPin("backend-a", "default"));
        // Any region is accepted for a topology-known server (we do not know
        // its real region set).
        assertTrue(registry.isReachableHardPin("backend-a", "anything"));
    }

    @Test
    void isReachableHardPin_rejects_server_not_in_topology_and_not_in_snapshot() {
        var registry = new PeerRegionRegistry(() -> snap(), "lobby-a");
        registry.setTopologyPeerSupplier(() -> Set.of("backend-a"));
        assertFalse(registry.isReachableHardPin("backend-z", "default"));
    }

    @Test
    void isReachableHardPin_killSwitch_heartbeat_beats_topology_seed() {
        // A live (kill-switched) heartbeat is authoritative: even though the
        // proxy still lists the server, the operator asserted it unavailable.
        var dead = hb("backend-a", Set.of("default"), true);
        var registry = new PeerRegionRegistry(() -> snap(dead), "lobby-a");
        registry.setTopologyPeerSupplier(() -> Set.of("backend-a"));
        assertFalse(registry.isReachableHardPin("backend-a", "default"));
    }

    @Test
    void isReachableHardPin_known_region_set_without_region_beats_topology_seed() {
        // backend-a has a concrete region set that does not include "missing":
        // KNOWN_UNAVAILABLE wins over the topology accept-all.
        var peer = hb("backend-a", Set.of("default"), false);
        var registry = new PeerRegionRegistry(() -> snap(peer), "lobby-a");
        registry.setTopologyPeerSupplier(() -> Set.of("backend-a"));
        assertFalse(registry.isReachableHardPin("backend-a", "missing"));
    }
}
