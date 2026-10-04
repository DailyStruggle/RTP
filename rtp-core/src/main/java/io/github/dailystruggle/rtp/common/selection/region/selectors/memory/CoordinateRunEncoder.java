package io.github.dailystruggle.rtp.common.selection.region.selectors.memory;

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Bandwidth-efficient Hilbert space-filling curve Run-Length Encoder (ADR-104).
 *
 * <p>Maps 2D world coordinates within bounding extents into an order-{@code k} Hilbert curve
 * grid (dimension {@code N = 2^k}), sorting spatial clusters contiguously to achieve high
 * run-length compression ratios (<5 KB for typical regional bad-location snapshots).
 *
 * <p>All operations are 100% computational over in-memory coordinate bitmasks with zero
 * chunk I/O (S-005).
 */
public final class CoordinateRunEncoder {

    /** Default Hilbert order (order 7 -> 128x128 grid = 16,384 cells). */
    public static final int DEFAULT_ORDER = 7;

    /** Maximum allowed Hilbert order (order 9 -> 512x512 grid = 262,144 cells). */
    public static final int MAX_ORDER = 9;

    private CoordinateRunEncoder() {}

    /**
     * Represents a single Run-Length Encoded span along the Hilbert curve.
     *
     * @param start start index on the Hilbert curve [0, 4^order - 1]
     * @param length number of consecutive contiguous cells in this run
     * @param value byte classification / cause code (e.g. 1 for hazard, or specific cause byte)
     */
    public record Run(int start, int length, byte value) implements Comparable<Run> {
        public Run {
            if (start < 0) throw new IllegalArgumentException("start must be >= 0: " + start);
            if (length <= 0) throw new IllegalArgumentException("length must be > 0: " + length);
        }

        @Override
        public int compareTo(Run o) {
            return Integer.compare(this.start, o.start);
        }
    }

