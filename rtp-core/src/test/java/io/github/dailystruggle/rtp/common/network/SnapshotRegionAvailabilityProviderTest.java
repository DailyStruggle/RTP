package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotRegionAvailabilityProviderTest {

    private static BackendHeartbeat hb(String serverId, boolean killSwitch, Set<String> regions, List<String> legacy) {
        return new BackendHeartbeat(
                serverId, 1, BackendHeartbeat.PluginState.READY, true,
                System.currentTimeMillis(), 20.0, 0, 100, 0L, 1L, 0,
                legacy, List.of(), killSwitch, 0, 0, regions, Map.of()
        );
    }

    private static NetworkSnapshot snap(BackendHeartbeat... heartbeats) {
        LinkedHashMap<String, BackendHeartbeat> map = new LinkedHashMap<>();
        for (BackendHeartbeat hb : heartbeats) {
            if (hb != null && hb.serverId() != null) {
                map.put(hb.serverId(), hb);
            }
        }
        return new NetworkSnapshot(System.currentTimeMillis(), map);
    }

    @Test
    @DisplayName("null supplier or throwing supplier degrades gracefully")
    void nullOrThrowingSupplierDegradesGracefully() {
        SnapshotRegionAvailabilityProvider nullProvider = new SnapshotRegionAvailabilityProvider(null);
        assertTrue(nullProvider.availableEntries().isEmpty());
        assertEquals(RegionAvailabilityProvider.Availability.UNKNOWN, nullProvider.availabilityOf("s1", "r1"));
        assertFalse(nullProvider.isServerKnown("s1"));

        SnapshotRegionAvailabilityProvider throwingProvider = new SnapshotRegionAvailabilityProvider(() -> {
            throw new RuntimeException("simulated failure");
        });
        assertTrue(throwingProvider.availableEntries().isEmpty());
        assertEquals(RegionAvailabilityProvider.Availability.UNKNOWN, throwingProvider.availabilityOf("s1", "r1"));
        assertFalse(throwingProvider.isServerKnown("s1"));
    }

    @Test
    @DisplayName("availableEntries aggregates server:region entries ignoring killSwitch and empty values")
    void availableEntriesAggregatesCorrectly() {
        BackendHeartbeat h1 = hb("server1", false, Set.of("wild", ""), List.of());
        BackendHeartbeat h2 = hb("server2", true, Set.of("nether"), List.of()); // killSwitch = true
        BackendHeartbeat h3 = hb("server3", false, Set.of(), List.of("end", "")); // legacy list
        BackendHeartbeat h4 = hb("", false, Set.of("wild"), List.of()); // empty serverId
        BackendHeartbeat h5 = new BackendHeartbeat(
                "server5", 1, BackendHeartbeat.PluginState.READY, true,
                System.currentTimeMillis(), 20.0, 0, 100, 0L, 1L, 0,
                List.of(), List.of(), false, 0, 0, Set.of(), Map.of()
        );

        NetworkSnapshot snapshot = snap(h1, h2, h3, h4, h5);
        SnapshotRegionAvailabilityProvider provider = new SnapshotRegionAvailabilityProvider(() -> snapshot);

        Set<String> available = provider.availableEntries();
        assertEquals(Set.of("server1:wild", "server3:end"), available);
    }

    @Test
    @DisplayName("availabilityOf evaluates target server and unconstrained server queries")
    void availabilityOfQueries() {
        BackendHeartbeat h1 = hb("server1", false, Set.of("wild"), List.of());
        BackendHeartbeat h2 = hb("server2", false, Set.of("nether"), List.of());
        BackendHeartbeat h3 = hb("server3", true, Set.of("minigame"), List.of()); // kill switched

        NetworkSnapshot snapshot = snap(h1, h2, h3);
        SnapshotRegionAvailabilityProvider provider = new SnapshotRegionAvailabilityProvider(() -> snapshot);

        // Null / empty regionKey -> UNKNOWN
        assertEquals(RegionAvailabilityProvider.Availability.UNKNOWN, provider.availabilityOf("server1", null));
        assertEquals(RegionAvailabilityProvider.Availability.UNKNOWN, provider.availabilityOf("server1", ""));

        // Unconstrained (null/empty serverId)
        assertEquals(RegionAvailabilityProvider.Availability.KNOWN_AVAILABLE, provider.availabilityOf(null, "wild"));
        assertEquals(RegionAvailabilityProvider.Availability.KNOWN_AVAILABLE, provider.availabilityOf("", "nether"));
        assertEquals(RegionAvailabilityProvider.Availability.UNKNOWN, provider.availabilityOf(null, "minigame"));
        assertEquals(RegionAvailabilityProvider.Availability.UNKNOWN, provider.availabilityOf("", "nonexistent"));

        // Constrained serverId
        assertEquals(RegionAvailabilityProvider.Availability.KNOWN_AVAILABLE, provider.availabilityOf("server1", "wild"));
        assertEquals(RegionAvailabilityProvider.Availability.KNOWN_UNAVAILABLE, provider.availabilityOf("server1", "nether"));
        assertEquals(RegionAvailabilityProvider.Availability.UNKNOWN, provider.availabilityOf("server3", "minigame")); // kill switched ignored
        assertEquals(RegionAvailabilityProvider.Availability.UNKNOWN, provider.availabilityOf("unknownServer", "wild"));
    }

    @Test
    @DisplayName("isServerKnown checks presence of non-killswitched server")
    void isServerKnown() {
        BackendHeartbeat h1 = hb("server1", false, Set.of("wild"), List.of());
        BackendHeartbeat h2 = hb("server2", true, Set.of("nether"), List.of()); // killSwitch = true

        NetworkSnapshot snapshot = snap(h1, h2);
        SnapshotRegionAvailabilityProvider provider = new SnapshotRegionAvailabilityProvider(() -> snapshot);

        assertFalse(provider.isServerKnown(null));
        assertFalse(provider.isServerKnown(""));
        assertTrue(provider.isServerKnown("server1"));
        assertFalse(provider.isServerKnown("server2")); // killSwitch
        assertFalse(provider.isServerKnown("server3")); // absent
    }
}
