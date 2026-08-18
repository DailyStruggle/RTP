package io.github.dailystruggle.mapsapi.model;

import java.util.Objects;

/**
 * Row-major 2-D scalar field. Used by {@code HeatmapRenderer} to render density
 * data (e.g. region attempt counts) onto a {@link io.github.dailystruggle.mapsapi.MapCanvas}.
 *
 * <p>The constructor defensively copies {@code values} so the caller may
 * mutate the supplied array after construction without affecting the model
 * (REQ-RTP-MAP-002 - renderer thread-safety contract).
 *
 * @param width   number of columns, must be {@code > 0}
 * @param height  number of rows, must be {@code > 0}
 * @param values  row-major {@code height × width} array of samples
 * @param minValue normalisation floor; samples at or below this map to
 *                 palette index 0
 * @param maxValue normalisation ceiling; samples at or above this map to the
 *                 maximum palette index. Must be {@code > minValue}.
 */
public record Heatmap2D(int width, int height, double[] values,
                        double minValue, double maxValue) implements ChartModel {

    public Heatmap2D {
        if (width <= 0) {
            throw new IllegalArgumentException("width shall be > 0, got " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height shall be > 0, got " + height);
        }
        Objects.requireNonNull(values, "values");
        if (values.length != width * height) {
            throw new IllegalArgumentException(
                    "values.length=" + values.length + " shall equal width*height=" + (width * height));
        }
        if (!(maxValue > minValue)) {
            throw new IllegalArgumentException(
                    "maxValue=" + maxValue + " shall be > minValue=" + minValue);
        }
        values = values.clone();
    }

    /** Defensive accessor - returns a clone of the internal value array. */
    @Override
    public double[] values() {
        return values.clone();
    }

    /** Returns the sample at row {@code y}, column {@code x}. No defensive copy. */
    public double valueAt(int x, int y) {
        return values[y * width + x];
    }
}
