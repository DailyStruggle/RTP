package io.github.dailystruggle.rtp.bukkitplatform.metrics;

import io.github.dailystruggle.metrics.api.FoliaRegionSample;
import io.github.dailystruggle.metrics.api.MetricsBinding;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SparkMetricsBinding}'s per-field merge over a native
 * delegate. Exercised through the package-private {@code SparkStats} test seam
 * so no spark jar is required on the test classpath.
 */
class SparkMetricsBindingTest {

    /** Fixed-value native binding used as the delegate. */
    private static MetricsBinding nativeDelegate() {
        return new MetricsBinding() {
            @Override public double tps1m() { return 18.0; }
            @Override public double tps5m() { return 17.0; }
            @Override public double tps15m() { return 16.0; }
            @Override public double mspt() { return 55.0; }
            @Override public int playerCount() { return 7; }
            @Override public int softCap() { return 20; }
            @Override public int chunkLoadBacklog() { return 3; }
            @Override public int databaseLatencyMs() { return 42; }
            @Override public List<FoliaRegionSample> foliaRegions() {
                return List.of(new FoliaRegionSample("world", 19.5, 51.0, 0, 0));
            }
        };
    }

    /** Spark source with constant values and a configurable availability. */
    private static SparkMetricsBinding.SparkStats spark(boolean available,
                                                        double t1, double t5, double t15,
                                                        double mspt) {
        return new SparkMetricsBinding.SparkStats() {
            @Override public boolean available() { return available; }
            @Override public double tps(int windowIdx) {
                switch (windowIdx) {
                    case 0: return t1;
                    case 1: return t5;
                    case 2: return t15;
                    default: return MetricsSnapshot.UNSAMPLED;
                }
            }
            @Override public double mspt() { return mspt; }
        };
    }

    @Test
    void spark_disabled_delegates_every_field() {
        SparkMetricsBinding b = new SparkMetricsBinding(
                nativeDelegate(),
                spark(false, 20.0, 20.0, 20.0, 50.0));

        assertFalse(b.sparkEnabled());
        assertEquals(18.0, b.tps1m(), 1e-9);
        assertEquals(17.0, b.tps5m(), 1e-9);
        assertEquals(16.0, b.tps15m(), 1e-9);
        assertEquals(55.0, b.mspt(), 1e-9);
    }

    @Test
    void spark_enabled_overrides_tps_and_mspt_only() {
        SparkMetricsBinding b = new SparkMetricsBinding(
                nativeDelegate(),
                spark(true, 19.9, 19.8, 19.7, 47.5));

        assertTrue(b.sparkEnabled());
        // spark wins for tick metrics
        assertEquals(19.9, b.tps1m(), 1e-9);
        assertEquals(19.8, b.tps5m(), 1e-9);
        assertEquals(19.7, b.tps15m(), 1e-9);
        assertEquals(47.5, b.mspt(), 1e-9);
        // native still owns the rest
        assertEquals(7, b.playerCount());
        assertEquals(20, b.softCap());
        assertEquals(3, b.chunkLoadBacklog());
        assertEquals(42, b.databaseLatencyMs());
        assertEquals(1, b.foliaRegions().size());
    }

    @Test
    void spark_enabled_but_unsampled_field_falls_back_to_delegate() {
        // tps1m sampled, tps5m/tps15m and mspt not yet sampled (NaN).
        SparkMetricsBinding b = new SparkMetricsBinding(
                nativeDelegate(),
                spark(true, 19.9,
                        MetricsSnapshot.UNSAMPLED,
                        MetricsSnapshot.UNSAMPLED,
                        MetricsSnapshot.UNSAMPLED));

        assertEquals(19.9, b.tps1m(), 1e-9); // spark value
        assertEquals(17.0, b.tps5m(), 1e-9); // delegate fallback
        assertEquals(16.0, b.tps15m(), 1e-9); // delegate fallback
        assertEquals(55.0, b.mspt(), 1e-9); // delegate fallback
    }

    @Test
    void null_delegate_is_treated_as_noop() {
        SparkMetricsBinding b = new SparkMetricsBinding(
                null,
                spark(true, 19.9, 19.8, 19.7, 47.5));

        // spark fields present
        assertEquals(19.9, b.tps1m(), 1e-9);
        // delegated fields fall to NOOP sentinels
        assertEquals(0, b.playerCount());
        assertEquals(-1, b.databaseLatencyMs());
        assertTrue(b.foliaRegions().isEmpty());
    }

    @Test
    void reflective_stats_is_disabled_when_spark_api_absent() {
        // spark API is not on the test classpath, so the production accessor
        // must self-disable rather than throw.
        ReflectiveSparkStats stats = new ReflectiveSparkStats();
        assertFalse(stats.available());
        assertTrue(Double.isNaN(stats.tps(0)));
        assertTrue(Double.isNaN(stats.mspt()));

        // And the production constructor wires it through to full delegation.
        SparkMetricsBinding b = new SparkMetricsBinding(nativeDelegate());
        assertFalse(b.sparkEnabled());
        assertEquals(18.0, b.tps1m(), 1e-9);
        assertEquals(55.0, b.mspt(), 1e-9);
    }
}
