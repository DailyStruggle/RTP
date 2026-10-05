package io.github.dailystruggle.rtp.common.commands.editor;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Dual-layer square encoding of per-chunk values for the web editor (ADR-104 §4.6).
 *
 * <p>Outer layer: one bin per region file (32 x 32 chunks), keyed by its square-spiral ring index
 * around region file (0, 0) ({@link #binKey} / {@link #binCoords}). Inner layer: an order-5 Hilbert
 * curve over the bin's 1,024 chunks; cells are stored as {@code (value, length)} unsigned-varint
 * runs along the curve, covering all 1,024 cells. Value {@code 0} means unknown; biome layers store
 * {@code paletteIndex + 1}.
 *
 * <p>Detail levels are aligned curve stretches: level 0 = 1 cell of 32 x 32 chunks, level 1 = 64
 * cells of 4 x 4, level 2 = every chunk. A coarse sample fills its stretch and finer samples
 * overwrite it in place, so one encoding serves every level. The page decodes the same bytes
 * ({@code docs/editor/index.html}, {@code decodeBinRuns}); the shared vectors in
 * {@code BiomeBinCodecTest} keep both sides identical.
 */
public final class BiomeBinCodec {

    public static final int EDGE = 32;
    public static final int CELLS = EDGE * EDGE;
    public static final int ORDER = 5;
    /** Stretch edge (chunks) per level: 32, 4, 1. */
    static final int[] LEVEL_SHIFT = {5, 2, 0};
    public static final int LEVELS = LEVEL_SHIFT.length;
    public static final int FINEST_LEVEL = LEVELS - 1;

    private static final int[] D2X = new int[CELLS];
    private static final int[] D2Z = new int[CELLS];
    private static final int[] XZ2D = new int[CELLS];

    static {
        for (int d = 0; d < CELLS; d++) {
            int x = 0;
            int z = 0;
            int t = d;
            for (int s = 1; s < EDGE; s <<= 1) {
                int rx = 1 & (t >> 1);
                int rz = 1 & (t ^ rx);
                if (rz == 0) {
                    if (rx == 1) {
                        x = s - 1 - x;
                        z = s - 1 - z;
                    }
                    int tmp = x;
                    x = z;
                    z = tmp;
                }
                x += s * rx;
                z += s * rz;
                t >>= 2;
            }
            D2X[d] = x;
            D2Z[d] = z;
            XZ2D[z * EDGE + x] = d;
        }
    }

    private BiomeBinCodec() {}

    /** Local chunk x (0..31) of curve position {@code d}. */
    public static int d2x(int d) {
        return D2X[d];
    }

    /** Local chunk z (0..31) of curve position {@code d}. */
    public static int d2z(int d) {
        return D2Z[d];
    }

    /** Curve position of local chunk {@code (lx, lz)}. */
    public static int xz2d(int lx, int lz) {
        return XZ2D[(lz & 31) * EDGE + (lx & 31)];
    }

    /** Curve cells per stretch at {@code level}. */
    public static int stretch(int level) {
        return 1 << (2 * LEVEL_SHIFT[level]);
    }

    /**
     * Local sample indices ({@code lz * 32 + lx}) for {@code level}, one per stretch: the chunk at
     * the centre of the stretch's square, in curve order.
     */
    public static int[] sampleIndices(int level) {
        int shift = LEVEL_SHIFT[level];
        int len = 1 << (2 * shift);
        int half = (1 << shift) >> 1;
        int mask = ~((1 << shift) - 1);
        int[] out = new int[CELLS / len];
        for (int k = 0; k < out.length; k++) {
            int d0 = k * len;
            int lx = (D2X[d0] & mask) + half;
            int lz = (D2Z[d0] & mask) + half;
            out[k] = lz * EDGE + lx;
        }
        return out;
    }

    /** Fills the stretch of {@code level} that contains local chunk {@code (lx, lz)}. */
    public static void fillStretch(int[] cells, int level, int lx, int lz, int value) {
        int len = stretch(level);
        int start = (xz2d(lx, lz) / len) * len;
        Arrays.fill(cells, start, start + len, value);
    }

    /** Runs covering {@code cells} (length 1,024) in curve order. */
    public static byte[] encode(int[] cells) {
        if (cells.length != CELLS) throw new IllegalArgumentException("cells.length != " + CELLS);
        ByteArrayOutputStream out = new ByteArrayOutputStream(16);
        int i = 0;
        while (i < CELLS) {
            int v = cells[i];
            if (v < 0) throw new IllegalArgumentException("negative cell value at " + i);
            int j = i + 1;
            while (j < CELLS && cells[j] == v) j++;
            writeVarint(out, v);
            writeVarint(out, j - i);
            i = j;
        }
        return out.toByteArray();
    }

    /** Cells of {@code runs}; throws when the runs do not cover exactly 1,024 cells. */
    public static int[] decode(byte[] runs) {
        int[] cells = new int[CELLS];
        if (runs == null || runs.length == 0) return cells;
        int[] pos = {0};
        int at = 0;
        while (pos[0] < runs.length) {
            int v = readVarint(runs, pos);
            int len = readVarint(runs, pos);
            if (len <= 0 || at + len > CELLS) throw new IllegalArgumentException("run overflows bin at cell " + at);
            Arrays.fill(cells, at, at + len, v);
            at += len;
        }
        if (at != CELLS) throw new IllegalArgumentException("runs cover " + at + " of " + CELLS + " cells");
        return cells;
    }

    /** {@code counts[v]} = cells holding value {@code v}, computed from runs without expanding. */
    public static int[] histogram(byte[] runs, int valueBound) {
        int[] counts = new int[Math.max(1, valueBound)];
        if (runs == null) return counts;
        int[] pos = {0};
        while (pos[0] < runs.length) {
            int v = readVarint(runs, pos);
            int len = readVarint(runs, pos);
            if (v >= counts.length) counts = Arrays.copyOf(counts, v + 1);
            counts[v] += len;
        }
        return counts;
    }

    /** Runs of a bin with every cell unknown. */
    public static byte[] empty() {
        return encode(new int[CELLS]);
    }

    static void writeVarint(ByteArrayOutputStream out, int v) {
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    static int readVarint(byte[] in, int[] pos) {
        int v = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (pos[0] >= in.length) throw new IllegalArgumentException("truncated varint");
            int b = in[pos[0]++] & 0xFF;
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
        }
        throw new IllegalArgumentException("varint too long");
    }

    /**
     * Square-spiral ring index of region file {@code (rx, rz)}: ring {@code r = max(|rx|, |rz|)}
     * starts at {@code (2r-1)^2}; sides run top (z=-r, x ascending), right (x=r, z ascending),
     * bottom (z=r, x descending), left (x=-r, z descending). Dense, origin-anchored, stable.
     */
    public static long binKey(int rx, int rz) {
        long r = Math.max(Math.abs((long) rx), Math.abs((long) rz));
        if (r == 0) return 0L;
        long base = (2 * r - 1) * (2 * r - 1);
        long side = 2 * r;
        if (rz == -r && rx < r) return base + (rx + r);
        if (rx == r && rz < r) return base + side + (rz + r);
        if (rz == r && rx > -r) return base + 2 * side + (r - rx);
        return base + 3 * side + (r - rz);
    }

    /** Inverse of {@link #binKey}: {@code [rx, rz]}. */
    public static int[] binCoords(long key) {
        if (key < 0) throw new IllegalArgumentException("negative bin key");
        if (key == 0) return new int[]{0, 0};
        long r = (long) Math.floor((Math.sqrt((double) key) + 1) / 2);
        while ((2 * r - 1) * (2 * r - 1) > key) r--;
        while ((2 * r + 1) * (2 * r + 1) <= key) r++;
        long off = key - (2 * r - 1) * (2 * r - 1);
        long side = 2 * r;
        int s = (int) (off / side);
        long t = off % side;
        return switch (s) {
            case 0 -> new int[]{(int) (t - r), (int) -r};
            case 1 -> new int[]{(int) r, (int) (t - r)};
            case 2 -> new int[]{(int) (r - t), (int) r};
            default -> new int[]{(int) -r, (int) (r - t)};
        };
    }
}
