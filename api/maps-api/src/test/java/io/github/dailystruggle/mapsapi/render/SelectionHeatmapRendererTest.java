package io.github.dailystruggle.mapsapi.render;

import io.github.dailystruggle.mapsapi.model.SelectionHeatmap;
import io.github.dailystruggle.mapsapi.model.SelectionHeatmap.SelectionPoint;
import io.github.dailystruggle.mapsapi.model.SelectionHeatmap.SelectionType;
import io.github.dailystruggle.mapsapi.testfixtures.RgbRecordingCanvas;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link SelectionHeatmapRenderer} against a canvas the same size as the model grid so
 * every model cell maps to exactly one pixel and block coordinates map 1:1 onto pixels.
 */
@DisplayName("SelectionHeatmapRenderer - domain backdrop, heat field and selection markers")
class SelectionHeatmapRendererTest {

    private static final int VOID = 0xFF0D1117;
    private static final int DOMAIN_BASE = 0xFF1C2128;
    private static final int DOMAIN_BORDER = 0xFF38434F;
    private static final int BLACK = 0xFF000000;

    private final SelectionHeatmapRenderer renderer = SelectionHeatmapRenderer.INSTANCE;

    private static boolean[] allInside(int w, int h) {
        boolean[] mask = new boolean[w * h];
        Arrays.fill(mask, true);
        return mask;
    }

    private static SelectionHeatmap model7(boolean[] inside, int[] biome, double[] density,
                                           List<SelectionPoint> points) {
        return new SelectionHeatmap("r", 7, 7, 0, 0, 6, 6, inside, biome, density, 4.0, points);
    }

    @Test
    @DisplayName("null canvas or model is rejected")
    void nullArgs() {
        SelectionHeatmap m = new SelectionHeatmap("r", 1, 1, 0, 0, 1, 1,
                new boolean[]{true}, null, new double[1], 0.0, List.of());
        RgbRecordingCanvas canvas = new RgbRecordingCanvas(1, 1);
        assertThrows(IllegalArgumentException.class, () -> renderer.render(null, m));
        assertThrows(IllegalArgumentException.class, () -> renderer.render(canvas, null));
    }

    @Test
    @DisplayName("backdrop shades void, domain edges, terrain and density heat")
    void backdrop() {
        int w = 7;
        boolean[] inside = allInside(w, w);
        inside[3 * w + 3] = false; // hole in the centre
        int[] biome = new int[w * w];
        biome[5 * w + 5] = 0x22AA44;
        double[] density = new double[w * w];
        density[5 * w + 1] = 4.0;
        density[1 * w + 5] = 1.0;

        RgbRecordingCanvas canvas = new RgbRecordingCanvas(w, w);
        renderer.render(canvas, model7(inside, biome, density, List.of()));

        assertEquals(VOID, canvas.rgbAt(3, 3));
        // Grid frame and every side of the hole are domain edges.
        assertEquals(DOMAIN_BORDER, canvas.rgbAt(0, 3));
        assertEquals(DOMAIN_BORDER, canvas.rgbAt(4, 3));
        assertEquals(DOMAIN_BORDER, canvas.rgbAt(2, 3));
        assertEquals(DOMAIN_BORDER, canvas.rgbAt(3, 4));
        assertEquals(DOMAIN_BORDER, canvas.rgbAt(3, 2));
        // Interior without terrain or density.
        assertEquals(DOMAIN_BASE, canvas.rgbAt(1, 1));
        // Interior with terrain colour: dimmed biome, never the plain base.
        int terrain = canvas.rgbAt(5, 5);
        assertNotEquals(DOMAIN_BASE, terrain);
        assertNotEquals(DOMAIN_BORDER, terrain);
        // Peak density blends towards white heat.
        int peak = canvas.rgbAt(1, 5);
        assertTrue(((peak >> 16) & 0xFF) > 200, "peak heat should be near white");
        // Partial density is tinted but darker than the peak.
        int partial = canvas.rgbAt(5, 1);
        assertNotEquals(DOMAIN_BASE, partial);
        assertNotEquals(peak, partial);
    }

    @Test
    @DisplayName("each selection type gets its own outlined marker; off-canvas points are skipped")
    void markers() {
        List<SelectionPoint> points = List.of(
                new SelectionPoint(0, 0, 1, SelectionType.CANDIDATE),
                new SelectionPoint(3, 0, 1, SelectionType.QUEUE_L1),
                new SelectionPoint(6, 0, 1, SelectionType.QUEUE_L2),
                new SelectionPoint(0, 3, 1, SelectionType.ARRIVAL),
                new SelectionPoint(3, 3, 1, SelectionType.HAZARD_DISCARD),
                new SelectionPoint(-5, 3, 1, SelectionType.CANDIDATE),
                new SelectionPoint(100, 3, 1, SelectionType.CANDIDATE),
                new SelectionPoint(3, -5, 1, SelectionType.CANDIDATE),
                new SelectionPoint(3, 100, 1, SelectionType.CANDIDATE));
        RgbRecordingCanvas canvas = new RgbRecordingCanvas(7, 7);

        renderer.render(canvas, model7(allInside(7, 7), null, new double[49], points));

        assertEquals(0xFF00E5FF, canvas.rgbAt(0, 0));
        assertEquals(0xFF00E676, canvas.rgbAt(3, 0));
        assertEquals(0xFF29B6F6, canvas.rgbAt(6, 0));
        assertEquals(0xFFFFD600, canvas.rgbAt(0, 3));
        assertEquals(0xFFFF1744, canvas.rgbAt(3, 3));
        assertEquals(BLACK, canvas.rgbAt(1, 1));
        // Far corner untouched by any on-canvas marker keeps the backdrop.
        assertEquals(DOMAIN_BORDER, canvas.rgbAt(6, 6));
    }

    @Test
    @DisplayName("heat ramp hits every stop at its band boundary")
    void heatRampStops() {
        assertEquals(0xFF0D47A1, SelectionHeatmapRenderer.sampleHeatRamp(-0.5));
        assertEquals(0xFF0D47A1, SelectionHeatmapRenderer.sampleHeatRamp(0.0));
        assertEquals(0xFF00E5FF, SelectionHeatmapRenderer.sampleHeatRamp(0.2));
        assertEquals(0xFF00E676, SelectionHeatmapRenderer.sampleHeatRamp(0.4));
        assertEquals(0xFFFFEA00, SelectionHeatmapRenderer.sampleHeatRamp(0.6));
        assertEquals(0xFFFF6D00, SelectionHeatmapRenderer.sampleHeatRamp(0.8));
        assertEquals(0xFFD50000, SelectionHeatmapRenderer.sampleHeatRamp(0.95));
        assertEquals(0xFFFFFFFF, SelectionHeatmapRenderer.sampleHeatRamp(1.0));
        assertEquals(0xFFFFFFFF, SelectionHeatmapRenderer.sampleHeatRamp(3.0));

        int mid = SelectionHeatmapRenderer.sampleHeatRamp(0.1);
        assertNotEquals(0xFF0D47A1, mid);
        assertNotEquals(0xFF00E5FF, mid);
        assertNotEquals(0xFFD50000, SelectionHeatmapRenderer.sampleHeatRamp(0.97));
    }
}
