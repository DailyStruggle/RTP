package io.github.dailystruggle.rtp.bukkit.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Bucket-boundary tests for the Phase M2 *Runtime health* chart helpers in
 * {@link RTPCostMetricsCharts} (CHECKLIST-metrics-and-multiserver.md row C4).
 *
 * <p>Each helper is pure (no {@code RTP} singleton dependency) so the suite
 * can drive boundary inputs directly without bootstrapping a plugin context.
 * The labels asserted here are the strings bStats serialises into the
 * dashboard payload, so a rename of any label is a dashboard-breaking change
 * and these tests will flag it.
 */
class RuntimeHealthBucketsTest {

    @Test
    @DisplayName("scaledTps: NaN / negative -> 0; nominal 20.0 -> 2000; clamp at 25.00 TPS -> 2500")
    void scaledTps_boundaries() {
        assertEquals(0, RTPCostMetricsCharts.scaledTps(Double.NaN));
        assertEquals(0, RTPCostMetricsCharts.scaledTps(-1.0));
        assertEquals(0, RTPCostMetricsCharts.scaledTps(0.0));
        assertEquals(1987, RTPCostMetricsCharts.scaledTps(19.87));
        assertEquals(2000, RTPCostMetricsCharts.scaledTps(20.0));
        assertEquals(2500, RTPCostMetricsCharts.scaledTps(25.0));
        assertEquals(2500, RTPCostMetricsCharts.scaledTps(99.0)); // clamped
    }

    @Test
    @DisplayName("heapPressureBucket: invalid -> unknown; 0% -> <25; boundary stops")
    void heapPressureBucket_boundaries() {
        assertEquals("unknown", RTPCostMetricsCharts.heapPressureBucket(0L, 0L));
        assertEquals("unknown", RTPCostMetricsCharts.heapPressureBucket(-1L, 100L));
        assertEquals("unknown", RTPCostMetricsCharts.heapPressureBucket(50L, -1L));
        assertEquals("<25", RTPCostMetricsCharts.heapPressureBucket(0L, 100L));
        assertEquals("<25", RTPCostMetricsCharts.heapPressureBucket(24L, 100L));
        assertEquals("25-50", RTPCostMetricsCharts.heapPressureBucket(25L, 100L));
        assertEquals("25-50", RTPCostMetricsCharts.heapPressureBucket(49L, 100L));
        assertEquals("50-75", RTPCostMetricsCharts.heapPressureBucket(50L, 100L));
        assertEquals("75-90", RTPCostMetricsCharts.heapPressureBucket(75L, 100L));
        assertEquals("90+", RTPCostMetricsCharts.heapPressureBucket(90L, 100L));
        assertEquals("90+", RTPCostMetricsCharts.heapPressureBucket(100L, 100L));
    }

    @Test
    @DisplayName("tickBudgetBucket: NaN/negative -> unknown; fractions 0..1 stop boundaries")
    void tickBudgetBucket_boundaries() {
        assertEquals("unknown", RTPCostMetricsCharts.tickBudgetBucket(Double.NaN));
        assertEquals("unknown", RTPCostMetricsCharts.tickBudgetBucket(-0.01));
        assertEquals("<25", RTPCostMetricsCharts.tickBudgetBucket(0.0));
        assertEquals("<25", RTPCostMetricsCharts.tickBudgetBucket(0.24));
        assertEquals("25-50", RTPCostMetricsCharts.tickBudgetBucket(0.25));
        assertEquals("50-75", RTPCostMetricsCharts.tickBudgetBucket(0.50));
        assertEquals("75-90", RTPCostMetricsCharts.tickBudgetBucket(0.75));
        assertEquals("90+", RTPCostMetricsCharts.tickBudgetBucket(0.90));
        assertEquals("90+", RTPCostMetricsCharts.tickBudgetBucket(1.50));
    }

    @Test
    @DisplayName("foliaRegionCountBucket: non-Folia 0 -> 0; bucketed thresholds")
    void foliaRegionCountBucket_boundaries() {
        assertEquals("0", RTPCostMetricsCharts.foliaRegionCountBucket(0));
        assertEquals("0", RTPCostMetricsCharts.foliaRegionCountBucket(-3));
        assertEquals("1", RTPCostMetricsCharts.foliaRegionCountBucket(1));
        assertEquals("2-4", RTPCostMetricsCharts.foliaRegionCountBucket(2));
        assertEquals("2-4", RTPCostMetricsCharts.foliaRegionCountBucket(4));
        assertEquals("5-16", RTPCostMetricsCharts.foliaRegionCountBucket(5));
        assertEquals("5-16", RTPCostMetricsCharts.foliaRegionCountBucket(16));
        assertEquals("17-64", RTPCostMetricsCharts.foliaRegionCountBucket(17));
        assertEquals("17-64", RTPCostMetricsCharts.foliaRegionCountBucket(64));
        assertEquals("65+", RTPCostMetricsCharts.foliaRegionCountBucket(65));
        assertEquals("65+", RTPCostMetricsCharts.foliaRegionCountBucket(10_000));
    }

    @Test
    @DisplayName("pendingTeleportsBucket: 0 -> 0; identical staircase to queueDepth")
    void pendingTeleportsBucket_boundaries() {
        assertEquals("0", RTPCostMetricsCharts.pendingTeleportsBucket(0));
        assertEquals("0", RTPCostMetricsCharts.pendingTeleportsBucket(-1));
        assertEquals("1-5", RTPCostMetricsCharts.pendingTeleportsBucket(1));
        assertEquals("1-5", RTPCostMetricsCharts.pendingTeleportsBucket(5));
        assertEquals("6-20", RTPCostMetricsCharts.pendingTeleportsBucket(6));
        assertEquals("6-20", RTPCostMetricsCharts.pendingTeleportsBucket(20));
        assertEquals("21-100", RTPCostMetricsCharts.pendingTeleportsBucket(21));
        assertEquals("21-100", RTPCostMetricsCharts.pendingTeleportsBucket(100));
        assertEquals("100+", RTPCostMetricsCharts.pendingTeleportsBucket(101));
    }
}
