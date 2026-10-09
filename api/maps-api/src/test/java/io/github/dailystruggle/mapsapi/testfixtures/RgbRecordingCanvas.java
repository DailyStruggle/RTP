package io.github.dailystruggle.mapsapi.testfixtures;

import io.github.dailystruggle.mapsapi.MapCanvas;

import java.util.Arrays;

/**
 * Arbitrary-size {@link MapCanvas} that records the raw {@code setPixelRgb} value per pixel,
 * so renderer tests can map model cells 1:1 onto pixels and assert exact colours. Unwritten
 * pixels read {@link #UNSET}; out-of-bounds writes are clipped per the {@link MapCanvas} contract.
 */
public final class RgbRecordingCanvas implements MapCanvas {

    /** Sentinel for pixels never written via {@link #setPixelRgb(int, int, int)}. */
    public static final int UNSET = 0x00ABCDEF;

    private final int width;
    private final int height;
    private final int[] rgb;

    public RgbRecordingCanvas(int width, int height) {
        this.width = width;
        this.height = height;
        this.rgb = new int[width * height];
        Arrays.fill(rgb, UNSET);
    }

    public int rgbAt(int x, int y) {
        return rgb[y * width + x];
    }

    @Override public int width() { return width; }
    @Override public int height() { return height; }

    @Override
    public void setPixelRgb(int x, int y, int value) {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        rgb[y * width + x] = value;
    }

    @Override public void setPixel(int x, int y, byte paletteIndex) {}
    @Override public void fillRect(int x0, int y0, int x1, int y1, byte paletteIndex) {}
    @Override public void drawText(int x, int y, String text, byte paletteIndex) {}
    @Override public void clear() { Arrays.fill(rgb, UNSET); }
    @Override public void commit() {}
}
