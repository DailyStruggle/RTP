package io.github.dailystruggle.helpers.stresstestrtp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerIdleBaselineTest {

    @Test
    @DisplayName("idle baseline: cores are CPU-ns over wall-ns, MSPT is the sample median")
    void computesCoresAndMedian() {
        // 10 s window; process used 35 s of CPU (3.5 cores), main thread 2 s (0.2 cores).
        MetricsRecorder.IdleBaseline b = Runner.idleBaseline(1_000L, 11_000L,
                0L, 35_000_000_000L, 5_000_000_000L, 7_000_000_000L,
                List.of(9.0, 3.0, 5.0));
        assertEquals(3.5, b.processCpuCores(), 1e-9);
        assertEquals(0.2, b.mainCpuCores(), 1e-9);
        assertEquals(5.0, b.msptP50(), 1e-9);
        assertEquals(10_000L, b.durationMs());
        assertTrue(b.available());
    }

    @Test
    @DisplayName("idle baseline: missing or backwards counters and no samples write -1")
    void missingCountersAreNotMeasured() {
        MetricsRecorder.IdleBaseline b = Runner.idleBaseline(0L, 5_000L,
                -1L, 10L, 20L, 10L, List.of());
        assertEquals(-1.0, b.processCpuCores());
        assertEquals(-1.0, b.mainCpuCores());
        assertEquals(-1.0, b.msptP50());
        assertFalse(b.available());
    }
}
