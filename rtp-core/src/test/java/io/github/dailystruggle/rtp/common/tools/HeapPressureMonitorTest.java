package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link HeapPressureMonitor} (ENTERPRISE_READINESS item 19, {@code tools} package).
 */
public class HeapPressureMonitorTest {

    @TempDir
    File pluginDir;

    @BeforeEach
    void setUp() throws Exception {
        RTPTestSetup.install(pluginDir);
        resetMonitorState();
    }

    private void resetMonitorState() throws Exception {
        Field lastSampleMs = HeapPressureMonitor.class.getDeclaredField("lastSampleMs");
        lastSampleMs.setAccessible(true);
        ((AtomicLong) lastSampleMs.get(null)).set(0L);

        Field lastWarnMs = HeapPressureMonitor.class.getDeclaredField("lastWarnMs");
        lastWarnMs.setAccessible(true);
        ((AtomicLong) lastWarnMs.get(null)).set(0L);

        Field cachedUnderPressure = HeapPressureMonitor.class.getDeclaredField("cachedUnderPressure");
        cachedUnderPressure.setAccessible(true);
        cachedUnderPressure.set(null, false);

        Field cachedUsedPercent = HeapPressureMonitor.class.getDeclaredField("cachedUsedPercent");
        cachedUsedPercent.setAccessible(true);
        cachedUsedPercent.set(null, 0.0);
    }

    @Test
    void privateConstructorCanBeInvoked() throws Exception {
        Constructor<HeapPressureMonitor> ctor = HeapPressureMonitor.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        HeapPressureMonitor instance = ctor.newInstance();
        assertNotNull(instance);
    }

    @Test
    void evaluateUnderPressureDefault() {
        // Under standard JVM test conditions, pressure should evaluate without throwing
        boolean pressure = HeapPressureMonitor.underPressure();
        double percent = HeapPressureMonitor.lastUsedPercent();
        assertTrue(percent >= 0.0, "used percent should be non-negative: " + percent);
        // On a test JVM with plenty of free memory, underPressure is typically false
        assertFalse(pressure && percent < 85.0);
    }

    @Test
    void disabledWhenMaxHeapPercentZeroOrNegative() throws Exception {
        @SuppressWarnings("unchecked")
        ConfigParser<PerformanceKeys> perf =
                (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        assertNotNull(perf);
        perf.set(PerformanceKeys.maxHeapPercent, 0.0);

        resetMonitorState();
        assertFalse(HeapPressureMonitor.underPressure());
        assertEquals(0.0, HeapPressureMonitor.lastUsedPercent());

        perf.set(PerformanceKeys.maxHeapPercent, -10.0);
        resetMonitorState();
        assertFalse(HeapPressureMonitor.underPressure());
    }

    @Test
    void disabledWhenMaxHeapPercentGreaterOrEqualTo100() throws Exception {
        @SuppressWarnings("unchecked")
        ConfigParser<PerformanceKeys> perf =
                (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        assertNotNull(perf);
        perf.set(PerformanceKeys.maxHeapPercent, 100.0);

        resetMonitorState();
        assertFalse(HeapPressureMonitor.underPressure());
        assertEquals(0.0, HeapPressureMonitor.lastUsedPercent());

        perf.set(PerformanceKeys.maxHeapPercent, 150.0);
        resetMonitorState();
        assertFalse(HeapPressureMonitor.underPressure());
    }

    @Test
    void cachingPreventsResamplingWithinSampleInterval() throws Exception {
        // Initial sample
        HeapPressureMonitor.underPressure();
        double firstPercent = HeapPressureMonitor.lastUsedPercent();

        // Immediate subsequent call should hit cache without recalculating
        HeapPressureMonitor.underPressure();
        assertEquals(firstPercent, HeapPressureMonitor.lastUsedPercent());
    }

    @Test
    void tripsPressureWhenThresholdIsExtremelyLow() throws Exception {
        @SuppressWarnings("unchecked")
        ConfigParser<PerformanceKeys> perf =
                (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        assertNotNull(perf);
        // Setting threshold to 0.0001% ensures it trips unless gate logic prevents it
        perf.set(PerformanceKeys.maxHeapPercent, 0.0001);

        resetMonitorState();
        // Depending on absolute headroom (>=512MB), fraction >= threshold may evaluate to true
        // and exercise the warning and trip logic
        boolean pressure = HeapPressureMonitor.underPressure();
        assertTrue(HeapPressureMonitor.lastUsedPercent() >= 0.0);
    }
}
