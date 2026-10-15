package io.github.dailystruggle.metrics.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Metrics facade & static registry (METRICS_PLAN.md)")
class MetricsRegistryTest {

    @BeforeEach
    @AfterEach
    void reset() {
        Metrics.resetRegistryForTesting();
    }

    @Test
    @DisplayName("NOOP metrics produce an all-sentinel snapshot")
    void noopSnapshot() {
        MetricsSnapshot s = Metrics.NOOP.snapshot();
        assertTrue(Double.isNaN(s.tps1m));
        assertTrue(Double.isNaN(s.mspt));
        assertEquals(0, s.playerCount);
        assertEquals(0, s.softCap);
        assertEquals(0L, s.heapUsedBytes);
        assertTrue(s.foliaRegions.isEmpty());
        assertTrue(s.takenAtEpochMs > 0);
    }

    @Test
    @DisplayName("MetricsBinding.NOOP returns documented sentinels")
    void bindingNoopDefaults() {
        MetricsBinding b = MetricsBinding.NOOP;
        assertTrue(Double.isNaN(b.tps1m()));
        assertTrue(Double.isNaN(b.tps5m()));
        assertTrue(Double.isNaN(b.tps15m()));
        assertTrue(Double.isNaN(b.mspt()));
        assertEquals(0, b.playerCount());
        assertEquals(0, b.softCap());
        assertEquals(0, b.chunkLoadBacklog());
        assertEquals(-1, b.databaseLatencyMs());
        assertTrue(b.foliaRegions().isEmpty());
    }

    @Test
    @DisplayName("ADR-087 regional circuit breaker: region readings default to the server values on single-loop bindings")
    void regionDefaultsFollowServerValues() {
        java.util.UUID world = java.util.UUID.randomUUID();
        MetricsBinding b = new MetricsBinding() {
            @Override public double tps1m() { return 18.5; }
            @Override public double mspt() { return 42.0; }
        };
        assertFalse(b.regionalTicking());
        assertEquals(18.5, b.regionTps1m(world, 10, -10));
        assertEquals(42.0, b.regionMspt(world, 10, -10));
        assertFalse(MetricsBinding.NOOP.regionalTicking());
        assertTrue(Double.isNaN(MetricsBinding.NOOP.regionTps1m(world, 0, 0)));
        assertTrue(Double.isNaN(MetricsBinding.NOOP.regionMspt(world, 0, 0)));
    }

    @Test
    @DisplayName("currentBinding defaults to NOOP before any registration")
    void currentBindingDefault() {
        assertSame(MetricsBinding.NOOP, Metrics.currentBinding());
    }

    @Test
    @DisplayName("registerBinding installs the binding and returns the prior one")
    void registerBinding() {
        MetricsBinding first = new MetricsBinding() {
            @Override public int playerCount() { return 5; }
        };
        MetricsBinding prev = Metrics.registerBinding(first);
        assertSame(MetricsBinding.NOOP, prev);
        assertSame(first, Metrics.currentBinding());
        assertEquals(5, Metrics.currentBinding().playerCount());

        // Displacing a non-NOOP binding returns the displaced one (last-writer-wins).
        MetricsBinding second = new MetricsBinding() {};
        assertSame(first, Metrics.registerBinding(second));
        assertSame(second, Metrics.currentBinding());
    }

    @Test
    @DisplayName("registerBinding(null) clears back to NOOP")
    void registerBindingNullClears() {
        MetricsBinding b = new MetricsBinding() {};
        Metrics.registerBinding(b);
        MetricsBinding prev = Metrics.registerBinding(null);
        assertSame(b, prev);
        assertSame(MetricsBinding.NOOP, Metrics.currentBinding());
    }

    @Test
    @DisplayName("registerExtension is additive; null suppliers are ignored")
    void registerExtension() {
        assertTrue(Metrics.registeredExtensions().isEmpty());

        Supplier<MetricsExtension<?>> s1 = () -> new Ext();
        Metrics.registerExtension(s1);
        Metrics.registerExtension(null); // ignored
        List<Supplier<? extends MetricsExtension<?>>> registered = Metrics.registeredExtensions();
        assertEquals(1, registered.size());
        assertNotNull(registered.get(0).get());
    }

    @Test
    @DisplayName("resetRegistryForTesting clears both binding and extensions")
    void resetClearsAll() {
        Metrics.registerBinding(new MetricsBinding() {});
        Metrics.registerExtension(Ext::new);
        Metrics.resetRegistryForTesting();
        assertSame(MetricsBinding.NOOP, Metrics.currentBinding());
        assertTrue(Metrics.registeredExtensions().isEmpty());
    }

    @Test
    @DisplayName("MetricsBinding default worst readings and wraps check")
    void bindingDefaultsWorstAndWraps() {
        MetricsBinding b = new MetricsBinding() {
            @Override public double tps1m() { return 19.2; }
            @Override public double mspt() { return 15.0; }
        };
        assertEquals(19.2, b.worstTps1m());
        assertEquals(15.0, b.worstMspt());
        assertTrue(b.wraps(b));
        assertFalse(b.wraps(MetricsBinding.NOOP));
    }

    static final class Ext implements MetricsExtension<Ext> {
    }
}
