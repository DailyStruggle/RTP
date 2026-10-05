package io.github.dailystruggle.rtp.common.metrics.bstats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Bucket-boundary tests for the *Runtime health* chart helpers in
 * {@link RtpBStatsCatalogue}.
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
        assertEquals(0, RtpBStatsCatalogue.scaledTps(Double.NaN));
        assertEquals(0, RtpBStatsCatalogue.scaledTps(-1.0));
        assertEquals(0, RtpBStatsCatalogue.scaledTps(0.0));
        assertEquals(1987, RtpBStatsCatalogue.scaledTps(19.87));
        assertEquals(2000, RtpBStatsCatalogue.scaledTps(20.0));
        assertEquals(2500, RtpBStatsCatalogue.scaledTps(25.0));
        assertEquals(2500, RtpBStatsCatalogue.scaledTps(99.0)); // clamped
    }

    @Test
    @DisplayName("heapPressureBucket: invalid -> unknown; 0% -> <25; boundary stops")
    void heapPressureBucket_boundaries() {
        assertEquals("unknown", RtpBStatsCatalogue.heapPressureBucket(0L, 0L));
        assertEquals("unknown", RtpBStatsCatalogue.heapPressureBucket(-1L, 100L));
        assertEquals("unknown", RtpBStatsCatalogue.heapPressureBucket(50L, -1L));
        assertEquals("<25", RtpBStatsCatalogue.heapPressureBucket(0L, 100L));
        assertEquals("<25", RtpBStatsCatalogue.heapPressureBucket(24L, 100L));
        assertEquals("25-50", RtpBStatsCatalogue.heapPressureBucket(25L, 100L));
        assertEquals("25-50", RtpBStatsCatalogue.heapPressureBucket(49L, 100L));
        assertEquals("50-75", RtpBStatsCatalogue.heapPressureBucket(50L, 100L));
        assertEquals("75-90", RtpBStatsCatalogue.heapPressureBucket(75L, 100L));
        assertEquals("90+", RtpBStatsCatalogue.heapPressureBucket(90L, 100L));
        assertEquals("90+", RtpBStatsCatalogue.heapPressureBucket(100L, 100L));
    }

    @Test
    @DisplayName("tickBudgetBucket: NaN/negative -> unknown; fractions 0..1 stop boundaries")
    void tickBudgetBucket_boundaries() {
        assertEquals("unknown", RtpBStatsCatalogue.tickBudgetBucket(Double.NaN));
        assertEquals("unknown", RtpBStatsCatalogue.tickBudgetBucket(-0.01));
        assertEquals("<25", RtpBStatsCatalogue.tickBudgetBucket(0.0));
        assertEquals("<25", RtpBStatsCatalogue.tickBudgetBucket(0.24));
        assertEquals("25-50", RtpBStatsCatalogue.tickBudgetBucket(0.25));
        assertEquals("50-75", RtpBStatsCatalogue.tickBudgetBucket(0.50));
        assertEquals("75-90", RtpBStatsCatalogue.tickBudgetBucket(0.75));
        assertEquals("90+", RtpBStatsCatalogue.tickBudgetBucket(0.90));
        assertEquals("90+", RtpBStatsCatalogue.tickBudgetBucket(1.50));
    }

    @Test
    @DisplayName("foliaRegionCountBucket: non-Folia 0 -> 0; bucketed thresholds")
    void foliaRegionCountBucket_boundaries() {
        assertEquals("0", RtpBStatsCatalogue.foliaRegionCountBucket(0));
        assertEquals("0", RtpBStatsCatalogue.foliaRegionCountBucket(-3));
        assertEquals("1", RtpBStatsCatalogue.foliaRegionCountBucket(1));
        assertEquals("2-4", RtpBStatsCatalogue.foliaRegionCountBucket(2));
        assertEquals("2-4", RtpBStatsCatalogue.foliaRegionCountBucket(4));
        assertEquals("5-16", RtpBStatsCatalogue.foliaRegionCountBucket(5));
        assertEquals("5-16", RtpBStatsCatalogue.foliaRegionCountBucket(16));
        assertEquals("17-64", RtpBStatsCatalogue.foliaRegionCountBucket(17));
        assertEquals("17-64", RtpBStatsCatalogue.foliaRegionCountBucket(64));
        assertEquals("65+", RtpBStatsCatalogue.foliaRegionCountBucket(65));
        assertEquals("65+", RtpBStatsCatalogue.foliaRegionCountBucket(10_000));
    }

    @Test
    @DisplayName("pendingTeleportsBucket: 0 -> 0; identical staircase to queueDepth")
    void pendingTeleportsBucket_boundaries() {
        assertEquals("0", RtpBStatsCatalogue.pendingTeleportsBucket(0));
        assertEquals("0", RtpBStatsCatalogue.pendingTeleportsBucket(-1));
        assertEquals("1-5", RtpBStatsCatalogue.pendingTeleportsBucket(1));
        assertEquals("1-5", RtpBStatsCatalogue.pendingTeleportsBucket(5));
        assertEquals("6-20", RtpBStatsCatalogue.pendingTeleportsBucket(6));
        assertEquals("6-20", RtpBStatsCatalogue.pendingTeleportsBucket(20));
        assertEquals("21-100", RtpBStatsCatalogue.pendingTeleportsBucket(21));
        assertEquals("21-100", RtpBStatsCatalogue.pendingTeleportsBucket(100));
        assertEquals("100+", RtpBStatsCatalogue.pendingTeleportsBucket(101));
    }
}
