package io.github.dailystruggle.mapsapi.render;

import io.github.dailystruggle.mapsapi.MapCanvas;
import io.github.dailystruggle.mapsapi.PaletteIndex;
import io.github.dailystruggle.mapsapi.model.DualSparkline;

/**
 * Stateless {@link ChartRenderer} for {@link DualSparkline}.
 *
 * <p>Draws two index-aligned polylines on the canvas with independent
 * y-scales. The x-axis spans the canvas width; each sample stretches
 * proportionally if the model has fewer samples than canvas columns
 * (warm-up period) or compresses if it has more (shouldn't happen with
 * the 128-cap ring but the math tolerates it).
 *
 * <p>Series A renders in {@link PaletteIndex#RED} (intended for MSPT) and
 * series B renders in a deep-blue ramp slot (intended for heap). The
 * canvas is cleared to {@link PaletteIndex#BLACK} first; a faint
 * {@link PaletteIndex#WHITE} baseline is drawn at the bottom row as a
 * visual axis.
 *
 * <p>{@code NaN} samples produce a gap in the polyline rather than a
 * y=0 spike: the renderer simply skips the line segment whose endpoint
 * is missing.
 *
 * <p>The model's y-scales {@code [aMin, aMax]} and {@code [bMin, bMax]}
 * are taken as-is; the resolver is expected to have already auto-scaled
 * them sensibly (e.g. MSPT clamped to a 50 ms floor so an idle server
 * doesn't render as a meaningless 0..0.3 ms zoom).
 *
 * <p>Stateless (REQ-RTP-MAP-002); the {@link #INSTANCE singleton} is
 * safe to share. No chunk I/O, no blocking futures.
 */
public final class DualSparklineRenderer implements ChartRenderer<DualSparkline> {

    public static final DualSparklineRenderer INSTANCE = new DualSparklineRenderer();

    /**
     * Series-A pen colour. Logical palette slot intended to read as a hot
     * red on Bukkit-family bindings (matches the canonical "MSPT" semantic
     * of "high values are bad").
     */
    private static final byte PEN_A = PaletteIndex.RED;

    /**
     * Series-B pen colour. {@link PaletteIndex#GREEN} is the only
     * non-red, non-white, non-black categorical slot in the 32-step
     * logical palette (see {@code BukkitMapBinding.buildPalette}); the
     * ramp slots 1..27 are a black -> red -> yellow -> white gradient,
     * so anything picked from inside the ramp renders as a dim red on
     * the BLACK background (slot 3 was ~RGB(39,0,0) - effectively
     * invisible, which presented as the "only one line" bug). GREEN is
     * vivid against black and visually distinct from PEN_A (red), so the
     * MSPT and heap polylines never visually collide regardless of
     * value.
     */
    private static final byte PEN_B = PaletteIndex.GREEN;

    /** Background colour. */
    private static final byte BG = PaletteIndex.BLACK;

    /** Axis / baseline colour. */
    private static final byte AXIS = PaletteIndex.WHITE;

    @Override
    public void render(MapCanvas canvas, DualSparkline model) {
        if (canvas == null) {
            throw new IllegalArgumentException("canvas shall not be null");
        }
        if (model == null) {
            throw new IllegalArgumentException("model shall not be null");
        }

        final int cw = canvas.width();
        final int ch = canvas.height();

        // Background.
        canvas.fillRect(0, 0, cw - 1, ch - 1, BG);

        final double[] a = model.seriesA();
        final double[] b = model.seriesB();
        if (cw == 0 || ch < 8) return;

        // Split the canvas vertically into two stacked panels: series A
        // (MSPT) on top, series B (heap) on bottom. Each panel gets its
        // own top axis row, label row, and bottom baseline so the two
        // lines never visually overlap regardless of their values.
        //
        // Panel layout (per panel):
        //   row 0           : top frame (white)
        //   row 1           : label text "MSPT (ms)" or "Heap (MB)"
        //   rows 2..(h-2)   : polyline drawing area
        //   row h-1         : bottom baseline (white)
        final int panelH = ch / 2;
        final int aTop = 0;
        final int aBot = panelH - 1;
        final int bTop = panelH;
        final int bBot = ch - 1;

        drawPanel(canvas, cw, aTop, aBot, model.labelA(),
                a, model.aMin(), model.aMax(), PEN_A);
        drawPanel(canvas, cw, bTop, bBot, model.labelB(),
                b, model.bMin(), model.bMax(), PEN_B);
    }

