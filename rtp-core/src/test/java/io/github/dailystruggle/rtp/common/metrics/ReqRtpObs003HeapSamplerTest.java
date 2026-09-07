package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.rtp.common.tools.HeapPressureMonitor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReqRtpObs003HeapSamplerTest {

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

    @Test
    void tenuredBytes_isSensible() {
        long tenuredUsed = HeapSampler.tenuredUsedBytes();
        long tenuredMax = HeapSampler.tenuredMaxBytes();
        assertTrue(tenuredUsed >= 0L, "expected non-negative tenured-used bytes, got " + tenuredUsed);
        assertTrue(tenuredMax >= 0L, "expected non-negative tenured-max bytes, got " + tenuredMax);
    }

    @Test
    void heapPressureMonitor_evaluatesWithoutError() {
        // underPressure should sample and return boolean without throwing
        boolean pressure = HeapPressureMonitor.underPressure();
        double percent = HeapPressureMonitor.lastUsedPercent();
        assertTrue(percent >= 0.0, "expected non-negative used percent, got " + percent);
    }
}
