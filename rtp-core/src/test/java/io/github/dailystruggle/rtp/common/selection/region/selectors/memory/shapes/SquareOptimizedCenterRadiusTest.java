package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-S-004 / ADR-001 / ADR-085: {@code centerRadius} must still carve the center hole out of
 * the optimized square shape ({@link SquareOptimizedDualLayer}). The optimized addressing excludes
 * the center by <em>shifting the spiral start</em> - it drops the inner {@code kInner = cr/p} macro
 * rings from the key space ({@code macroLoc = fullMacroIdx - 4*kInner*kInner}) rather than
 * rejection-sampling the whole plane.
 *
 * <p>Addressing works in macro tiles of edge {@code p}, indexed with {@code floorDiv}, so the tile
 * grid is centred asymmetrically (the negative axis reaches one tile further than the positive).
 * These tests therefore pin the guarantees that actually hold: no addressable index resolves into
 * the center hole, every hole chunk is refused by the forward map, the key space is a clean
 * bijection ({@code locationToXZ} then {@code xzToLocation} is identity over {@code [0, range)}),
 * and each resolved chunk stays within {@code radius}.
 */
public class SquareOptimizedCenterRadiusTest {

    static {
        MockRTPServerAccessor accessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
        RTP.serverAccessor = accessor;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
    }

    // Fixed macro-tile edge keeps the center hole aligned to the tile grid so the exclusion is exact.
    private static final int POINT_EDGE_CHUNKS = 16;

    private static SquareOptimizedDualLayer shape(int centerRadius, int radius) {
        SquareOptimizedDualLayer s =
                new SquareOptimizedDualLayer("SQUARE_OPTIMIZED_DUAL_LAYER", POINT_EDGE_CHUNKS);
        s.set(GenericMemoryShapeParams.radius, (long) radius);
        s.set(GenericMemoryShapeParams.centerRadius, (long) centerRadius);
        s.set(GenericMemoryShapeParams.expand, false);
        s.setSpatialResolution(1L);
        return s;
    }

    @ParameterizedTest(name = "centerRadius={0} radius={1}: every mapped index lands outside the center hole")
    @CsvSource({"64,256", "32,128", "16,64", "0,128"})
    @DisplayName("sweeping [0, range) through locationToXZ never resolves a cell inside centerRadius")
    void reverseSweep_neverEntersCenterHole(int centerRadius, int radius) {
        SquareOptimizedDualLayer s = shape(centerRadius, radius);
        long range = s.getRange();
        MutableRTPCoords coords = new MutableRTPCoords(0, 0);
        for (long loc = 0; loc < range; loc++) {
            s.locationToXZ(loc, coords);
            int r = Math.max(Math.abs(coords.x), Math.abs(coords.z));
            assertTrue(r >= centerRadius,
                    "index " + loc + " resolved to (" + coords.x + "," + coords.z + ") on ring " + r
                            + ", inside centerRadius " + centerRadius);
            // floorDiv tile grid lets the negative edge reach the outer tile boundary itself.
            assertTrue(r <= radius,
                    "index " + loc + " resolved to (" + coords.x + "," + coords.z + ") on ring " + r
                            + ", beyond radius " + radius);
        }
    }

    @ParameterizedTest(name = "centerRadius={0} radius={1}: chunks inside the hole are refused by the forward map")
    @CsvSource({"64,256", "32,128", "16,64"})
    @DisplayName("every chunk with chebyshev < centerRadius maps to the OUT_OF_DOMAIN sentinel (-1)")
    void forwardMap_refusesCenterHole(int centerRadius, int radius) {
        SquareOptimizedDualLayer s = shape(centerRadius, radius);
        for (int x = -(centerRadius - 1); x <= centerRadius - 1; x++) {
            for (int z = -(centerRadius - 1); z <= centerRadius - 1; z++) {
                assertEquals(-1L, s.xzToLocation((long) x, (long) z),
                        "center chunk (" + x + "," + z + ") must be excluded from the domain");
                assertFalse(s.contains(x, z),
                        "center chunk (" + x + "," + z + ") must not be contained");
            }
        }
    }

    @ParameterizedTest(name = "centerRadius={0} radius={1}: key space is a clean bijection")
    @CsvSource({"64,256", "32,128", "16,64", "0,128"})
    @DisplayName("every index in [0, range) resolves to a distinct annulus chunk that maps back to itself")
    void keySpace_isCleanBijection(int centerRadius, int radius) {
        SquareOptimizedDualLayer s = shape(centerRadius, radius);
        long range = s.getRange();
        Set<Long> chunks = new HashSet<>();
        MutableRTPCoords coords = new MutableRTPCoords(0, 0);
        for (long loc = 0; loc < range; loc++) {
            s.locationToXZ(loc, coords);
            assertTrue(chunks.add(key(coords.x, coords.z)),
                    "index " + loc + " resolved to (" + coords.x + "," + coords.z
                            + "), already reached by a lower index");
            long back = s.xzToLocation((long) coords.x, (long) coords.z);
            assertEquals(loc, back,
                    "round trip mismatch: index " + loc + " -> (" + coords.x + "," + coords.z
                            + ") -> " + back);
        }
        assertEquals(range, chunks.size(), "each index must resolve to a distinct chunk");
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
    }
}
