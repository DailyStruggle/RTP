package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.metrics.api.MetricsBinding;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReqRtpObs001CoreMetricsTest {

    @Test
    void snapshot_usesNoopBindingByDefault() {
        CoreMetrics m = new CoreMetrics();
        MetricsSnapshot s = m.snapshot();
        assertTrue(Double.isNaN(s.tps1m));
        assertTrue(Double.isNaN(s.mspt));
        assertEquals(0, s.playerCount);
        assertEquals(0, s.softCap);
        RTPMetricsExtension ext = s.extension(RTPMetricsExtension.class);
        assertNotNull(ext);
        assertEquals(-1, ext.databaseLatencyMs);
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
        RTPMetricsExtension ext = s.extension(RTPMetricsExtension.class);
        assertNotNull(ext);
        assertEquals(4, ext.chunkLoadBacklog);
        assertEquals(12, ext.databaseLatencyMs);
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
        RTPMetricsExtension ext = s.extension(RTPMetricsExtension.class);
        assertNotNull(ext);
        assertEquals(50.0, ext.avgPipelineMs, 0.0001);
    }

    @Test
    void snapshot_neverThrows_evenWithoutRtpInstance() {
        // RTP.getInstance() may be null in this test JVM (no plugin bootstrap).
        // The aggregator must still return a sane snapshot.
        CoreMetrics m = new CoreMetrics();
        assertDoesNotThrow(m::snapshot);
        RTPMetricsExtension ext = m.snapshot().extension(RTPMetricsExtension.class);
        assertNotNull(ext);
        assertEquals(0, ext.queueDepth);
    }
}
