package io.github.dailystruggle.mapsapi;

import io.github.dailystruggle.mapsapi.model.CategoryDistribution;
import io.github.dailystruggle.mapsapi.model.ChartModel;
import io.github.dailystruggle.mapsapi.model.Heatmap2D;
import io.github.dailystruggle.mapsapi.model.MermaidChart;
import io.github.dailystruggle.mapsapi.model.RegionBadLocations;
import io.github.dailystruggle.mapsapi.model.RegionBiomesRgb;
import io.github.dailystruggle.mapsapi.model.RegionCoverage;
import io.github.dailystruggle.mapsapi.model.TimeSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Surface-shape assertions on the {@code maps-api} SPI: sealed {@link ChartModel}
 * permits the expected set of records, every record record-constructor
 * defensively copies its array / collection fields, and null arguments are
 * rejected with {@link NullPointerException}.
 */
@DisplayName("maps-api Stage-1 SPI surface — sealed shape + defensive copies + null rejection")
class MapsApiSurfaceTest {

    @Test
    @DisplayName("ChartModel is sealed and permits exactly the documented record subtypes")
    void chartModelIsSealedWithExpectedPermits() {
        Class<?> sealedRoot = ChartModel.class;
        assertTrue(sealedRoot.isSealed(), "ChartModel shall be sealed");
        Set<String> permitted = Stream.of(sealedRoot.getPermittedSubclasses())
                .map(Class::getName)
                .collect(Collectors.toSet());
        Set<String> expected = Set.of(
                Heatmap2D.class.getName(),
                CategoryDistribution.class.getName(),
                TimeSeries.class.getName(),
                RegionCoverage.class.getName(),
                RegionBadLocations.class.getName(),
                RegionBiomesRgb.class.getName(),
                MermaidChart.class.getName(),
                io.github.dailystruggle.mapsapi.model.DualSparkline.class.getName());
        assertEquals(expected, permitted,
                "ChartModel permits clause shall list exactly the documented record shapes");
    }

    @Test
    @DisplayName("Every permitted ChartModel subtype is a record")
    void everyPermittedSubtypeIsRecord() {
        for (Class<?> sub : ChartModel.class.getPermittedSubclasses()) {
            assertTrue(sub.isRecord(), sub.getName() + " shall be a record");
        }
    }

    @Test
    @DisplayName("Heatmap2D defensively copies its values array on construction and on accessor read")
    void heatmap2DDefensiveCopy() {
        double[] external = {0.0, 1.0, 2.0, 3.0};
        Heatmap2D model = new Heatmap2D(2, 2, external, 0.0, 3.0);

        // Mutating the source must not affect the model.
        external[0] = 99.0;
        assertEquals(0.0, model.valueAt(0, 0));

        // The accessor must return a fresh array each call.
        double[] a = model.values();
        double[] b = model.values();
        assertNotSame(a, b, "values() shall return a defensive copy each call");
        a[0] = 42.0;
        assertEquals(0.0, model.valueAt(0, 0));
    }

    @Test
    @DisplayName("RegionCoverage defensively copies its states array on construction and on accessor read")
    void regionCoverageDefensiveCopy() {
        byte[] external = new byte[9]; // radius=1 → side=3 → 9 cells
        RegionCoverage rc = new RegionCoverage("region-a", 0, 0, 1, external);

        external[0] = (byte) 3;
        byte[] readBack = rc.states();
        assertEquals(0, readBack[0]);

        byte[] x = rc.states();
        byte[] y = rc.states();
        assertNotSame(x, y);
        x[1] = (byte) 9;
        assertArrayEquals(new byte[9], rc.states());
    }

    @Test
    @DisplayName("RegionBadLocations defensively copies its palette buffer on construction and on accessor read")
    void regionBadLocationsDefensiveCopy() {
        byte[] external = new byte[4]; // 2x2 palette buffer
        external[0] = PaletteIndex.GREEN;
        external[1] = PaletteIndex.RED;
        external[2] = PaletteIndex.BLACK;
        external[3] = PaletteIndex.GREEN;
        RegionBadLocations rbl = new RegionBadLocations("region-a", 2, 2, external);

        // Mutating source must not affect snapshot.
        external[0] = PaletteIndex.RED;
        assertEquals(PaletteIndex.GREEN, rbl.palette()[0]);

        // Accessor returns a fresh array each call.
        byte[] a = rbl.palette();
        byte[] b = rbl.palette();
        assertNotSame(a, b, "palette() shall return a defensive copy each call");
        a[0] = PaletteIndex.RED;
        assertEquals(PaletteIndex.GREEN, rbl.palette()[0]);
    }

