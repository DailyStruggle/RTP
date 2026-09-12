package io.github.dailystruggle.rtp.common.selection.region.selectors.shapes;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle_Normal;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square_Normal;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.NormalDistributionParams;
import net.jqwik.api.*;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.BeforeAll;

import java.io.File;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests verifying the mathematical invariants of Archimedean spiral mapping (ADR-001)
 * across Circle, Circle_Normal, Square, and Square_Normal.
 *
 * Traceability: REQ-RTP-F-005, REQ-RTP-F-006, ADR-001.
 * ENTERPRISE_READINESS.md item 23.
 */
public class SpiralMathPropertyTest {

    @BeforeAll
    static void setup() {
        MockRTPServerAccessor accessor = new MockRTPServerAccessor(new File("target/test-data"));
        RTP.serverAccessor = accessor;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
    }

    /**
     * Invariant 1: Range monotonicity and positivity.
     * For any outer radius R > centerRadius CR >= 0:
     * - getRange() > 0
     * - For R2 > R1 with fixed CR, getRange(R2) > getRange(R1)
     */
    @Property(tries = 200)
    void circleRangeIsStrictlyPositiveAndMonotonic(
            @ForAll @LongRange(min = 1, max = 50000) long cr,
            @ForAll @LongRange(min = 1, max = 20000) long delta1,
            @ForAll @LongRange(min = 1, max = 20000) long delta2) {

        long r1 = cr + delta1;
        long r2 = r1 + delta2;

        Circle c1 = new Circle();
        c1.set(GenericMemoryShapeParams.centerRadius, cr);
        c1.set(GenericMemoryShapeParams.radius, r1);

        Circle c2 = new Circle();
        c2.set(GenericMemoryShapeParams.centerRadius, cr);
        c2.set(GenericMemoryShapeParams.radius, r2);

        long range1 = c1.getRange();
        long range2 = c2.getRange();

        assertTrue(range1 > 0, "Range 1 must be strictly positive");
        assertTrue(range2 > range1, "Range must be strictly monotonic with radius");
    }

    @Property(tries = 200)
    void squareRangeIsStrictlyPositiveAndMonotonic(
            @ForAll @LongRange(min = 1, max = 50000) long cr,
            @ForAll @LongRange(min = 1, max = 20000) long delta1,
            @ForAll @LongRange(min = 1, max = 20000) long delta2) {

        long r1 = cr + delta1;
        long r2 = r1 + delta2;

        Square s1 = new Square();
        s1.set(GenericMemoryShapeParams.centerRadius, cr);
        s1.set(GenericMemoryShapeParams.radius, r1);

        Square s2 = new Square();
        s2.set(GenericMemoryShapeParams.centerRadius, cr);
        s2.set(GenericMemoryShapeParams.radius, r2);

        long range1 = s1.getRange();
        long range2 = s2.getRange();

        assertTrue(range1 > 0, "Square Range 1 must be strictly positive");
        assertTrue(range2 > range1, "Square Range must be strictly monotonic with radius");
    }

    /**
     * Invariant 2: Geometric boundary containment.
     * For any valid location index L in [0, range), locationToXZ(L) produces chunk coordinates
     * whose Euclidean distance from center (centerX, centerZ) lies within the outer boundary
     * [cr, r + tolerance].
     */
    @Property(tries = 300)
    void circleLocationToXZ_respectsRadiusBounds(
            @ForAll @LongRange(min = 10, max = 5000) long r,
            @ForAll @LongRange(min = 0, max = 9) long crRatioPercent,
            @ForAll @LongRange(min = -10000, max = 10000) long cx,
            @ForAll @LongRange(min = -10000, max = 10000) long cz) {

        long cr = (r * crRatioPercent) / 10L; // cr in [0, 0.9 * r]
        Circle circle = new Circle();
        circle.set(GenericMemoryShapeParams.radius, r);
        circle.set(GenericMemoryShapeParams.centerRadius, cr);
        circle.set(GenericMemoryShapeParams.centerX, cx);
        circle.set(GenericMemoryShapeParams.centerZ, cz);

        long range = circle.getRange();
        if (range <= 0) return;

        // Sample boundary, start, mid, end locations
        long[] testLocations = new long[]{
                0L,
                range / 4,
                range / 2,
                (range * 3) / 4,
                range - 1
        };

        for (long loc : testLocations) {
            int[] xz = circle.locationToXZ(loc);
            double dx = xz[0] - cx;
            double dz = xz[1] - cz;
            double dist = Math.hypot(dx, dz);

            // Spiral mapping discretizes theta and radius. Upper bound is r + 2.0 chunk padding.
            assertTrue(dist <= r + 2.5,
                    () -> "Distance " + dist + " exceeded radius " + r + " for location " + loc);
            // Lower bound is cr - 2.0 chunk padding (clamped to 0)
            if (cr > 3) {
                assertTrue(dist >= cr - 2.5,
                        () -> "Distance " + dist + " fell below centerRadius " + cr + " for location " + loc);
            }
        }
    }

    @Property(tries = 300)
    void squareLocationToXZ_respectsChebyshevBounds(
            @ForAll @LongRange(min = 10, max = 5000) long r,
            @ForAll @LongRange(min = 0, max = 9) long crRatioPercent,
            @ForAll @LongRange(min = -10000, max = 10000) long cx,
            @ForAll @LongRange(min = -10000, max = 10000) long cz) {

        long cr = (r * crRatioPercent) / 10L;
        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, r);
        square.set(GenericMemoryShapeParams.centerRadius, cr);
        square.set(GenericMemoryShapeParams.centerX, cx);
        square.set(GenericMemoryShapeParams.centerZ, cz);

