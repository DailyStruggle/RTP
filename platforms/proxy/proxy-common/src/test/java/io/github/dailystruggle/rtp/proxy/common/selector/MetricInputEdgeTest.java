package io.github.dailystruggle.rtp.proxy.common.selector;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricInputEdgeTest {

    private BackendHeartbeat create(double mspt, int queueDepth, int softCap, long heapUsed, long heapMax,
                                    int players, int keptCount, Map<String, Integer> regionKept) {
        return new BackendHeartbeat(
                "srv-1", 1, PluginState.READY, true, System.currentTimeMillis(),
                mspt, queueDepth, softCap, heapUsed, heapMax, players,
                List.of(), List.of(), false, keptCount, 0,
                regionKept != null ? regionKept.keySet() : Collections.emptySet(),
                regionKept != null ? regionKept : Collections.emptyMap());
    }

    @Test
    void parse_configIdOrName_andErrors() {
        assertEquals(MetricInput.MSPT, MetricInput.fromConfigId("mspt"));
        assertEquals(MetricInput.MSPT, MetricInput.fromConfigId("MSPT"));
        assertEquals(MetricInput.QUEUE_DEPTH, MetricInput.fromConfigId("queueDepth"));
        assertEquals(MetricInput.QUEUE_DEPTH, MetricInput.fromConfigId("QUEUE_DEPTH"));
        assertEquals(MetricInput.HEAP_USED, MetricInput.fromConfigId("heapUsed"));
        assertEquals(MetricInput.HEAP_FREE, MetricInput.fromConfigId("heapFree"));
        assertEquals(MetricInput.PLAYER_COUNT, MetricInput.fromConfigId("playerCount"));
        assertEquals(MetricInput.KEPT_COUNT, MetricInput.fromConfigId("keptCount"));
        assertEquals(MetricInput.TPS, MetricInput.fromConfigId("tps"));
        assertEquals(MetricInput.KEPT_REGION, MetricInput.fromConfigId("keptRegion"));

        assertThrows(IllegalArgumentException.class, () -> MetricInput.fromConfigId(null));
        assertThrows(IllegalArgumentException.class, () -> MetricInput.fromConfigId("non-existent-input"));
    }

    @Test
    void normalize_heapUsedAndFree_zeroHeapMax() {
        BackendHeartbeat hb = create(20.0, 0, 10, 0, 0, 10, 64, Map.of());
        assertEquals(0.0, MetricInput.HEAP_USED.normalize(hb));
        assertEquals(0.0, MetricInput.HEAP_FREE.normalize(hb));
    }

    @Test
    void normalize_tps_variousMspt() {
        // mspt <= 0 gives 20 tps -> 0.0 (best)
        BackendHeartbeat hbZero = create(0.0, 0, 10, 10, 100, 10, 64, Map.of());
        assertEquals(0.0, MetricInput.TPS.normalize(hbZero));

        BackendHeartbeat hbNeg = create(-5.0, 0, 10, 10, 100, 10, 64, Map.of());
        assertEquals(0.0, MetricInput.TPS.normalize(hbNeg));

        // 50mspt gives 20tps -> 0.0
        BackendHeartbeat hb50 = create(50.0, 0, 10, 10, 100, 10, 64, Map.of());
        assertEquals(0.0, MetricInput.TPS.normalize(hb50));

        // 100mspt gives 10tps -> 1 - 10/20 = 0.5
        BackendHeartbeat hb100 = create(100.0, 0, 10, 10, 100, 10, 64, Map.of());
        assertEquals(0.5, MetricInput.TPS.normalize(hb100), 1e-6);
    }

    @Test
    void normalize_keptRegion_fallbacks() {
        BackendHeartbeat hb = create(20.0, 0, 10, 10, 100, 10, 32, Map.of("r1", 64, "r2", 0));

        // null regionKey falls back to keptCount
        assertEquals(MetricInput.KEPT_COUNT.normalize(hb), MetricInput.KEPT_REGION.normalize(hb, null));
        assertEquals(MetricInput.KEPT_COUNT.normalize(hb), MetricInput.KEPT_REGION.normalize(hb, ""));

        // r1 has 64 locations -> 1.0 warm -> 0.0 worst
        assertEquals(0.0, MetricInput.KEPT_REGION.normalize(hb, "r1"));
        // r2 has 0 locations -> 0.0 warm -> 1.0 worst
        assertEquals(1.0, MetricInput.KEPT_REGION.normalize(hb, "r2"));
        // unmapped region -> 0 -> 1.0 worst
        assertEquals(1.0, MetricInput.KEPT_REGION.normalize(hb, "unmapped"));

        // Heartbeat with null regionKeptCounts map
        BackendHeartbeat hbNullMap = create(20.0, 0, 10, 10, 100, 10, 64, null);
        assertEquals(1.0, MetricInput.KEPT_REGION.normalize(hbNullMap, "r1"));
    }

    @Test
    void normalize_regionAwareVariant_delegatesForBackendInputs() {
        BackendHeartbeat hb = create(50.0, 5, 10, 50, 100, 20, 64, Map.of());
        assertEquals(MetricInput.MSPT.normalize(hb), MetricInput.MSPT.normalize(hb, "r1"));
        assertEquals(MetricInput.QUEUE_DEPTH.normalize(hb), MetricInput.QUEUE_DEPTH.normalize(hb, "r1"));
        assertEquals(MetricInput.PLAYER_COUNT.normalize(hb), MetricInput.PLAYER_COUNT.normalize(hb, "r1"));
    }
}
