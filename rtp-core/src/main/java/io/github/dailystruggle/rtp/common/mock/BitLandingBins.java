package io.github.dailystruggle.rtp.common.mock;

import java.util.List;
import java.util.Objects;

/**
 * Bins BIT mock landings by their world X/Z coordinates without reading live landing metrics.
 */
public final class BitLandingBins {
    private static final int MAX_CELLS = 1_048_576;

    private BitLandingBins() {
    }

    public static Bins bin(List<MockTeleportRecord> records, int width, int height) {
        Objects.requireNonNull(records, "records");
        if (width <= 0 || height <= 0 || (long) width * height > MAX_CELLS) {
            throw new IllegalArgumentException("bin dimensions must be positive and contain at most " + MAX_CELLS + " cells");
        }

        long[] counts = new long[width * height];
        if (records.isEmpty()) return new Bins(width, height, counts, 0, 0, 0, 0, 0);

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (MockTeleportRecord record : records) {
            Objects.requireNonNull(record, "record");
            minX = Math.min(minX, record.x());
            maxX = Math.max(maxX, record.x());
            minZ = Math.min(minZ, record.z());
            maxZ = Math.max(maxZ, record.z());
        }

        for (MockTeleportRecord record : records) {
            int x = binCoordinate(record.x(), minX, maxX, width);
            int z = binCoordinate(record.z(), minZ, maxZ, height);
            counts[z * width + x]++;
        }
        return new Bins(width, height, counts, minX, maxX, minZ, maxZ, records.size());
    }

    private static int binCoordinate(int coordinate, int minimum, int maximum, int size) {
        long span = (long) maximum - minimum;
        if (span == 0 || size == 1) return 0;
        return (int) (((long) coordinate - minimum) * (size - 1) / span);
    }

    /**
     * Immutable bin grid, with X as columns and Z as rows.
     */
    public record Bins(
            int width,
            int height,
            long[] counts,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            int sampleCount) {
        public Bins {
            if (width <= 0 || height <= 0 || counts == null || counts.length != width * height) {
                throw new IllegalArgumentException("counts shall match positive bin dimensions");
            }
            if (sampleCount < 0) throw new IllegalArgumentException("sampleCount shall not be negative");
            counts = counts.clone();
        }

        @Override
        public long[] counts() {
            return counts.clone();
        }

        public long countAt(int x, int z) {
            if (x < 0 || x >= width || z < 0 || z >= height) {
                throw new IndexOutOfBoundsException("Bin (" + x + ", " + z + ") outside " + width + "x" + height);
            }
            return counts[z * width + x];
        }

        public double[] values() {
            double[] values = new double[counts.length];
            for (int i = 0; i < counts.length; i++) values[i] = counts[i];
            return values;
        }
    }
}
