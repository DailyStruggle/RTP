package io.github.dailystruggle.rtp.common.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReqRtpObs002PipelineHistogramTest {

    @Test
    void mean_returnsNaN_whenNoSamples() {
        PipelineHistogram h = new PipelineHistogram();
        assertTrue(Double.isNaN(h.mean()));
        assertEquals(0, h.sampleCount());
        assertEquals(0L, h.totalRecorded());
    }

    @Test
    void mean_arithmeticOverRecordedSamples() {
        PipelineHistogram h = new PipelineHistogram();
        h.record(10);
        h.record(20);
        h.record(30);
        assertEquals(3, h.sampleCount());
        assertEquals(20.0, h.mean(), 0.0001);
    }

    @Test
    void record_clampsNegativeToZero() {
        PipelineHistogram h = new PipelineHistogram();
        h.record(-5);
        h.record(10);
        assertEquals(5.0, h.mean(), 0.0001);
    }

    @Test
    void record_overCapacity_keepsRollingWindow() {
        PipelineHistogram h = new PipelineHistogram();
        // Fill ring with 1s, then overwrite with 100s
        for (int i = 0; i < PipelineHistogram.CAPACITY; i++) h.record(1);
        for (int i = 0; i < PipelineHistogram.CAPACITY; i++) h.record(100);
        assertEquals(PipelineHistogram.CAPACITY, h.sampleCount());
        assertEquals(2L * PipelineHistogram.CAPACITY, h.totalRecorded());
        assertEquals(100.0, h.mean(), 0.0001);
    }
}
