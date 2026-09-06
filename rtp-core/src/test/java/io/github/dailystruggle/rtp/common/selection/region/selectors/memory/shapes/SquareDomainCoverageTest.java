package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.NormalDistributionParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-S-004 / ADR-001: every cell between {@code centerRadius} and {@code radius} has to be
 * addressable in both directions, otherwise a slice of the region is either never offered as a
 * destination or can never carry learned state.
 *
 * <p>The map has to be a bijection: {@link Square#xzToLocation(long, long)} over the annulus must
 * land inside {@code [0, getRange())} and never collide, and sweeping every index of
 * {@code [0, getRange())} through {@link Square#locationToXZ(long)} must visit each of those cells
 * exactly once. Ring {@code R} therefore owns exactly its own {@code 8R} indices; the earlier
 * {@code 4(R^2 - cr^2)} allotment gave it {@code 8R + 4}, forcing 4 aliased cells per ring.
 */
public class SquareDomainCoverageTest {

    static {
        MockRTPServerAccessor accessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
        RTP.serverAccessor = accessor;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
    }

    private static Square shape(int centerRadius, int radius) {
        Square shape = new Square();
        shape.set(GenericMemoryShapeParams.radius, radius);
        shape.set(GenericMemoryShapeParams.centerRadius, centerRadius);
        return shape;
    }

    /** Cells of the annulus {@code centerRadius <= max(|x|,|z|) < radius}, packed into a long key. */
    private static Set<Long> annulusCells(int centerRadius, int radius) {
        Set<Long> cells = new HashSet<>();
        for (int r = centerRadius; r < radius; r++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != r) continue;
                    cells.add(key(x, z));
                }
            }
        }
        return cells;
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static String unkey(long k) {
        return "(" + (int) (k >> 32) + "," + (int) k + ")";
    }

    @ParameterizedTest(name = "centerRadius={0} radius={1}: forward map covers the annulus injectively")
    @CsvSource({"0,16", "1,16", "8,32", "16,48", "64,128"})
    @DisplayName("every cell between centerRadius and radius maps to a distinct index in [0, range)")
    void forwardMap_coversAnnulusInjectively(int centerRadius, int radius) {
        Square shape = shape(centerRadius, radius);
        long range = shape.getRange();
        Set<Long> indices = new HashSet<>();
        int cells = 0;
        for (int r = centerRadius; r < radius; r++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != r) continue;
                    cells++;
                    long loc = shape.xzToLocation((long) x, (long) z);
                    assertTrue(loc >= 0L && loc < range,
                            "cell (" + x + "," + z + ") on ring " + r + " mapped to " + loc
                                    + ", outside [0," + range + ")");
                    assertTrue(indices.add(loc),
                            "index " + loc + " reused by cell (" + x + "," + z + ") on ring " + r);
                }
            }
        }
        assertEquals(cells, indices.size(), "one index per annulus cell");
        assertEquals(cells, range,
                "range " + range + " is not the cell count " + cells + ", so the map cannot be a bijection");
    }

    @ParameterizedTest(name = "centerRadius={0} radius={1}: reverse sweep visits every annulus cell once")
    @CsvSource({"0,16", "1,16", "8,32", "16,48", "64,128"})
    @DisplayName("sweeping [0, range) through locationToXZ visits every cell of the annulus exactly once")
    void reverseSweep_visitsEveryAnnulusCell(int centerRadius, int radius) {
        Square shape = shape(centerRadius, radius);
        long range = shape.getRange();
        Set<Long> expected = annulusCells(centerRadius, radius);
        Set<Long> visited = new HashSet<>();
        MutableRTPCoords coords = new MutableRTPCoords(0, 0);
        for (long loc = 0; loc < range; loc++) {
            shape.locationToXZ(loc, coords);
            int r = Math.max(Math.abs(coords.x), Math.abs(coords.z));
            assertTrue(r >= centerRadius && r < radius,
                    "index " + loc + " resolved to (" + coords.x + "," + coords.z + ") on ring " + r
                            + ", outside [" + centerRadius + "," + radius + ")");
            assertTrue(visited.add(key(coords.x, coords.z)),
                    "index " + loc + " resolved to (" + coords.x + "," + coords.z
                            + "), already reached by a lower index");
        }

        Set<Long> missing = new HashSet<>(expected);
        missing.removeAll(visited);
        assertTrue(missing.isEmpty(),
                missing.size() + " of " + expected.size() + " cells were never visited, e.g. "
                        + missing.stream().limit(8).map(SquareDomainCoverageTest::unkey).toList());
        assertEquals(expected.size(), visited.size(), "one cell per index");
    }

    @ParameterizedTest(name = "centerRadius={0} radius={1}: index -> cell -> index is the identity")
    @CsvSource({"0,16", "1,16", "8,32", "16,48", "64,128"})
    @DisplayName("locationToXZ then xzToLocation returns the original index for every index in range")
    void roundTrip_indexToCellToIndex_isIdentity(int centerRadius, int radius) {
        Square shape = shape(centerRadius, radius);
        MutableRTPCoords coords = new MutableRTPCoords(0, 0);
        for (long loc = 0; loc < shape.getRange(); loc++) {
            shape.locationToXZ(loc, coords);
            assertEquals(loc, shape.xzToLocation((long) coords.x, (long) coords.z),
                    "index " + loc + " -> (" + coords.x + "," + coords.z + ") -> other index");
        }
    }

    @ParameterizedTest(name = "centerRadius={0} radius={1}: cell -> index -> cell is the identity")
    @CsvSource({"0,16", "1,16", "8,32", "16,48", "64,128"})
    @DisplayName("xzToLocation then locationToXZ returns the original cell for every annulus cell")
    void roundTrip_cellToIndexToCell_isIdentity(int centerRadius, int radius) {
        Square shape = shape(centerRadius, radius);
        MutableRTPCoords coords = new MutableRTPCoords(0, 0);
        for (int r = centerRadius; r < radius; r++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != r) continue;
                    long loc = shape.xzToLocation((long) x, (long) z);
                    shape.locationToXZ(loc, coords);
                    assertEquals(unkey(key(x, z)), unkey(key(coords.x, coords.z)),
                            "cell (" + x + "," + z + ") -> " + loc + " -> other cell");
                }
            }
        }
    }

    @ParameterizedTest(name = "centerRadius={0} radius={1}: both xzToLocation overloads agree")
    @CsvSource({"0,16", "8,32", "64,128"})
    @DisplayName("the coords overload of xzToLocation matches the (x,z) overload on every cell")
    void forwardOverloads_agree(int centerRadius, int radius) {
        Square shape = shape(centerRadius, radius);
        MutableRTPCoords coords = new MutableRTPCoords(0, 0);
        for (int r = centerRadius; r < radius; r++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != r) continue;
                    coords.setXZ(x, z);
                    assertEquals(shape.xzToLocation((long) x, (long) z), shape.xzToLocation(coords),
                            "overloads disagree on (" + x + "," + z + ")");
                }
            }
        }
    }

    @Test
    @DisplayName("an off-origin centre keeps the map a bijection in both directions")
    void offOriginCentre_isBijective() {
        int centerRadius = 4;
        int radius = 24;
        Square shape = shape(centerRadius, radius);
        shape.set(GenericMemoryShapeParams.centerX, 137L);
        shape.set(GenericMemoryShapeParams.centerZ, -91L);

        long range = shape.getRange();
        Set<Long> visited = new HashSet<>();
        MutableRTPCoords coords = new MutableRTPCoords(0, 0);
        for (long loc = 0; loc < range; loc++) {
            shape.locationToXZ(loc, coords);
            int r = Math.max(Math.abs(coords.x - 137), Math.abs(coords.z + 91));
            assertTrue(r >= centerRadius && r < radius,
                    "index " + loc + " left the annulus at ring " + r);
            assertTrue(visited.add(key(coords.x, coords.z)),
                    "index " + loc + " revisits (" + coords.x + "," + coords.z + ")");
            assertEquals(loc, shape.xzToLocation((long) coords.x, (long) coords.z),
                    "index " + loc + " does not round-trip");
        }
        assertEquals(range, visited.size(), "one cell per index");
    }

    @Test
    @DisplayName("Square_Normal round-trips index -> cell -> index for every index in range")
    void normalVariant_roundTrip_isIdentity() {
        int centerRadius = 8;
        int radius = 40;
        Square_Normal shape = new Square_Normal();
        shape.set(NormalDistributionParams.radius, radius);
        shape.set(NormalDistributionParams.centerRadius, centerRadius);

        MutableRTPCoords coords = new MutableRTPCoords(0, 0);
        for (long loc = 0; loc < shape.getRange(); loc++) {
            shape.locationToXZ(loc, coords);
            assertEquals(loc, shape.xzToLocation((long) coords.x, (long) coords.z),
                    "index " + loc + " -> (" + coords.x + "," + coords.z + ") -> other index");
        }
    }

    @Test
    @DisplayName("Square_Normal shares the geometry, so its sweep must cover the annulus once too")
    void normalVariant_reverseSweep_visitsEveryAnnulusCell() {
        int centerRadius = 16;
        int radius = 48;
        Square_Normal shape = new Square_Normal();
        shape.set(NormalDistributionParams.radius, radius);
        shape.set(NormalDistributionParams.centerRadius, centerRadius);

        Set<Long> expected = annulusCells(centerRadius, radius);
        assertEquals(expected.size(), shape.getRange(), "range is not the cell count");
        Set<Long> visited = new HashSet<>();
        MutableRTPCoords coords = new MutableRTPCoords(0, 0);
        for (long loc = 0; loc < shape.getRange(); loc++) {
            shape.locationToXZ(loc, coords);
            assertTrue(visited.add(key(coords.x, coords.z)),
                    "index " + loc + " revisits (" + coords.x + "," + coords.z + ")");
        }
        expected.removeAll(visited);
        assertTrue(expected.isEmpty(), expected.size() + " cells were never visited");
    }
}
