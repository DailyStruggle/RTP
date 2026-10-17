package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.metrics.api.FoliaRegionSample;
import io.github.dailystruggle.metrics.api.MetricsBinding;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.common.RTP;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("performance.yml minTPS / maxMSPT background-work gate")
class ServerHealthGateTest {

    @BeforeEach
    void setUp() {
        ServerHealthGate.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        ServerHealthGate.resetForTesting();
        RTP.metrics.setBinding(null);
    }

    private static final UUID WORLD = UUID.randomUUID();

    /** Folia-shaped binding: chunk (0,0) is a 1.4 TPS region, chunk (64,0) is healthy, the rest unsampled. */
    private static MetricsBinding regional(double meanTps) {
        return new MetricsBinding() {
            @Override public boolean regionalTicking() { return true; }
            @Override public double tps1m() { return meanTps; }
            @Override public double mspt() { return 700.0; }
            @Override public double worstTps1m() { return 1.4; }
            @Override public double worstMspt() { return 700.0; }
            @Override public double regionTps1m(UUID worldId, int chunkX, int chunkZ) {
                if (!WORLD.equals(worldId)) return MetricsSnapshot.UNSAMPLED;
                if (chunkX == 0) return 1.4;
                if (chunkX == 64) return 20.0;
                return MetricsSnapshot.UNSAMPLED;
            }
            @Override public double regionMspt(UUID worldId, int chunkX, int chunkZ) {
                return WORLD.equals(worldId) && chunkX == 0 ? 700.0 : MetricsSnapshot.UNSAMPLED;
            }
        };
    }

    @Test
    @DisplayName("TPS below minTPS pauses; recovery needs minTPS + hysteresis")
    void tpsGateWithHysteresis() {
        assertFalse(ServerHealthGate.evaluate(false, 19.5, 20.0, 19.0, 0.0));
        assertTrue(ServerHealthGate.evaluate(false, 18.9, 20.0, 19.0, 0.0));
        assertTrue(ServerHealthGate.evaluate(true, 19.2, 20.0, 19.0, 0.0), "still inside hysteresis band");
        assertFalse(ServerHealthGate.evaluate(true, 19.6, 20.0, 19.0, 0.0));
    }

    @Test
    @DisplayName("MSPT above maxMSPT pauses even at a clamped 20 TPS; recovery needs maxMSPT - hysteresis")
    void msptGateWithHysteresis() {
        assertFalse(ServerHealthGate.evaluate(false, 20.0, 44.0, 19.0, 45.0));
        assertTrue(ServerHealthGate.evaluate(false, 20.0, 46.0, 19.0, 45.0));
        assertTrue(ServerHealthGate.evaluate(true, 20.0, 42.0, 19.0, 45.0));
        assertFalse(ServerHealthGate.evaluate(true, 20.0, 39.0, 19.0, 45.0));
    }

    @Test
    @DisplayName("zero thresholds disable each half; unsampled (NaN) metrics fail open")
    void disabledAndUnsampled() {
        assertFalse(ServerHealthGate.evaluate(false, 5.0, 200.0, 0.0, 0.0));
        assertFalse(ServerHealthGate.evaluate(false, Double.NaN, Double.NaN, 19.0, 45.0));
        assertFalse(ServerHealthGate.evaluate(true, Double.NaN, Double.NaN, 19.0, 45.0));
    }

    @Test
    @DisplayName("minTPS of 20 resumes at 20, never at an unreachable 20.5")
    void resumeThresholdCappedAtTwenty() {
        assertFalse(ServerHealthGate.evaluate(true, 20.0, Double.NaN, 20.0, 0.0));
    }

    @Test
    @DisplayName("shouldPause samples the worst-loop readings and caches the decision")
    void shouldPauseUsesReadings() {
        ServerHealthGate.tpsOverride = () -> 12.0;
        ServerHealthGate.msptOverride = () -> 80.0;
        assertTrue(ServerHealthGate.shouldPause());
        assertEquals(12.0, ServerHealthGate.lastTps());
        assertEquals(80.0, ServerHealthGate.lastMspt());

        // Within the sample interval the cached decision holds.
        ServerHealthGate.tpsOverride = () -> 20.0;
        ServerHealthGate.msptOverride = () -> 10.0;
        assertTrue(ServerHealthGate.shouldPause());
    }

