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
 * Surface guard for {@link ChartSpec} (REQ-RTP-MAP-006, ADR-047).
 * Validates null handling, dimension bounds, and record equality.
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
                ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE,
                ChartSpec.Kind.REGION_BIOMES,
                ChartSpec.Kind.REGION_COVERAGE,
                ChartSpec.Kind.FAIL_RATE_HEATMAP,
                ChartSpec.Kind.CACHE_OCCUPANCY,
                ChartSpec.Kind.METRIC_SPARKLINE);
        Set<ChartSpec.Kind> actual = EnumSet.allOf(ChartSpec.Kind.class);
        assertEquals(expected, actual,
                "ChartSpec.Kind must list exactly the seven values locked by CHECKLIST-metrics-to-maps.md row 1.2 "
                        + "(four Stage-3 reserved + REGION_BAD_LOCATIONS_SHAPE and REGION_BIOMES active via maps-api); "
                        + "adding or removing a value is a Stage-3 change that requires updating the resolver registry.");
    }
}
