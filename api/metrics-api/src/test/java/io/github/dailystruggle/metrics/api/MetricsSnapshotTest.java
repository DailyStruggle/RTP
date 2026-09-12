package io.github.dailystruggle.metrics.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MetricsSnapshot (METRICS_PLAN.md)")
class MetricsSnapshotTest {

    private MetricsSnapshot sampleSnapshot() {
        return new MetricsSnapshot(
                19.5, 19.8, 20.0, 25.0,
                12, 100,
                256L * 1024L * 1024L, 1024L * 1024L * 1024L,
                123456789L,
                List.of(new FoliaRegionSample("region-0", 19.5, 25.0, 4, 1)));
    }

    @Test
    @DisplayName("Public constructor stores host-runtime fields verbatim")
    void publicConstructorFields() {
        MetricsSnapshot s = sampleSnapshot();
        assertEquals(19.5, s.tps1m);
        assertEquals(19.8, s.tps5m);
        assertEquals(20.0, s.tps15m);
        assertEquals(25.0, s.mspt);
        assertEquals(12, s.playerCount);
        assertEquals(100, s.softCap);
        assertEquals(123456789L, s.takenAtEpochMs);
        assertEquals(1, s.foliaRegions.size());
    }

    @Test
    @DisplayName("tickBudgetUtilisation is mspt/50 when sampled, NaN otherwise")
    void tickBudgetUtilisation() {
        assertEquals(0.5, sampleSnapshot().tickBudgetUtilisation);

        MetricsSnapshot unsampled = new MetricsSnapshot(
                MetricsSnapshot.UNSAMPLED, MetricsSnapshot.UNSAMPLED, MetricsSnapshot.UNSAMPLED,
                MetricsSnapshot.UNSAMPLED, 0, 0, 0L, 0L, 1L, null);
        assertTrue(Double.isNaN(unsampled.tickBudgetUtilisation));
        assertTrue(unsampled.foliaRegions.isEmpty());
    }

    @Test
    @DisplayName("UNSAMPLED sentinel is NaN")
    void unsampledSentinel() {
        assertTrue(Double.isNaN(MetricsSnapshot.UNSAMPLED));
    }

    @Test
    @DisplayName("heap convenience accessors convert bytes to MB; unbounded max returns -1")
    void heapConversions() {
        MetricsSnapshot s = sampleSnapshot();
        assertEquals(256L, s.heapUsedMb());
        assertEquals(1024L, s.heapMaxMb());

        MetricsSnapshot unbounded = new MetricsSnapshot(
                1, 1, 1, 1, 0, 0, 0L, -1L, 1L, null);
        assertEquals(-1L, unbounded.heapMaxMb());
    }

    @Test
    @DisplayName("foliaRegions is defensively unmodifiable")
    void foliaRegionsUnmodifiable() {
        MetricsSnapshot s = sampleSnapshot();
        assertThrows(UnsupportedOperationException.class,
                () -> s.foliaRegions.add(new FoliaRegionSample("x", 1, 1, 0, 0)));
    }

    @Test
    @DisplayName("withExtension returns a copy carrying the payload; receiver unchanged")
    void withExtension() {
        MetricsSnapshot base = sampleSnapshot();
        SampleExtension ext = new SampleExtension(42);

        MetricsSnapshot withExt = base.withExtension(ext);
        assertNotSame(base, withExt);
        assertNull(base.extension(SampleExtension.class));
        assertSame(ext, withExt.extension(SampleExtension.class));
        // scalar fields carried across
        assertEquals(base.tps1m, withExt.tps1m);
        assertEquals(base.mspt, withExt.mspt);
    }

    @Test
    @DisplayName("withExtension replaces a prior payload of the same type")
    void withExtensionReplaces() {
        MetricsSnapshot s = sampleSnapshot()
                .withExtension(new SampleExtension(1))
                .withExtension(new SampleExtension(2));
        assertEquals(2, s.extension(SampleExtension.class).value);
    }

    @Test
    @DisplayName("withExtension rejects null")
    void withExtensionNull() {
        assertThrows(NullPointerException.class, () -> sampleSnapshot().withExtension(null));
    }

    @Test
    @DisplayName("extension(null) and unknown type return null")
    void extensionLookupEdges() {
        MetricsSnapshot s = sampleSnapshot();
        assertNull(s.extension(null));
        assertNull(s.extension(SampleExtension.class));
    }

    @Test
    @DisplayName("toString includes MB-rounded heap and extensions")
    void toStringContent() {
        String str = sampleSnapshot().withExtension(new SampleExtension(7)).toString();
        assertTrue(str.contains("heapUsedMb=256"));
        assertTrue(str.contains("MetricsSnapshot{"));
        assertTrue(str.contains("extensions="));
    }

    /** Minimal in-test extension payload. */
    static final class SampleExtension implements MetricsExtension<SampleExtension> {
        final int value;
        SampleExtension(int value) { this.value = value; }
    }
}
