package io.github.dailystruggle.rtp.common.commands.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-104 §4.6: dual-layer square bin codec shared by store, wire and page")
class BiomeBinCodecTest {

    /** Shared with the page decoder (docs/editor/index.html decodeBinRuns): keep both in sync. */
    static final String VECTOR_UNIFORM_3 = "A4AI";
    static final String VECTOR_HALVES_1_2 = "AYAEAoAE";

    @Test
    @DisplayName("Inner layer: order-5 Hilbert curve is a bijection over 32x32 chunks with unit steps")
    void hilbertBijectionAndAdjacency() {
        Set<Integer> seen = new HashSet<>();
        for (int d = 0; d < BiomeBinCodec.CELLS; d++) {
            int x = BiomeBinCodec.d2x(d);
            int z = BiomeBinCodec.d2z(d);
            assertTrue(x >= 0 && x < 32 && z >= 0 && z < 32);
            assertTrue(seen.add(z * 32 + x), "each chunk exactly once");
            assertEquals(d, BiomeBinCodec.xz2d(x, z));
            if (d > 0) {
                int dist = Math.abs(x - BiomeBinCodec.d2x(d - 1)) + Math.abs(z - BiomeBinCodec.d2z(d - 1));
                assertEquals(1, dist, "consecutive curve cells are neighbours");
            }
        }
    }

    @Test
    @DisplayName("Detail levels are aligned curve stretches forming squares; samples sit at their centres")
    void levelsAreAlignedSquares() {
        int[] edges = {32, 4, 1};
        for (int level = 0; level < BiomeBinCodec.LEVELS; level++) {
            int len = BiomeBinCodec.stretch(level);
            assertEquals(edges[level] * edges[level], len);
            int[] samples = BiomeBinCodec.sampleIndices(level);
            assertEquals(BiomeBinCodec.CELLS / len, samples.length);
            assertEquals(samples.length, Arrays.stream(samples).distinct().count());
            for (int k = 0; k < samples.length; k++) {
                int minX = 99, minZ = 99, maxX = -1, maxZ = -1;
                for (int d = k * len; d < (k + 1) * len; d++) {
                    minX = Math.min(minX, BiomeBinCodec.d2x(d));
                    maxX = Math.max(maxX, BiomeBinCodec.d2x(d));
                    minZ = Math.min(minZ, BiomeBinCodec.d2z(d));
                    maxZ = Math.max(maxZ, BiomeBinCodec.d2z(d));
                }
                assertEquals(edges[level] - 1, maxX - minX, "stretch is a square");
                assertEquals(edges[level] - 1, maxZ - minZ);
                int lx = samples[k] & 31;
                int lz = samples[k] >> 5;
                assertTrue(lx >= minX && lx <= maxX && lz >= minZ && lz <= maxZ, "sample inside its stretch");
            }
        }
        assertArrayEquals(new int[]{16 * 32 + 16}, BiomeBinCodec.sampleIndices(0), "level 0 samples the bin centre");
    }

    @Test
    @DisplayName("Runs round-trip, cover all 1,024 cells and match the shared page vectors")
    void runsRoundTripAndVectors() {
        int[] uniform = new int[BiomeBinCodec.CELLS];
        Arrays.fill(uniform, 3);
        assertEquals(VECTOR_UNIFORM_3, Base64.getEncoder().encodeToString(BiomeBinCodec.encode(uniform)));
        int[] halves = new int[BiomeBinCodec.CELLS];
        Arrays.fill(halves, 0, 512, 1);
        Arrays.fill(halves, 512, 1024, 2);
        assertEquals(VECTOR_HALVES_1_2, Base64.getEncoder().encodeToString(BiomeBinCodec.encode(halves)));

        int[] noisy = new int[BiomeBinCodec.CELLS];
        for (int i = 0; i < noisy.length; i++) noisy[i] = (i * 7919) % 300;
        byte[] runs = BiomeBinCodec.encode(noisy);
        assertArrayEquals(noisy, BiomeBinCodec.decode(runs));
        int[] hist = BiomeBinCodec.histogram(runs, 1);
        assertEquals(BiomeBinCodec.CELLS, Arrays.stream(hist).sum());

        assertThrows(IllegalArgumentException.class, () -> BiomeBinCodec.decode(new byte[]{1, 5}), "short coverage rejected");
        assertThrows(IllegalArgumentException.class, () -> BiomeBinCodec.decode(new byte[]{1, (byte) 0x80}), "truncated varint rejected");
    }

    @Test
    @DisplayName("A coarse fill is refined in place by finer samples")
    void coarseThenFine() {
        int[] cells = new int[BiomeBinCodec.CELLS];
        BiomeBinCodec.fillStretch(cells, 0, 16, 16, 5);
        assertEquals(3, BiomeBinCodec.encode(cells).length, "level 0 is a single run");
        BiomeBinCodec.fillStretch(cells, 1, 1, 1, 9);
        int[] hist = BiomeBinCodec.histogram(BiomeBinCodec.encode(cells), 10);
        assertEquals(16, hist[9], "one 4x4 stretch replaced");
        assertEquals(1008, hist[5]);
        for (int lz = 0; lz < 4; lz++) {
            for (int lx = 0; lx < 4; lx++) assertEquals(9, cells[BiomeBinCodec.xz2d(lx, lz)]);
        }
    }

    @Test
    @DisplayName("Outer layer: square-spiral keys are dense, origin-anchored and invertible")
    void squareSpiralKeys() {
        Set<Long> keys = new HashSet<>();
        for (int rz = -20; rz <= 20; rz++) {
            for (int rx = -20; rx <= 20; rx++) {
                long k = BiomeBinCodec.binKey(rx, rz);
                assertTrue(keys.add(k));
                assertArrayEquals(new int[]{rx, rz}, BiomeBinCodec.binCoords(k));
            }
        }
        assertEquals(0L, BiomeBinCodec.binKey(0, 0));
        for (long k = 0; k < 41L * 41L; k++) assertTrue(keys.contains(k), "keys of rings 0..20 are exactly 0..1680");
        assertArrayEquals(new int[]{58_000, -3}, BiomeBinCodec.binCoords(BiomeBinCodec.binKey(58_000, -3)), "far rings stay exact");
    }
}