        long range = square.getRange();
        if (range <= 0) return;

        long[] testLocations = new long[]{
                0L,
                range / 4,
                range / 2,
                (range * 3) / 4,
                range - 1
        };

        for (long loc : testLocations) {
            int[] xz = square.locationToXZ(loc);
            long dx = Math.abs((long) xz[0] - cx);
            long dz = Math.abs((long) xz[1] - cz);
            long chebyshevDist = Math.max(dx, dz);

            assertTrue(chebyshevDist <= r + 2,
                    () -> "Chebyshev distance " + chebyshevDist + " exceeded radius " + r + " for loc " + loc);
            if (cr > 3) {
                assertTrue(chebyshevDist >= cr - 2,
                        () -> "Chebyshev distance " + chebyshevDist + " fell below centerRadius " + cr + " for loc " + loc);
            }
        }
    }

    /**
     * Invariant 3: chunkToLocations preimage soundness and ascending sort order.
     * For any shape (Circle, Square) and arbitrary (cx, cz) sampled via rand()/locationToXZ:
     * - chunkToLocations returns an array of length 0, 1, or 2 (<= 2 preimage cap).
     * - If length == 2, preimage[0] < preimage[1] (strictly sorted).
     * - Every returned preimage L decodes back via locationToXZ(L) to (cx, cz).
     */
    @Property(tries = 200)
    void circleChunkToLocations_soundnessAndOrdering(
            @ForAll @LongRange(min = 50, max = 2000) long r,
            @ForAll @LongRange(min = 0, max = 30) long cr,
            @ForAll @LongRange(min = 0, max = 1000) long sampleOffset) {

        Circle circle = new Circle();
        circle.set(GenericMemoryShapeParams.radius, r);
        circle.set(GenericMemoryShapeParams.centerRadius, cr);
        circle.set(GenericMemoryShapeParams.centerX, 0L);
        circle.set(GenericMemoryShapeParams.centerZ, 0L);

        long range = circle.getRange();
        if (range <= 0) return;

        long loc = sampleOffset % range;
        int[] xz = circle.locationToXZ(loc);

        long[] preimages = circle.chunkToLocations(xz[0], xz[1]);
        assertNotNull(preimages);
        assertTrue(preimages.length <= 2,
                () -> "Preimage count must be <= 2, got " + Arrays.toString(preimages));

        if (preimages.length == 2) {
            assertTrue(preimages[0] < preimages[1],
                    () -> "Preimages must be sorted in strictly ascending order: " + Arrays.toString(preimages));
        }

        for (long p : preimages) {
            int[] pxz = circle.locationToXZ(p);
            assertEquals(xz[0], pxz[0], "Preimage round-trip X coordinate mismatch");
            assertEquals(xz[1], pxz[1], "Preimage round-trip Z coordinate mismatch");
        }
    }

    @Property(tries = 200)
    void squareChunkToLocations_soundnessAndOrdering(
            @ForAll @LongRange(min = 50, max = 2000) long r,
            @ForAll @LongRange(min = 0, max = 30) long cr,
            @ForAll @LongRange(min = 0, max = 1000) long sampleOffset) {

        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, r);
        square.set(GenericMemoryShapeParams.centerRadius, cr);
        square.set(GenericMemoryShapeParams.centerX, 0L);
        square.set(GenericMemoryShapeParams.centerZ, 0L);

        long range = square.getRange();
        if (range <= 0) return;

        long loc = sampleOffset % range;
        int[] xz = square.locationToXZ(loc);

        long[] preimages = square.chunkToLocations(xz[0], xz[1]);
        assertNotNull(preimages);
        assertTrue(preimages.length <= 2,
                () -> "Preimage count must be <= 2, got " + Arrays.toString(preimages));

        if (preimages.length == 2) {
            assertTrue(preimages[0] < preimages[1],
                    () -> "Preimages must be sorted in strictly ascending order: " + Arrays.toString(preimages));
        }

        for (long p : preimages) {
            int[] pxz = square.locationToXZ(p);
            assertEquals(xz[0], pxz[0], "Preimage round-trip X coordinate mismatch");
            assertEquals(xz[1], pxz[1], "Preimage round-trip Z coordinate mismatch");
        }
    }

    /**
     * Invariant 4: Normal distribution shapes (Circle_Normal, Square_Normal)
     * maintain range and bounded coordinate generation.
     */
    @Property(tries = 150)
    void normalDistributionShapes_boundedRangeAndCoordinates(
            @ForAll @LongRange(min = 100, max = 2000) long r,
            @ForAll @LongRange(min = 0, max = 50) long cr) {

        Circle_Normal cn = new Circle_Normal();
        cn.set(NormalDistributionParams.radius, r);
        cn.set(NormalDistributionParams.centerRadius, cr);
        long cRange = cn.getRange();
        assertTrue(cRange > 0);

        Square_Normal sn = new Square_Normal();
        sn.set(NormalDistributionParams.radius, r);
        sn.set(NormalDistributionParams.centerRadius, cr);
        long sRange = sn.getRange();
        assertTrue(sRange > 0);

        // locationToXZ on normal shapes delegate to the base spiral formulas
        int[] cXz = cn.locationToXZ(cRange / 2);
        assertTrue(Math.hypot(cXz[0], cXz[1]) <= r + 2.5);

        int[] sXz = sn.locationToXZ(sRange / 2);
        assertTrue(Math.max(Math.abs(sXz[0]), Math.abs(sXz[1])) <= r + 2);
    }
}
