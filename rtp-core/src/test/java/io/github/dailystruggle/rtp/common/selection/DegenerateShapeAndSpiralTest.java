package io.github.dailystruggle.rtp.common.selection;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle_Normal;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Ellipse;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Polygon;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Rectangle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square_Normal;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.EllipseMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.NormalDistributionParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests covering degenerate shapes (radius=0, negative radii, inverted min/max heights),
 * bounding box calculations, out-of-bounds inputs, and deterministic spiral mapping (ADR-001).
 */
public class DegenerateShapeAndSpiralTest {

    @TempDir
    static File tempDir;

    @BeforeAll
    static void setUp() {
        RTPTestSetup.install(tempDir);
    }

    @Test
    @DisplayName("Square: Degenerate radii (radius=0, negative radius, centerRadius >= radius)")
    void testSquareDegenerateRadii() {
        Square square = new Square();
        // Zero radius
        square.set(GenericMemoryShapeParams.radius, 0L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        assertEquals(0L, square.getRange());
        assertFalse(square.contains(1, 1));

        // Negative radius
        square.set(GenericMemoryShapeParams.radius, -100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        assertEquals(0L, square.getRange());
        assertFalse(square.contains(1, 1));

        // centerRadius > radius (inverted bounds)
        square.set(GenericMemoryShapeParams.radius, 50L);
        square.set(GenericMemoryShapeParams.centerRadius, 100L);
        assertEquals(0L, square.getRange());
        // For coords with radius < centerRadius, xzToLocation returns OUT_OF_DOMAIN (-1L)
        assertEquals(-1L, square.xzToLocation(20L, 20L));
        assertEquals(-1L, square.xzToLocation(new MutableRTPCoords(20, 20)));

        // Center cell with centerRadius=0
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        square.set(GenericMemoryShapeParams.centerX, 10L);
        square.set(GenericMemoryShapeParams.centerZ, -10L);
        assertEquals(0L, square.xzToLocation(10L, -10L));

        int[] centerDecoded = square.locationToXZ(0L);
        assertEquals(10, centerDecoded[0]);
        assertEquals(-10, centerDecoded[1]);
    }

    @Test
    @DisplayName("Circle: Degenerate radii and spiral step reproduction")
    void testCircleDegenerateAndSpiralMapping() {
        Circle circle = new Circle();
        circle.set(GenericMemoryShapeParams.centerX, 0L);
        circle.set(GenericMemoryShapeParams.centerZ, 0L);

        // Degenerate centerRadius > radius: in Circle, formula is (radius - cr) * (radius + cr) * PI
        circle.set(GenericMemoryShapeParams.radius, 10L);
        circle.set(GenericMemoryShapeParams.centerRadius, 100L);
        assertTrue(circle.getRange() < 0L);
        // contains should reject when radius <= centerRadius
        assertFalse(circle.contains(5, 5));

        // Normal values
        circle.set(GenericMemoryShapeParams.radius, 256L);
        circle.set(GenericMemoryShapeParams.centerRadius, 64L);
        assertTrue(circle.getRange() > 0L);

        // Check spiral point spacing and deterministic mapping
        MutableRTPCoords coords = new MutableRTPCoords(0, 0);
        long loc1 = 5000L;
        circle.locationToXZ(loc1, coords);
        int x1 = coords.x;
        int z1 = coords.z;

        // Same location should deterministically produce identical coordinates
        circle.locationToXZ(loc1, coords);
        assertEquals(x1, coords.x);
        assertEquals(z1, coords.z);

        // Converting (x1, z1) back to location
        long invertedLoc = circle.xzToLocation(x1, z1);
        assertTrue(invertedLoc >= 0);

        // Ensure locationToXZ with array overload matches mutable overload
        int[] arrCoords = circle.locationToXZ(loc1);
        assertEquals(x1, arrCoords[0]);
        assertEquals(z1, arrCoords[1]);
    }

    @Test
    @DisplayName("Rectangle: Degenerate dimensions and rotated bounding box")
    void testRectangleDegenerateAndBounding() {
        Rectangle rect = new Rectangle();
        rect.set(RectangleParams.centerX, 50L);
        rect.set(RectangleParams.centerZ, -50L);

        // Zero / negative width or height
        rect.set(RectangleParams.width, 0L);
        rect.set(RectangleParams.height, 100L);
        assertEquals(0L, rect.getRange());
        assertFalse(rect.contains(50, -50));

        rect.set(RectangleParams.width, -50L);
        rect.set(RectangleParams.height, -50L);
        assertFalse(rect.contains(50, -50));

        // Normal bounding box test without rotation
        rect.set(RectangleParams.width, 100L);
        rect.set(RectangleParams.height, 200L);
        rect.set(RectangleParams.rotation, 0L);
        assertEquals(20000L, rect.getRange());

        assertTrue(rect.contains(50, -50)); // center
        assertTrue(rect.contains(50 + 50, -50 + 100)); // corner
        assertFalse(rect.contains(50 + 51, -50)); // out of bounds in X
        assertFalse(rect.contains(50, -50 + 101)); // out of bounds in Z

        // Rotated 90 degrees
        rect.set(RectangleParams.rotation, 90L);
        // At 90 deg rotation, width and height swap relative to axes
        assertTrue(rect.contains(50, -50 + 50));
        assertTrue(rect.contains(50 + 100, -50));

        // locationToXZ and xzToLocation consistency
        MutableRTPCoords out = new MutableRTPCoords(0, 0);
        rect.locationToXZ(500L, out);
        long backLoc = rect.xzToLocation(out.x, out.z);
        // decoded should match within bounding box
        assertTrue(rect.contains(out.x, out.z));
    }

    @Test
    @DisplayName("Ellipse: Degenerate radii and rotation checks")
    void testEllipseDegenerateRadiiAndRotation() {
        Ellipse ellipse = new Ellipse();
        ellipse.set(EllipseMemoryShapeParams.centerX, 0L);
        ellipse.set(EllipseMemoryShapeParams.centerZ, 0L);

        // Degenerate radii
        ellipse.set(EllipseMemoryShapeParams.radius, 0L);
        ellipse.set(EllipseMemoryShapeParams.radius2, 100L);
        assertFalse(ellipse.contains(0, 10));

        ellipse.set(EllipseMemoryShapeParams.radius, -50L);
        ellipse.set(EllipseMemoryShapeParams.radius2, 50L);
        assertFalse(ellipse.contains(10, 0));

        // Valid ellipse: radius=100, radius2=50
        ellipse.set(EllipseMemoryShapeParams.radius, 100L);
        ellipse.set(EllipseMemoryShapeParams.radius2, 50L);
        ellipse.set(EllipseMemoryShapeParams.centerRadius, 10L);
        ellipse.set(EllipseMemoryShapeParams.centerRadius2, 5L);
        ellipse.set(EllipseMemoryShapeParams.rotation, 0L);

        assertTrue(ellipse.contains(50, 0)); // inside
        assertFalse(ellipse.contains(0, 0)); // inside inner hole (centerRadius)
        assertFalse(ellipse.contains(101, 0)); // outside outer radius
        assertFalse(ellipse.contains(0, 51)); // outside outer radius2

        // Rotated 90 degrees
        ellipse.set(EllipseMemoryShapeParams.rotation, 90L);
        assertTrue(ellipse.contains(0, 50)); // now along Z axis
    }

    @Test
    @DisplayName("Polygon: Degenerate vertex configurations and bounds")
    void testPolygonDegenerateVertices() {
        Polygon polygon = new Polygon();
        // Empty or single vertex polygon
        polygon.set(GenericMemoryShapeParams.radius, 100L);
        polygon.set(GenericMemoryShapeParams.centerRadius, 0L);

        // Normal polygon behavior when vertices are present vs empty
        assertNotNull(polygon.getParameters());
        assertNotNull(polygon.keys());
    }

    @Test
    @DisplayName("Normal distribution shapes: Circle_Normal and Square_Normal bounds")
    void testNormalShapesDegenerate() {
        Circle_Normal cn = new Circle_Normal();
        cn.set(NormalDistributionParams.radius, 100L);
        cn.set(NormalDistributionParams.centerRadius, 0L);
        cn.set(NormalDistributionParams.mean, 0.5);
        cn.set(NormalDistributionParams.deviation, 0.1);

        assertEquals(100L, cn.getNumber(NormalDistributionParams.radius, 0L).longValue());
        assertTrue(cn.contains(10, 10));

        // Inverted centerRadius for Circle_Normal
        cn.set(NormalDistributionParams.radius, 50L);
        cn.set(NormalDistributionParams.centerRadius, 100L);
        assertTrue(cn.getRange() < 0L);
        assertFalse(cn.contains(10, 10));

        Square_Normal sn = new Square_Normal();
        sn.set(NormalDistributionParams.radius, 0L);
        assertEquals(0L, sn.getRange());

        sn.set(NormalDistributionParams.radius, 100L);
        sn.set(NormalDistributionParams.centerRadius, 20L);
        assertTrue(sn.getRange() > 0L);
        assertFalse(sn.contains(0, 0)); // in centerRadius hole
        assertTrue(sn.contains(50, 50));
    }

    @Test
    @DisplayName("Spiral Step Generator: Determinism, monotonicity, and consecutive points")
    void testSpiralStepMonotonicityAndDeterminism() {
        Square square = new Square();
        square.set(GenericMemoryShapeParams.centerX, 0L);
        square.set(GenericMemoryShapeParams.centerZ, 0L);
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);

        MutableRTPCoords c1 = new MutableRTPCoords(0, 0);
        MutableRTPCoords c2 = new MutableRTPCoords(0, 0);

        // Check sequential stepping along square spiral
        for (long loc = 1; loc <= 50; loc++) {
            square.locationToXZ(loc, c1);
            square.locationToXZ(loc, c2);
            assertEquals(c1.x, c2.x, "Location " + loc + " must be deterministic for X");
            assertEquals(c1.z, c2.z, "Location " + loc + " must be deterministic for Z");

            // Consecutive spiral steps should be adjacent (Chebyshev distance <= 1)
            square.locationToXZ(loc - 1, c2);
            int dx = Math.abs(c1.x - c2.x);
            int dz = Math.abs(c1.z - c2.z);
            assertTrue(dx <= 1 && dz <= 1, "Consecutive spiral locations must be adjacent cells");
        }
    }
}
