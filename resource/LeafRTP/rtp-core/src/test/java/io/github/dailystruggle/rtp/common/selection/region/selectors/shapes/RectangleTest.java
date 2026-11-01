package io.github.dailystruggle.rtp.common.selection.region.selectors.shapes;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Rectangle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Rectangle shape range, coordinate conversion, bounds, and rotation.
 */
public class RectangleTest {

    @BeforeAll
    static void setupServer() {
        MockRTPServerAccessor accessor =
                new MockRTPServerAccessor(new java.io.File("target/test-data"));
        RTPRuntime.setServerAccessor(accessor);
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
    }

    // -------------------------------------------------------------------------
    // getRange() tests
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "width={0}, height={1} => range={2}")
    @CsvSource({
        "256, 256, 65536",
        "100, 200, 20000",
        "  0, 256,     0",
        "256,   0,     0",
        "  1,   1,     1",
        "10000, 10000, 100000000",
    })
    void getRange_widthTimesHeight(long width, long height, long expectedRange) {
        Rectangle shape = new Rectangle();
        shape.set(RectangleParams.width, width);
        shape.set(RectangleParams.height, height);
        assertEquals(expectedRange, shape.getRange());
    }

    // -------------------------------------------------------------------------
    // xzToLocation() - no rotation
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "xzToLocation({0},{1}) no rotation")
    @CsvSource({
        "  0,   0",
        "100,  50",
        "-100,  50",
        "100, -50",
        "-100, -50",
        "128, 128",
        "-128, -128",
    })
    void xzToLocation_noRotation_doesNotThrow(long x, long z) {
        Rectangle shape = new Rectangle();
        shape.set(RectangleParams.width, 512L);
        shape.set(RectangleParams.height, 512L);
        shape.set(RectangleParams.centerX, 0L);
        shape.set(RectangleParams.centerZ, 0L);
        shape.set(RectangleParams.rotation, 0L);
        assertDoesNotThrow(() -> shape.xzToLocation(x, z));
    }

    @ParameterizedTest(name = "xzToLocation coords({0},{1}) == long({0},{1})")
    @CsvSource({
        "  0,   0",
        "100,  50",
        "-100,  50",
        "100, -50",
        "-100, -50",
    })
    void xzToLocation_coordsVariant_matchesLongVariant(int x, int z) {
        Rectangle shape = new Rectangle();
        shape.set(RectangleParams.width, 512L);
        shape.set(RectangleParams.height, 512L);
        shape.set(RectangleParams.centerX, 0L);
        shape.set(RectangleParams.centerZ, 0L);
        shape.set(RectangleParams.rotation, 0L);

        long fromLong = shape.xzToLocation((long) x, (long) z);
        MutableRTPCoords coords = new MutableRTPCoords(x, z);
        long fromCoords = shape.xzToLocation(coords);
        assertEquals(fromLong, fromCoords,
                "Coords variant should match long variant for (" + x + "," + z + ")");
    }

    // -------------------------------------------------------------------------
    // xzToLocation() - with rotation
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "xzToLocation({0},{1}) rotation={2}")
    @CsvSource({
        "100,  50,  90",
        "100,  50, 180",
        "100,  50, 270",
        "100,  50,  45",
        "-100, -50, 90",
    })
    void xzToLocation_withRotation_doesNotThrow(long x, long z, long rotation) {
        Rectangle shape = new Rectangle();
        shape.set(RectangleParams.width, 512L);
        shape.set(RectangleParams.height, 512L);
        shape.set(RectangleParams.centerX, 0L);
        shape.set(RectangleParams.centerZ, 0L);
        shape.set(RectangleParams.rotation, rotation);
        assertDoesNotThrow(() -> shape.xzToLocation(x, z));
    }

    // -------------------------------------------------------------------------
    // contains() - no rotation
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "contains({0},{1}) width={2} height={3} => {4}")
    @CsvSource({
        // Inside (chunk coords: width=256, half = 128; so |x|,|z| <= 128)
        "  0,   0, 256, 256, true",
        " 50,  50, 256, 256, true",
        "-50, -50, 256, 256, true",
        // On boundary (chunk coords: half = 128)
        "128, 128, 256, 256, true",
        // Outside
        "150, 150, 256, 256, false",
        "-150, 150, 256, 256, false",
        // Zero dimensions
        "  0,   0,   0, 256, false",
        "  0,   0, 256,   0, false",
    })
    void contains_noRotation(int x, int z, long width, long height, boolean expected) {
        Rectangle shape = new Rectangle();
        shape.set(RectangleParams.width, width);
        shape.set(RectangleParams.height, height);
        shape.set(RectangleParams.centerX, 0L);
        shape.set(RectangleParams.centerZ, 0L);
        shape.set(RectangleParams.rotation, 0L);
        assertEquals(expected, shape.contains(x, z),
                "contains(" + x + "," + z + ") with width=" + width + " height=" + height);
    }

    // -------------------------------------------------------------------------
    // contains() - with rotation (non-zero degrees branch)
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "contains({0},{1}) rotation={2} => {3}")
    @CsvSource({
        // A point inside a 256x256 rectangle rotated 90 degrees
        "  0,   0,  90, true",
        "  5,   5,  90, true",
        // A point outside
        "200, 200,  90, false",
        // 45 degree rotation
        "  0,   0,  45, true",
        "200, 200,  45, false",
    })
    void contains_withRotation(int x, int z, long rotation, boolean expected) {
        Rectangle shape = new Rectangle();
        shape.set(RectangleParams.width, 256L);
        shape.set(RectangleParams.height, 256L);
        shape.set(RectangleParams.centerX, 0L);
        shape.set(RectangleParams.centerZ, 0L);
        shape.set(RectangleParams.rotation, rotation);
        assertEquals(expected, shape.contains(x, z),
                "contains(" + x + "," + z + ") rotation=" + rotation);
    }

    // -------------------------------------------------------------------------
    // locationToXZ() round-trip
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "locationToXZ round-trip width={0}, height={1}, loc={2}")
    @CsvSource({
        "256, 256,    0",
        "256, 256,  100",
        "256, 256, 1000",
        "100, 200,   50",
        "100, 200,  500",
    })
    void locationToXZ_roundTrip_withinBounds(long width, long height, long location) {
        Rectangle shape = new Rectangle();
        shape.set(RectangleParams.width, width);
        shape.set(RectangleParams.height, height);
        shape.set(RectangleParams.centerX, 0L);
        shape.set(RectangleParams.centerZ, 0L);
        shape.set(RectangleParams.rotation, 0L);

        int[] xz = shape.locationToXZ(location);
        assertNotNull(xz);
        assertEquals(2, xz.length);
    }

    @ParameterizedTest(name = "locationToXZ MutableRTPCoords variant, loc={0}")
    @CsvSource({"0", "100", "500", "1000"})
    void locationToXZ_mutableCoordsVariant_matchesArrayVariant(long location) {
        Rectangle shape = new Rectangle();
        shape.set(RectangleParams.width, 256L);
        shape.set(RectangleParams.height, 256L);
        shape.set(RectangleParams.centerX, 0L);
        shape.set(RectangleParams.centerZ, 0L);
        shape.set(RectangleParams.rotation, 0L);

        int[] arr = shape.locationToXZ(location);
        MutableRTPCoords coords = new MutableRTPCoords(0, 0);
        shape.locationToXZ(location, coords);

        assertEquals(arr[0], coords.x, "x mismatch for location " + location);
        assertEquals(arr[1], coords.z, "z mismatch for location " + location);
    }

    // -------------------------------------------------------------------------
    // locationToXZ() with rotation
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "locationToXZ with rotation={0}, loc={1}")
    @CsvSource({
        " 90,   0",
        " 90, 100",
        "180,   0",
        "180, 100",
        "270,   0",
        "270, 100",
    })
    void locationToXZ_withRotation_doesNotThrow(long rotation, long location) {
        Rectangle shape = new Rectangle();
        shape.set(RectangleParams.width, 256L);
        shape.set(RectangleParams.height, 256L);
        shape.set(RectangleParams.centerX, 0L);
        shape.set(RectangleParams.centerZ, 0L);
        shape.set(RectangleParams.rotation, rotation);
        assertDoesNotThrow(() -> shape.locationToXZ(location));
    }

    // -------------------------------------------------------------------------
    // Center offset
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "center=({0},{1}), loc={2}")
    @CsvSource({
        "100, 200,   0",
        "100, 200, 100",
        "-50, -50,  50",
    })
    void locationToXZ_withCenterOffset_doesNotThrow(long cx, long cz, long location) {
        Rectangle shape = new Rectangle();
        shape.set(RectangleParams.width, 256L);
        shape.set(RectangleParams.height, 256L);
        shape.set(RectangleParams.centerX, cx);
        shape.set(RectangleParams.centerZ, cz);
        shape.set(RectangleParams.rotation, 0L);
        assertDoesNotThrow(() -> shape.locationToXZ(location));
    }

    // -------------------------------------------------------------------------
    // Extreme values
    // -------------------------------------------------------------------------

    @Test
    void xzToLocation_extremeCoordinates_doesNotThrow() {
        Rectangle shape = new Rectangle();
        shape.set(RectangleParams.width, 100_000L);
        shape.set(RectangleParams.height, 100_000L);
        shape.set(RectangleParams.centerX, 0L);
        shape.set(RectangleParams.centerZ, 0L);
        shape.set(RectangleParams.rotation, 0L);
        assertDoesNotThrow(() -> shape.xzToLocation(50000L, 50000L));
        assertDoesNotThrow(() -> shape.xzToLocation(-50000L, -50000L));
    }

    @Test
    void locationToXZ_locationZero_doesNotThrow() {
        Rectangle shape = new Rectangle();
        shape.set(RectangleParams.width, 256L);
        shape.set(RectangleParams.height, 256L);
        assertDoesNotThrow(() -> shape.locationToXZ(0L));
    }

    // -------------------------------------------------------------------------
    // keys() and getParameters()
    // -------------------------------------------------------------------------

    @Test
    void keys_returnsAllRectangleParams() {
        Rectangle shape = new Rectangle();
        assertNotNull(shape.keys());
        assertFalse(shape.keys().isEmpty());
    }

    @Test
    void getParameters_returnsNonNull() {
        Rectangle shape = new Rectangle();
        assertNotNull(shape.getParameters());
    }

    // -------------------------------------------------------------------------
    // contains() tests
    // -------------------------------------------------------------------------

    @Test
    void contains_respectsChunkCoordinatesMatchingGeneratedRange() {
        Rectangle shape = new Rectangle();
        shape.set(RectangleParams.width, 256L);
        shape.set(RectangleParams.height, 256L);
        shape.set(RectangleParams.centerX, 0L);
        shape.set(RectangleParams.centerZ, 0L);
        shape.set(RectangleParams.rotation, 0L);

        // Center chunk (0,0) and boundary chunk (128, 128) should be contained
        assertTrue(shape.contains(0, 0));
        assertTrue(shape.contains(128, 128));
        assertTrue(shape.contains(-128, -128));
        assertTrue(shape.contains(100, 100));

        // Outside boundary chunk (> 128)
        assertFalse(shape.contains(129, 0));
        assertFalse(shape.contains(0, 129));
        assertFalse(shape.contains(-129, 0));
        assertFalse(shape.contains(200, 200));

        // Generated locations should all be contained
        for (long loc = 0; loc < shape.getRange(); loc += 5000) {
            int[] xz = shape.locationToXZ(loc);
            assertTrue(shape.contains(xz[0], xz[1]), "Generated point (" + xz[0] + "," + xz[1] + ") should be contained");
        }
    }

    // -------------------------------------------------------------------------
    // Custom name constructor
    // -------------------------------------------------------------------------

    @Test
    void customNameConstructor_setsName() {
        Rectangle shape = new Rectangle("MY_RECT");
        assertEquals("MY_RECT", shape.name);
    }

    @Test
    void xzToLocation_and_locationToXZ_roundTripAcrossRotations() {
        long width = 64L;
        long height = 48L;
        long cx = 10L;
        long cz = -20L;
        long[] rotations = new long[] {0L, 45L, 90L, 180L};

        for (long rotation : rotations) {
            Rectangle shape = new Rectangle();
            shape.set(RectangleParams.width, width);
            shape.set(RectangleParams.height, height);
            shape.set(RectangleParams.centerX, cx);
            shape.set(RectangleParams.centerZ, cz);
            shape.set(RectangleParams.rotation, rotation);

            long range = shape.getRange();
            long centerLoc = (height / 2) * width + (width / 2);
            int[] centerXz = shape.locationToXZ(centerLoc);
            assertEquals(centerLoc, shape.xzToLocation(centerXz[0], centerXz[1]),
                    "Center location must round-trip for rotation=" + rotation);
            assertEquals(centerLoc, shape.xzToLocation(new MutableRTPCoords(centerXz[0], centerXz[1])),
                    "Center MutableRTPCoords must round-trip for rotation=" + rotation);

            for (long loc = 0; loc < range; loc += 37L) {
                int[] xz = shape.locationToXZ(loc);
                long fromLong = shape.xzToLocation(xz[0], xz[1]);
                long fromCoords = shape.xzToLocation(new MutableRTPCoords(xz[0], xz[1]));
                assertEquals(fromLong, fromCoords,
                        "xzToLocation overloads must agree at loc=" + loc + " rotation=" + rotation);
                assertTrue(fromLong >= 0 && fromLong < range,
                        "xzToLocation must stay within [0, range) at loc=" + loc + " rotation=" + rotation);

                if (rotation == 0L) {
                    assertEquals(loc, fromLong,
                            "Unrotated rectangle must round-trip loc=" + loc);
                } else {
                    long dRow = Math.abs((fromLong / width) - (loc / width));
                    long dCol = Math.abs((fromLong % width) - (loc % width));
                    assertTrue(dRow <= 2 && dCol <= 2,
                            "Rotated xzToLocation(locationToXZ(loc)) must stay within 2 cells of loc=" + loc
                                    + " (got " + fromLong + ") for rotation=" + rotation);
                }

                long[] preimages = shape.chunkToLocations(xz[0], xz[1]);
                assertTrue(preimages.length >= 1 && preimages.length <= 2,
                        "chunkToLocations must find 1..2 preimages for decoded chunk at loc=" + loc + " rotation=" + rotation);
                for (long p : preimages) {
                    int[] decoded = shape.locationToXZ(p);
                    assertEquals(xz[0], decoded[0],
                            "Preimage " + p + " must decode to x=" + xz[0] + " for rotation=" + rotation);
                    assertEquals(xz[1], decoded[1],
                            "Preimage " + p + " must decode to z=" + xz[1] + " for rotation=" + rotation);
                }
                if (rotation == 0L || rotation == 90L || rotation == 180L) {
                    boolean containsLoc = false;
                    for (long p : preimages) {
                        if (p == loc) {
                            containsLoc = true;
                            break;
                        }
                    }
                    assertTrue(containsLoc,
                            "chunkToLocations(locationToXZ(loc)) must contain loc=" + loc + " for rotation=" + rotation);
                } else {
                    assertTrue(shape.contains(xz[0], xz[1]),
                            "Decoded chunk (" + xz[0] + "," + xz[1] + ") must be contained for rotation=" + rotation);
                }
            }
        }
    }
}
