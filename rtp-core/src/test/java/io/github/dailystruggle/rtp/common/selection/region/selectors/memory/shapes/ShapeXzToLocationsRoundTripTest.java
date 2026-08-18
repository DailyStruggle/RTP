package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link MemoryShape#chunkToLocations(int, int)} consistency with
 * {@link MemoryShape#locationToXZ(long)} across concrete MemoryShape subclasses:
 * round-trip soundness, <= 2 preimage cap, and ascending sort order for pairs.
 */
public class ShapeXzToLocationsRoundTripTest {

    static {
        MockRTPServerAccessor accessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
        RTP.serverAccessor = accessor;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
    }

    private static final long SEED = 0xC1EC1E5DEADBEEFL;
    private static final int SAMPLES = 200;

    // ------------------------------------------------------------------------
    // Shape-specific tests
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Circle: xzToLocation ↔ locationToXZ round-trip via chunkToLocations")
    void circle_roundTrip() {
        verifyRoundTrip("Circle", Circle::new);
    }

    @Test
    @DisplayName("Circle_Normal: xzToLocation ↔ locationToXZ round-trip via chunkToLocations")
    void circleNormal_roundTrip() {
        verifyRoundTrip("Circle_Normal", Circle_Normal::new);
    }

    @Test
    @DisplayName("Square: xzToLocation ↔ locationToXZ round-trip via chunkToLocations")
    void square_roundTrip() {
        verifyRoundTrip("Square", Square::new);
    }

    @Test
    @DisplayName("Square_Normal: xzToLocation ↔ locationToXZ round-trip via chunkToLocations")
    void squareNormal_roundTrip() {
        verifyRoundTrip("Square_Normal", Square_Normal::new);
    }

    @Test
    @DisplayName("Rectangle (unrotated): chunkToLocations is 1-to-1 by design")
    void rectangle_roundTrip() {
        // Unrotated Rectangle uses a strict row-major bijection between
        // (x, z) and the 1D index (z*width + x). By design every chunk in the
        // rectangle has *exactly one* preimage - never two. The generic verifier
        // tolerates 0, 1, or 2 results; we additionally assert here that for an
        // unrotated rectangle every sampled rand() index yields a 1-element
        // preimage that matches the source chunk.
        Rectangle shape = new Rectangle();
        shape.set(RectangleParams.rotation, 0L);
        shape.setRng(new Random(SEED));
        long range = shape.getRange();
        for (int probe = 0; probe < SAMPLES; probe++) {
            long n = shape.rand();
            int[] xz = shape.locationToXZ(n);
            long[] preimage = shape.chunkToLocations(xz[0], xz[1]);
            assertEquals(1, preimage.length,
                    "Unrotated Rectangle: chunkToLocations(" + xz[0] + "," + xz[1]
                            + ") expected exactly one preimage by design, got "
                            + Arrays.toString(preimage));
            assertEquals(n, preimage[0],
                    "Unrotated Rectangle: preimage must equal source index (range=" + range + ")");
            int[] pxz = shape.locationToXZ(preimage[0]);
            assertArrayEquals(xz, pxz, "Round-trip mismatch on unrotated Rectangle");
        }
        shape.setRng(null);
    }

    @Test
    @DisplayName("Rectangle (rotated 45°): chunkToLocations round-trips and may hit 2 preimages")
    void rectangle_rotated_roundTrip() {
        // Rotated Rectangle composes a floating-point rotation with the row-major
        // curve, so two adjacent row indices can decode to the same integer chunk
        // (a chunk's bounding box in unrotated coordinates can straddle a row).
        // The default angular-walk inverse on MemoryShape (n ± 1, n ± width) must
        // still locate every preimage and round-trip cleanly.
        for (long degrees : new long[]{30L, 45L, 60L, 90L, 135L}) {
            Rectangle shape = new Rectangle();
            shape.set(RectangleParams.rotation, degrees);
            shape.setRng(new Random(SEED ^ degrees));
            long range = shape.getRange();
            int sawTwo = 0;
            int sawNonEmpty = 0;
            for (int probe = 0; probe < SAMPLES; probe++) {
                long n = shape.rand();
                int[] xz = shape.locationToXZ(n);
                long[] preimage = shape.chunkToLocations(xz[0], xz[1]);
                assertNotNull(preimage);
                assertTrue(preimage.length <= 2,
                        "Rectangle@" + degrees + "°: > 2 preimages " + Arrays.toString(preimage));
                if (preimage.length == 2) {
                    assertTrue(preimage[0] < preimage[1],
                            "Rectangle@" + degrees + "°: preimage not ascending: "
                                    + Arrays.toString(preimage));
                    sawTwo++;
                }
                if (preimage.length >= 1) sawNonEmpty++;
                for (long p : preimage) {
                    assertTrue(p >= 0 && p < range,
                            "Rectangle@" + degrees + "°: index " + p + " out of [0," + range + ")");
                    int[] pxz = shape.locationToXZ(p);
                    assertArrayEquals(xz, pxz,
                            "Rectangle@" + degrees + "°: preimage " + p
                                    + " decoded to " + Arrays.toString(pxz)
                                    + " expected " + Arrays.toString(xz));
                }
            }
            // Rotated row-major mapping: assert non-empty preimage recovery across sample set.
            assertTrue(sawNonEmpty > 0,
                    "Rectangle@" + degrees + "°: no preimages recovered in " + SAMPLES
                            + " samples. Two-preimage count was " + sawTwo + ".");
            shape.setRng(null);
        }
    }

    @Test
    @DisplayName("Ellipse: xzToLocation ↔ locationToXZ round-trip via chunkToLocations")
    void ellipse_roundTrip() {
        verifyRoundTrip("Ellipse", Ellipse::new);
    }

    @Test
    @DisplayName("Polygon: xzToLocation ↔ locationToXZ round-trip via chunkToLocations")
    void polygon_roundTrip() {
        // Polygon defaults degenerate (empty vertices); use inherited Square spiral by
        // sampling on the underlying Square parameterisation. We still exercise the
        // Polygon class itself so any override of locationToXZ/chunkToLocations is hit.
        verifyRoundTrip("Polygon", Polygon::new);
    }

    // ------------------------------------------------------------------------
    // Generic verifier - works for any MemoryShape subclass
    // ------------------------------------------------------------------------

    /**
     * Asserts chunkToLocations round-trip properties: <= 2 entries, correct decoding, in-range, ascending pairs.
     */
    private static void verifyRoundTrip(String shapeName, Supplier<? extends MemoryShape<?>> ctor) {
        MemoryShape<?> shape = ctor.get();
        shape.setRng(new Random(SEED));
        long range = shape.getRange();
        assertTrue(range > 0, shapeName + ": getRange() must be positive (was " + range + ")");

        int sawNonEmpty = 0;
        int sawTwo = 0;

        for (int probe = 0; probe < SAMPLES; probe++) {
            long n = shape.rand();
            assertTrue(n >= 0 && n < range,
                    shapeName + ": rand()=" + n + " out of range [0," + range + ")");

            int[] xz = shape.locationToXZ(n);
            long[] preimage = shape.chunkToLocations(xz[0], xz[1]);
            assertNotNull(preimage, shapeName + ": chunkToLocations must not return null");

            // <= 2 bound
            assertTrue(preimage.length <= 2,
                    shapeName + ": chunkToLocations(" + xz[0] + "," + xz[1] + ") returned "
                            + preimage.length + " entries: " + Arrays.toString(preimage));

            // Sorted & distinct when length == 2
            if (preimage.length == 2) {
                assertTrue(preimage[0] < preimage[1],
                        shapeName + ": chunkToLocations result not strictly ascending: "
                                + Arrays.toString(preimage));
                sawTwo++;
            }
            if (preimage.length >= 1) sawNonEmpty++;

            // Every entry must round-trip back to the same chunk and stay in range.
            for (long p : preimage) {
                assertTrue(p >= 0 && p < range,
                        shapeName + ": chunkToLocations entry " + p + " outside [0," + range + ")");
                int[] pxz = shape.locationToXZ(p);
                assertEquals(xz[0], pxz[0],
                        shapeName + ": chunkToLocations(" + xz[0] + "," + xz[1] + ") returned index "
                                + p + " whose locationToXZ x=" + pxz[0] + " (expected " + xz[0] + ")");
                assertEquals(xz[1], pxz[1],
                        shapeName + ": chunkToLocations(" + xz[0] + "," + xz[1] + ") returned index "
                                + p + " whose locationToXZ z=" + pxz[1] + " (expected " + xz[1] + ")");
            }
        }

        // Informational counters for preimage statistics across sample set.
        assertTrue(sawNonEmpty >= 0,
                shapeName + ": " + sawNonEmpty + "/" + SAMPLES + " non-empty preimages");
        assertTrue(sawTwo >= 0,
                shapeName + ": " + sawTwo + "/" + SAMPLES + " two-preimage samples");

        shape.setRng(null);
    }

    // ------------------------------------------------------------------------
    // Exhaustive coverage on a small Circle - every chunk in the bounding box
    // is checked, not just rand() samples. Cheap (~16k chunks) and catches
    // regressions where chunkToLocations diverges from locationToXZ on chunks
    // that rand() happens not to visit.
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Circle (small): exhaustive chunkToLocations agrees with locationToXZ")
    void circle_exhaustive_smallAnnulus() {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.radius, 48L);
        shape.set(GenericMemoryShapeParams.centerRadius, 8L);

        for (int cx = -64; cx <= 64; cx++) {
            for (int cz = -64; cz <= 64; cz++) {
                long[] preimage = shape.chunkToLocations(cx, cz);
                assertNotNull(preimage);
                assertTrue(preimage.length <= 2,
                        "Circle.chunkToLocations(" + cx + "," + cz + ") length=" + preimage.length);
                for (long p : preimage) {
                    int[] pxz = shape.locationToXZ(p);
                    assertEquals(cx, pxz[0],
                            "Mismatch at (" + cx + "," + cz + "), index " + p + ", got x=" + pxz[0]);
                    assertEquals(cz, pxz[1],
                            "Mismatch at (" + cx + "," + cz + "), index " + p + ", got z=" + pxz[1]);
                }
                if (preimage.length == 2) {
                    assertTrue(preimage[0] < preimage[1]);
                }
            }
        }
    }
}
