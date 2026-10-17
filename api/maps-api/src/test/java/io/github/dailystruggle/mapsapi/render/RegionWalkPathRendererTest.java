package io.github.dailystruggle.mapsapi.render;

import io.github.dailystruggle.mapsapi.model.RegionWalkPath;
import io.github.dailystruggle.mapsapi.model.RegionWalkPath.StepStatus;
import io.github.dailystruggle.mapsapi.model.RegionWalkPath.WalkStep;
import io.github.dailystruggle.mapsapi.testfixtures.RgbRecordingCanvas;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Drives {@link RegionWalkPathRenderer} against a canvas the same size as the model grid so
 * every model cell maps to exactly one pixel.
 */
@DisplayName("RegionWalkPathRenderer - domain backdrop, trajectory and step markers")
class RegionWalkPathRendererTest {

    private static final int OUTSIDE = 0xFF141414;
    private static final int INSIDE = 0xFF1B2228;
    private static final int BOUNDARY = 0xFF30363D;
    private static final int HAZARD = 0xFFE74C3C;
    private static final int OUT_BOUNDS = 0xFF484F58;
    private static final int BLACK = 0xFF000000;
    private static final int HAZARD_BORDER = 0xFF800000;

    private final RegionWalkPathRenderer renderer = RegionWalkPathRenderer.INSTANCE;

    private static boolean[] allInside(int w, int h) {
        boolean[] mask = new boolean[w * h];
        Arrays.fill(mask, true);
        return mask;
    }

    @Test
    @DisplayName("null canvas or model is rejected")
    void nullArgs() {
        RegionWalkPath m = new RegionWalkPath("r", 1, 1, new boolean[]{true}, List.of());
        RgbRecordingCanvas canvas = new RgbRecordingCanvas(1, 1);
        assertThrows(IllegalArgumentException.class, () -> renderer.render(null, m));
        assertThrows(IllegalArgumentException.class, () -> renderer.render(canvas, null));
    }

    @Test
    @DisplayName("without terrain data the domain renders as interior / boundary / void silhouette")
    void silhouetteEdgeDetection() {
        boolean[] inside = allInside(5, 5);
        inside[2 * 5 + 2] = false; // hole in the centre
        RegionWalkPath m = new RegionWalkPath("r", 5, 5, inside, List.of());
        RgbRecordingCanvas canvas = new RgbRecordingCanvas(5, 5);

        renderer.render(canvas, m);

        assertEquals(OUTSIDE, canvas.rgbAt(2, 2));
        assertEquals(INSIDE, canvas.rgbAt(1, 1));
        assertEquals(INSIDE, canvas.rgbAt(3, 3));
        // Outer frame of the grid.
        assertEquals(BOUNDARY, canvas.rgbAt(0, 2));
        assertEquals(BOUNDARY, canvas.rgbAt(4, 2));
        assertEquals(BOUNDARY, canvas.rgbAt(2, 0));
        assertEquals(BOUNDARY, canvas.rgbAt(2, 4));
        // Cells bordering the hole from each side.
        assertEquals(BOUNDARY, canvas.rgbAt(3, 2));
        assertEquals(BOUNDARY, canvas.rgbAt(1, 2));
        assertEquals(BOUNDARY, canvas.rgbAt(2, 1));
        assertEquals(BOUNDARY, canvas.rgbAt(2, 3));
    }

    @Test
    @DisplayName("terrain backdrop is desaturated and hazard cells are tinted")
    void terrainBackdropWithHazardMask() {
        int biome = 0x336699;
        RegionWalkPath m = new RegionWalkPath("r", 3, 1, 0, 0, 3, 1,
                new boolean[]{true, true, false},
                new int[]{biome, biome, 0},
                new boolean[]{true, false, false},
                List.of());
        RgbRecordingCanvas canvas = new RgbRecordingCanvas(3, 1);

        renderer.render(canvas, m);

        int desat = CanvasDrawing.desaturate(biome, 0.45f);
        assertEquals(CanvasDrawing.blend(desat, HAZARD, 0.45f), canvas.rgbAt(0, 0));
        assertEquals(desat, canvas.rgbAt(1, 0));
        assertEquals(OUTSIDE, canvas.rgbAt(2, 0));
    }

    @Test
    @DisplayName("missing hazard mask leaves the terrain backdrop untinted")
    void terrainBackdropWithoutHazardMask() {
        int biome = 0x22AA44;
        RegionWalkPath m = new RegionWalkPath("r", 1, 1, 0, 0, 1, 1,
                new boolean[]{true}, new int[]{biome}, null, List.of());
        RgbRecordingCanvas canvas = new RgbRecordingCanvas(1, 1);

        renderer.render(canvas, m);

        assertEquals(CanvasDrawing.desaturate(biome, 0.45f), canvas.rgbAt(0, 0));
    }

