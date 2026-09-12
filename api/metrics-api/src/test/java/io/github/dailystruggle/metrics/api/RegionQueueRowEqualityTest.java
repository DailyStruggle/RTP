package io.github.dailystruggle.metrics.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RegionQueueRow equals/hashCode/toString & edge cases (METRICS_PLAN.md M2)")
class RegionQueueRowEqualityTest {

    private RegionQueueRow row() {
        return new RegionQueueRow(1, 5, 20, 15, 50, 2, 20,
                RegionQueueStatus.OK, Map.of("hot", 5), 3);
    }

    @Test
    @DisplayName("equals is reflexive, symmetric, and value-based")
    void equalsContract() {
        RegionQueueRow a = row();
        RegionQueueRow b = row();

        assertEquals(a, a);
        assertEquals(a, b);
        assertEquals(b, a);
        assertEquals(a.hashCode(), b.hashCode());

        assertNotEquals(a, null);
        assertNotEquals(a, "not a row");
    }

    @Test
    @DisplayName("equals distinguishes every differing field")
    void equalsDiffers() {
        RegionQueueRow base = row();
        // Each variant flips exactly one field so every && short-circuit in equals is exercised.
        assertNotEquals(base, new RegionQueueRow(9, 5, 20, 15, 50, 2, 20, RegionQueueStatus.OK, Map.of("hot", 5), 3));
        assertNotEquals(base, new RegionQueueRow(1, 9, 20, 15, 50, 2, 20, RegionQueueStatus.OK, Map.of("hot", 5), 3));
        assertNotEquals(base, new RegionQueueRow(1, 5, 99, 15, 50, 2, 20, RegionQueueStatus.OK, Map.of("hot", 5), 3));
        assertNotEquals(base, new RegionQueueRow(1, 5, 20, 99, 50, 2, 20, RegionQueueStatus.OK, Map.of("hot", 5), 3));
        assertNotEquals(base, new RegionQueueRow(1, 5, 20, 15, 99, 2, 20, RegionQueueStatus.OK, Map.of("hot", 5), 3));
        assertNotEquals(base, new RegionQueueRow(1, 5, 20, 15, 50, 9, 20, RegionQueueStatus.OK, Map.of("hot", 5), 3));
        assertNotEquals(base, new RegionQueueRow(1, 5, 20, 15, 50, 2, 99, RegionQueueStatus.OK, Map.of("hot", 5), 3));
        assertNotEquals(base, new RegionQueueRow(1, 5, 20, 15, 50, 2, 20, RegionQueueStatus.LOW, Map.of("hot", 5), 3));
        assertNotEquals(base, new RegionQueueRow(1, 5, 20, 15, 50, 2, 20, RegionQueueStatus.OK, Map.of("cold", 9), 3));
        assertNotEquals(base, new RegionQueueRow(1, 5, 20, 15, 50, 2, 20, RegionQueueStatus.OK, Map.of("hot", 5), 9));
    }

    @Test
    @DisplayName("RegionQueueStatus.derive returns OK when keptCap is zero and cache is non-empty")
    void deriveOkWithZeroCap() {
        // keptFill>0 so not EMPTY/SATURATED; keptCap==0 skips the LOW check and falls through to OK.
        assertEquals(RegionQueueStatus.OK, RegionQueueStatus.derive(0, 3, 0, 0));
        // enum valueOf/values coverage
        assertEquals(RegionQueueStatus.EMPTY, RegionQueueStatus.valueOf("EMPTY"));
        assertEquals(4, RegionQueueStatus.values().length);
    }

    @Test
    @DisplayName("Negative reallocations are clamped to zero")
    void reallocationsClamped() {
        RegionQueueRow r = new RegionQueueRow(0, 1, 4, 0, 0, null, null,
                RegionQueueStatus.OK, null, -7);
        assertEquals(0, r.reallocations);
        assertTrue(r.stageOccupancy.isEmpty());
    }

    @Test
    @DisplayName("toString carries the field names")
    void toStringContent() {
        String s = row().toString();
        assertTrue(s.contains("RegionQueueRow{"));
        assertTrue(s.contains("playerQueueDepth=1"));
        assertTrue(s.contains("stageOccupancy="));
    }
}
