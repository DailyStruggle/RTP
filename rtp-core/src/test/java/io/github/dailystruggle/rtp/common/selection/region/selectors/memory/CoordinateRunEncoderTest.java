package io.github.dailystruggle.rtp.common.selection.region.selectors.memory;

import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CoordinateRunEncoder Hilbert RLE tests (ADR-104 Phase 4)")
class CoordinateRunEncoderTest {

    @Test
    @DisplayName("Hilbert curve forward and inverse bijections are exact for all cells")
    void testHilbertBijectionExactness() {
        int order = 5; // 32x32 = 1024 cells
        int gridSize = 1 << order;
        int totalCells = gridSize * gridSize;

        boolean[] visited = new boolean[totalCells];
        for (int y = 0; y < gridSize; y++) {
            for (int x = 0; x < gridSize; x++) {
                int hIndex = CoordinateRunEncoder.xyToHilbert(x, y, order);
                assertTrue(hIndex >= 0 && hIndex < totalCells, "hIndex out of bounds: " + hIndex);
                visited[hIndex] = true;

                int[] xy = CoordinateRunEncoder.hilbertToXY(hIndex, order);
                assertEquals(x, xy[0], "X mismatch at (" + x + "," + y + ")");
                assertEquals(y, xy[1], "Y mismatch at (" + x + "," + y + ")");
            }
        }

        for (int i = 0; i < totalCells; i++) {
            assertTrue(visited[i], "Cell index not reached: " + i);
        }
    }

    @Test
    @DisplayName("Mask encoding and decoding roundtrip preserves exact coordinates and values")
    void testMaskRoundtripFidelity() {
        int order = 6; // 64x64 grid
        int size = 1 << order;
        boolean[][] mask = new boolean[size][size];

        // Draw multiple clustered patches of hazards
        for (int y = 10; y <= 25; y++) {
            for (int x = 15; x <= 30; x++) {
                mask[y][x] = true;
            }
        }
        for (int y = 40; y <= 50; y++) {
            for (int x = 5; x <= 20; x++) {
                mask[y][x] = true;
            }
        }

        CoordinateRunEncoder.EncodedPayload payload = CoordinateRunEncoder.encodeMask(
                mask, -1000, -1000, 1000, 1000, order);

        assertNotNull(payload);
        assertTrue(payload.runs().size() > 0);

        // Verify base64 serialization roundtrip
        String b64 = payload.toBase64();
        CoordinateRunEncoder.EncodedPayload decodedPayload = CoordinateRunEncoder.fromBase64(b64);

        assertEquals(payload.order(), decodedPayload.order());
        assertEquals(payload.totalRunCount(), decodedPayload.totalRunCount());
        assertEquals(payload.markedCellCount(), decodedPayload.markedCellCount());
        assertEquals(payload.runs(), decodedPayload.runs());

        // Decode to grid and verify exact match
        byte[][] grid = CoordinateRunEncoder.decodeToGrid(decodedPayload);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean expected = mask[y][x];
                boolean actual = (grid[y][x] > 0);
                assertEquals(expected, actual, "Fidelity mismatch at (" + x + "," + y + ")");
            }
        }
    }

    @Test
    @DisplayName("Compression ratio meets ADR-104 <5 KB threshold for 128x128 grid")
    void testCompressionRatioUnder5KB() {
        int order = CoordinateRunEncoder.DEFAULT_ORDER; // order 7 = 128x128 (16,384 cells)
        int size = 1 << order;
        boolean[][] mask = new boolean[size][size];

        // Scatter 15 clusters simulating biome or hazard pockets (e.g. ocean, lava, claims)
        Random rng = new Random(42);
        for (int c = 0; c < 15; c++) {
            int cx = rng.nextInt(size);
            int cy = rng.nextInt(size);
            int r = rng.nextInt(8) + 4;
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (dx * dx + dy * dy <= r * r) {
                        int nx = cx + dx;
                        int ny = cy + dy;
                        if (nx >= 0 && nx < size && ny >= 0 && ny < size) {
                            mask[ny][nx] = true;
                        }
                    }
                }
            }
        }

        CoordinateRunEncoder.EncodedPayload payload = CoordinateRunEncoder.encodeMask(
                mask, -2000, -2000, 2000, 2000, order);

        int byteSize = payload.binaryByteSize();
        System.out.println("[DEBUG_LOG] Encoded binary byte size for 128x128 multi-cluster hazard grid: " + byteSize + " bytes (threshold: 5120 bytes)");
        assertTrue(byteSize < 5120, "Binary payload size (" + byteSize + ") exceeded 5 KB target");
    }

    @Test
    @DisplayName("Encode directly from MemoryShape with zero chunk I/O")
    void testEncodeFromMemoryShape() throws Exception {
        Square square = new Square("TEST_RLE_SQUARE");
        square.set(GenericMemoryShapeParams.radius, 1000L);
        square.set(GenericMemoryShapeParams.centerRadius, 100L);
        square.set(GenericMemoryShapeParams.centerX, 0L);
        square.set(GenericMemoryShapeParams.centerZ, 0L);

        // Seed a contiguous patch of bad locations
        java.util.List<Long> raw = new java.util.ArrayList<>();
        for (int dx = -20; dx <= 20; dx++) {
            for (int dz = -20; dz <= 20; dz++) {
                raw.add(square.xzToLocation(200L + dx, 200L + dz));
            }
        }
        long[] keys = raw.stream().distinct().mapToLong(Long::longValue).toArray();
        java.util.Arrays.sort(keys);

        java.lang.reflect.Field keysField = MemoryShape.class.getDeclaredField("badKeysCache");
        keysField.setAccessible(true);
        keysField.set(square, keys);
        long[] sums = new long[keys.length];
        for (int i = 0; i < keys.length; i++) sums[i] = i + 1L;
        java.lang.reflect.Field sumsField = MemoryShape.class.getDeclaredField("badPrefixSumsCache");
        sumsField.setAccessible(true);
        sumsField.set(square, sums);
        byte[] causes = new byte[keys.length];
        java.util.Arrays.fill(causes, (byte) LocationGenerator.FailTypes.safety.ordinal());
        java.lang.reflect.Field snapField = MemoryShape.class.getDeclaredField("badLocationsSnapshot");
        snapField.setAccessible(true);
        long[] expiries = new long[keys.length];
        snapField.set(square, new MemoryShape.BadLocationsSnapshot(keys, sums, causes, expiries));

        CoordinateRunEncoder.EncodedPayload payload = CoordinateRunEncoder.encode(
                square, -1000, -1000, 1000, 1000, 7);

        assertNotNull(payload);
        assertTrue(payload.markedCellCount() >= 1);
        assertTrue(payload.binaryByteSize() < 5120);

        byte[][] grid = CoordinateRunEncoder.decodeToGrid(payload);
        assertNotNull(grid);
    }
}
