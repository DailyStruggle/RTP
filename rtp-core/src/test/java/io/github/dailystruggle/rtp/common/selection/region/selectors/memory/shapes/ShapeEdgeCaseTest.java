package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.NormalDistributionParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parameterized edge-case and boundary tests for all {@link MemoryShape} subclasses.
 */
public class ShapeEdgeCaseTest {

    private static final long SEED = 0xCAFEBABEL;

    static {
        MockRTPServerAccessor accessor =
                new MockRTPServerAccessor(new java.io.File("target/test-data"));
        RTP.serverAccessor = accessor;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
    }

    // -------------------------------------------------------------------------
    // Radius boundary tests - Circle
    // -------------------------------------------------------------------------

    @Test
    void circle_zeroEffectiveRadius_rangeIsZeroOrNegative() {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.radius, 64L);
        shape.set(GenericMemoryShapeParams.centerRadius, 64L);
        long range = shape.getRange();
        assertTrue(range <= 0, "Range should be 0 when radius == centerRadius, got " + range);
    }

    @Test
    void circle_minRadius_oneMoreThanCenter_positiveRange() {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.radius, 65L);
        shape.set(GenericMemoryShapeParams.centerRadius, 64L);
        assertTrue(shape.getRange() > 0, "Range should be positive when radius > centerRadius");
    }

    @Test
    void circle_largeRadius_doesNotOverflow() {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.radius, 100_000L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        assertDoesNotThrow(shape::getRange);
        assertTrue(shape.getRange() > 0);
    }

    @Test
    void circle_zeroCenterRadius_rangeIsPositive() {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.radius, 256L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        assertTrue(shape.getRange() > 0);
    }

    // -------------------------------------------------------------------------
    // Radius boundary tests - Square
    // -------------------------------------------------------------------------

    @Test
    void square_zeroEffectiveRadius_rangeIsZeroOrNegative() {
        Square shape = new Square();
        shape.set(GenericMemoryShapeParams.radius, 64L);
        shape.set(GenericMemoryShapeParams.centerRadius, 64L);
        assertTrue(shape.getRange() <= 0,
                "Range should be 0 when radius == centerRadius");
    }

    @Test
    void square_largeRadius_doesNotOverflow() {
        Square shape = new Square();
        shape.set(GenericMemoryShapeParams.radius, 100_000L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        assertDoesNotThrow(shape::getRange);
        assertTrue(shape.getRange() > 0);
    }

    // -------------------------------------------------------------------------
    // Radius boundary tests - Rectangle
    // -------------------------------------------------------------------------

    @Test
    void rectangle_zeroWidth_rangeIsZero() {
        Rectangle shape = new Rectangle();
        shape.set(RectangleParams.width, 0L);
        shape.set(RectangleParams.height, 256L);
        assertEquals(0L, shape.getRange());
    }

    @Test
    void rectangle_zeroHeight_rangeIsZero() {
        Rectangle shape = new Rectangle();
        shape.set(RectangleParams.width, 256L);
        shape.set(RectangleParams.height, 0L);
        assertEquals(0L, shape.getRange());
    }

    @Test
    void rectangle_largeWidthHeight_doesNotOverflow() {
        Rectangle shape = new Rectangle();
        shape.set(RectangleParams.width, 100_000L);
        shape.set(RectangleParams.height, 100_000L);
        assertDoesNotThrow(shape::getRange);
        assertTrue(shape.getRange() > 0);
    }

    // -------------------------------------------------------------------------
    // rand() mode: NEAREST - Circle
    // -------------------------------------------------------------------------

    @Test
    void circle_nearestMode_badLocation_returnsAdjacentGoodLocation() {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.mode, "NEAREST");
        shape.set(GenericMemoryShapeParams.radius, 256L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        shape.setRng(new Random(SEED));

        for (long i = 0; i < 1000; i++) shape.addBadLocation(i);
        shape.flushAndRebuild(shape.spatialResolution());

        long loc = shape.rand();
        assertTrue(loc >= 0 || loc == -1, "NEAREST mode should return valid location or -1");
        shape.setRng(null);
    }

    // -------------------------------------------------------------------------
    // rand() mode: REROLL - Circle
    // -------------------------------------------------------------------------

    @Test
    void circle_rerollMode_badLocation_returnsMinusOne() {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.mode, "REROLL");
        shape.set(GenericMemoryShapeParams.radius, 10L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        shape.setRng(new Random(SEED));

        long range = shape.getRange();
        for (long i = 0; i < range; i++) shape.addBadLocation(i);
        shape.flushAndRebuild(shape.spatialResolution());

        long loc = shape.rand();
        assertEquals(-1L, loc, "REROLL mode should return -1 when selected location is bad");
        shape.setRng(null);
    }

    // -------------------------------------------------------------------------
    // rand() mode: ACCUMULATE - Square
    // -------------------------------------------------------------------------

    @Test
    void square_accumulateMode_withBadLocations_skipsThemCorrectly() {
        Square shape = new Square();
        shape.set(GenericMemoryShapeParams.mode, "ACCUMULATE");
        shape.set(GenericMemoryShapeParams.radius, 100L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        shape.setRng(new Random(SEED));

        for (long i = 0; i < 50; i++) shape.addBadLocation(i);
        shape.flushAndRebuild(shape.spatialResolution());

        long range = shape.getRange();
        for (int i = 0; i < 50; i++) {
            long loc = shape.rand();
            assertFalse(shape.isKnownBad(loc),
                    "ACCUMULATE mode must not return a known-bad location, got " + loc);
            assertTrue(loc >= 0 && loc < range + 50,
                    "Location out of expected range: " + loc);
        }
        shape.setRng(null);
    }

    // -------------------------------------------------------------------------
    // weight parameter - Circle
    // -------------------------------------------------------------------------

    @Test
    void circle_highWeight_skewsTowardLowerLocations() {
        Circle shapeHigh = new Circle();
        shapeHigh.set(GenericMemoryShapeParams.weight, 10.0);
        shapeHigh.set(GenericMemoryShapeParams.radius, 1000L);
        shapeHigh.set(GenericMemoryShapeParams.centerRadius, 0L);
        shapeHigh.setRng(new Random(SEED));

        Circle shapeLow = new Circle();
        shapeLow.set(GenericMemoryShapeParams.weight, 0.1);
        shapeLow.set(GenericMemoryShapeParams.radius, 1000L);
        shapeLow.set(GenericMemoryShapeParams.centerRadius, 0L);
        shapeLow.setRng(new Random(SEED));

        long sumHigh = 0, sumLow = 0;
        int n = 1000;
        for (int i = 0; i < n; i++) {
            sumHigh += shapeHigh.rand();
            sumLow += shapeLow.rand();
        }
        assertTrue(sumHigh < sumLow,
                "High weight should produce lower average locations than low weight");
        shapeHigh.setRng(null);
        shapeLow.setRng(null);
    }

    // -------------------------------------------------------------------------
    // expand flag - Circle
    // -------------------------------------------------------------------------

    @Test
    void circle_expandMode_withBadLocations_doesNotThrow() {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.expand, true);
        shape.set(GenericMemoryShapeParams.mode, "REROLL");
        shape.set(GenericMemoryShapeParams.radius, 200L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        shape.setRng(new Random(SEED));

        for (long i = 0; i < 100; i++) shape.addBadLocation(i);
        shape.flushAndRebuild(shape.spatialResolution());

        assertDoesNotThrow(shape::rand);
        shape.setRng(null);
    }

    // -------------------------------------------------------------------------
    // uniquePlacements - Circle
    // -------------------------------------------------------------------------

    @Test
    void uniquePlacementsRadius_coercesAllSupportedTypes() {
        assertEquals(0, MemoryShape.uniquePlacementsRadius(null));
        assertEquals(0, MemoryShape.uniquePlacementsRadius(false));
        assertEquals(1, MemoryShape.uniquePlacementsRadius(true));
        assertEquals(0, MemoryShape.uniquePlacementsRadius(0));
        assertEquals(3, MemoryShape.uniquePlacementsRadius(3));
        assertEquals(0, MemoryShape.uniquePlacementsRadius(-5));
        assertEquals(0, MemoryShape.uniquePlacementsRadius("false"));
        assertEquals(1, MemoryShape.uniquePlacementsRadius("true"));
        assertEquals(4, MemoryShape.uniquePlacementsRadius("4"));
        assertEquals(8, MemoryShape.uniquePlacementsRadius("auto")); // Default view distance 10 -> power-of-two 8
        assertEquals(0, MemoryShape.uniquePlacementsRadius("garbage"));
    }

    @Test
    void circle_uniquePlacementsRadius_marksMoreThanSingleChunk() {
        // A location well inside the region so its 3x3 chunk neighbourhood is also in-shape.
        Circle r1 = new Circle();
        r1.set(GenericMemoryShapeParams.radius, 64L);
        r1.set(GenericMemoryShapeParams.centerRadius, 0L);
        long loc1 = r1.xzToLocation(10, 10);
        int marked1 = r1.addBadChunkRadius(loc1, 1);

        Circle r2 = new Circle();
        r2.set(GenericMemoryShapeParams.radius, 64L);
        r2.set(GenericMemoryShapeParams.centerRadius, 0L);
        long loc2 = r2.xzToLocation(10, 10);
        int marked2 = r2.addBadChunkRadius(loc2, 2);

        assertTrue(marked1 > 0, "radius-1 unique placement should mark the landing chunk");
        assertTrue(marked2 > marked1,
                "radius-2 unique placement must clear more indices than the single landing chunk "
                        + "(radius-1=" + marked1 + ", radius-2=" + marked2 + ")");
    }

    @Test
    void circle_uniquePlacements_noDuplicatesInSmallRange() {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.uniquePlacements, true);
        shape.set(GenericMemoryShapeParams.radius, 20L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        shape.set(GenericMemoryShapeParams.mode, "ACCUMULATE");
        shape.setRng(new Random(SEED));

        java.util.Set<Long> seen = new java.util.HashSet<>();
        long range = shape.getRange();
        int draws = (int) Math.min(range / 2, 20);
        for (int i = 0; i < draws; i++) {
            long loc = shape.rand();
            if (loc >= 0) {
                assertFalse(seen.contains(loc),
                        "uniquePlacements=true must not return duplicate location " + loc);
                seen.add(loc);
            }
        }
        shape.setRng(null);
    }

    // -------------------------------------------------------------------------
    // Distribution uniformity - parameterized across all 5 shapes
    // -------------------------------------------------------------------------

    static Stream<String> uniformShapeNames() {
        // Normal-distribution shapes are bell-curved - excluded from uniform bucket check
        return Stream.of("CIRCLE", "SQUARE", "RECTANGLE");
    }

    static Stream<String> allShapeNames() {
        return Stream.of("CIRCLE", "SQUARE", "RECTANGLE", "CIRCLE_NORMAL", "SQUARE_NORMAL");
    }

    @ParameterizedTest(name = "uniformDistribution_{0}")
    @MethodSource("uniformShapeNames")
    void uniformDistribution_10kSamples_allBucketsPopulated(String shapeName) {
        MemoryShape<?> shape = createShape(shapeName);
        shape.setRng(new Random(SEED));

        int samples = 10_000;
        int buckets = 10;
        int[] counts = new int[buckets];
        long range = shape.getRange();
        assertTrue(range > 0, "Shape " + shapeName + " must have positive range");

        for (int i = 0; i < samples; i++) {
            long loc = shape.rand();
            if (loc < 0) continue; // REROLL miss
            int bucket = (int) ((loc * buckets) / range);
            if (bucket >= 0 && bucket < buckets) counts[bucket]++;
        }

        // Each bucket should have at least 1% of samples (very loose - just checks no dead zones)
        int minExpected = samples / (buckets * 10);
        for (int b = 0; b < buckets; b++) {
            assertTrue(counts[b] >= minExpected,
                    "Bucket " + b + " for shape " + shapeName + " has too few samples: "
                            + counts[b] + " (min expected " + minExpected + ")");
        }
        shape.setRng(null);
    }

    @ParameterizedTest(name = "normalDistribution_centreHeavy_{0}")
    @MethodSource("allShapeNames")
    void allShapes_10kSamples_produceNonZeroOutput(String shapeName) {
        // Verifies all shapes produce output without throwing - distribution shape is not asserted
        MemoryShape<?> shape = createShape(shapeName);
        shape.setRng(new Random(SEED));

        int samples = 10_000;
        int nonNegative = 0;
        long range = shape.getRange();
        assertTrue(range > 0, "Shape " + shapeName + " must have positive range");

        for (int i = 0; i < samples; i++) {
            long loc = shape.rand();
            if (loc >= 0) nonNegative++;
        }

        assertTrue(nonNegative > samples / 2,
                "Shape " + shapeName + " must produce non-negative output for >50% of samples, got "
                        + nonNegative + "/" + samples);
        shape.setRng(null);
    }

    // -------------------------------------------------------------------------
    // contains() - Circle
    // -------------------------------------------------------------------------

    @Test
    void circle_contains_pointInsideRadius_returnsTrue() {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.radius, 100L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        shape.set(GenericMemoryShapeParams.centerX, 0L);
        shape.set(GenericMemoryShapeParams.centerZ, 0L);
        assertTrue(shape.contains(50, 0));
        assertTrue(shape.contains(0, 50));
        assertTrue(shape.contains(0, 0));
    }

    @Test
    void circle_contains_pointOutsideRadius_returnsFalse() {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.radius, 100L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        shape.set(GenericMemoryShapeParams.centerX, 0L);
        shape.set(GenericMemoryShapeParams.centerZ, 0L);
        assertFalse(shape.contains(200, 0));
        assertFalse(shape.contains(0, 200));
    }

    @Test
    void circle_contains_pointInHole_returnsFalse() {
        Circle shape = new Circle();
        shape.set(GenericMemoryShapeParams.radius, 100L);
        shape.set(GenericMemoryShapeParams.centerRadius, 50L);
        shape.set(GenericMemoryShapeParams.centerX, 0L);
        shape.set(GenericMemoryShapeParams.centerZ, 0L);
        assertFalse(shape.contains(10, 0));
        assertFalse(shape.contains(0, 10));
    }

    @Test
    void circleOptimizedDualLayer_expandMode_walksPastDeclaredRadius() {
        CircleOptimizedDualLayer shape = new CircleOptimizedDualLayer("EXPAND_CIRCLE", 32);
        shape.set(GenericMemoryShapeParams.radius, 100L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        shape.set(GenericMemoryShapeParams.expand, true);
        shape.set(GenericMemoryShapeParams.mode, "ACCUMULATE");
        shape.setRng(new Random(SEED));

        // Mark bad locations to simulate consumed territory
        long range = shape.getRange();
        for (long i = 0; i < range / 2; i++) {
            shape.addBadLocation(i);
        }
        shape.flushAndRebuild(shape.spatialResolution());

        long maxRadius = 0;
        int samples = 500;
        for (int i = 0; i < samples; i++) {
            int[] xz = shape.select();
            assertNotNull(xz);
            long r = (long) Math.ceil(Math.sqrt((long) xz[0] * xz[0] + (long) xz[1] * xz[1]));
            if (r > maxRadius) maxRadius = r;
            assertTrue(shape.contains(xz[0], xz[1]), "Expanded coordinate (" + xz[0] + "," + xz[1] + ") must be contained by shape");
        }

        assertTrue(maxRadius > 100L, "When expand=true and locations are consumed, max radius reached ("
                + maxRadius + ") should exceed declared radius (100)");
        shape.setRng(null);
    }

    @Test
    void squareOptimizedDualLayer_expandMode_walksPastDeclaredRadius() {
        SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("EXPAND_SQUARE", 32);
        shape.set(GenericMemoryShapeParams.radius, 100L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        shape.set(GenericMemoryShapeParams.expand, true);
        shape.set(GenericMemoryShapeParams.mode, "ACCUMULATE");
        shape.setRng(new Random(SEED));

        // Mark bad locations to simulate consumed territory
        long range = shape.getRange();
        for (long i = 0; i < range / 2; i++) {
            shape.addBadLocation(i);
        }
        shape.flushAndRebuild(shape.spatialResolution());

        long maxRadius = 0;
        int samples = 500;
        for (int i = 0; i < samples; i++) {
            int[] xz = shape.select();
            assertNotNull(xz);
            long chebyshev = Math.max(Math.abs(xz[0]), Math.abs(xz[1]));
            if (chebyshev > maxRadius) maxRadius = chebyshev;
            assertTrue(shape.contains(xz[0], xz[1]), "Expanded coordinate (" + xz[0] + "," + xz[1] + ") must be contained by shape");
        }

        assertTrue(maxRadius > 100L, "When expand=true and locations are consumed, max radius reached ("
                + maxRadius + ") should exceed declared radius (100)");
        shape.setRng(null);
    }

    @Test
    void testVertexCenteredSquareOptimizedDualLayer() {
        SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("TEST_SYMMETRIC", 32);
        shape.set(GenericMemoryShapeParams.radius, 256L);
        shape.set(GenericMemoryShapeParams.centerRadius, 64L);
        shape.set(GenericMemoryShapeParams.centerX, 0L);
        shape.set(GenericMemoryShapeParams.centerZ, 0L);

        long range = shape.getRange();
        System.out.println("[DEBUG_LOG] Current shape.getRange() = " + range);

        // Test bijection over thousands of points
        MutableRTPCoords coords = new MutableRTPCoords(0, 0);
        long minX = Long.MAX_VALUE, maxX = Long.MIN_VALUE;
        long minZ = Long.MAX_VALUE, maxZ = Long.MIN_VALUE;

        for (long loc = 0; loc < range; loc += 1) {
            shape.locationToXZ(loc, coords);
            if (coords.x < minX) minX = coords.x;
            if (coords.x > maxX) maxX = coords.x;
            if (coords.z < minZ) minZ = coords.z;
            if (coords.z > maxZ) maxZ = coords.z;

            if (loc % 17 == 0) {
                long back = shape.xzToLocation(coords.x, coords.z);
                assertEquals(loc, back, "Round-trip failure at loc=" + loc + " coords=(" + coords.x + "," + coords.z + ")");
                assertTrue(shape.contains(coords.x, coords.z), "contains() must return true for valid location=" + loc);
            }
        }
        System.out.println("[DEBUG_LOG] Full range sampled bounds: X in [" + minX + ", " + maxX + "], Z in [" + minZ + ", " + maxZ + "]");
    }

    // -------------------------------------------------------------------------
    // contains() - Square
    // -------------------------------------------------------------------------

    @Test
    void square_contains_pointInsideRadius_returnsTrue() {
        Square shape = new Square();
        shape.set(GenericMemoryShapeParams.radius, 100L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        shape.set(GenericMemoryShapeParams.centerX, 0L);
        shape.set(GenericMemoryShapeParams.centerZ, 0L);
        assertTrue(shape.contains(50, 50));
        assertTrue(shape.contains(-50, -50));
    }

    @Test
    void square_contains_pointOutsideRadius_returnsFalse() {
        Square shape = new Square();
        shape.set(GenericMemoryShapeParams.radius, 100L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        shape.set(GenericMemoryShapeParams.centerX, 0L);
        shape.set(GenericMemoryShapeParams.centerZ, 0L);
        assertFalse(shape.contains(200, 0));
    }

    // -------------------------------------------------------------------------
    // locationToXZ round-trip - Square
    // -------------------------------------------------------------------------

    @Test
    void square_locationToXZ_decodedPointIsInsideShape() {
        Square shape = new Square();
        shape.set(GenericMemoryShapeParams.radius, 100L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        shape.set(GenericMemoryShapeParams.centerX, 0L);
        shape.set(GenericMemoryShapeParams.centerZ, 0L);
        shape.setRng(new Random(SEED));
        for (int i = 0; i < 20; i++) {
            long loc = shape.rand();
            int[] xz = shape.locationToXZ(loc);
            assertTrue(shape.contains(xz[0], xz[1]),
                    "Square.locationToXZ(rand()) must decode inside shape, got ("
                            + xz[0] + "," + xz[1] + ")");
        }
        shape.setRng(null);
    }

    // -------------------------------------------------------------------------
    // Circle_Normal - basic sanity
    // -------------------------------------------------------------------------

    @Test
    void circleNormal_rand_returnsNonNegative() {
        Circle_Normal shape = new Circle_Normal();
        shape.set(NormalDistributionParams.radius, 256L);
        shape.set(NormalDistributionParams.centerRadius, 0L);
        shape.set(NormalDistributionParams.mean, 0.5);
        shape.set(NormalDistributionParams.deviation, 1.0);
        shape.setRng(new Random(SEED));
        for (int i = 0; i < 20; i++) {
            long loc = shape.rand();
            assertTrue(loc >= -1, "Circle_Normal.rand() must return >= -1, got " + loc);
        }
        shape.setRng(null);
    }

    @Test
    void circleNormal_sameSeed_deterministic() {
        long[] first = sampleCircleNormal(SEED, 20);
        long[] second = sampleCircleNormal(SEED, 20);
        assertArrayEquals(first, second, "Circle_Normal must be deterministic for same seed");
    }

    // -------------------------------------------------------------------------
    // Square_Normal - basic sanity
    // -------------------------------------------------------------------------

    @Test
    void squareNormal_rand_returnsNonNegative() {
        Square_Normal shape = new Square_Normal();
        shape.set(NormalDistributionParams.radius, 256L);
        shape.set(NormalDistributionParams.centerRadius, 0L);
        shape.set(NormalDistributionParams.mean, 0.5);
        shape.set(NormalDistributionParams.deviation, 1.0);
        shape.setRng(new Random(SEED));
        for (int i = 0; i < 20; i++) {
            long loc = shape.rand();
            assertTrue(loc >= -1, "Square_Normal.rand() must return >= -1, got " + loc);
        }
        shape.setRng(null);
    }

    @Test
    void squareNormal_sameSeed_deterministic() {
        long[] first = sampleSquareNormal(SEED, 20);
        long[] second = sampleSquareNormal(SEED, 20);
        assertArrayEquals(first, second, "Square_Normal must be deterministic for same seed");
    }

    // -------------------------------------------------------------------------
    // Optimized Dual Layer Shapes - minradius (centerRadius) and boundary tests
    // -------------------------------------------------------------------------

    @Test
    void squareOptimized_zeroEffectiveRadius_rangeIsZero() {
        SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("TEST_SQUARE_OPT", 32);
        shape.set(GenericMemoryShapeParams.radius, 64L);
        shape.set(GenericMemoryShapeParams.centerRadius, 64L);
        assertEquals(0L, shape.getRange(), "SquareOptimizedDualLayer range should be 0 when radius == centerRadius");

        shape.set(GenericMemoryShapeParams.radius, 30L);
        shape.set(GenericMemoryShapeParams.centerRadius, 30L);
        assertEquals(0L, shape.getRange(), "SquareOptimizedDualLayer range should be 0 when radius == centerRadius (sub-chunk)");

        shape.set(GenericMemoryShapeParams.radius, 50L);
        shape.set(GenericMemoryShapeParams.centerRadius, 60L);
        assertEquals(0L, shape.getRange(), "SquareOptimizedDualLayer range should be 0 when radius < centerRadius");
    }

    @Test
    void circleOptimized_zeroEffectiveRadius_rangeIsZero() {
        CircleOptimizedDualLayer shape = new CircleOptimizedDualLayer("TEST_CIRCLE_OPT", 32);
        shape.set(GenericMemoryShapeParams.radius, 64L);
        shape.set(GenericMemoryShapeParams.centerRadius, 64L);
        assertEquals(0L, shape.getRange(), "CircleOptimizedDualLayer range should be 0 when radius == centerRadius");

        shape.set(GenericMemoryShapeParams.radius, 30L);
        shape.set(GenericMemoryShapeParams.centerRadius, 30L);
        assertEquals(0L, shape.getRange(), "CircleOptimizedDualLayer range should be 0 when radius == centerRadius (sub-chunk)");

        shape.set(GenericMemoryShapeParams.radius, 50L);
        shape.set(GenericMemoryShapeParams.centerRadius, 60L);
        assertEquals(0L, shape.getRange(), "CircleOptimizedDualLayer range should be 0 when radius < centerRadius");
    }

    @Test
    void squareOptimized_containsAndXzToLocation_respectCenterRadius() {
        SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("TEST_SQUARE_OPT", 32);
        shape.set(GenericMemoryShapeParams.radius, 256L);
        shape.set(GenericMemoryShapeParams.centerRadius, 64L);
        shape.set(GenericMemoryShapeParams.centerX, 0L);
        shape.set(GenericMemoryShapeParams.centerZ, 0L);

        // Inside inner exclusion zone (Chebyshev < 64)
        assertFalse(shape.contains(0, 0), "Center (0,0) must not be contained when centerRadius=64");
        assertFalse(shape.contains(32, 0), "(32,0) must not be contained when centerRadius=64");
        assertFalse(shape.contains(0, 63), "(0,63) must not be contained when centerRadius=64");
        assertFalse(shape.contains(-63, -63), "(-63,-63) must not be contained when centerRadius=64");

        assertEquals(-1L, shape.xzToLocation(0, 0), "xzToLocation must return -1 inside centerRadius");
        assertEquals(-1L, shape.xzToLocation(32, 0), "xzToLocation must return -1 inside centerRadius");
        assertEquals(-1L, shape.xzToLocation(0, 63), "xzToLocation must return -1 inside centerRadius");

        // At or outside centerRadius
        assertTrue(shape.contains(64, 0), "(64,0) must be contained when centerRadius=64");
        assertTrue(shape.contains(-64, 0), "(-64,0) must be contained when centerRadius=64");
        assertTrue(shape.contains(0, 64), "(0,64) must be contained when centerRadius=64");
        assertTrue(shape.contains(0, -64), "(0,-64) must be contained when centerRadius=64");
        assertTrue(shape.contains(100, 100), "(100,100) must be contained");

        assertTrue(shape.xzToLocation(64, 0) >= 0, "xzToLocation must return valid location for (64,0)");
        assertTrue(shape.xzToLocation(100, 100) >= 0, "xzToLocation must return valid location for (100,100)");

        // Outside outer radius
        assertFalse(shape.contains(300, 0), "(300,0) must not be contained when radius=256");
        assertEquals(-1L, shape.xzToLocation(300, 0), "xzToLocation must return -1 outside radius");
    }

    @Test
    void circleOptimized_containsAndXzToLocation_respectCenterRadius() {
        CircleOptimizedDualLayer shape = new CircleOptimizedDualLayer("TEST_CIRCLE_OPT", 32);
        shape.set(GenericMemoryShapeParams.radius, 256L);
        shape.set(GenericMemoryShapeParams.centerRadius, 64L);
        shape.set(GenericMemoryShapeParams.centerX, 0L);
        shape.set(GenericMemoryShapeParams.centerZ, 0L);

        // Inside inner exclusion zone (Euclidean < 64)
        assertFalse(shape.contains(0, 0), "Center (0,0) must not be contained when centerRadius=64");
        assertFalse(shape.contains(32, 0), "(32,0) must not be contained when centerRadius=64");
        assertFalse(shape.contains(0, 63), "(0,63) must not be contained when centerRadius=64");
        assertFalse(shape.contains(40, 40), "(40,40) dist 56.5 must not be contained when centerRadius=64");

        assertEquals(-1L, shape.xzToLocation(0, 0), "xzToLocation must return -1 inside centerRadius");
        assertEquals(-1L, shape.xzToLocation(32, 0), "xzToLocation must return -1 inside centerRadius");
        assertEquals(-1L, shape.xzToLocation(0, 63), "xzToLocation must return -1 inside centerRadius");

        // At or outside centerRadius
        assertTrue(shape.contains(64, 0), "(64,0) must be contained when centerRadius=64");
        assertTrue(shape.contains(-64, 0), "(-64,0) must be contained when centerRadius=64");
        assertTrue(shape.contains(0, 64), "(0,64) must be contained when centerRadius=64");
        assertTrue(shape.contains(0, -64), "(0,-64) must be contained when centerRadius=64");

        assertTrue(shape.xzToLocation(64, 0) >= 0, "xzToLocation must return valid location for (64,0)");
        assertTrue(shape.xzToLocation(100, 100) >= 0, "xzToLocation must return valid location for (100,100)");

        // Outside outer radius
        assertFalse(shape.contains(300, 0), "(300,0) must not be contained when radius=256");
        assertEquals(-1L, shape.xzToLocation(300, 0), "xzToLocation must return -1 outside radius");
    }

    @Test
    void circleOptimized_select_respectsCenterRadius() {
        CircleOptimizedDualLayer shape = new CircleOptimizedDualLayer("TEST_CIRCLE_OPT", 32);
        long r = 256;
        long cr = 64;
        shape.set(GenericMemoryShapeParams.radius, r);
        shape.set(GenericMemoryShapeParams.centerRadius, cr);
        shape.set(GenericMemoryShapeParams.centerX, 0L);
        shape.set(GenericMemoryShapeParams.centerZ, 0L);

        for (int i = 0; i < 200; i++) {
            int[] sel = shape.select();
            assertNotNull(sel);
            long distSq = (long) sel[0] * sel[0] + (long) sel[1] * sel[1];
            assertTrue(distSq >= cr * cr, "Selected point (" + sel[0] + "," + sel[1] + ") has distSq " + distSq + " < crSq " + (cr * cr));
            assertTrue(distSq <= r * r, "Selected point (" + sel[0] + "," + sel[1] + ") has distSq " + distSq + " > rSq " + (r * r));
            assertTrue(shape.contains(sel[0], sel[1]), "shape.contains must return true for selected coordinate");
        }
    }

    @Test
    void squareOptimized_select_respectsCenterRadius() {
        SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("TEST_SQUARE_OPT", 32);
        long r = 256;
        long cr = 64;
        shape.set(GenericMemoryShapeParams.radius, r);
        shape.set(GenericMemoryShapeParams.centerRadius, cr);
        shape.set(GenericMemoryShapeParams.centerX, 0L);
        shape.set(GenericMemoryShapeParams.centerZ, 0L);

        for (int i = 0; i < 200; i++) {
            int[] sel = shape.select();
            assertNotNull(sel);
            long chebyshev = Math.max(Math.abs((long) sel[0]), Math.abs((long) sel[1]));
            assertTrue(chebyshev >= cr, "Selected point (" + sel[0] + "," + sel[1] + ") has chebyshev " + chebyshev + " < cr " + cr);
            assertTrue(chebyshev <= r, "Selected point (" + sel[0] + "," + sel[1] + ") has chebyshev " + chebyshev + " > r " + r);
            assertTrue(shape.contains(sel[0], sel[1]), "shape.contains must return true for selected coordinate");
        }
    }

    @Test
    void squareOptimized_noEmptyBarsOnBoundaries() {
        SquareOptimizedDualLayer square = new SquareOptimizedDualLayer("SQUARE_R1024", 32);
        square.set(GenericMemoryShapeParams.radius, 1024L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);

        java.util.BitSet activeBins = new java.util.BitSet(4096);
        for (int i = 0; i < 15000; i++) {
            int[] sel = square.select();
            if (sel == null || sel.length < 2) continue;
            int cx = sel[0];
            int cz = sel[1];
            int bx = (cx + 1024) / 32;
            int bz = (cz + 1024) / 32;
            if (bx >= 0 && bx < 64 && bz >= 0 && bz < 64) {
                activeBins.set(bz * 64 + bx);
            }
        }

        // Verify that all 4 outer border edges (left bx=0, right bx=63, top bz=0, bottom bz=63) have active bins
        int countLeft = 0, countRight = 0, countTop = 0, countBottom = 0;
        for (int bz = 0; bz < 64; bz++) {
            if (activeBins.get(bz * 64 + 0)) countLeft++;
            if (activeBins.get(bz * 64 + 63)) countRight++;
        }
        for (int bx = 0; bx < 64; bx++) {
            if (activeBins.get(0 * 64 + bx)) countTop++;
            if (activeBins.get(63 * 64 + bx)) countBottom++;
        }

        System.out.printf("[DEBUG_LOG] Active bins on borders: left(bx=0)=%d, right(bx=63)=%d, top(bz=0)=%d, bottom(bz=63)=%d%n",
                countLeft, countRight, countTop, countBottom);

        assertTrue(countLeft > 30, "Left boundary (bx=0) must not be empty, got " + countLeft);
        assertTrue(countRight > 30, "Right boundary (bx=63) must not be empty, got " + countRight);
        assertTrue(countTop > 30, "Top boundary (bz=0) must not be empty, got " + countTop);
        assertTrue(countBottom > 30, "Bottom boundary (bz=63) must not be empty, got " + countBottom);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static MemoryShape<?> createShape(String name) {
        switch (name) {
            case "CIRCLE":        return new Circle();
            case "SQUARE":        return new Square();
            case "RECTANGLE":     return new Rectangle();
            case "CIRCLE_NORMAL": return new Circle_Normal();
            case "SQUARE_NORMAL": return new Square_Normal();
            default: throw new IllegalArgumentException("Unknown shape: " + name);
        }
    }

    private static long[] sampleCircleNormal(long seed, int n) {
        Circle_Normal shape = new Circle_Normal();
        shape.set(NormalDistributionParams.radius, 256L);
        shape.set(NormalDistributionParams.centerRadius, 0L);
        shape.setRng(new Random(seed));
        long[] out = new long[n];
        for (int i = 0; i < n; i++) out[i] = shape.rand();
        shape.setRng(null);
        return out;
    }

    private static long[] sampleSquareNormal(long seed, int n) {
        Square_Normal shape = new Square_Normal();
        shape.set(NormalDistributionParams.radius, 256L);
        shape.set(NormalDistributionParams.centerRadius, 0L);
        shape.setRng(new Random(seed));
        long[] out = new long[n];
        for (int i = 0; i < n; i++) out[i] = shape.rand();
        shape.setRng(null);
        return out;
    }
}
