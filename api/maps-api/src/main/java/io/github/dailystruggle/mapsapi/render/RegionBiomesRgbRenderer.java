package io.github.dailystruggle.mapsapi.render;

import io.github.dailystruggle.mapsapi.MapCanvas;
import io.github.dailystruggle.mapsapi.PaletteIndex;
import io.github.dailystruggle.mapsapi.model.RegionBiomesRgb;

/**
 * Stateless {@link ChartRenderer} for {@link RegionBiomesRgb}. Blits the
 * model's pre-classified RGB + mask buffers onto the canvas using
 * nearest-neighbour scaling when the canvas size differs from the model's
 * buffer size.
 *
 * <p>Per-pixel routing (see {@link RegionBiomesRgb} mask docs):
 * <ul>
 *   <li>{@link RegionBiomesRgb#MASK_OUTSIDE} -> {@code setPixel(PaletteIndex.BLACK)};</li>
 *   <li>{@link RegionBiomesRgb#MASK_UNSAMPLED} -> {@code setPixel(PaletteIndex.BLACK)}
 *       ("honest no-data" rendering rather than parchment-white);</li>
 *   <li>{@link RegionBiomesRgb#MASK_RGB} -> {@code setPixelRgb(rgb)} so the
 *       binding's RGB quantiser (Bukkit: {@code MapPalette.matchColor})
 *       picks the closest vanilla map colour from the full ~144-entry
 *       palette.</li>
 * </ul>
 *
 * <p>Stateless singleton (REQ-RTP-MAP-002); no chunk I/O, no blocking.
 */
public final class RegionBiomesRgbRenderer implements ChartRenderer<RegionBiomesRgb> {

    /** Process-wide stateless singleton. */
    public static final RegionBiomesRgbRenderer INSTANCE = new RegionBiomesRgbRenderer();

    @Override
    public void render(MapCanvas canvas, RegionBiomesRgb model) {
        if (canvas == null) {
            throw new IllegalArgumentException("canvas shall not be null");
        }
        if (model == null) {
            throw new IllegalArgumentException("model shall not be null");
        }
        final int cw = canvas.width();
        final int ch = canvas.height();
        final int mw = model.width();
        final int mh = model.height();
        // Read once via the defensive accessor; we'll index it directly.
        final int[] rgb = model.rgb();
        final byte[] mask = model.mask();

        if (cw == mw && ch == mh) {
            // Fast path: identical resolutions, blit row by row.
            for (int py = 0; py < ch; py++) {
                int row = py * mw;
                for (int px = 0; px < cw; px++) {
                    paintAt(canvas, px, py, rgb[row + px], mask[row + px]);
                }
            }
            return;
        }
        // Nearest-neighbour scale from (mw x mh) to (cw x ch).
        for (int py = 0; py < ch; py++) {
            int sy = (int) ((long) py * mh / ch);
            if (sy >= mh) sy = mh - 1;
            int row = sy * mw;
            for (int px = 0; px < cw; px++) {
                int sx = (int) ((long) px * mw / cw);
                if (sx >= mw) sx = mw - 1;
                paintAt(canvas, px, py, rgb[row + sx], mask[row + sx]);
            }
        }
    }

    private static void paintAt(MapCanvas canvas, int x, int y, int rgb, byte mask) {
        if (mask == RegionBiomesRgb.MASK_RGB) {
            canvas.setPixelRgb(x, y, rgb);
        } else {
            // MASK_OUTSIDE and MASK_UNSAMPLED both render as BLACK.
            canvas.setPixel(x, y, PaletteIndex.BLACK);
        }
    }
}
