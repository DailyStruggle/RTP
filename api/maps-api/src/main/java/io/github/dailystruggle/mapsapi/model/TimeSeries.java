package io.github.dailystruggle.mapsapi.model;

import java.util.Objects;

/**
 * Equally-spaced time-series samples for a single metric. Used by the
 * {@code SparklineRenderer} for TPS / MSPT / pipeline-latency
 * traces.
 *
 * <p>The constructor defensively copies {@code samples} so the caller may
 * mutate the supplied array after construction (REQ-RTP-MAP-002).
 *
 * @param label    human-readable metric name (e.g. {@code "TPS"})
 * @param samples  most-recent-last array of samples; must be non-empty
 * @param yMin     normalisation floor for the y axis
 * @param yMax     normalisation ceiling for the y axis; must be {@code > yMin}
 */
public record TimeSeries(String label, double[] samples, double yMin, double yMax)
        implements ChartModel {

    public TimeSeries {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(samples, "samples");
        if (samples.length == 0) {
            throw new IllegalArgumentException("samples shall be non-empty");
        }
        if (!(yMax > yMin)) {
            throw new IllegalArgumentException(
                    "yMax=" + yMax + " shall be > yMin=" + yMin);
        }
        samples = samples.clone();
    }

    /** Defensive accessor - returns a clone of the internal sample array. */
    @Override
    public double[] samples() {
        return samples.clone();
    }

    /** Returns the sample at index {@code i}. No defensive copy. */
    public double sampleAt(int i) {
        return samples[i];
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TimeSeries(String thatLabel, double[] thatSamples, double thatYMin, double thatYMax))) {
            return false;
        }
        return Double.compare(thatYMin, yMin) == 0
                && Double.compare(thatYMax, yMax) == 0
                && label.equals(thatLabel)
                && java.util.Arrays.equals(samples, thatSamples);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(label, yMin, yMax);
        result = 31 * result + java.util.Arrays.hashCode(samples);
        return result;
    }

    @Override
    public String toString() {
        return "TimeSeries[" +
                "label=" + label + ", " +
                "samples=" + java.util.Arrays.toString(samples) + ", " +
                "yMin=" + yMin + ", " +
                "yMax=" + yMax + ']';
    }
}
