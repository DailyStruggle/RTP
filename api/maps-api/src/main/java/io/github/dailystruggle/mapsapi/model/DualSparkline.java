package io.github.dailystruggle.mapsapi.model;

import java.util.Objects;

/**
 * Two index-aligned time-series intended for a dual-line sparkline chart
 * (e.g. MSPT + heap-used) rendered on a single 128x128 cartography canvas.
 *
 * <p>{@code seriesA} and {@code seriesB} share the x-axis (sample index)
 * and are plotted with independent y-scales {@code [aMin, aMax]} and
 * {@code [bMin, bMax]}. The two arrays must have the same length; an
 * empty array is rejected (use a different {@link ChartModel} for "no
 * data" surfaces).
 *
 * <p>{@code NaN} values in either series are treated by the renderer as
 * "no sample" for that column and produce a gap in the line rather than
 * a y=0 spike. This makes the chart resilient to mid-history sampler
 * misses.
 *
 * <p>Defensive copies of both arrays are taken on construction and on
 * accessor read (REQ-RTP-MAP-002).
 *
 * @param labelA  human-readable name for series A (e.g. {@code "MSPT (ms)"})
 * @param seriesA most-recent-last samples for series A; same length as B
 * @param aMin    y-axis floor for series A
 * @param aMax    y-axis ceiling for series A; must be {@code > aMin}
 * @param labelB  human-readable name for series B (e.g. {@code "Heap (MB)"})
 * @param seriesB most-recent-last samples for series B; same length as A
 * @param bMin    y-axis floor for series B
 * @param bMax    y-axis ceiling for series B; must be {@code > bMin}
 */
public record DualSparkline(
        String labelA,
        double[] seriesA,
        double aMin,
        double aMax,
        String labelB,
        double[] seriesB,
        double bMin,
        double bMax) implements ChartModel {

    public DualSparkline {
        Objects.requireNonNull(labelA, "labelA");
        Objects.requireNonNull(seriesA, "seriesA");
        Objects.requireNonNull(labelB, "labelB");
        Objects.requireNonNull(seriesB, "seriesB");
        if (seriesA.length == 0) {
            throw new IllegalArgumentException("seriesA shall be non-empty");
        }
        if (seriesA.length != seriesB.length) {
            throw new IllegalArgumentException(
                    "seriesA.length=" + seriesA.length
                            + " shall equal seriesB.length=" + seriesB.length);
        }
        if (!(aMax > aMin)) {
            throw new IllegalArgumentException(
                    "aMax=" + aMax + " shall be > aMin=" + aMin);
        }
        if (!(bMax > bMin)) {
            throw new IllegalArgumentException(
                    "bMax=" + bMax + " shall be > bMin=" + bMin);
        }
        seriesA = seriesA.clone();
        seriesB = seriesB.clone();
    }

    /** Defensive accessor - returns a clone of the internal series A array. */
    @Override
    public double[] seriesA() {
        return seriesA.clone();
    }

    /** Defensive accessor - returns a clone of the internal series B array. */
    @Override
    public double[] seriesB() {
        return seriesB.clone();
    }

    /** Number of samples in each series (both are equal length by contract). */
    public int sampleCount() {
        return seriesA.length;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DualSparkline(String thatLabelA, double[] thatSeriesA, double thatAMin, double thatAMax,
                                         String thatLabelB, double[] thatSeriesB, double thatBMin, double thatBMax))) {
            return false;
        }
        return Double.compare(thatAMin, aMin) == 0
                && Double.compare(thatAMax, aMax) == 0
                && Double.compare(thatBMin, bMin) == 0
                && Double.compare(thatBMax, bMax) == 0
                && labelA.equals(thatLabelA)
                && java.util.Arrays.equals(seriesA, thatSeriesA)
                && labelB.equals(thatLabelB)
                && java.util.Arrays.equals(seriesB, thatSeriesB);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(labelA, aMin, aMax, labelB, bMin, bMax);
        result = 31 * result + java.util.Arrays.hashCode(seriesA);
        result = 31 * result + java.util.Arrays.hashCode(seriesB);
        return result;
    }

    @Override
    public String toString() {
        return "DualSparkline[" +
                "labelA=" + labelA + ", " +
                "seriesA=" + java.util.Arrays.toString(seriesA) + ", " +
                "aMin=" + aMin + ", " +
                "aMax=" + aMax + ", " +
                "labelB=" + labelB + ", " +
                "seriesB=" + java.util.Arrays.toString(seriesB) + ", " +
                "bMin=" + bMin + ", " +
                "bMax=" + bMax + ']';
    }
}
