package io.github.dailystruggle.mapsapi.render;

import io.github.dailystruggle.mapsapi.MapCanvas;
import io.github.dailystruggle.mapsapi.model.Heatmap2D;

/**
 * First concrete {@link ChartRenderer}: maps a {@link Heatmap2D}'s scalar
 * field onto the canvas using the logical 32-symbol palette ramp from
 * {@code maps-api-ADR-001} §Palette policy.
 *
 * <p>Renders nearest-neighbour: each canvas pixel samples the model cell at
 * {@code (x * model.width() / canvas.width(), y * model.height() / canvas.height())}.
 * Samples outside {@code [minValue, maxValue]} are clamped before
 * normalisation. No chunk I/O, no blocking futures — REQ-RTP-MAP-002.
 */
public final class HeatmapRenderer implements ChartRenderer<Heatmap2D> {

    /** Lowest palette ramp index (transparent / unset). */
    public static final byte RAMP_MIN = 0;
    /** Highest palette ramp index in the logical 32-symbol contract. */
    public static final byte RAMP_MAX = 31;

    @Override
    public void render(MapCanvas canvas, Heatmap2D model) {
        if (canvas == null) {
            throw new IllegalArgumentException("canvas shall not be null");
        }
        if (model == null) {
            throw new IllegalArgumentException("model shall not be null");
        }
        canvas.clear();
        final int cw = canvas.width();
        final int ch = canvas.height();
        final int mw = model.width();
        final int mh = model.height();
        final double lo = model.minValue();
        final double hi = model.maxValue();
        final double span = hi - lo;
        final int rampSpan = RAMP_MAX - RAMP_MIN;
        for (int y = 0; y < ch; y++) {
            int my = Math.min(mh - 1, (int) ((long) y * mh / ch));
            for (int x = 0; x < cw; x++) {
                int mx = Math.min(mw - 1, (int) ((long) x * mw / cw));
                double v = model.valueAt(mx, my);
                double clamped = Math.min(hi, Math.max(lo, v));
                double t = (clamped - lo) / span;
                int idx = RAMP_MIN + (int) Math.round(t * rampSpan);
                if (idx < RAMP_MIN) idx = RAMP_MIN;
                if (idx > RAMP_MAX) idx = RAMP_MAX;
                canvas.setPixel(x, y, (byte) idx);
            }
        }
    }
}
