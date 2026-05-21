package io.github.dailystruggle.rtp.api.maps;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Surface guard for {@link ChartSpec} — REQ-RTP-MAP-006 / ADR-047 Stage 1.
 *
 * <p>Stage 1 contract:
 * <ul>
 *   <li>null-reject {@code kind} / {@code regionName};</li>
 *   <li>reject {@code tilesRows < 1} and {@code tilesCols < 1};</li>
 *   <li>reject negative {@code windowSeconds};</li>
 *   <li>equality / hashCode follow the record contract;</li>
 *   <li>{@link ChartSpec.Kind} exposes exactly the five Stage-1 / Stage-3
 *       reserved values, with {@code BAD_POINTS_HEATMAP} being the only
 *       Stage-1-active value.</li>
 * </ul>
 */
class ChartSpecSurfaceTest {

    @Test
    void nullKindRejected() {
        assertThrows(NullPointerException.class,
                () -> new ChartSpec(null, "default", null, 1, 1, null, 0));
    }

    @Test
    void nullRegionNameRejected() {
        assertThrows(NullPointerException.class,
                () -> new ChartSpec(ChartSpec.Kind.BAD_POINTS_HEATMAP, null, null, 1, 1, null, 0));
    }

    @Test
    void zeroTilesRowsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChartSpec(ChartSpec.Kind.BAD_POINTS_HEATMAP, "r", null, 0, 1, null, 0));
    }

    @Test
    void negativeTilesColsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChartSpec(ChartSpec.Kind.BAD_POINTS_HEATMAP, "r", null, 1, -1, null, 0));
    }

    @Test
    void negativeWindowRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChartSpec(ChartSpec.Kind.BAD_POINTS_HEATMAP, "r", null, 1, 1, null, -1));
    }

    @Test
    void nullableFieldsAcceptNull() {
        ChartSpec spec = new ChartSpec(
                ChartSpec.Kind.BAD_POINTS_HEATMAP, "default", null, 1, 1, null, 0);
        assertNull(spec.viewer());
        assertNull(spec.metricKey());
    }

    @Test
    void convenienceFactoryProducesSingleTileSpec() {
        ChartSpec spec = ChartSpec.of(ChartSpec.Kind.BAD_POINTS_HEATMAP, "default");
        assertEquals(1, spec.tilesRows());
        assertEquals(1, spec.tilesCols());
        assertEquals(0, spec.windowSeconds());
        assertNull(spec.viewer());
        assertNull(spec.metricKey());
    }

    @Test
    void equalityFollowsRecordContract() {
        UUID viewer = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ChartSpec a = new ChartSpec(
                ChartSpec.Kind.BAD_POINTS_HEATMAP, "r", viewer, 2, 3, "tps", 60);
        ChartSpec b = new ChartSpec(
                ChartSpec.Kind.BAD_POINTS_HEATMAP, "r", viewer, 2, 3, "tps", 60);
        ChartSpec different = new ChartSpec(
                ChartSpec.Kind.BAD_POINTS_HEATMAP, "r", viewer, 2, 3, "mspt", 60);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, different);
    }

    @Test
    void kindEnumExposesExactlyTheDocumentedValueSet() {
        Set<ChartSpec.Kind> expected = EnumSet.of(
                ChartSpec.Kind.BAD_POINTS_HEATMAP,
                ChartSpec.Kind.REGION_COVERAGE,
                ChartSpec.Kind.FAIL_RATE_HEATMAP,
                ChartSpec.Kind.CACHE_OCCUPANCY,
                ChartSpec.Kind.METRIC_SPARKLINE);
        Set<ChartSpec.Kind> actual = EnumSet.allOf(ChartSpec.Kind.class);
        assertEquals(expected, actual,
                "ChartSpec.Kind must list exactly the five values locked by CHECKLIST-metrics-to-maps.md row 1.2; "
                        + "adding or removing a value is a Stage-3 change that requires updating the resolver registry.");
    }
}