    /**
     * Encapsulates encoded Hilbert RLE payload metadata and compressed runs.
     */
    public record EncodedPayload(
            int order,
            int gridSize,
            int minX,
            int minZ,
            int maxX,
            int maxZ,
            int totalRunCount,
            int markedCellCount,
            List<Run> runs
    ) {
        public EncodedPayload {
            Objects.requireNonNull(runs, "runs");
            runs = Collections.unmodifiableList(new ArrayList<>(runs));
        }

        /**
         * Serializes the runs into a compact binary format and returns standard Base64 string.
         * Binary format per run:
         * VarInt startDelta, VarInt length, 1 byte value.
         */
        public String toBase64() {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);
                dos.writeByte(order);
                dos.writeInt(minX);
                dos.writeInt(minZ);
                dos.writeInt(maxX);
                dos.writeInt(maxZ);
                writeVarInt(dos, runs.size());
                int lastStart = 0;
                for (Run r : runs) {
                    writeVarInt(dos, r.start() - lastStart);
                    writeVarInt(dos, r.length());
                    dos.writeByte(r.value());
                    lastStart = r.start();
                }
                dos.flush();
                return Base64.getEncoder().encodeToString(baos.toByteArray());
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode payload to base64", e);
            }
        }

        /**
         * Calculates the binary size in bytes.
         */
        public int binaryByteSize() {
            return Base64.getDecoder().decode(toBase64()).length;
        }
    }

    /**
     * Decodes a Base64 string produced by {@link EncodedPayload#toBase64()}.
     */
    public static EncodedPayload fromBase64(String base64) {
        Objects.requireNonNull(base64, "base64");
        byte[] data = Base64.getDecoder().decode(base64);
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            int order = dis.readByte() & 0xFF;
            int minX = dis.readInt();
            int minZ = dis.readInt();
            int maxX = dis.readInt();
            int maxZ = dis.readInt();
            int runCount = readVarInt(dis);
            List<Run> runs = new ArrayList<>(runCount);
            int lastStart = 0;
            int markedCells = 0;
            for (int i = 0; i < runCount; i++) {
                int delta = readVarInt(dis);
                int start = lastStart + delta;
                int len = readVarInt(dis);
                byte val = dis.readByte();
                runs.add(new Run(start, len, val));
                lastStart = start;
                markedCells += len;
            }
            int gridSize = 1 << order;
            return new EncodedPayload(order, gridSize, minX, minZ, maxX, maxZ, runCount, markedCells, runs);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid encoded payload data", e);
        }
    }

    /**
     * Encodes known bad locations from a {@link MemoryShape} by automatically sampling its bounding box.
     *
     * @param memoryShape memory shape
     * @param order Hilbert order
     * @return encoded payload
     */
    public static EncodedPayload encode(MemoryShape<?> memoryShape, int order) {
        Objects.requireNonNull(memoryShape, "memoryShape");
        long range = memoryShape.getRange();
        if (range <= 0L) {
            int gridSize = 1 << order;
            return new EncodedPayload(order, gridSize, 0, 0, 0, 0, 0, 0, Collections.emptyList());
        }

        int gridSize = 1 << order;
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;

        long sampleStep = Math.max(1L, range / (long) (gridSize * 4));
        int samples = 0;
        for (long i = 0; i < range; i += sampleStep) {
            int[] xz = memoryShape.locationToXZ(i);
            if (xz == null || xz.length < 2) continue;
            int bx = xz[0];
            int bz = xz[1];
            if (bx < minX) minX = bx;
            if (bx > maxX) maxX = bx;
            if (bz < minZ) minZ = bz;
            if (bz > maxZ) maxZ = bz;
            samples++;
        }

        if (samples == 0) {
            return new EncodedPayload(order, gridSize, 0, 0, 0, 0, 0, 0, Collections.emptyList());
        }

        int extentX = Math.max(1, maxX - minX);
        int extentZ = Math.max(1, maxZ - minZ);
        int pad = Math.max(1, Math.max(extentX, extentZ) / 10);
        minX -= pad; maxX += pad;
        minZ -= pad; maxZ += pad;

        return encode(memoryShape, minX, minZ, maxX, maxZ, order);
    }

    /**
     * Convenience method using {@link #DEFAULT_ORDER}.
     */
    public static EncodedPayload encode(MemoryShape<?> memoryShape) {
        return encode(memoryShape, DEFAULT_ORDER);
    }

    /**
     * Encodes known bad locations from a {@link MemoryShape} within the given bounding extents
     * into compact Hilbert RLE runs.
     *
     * @param memoryShape memory shape containing bad location bitmasks / rejection causes
     * @param minX minimum X bounding box
     * @param minZ minimum Z bounding box
     * @param maxX maximum X bounding box
     * @param maxZ maximum Z bounding box
     * @param order Hilbert order (e.g. 7 for 128x128 grid)
     * @return encoded payload with runs
     */
    public static EncodedPayload encode(
            MemoryShape<?> memoryShape,
            int minX,
            int minZ,
            int maxX,
            int maxZ,
            int order
    ) {
        Objects.requireNonNull(memoryShape, "memoryShape");
        if (order < 1 || order > MAX_ORDER) {
            throw new IllegalArgumentException("order must be between 1 and " + MAX_ORDER + ": " + order);
        }
        int gridSize = 1 << order;
        int totalCells = gridSize * gridSize;

        long boundW = Math.max(1L, (long) maxX - minX);
        long boundH = Math.max(1L, (long) maxZ - minZ);

        byte[] cellValues = new byte[totalCells];
        BitSet marked = new BitSet(totalCells);

        for (int gy = 0; gy < gridSize; gy++) {
            int bz = (int) (minZ + (gy * boundH) / (gridSize - 1));
            for (int gx = 0; gx < gridSize; gx++) {
                int bx = (int) (minX + (gx * boundW) / (gridSize - 1));
                if (memoryShape.contains(bx, bz)) {
                    int cause = memoryShape.causeAt(bx, bz);
                    if (cause >= 0) {
                        int hIndex = xyToHilbert(gx, gy, order);
                        cellValues[hIndex] = (byte) (cause + 1);
                        marked.set(hIndex);
                    }
                }
            }
        }

        List<Run> runs = buildRuns(cellValues, marked, totalCells);
        return new EncodedPayload(order, gridSize, minX, minZ, maxX, maxZ, runs.size(), marked.cardinality(), runs);
    }

    /**
     * Encodes a 2D boolean mask into Hilbert RLE runs.
     *
     * @param mask 2D boolean mask of dimension [gridSize][gridSize]
     * @param minX bounding extents
     * @param minZ bounding extents
     * @param maxX bounding extents
     * @param maxZ bounding extents
     * @param order Hilbert order where gridSize == 1 << order
     */
    public static EncodedPayload encodeMask(
            boolean[][] mask,
            int minX,
            int minZ,
            int maxX,
            int maxZ,
            int order
    ) {
        Objects.requireNonNull(mask, "mask");
        int gridSize = 1 << order;
        int totalCells = gridSize * gridSize;
        byte[] cellValues = new byte[totalCells];
        BitSet marked = new BitSet(totalCells);

        for (int y = 0; y < gridSize && y < mask.length; y++) {
            boolean[] row = mask[y];
            if (row == null) continue;
            for (int x = 0; x < gridSize && x < row.length; x++) {
                if (row[x]) {
                    int hIndex = xyToHilbert(x, y, order);
                    cellValues[hIndex] = 1;
                    marked.set(hIndex);
                }
            }
        }

        List<Run> runs = buildRuns(cellValues, marked, totalCells);
        return new EncodedPayload(order, gridSize, minX, minZ, maxX, maxZ, runs.size(), marked.cardinality(), runs);
    }

    /**
     * Reconstructs a 2D grid of byte values from an {@link EncodedPayload}.
     *
     * @param payload decoded or encoded payload
     * @return 2D byte array of dimensions [gridSize][gridSize]
     */
    public static byte[][] decodeToGrid(EncodedPayload payload) {
        Objects.requireNonNull(payload, "payload");
        int order = payload.order();
        int gridSize = payload.gridSize();
        byte[][] grid = new byte[gridSize][gridSize];

        for (Run run : payload.runs()) {
            for (int i = 0; i < run.length(); i++) {
                int hIndex = run.start() + i;
                int[] xy = hilbertToXY(hIndex, order);
                grid[xy[1]][xy[0]] = run.value();
            }
        }

        return grid;
    }

    private static List<Run> buildRuns(byte[] cellValues, BitSet marked, int totalCells) {
        List<Run> runs = new ArrayList<>();
        int idx = marked.nextSetBit(0);
        while (idx >= 0 && idx < totalCells) {
            byte val = cellValues[idx];
            int start = idx;
            int len = 1;
            idx++;
            while (idx < totalCells && marked.get(idx) && cellValues[idx] == val) {
                len++;
                idx++;
            }
            runs.add(new Run(start, len, val));
            idx = marked.nextSetBit(idx);
        }
        return runs;
    }

    // --- Hilbert Curve Bijection (order n over 2^n x 2^n grid) ---

    /**
     * Converts 2D grid coordinates {@code (x, y)} into a 1D Hilbert curve index.
     * Both x and y must be in {@code [0, 2^order - 1]}.
     */
    public static int xyToHilbert(int x, int y, int order) {
        int d = 0;
        for (int s = (1 << (order - 1)); s > 0; s >>>= 1) {
            int rx = (x & s) > 0 ? 1 : 0;
            int ry = (y & s) > 0 ? 1 : 0;
            d += s * s * ((3 * rx) ^ ry);
            if (ry == 0) {
                if (rx == 1) {
                    x = (1 << order) - 1 - x;
                    y = (1 << order) - 1 - y;
                }
                int t = x;
                x = y;
                y = t;
            }
        }
        return d;
    }

    /**
     * Converts a 1D Hilbert index into 2D grid coordinates {@code [x, y]}.
     */
    public static int[] hilbertToXY(int d, int order) {
        int x = 0;
        int y = 0;
        int t = d;
        for (int s = 1; s < (1 << order); s <<= 1) {
            int rx = 1 & (t / 2);
            int ry = 1 & (t ^ rx);
            if (ry == 0) {
                if (rx == 1) {
                    x = s - 1 - x;
                    y = s - 1 - y;
                }
                int temp = x;
                x = y;
                y = temp;
            }
            x += s * rx;
            y += s * ry;
            t /= 4;
        }
        return new int[]{x, y};
    }

    // --- VarInt Helpers for compact stream serialization ---

    static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0L) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }

    static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int i = 0;
        int b;
        while (((b = in.readByte()) & 0x80) != 0) {
            value |= (b & 0x7F) << i;
            i += 7;
            if (i > 35) throw new IllegalArgumentException("Variable length quantity is too long");
        }
        return value | (b << i);
    }
}
