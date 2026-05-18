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
     * Publishes the current pixel state to the host runtime. After {@code commit()}
     * the canvas may be reused for the next frame. Implementations shall not block
     * the calling thread on chunk I/O (REQ-RTP-S-005, REQ-RTP-MAP-002).
     */
    void commit();
}
