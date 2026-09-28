package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.image.ImageMapCanvas;
import io.github.dailystruggle.mapsapi.model.ChartModel;
import io.github.dailystruggle.mapsapi.model.SelectionHeatmap;
import io.github.dailystruggle.mapsapi.render.ChartRenderer;
import io.github.dailystruggle.mapsapi.render.SelectionHeatmapRenderer;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    }

    @Test
    @DisplayName("resolve builds SelectionHeatmap with density and points")
    void resolve_happyPath() throws Exception {
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
