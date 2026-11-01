package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("performance.yml tick budgets: defaults, clamping and reload")
class TickBudgetTest {

    @TempDir
    File tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        TickBudget.clear();
    }

    @AfterEach
    void tearDown() {
        TickBudget.clear();
    }

    @SuppressWarnings("unchecked")
    private static ConfigParser<PerformanceKeys> perf() {
        return ActiveConfigs.get() == null ? null : (ConfigParser<PerformanceKeys>) ActiveConfigs.get().getParser(PerformanceKeys.class);
    }

    @Test
    @DisplayName("without a performance parser the code fallbacks equal the shipped yml defaults")
    void defaultsWithoutConfig() {
        Object saved = ActiveConfigs.get();
        ActiveConfigs.set(null);
        try {
            TickBudget.clear();
            assertEquals(TimeUnit.MILLISECONDS.toNanos(5), TickBudget.syncNanos());
            assertEquals(TimeUnit.MILLISECONDS.toNanos(25), TickBudget.asyncNanos());
            assertEquals(20L, TickBudget.periodTicks());
        } finally {
            ActiveConfigs.set((io.github.dailystruggle.rtp.common.configuration.Configs) saved);
        }
    }

    @Test
    @DisplayName("allotted times are clamped to [1, 25] ms so half of every tick stays free")
    void clampsToCeilingAndFloor() {
        assertEquals(TimeUnit.MILLISECONDS.toNanos(25), TickBudget.toNanos(50));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(25), TickBudget.toNanos(Long.MAX_VALUE));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(1), TickBudget.toNanos(0));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(1), TickBudget.toNanos(-7));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(10), TickBudget.toNanos(10));
    }

    @Test
    @DisplayName("refresh picks up edited syncAllottedTime / asyncAllottedTime / period")
    void refreshReadsConfig() {
        ConfigParser<PerformanceKeys> perf = perf();
        if (perf == null) return; // harness without performance.yml: covered by defaultsWithoutConfig
        perf.set(PerformanceKeys.syncAllottedTime, 7);
        perf.set(PerformanceKeys.asyncAllottedTime, 90);
        perf.set(PerformanceKeys.period, 40);
        TickBudget.refresh();

        assertEquals(TimeUnit.MILLISECONDS.toNanos(7), TickBudget.syncNanos());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(25), TickBudget.asyncNanos(), "async clamped to ceiling");
        assertEquals(40L, TickBudget.periodTicks());
    }

    @Test
    @DisplayName("REQ-RTP-F-016: allotted time settings accept explicit duration units")
    void refreshReadsDurationUnits() {
        ConfigParser<PerformanceKeys> perf = perf();
        if (perf == null) return;
        perf.set(PerformanceKeys.syncAllottedTime, "10ms");
        perf.set(PerformanceKeys.asyncAllottedTime, "10s");
        perf.set(PerformanceKeys.period, "2s");

        TickBudget.refresh();

        assertEquals(TimeUnit.MILLISECONDS.toNanos(10), TickBudget.syncNanos());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(25), TickBudget.asyncNanos(), "async budget remains capped");
        assertEquals(40L, TickBudget.periodTicks());

        perf.set(PerformanceKeys.period, "invalid");
        TickBudget.refresh();
        assertEquals(TickBudget.DEFAULT_PERIOD_TICKS, TickBudget.periodTicks());
    }
}
