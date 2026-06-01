package io.github.dailystruggle.mapsapi.render;

import io.github.dailystruggle.mapsapi.MapCanvas;
import io.github.dailystruggle.mapsapi.PaletteIndex;
import io.github.dailystruggle.mapsapi.model.RegionBadLocations;

/**
 * Stateless {@link ChartRenderer} for {@link RegionBadLocations}. Blits the
 * model's pre-classified {@code byte[] palette} buffer onto the canvas
 * using nearest-neighbour scaling when the canvas size differs from the
 * model's buffer size.
 *
 * <p>Classification is entirely the resolver's responsibility - this
 * renderer makes no assumptions about region geometry (no center,
 * no radius, no disk). The three palette colours that appear in
 * {@code model.palette()} are by convention:
 *
 * <ul>
 *   <li>{@link PaletteIndex#BLACK} - outside the region's spatial domain;</li>
 *   <li>{@link PaletteIndex#GREEN} - inside the region, not flagged bad;</li>
 *   <li>{@link PaletteIndex#RED}   - flagged bad in the region's memory shape.</li>
 * </ul>
 *
 * <p>This renderer is stateless (REQ-RTP-MAP-002); the
 * {@link #INSTANCE singleton} is safe to share across resolvers, dispatchers,
 * and binding implementations. No chunk I/O, no blocking futures.
 */
public final class RegionBadLocationsRenderer implements ChartRenderer<RegionBadLocations> {

    /** Process-wide stateless singleton (REQ-RTP-MAP-002). */
    public static final RegionBadLocationsRenderer INSTANCE = new RegionBadLocationsRenderer();

    @Override
    public void render(MapCanvas canvas, RegionBadLocations model) {
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
        final byte[] palette = model.palette();

        if (cw == mw && ch == mh) {
            // Fast path: identical resolutions, blit row by row.
            for (int py = 0; py < ch; py++) {
                int row = py * mw;
                for (int px = 0; px < cw; px++) {
                    canvas.setPixel(px, py, palette[row + px]);
                }
            }
            return;
        }
        // Nearest-neighbour scale from (mw x mh) to (cw x ch). Source index
        // = px * mw / cw (truncating), which collapses to identity when
        // cw == mw. Clamp defensively in case of rounding edge cases.
        for (int py = 0; py < ch; py++) {
            int sy = (int) ((long) py * mh / ch);
            if (sy >= mh) sy = mh - 1;
            int row = sy * mw;
            for (int px = 0; px < cw; px++) {
                int sx = (int) ((long) px * mw / cw);
                if (sx >= mw) sx = mw - 1;
                canvas.setPixel(px, py, palette[row + sx]);
            }
        }
        // Note: the binding owns commit() dispatch (one-shot or live frame).
        // Mirrors HeatmapRenderer which also does not call commit() itself.
    }
}
