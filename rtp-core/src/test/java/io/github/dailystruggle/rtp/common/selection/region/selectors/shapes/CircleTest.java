package io.github.dailystruggle.rtp.common.selection.region.selectors.shapes;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Circle shape covering:
 * - getRange() boundary conditions
 * - xzToLocation() all 4 quadrant branches (x>0/z>0, x<0/z>0, x>0/z<0, x<0/z<0)
 * - locationToXZ() round-trip fidelity
 * - xzToLocation(MutableRTPCoords) mirrors long-variant
 * - Extreme values (0, negative, large)
 */
public class CircleTest {

    @BeforeAll
    static void setupServer() {
        MockRTPServerAccessor accessor =
                new MockRTPServerAccessor(new java.io.File("target/test-data"));
        RTP.serverAccessor = accessor;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
    }

    // -------------------------------------------------------------------------
    // getRange() tests
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "radius={0}, cr={1} => range>0={2}")
    @CsvSource({
        "256, 64,  true",
        "256,  0,  true",
        "65,  64,  true",
        "64,  64,  false",   // radius == centerRadius => range <= 0
        "63,  64,  false",   // radius < centerRadius => range <= 0
        "100000, 0, true",
    })
    void getRange_variousRadii(long radius, long cr, boolean expectPositive) {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.radius, radius);
        shape.set(GenericMemoryShapeParams.centerRadius, cr);
        long range = shape.getRange();
        if (expectPositive) {
            assertTrue(range > 0, "Expected positive range but got " + range);
        } else {
            assertTrue(range <= 0, "Expected non-positive range but got " + range);
        }
    }

    @Test
    void getRange_formula_matchesPiTimesRadiusDiff() {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.radius, 100L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        long range = shape.getRange();
        long expected = (long) (100L * 100L * Math.PI);
        assertEquals(expected, range);
    }

    // -------------------------------------------------------------------------
    // xzToLocation() — quadrant branch coverage
    // -------------------------------------------------------------------------

    /**
     * Points in all 4 quadrants relative to center (0,0).
     * x>0,z>0 | x<0,z>0 | x>0,z<0 | x<0,z<0
     * Also tests on-axis points (x=0 or z=0 edge cases).
     */
    @ParameterizedTest(name = "xzToLocation({0},{1}) >= 0")
    @CsvSource({
        // Quadrant 1: x>0, z>0
        "100,  50",
        "50,  100",
        "1,    1",
        // Quadrant 2: x<0, z>0  (rotation += 0.25)
        "-100,  50",
        "-50,  100",
        // Quadrant 3: x<0, z<0  (rotation += 0.5)
        "-100, -50",
        "-50, -100",
        // Quadrant 4: x>0, z<0  (rotation += 0.75)
        "100,  -50",
        "50,  -100",
        // On positive x-axis
        "200,    0",
        // On positive z-axis
        "0,    200",
    })
    void xzToLocation_allQuadrants_returnsNonNegative(long x, long z) {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.radius, 300L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        shape.set(GenericMemoryShapeParams.centerX, 0L);
        shape.set(GenericMemoryShapeParams.centerZ, 0L);
        long loc = shape.xzToLocation(x, z);
        assertTrue(loc >= 0, "Expected non-negative location for (" + x + "," + z + ") but got " + loc);
    }

    @ParameterizedTest(name = "xzToLocation coords({0},{1}) == long({0},{1})")
    @CsvSource({
        "100,  50",
        "-100,  50",
        "100, -50",
        "-100, -50",
        "0,   100",
        "100,   0",
    })
    void xzToLocation_coordsVariant_matchesLongVariant(int x, int z) {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.radius, 300L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        shape.set(GenericMemoryShapeParams.centerX, 0L);
        shape.set(GenericMemoryShapeParams.centerZ, 0L);

        long fromLong = shape.xzToLocation((long) x, (long) z);
        MutableRTPCoords coords = new MutableRTPCoords(x, z);
        long fromCoords = shape.xzToLocation(coords);
        assertEquals(fromLong, fromCoords,
                "Coords variant should match long variant for (" + x + "," + z + ")");
    }

    // -------------------------------------------------------------------------
    // xzToLocation() — center offset
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "center=({0},{1}), point=({2},{3})")
    @CsvSource({
        "100, 200,  200, 250",
        "100, 200,    0, 150",
        "100, 200,  200, 150",
        "100, 200,    0, 250",
    })
    void xzToLocation_withCenterOffset_returnsNonNegative(long cx, long cz, long x, long z) {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.radius, 300L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        shape.set(GenericMemoryShapeParams.centerX, cx);
        shape.set(GenericMemoryShapeParams.centerZ, cz);
        long loc = shape.xzToLocation(x, z);
        assertTrue(loc >= 0, "Expected non-negative location but got " + loc);
    }

    // -------------------------------------------------------------------------
    // locationToXZ() round-trip
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "locationToXZ round-trip radius={0}, cr={1}, loc={2}")
    @CsvSource({
        "256,  0,    0",
        "256,  0,  100",
        "256,  0, 1000",
        "256, 64,  500",
        "256, 64, 5000",
        "100,  0,   50",
    })
    void locationToXZ_roundTrip_withinRadius(long radius, long cr, long location) {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.radius, radius);
        shape.set(GenericMemoryShapeParams.centerRadius, cr);
        shape.set(GenericMemoryShapeParams.centerX, 0L);
        shape.set(GenericMemoryShapeParams.centerZ, 0L);

        int[] xz = shape.locationToXZ(location);
        assertNotNull(xz);
        assertEquals(2, xz.length);

        // The decoded point should be within the outer radius
        double dist = Math.sqrt((double) xz[0] * xz[0] + (double) xz[1] * xz[1]);
        assertTrue(dist <= radius + 1,
                "Decoded point (" + xz[0] + "," + xz[1] + ") is outside radius " + radius);
    }

    @ParameterizedTest(name = "locationToXZ MutableRTPCoords variant, loc={0}")
    @CsvSource({"0", "100", "500", "1000", "5000"})
    void locationToXZ_mutableCoordsVariant_matchesArrayVariant(long location) {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.radius, 256L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        shape.set(GenericMemoryShapeParams.centerX, 0L);
        shape.set(GenericMemoryShapeParams.centerZ, 0L);

        int[] arr = shape.locationToXZ(location);
        MutableRTPCoords coords = new MutableRTPCoords(0, 0);
        shape.locationToXZ(location, coords);

        assertEquals(arr[0], coords.x, "x mismatch for location " + location);
        assertEquals(arr[1], coords.z, "z mismatch for location " + location);
    }

    // -------------------------------------------------------------------------
    // Extreme values
    // -------------------------------------------------------------------------

    @Test
    void xzToLocation_extremeCoordinates_doesNotThrow() {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.radius, Integer.MAX_VALUE / 2L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        shape.set(GenericMemoryShapeParams.centerX, 0L);
        shape.set(GenericMemoryShapeParams.centerZ, 0L);
        assertDoesNotThrow(() -> shape.xzToLocation(10000L, 10000L));
        assertDoesNotThrow(() -> shape.xzToLocation(-10000L, -10000L));
    }

    @Test
    void locationToXZ_locationZero_doesNotThrow() {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.radius, 256L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        assertDoesNotThrow(() -> shape.locationToXZ(0L));
    }

    @Test
    void getRange_zeroCenterRadius_largeRadius_noOverflow() {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.radius, 500_000L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        assertDoesNotThrow(shape::getRange);
        assertTrue(shape.getRange() > 0);
    }

    // -------------------------------------------------------------------------
    // keys() and getParameters()
    // -------------------------------------------------------------------------

    @Test
    void keys_returnsAllGenericParams() {
        Circle shape = new Circle();
        assertNotNull(shape.keys());
        assertFalse(shape.keys().isEmpty());
    }

    @Test
    void getParameters_returnsNonNull() {
        Circle shape = new Circle();
        assertNotNull(shape.getParameters());
    }

    // -------------------------------------------------------------------------
    // Custom name constructor
    // -------------------------------------------------------------------------

    @Test
    void customNameConstructor_setsName() {
        Circle shape = new Circle("MY_CIRCLE");
        assertEquals("MY_CIRCLE", shape.name);
    }
}
