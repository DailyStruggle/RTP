package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.image.ImageMapCanvas;
import io.github.dailystruggle.mapsapi.model.ChartModel;
import io.github.dailystruggle.mapsapi.model.SelectionHeatmap;
import io.github.dailystruggle.mapsapi.render.ChartRenderer;
import io.github.dailystruggle.mapsapi.render.SelectionHeatmapRenderer;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.metrics.LandingHeatmap;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SelectionHeatmapResolver unit tests")
class SelectionHeatmapResolverTest {

    @TempDir
    Path tempDir;

    private SelectionHeatmapResolver resolver;
    private Region testRegion;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        resolver = new SelectionHeatmapResolver();

        SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("testSquare", 32);
        shape.set(GenericMemoryShapeParams.radius, 64L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        shape.set(GenericMemoryShapeParams.centerX, 0L);
        shape.set(GenericMemoryShapeParams.centerZ, 0L);

        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                "test-region", new MockRTPWorld("test_world"), shape, vert,
                false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false
        );
        testRegion = new Region("test-region", settings);
        if (io.github.dailystruggle.rtp.common.RTP.selectionAPI != null) {
            io.github.dailystruggle.rtp.common.RTP.selectionAPI.permRegionLookup.put("test-region", testRegion);
        }
    }

    @Test
    @DisplayName("resolve throws on null spec or wrong kind")
    void resolve_validation() {
        assertThrows(ChartSpecResolver.UnresolvableChartSpecException.class, () -> resolver.resolve(null));
        assertThrows(ChartSpecResolver.UnresolvableChartSpecException.class, () ->
                resolver.resolve(ChartSpec.of(ChartSpec.Kind.REGION_BIOMES, "test-region")));
        assertThrows(ChartSpecResolver.UnresolvableChartSpecException.class, () ->
                resolver.resolve(ChartSpec.of(ChartSpec.Kind.BAD_POINTS_HEATMAP, "test-region")));
    }

    @Test
    @DisplayName("heat comes from landings in chunk units, never from bad locations or uniform candidates")
    void resolve_landingsOnly() throws Exception {
        String name = "heat-landings-" + System.nanoTime();
        SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("heatSquare", 32);
        shape.set(GenericMemoryShapeParams.radius, 64L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        shape.set(GenericMemoryShapeParams.centerX, 0L);
        shape.set(GenericMemoryShapeParams.centerZ, 0L);
        RegionSettings settings = new RegionSettings(
                name, new MockRTPWorld("test_world"), shape, new LinearAdjustor(new ArrayList<>()),
                false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        Region region = new Region(name, settings);
        io.github.dailystruggle.rtp.common.RTP.selectionAPI.permRegionLookup.put(name, region);

        // Block (520, -200) -> chunk (32, -13), three landings; block (-700, 300) -> chunk (-44, 18), one
        for (int i = 0; i < 3; i++) LandingHeatmap.record(name, 520, -200);
        LandingHeatmap.record(name, -700, 300);

        SelectionHeatmap model = (SelectionHeatmap) resolver.resolve(
                ChartSpec.of(ChartSpec.Kind.SELECTION_HEATMAP, name)).model();

        for (SelectionHeatmap.SelectionPoint p : model.points()) {
            assertNotEquals(SelectionHeatmap.SelectionType.HAZARD_DISCARD, p.type());
            assertNotEquals(SelectionHeatmap.SelectionType.CANDIDATE, p.type());
        }
        List<SelectionHeatmap.SelectionPoint> arrivals = model.points().stream()
                .filter(p -> p.type() == SelectionHeatmap.SelectionType.ARRIVAL).toList();
        assertEquals(2, arrivals.size());
        assertTrue(arrivals.contains(new SelectionHeatmap.SelectionPoint(
                32, -13, 3, SelectionHeatmap.SelectionType.ARRIVAL)));

        // Peak density sits on the 3-landing chunk, not near the centre
        double[] d = model.densityGrid();
        int peak = 0;
        for (int i = 1; i < d.length; i++) if (d[i] > d[peak]) peak = i;
        int gx = (int) ((32L - model.minX()) * (model.width() - 1) / model.boundW());
        int gz = (int) ((-13L - model.minZ()) * (model.height() - 1) / model.boundH());
        assertEquals(gx, peak % model.width());
        assertEquals(gz, peak / model.width());
    }

    @Test
    @DisplayName("resolve builds SelectionHeatmap with density and points")
    void resolve_happyPath() throws Exception {
        LandingHeatmap.record("test-region", 100, 100);
        ChartSpec spec = ChartSpec.of(ChartSpec.Kind.SELECTION_HEATMAP, "test-region");
        ChartSpecResolver.Resolution resolution = resolver.resolve(spec);

        assertNotNull(resolution);
        assertSame(SelectionHeatmapRenderer.INSTANCE, resolution.renderer());
        assertTrue(resolution.model() instanceof SelectionHeatmap);

        SelectionHeatmap model = (SelectionHeatmap) resolution.model();
        assertEquals("test-region", model.regionName());
        assertTrue(model.boundW() > 0);
        assertTrue(model.boundH() > 0);
        assertTrue(model.points().size() > 0);
        assertTrue(model.maxDensity() > 0.0);

        ImageMapCanvas canvas = new ImageMapCanvas(128, 128);
        @SuppressWarnings("unchecked")
        ChartRenderer<ChartModel> renderer = (ChartRenderer<ChartModel>) resolution.renderer();
        renderer.render(canvas, model);
        canvas.commit();

        assertEquals(128, canvas.width());
        assertEquals(128, canvas.height());
    }
}
