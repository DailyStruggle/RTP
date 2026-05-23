package io.github.dailystruggle.mapsapi.render;

import io.github.dailystruggle.mapsapi.MapCanvas;
import io.github.dailystruggle.mapsapi.PaletteIndex;
import io.github.dailystruggle.mapsapi.model.RegionBadLocations;

/**
 * Categorical {@link ChartRenderer} for the admin {@code Visualizations}
 * submenu's {@code Region shape} entry. Paints the region's bad-location
 * snapshot onto the canvas using three named palette slots:
 *
 * <ul>
 *   <li>{@link PaletteIndex#BLACK} — outside the inscribed disk of radius
 *       {@code model.radius()}; the unexplored backdrop.</li>
 *   <li>{@link PaletteIndex#GREEN} — inside the disk and not flagged bad;
 *       the "good inside the region" backdrop. Note this is "not flagged
 *       bad", not "verified safe" — regions don't persist a verified-safe
 *       set (see {@link RegionBadLocations} class Javadoc).</li>
 *   <li>{@link PaletteIndex#RED} — a recorded bad location. Drawn last so
 *       a bad pixel inside the disk overrides the green backdrop.</li>
 * </ul>
 *
 * <p>Pixel mapping. The canvas is square ({@code canvas.width() ==
 * canvas.height() == 128} for vanilla cartography), centred on
 * {@code (model.centerX(), model.centerZ())} with a half-edge equal to
 * {@code model.radius()}. A block coordinate {@code (bx, bz)} maps to
 * pixel:
 * <pre>
 *   px = (bx - centerX + radius) * width  / (2 * radius)
 *   py = (bz - centerZ + radius) * height / (2 * radius)
 * </pre>
 * Block coordinates outside the {@code [centerX - radius, centerX + radius]}
 * by {@code [centerZ - radius, centerZ + radius]} square clip to nothing
 * via {@link MapCanvas#setPixel(int, int, byte)}'s out-of-bounds rule.
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
        // 1) Black backdrop: outside-disk + reset prior frame state.
        canvas.fillRect(0, 0, cw - 1, ch - 1, PaletteIndex.BLACK);

        final int radius = model.radius();
        final int centerX = model.centerX();
        final int centerZ = model.centerZ();
        final long diameter = 2L * radius;

        // 2) Green disk: paint every pixel whose canvas-centre corresponds to
        // a block coordinate within the inscribed disk of radius `radius`.
        // We iterate by pixel rather than by block to honour the canvas's
        // resolution exactly and avoid aliasing when `radius > canvas.width()`.
        final double cxPixel = (cw - 1) / 2.0;
        final double cyPixel = (ch - 1) / 2.0;
        // Squared half-extent in pixel space (the inscribed circle inside
        // the canvas-aligned bounding square).
        final double rPixel = Math.min(cxPixel, cyPixel);
        final double rPixelSq = rPixel * rPixel;
        for (int py = 0; py < ch; py++) {
            double dy = py - cyPixel;
            double dy2 = dy * dy;
            for (int px = 0; px < cw; px++) {
                double dx = px - cxPixel;
                if (dx * dx + dy2 <= rPixelSq) {
                    canvas.setPixel(px, py, PaletteIndex.GREEN);
                }
            }
        }

        // 3) Red bad cells: stamp every bad-key over the green disk. Coords
        // outside the bounding square map to out-of-canvas pixels and are
        // clipped by MapCanvas.setPixel's out-of-bounds rule.
        final long[] keys = model.badKeys();
        for (long key : keys) {
            int bx = (int) (key >> 32);
            int bz = (int) key;
            // Block -> pixel: bx in [centerX - radius, centerX + radius] -> px in [0, cw - 1]
            long dx = (long) bx - centerX + radius;
            long dz = (long) bz - centerZ + radius;
            if (dx < 0 || dx > diameter || dz < 0 || dz > diameter) {
                // Outside the bounding square; clipped.
                continue;
            }
            int px = (int) (dx * (cw - 1) / diameter);
            int py = (int) (dz * (ch - 1) / diameter);
            canvas.setPixel(px, py, PaletteIndex.RED);
        }
        // Note: the binding owns commit() dispatch (one-shot or live frame).
        // Mirrors HeatmapRenderer which also does not call commit() itself.
    }
}
