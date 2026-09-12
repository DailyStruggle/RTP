package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-S-004: a bad-location mark whose 1D index is not addressable has to be refused
 * visibly, not accepted and then dropped by the rebuild.
 *
 * <p>{@link Square#xzToLocation(long, long)} numbers only the region's own cells, so every
 * cell inside the {@code centerRadius} hole maps to a negative index. Those cells are
 * unreachable by {@link MemoryShape#locationToXZ(long)} and therefore must not enter the
 * learned state - but the refusal has to show up in the shape's counters.
 */
public class MemoryShapeOutOfDomainMarkTest {

    static {
        MockRTPServerAccessor accessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
        RTP.serverAccessor = accessor;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
    }

    private static final int CENTER_RADIUS = 64;

    private static Square shape() {
        Square shape = new Square();
        shape.set(GenericMemoryShapeParams.radius, 1024);
        shape.set(GenericMemoryShapeParams.centerRadius, CENTER_RADIUS);
        return shape;
    }

    /** Marks an {@code edge} x {@code edge} block of cells centred on {@code (cx, 0)}. */
    private static Square markBlock(int cx, int edge) {
        Square shape = shape();
        int half = edge / 2;
        for (int x = cx - half; x < cx + half; x++) {
            for (int z = -half; z < half; z++) {
                shape.addBadLocation(
                        shape.xzToLocation((long) x, (long) z), LocationGenerator.FailTypes.safety);
            }
        }
        shape.flushAndRebuild(0L);
        return shape;
    }

    @ParameterizedTest(name = "off-centre {0}x{0} block records every cell")
    @ValueSource(ints = {8, 16, 32, 64, 128})
    @DisplayName("REQ-RTP-S-004: marks inside the addressable domain are all retained")
    void offCentreBlock_recordsEveryCell(int edge) {
        Square shape = markBlock(512, edge);
        assertEquals((long) edge * edge, shape.getEffectiveBadCount(),
                "off-centre block must record edge*edge cells");
        assertEquals(0L, shape.getOutOfDomainMarkCount(),
                "no off-centre cell may be out of domain");
    }

    @ParameterizedTest(name = "origin-centred {0}x{0} block accounts for every mark")
    @ValueSource(ints = {8, 16, 32, 64, 128})
    @DisplayName("REQ-RTP-S-004: marks inside the centerRadius hole are refused, never silently dropped")
    void originCentredBlock_refusesOutOfDomainMarksVisibly(int edge) {
        Square shape = markBlock(0, edge);
        long recorded = shape.getEffectiveBadCount();
        long refused = shape.getOutOfDomainMarkCount();
        assertEquals((long) edge * edge, recorded + refused,
                "every applied mark has to be either recorded or counted as refused");
        // Blocks fully inside the hole address nothing at all.
        if (edge <= 2 * CENTER_RADIUS) {
            assertTrue(refused > 0L, "cells inside the centerRadius hole must be refused");
        }
    }

    @Test
    @DisplayName("centerRadius hole maps to negative, unaddressable indices")
    void innerHole_mapsOutsideDomain() {
        Square shape = shape();
        long range = shape.getRange();
        for (int x = -CENTER_RADIUS + 1; x < CENTER_RADIUS; x++) {
            for (int z = -CENTER_RADIUS + 1; z < CENTER_RADIUS; z++) {
                long loc = shape.xzToLocation((long) x, (long) z);
                assertTrue(loc < 0L,
                        "(" + x + "," + z + ") is inside the hole yet mapped to " + loc);
                assertTrue(loc < range, "index must stay below range " + range);
            }
        }
    }

    @Test
    @DisplayName("every ring maps injectively into [0, range) - no octant-seam aliasing")
    void rings_mapInjectivelyIntoDomain() {
        Square shape = shape();
        long range = shape.getRange();
        for (int r : new int[] {CENTER_RADIUS, CENTER_RADIUS + 1, CENTER_RADIUS + 2, 128, 512, 1023}) {
            Map<Long, int[]> seen = new HashMap<>();
            int cells = 0;
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != r) continue;
                    cells++;
                    long loc = shape.xzToLocation((long) x, (long) z);
                    assertTrue(loc >= 0L && loc < range,
                            "ring " + r + " cell (" + x + "," + z + ") mapped outside [0," + range + "): " + loc);
                    int[] prior = seen.putIfAbsent(loc, new int[] {x, z});
                    assertNull(prior, "index " + loc + " shared by (" + x + "," + z + ") and ("
                            + (prior == null ? "" : prior[0] + "," + prior[1]) + ")");
                }
            }
            assertEquals(8 * r, cells, "ring " + r + " must hold 8R cells");
            assertEquals(cells, seen.size(), "ring " + r + " indices must be distinct");
        }
    }

    @Test
    @DisplayName("xzToLocation is the inverse of the ring walk used by locationToXZ")
    void forwardIndex_matchesRingWalkOrder() {
        Square shape = shape();
        int r = CENTER_RADIUS + 3;
        // Each ring owns exactly its own 8R indices, so the starts telescope to 4(R(R-1) - cr(cr-1)).
        long ringStart =
                ((long) r * (r - 1) - (long) CENTER_RADIUS * (CENTER_RADIUS - 1)) * 4L;
        // Counter-clockwise walk from (R,0): right edge up, top edge left, left edge down,
        // bottom edge right. Step k must land on ringStart + k.
        long step = 0L;
        for (int z = 0; z < r; z++) assertEquals(ringStart + step++, shape.xzToLocation(r, z));
        for (int x = r; x >= -r; x--) assertEquals(ringStart + step++, shape.xzToLocation(x, r));
        for (int z = r - 1; z >= -r; z--) assertEquals(ringStart + step++, shape.xzToLocation(-r, z));
        for (int x = -r + 1; x <= r; x++) assertEquals(ringStart + step++, shape.xzToLocation(x, -r));
        for (int z = -r + 1; z <= -1; z++) assertEquals(ringStart + step++, shape.xzToLocation(r, z));
        assertEquals(8L * r, step, "the walk must cover the whole ring exactly once");
    }
}
