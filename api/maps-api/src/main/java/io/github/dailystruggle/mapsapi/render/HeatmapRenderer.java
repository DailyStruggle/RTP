package io.github.dailystruggle.mapsapi.render;

import io.github.dailystruggle.mapsapi.MapCanvas;
import io.github.dailystruggle.mapsapi.PaletteIndex;
import io.github.dailystruggle.mapsapi.model.Heatmap2D;

/**
 * First concrete {@link ChartRenderer}: maps a {@link Heatmap2D}'s scalar
 * field onto the canvas using the logical heat-ramp slice
 * ({@link PaletteIndex#RAMP_MIN}..{@link PaletteIndex#RAMP_MAX}) from
 * {@code maps-api-ADR-001} §Palette policy.
 *
 * <p>Renders nearest-neighbour: each canvas pixel samples the model cell at
 * {@code (x * model.width() / canvas.width(), y * model.height() / canvas.height())}.
 * Samples outside {@code [minValue, maxValue]} are clamped before
 * normalisation. No chunk I/O, no blocking futures — REQ-RTP-MAP-002.
 */
public final class HeatmapRenderer implements ChartRenderer<Heatmap2D> {

    /**
     * Process-wide singleton. The renderer is stateless (REQ-RTP-MAP-002) so a
     * single instance is safe to share across resolvers, dispatchers, and
     * binding implementations; {@code MapDispatch} (rtp-core, ADR-047)
     * references it directly to avoid per-frame allocation.
     */
    public static final HeatmapRenderer INSTANCE = new HeatmapRenderer();

    /**
     * Lowest palette ramp index (cool end). Mirrors
     * {@link PaletteIndex#RAMP_MIN}; retained as a renderer-local constant
     * for back-compat with existing {@code HeatmapRendererTest} call sites.
     */
    public static final byte RAMP_MIN = PaletteIndex.RAMP_MIN;

    /**
     * Highest palette ramp index (hot end). Mirrors
     * {@link PaletteIndex#RAMP_MAX}; the heat ramp deliberately stops short
     * of the {@code 28..31} named-color slots reserved for categorical
     * renderers (see {@code maps-api-ADR-001} §Palette policy).
     */
    public static final byte RAMP_MAX = PaletteIndex.RAMP_MAX;

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
