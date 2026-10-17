package io.github.dailystruggle.rtp.common.visualization;

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

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MatrixMapping unit tests")
class MatrixMappingTest {

    @TempDir
    Path tempDir;

    private SquareOptimizedDualLayer shape;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());

        shape = new SquareOptimizedDualLayer("testSquare", 32);
        shape.set(GenericMemoryShapeParams.radius, 64L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        shape.set(GenericMemoryShapeParams.centerX, 0L);
        shape.set(GenericMemoryShapeParams.centerZ, 0L);

        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                "matrix-test-region", new MockRTPWorld("test_world"), shape, vert,
                false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false
        );
        new Region("matrix-test-region", settings);
    }

    @Test
    @DisplayName("worldToPixel and pixelToWorld conversions are consistent")
    void testCoordinateTranslation() {
        int minX = -100;
        long boundW = 200;
        int viewportW = 100;

        int px0 = MatrixMapping.worldToPixelX(minX, minX, boundW, viewportW);
        assertEquals(0, px0);

        int pxEnd = MatrixMapping.worldToPixelX(minX + (int) boundW, minX, boundW, viewportW);
        assertEquals(viewportW - 1, pxEnd);

        int wx0 = MatrixMapping.pixelToWorldX(0, minX, boundW, viewportW);
        assertEquals(minX, wx0);

        int wxEnd = MatrixMapping.pixelToWorldX(viewportW - 1, minX, boundW, viewportW);
        assertEquals(minX + boundW, wxEnd);
    }

    @Test
    @DisplayName("renderMapBuffer correctly renders shape domain and outside colors")
    void testRenderMapBuffer() {
        int minX = -100;
        int minZ = -100;
        long boundW = 200;
        long boundH = 200;
        int mapW = 64;
        int mapH = 64;
        Color outside = new Color(0x11, 0x22, 0x33);

        BufferedImage buffer = MatrixMapping.renderMapBuffer(
                shape, minX, minZ, boundW, boundH, mapW, mapH, outside);

        assertNotNull(buffer);
        assertEquals(mapW, buffer.getWidth());
        assertEquals(mapH, buffer.getHeight());

        // Center pixel should be inside the shape
        int centerRgb = buffer.getRGB(mapW / 2, mapH / 2) & 0xFFFFFF;
        assertTrue(centerRgb != (outside.getRGB() & 0xFFFFFF), "Center pixel should be inside shape");

        // Corner pixel (-100, -100) is outside the radius 64 shape
        int cornerRgb = buffer.getRGB(0, 0) & 0xFFFFFF;
        assertEquals(outside.getRGB() & 0xFFFFFF, cornerRgb, "Corner pixel should be outside shape");
    }
}
