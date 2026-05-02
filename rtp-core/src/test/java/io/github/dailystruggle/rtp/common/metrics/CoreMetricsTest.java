package io.github.dailystruggle.rtp.common.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoreMetricsTest {

    @Test
    void snapshot_usesNoopBindingByDefault() {
        CoreMetrics m = new CoreMetrics();
        MetricsSnapshot s = m.snapshot();
        assertTrue(Double.isNaN(s.tps1m));
        assertTrue(Double.isNaN(s.mspt));
        assertEquals(0, s.playerCount);
        assertEquals(0, s.softCap);
        assertEquals(-1, s.databaseLatencyMs);
        // Heap is core-derived and always populated.
        assertTrue(s.heapUsedBytes > 0L);
    }

    @Test
    void setBinding_replacesPlatformValues() {
        CoreMetrics m = new CoreMetrics();
        m.setBinding(new MetricsBinding() {
            @Override public double tps1m() { return 19.5; }
            @Override public double mspt() { return 30.0; }
            @Override public int playerCount() { return 7; }
            @Override public int softCap() { return 50; }
            @Override public int chunkLoadBacklog() { return 4; }
            @Override public int databaseLatencyMs() { return 12; }
        });
        MetricsSnapshot s = m.snapshot();
        assertEquals(19.5, s.tps1m, 0.0001);
        assertEquals(30.0, s.mspt, 0.0001);
        assertEquals(0.6, s.tickBudgetUtilisation, 0.0001);
        assertEquals(7, s.playerCount);
        assertEquals(50, s.softCap);
        assertEquals(4, s.chunkLoadBacklog);
        assertEquals(12, s.databaseLatencyMs);
    }

    @Test
    void setBinding_nullFallsBackToNoop() {
        CoreMetrics m = new CoreMetrics();
        m.setBinding(null);
        assertSame(MetricsBinding.NOOP, m.getBinding());
        // Must not throw.
        assertNotNull(m.snapshot());
    }

    @Test
    void snapshot_picksUpHistogramMean() {
        CoreMetrics m = new CoreMetrics();
        m.pipelineHistogram().record(40);
        m.pipelineHistogram().record(60);
        MetricsSnapshot s = m.snapshot();
        assertEquals(50.0, s.avgPipelineMs, 0.0001);
    }

    @Test
    void snapshot_neverThrows_evenWithoutRtpInstance() {
        // RTP.getInstance() may be null in this test JVM (no plugin bootstrap).
        // The aggregator must still return a sane snapshot.
        CoreMetrics m = new CoreMetrics();
        assertDoesNotThrow(m::snapshot);
        assertEquals(0, m.snapshot().queueDepth);
    }
}
