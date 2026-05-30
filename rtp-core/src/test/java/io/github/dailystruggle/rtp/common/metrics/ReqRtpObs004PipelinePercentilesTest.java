package io.github.dailystruggle.rtp.common.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REQ-RTP-OBS-004 (ADR-053 §1): the pipeline-latency histogram exposes percentiles
 * computed on demand from the bounded sample window without mutating it or blocking
 * the wait-free write path.
 */
@DisplayName("REQ-RTP-OBS-004 pipeline-latency percentile readout")
class ReqRtpObs004PipelinePercentilesTest {

    @Test
    void emptyHistogram_returnsNaNAndZeroSamples() {
        PipelineHistogram h = new PipelineHistogram();
        assertTrue(Double.isNaN(h.percentile(50.0)));
        PipelineHistogram.Percentiles p = h.percentiles();
        assertEquals(0, p.sampleCount);
        assertTrue(Double.isNaN(p.p50));
        assertTrue(Double.isNaN(p.p99));
        assertTrue(Double.isNaN(p.min));
        assertTrue(Double.isNaN(p.max));
    }

    @Test
    void percentiles_useNearestRankOverSortedCopy() {
        PipelineHistogram h = new PipelineHistogram();
        // 1..100 ms, inserted out of order to prove sorting happens on read.
        for (int v = 100; v >= 1; v--) h.record(v);
        PipelineHistogram.Percentiles p = h.percentiles();
        assertEquals(100, p.sampleCount);
        // Nearest-rank: rank = ceil(pct/100 * n); for n=100 the value equals pct.
        assertEquals(50.0, p.p50, 0.0001);
        assertEquals(75.0, p.p75, 0.0001);
        assertEquals(90.0, p.p90, 0.0001);
        assertEquals(95.0, p.p95, 0.0001);
        assertEquals(99.0, p.p99, 0.0001);
        assertEquals(1.0, p.min, 0.0001);
        assertEquals(100.0, p.max, 0.0001);
    }

    @Test
    void percentile_clampsBelowZeroToMinAndAboveHundredToMax() {
        PipelineHistogram h = new PipelineHistogram();
        h.record(10);
        h.record(20);
        h.record(30);
        assertEquals(10.0, h.percentile(0.0), 0.0001);
        assertEquals(10.0, h.percentile(-5.0), 0.0001);
        assertEquals(30.0, h.percentile(100.0), 0.0001);
        assertEquals(30.0, h.percentile(150.0), 0.0001);
    }

    @Test
    void readingPercentiles_doesNotMutateWindow_andMeanStaysConsistent() {
        PipelineHistogram h = new PipelineHistogram();
        h.record(40);
        h.record(60);
        double meanBefore = h.mean();
        long totalBefore = h.totalRecorded();
        // Many reads must not perturb the window.
        for (int i = 0; i < 50; i++) h.percentiles();
        assertEquals(meanBefore, h.mean(), 0.0001);
        assertEquals(totalBefore, h.totalRecorded());
        assertEquals(2, h.sampleCount());
        // Nearest-rank over {40,60}: rank = ceil(0.5*2) = 1 -> sorted[0] = 40.
        assertEquals(40.0, h.percentile(50.0), 0.0001);
    }

    @Test
    void capacityIsBounded_sampleCountNeverExceedsCapacity() {
        PipelineHistogram h = new PipelineHistogram();
        for (int i = 0; i < PipelineHistogram.CAPACITY * 3; i++) h.record(i % 7);
        assertEquals(PipelineHistogram.CAPACITY, h.sampleCount());
        PipelineHistogram.Percentiles p = h.percentiles();
        assertEquals(PipelineHistogram.CAPACITY, p.sampleCount);
        assertTrue(p.min >= 0.0 && p.max <= 6.0);
    }
}
