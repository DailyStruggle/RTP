package io.github.dailystruggle.mapsapi.render;

import io.github.dailystruggle.mapsapi.MapAllocationRequest;
import io.github.dailystruggle.mapsapi.MapHandle;
import io.github.dailystruggle.mapsapi.model.Heatmap2D;
import io.github.dailystruggle.mapsapi.testfixtures.InMemoryMapBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link HeatmapRenderer} against {@link InMemoryMapBinding} and asserts
 * on the resulting pixel buffer: palette ramp endpoints, clamping behaviour,
 * canvas dimensions, and that {@link io.github.dailystruggle.mapsapi.MapCanvas#commit()}
 * is invoked exactly once per ephemeral render.
 */
@DisplayName("HeatmapRenderer — pixel buffer + palette + commit semantics")
class HeatmapRendererTest {

    private final InMemoryMapBinding binding = new InMemoryMapBinding();
    private final HeatmapRenderer renderer = new HeatmapRenderer();

    private MapHandle allocate(String id) {
        return binding.allocate(new MapAllocationRequest(
                id, null, MapAllocationRequest.Locking.LOCKED));
    }

    @Test
    @DisplayName("uniform-zero model produces all-RAMP_MIN pixels")
    void uniformZero() {
        MapHandle h = allocate("uniform-zero");
        Heatmap2D model = new Heatmap2D(4, 4, new double[16], 0.0, 1.0);
        binding.renderEphemeral(h, renderer, model);

        byte[][] pixels = binding.snapshot(h);
        for (byte[] row : pixels) {
            for (byte b : row) {
                assertEquals(HeatmapRenderer.RAMP_MIN, b);
            }
        }
        assertEquals(1, binding.commitCount(h));
    }

    @Test
    @DisplayName("uniform-max model produces all-RAMP_MAX pixels")
    void uniformMax() {
        MapHandle h = allocate("uniform-max");
        double[] vals = new double[16];
        for (int i = 0; i < vals.length; i++) vals[i] = 1.0;
        Heatmap2D model = new Heatmap2D(4, 4, vals, 0.0, 1.0);
        binding.renderEphemeral(h, renderer, model);

        byte[][] pixels = binding.snapshot(h);
        for (byte[] row : pixels) {
            for (byte b : row) {
                assertEquals(HeatmapRenderer.RAMP_MAX, b);
            }
        }
    }

    @Test
    @DisplayName("samples above maxValue clamp to RAMP_MAX")
    void clampsAboveCeiling() {
        MapHandle h = allocate("clamp-up");
        double[] vals = new double[4];
        for (int i = 0; i < 4; i++) vals[i] = 999.0;
        Heatmap2D model = new Heatmap2D(2, 2, vals, 0.0, 1.0);
        binding.renderEphemeral(h, renderer, model);

        byte[][] pixels = binding.snapshot(h);
        assertEquals(HeatmapRenderer.RAMP_MAX, pixels[0][0]);
        assertEquals(HeatmapRenderer.RAMP_MAX, pixels[127][127]);
    }

    @Test
    @DisplayName("samples below minValue clamp to RAMP_MIN")
    void clampsBelowFloor() {
        MapHandle h = allocate("clamp-down");
        double[] vals = new double[4];
        for (int i = 0; i < 4; i++) vals[i] = -999.0;
        Heatmap2D model = new Heatmap2D(2, 2, vals, 0.0, 1.0);
        binding.renderEphemeral(h, renderer, model);

        byte[][] pixels = binding.snapshot(h);
        assertEquals(HeatmapRenderer.RAMP_MIN, pixels[0][0]);
        assertEquals(HeatmapRenderer.RAMP_MIN, pixels[127][127]);
    }

    @Test
    @DisplayName("canvas dimensions remain vanilla 128x128 regardless of model size")
    void canvasIs128x128() {
        MapHandle h = allocate("dim-check");
        Heatmap2D model = new Heatmap2D(7, 9, new double[63], 0.0, 1.0);
        binding.renderEphemeral(h, renderer, model);

        byte[][] pixels = binding.snapshot(h);
        assertEquals(128, pixels.length);
        for (byte[] row : pixels) {
            assertEquals(128, row.length);
        }
    }

    @Test
    @DisplayName("two-row gradient produces a visible top-vs-bottom split")
    void gradientTopVsBottom() {
        MapHandle h = allocate("gradient");
        // 2x2 model: top row 0.0, bottom row 1.0
        double[] vals = {0.0, 0.0, 1.0, 1.0};
        Heatmap2D model = new Heatmap2D(2, 2, vals, 0.0, 1.0);
        binding.renderEphemeral(h, renderer, model);

        byte[][] pixels = binding.snapshot(h);
        // Top half samples model row 0 (value 0.0) → RAMP_MIN
        // Bottom half samples model row 1 (value 1.0) → RAMP_MAX
        assertEquals(HeatmapRenderer.RAMP_MIN, pixels[0][64]);
        assertEquals(HeatmapRenderer.RAMP_MAX, pixels[127][64]);
        assertTrue(pixels[0][64] < pixels[127][64], "bottom should be brighter than top");
    }

    @Test
    @DisplayName("renderer clears canvas before drawing — old pixels do not bleed through")
    void clearsBeforeDraw() {
        MapHandle h = allocate("clear-check");
        // Frame 1: all-max
        double[] maxVals = new double[16];
        for (int i = 0; i < 16; i++) maxVals[i] = 1.0;
        binding.renderEphemeral(h, renderer, new Heatmap2D(4, 4, maxVals, 0.0, 1.0));
        // Frame 2: all-zero
        binding.renderEphemeral(h, renderer, new Heatmap2D(4, 4, new double[16], 0.0, 1.0));

        byte[][] pixels = binding.snapshot(h);
        for (byte[] row : pixels) {
            for (byte b : row) {
                assertEquals(HeatmapRenderer.RAMP_MIN, b,
                        "frame-2 should have fully overwritten frame-1");
            }
        }
        assertEquals(2, binding.commitCount(h));
    }
}