    @Test
    @DisplayName("terrain array of the wrong length falls back to the silhouette")
    void mismatchedTerrainFallsBackToSilhouette() {
        RegionWalkPath m = new RegionWalkPath("r", 1, 1, 0, 0, 1, 1,
                new boolean[]{true}, new int[]{0x111111, 0x222222}, null, List.of());
        RgbRecordingCanvas canvas = new RgbRecordingCanvas(1, 1);

        renderer.render(canvas, m);

        assertEquals(BOUNDARY, canvas.rgbAt(0, 0));
    }

    @Test
    @DisplayName("world-coordinate steps map through the bounds and get status-coloured markers")
    void worldCoordinateMarkers() {
        WalkStep valid = new WalkStep(100, 200, StepStatus.VALID, 0.0f);
        WalkStep hazard = new WalkStep(102, 200, StepStatus.HAZARD, 0.5f);
        WalkStep oob = new WalkStep(103, 203, StepStatus.OUT_OF_BOUNDS, 1.0f);
        RegionWalkPath m = new RegionWalkPath("r", 4, 4, 100, 200, 104, 204,
                allInside(4, 4), null, null, List.of(valid, hazard, oob));
        RgbRecordingCanvas canvas = new RgbRecordingCanvas(4, 4);

        renderer.render(canvas, m);

        assertEquals(RegionWalkPathRenderer.resolveStepColor(valid), canvas.rgbAt(0, 0));
        assertEquals(BLACK, canvas.rgbAt(0, 1));
        assertEquals(HAZARD, canvas.rgbAt(2, 0));
        assertEquals(HAZARD_BORDER, canvas.rgbAt(1, 0));
        assertEquals(OUT_BOUNDS, canvas.rgbAt(3, 3));
        assertEquals(BLACK, canvas.rgbAt(2, 2));
    }

    @Test
    @DisplayName("degenerate bounds fall back to grid coordinates and connect steps with lines")
    void gridCoordinateFallback() {
        WalkStep first = new WalkStep(1, 1, StepStatus.VALID, 0.0f);
        WalkStep second = new WalkStep(3, 3, StepStatus.VALID, 1.0f);
        RegionWalkPath m = new RegionWalkPath("r", 4, 4, 0, 0, 0, 0,
                allInside(4, 4), null, null, List.of(first, second));
        RgbRecordingCanvas canvas = new RgbRecordingCanvas(8, 8);

        renderer.render(canvas, m);

        assertEquals(RegionWalkPathRenderer.resolveStepColor(first), canvas.rgbAt(2, 2));
        assertEquals(RegionWalkPathRenderer.resolveStepColor(second), canvas.rgbAt(6, 6));
        // Midpoint of the segment is outside both 3x3 markers, so it carries the line colour.
        assertEquals(RegionWalkPathRenderer.resolveStepColor(second), canvas.rgbAt(4, 4));
    }

    @Test
    @DisplayName("paths longer than 2000 steps draw the trajectory without point markers")
    void longPathSkipsMarkers() {
        List<WalkStep> steps = new ArrayList<>();
        for (int i = 0; i <= 2000; i++) {
            int c = (i % 2 == 0) ? 0 : 3;
            steps.add(new WalkStep(c, c, StepStatus.VALID, i / 2000.0f));
        }
        RegionWalkPath m = new RegionWalkPath("r", 4, 4, 0, 0, 0, 0,
                allInside(4, 4), null, null, steps);
        RgbRecordingCanvas canvas = new RgbRecordingCanvas(4, 4);

        renderer.render(canvas, m);

        // A marker at (0,0) would paint a black border at (1,0); the backdrop survives instead.
        assertEquals(BOUNDARY, canvas.rgbAt(1, 0));
        assertNotEquals(BOUNDARY, canvas.rgbAt(0, 0));
    }

    @Test
    @DisplayName("step colour reflects status and clamped progress")
    void resolveStepColor() {
        assertEquals(HAZARD, RegionWalkPathRenderer.resolveStepColor(
                new WalkStep(0, 0, StepStatus.HAZARD, 0.3f)));
        assertEquals(OUT_BOUNDS, RegionWalkPathRenderer.resolveStepColor(
                new WalkStep(0, 0, StepStatus.OUT_OF_BOUNDS, 0.3f)));

        int start = RegionWalkPathRenderer.resolveStepColor(new WalkStep(0, 0, StepStatus.VALID, 0.0f));
        int end = RegionWalkPathRenderer.resolveStepColor(new WalkStep(0, 0, StepStatus.VALID, 1.0f));
        assertNotEquals(start, end);
        assertEquals(start, RegionWalkPathRenderer.resolveStepColor(
                new WalkStep(0, 0, StepStatus.VALID, -1.0f)));
        assertEquals(end, RegionWalkPathRenderer.resolveStepColor(
                new WalkStep(0, 0, StepStatus.VALID, 2.0f)));
        assertEquals(java.awt.Color.HSBtoRGB(0.58f, 0.85f, 0.95f) & 0xFFFFFF, start);
    }
}
