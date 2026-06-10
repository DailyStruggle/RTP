package io.github.dailystruggle.mapsapi.render;

import io.github.dailystruggle.mapsapi.MapCanvas;
import io.github.dailystruggle.mapsapi.PaletteIndex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the embedded {@link PixelFont} rasterises labels by writing the
 * supplied palette colour through {@link MapCanvas#setPixel}. This is the
 * regression guard for the missing chart labels on the ARGB carriers
 * (Fabric / NeoForge {@code *MapCanvas#drawText}) whose previous no-op left
 * the pipeline-health "MSPT (ms)" / "Heap (MB)" labels invisible.
 */
class PixelFontTest {

    /** Minimal capture canvas: records every {@code setPixel} into a byte grid. */
    private static final class CaptureCanvas implements MapCanvas {
        final byte[] pixels = new byte[VANILLA_WIDTH * VANILLA_HEIGHT];
        @Override public int width() { return VANILLA_WIDTH; }
        @Override public int height() { return VANILLA_HEIGHT; }
        @Override public void setPixel(int x, int y, byte p) {
            if (x < 0 || x >= VANILLA_WIDTH || y < 0 || y >= VANILLA_HEIGHT) return;
            pixels[y * VANILLA_WIDTH + x] = p;
        }
        @Override public void fillRect(int x0, int y0, int x1, int y1, byte p) { }
        @Override public void drawText(int x, int y, String t, byte p) { }
        @Override public void clear() { }
        @Override public void commit() { }
        int litCount() {
            int n = 0;
            for (byte b : pixels) if (b != 0) n++;
            return n;
        }
    }

    @Test
    void drawsForegroundPixelsForLabel() {
        CaptureCanvas canvas = new CaptureCanvas();
        PixelFont.draw(canvas, 2, 1, "MSPT (ms)", PaletteIndex.WHITE);
        assertTrue(canvas.litCount() > 0, "label should light at least some pixels");
        // All lit pixels must carry the requested colour.
        for (byte b : canvas.pixels) {
            if (b != 0) assertEquals(PaletteIndex.WHITE, b);
        }
    }

    @Test
    void emptyAndNullTextDrawNothing() {
        CaptureCanvas canvas = new CaptureCanvas();
        PixelFont.draw(canvas, 0, 0, "", PaletteIndex.WHITE);
        PixelFont.draw(canvas, 0, 0, null, PaletteIndex.WHITE);
        assertEquals(0, canvas.litCount());
    }

    @Test
    void spaceGlyphLightsNoPixels() {
        CaptureCanvas canvas = new CaptureCanvas();
        PixelFont.draw(canvas, 0, 0, "   ", PaletteIndex.WHITE);
        assertEquals(0, canvas.litCount());
    }

    @Test
    void measureMatchesAdvanceLayout() {
        assertEquals(0, PixelFont.measure(""));
        assertEquals(PixelFont.GLYPH_WIDTH, PixelFont.measure("A"));
        assertEquals(3 * PixelFont.ADVANCE - 1, PixelFont.measure("ABC"));
    }
}
