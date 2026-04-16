package io.github.dailystruggle.rtp.common.selection.region.selectors.shapes;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square_Normal;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.NormalDistributionParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Square_Normal shape covering:
 * - getRange() boundary conditions
 * - xzToLocation() all 8 octant branches
 * - locationToXZ() round-trip fidelity
 * - xzToLocation(MutableRTPCoords) mirrors long-variant
 * - NormalDistributionParams (mean, deviation) settings
 * - Extreme values (0, negative, large)
 */
public class SquareNormalTest {

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
        "64,  64,  false",
        "63,  64,  false",
        "100000, 0, true",
    })
    void getRange_variousRadii(long radius, long cr, boolean expectPositive) {
        Square_Normal shape = new Square_Normal();
        shape.set(NormalDistributionParams.radius, radius);
        shape.set(NormalDistributionParams.centerRadius, cr);
        long range = shape.getRange();
        if (expectPositive) {
            assertTrue(range > 0, "Expected positive range but got " + range);
        } else {
            assertTrue(range <= 0, "Expected non-positive range but got " + range);
        }
    }

    @Test
    void getRange_formula_is4TimesDiff() {
        Square_Normal shape = new Square_Normal();
        shape.set(NormalDistributionParams.radius, 100L);
        shape.set(NormalDistributionParams.centerRadius, 0L);
        long range = shape.getRange();
        assertEquals(100L * 100L * 4, range);
    }

    // -------------------------------------------------------------------------
    // xzToLocation() — all 8 octants
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "xzToLocation({0},{1}) octant coverage")
    @CsvSource({
        // Octant 1: x>0, z>0, z<x
        "100,  30",
        // Octant 2: x>0, z>0, z>x
        "30,  100",
        // Octant 3: x<0, z>0, |z|>|x|
        "-30,  100",
        // Octant 4: x<0, z>0, |x|>|z|
        "-100,  30",
        // Octant 5: x<0, z<0, |x|>|z|
        "-100, -30",
        // Octant 6: x<0, z<0, |z|>|x|
        "-30, -100",
        // Octant 7: x>0, z<0, |z|>|x|
        "30, -100",
        // Octant 8: x>0, z<0, |x|>|z|
        "100, -30",
        // On-axis
        "100,   0",
        "0,   100",
    })
    void xzToLocation_allOctants_returnsNonNegative(long x, long z) {
        Square_Normal shape = new Square_Normal();
        shape.set(NormalDistributionParams.radius, 300L);
        shape.set(NormalDistributionParams.centerRadius, 0L);
        shape.set(NormalDistributionParams.centerX, 0L);
        shape.set(NormalDistributionParams.centerZ, 0L);
        long loc = shape.xzToLocation(x, z);
        assertTrue(loc >= 0, "Expected non-negative location for (" + x + "," + z + ") but got " + loc);
    }

    @ParameterizedTest(name = "xzToLocation coords({0},{1}) == long({0},{1})")
    @CsvSource({
        "100,  30",
        "30,  100",
        "-30,  100",
        "-100,  30",
        "-100, -30",
        "-30, -100",
        "30, -100",
        "100, -30",
    })
    void xzToLocation_coordsVariant_matchesLongVariant(int x, int z) {
        Square_Normal shape = new Square_Normal();
        shape.set(NormalDistributionParams.radius, 300L);
        shape.set(NormalDistributionParams.centerRadius, 0L);
        shape.set(NormalDistributionParams.centerX, 0L);
        shape.set(NormalDistributionParams.centerZ, 0L);

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
        "50, 50,  150,  80",
        "50, 50,  -50,  80",
        "50, 50,  150, -50",
        "50, 50,  -50, -50",
    })
    void xzToLocation_withCenterOffset_returnsNonNegative(long cx, long cz, long x, long z) {
        Square_Normal shape = new Square_Normal();
        shape.set(NormalDistributionParams.radius, 300L);
        shape.set(NormalDistributionParams.centerRadius, 0L);
        shape.set(NormalDistributionParams.centerX, cx);
        shape.set(NormalDistributionParams.centerZ, cz);
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
        Square_Normal shape = new Square_Normal();
        shape.set(NormalDistributionParams.radius, radius);
        shape.set(NormalDistributionParams.centerRadius, cr);
        shape.set(NormalDistributionParams.centerX, 0L);
        shape.set(NormalDistributionParams.centerZ, 0L);

        int[] xz = shape.locationToXZ(location);
        assertNotNull(xz);
        assertEquals(2, xz.length);

        long chebyshev = Math.max(Math.abs(xz[0]), Math.abs(xz[1]));
        assertTrue(chebyshev <= radius + 1,
                "Decoded point (" + xz[0] + "," + xz[1] + ") is outside radius " + radius);
    }

    @ParameterizedTest(name = "locationToXZ MutableRTPCoords variant, loc={0}")
    @CsvSource({"0", "100", "500", "1000", "5000"})
    void locationToXZ_mutableCoordsVariant_matchesArrayVariant(long location) {
        Square_Normal shape = new Square_Normal();
        shape.set(NormalDistributionParams.radius, 256L);
        shape.set(NormalDistributionParams.centerRadius, 0L);
        shape.set(NormalDistributionParams.centerX, 0L);
        shape.set(NormalDistributionParams.centerZ, 0L);

        int[] arr = shape.locationToXZ(location);
        MutableRTPCoords coords = new MutableRTPCoords(0, 0);
        shape.locationToXZ(location, coords);

        assertEquals(arr[0], coords.x, "x mismatch for location " + location);
        assertEquals(arr[1], coords.z, "z mismatch for location " + location);
    }

    // -------------------------------------------------------------------------
    // NormalDistributionParams — mean and deviation
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "mean={0}, deviation={1}")
    @CsvSource({
        "0.5, 1.0",
        "0.0, 1.0",
        "1.0, 1.0",
        "0.5, 0.1",
        "0.5, 2.0",
    })
    void set_meanAndDeviation_doesNotThrow(double mean, double deviation) {
        Square_Normal shape = new Square_Normal();
        assertDoesNotThrow(() -> {
            shape.set(NormalDistributionParams.mean, mean);
            shape.set(NormalDistributionParams.deviation, deviation);
            shape.getRange();
        });
    }

    // -------------------------------------------------------------------------
    // Extreme values
    // -------------------------------------------------------------------------

    @Test
    void xzToLocation_extremeCoordinates_doesNotThrow() {
        Square_Normal shape = new Square_Normal();
        shape.set(NormalDistributionParams.radius, 100_000L);
        shape.set(NormalDistributionParams.centerRadius, 0L);
        shape.set(NormalDistributionParams.centerX, 0L);
        shape.set(NormalDistributionParams.centerZ, 0L);
        assertDoesNotThrow(() -> shape.xzToLocation(50000L, 50000L));
        assertDoesNotThrow(() -> shape.xzToLocation(-50000L, -50000L));
    }

    @Test
    void locationToXZ_locationZero_doesNotThrow() {
        Square_Normal shape = new Square_Normal();
        shape.set(NormalDistributionParams.radius, 256L);
        shape.set(NormalDistributionParams.centerRadius, 0L);
        assertDoesNotThrow(() -> shape.locationToXZ(0L));
    }

    @Test
    void getRange_largeRadius_noOverflow() {
        Square_Normal shape = new Square_Normal();
        shape.set(NormalDistributionParams.radius, 500_000L);
        shape.set(NormalDistributionParams.centerRadius, 0L);
        assertDoesNotThrow(shape::getRange);
        assertTrue(shape.getRange() > 0);
    }

    // -------------------------------------------------------------------------
    // keys() and getParameters()
    // -------------------------------------------------------------------------

    @Test
    void keys_returnsAllNormalDistributionParams() {
        Square_Normal shape = new Square_Normal();
        assertNotNull(shape.keys());
        assertFalse(shape.keys().isEmpty());
    }

    @Test
    void getParameters_returnsNonNull() {
        Square_Normal shape = new Square_Normal();
        assertNotNull(shape.getParameters());
    }

    // -------------------------------------------------------------------------
    // Custom name constructor
    // -------------------------------------------------------------------------

    @Test
    void customNameConstructor_setsName() {
        Square_Normal shape = new Square_Normal("MY_SQUARE_NORMAL");
        assertEquals("MY_SQUARE_NORMAL", shape.name);
    }
}