    @Test
    @DisplayName("ADR-087 regional circuit breaker: global gate on a regional binding reads the mean TPS and ignores MSPT")
    void globalGateOnRegionalBindingUsesMean() {
        RTP.metrics.setBinding(regional(19.6));
        assertFalse(ServerHealthGate.shouldPause(), "one 1.4 TPS region must not pause healthy regions");
        assertEquals(19.6, ServerHealthGate.lastTps());
        assertTrue(Double.isNaN(ServerHealthGate.lastMspt()), "regional tick intervals are not work time");

        ServerHealthGate.resetForTesting();
        RTP.metrics.setBinding(regional(12.0));
        assertTrue(ServerHealthGate.shouldPause(), "a low mean still pauses server-wide");
    }

    @Test
    @DisplayName("ADR-087 regional circuit breaker: slow region is unhealthy; healthy and unsampled regions are not")
    void regionUnhealthyPerCell() {
        RTP.metrics.setBinding(regional(19.6));
        assertTrue(ServerHealthGate.regionUnhealthy(WORLD, 0, 0));
        assertFalse(ServerHealthGate.regionUnhealthy(WORLD, 64, 0));
        assertFalse(ServerHealthGate.regionUnhealthy(WORLD, 640, 640), "unsampled region fails open");
        assertFalse(ServerHealthGate.regionUnhealthy(UUID.randomUUID(), 0, 0), "unknown world fails open");
    }

    @Test
    @DisplayName("ADR-087 regional circuit breaker: region MSPT overrun alone marks the region unhealthy")
    void regionMsptOverrun() {
        RTP.metrics.setBinding(new MetricsBinding() {
            @Override public boolean regionalTicking() { return true; }
            @Override public double regionTps1m(UUID w, int x, int z) { return 20.0; }
            @Override public double regionMspt(UUID w, int x, int z) { return 80.0; }
        });
        assertTrue(ServerHealthGate.regionUnhealthy(WORLD, 0, 0));
    }

    @Test
    @DisplayName("ADR-087 regional circuit breaker: single-loop bindings answer regionUnhealthy with shouldPause")
    void regionUnhealthyEqualsShouldPauseOnSingleLoop() {
        RTP.metrics.setBinding(new MetricsBinding() {
            @Override public double tps1m() { return 12.0; }
            @Override public double mspt() { return 30.0; }
        });
        assertTrue(ServerHealthGate.regionUnhealthy(WORLD, 0, 0));
        assertEquals(ServerHealthGate.shouldPause(), ServerHealthGate.regionUnhealthy(WORLD, 64, 0));

        ServerHealthGate.resetForTesting();
        RTP.metrics.setBinding(new MetricsBinding() {
            @Override public double tps1m() { return 20.0; }
            @Override public double mspt() { return 30.0; }
        });
        assertFalse(ServerHealthGate.regionUnhealthy(WORLD, 0, 0));
        assertEquals(ServerHealthGate.shouldPause(), ServerHealthGate.regionUnhealthy(WORLD, 0, 0));
    }

    @Test
    @DisplayName("formatRegionalDetail formats samples with sample count and age")
    void formatRegionalDetailFormatsSamples() {
        assertEquals("", ServerHealthGate.formatRegionalDetail(null));
        assertEquals("", ServerHealthGate.formatRegionalDetail(List.of()));

        FoliaRegionSample s1 = new FoliaRegionSample("region-0", 18.5, 25.0, 0, 0, 10L, 5000L);
        FoliaRegionSample s2 = new FoliaRegionSample("region-1", 20.0, 10.0, 0, 0, 1200L, 60000L);
        String formatted = ServerHealthGate.formatRegionalDetail(List.of(s1, s2));
        assertTrue(formatted.contains("region-0: tps=18.5, n=10, age=5s"));
        assertTrue(formatted.contains("region-1: tps=20.0, n=1200, age=60s"));
    }
}
