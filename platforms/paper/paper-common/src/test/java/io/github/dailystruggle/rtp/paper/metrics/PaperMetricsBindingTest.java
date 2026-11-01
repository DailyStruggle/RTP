package io.github.dailystruggle.rtp.paper.metrics;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link PaperMetricsBinding}.
 *
 * <p>Uses the package-private supplier-injection constructor so the test exercises
 * the binding's plumbing (sentinel propagation, supplier delegation) without
 * standing up MockBukkit. The Bukkit-bound production constructor is exercised
 * indirectly by the platform smoke tests and at runtime.
 */
class PaperMetricsBindingTest {

    @Test
    void delegatesEachField_toItsSupplier() {
        PaperMetricsBinding b = new PaperMetricsBinding(
                () -> 19.95,
                () -> 19.80,
                () -> 19.50,
                () -> 50.25,
                () -> 7,
                () -> 20
        );

        assertEquals(19.95, b.tps1m(),  1e-9);
        assertEquals(19.80, b.tps5m(),  1e-9);
        assertEquals(19.50, b.tps15m(), 1e-9);
        assertEquals(50.25, b.mspt(),   1e-9);
        assertEquals(7,     b.playerCount());
        assertEquals(20,    b.softCap());
    }

    @Test
    void unsampled_sentinelsArePropagated_unchanged() {
        PaperMetricsBinding b = new PaperMetricsBinding(
                () -> MetricsSnapshot.UNSAMPLED,
                () -> MetricsSnapshot.UNSAMPLED,
                () -> MetricsSnapshot.UNSAMPLED,
                () -> MetricsSnapshot.UNSAMPLED,
                () -> 0,
                () -> 0
        );

        // UNSAMPLED is NaN: equality checks must use Double.isNaN, not ==.
        org.junit.jupiter.api.Assertions.assertTrue(Double.isNaN(b.tps1m()));
        org.junit.jupiter.api.Assertions.assertTrue(Double.isNaN(b.tps5m()));
        org.junit.jupiter.api.Assertions.assertTrue(Double.isNaN(b.tps15m()));
        org.junit.jupiter.api.Assertions.assertTrue(Double.isNaN(b.mspt()));
        assertEquals(0, b.playerCount());
    }

    @Test
    void inheritedDefaults_unchangedForUnimplementedFields() {
        // PaperMetricsBinding intentionally does not override chunkLoadBacklog /
        // databaseLatencyMs - they must keep MetricsBinding's documented sentinels.
        // softCap is now wired (see softCap_isPlumbedThroughSupplier).
        PaperMetricsBinding b = new PaperMetricsBinding(
                () -> 20.0, () -> 20.0, () -> 20.0, () -> 0.0, () -> 0, () -> 0
        );

        assertEquals(0,  b.chunkLoadBacklog());
        assertEquals(-1, b.databaseLatencyMs());
    }
}