    @Test
    @DisplayName("RegionBadLocations constructor rejects null name, null buffer, non-positive dimensions, and length mismatch")
    void regionBadLocationsRejectsBadArgs() {
        assertThrows(NullPointerException.class,
                () -> new RegionBadLocations(null, 2, 2, new byte[4]));
        assertThrows(NullPointerException.class,
                () -> new RegionBadLocations("r", 2, 2, null));
        assertThrows(IllegalArgumentException.class,
                () -> new RegionBadLocations("r", 0, 2, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new RegionBadLocations("r", -1, 2, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new RegionBadLocations("r", 2, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new RegionBadLocations("r", 2, 2, new byte[3]));
        // Empty palette (zero-byte buffer) is not legal; dims must match.
        // Minimum legal size: 1x1.
        RegionBadLocations ok = new RegionBadLocations("r", 1, 1, new byte[1]);
        assertEquals(1, ok.width());
        assertEquals(1, ok.height());
    }

    @Test
    @DisplayName("TimeSeries defensively copies its samples array on construction and on accessor read")
    void timeSeriesDefensiveCopy() {
        double[] external = {1.0, 2.0, 3.0};
        TimeSeries ts = new TimeSeries("tps", external, 0.0, 20.0);

        external[0] = 99.0;
        assertEquals(1.0, ts.sampleAt(0));

        assertNotSame(ts.samples(), ts.samples());
    }

    @Test
    @DisplayName("CategoryDistribution holds an immutable view of its labels and counts")
    void categoryDistributionImmutable() {
        List<String> labels = new java.util.ArrayList<>(List.of("a", "b"));
        List<Long> counts = new java.util.ArrayList<>(List.of(1L, 2L));
        CategoryDistribution cd = new CategoryDistribution(labels, counts);

        labels.set(0, "MUTATED");
        counts.set(0, 999L);

        assertEquals("a", cd.labels().get(0));
        assertEquals(1L, cd.counts().get(0));
        assertThrows(UnsupportedOperationException.class, () -> cd.labels().add("c"));
        assertThrows(UnsupportedOperationException.class, () -> cd.counts().add(3L));
    }

    @Test
    @DisplayName("MapHandle constructor rejects null chartId and blank chartId")
    void mapHandleNullArg() {
        assertThrows(NullPointerException.class, () -> new MapHandle(null, null, 1));
        assertThrows(IllegalArgumentException.class, () -> new MapHandle("", null, 1));
        assertThrows(IllegalArgumentException.class, () -> new MapHandle("   ", null, 1));
    }

    @Test
    @DisplayName("MapAllocationRequest constructor rejects null chartId / null locking, accepts null viewer")
    void allocationRequestNullArgs() {
        assertThrows(NullPointerException.class, () ->
                new MapAllocationRequest(null, null, MapAllocationRequest.Locking.LOCKED));
        assertThrows(NullPointerException.class, () ->
                new MapAllocationRequest("c", null, null));
        assertThrows(IllegalArgumentException.class, () ->
                new MapAllocationRequest("", null, MapAllocationRequest.Locking.LOCKED));
        // viewer = null is legal (world-scoped map).
        MapAllocationRequest req = new MapAllocationRequest("c", null,
                MapAllocationRequest.Locking.EDITABLE);
        assertEquals("c", req.chartId());
    }

    @Test
    @DisplayName("Heatmap2D constructor rejects shape mismatch and inverted [min,max]")
    void heatmap2DRejectsBadShape() {
        assertThrows(IllegalArgumentException.class, () ->
                new Heatmap2D(0, 2, new double[0], 0.0, 1.0));
        assertThrows(IllegalArgumentException.class, () ->
                new Heatmap2D(2, 2, new double[3], 0.0, 1.0));
        assertThrows(IllegalArgumentException.class, () ->
                new Heatmap2D(2, 2, new double[4], 1.0, 1.0));
        assertThrows(IllegalArgumentException.class, () ->
                new Heatmap2D(2, 2, new double[4], 2.0, 1.0));
        assertThrows(NullPointerException.class, () ->
                new Heatmap2D(2, 2, null, 0.0, 1.0));
    }

    @Test
    @DisplayName("MermaidChart constructor rejects null source / title")
    void mermaidChartNullArgs() {
        assertThrows(NullPointerException.class, () -> new MermaidChart(null, "t"));
        assertThrows(NullPointerException.class, () -> new MermaidChart("flowchart LR", null));
        MermaidChart ok = new MermaidChart("flowchart LR\nA-->B", "");
        assertFalse(ok.source().isEmpty());
    }

    @Test
    @DisplayName("Every Stage-1 record is final (sealed-permits hygiene)")
    void everyRecordIsFinal() {
        for (Class<?> sub : ChartModel.class.getPermittedSubclasses()) {
            assertTrue(java.lang.reflect.Modifier.isFinal(sub.getModifiers()),
                    sub.getName() + " shall be final");
        }
    }

    /** Sanity: Arrays import not stripped. */
    @SuppressWarnings("unused")
    private static double[] _keepArraysImportAlive() {
        return Arrays.copyOf(new double[]{1.0}, 1);
    }
}
