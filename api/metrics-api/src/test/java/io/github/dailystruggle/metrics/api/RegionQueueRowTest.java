package io.github.dailystruggle.metrics.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RegionQueueRow & RegionQueueStatus (METRICS_PLAN.md M2)")
class RegionQueueRowTest {

    @Test
    @DisplayName("RegionQueueStatus derivation covers OK, LOW, EMPTY, and SATURATED")
    void statusDerivation() {
        // OK: queue 0, keptFill > 25% of keptCap, unkeptFill > 0
        assertEquals(RegionQueueStatus.OK, RegionQueueStatus.derive(0, 10, 20, 5));

        // LOW: keptFill < keptCap / 4
        assertEquals(RegionQueueStatus.LOW, RegionQueueStatus.derive(0, 4, 20, 5));

        // EMPTY: keptFill == 0 && unkeptFill == 0
        assertEquals(RegionQueueStatus.EMPTY, RegionQueueStatus.derive(0, 0, 20, 0));

        // SATURATED: playerQueueDepth > 0 && keptFill == 0
        assertEquals(RegionQueueStatus.SATURATED, RegionQueueStatus.derive(3, 0, 20, 10));
        assertEquals(RegionQueueStatus.SATURATED, RegionQueueStatus.derive(1, 0, 20, 0));
    }

    @Test
    @DisplayName("RegionQueueRow immutability and fields")
    void rowFieldsAndImmutability() {
        Map<String, Integer> stages = Map.of("hot", 5, "login", 2);
        RegionQueueRow row = new RegionQueueRow(
                1, 5, 20, 15, 50, 2, 20, RegionQueueStatus.OK, stages, 3
        );

        assertEquals(1, row.playerQueueDepth);
        assertEquals(5, row.keptFill);
        assertEquals(20, row.keptCap);
        assertEquals(15, row.unkeptFill);
        assertEquals(50, row.unkeptCap);
        assertEquals(2, row.loginFill);
        assertEquals(20, row.loginCap);
        assertEquals(RegionQueueStatus.OK, row.status);
        assertEquals(stages, row.stageOccupancy);
        assertEquals(3, row.reallocations);

        // defensive copy
        assertThrows(UnsupportedOperationException.class, () -> row.stageOccupancy.put("test", 1));
    }

    @Test
    @DisplayName("RegionQueueRow auto-derives status in convenience constructor")
    void convenienceConstructor() {
        RegionQueueRow row = new RegionQueueRow(0, 0, 10, 0, 20, null, null);
        assertEquals(RegionQueueStatus.EMPTY, row.status);
        assertTrue(row.stageOccupancy.isEmpty());
        assertEquals(0, row.reallocations);
    }
}