    /**
     * Draw one panel: top frame row, label row, polyline drawing area,
     * bottom baseline row. The polyline is clipped to the rows strictly
     * inside the frame so it never paints over the axis or label.
     */
    private static void drawPanel(MapCanvas canvas,
                                  int cw,
                                  int panelTop,
                                  int panelBot,
                                  String label,
                                  double[] samples,
                                  double yMin,
                                  double yMax,
                                  byte pen) {
        if (panelBot - panelTop < 4) return;

        // Top frame.
        for (int x = 0; x < cw; x++) {
            canvas.setPixel(x, panelTop, AXIS);
        }
        // Bottom baseline.
        for (int x = 0; x < cw; x++) {
            canvas.setPixel(x, panelBot, AXIS);
        }
        // Label text on the row just below the top frame. The text
        // renderer paints in the chosen palette index; we use AXIS
        // (white) so the label reads clearly against the BLACK background.
        if (label != null && !label.isEmpty()) {
            canvas.drawText(2, panelTop + 1, label, AXIS);
        }

        // Polyline drawing area: rows below the label, above the
        // baseline. Reserve ~7px (a Minecraft glyph row height) for the
        // label area when the panel is tall enough; otherwise just give
        // it 2px so very small panels still render.
        int labelArea = Math.min(8, (panelBot - panelTop) / 3);
        int yTop = panelTop + 1 + Math.max(2, labelArea);
        int yBot = panelBot - 1;
        if (yTop >= yBot) return;

        drawPolyline(canvas, samples, yMin, yMax, yTop, yBot, cw, pen);
    }

    /**
     * Maps {@code samples} onto x-columns {@code [0..cw)} and y-rows
     * {@code [yTop..yBot]} via independent linear scales, then draws
     * line segments between consecutive samples with Bresenham.
     */
    private static void drawPolyline(MapCanvas canvas,
                                     double[] samples,
                                     double yMin,
                                     double yMax,
                                     int yTop,
                                     int yBot,
                                     int cw,
                                     byte pen) {
        final int n = samples.length;
        final double range = yMax - yMin;
        if (range <= 0.0) return;

        // Single sample -> just draw a dot at the rightmost column.
        if (n == 1) {
            double s = samples[0];
            if (!Double.isNaN(s)) {
                int y = mapY(s, yMin, range, yTop, yBot);
                canvas.setPixel(cw - 1, y, pen);
            }
            return;
        }

        // Convert each sample to (x, y); skip NaN samples (the line is
        // broken across them so a missed sample doesn't render as a
        // dive to zero).
        int prevX = -1;
        int prevY = -1;
        for (int i = 0; i < n; i++) {
            double s = samples[i];
            int x = (int) ((long) i * (cw - 1) / (n - 1));
            if (Double.isNaN(s)) {
                prevX = -1;
                prevY = -1;
                continue;
            }
            int y = mapY(s, yMin, range, yTop, yBot);
            if (prevX < 0) {
                canvas.setPixel(x, y, pen);
            } else {
                drawLine(canvas, prevX, prevY, x, y, pen);
            }
            prevX = x;
            prevY = y;
        }
    }

    /** Linear map of a sample value to a y row, clamped to [yTop, yBot]. Inverted: high value = small y (toward top). */
    private static int mapY(double value, double yMin, double range, int yTop, int yBot) {
        double t = (value - yMin) / range;
        if (t < 0.0) t = 0.0;
        else if (t > 1.0) t = 1.0;
        int y = yBot - (int) Math.round(t * (yBot - yTop));
        if (y < yTop) y = yTop;
        else if (y > yBot) y = yBot;
        return y;
    }

    /** Bresenham. */
    private static void drawLine(MapCanvas canvas, int x0, int y0, int x1, int y1, byte pen) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0;
        int y = y0;
        while (true) {
            canvas.setPixel(x, y, pen);
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) {
                if (x == x1) break;
                err += dy;
                x += sx;
            }
            if (e2 <= dx) {
                if (y == y1) break;
                err += dx;
                y += sy;
            }
        }
    }
}
