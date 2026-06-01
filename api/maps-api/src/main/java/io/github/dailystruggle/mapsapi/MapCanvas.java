package io.github.dailystruggle.mapsapi;

/**
 * Platform-neutral drawing surface that a {@link io.github.dailystruggle.mapsapi.render.ChartRenderer}
 * writes into. Always 128 by 128 pixels in vanilla Minecraft cartography, with
 * a per-binding byte palette. Implementations shall be single-threaded from the
 * caller's point of view; the binding itself is responsible for hopping to the
 * correct platform thread before {@link #commit()} is observed by the runtime
 * (REQ-RTP-MAP-002, REQ-RTP-MAP-003).
 *
 * <p>This SPI does <strong>not</strong> import {@code org.bukkit.*} or
 * {@code net.minecraft.*}. Concrete bindings live in
 * {@code mapsapi.bukkit} / {@code mapsapi.fabric} per
 * {@code maps-api-ADR-001} and translate canvas state to the host's map
 * representation (e.g. {@code MapCanvas#setPixel} for Bukkit,
 * {@code MapItemSavedData} mutation for Fabric).
 *
 * <p>Palette policy: bytes written via {@link #setPixel(int, int, byte)} are
 * <em>logical</em> palette indices in the {@code PaletteIndex} 32-symbol
 * contract documented by {@code maps-api-ADR-001}. The active binding maps
 * those to the concrete vanilla map-colour byte before commit. Stage 1 ships
 * the logical contract; the concrete translation table lands in Stage 2.
 *
 * @see io.github.dailystruggle.mapsapi.render.ChartRenderer
 * @see io.github.dailystruggle.mapsapi.MapBinding
 */
public interface MapCanvas {

    /** Vanilla Minecraft cartography width and height in pixels. */
    int VANILLA_WIDTH = 128;

    /** Vanilla Minecraft cartography width and height in pixels. */
    int VANILLA_HEIGHT = 128;

    /** Returns the canvas width in pixels. Always {@link #VANILLA_WIDTH} for vanilla MC. */
    int width();

    /** Returns the canvas height in pixels. Always {@link #VANILLA_HEIGHT} for vanilla MC. */
    int height();

    /**
     * Writes a single logical-palette byte at {@code (x, y)}. Coordinates outside
     * {@code [0, width()) × [0, height())} shall be silently clipped.
     *
     * @param x pixel column, 0-indexed from the left
     * @param y pixel row, 0-indexed from the top
     * @param paletteIndex logical palette byte per the {@code PaletteIndex} contract
     */
    void setPixel(int x, int y, byte paletteIndex);

    /**
     * Fills the inclusive rectangle {@code [x0, x1] × [y0, y1]} with {@code paletteIndex}.
     * Coordinates are clipped to the canvas bounds. Implementations may optimise
     * over a naive {@link #setPixel(int, int, byte)} loop but the visible result
     * shall be identical.
     */
    void fillRect(int x0, int y0, int x1, int y1, byte paletteIndex);

    /**
     * Draws a one-line ASCII text label at {@code (x, y)} in the supplied logical
     * palette colour. The exact font is binding-defined; renderers shall not assume
     * a specific glyph size beyond a 6-pixel minimum legible width per
     * {@code maps-api-ADR-001} §Mermaid output.
     */
    void drawText(int x, int y, String text, byte paletteIndex);

    /** Resets every pixel to the transparent / default palette index (logical 0). */
    void clear();

    /**
     * Writes a single pixel at {@code (x, y)} using a 24-bit RGB colour
     * (0xRRGGBB; the high byte is ignored). Bindings that can quantise
     * arbitrary RGB to their host palette (Bukkit-family via
     * {@code MapPalette.matchColor}) shall override this; the default
     * implementation falls back to the nearest logical-palette colour by
     * crude luminance bucketing so that non-Bukkit bindings render
     * something visible rather than silently dropping the pixel.
     *
     * <p>Renderers that need richer-than-32-slot colour (e.g. biome maps
     * using the server-provided per-biome map colour) shall prefer this
     * entry point; the binding handles the platform-specific colour-match
     * detail.
     *
     * <p>Coordinates outside {@code [0, width()) x [0, height())} shall
     * be silently clipped.
     *
     * @param x   pixel column
     * @param y   pixel row
     * @param rgb 24-bit colour as {@code (r << 16) | (g << 8) | b}
     */
    default void setPixelRgb(int x, int y, int rgb) {
        // Default fallback: pick a logical-palette ramp slot by luminance.
        // Bukkit-family bindings override this with MapPalette.matchColor
        // for true ~144-colour rendering; this fallback keeps NoopMapBinding
        // and any other binding without an RGB matcher honest.
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8)  & 0xFF;
        int b = rgb         & 0xFF;
        int luma = (r * 299 + g * 587 + b * 114) / 1000; // 0..255
        // Map onto PaletteIndex.RAMP_MIN(1) .. RAMP_MAX(27).
        int slot = 1 + (luma * (27 - 1) / 255);
        setPixel(x, y, (byte) slot);
    }

    /**
     * Publishes the current pixel state to the host runtime. After {@code commit()}
     * the canvas may be reused for the next frame. Implementations shall not block
     * the calling thread on chunk I/O (REQ-RTP-S-005, REQ-RTP-MAP-002).
     */
    void commit();
}
