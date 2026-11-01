package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class ReqRtpObs001MetricsSnapshotTest {

    @Test
    void tickBudgetUtilisation_isMsptOver50() {
        MetricsSnapshot s = build(25.0);
        assertEquals(0.5, s.tickBudgetUtilisation, 0.0001);
    }

    @Test
    void tickBudgetUtilisation_isNaN_whenMsptUnsampled() {
        MetricsSnapshot s = build(MetricsSnapshot.UNSAMPLED);
        assertTrue(Double.isNaN(s.tickBudgetUtilisation));
    }

    @Test
    void heapMb_helpersConvertBytes() {
        MetricsSnapshot s = new MetricsSnapshot(
                20.0, 20.0, 20.0, 25.0,
                3, 100,
                /* heapUsedBytes */ 4L * 1024L * 1024L,
                /* heapMaxBytes  */ 8L * 1024L * 1024L,
                0L,
                Collections.emptyList());
        assertEquals(4L, s.heapUsedMb());
        assertEquals(8L, s.heapMaxMb());
    }

    @Test
    void toString_includesHostRuntimeFields() {
        String repr = build(25.0).toString();
        for (String field : new String[]{
                "tps1m", "tps5m", "tps15m", "mspt", "tickBudgetUtilisation",
                "playerCount", "softCap", "heapUsedMb", "heapMaxMb",
                "takenAtEpochMs"}) {
            assertTrue(repr.contains(field), "missing field in toString: " + field);
        }
    }

    private static MetricsSnapshot build(double mspt) {
        return new MetricsSnapshot(
                20.0, 20.0, 20.0, mspt,
                1, 20,
                1024L, 2048L,
                System.currentTimeMillis(),
                Collections.emptyList());
    }
}
