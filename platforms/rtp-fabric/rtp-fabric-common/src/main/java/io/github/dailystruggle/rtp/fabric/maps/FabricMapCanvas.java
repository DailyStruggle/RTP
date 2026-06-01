package io.github.dailystruggle.rtp.fabric.maps;

import io.github.dailystruggle.mapsapi.MapCanvas;
import io.github.dailystruggle.mapsapi.PaletteIndex;

/**
 * Platform-neutral {@link MapCanvas} adapter that writes into a row-major
 * 128x128 ARGB pixel buffer. {@code ChartRenderer}s author against the
 * maps-api logical palette (0..31); this canvas translates each logical byte
 * to an ARGB int via {@link #PALETTE}. The filled buffer is later handed to
 * the active {@code FabricVersionAdapter#renderMapChart} seam, which performs
 * the version-specific ARGB to vanilla {@code MapColor} byte matching and the
 * actual {@code MapItemSavedData} write (rtp-fabric-ADR-014).
 *
 * <p>Mirrors the {@code BukkitMapCanvas} inner adapter in
 * {@code mapsapi.bukkit.BukkitMapBinding}: same logical-to-RGB ramp, same
 * {@code setPixelRgb} full-colour bypass, but produces ARGB ints instead of
 * pre-translated vanilla bytes so the per-version carrier keeps ownership of
 * the {@code net.minecraft.*} colour table.
 *
 * <p>No chunk I/O, no blocking (REQ-RTP-MAP-002 / S-005). {@link #commit()}
 * is a no-op: the buffer is read by the carrier after the render pass returns.
 */
public final class FabricMapCanvas implements MapCanvas {

    /** Logical palette index (0..31) to ARGB int. Index 0 is transparent (alpha 0). */
    static final int[] PALETTE = buildPalette();

    private final int[] argb;

    /**
     * @param argb a row-major {@link #VANILLA_WIDTH} x {@link #VANILLA_HEIGHT}
     *             ARGB buffer (length 16384) owned by the caller
     */
    public FabricMapCanvas(int[] argb) {
        if (argb == null || argb.length < VANILLA_WIDTH * VANILLA_HEIGHT) {
            throw new IllegalArgumentException(
                    "argb buffer must be at least " + (VANILLA_WIDTH * VANILLA_HEIGHT) + " ints");
        }
        this.argb = argb;
    }

    @Override public int width() { return VANILLA_WIDTH; }
    @Override public int height() { return VANILLA_HEIGHT; }

    @Override
    public void setPixel(int x, int y, byte paletteIndex) {
        if (x < 0 || x >= VANILLA_WIDTH || y < 0 || y >= VANILLA_HEIGHT) return;
        argb[y * VANILLA_WIDTH + x] = toArgb(paletteIndex);
    }

    @Override
    public void fillRect(int x0, int y0, int x1, int y1, byte paletteIndex) {
        int lx = Math.max(0, Math.min(x0, x1));
        int hx = Math.min(VANILLA_WIDTH - 1, Math.max(x0, x1));
        int ly = Math.max(0, Math.min(y0, y1));
        int hy = Math.min(VANILLA_HEIGHT - 1, Math.max(y0, y1));
        int v = toArgb(paletteIndex);
        for (int yy = ly; yy <= hy; yy++) {
            int row = yy * VANILLA_WIDTH;
            for (int xx = lx; xx <= hx; xx++) {
                argb[row + xx] = v;
            }
        }
    }

    @Override
    public void drawText(int x, int y, String text, byte paletteIndex) {
        // Vanilla map text rendering needs a server-side font raster that the
        // NM-free common module cannot reach; the carrier seam only accepts a
        // pixel buffer. Chart labels degrade gracefully to "no label" on
        // Fabric (the heatmap / region-shape charts that drive the Fabric
        // path are pixel-only). A future enhancement could pre-raster glyphs
        // into the buffer here.
    }

    @Override
    public void clear() {
        java.util.Arrays.fill(argb, 0, VANILLA_WIDTH * VANILLA_HEIGHT, 0);
    }

    @Override
    public void setPixelRgb(int x, int y, int rgb) {
        if (x < 0 || x >= VANILLA_WIDTH || y < 0 || y >= VANILLA_HEIGHT) return;
        // Full-colour bypass: opaque ARGB. The carrier matches to the nearest
        // vanilla MapColor across the ~240-entry palette, so renderers driving
        // setPixelRgb get the full vanilla colour space.
        argb[y * VANILLA_WIDTH + x] = 0xFF000000 | (rgb & 0x00FFFFFF);
    }

    @Override
    public void commit() {
        // No-op: the buffer is consumed by the carrier after the render pass.
    }

    /** Translate a logical-palette byte (0..31) to an ARGB int. */
    static int toArgb(byte logicalIndex) {
        int idx = logicalIndex & 0xFF;
        if (idx >= PALETTE.length) idx = PALETTE.length - 1;
        return PALETTE[idx];
    }

    /**
     * Build the 32-step logical-to-ARGB ramp. Layout matches
     * {@code BukkitMapBinding.buildPalette} (maps-api-ADR-001 Palette policy):
     * index 0 transparent; indices RAMP_MIN..RAMP_MAX walk black to red to
     * yellow to white; the four named slots are stable categorical colours.
     */
    private static int[] buildPalette() {
        int[] table = new int[32];
        table[0] = 0; // transparent (alpha 0)
        final int rampLo = PaletteIndex.RAMP_MIN;
        final int rampHi = PaletteIndex.RAMP_MAX;
        final int rampSpan = rampHi - rampLo;
        for (int i = rampLo; i <= rampHi; i++) {
            float t = (i - rampLo) / (float) rampSpan; // 0..1
            int r, g, b;
            if (t < 0.5f) {
                float u = t * 2.0f;            // black -> red
                r = Math.round(255 * u); g = 0; b = 0;
            } else if (t < 0.85f) {
                float u = (t - 0.5f) / 0.35f;  // red -> yellow
                r = 255; g = Math.round(255 * u); b = 0;
            } else {
                float u = (t - 0.85f) / 0.15f; // yellow -> white
                r = 255; g = 255; b = Math.round(255 * u);
            }
            table[i] = argb(r, g, b);
        }
        table[PaletteIndex.BLACK] = argb(0, 0, 0);
        table[PaletteIndex.RED]   = argb(170, 0, 0);
        table[PaletteIndex.GREEN] = argb(0, 170, 0);
        table[PaletteIndex.WHITE] = argb(255, 255, 255);
        return table;
    }

    private static int argb(int r, int g, int b) {
        return 0xFF000000 | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }
}
