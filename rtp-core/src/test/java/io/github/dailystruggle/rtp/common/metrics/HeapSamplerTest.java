package io.github.dailystruggle.rtp.common.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HeapSamplerTest {

    @Test
    void heapUsedBytes_isPositive() {
        long used = HeapSampler.heapUsedBytes();
        assertTrue(used > 0L, "expected positive heap-used bytes, got " + used);
    }

    @Test
    void heapMaxBytes_isNonNegative() {
        // -Xmx may report -1 on some JVMs; the wrapper clamps to 0.
        long max = HeapSampler.heapMaxBytes();
        assertTrue(max >= 0L, "expected non-negative heap-max bytes, got " + max);
    }
}
