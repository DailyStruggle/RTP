package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.metrics.api.Metrics;
import io.github.dailystruggle.metrics.api.MetricsBinding;
import io.github.dailystruggle.metrics.api.MetricsExtension;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-plugin static registry tests for {@link Metrics}.
 *
 * <p>Verifies registration semantics, binding mirroring, and extension folding.
 */
class MetricsRegistryTest {

    @BeforeEach
    void clean() {
        Metrics.resetRegistryForTesting();
    }

    @AfterEach
    void tidy() {
        Metrics.resetRegistryForTesting();
    }

    @Test
    void defaultBindingIsNoop() {
        assertSame(MetricsBinding.NOOP, Metrics.currentBinding());
    }

    @Test
    void registerBindingIsLastWriterWinsAndReturnsPrior() {
        MetricsBinding b1 = new MetricsBinding() {
            @Override public double tps1m() { return 19.5; }
        };
        MetricsBinding b2 = new MetricsBinding() {
            @Override public double tps1m() { return 17.0; }
        };
        assertSame(MetricsBinding.NOOP, Metrics.registerBinding(b1));
        assertSame(b1, Metrics.currentBinding());
        assertSame(b1, Metrics.registerBinding(b2));
        assertSame(b2, Metrics.currentBinding());
        // Null clears back to NOOP
        assertSame(b2, Metrics.registerBinding(null));
        assertSame(MetricsBinding.NOOP, Metrics.currentBinding());
    }

    @Test
    void coreMetricsSetBindingMirrorsIntoRegistry() {
        CoreMetrics core = new CoreMetrics();
        MetricsBinding b = new MetricsBinding() {
            @Override public int playerCount() { return 7; }
        };
        core.setBinding(b);
        assertSame(b, Metrics.currentBinding());
    }

    @Test
    void registeredExtensionsAreFoldedOntoSnapshot() {
        CoreMetrics core = new CoreMetrics();
        Metrics.registerExtension(() -> new SampleExtension("hello"));
        MetricsSnapshot snap = core.snapshot();
        SampleExtension ext = snap.extension(SampleExtension.class);
        assertNotNull(ext, "Sibling extension must be present on the snapshot");
        assertEquals("hello", ext.payload);
        // RTP's built-in extension must still be present.
        assertNotNull(snap.extension(RTPMetricsExtension.class));
    }

    @Test
    void throwingExtensionSupplierDoesNotPoisonSnapshot() {
        CoreMetrics core = new CoreMetrics();
        Metrics.registerExtension(() -> { throw new RuntimeException("boom"); });
        Metrics.registerExtension(() -> new SampleExtension("survived"));
        MetricsSnapshot snap = core.snapshot();
        assertNotNull(snap, "snapshot() must never throw");
        SampleExtension ext = snap.extension(SampleExtension.class);
        assertNotNull(ext);
        assertEquals("survived", ext.payload);
    }

    @Test
    void resetRegistryClearsBindingAndExtensions() {
        Metrics.registerBinding(new MetricsBinding() {});
        Metrics.registerExtension(() -> new SampleExtension("x"));
        Metrics.resetRegistryForTesting();
        assertSame(MetricsBinding.NOOP, Metrics.currentBinding());
        assertTrue(Metrics.registeredExtensions().isEmpty());
    }

    private static final class SampleExtension implements MetricsExtension<SampleExtension> {
        final String payload;
        SampleExtension(String payload) { this.payload = payload; }
    }
}
