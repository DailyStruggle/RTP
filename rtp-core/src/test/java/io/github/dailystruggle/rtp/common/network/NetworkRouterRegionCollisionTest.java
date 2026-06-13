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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-function audit of region-name overlap across
 * backends. The side-effecting {@code auditAndWarn} variant is exercised
 * for the null-snapshot / no-collision branches; log assertions are out of
 * scope for unit-level coverage (covered by {@code rtp test full}).
 */
class NetworkRouterRegionCollisionTest {

    private static BackendHeartbeat hb(String id, Set<String> regions, List<String> legacy) {
        return new BackendHeartbeat(
                id, 1, BackendHeartbeat.PluginState.READY, true,
                System.currentTimeMillis(), 5.0, 0, 100, 0L, 1L, 0,
                legacy, List.of(),
                false, 0, 0, regions, Map.of());
    }

    private static NetworkSnapshot snap(BackendHeartbeat... rows) {
        LinkedHashMap<String, BackendHeartbeat> m = new LinkedHashMap<>();
        for (BackendHeartbeat r : rows) m.put(r.serverId(), r);
        return new NetworkSnapshot(System.currentTimeMillis(), m);
    }

    @Test
    void no_overlap_returns_empty_map() {
        NetworkSnapshot s = snap(
                hb("east", Set.of("safe", "wild"), List.of()),
                hb("west", Set.of("nether"), List.of()));
        Map<String, Set<String>> collisions =
                NetworkRegionCollisionWarner.findCollisions(s, "east");
        assertTrue(collisions.isEmpty());
    }

    @Test
    void single_region_overlap_returns_one_row_with_both_servers() {
        NetworkSnapshot s = snap(
                hb("east", Set.of("safe"), List.of()),
                hb("west", Set.of("safe"), List.of()));
        Map<String, Set<String>> collisions =
                NetworkRegionCollisionWarner.findCollisions(s, "east");
        assertEquals(1, collisions.size());
        assertEquals(Set.of("east", "west"), collisions.get("safe"));
    }

    @Test
    void three_way_overlap_records_all_three_servers() {
        NetworkSnapshot s = snap(
                hb("east", Set.of("safe"), List.of()),
                hb("west", Set.of("safe"), List.of()),
                hb("north", Set.of("safe"), List.of()));
        Map<String, Set<String>> collisions =
                NetworkRegionCollisionWarner.findCollisions(s, "east");
        assertEquals(Set.of("east", "west", "north"), collisions.get("safe"));
    }

    @Test
    void legacy_regionsAvailable_is_used_when_regions_set_is_empty() {
        NetworkSnapshot s = snap(
                hb("east", Set.of(), List.of("safe")),
                hb("west", Set.of(), List.of("safe")));
        Map<String, Set<String>> collisions =
                NetworkRegionCollisionWarner.findCollisions(s, "east");
        assertEquals(1, collisions.size());
        assertEquals(Set.of("east", "west"), collisions.get("safe"));
    }

    @Test
    void null_snapshot_returns_empty_and_auditAndWarn_does_not_throw() {
        Map<String, Set<String>> collisions =
                NetworkRegionCollisionWarner.findCollisions(null, "east");
        assertTrue(collisions.isEmpty());
        // Side-effecting variant must tolerate a null snapshot without an NPE
        // (boot path may call this before any heartbeat is observed).
        NetworkRegionCollisionWarner.auditAndWarn(
                null, "east", NetworkRegionCollisionWarner.Policy.WARN);
    }

    @Test
    void policy_parse_is_forwards_compatible() {
        // Only WARN is supported; unknown values fall back to WARN rather than throwing.
        assertEquals(NetworkRegionCollisionWarner.Policy.WARN,
                NetworkRegionCollisionWarner.Policy.parse(null));
        assertEquals(NetworkRegionCollisionWarner.Policy.WARN,
                NetworkRegionCollisionWarner.Policy.parse("rename-local"));
        assertEquals(NetworkRegionCollisionWarner.Policy.WARN,
                NetworkRegionCollisionWarner.Policy.parse("reject-startup"));
    }

    @Test
    void mixed_regions_only_collide_on_overlap_keys() {
        NetworkSnapshot s = snap(
                hb("east", Set.of("safe", "wild"), List.of()),
                hb("west", Set.of("wild"), List.of()),
                hb("north", Set.of("nether"), List.of()));
        Map<String, Set<String>> collisions =
                NetworkRegionCollisionWarner.findCollisions(s, "east");
        assertEquals(1, collisions.size());
        assertTrue(collisions.containsKey("wild"));
        assertFalse(collisions.containsKey("safe"));
        assertFalse(collisions.containsKey("nether"));
    }
}
