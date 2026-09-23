package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.util;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SquareGeometry unit and branch coverage")
class SquareGeometryTest {

    @Test
    void testSquareOct2CoordsAllOctants() {
        MutableRTPCoords coords = new MutableRTPCoords(0, 0);
        long radius = 10L;

        // Octant 0: shortStep = 0 -> x = radius, z = 0
        SquareGeometry.squareOct2Coords(radius, 0.0, coords);
        assertEquals(10, coords.x);
        assertEquals(0, coords.z);

        // Octant 0 mid: shortStep = 5 -> x = radius, z = 5
        SquareGeometry.squareOct2Coords(radius, 5.0, coords);
        assertEquals(10, coords.x);
        assertEquals(5, coords.z);

        // Octant 1: shortStep = 2 -> x = 10 - 2 = 8, z = 10
        SquareGeometry.squareOct2Coords(radius, 12.0, coords);
        assertEquals(8, coords.x);
        assertEquals(10, coords.z);

        // Octant 2: shortStep = 3 -> x = -3, z = 10
        SquareGeometry.squareOct2Coords(radius, 23.0, coords);
        assertEquals(-3, coords.x);
        assertEquals(10, coords.z);

        // Octant 3: shortStep = 4 -> x = -10, z = 10 - 4 = 6
        SquareGeometry.squareOct2Coords(radius, 34.0, coords);
        assertEquals(-10, coords.x);
        assertEquals(6, coords.z);

        // Octant 4: shortStep = 5 -> x = -10, z = -5
        SquareGeometry.squareOct2Coords(radius, 45.0, coords);
        assertEquals(-10, coords.x);
        assertEquals(-5, coords.z);

        // Octant 5: shortStep = 6 -> x = -(10 - 6) = -4, z = -10
        SquareGeometry.squareOct2Coords(radius, 56.0, coords);
        assertEquals(-4, coords.x);
        assertEquals(-10, coords.z);

        // Octant 6: shortStep = 7 -> x = 7, z = -10
        SquareGeometry.squareOct2Coords(radius, 67.0, coords);
        assertEquals(7, coords.x);
        assertEquals(-10, coords.z);

        // Default / Octant 7: shortStep = 8 -> x = 10, z = -(10 - 8) = -2
        SquareGeometry.squareOct2Coords(radius, 78.0, coords);
        assertEquals(10, coords.x);
        assertEquals(-2, coords.z);
    }

    @Test
    void testPerimeterStep() {
        // radius == 0
        assertEquals(0L, SquareGeometry.perimeterStep(0, 0, 0L));

        long radius = 5L;
        // z == radius -> 2*radius - x
        assertEquals(5L, SquareGeometry.perimeterStep(5, 5, radius));
        assertEquals(10L, SquareGeometry.perimeterStep(0, 5, radius));
        assertEquals(15L, SquareGeometry.perimeterStep(-5, 5, radius));

        // x == -radius -> 4*radius - z
        assertEquals(20L, SquareGeometry.perimeterStep(-5, 0, radius));
        assertEquals(25L, SquareGeometry.perimeterStep(-5, -5, radius));

        // z == -radius -> 6*radius + x
        assertEquals(30L, SquareGeometry.perimeterStep(0, -5, radius));
        assertEquals(35L, SquareGeometry.perimeterStep(5, -5, radius));

        // Other points: z >= 0 -> z; else 8*radius + z
        assertEquals(2L, SquareGeometry.perimeterStep(5, 2, radius));
        assertEquals(38L, SquareGeometry.perimeterStep(5, -2, radius));
    }

    @Test
    void testRingStartAndRingOf() {
        // cr == 0
        assertEquals(1L, SquareGeometry.ringStart(1L, 0L));
        assertEquals(9L, SquareGeometry.ringStart(2L, 0L));
        assertEquals(25L, SquareGeometry.ringStart(3L, 0L));

        // cr > 0
        long startWithCr = SquareGeometry.ringStart(3L, 1L);
        assertEquals(24L, startWithCr);

        // ringOf with cr == 0
        assertEquals(0L, SquareGeometry.ringOf(0L, 0L));
        assertEquals(0L, SquareGeometry.ringOf(-5L, 0L));
        assertEquals(1L, SquareGeometry.ringOf(1L, 0L));
        assertEquals(1L, SquareGeometry.ringOf(8L, 0L));
        assertEquals(2L, SquareGeometry.ringOf(9L, 0L));
        assertEquals(2L, SquareGeometry.ringOf(24L, 0L));
        assertEquals(3L, SquareGeometry.ringOf(25L, 0L));

        // ringOf with cr > 0
        assertEquals(1L, SquareGeometry.ringOf(0L, 1L));
        long r = SquareGeometry.ringOf(100L, 2L);
        assertTrue(r >= 2L);
    }
}
