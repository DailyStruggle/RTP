package io.github.dailystruggle.rtp.proxy.common.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape contract for the four cross-server fields added to
 * {@link BackendHeartbeat} - {@code keptCount}, {@code networkReservedCount},
 * {@code regions}, {@code regionKeptCounts}. Verifies field round-trip,
 * immutability, null-rejection, and that both backward-compatibility
 * constructors default the new fields to zero / empty.
 */
class BackendHeartbeatKeptCountTest {

    private static final long NOW = 1_700_000_000_000L;

    private static BackendHeartbeat fullHeartbeat(int keptCount,
                                           int networkReservedCount,
                                           Set<String> regions,
                                           Map<String, Integer> regionKeptCounts) {
        return new BackendHeartbeat(
                "a", 1, BackendHeartbeat.PluginState.READY, true, NOW,
                10.0, 0, 20, 0L, 0L, 0,
                List.of(), List.of(), false,
                keptCount, networkReservedCount, regions, regionKeptCounts);
    }

    @Test
    @DisplayName("All four cross-server fields round-trip via the full constructor")
    void fullCtorRoundTrip() {
        BackendHeartbeat hb = fullHeartbeat(7, 3,
                Set.of("default", "nether"),
                Map.of("default", 5, "nether", 2));
        assertEquals(7, hb.keptCount());
        assertEquals(3, hb.networkReservedCount());
        assertEquals(Set.of("default", "nether"), hb.regions());
        assertEquals(Map.of("default", 5, "nether", 2), hb.regionKeptCounts());
    }

    @Test
    @DisplayName("13-arg compat ctor defaults cross-server fields to zero / empty")
    void compatCtor13Defaults() {
        BackendHeartbeat hb = new BackendHeartbeat(
                "a", 1, BackendHeartbeat.PluginState.READY, true, NOW,
                10.0, 0, 20, 0L, 0L, 0,
                List.of(), List.of());
        assertEquals(0, hb.keptCount());
        assertEquals(0, hb.networkReservedCount());
        assertTrue(hb.regions().isEmpty());
        assertTrue(hb.regionKeptCounts().isEmpty());
        // killSwitch default still preserved.
        assertEquals(false, hb.killSwitch());
    }

    @Test
    @DisplayName("14-arg killSwitch compat ctor defaults cross-server fields to zero / empty")
    void compatCtor14Defaults() {
        BackendHeartbeat hb = new BackendHeartbeat(
                "a", 1, BackendHeartbeat.PluginState.READY, true, NOW,
                10.0, 0, 20, 0L, 0L, 0,
                List.of(), List.of(), true);
        assertEquals(true, hb.killSwitch());
        assertEquals(0, hb.keptCount());
        assertEquals(0, hb.networkReservedCount());
        assertTrue(hb.regions().isEmpty());
        assertTrue(hb.regionKeptCounts().isEmpty());
    }

    @Test
    @DisplayName("regions and regionKeptCounts are defensively copied")
    void defensiveCopy() {
        java.util.HashSet<String> mutableRegions = new java.util.HashSet<>();
        mutableRegions.add("default");
        java.util.HashMap<String, Integer> mutableCounts = new java.util.HashMap<>();
        mutableCounts.put("default", 5);

        BackendHeartbeat hb = fullHeartbeat(5, 0, mutableRegions, mutableCounts);

        mutableRegions.add("nether");
        mutableCounts.put("nether", 99);

        assertEquals(Set.of("default"), hb.regions());
        assertEquals(Map.of("default", 5), hb.regionKeptCounts());

        // The exposed views must reject mutation.
        assertThrows(UnsupportedOperationException.class,
                () -> hb.regions().add("end"));
        assertThrows(UnsupportedOperationException.class,
                () -> hb.regionKeptCounts().put("end", 1));
    }

    @Test
    @DisplayName("null regions and regionKeptCounts are rejected")
    void nullRejection() {
        assertThrows(NullPointerException.class,
                () -> fullHeartbeat(0, 0, null, Map.of()));
        assertThrows(NullPointerException.class,
                () -> fullHeartbeat(0, 0, Set.of(), null));
    }

    @Test
    @DisplayName("Empty regions / regionKeptCounts are accepted and non-null")
    void emptyAccepted() {
        BackendHeartbeat hb = fullHeartbeat(0, 0, Set.of(), Map.of());
        assertNotNull(hb.regions());
        assertNotNull(hb.regionKeptCounts());
        assertTrue(hb.regions().isEmpty());
        assertTrue(hb.regionKeptCounts().isEmpty());
    }
}
