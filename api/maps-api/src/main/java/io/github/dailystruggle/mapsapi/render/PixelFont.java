package io.github.dailystruggle.mapsapi.render;

import io.github.dailystruggle.mapsapi.MapCanvas;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal embedded 5x7 bitmap font used to rasterise chart labels directly
 * into a {@link MapCanvas} via {@link MapCanvas#setPixel(int, int, byte)}.
 *
 * <p>Bukkit-family bindings have a server-side {@code MinecraftFont} and use
 * it through {@code org.bukkit.map.MapCanvas#drawText}. The NM-free ARGB
 * carriers (Fabric / NeoForge {@code *MapCanvas}) have no font raster reachable
 * from the common module, so chart labels ("MSPT (ms)", "Heap (MB)", region
 * titles) silently dropped on those platforms. This class closes that gap with
 * a tiny self-contained glyph table so labels render identically across every
 * binding.
 *
 * <p>Each glyph is 5 pixels wide and 7 pixels tall, encoded as seven row
 * bitmasks (low 5 bits, MSB = left-most column). Glyphs advance 6 pixels
 * (5 + 1px spacing). Lowercase letters reuse the uppercase shapes (small
 * caps) to keep the table compact; unknown characters render as a blank
 * advance. Drawing is pure {@link MapCanvas#setPixel} - no chunk I/O, no
 * blocking (REQ-RTP-MAP-002 / S-005).
 */
public final class PixelFont {

    private PixelFont() {
    }

    /** Glyph cell width in pixels. */
    public static final int GLYPH_WIDTH = 5;

    /** Glyph cell height in pixels. */
    public static final int GLYPH_HEIGHT = 7;

    /** Horizontal advance per glyph (cell width + 1px spacing). */
    public static final int ADVANCE = GLYPH_WIDTH + 1;

    private static final Map<Character, int[]> GLYPHS = buildGlyphs();

    /**
     * Rasterises {@code text} onto {@code canvas} starting at the top-left
     * pixel {@code (x, y)} in the supplied logical-palette colour. Coordinates
     * outside the canvas are silently clipped by {@link MapCanvas#setPixel}.
     *
     * @param canvas       target surface
     * @param x            left pixel of the first glyph
     * @param y            top pixel of the text row
     * @param text         label to draw (null / empty renders nothing)
     * @param paletteIndex logical-palette colour byte
     */
    public static void draw(MapCanvas canvas, int x, int y, String text, byte paletteIndex) {
        if (canvas == null || text == null || text.isEmpty()) return;
        int cx = x;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int[] glyph = lookup(c);
            if (glyph != null) {
                for (int row = 0; row < GLYPH_HEIGHT; row++) {
                    int bits = glyph[row];
                    for (int col = 0; col < GLYPH_WIDTH; col++) {
                        if ((bits & (1 << (GLYPH_WIDTH - 1 - col))) != 0) {
                            canvas.setPixel(cx + col, y + row, paletteIndex);
                        }
                    }
                }
            }
            cx += ADVANCE;
        }
    }

    /** Pixel width a label would occupy if drawn (no trailing spacing). */
    public static int measure(String text) {
        if (text == null || text.isEmpty()) return 0;
        return text.length() * ADVANCE - 1;
    }

    private static int[] lookup(char c) {
        int[] g = GLYPHS.get(c);
        if (g != null) return g;
        if (c >= 'a' && c <= 'z') {
            return GLYPHS.get((char) (c - 'a' + 'A'));
        }
        return null; // unknown -> blank advance
    }

    private static Map<Character, int[]> buildGlyphs() {
        Map<Character, int[]> m = new HashMap<>();

        m.put(' ', rows(0, 0, 0, 0, 0, 0, 0));

        m.put('0', rows(0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110));
        m.put('1', rows(0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110));
        m.put('2', rows(0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111));
        m.put('3', rows(0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110));
        m.put('4', rows(0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010));
        m.put('5', rows(0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110));
        m.put('6', rows(0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110));
        m.put('7', rows(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000));
        m.put('8', rows(0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110));
        m.put('9', rows(0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100));

        m.put('A', rows(0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001));
        m.put('B', rows(0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110));
        m.put('C', rows(0b01110, 0b10001, 0b10000, 0b10000, 0b10000, 0b10001, 0b01110));
        m.put('D', rows(0b11100, 0b10010, 0b10001, 0b10001, 0b10001, 0b10010, 0b11100));
        m.put('E', rows(0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111));
        m.put('F', rows(0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000));
        m.put('G', rows(0b01110, 0b10001, 0b10000, 0b10111, 0b10001, 0b10001, 0b01111));
        m.put('H', rows(0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001));
        m.put('I', rows(0b01110, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110));
        m.put('J', rows(0b00111, 0b00010, 0b00010, 0b00010, 0b00010, 0b10010, 0b01100));
        m.put('K', rows(0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001));
        m.put('L', rows(0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111));
        m.put('M', rows(0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001));
        m.put('N', rows(0b10001, 0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001));
        m.put('O', rows(0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110));
        m.put('P', rows(0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000));
        m.put('Q', rows(0b01110, 0b10001, 0b10001, 0b10001, 0b10101, 0b10010, 0b01101));
        m.put('R', rows(0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001));
        m.put('S', rows(0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110));
        m.put('T', rows(0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100));
        m.put('U', rows(0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110));
        m.put('V', rows(0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100));
        m.put('W', rows(0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b11011, 0b10001));
        m.put('X', rows(0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001));
        m.put('Y', rows(0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100, 0b00100));
        m.put('Z', rows(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b11111));

        m.put('(', rows(0b00010, 0b00100, 0b01000, 0b01000, 0b01000, 0b00100, 0b00010));
        m.put(')', rows(0b01000, 0b00100, 0b00010, 0b00010, 0b00010, 0b00100, 0b01000));
        m.put('.', rows(0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00100, 0b00100));
        m.put(',', rows(0b00000, 0b00000, 0b00000, 0b00000, 0b00100, 0b00100, 0b01000));
        m.put(':', rows(0b00000, 0b00100, 0b00100, 0b00000, 0b00100, 0b00100, 0b00000));
        m.put('-', rows(0b00000, 0b00000, 0b00000, 0b01110, 0b00000, 0b00000, 0b00000));
        m.put('+', rows(0b00000, 0b00100, 0b00100, 0b11111, 0b00100, 0b00100, 0b00000));
        m.put('/', rows(0b00001, 0b00010, 0b00100, 0b00100, 0b01000, 0b10000, 0b10000));
        m.put('%', rows(0b11001, 0b11010, 0b00100, 0b01000, 0b10000, 0b01011, 0b10011));
        m.put('#', rows(0b01010, 0b01010, 0b11111, 0b01010, 0b11111, 0b01010, 0b01010));
        m.put('_', rows(0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b11111));

        return m;
    }

    private static int[] rows(int r0, int r1, int r2, int r3, int r4, int r5, int r6) {
        return new int[]{r0, r1, r2, r3, r4, r5, r6};
    }
}
