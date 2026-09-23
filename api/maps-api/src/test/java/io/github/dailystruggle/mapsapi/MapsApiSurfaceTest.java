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
                io.github.dailystruggle.mapsapi.model.DualSparkline.class.getName(),
                io.github.dailystruggle.mapsapi.model.CompositeRegionModel.class.getName());
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

        RegionBadLocations same = new RegionBadLocations("r", 1, 1, new byte[1]);
        RegionBadLocations diff = new RegionBadLocations("r2", 1, 1, new byte[1]);
        assertTrue(ok.equals(ok));
        assertEquals(ok, same);
        org.junit.jupiter.api.Assertions.assertNotEquals(ok, diff);
        assertFalse(ok.equals(null));
        assertFalse(ok.equals("str"));
        assertEquals(ok.hashCode(), same.hashCode());
        org.junit.jupiter.api.Assertions.assertNotNull(ok.toString());
    }

    @Test
    @DisplayName("TimeSeries defensively copies its samples array on construction and on accessor read")
    void timeSeriesDefensiveCopy() {
        double[] external = {1.0, 2.0, 3.0};
        TimeSeries ts = new TimeSeries("tps", external, 0.0, 20.0);

        external[0] = 99.0;
        assertEquals(1.0, ts.sampleAt(0));

        assertNotSame(ts.samples(), ts.samples());

        TimeSeries same = new TimeSeries("tps", new double[]{1.0, 2.0, 3.0}, 0.0, 20.0);
        TimeSeries diff = new TimeSeries("mspt", new double[]{1.0, 2.0, 3.0}, 0.0, 20.0);
        assertTrue(ts.equals(ts));
        assertEquals(ts, same);
        org.junit.jupiter.api.Assertions.assertNotEquals(ts, diff);
        assertFalse(ts.equals(null));
        assertFalse(ts.equals("str"));
        assertEquals(ts.hashCode(), same.hashCode());
        org.junit.jupiter.api.Assertions.assertNotNull(ts.toString());
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

        // Validation paths
        assertThrows(NullPointerException.class, () -> new CategoryDistribution(null, counts));
        assertThrows(NullPointerException.class, () -> new CategoryDistribution(labels, null));
        assertThrows(IllegalArgumentException.class, () -> new CategoryDistribution(List.of("a"), List.of(1L, 2L)));
        assertThrows(NullPointerException.class, () -> new CategoryDistribution(Arrays.asList("a", null), List.of(1L, 2L)));
        assertThrows(NullPointerException.class, () -> new CategoryDistribution(List.of("a", "b"), Arrays.asList(1L, null)));
        assertThrows(IllegalArgumentException.class, () -> new CategoryDistribution(List.of("a", "b"), List.of(1L, -5L)));

        // CategoryDistribution.of(Map)
        CategoryDistribution fromMap = CategoryDistribution.of(java.util.Map.of("k1", 10L, "k2", 20L));
        assertEquals(2, fromMap.labels().size());
        assertThrows(NullPointerException.class, () -> CategoryDistribution.of(null));
    }

    @Test
    @DisplayName("RegionCoverage validations and side calculation")
    void regionCoverageValidations() {
        assertThrows(NullPointerException.class, () -> new RegionCoverage(null, 0, 0, 1, new byte[9]));
        assertThrows(IllegalArgumentException.class, () -> new RegionCoverage("r", 0, 0, 0, new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> new RegionCoverage("r", 0, 0, -2, new byte[9]));
        assertThrows(NullPointerException.class, () -> new RegionCoverage("r", 0, 0, 1, null));
        assertThrows(IllegalArgumentException.class, () -> new RegionCoverage("r", 0, 0, 1, new byte[8])); // expected (2*1+1)^2 = 9

        RegionCoverage valid = new RegionCoverage("r", 10, 20, 2, new byte[25]);
        assertEquals("r", valid.regionName());
        assertEquals(10, valid.centerX());
        assertEquals(20, valid.centerZ());
        assertEquals(2, valid.radius());
        assertEquals(5, valid.side());
        assertEquals(25, valid.states().length);

        RegionCoverage same = new RegionCoverage("r", 10, 20, 2, new byte[25]);
        RegionCoverage diff = new RegionCoverage("r2", 10, 20, 2, new byte[25]);
        assertTrue(valid.equals(valid));
        assertEquals(valid, same);
        org.junit.jupiter.api.Assertions.assertNotEquals(valid, diff);
        assertFalse(valid.equals(null));
        assertFalse(valid.equals("str"));
        assertEquals(valid.hashCode(), same.hashCode());
        org.junit.jupiter.api.Assertions.assertNotNull(valid.toString());
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

        Heatmap2D h1 = new Heatmap2D(2, 2, new double[]{1, 2, 3, 4}, 0.0, 5.0);
        Heatmap2D same = new Heatmap2D(2, 2, new double[]{1, 2, 3, 4}, 0.0, 5.0);
        Heatmap2D diff = new Heatmap2D(2, 2, new double[]{1, 2, 3, 5}, 0.0, 5.0);
        assertTrue(h1.equals(h1));
        assertEquals(h1, same);
        org.junit.jupiter.api.Assertions.assertNotEquals(h1, diff);
        assertFalse(h1.equals(null));
        assertFalse(h1.equals("str"));
        assertEquals(h1.hashCode(), same.hashCode());
        org.junit.jupiter.api.Assertions.assertNotNull(h1.toString());
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
    @DisplayName("RegionBiomesRgb validation, equals, hashCode and toString")
    void regionBiomesRgbValidations() {
        assertThrows(NullPointerException.class, () -> new RegionBiomesRgb(null, 2, 2, new int[4], new byte[4]));
        assertThrows(IllegalArgumentException.class, () -> new RegionBiomesRgb("r", 0, 2, new int[0], new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new RegionBiomesRgb("r", 2, 0, new int[0], new byte[0]));
        assertThrows(NullPointerException.class, () -> new RegionBiomesRgb("r", 2, 2, null, new byte[4]));
        assertThrows(NullPointerException.class, () -> new RegionBiomesRgb("r", 2, 2, new int[4], null));
        assertThrows(IllegalArgumentException.class, () -> new RegionBiomesRgb("r", 2, 2, new int[3], new byte[4]));
        assertThrows(IllegalArgumentException.class, () -> new RegionBiomesRgb("r", 2, 2, new int[4], new byte[3]));

        RegionBiomesRgb r1 = new RegionBiomesRgb("r", 2, 2, new int[]{1, 2, 3, 4}, new byte[]{0, 1, 2, 0});
        RegionBiomesRgb same = new RegionBiomesRgb("r", 2, 2, new int[]{1, 2, 3, 4}, new byte[]{0, 1, 2, 0});
        RegionBiomesRgb diffName = new RegionBiomesRgb("other", 2, 2, new int[]{1, 2, 3, 4}, new byte[]{0, 1, 2, 0});
        RegionBiomesRgb diffRgb = new RegionBiomesRgb("r", 2, 2, new int[]{9, 2, 3, 4}, new byte[]{0, 1, 2, 0});
        RegionBiomesRgb diffMask = new RegionBiomesRgb("r", 2, 2, new int[]{1, 2, 3, 4}, new byte[]{2, 1, 2, 0});

        assertTrue(r1.equals(r1));
        assertEquals(r1, same);
        org.junit.jupiter.api.Assertions.assertNotEquals(r1, diffName);
        org.junit.jupiter.api.Assertions.assertNotEquals(r1, diffRgb);
        org.junit.jupiter.api.Assertions.assertNotEquals(r1, diffMask);
        assertFalse(r1.equals(null));
        assertFalse(r1.equals("str"));
        assertEquals(r1.hashCode(), same.hashCode());
        org.junit.jupiter.api.Assertions.assertNotNull(r1.toString());
        assertNotSame(r1.rgb(), r1.rgb());
        assertNotSame(r1.mask(), r1.mask());
    }

    @Test
    @DisplayName("RegionBadLocations validation, equals, hashCode and toString")
    void regionBadLocationsValidations() {
        assertThrows(NullPointerException.class, () -> new RegionBadLocations(null, 2, 2, new byte[4]));
        assertThrows(IllegalArgumentException.class, () -> new RegionBadLocations("r", 0, 2, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new RegionBadLocations("r", 2, 0, new byte[0]));
        assertThrows(NullPointerException.class, () -> new RegionBadLocations("r", 2, 2, null));
        assertThrows(IllegalArgumentException.class, () -> new RegionBadLocations("r", 2, 2, new byte[3]));

        RegionBadLocations b1 = new RegionBadLocations("r", 2, 2, new byte[]{1, 2, 3, 4});
        RegionBadLocations same = new RegionBadLocations("r", 2, 2, new byte[]{1, 2, 3, 4});
        RegionBadLocations diffName = new RegionBadLocations("diff", 2, 2, new byte[]{1, 2, 3, 4});
        RegionBadLocations diffPalette = new RegionBadLocations("r", 2, 2, new byte[]{1, 2, 3, 5});

        assertTrue(b1.equals(b1));
        assertEquals(b1, same);
        org.junit.jupiter.api.Assertions.assertNotEquals(b1, diffName);
        org.junit.jupiter.api.Assertions.assertNotEquals(b1, diffPalette);
        assertFalse(b1.equals(null));
        assertFalse(b1.equals("str"));
        assertEquals(b1.hashCode(), same.hashCode());
        org.junit.jupiter.api.Assertions.assertNotNull(b1.toString());
        assertNotSame(b1.palette(), b1.palette());
    }

    @Test
    @DisplayName("TimeSeries validation, equals, hashCode and toString")
    void timeSeriesValidations() {
        assertThrows(NullPointerException.class, () -> new TimeSeries(null, new double[]{1.0}, 0.0, 10.0));
        assertThrows(NullPointerException.class, () -> new TimeSeries("TPS", null, 0.0, 10.0));
        assertThrows(IllegalArgumentException.class, () -> new TimeSeries("TPS", new double[0], 0.0, 10.0));
        assertThrows(IllegalArgumentException.class, () -> new TimeSeries("TPS", new double[]{1.0}, 10.0, 10.0));
        assertThrows(IllegalArgumentException.class, () -> new TimeSeries("TPS", new double[]{1.0}, 15.0, 10.0));

        TimeSeries ts = new TimeSeries("TPS", new double[]{18.5, 20.0}, 0.0, 20.0);
        assertEquals(18.5, ts.sampleAt(0));
        assertEquals(20.0, ts.sampleAt(1));
        assertNotSame(ts.samples(), ts.samples());

        TimeSeries same = new TimeSeries("TPS", new double[]{18.5, 20.0}, 0.0, 20.0);
        TimeSeries diffLabel = new TimeSeries("MSPT", new double[]{18.5, 20.0}, 0.0, 20.0);
        TimeSeries diffSamples = new TimeSeries("TPS", new double[]{19.0, 20.0}, 0.0, 20.0);

        assertTrue(ts.equals(ts));
        assertEquals(ts, same);
        org.junit.jupiter.api.Assertions.assertNotEquals(ts, diffLabel);
        org.junit.jupiter.api.Assertions.assertNotEquals(ts, diffSamples);
        assertFalse(ts.equals(null));
        assertFalse(ts.equals("str"));
        assertEquals(ts.hashCode(), same.hashCode());
        org.junit.jupiter.api.Assertions.assertNotNull(ts.toString());
    }

    @Test
    @DisplayName("DualSparkline equals, hashCode, and toString branches")
    void dualSparklineBranches() {
        double[] sA = new double[]{10.0, 20.0};
        double[] sB = new double[]{100.0, 200.0};
        io.github.dailystruggle.mapsapi.model.DualSparkline ds1 =
                new io.github.dailystruggle.mapsapi.model.DualSparkline("A", sA, 0.0, 50.0, "B", sB, 0.0, 500.0);
        io.github.dailystruggle.mapsapi.model.DualSparkline same =
                new io.github.dailystruggle.mapsapi.model.DualSparkline("A", sA, 0.0, 50.0, "B", sB, 0.0, 500.0);
        io.github.dailystruggle.mapsapi.model.DualSparkline diffA =
                new io.github.dailystruggle.mapsapi.model.DualSparkline("DiffA", sA, 0.0, 50.0, "B", sB, 0.0, 500.0);
        io.github.dailystruggle.mapsapi.model.DualSparkline diffB =
                new io.github.dailystruggle.mapsapi.model.DualSparkline("A", sA, 0.0, 50.0, "DiffB", sB, 0.0, 500.0);

        assertTrue(ds1.equals(ds1));
        assertEquals(ds1, same);
        org.junit.jupiter.api.Assertions.assertNotEquals(ds1, diffA);
        org.junit.jupiter.api.Assertions.assertNotEquals(ds1, diffB);
        assertFalse(ds1.equals(null));
        assertFalse(ds1.equals("str"));
        assertEquals(ds1.hashCode(), same.hashCode());
        org.junit.jupiter.api.Assertions.assertNotNull(ds1.toString());
        assertEquals(2, ds1.sampleCount());
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
