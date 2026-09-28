package io.github.dailystruggle.rtp.common.visualization;

import io.github.dailystruggle.mapsapi.image.ImageMapCanvas;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FramedVisualizationExporter unit tests")
class FramedVisualizationExporterTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
    }

    @Test
    @DisplayName("FramedVisualizationExporter throws on null canvas")
    void frame_nullCanvas_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                FramedVisualizationExporter.frame(null, ChartSpec.Kind.REGION_COMPOSITE, "pipeline", null));
    }

    @Test
    @DisplayName("FramedVisualizationExporter produces framed images with sidebar and header across all visualization types")
    void frame_allKinds_producesValidDimensions() {
        ImageMapCanvas canvas = new ImageMapCanvas(256, 128);
        canvas.clear();
        canvas.setPixelRgb(10, 10, 0xFF00FF00);

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
        Region region = new Region("test-region", settings);

        ChartSpec.Kind[] kinds = new ChartSpec.Kind[]{
                ChartSpec.Kind.REGION_COMPOSITE,
                ChartSpec.Kind.REGION_BIOMES,
                ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE,
                ChartSpec.Kind.METRIC_SPARKLINE,
                ChartSpec.Kind.REGION_WALK_PATH,
                ChartSpec.Kind.SELECTION_HEATMAP
        };

        for (ChartSpec.Kind kind : kinds) {
            BufferedImage framed = FramedVisualizationExporter.frame(canvas, kind, kind.name().toLowerCase(), region);
            assertNotNull(framed, "Framed image must not be null for " + kind);

            // Framed width = 30 (left margin) + 256 (canvas) + 24 (sideGap) + 280 (sideW) + 30 (right margin) = 620
            // Framed height = 110 (top header) + 128 (canvas) + 60 (bottomMargin) = 298
            assertEquals(620, framed.getWidth(), "Framed width must include sidebar legend for " + kind);
            assertEquals(298, framed.getHeight(), "Framed height must include header and footer for " + kind);

            // Verify non-empty rendering by sampling background, border and content
            assertTrue(framed.getRGB(0, 0) != 0, "Top-left background pixel must be painted");
            assertTrue(framed.getRGB(30 + 10, 110 + 10) != 0, "Viewport content pixel must be painted");
        }
    }

    @Test
    @DisplayName("FramedVisualizationExporter gracefully handles null region")
    void frame_nullRegion_succeeds() {
        ImageMapCanvas canvas = new ImageMapCanvas(64, 64);
        BufferedImage framed = FramedVisualizationExporter.frame(
                canvas, ChartSpec.Kind.METRIC_SPARKLINE, "sparkline", null);
        assertNotNull(framed);
        assertTrue(framed.getWidth() > 64);
        assertTrue(framed.getHeight() > 64);
    }
}
