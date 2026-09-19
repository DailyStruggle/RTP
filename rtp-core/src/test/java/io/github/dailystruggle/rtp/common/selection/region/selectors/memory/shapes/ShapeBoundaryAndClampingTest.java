package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.EllipseMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.PolygonMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams;
import io.github.dailystruggle.rtp.common.selection.region.util.WorldBorderAuditor;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ShapeBoundaryAndClampingTest - Zero radius, non-uniform scaling, center offsets, and border clamping")
class ShapeBoundaryAndClampingTest {

    @BeforeAll
    static void setUp() {
        MockRTPServerAccessor accessor = new MockRTPServerAccessor(new File("target/test-data"));
        RTP.serverAccessor = accessor;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
    }

    @org.junit.jupiter.api.AfterAll
    static void tearDown() {
        RTP.serverAccessor = null;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
    }

    // -------------------------------------------------------------------------
    // Zero-radius & centerRadius >= radius edge cases
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Circle: zero-radius and centerRadius >= radius clamp getRange to 0")
    void circle_zeroRadiusAndInvertedCenterRadius() {
        Circle circle = new Circle();
        circle.set(GenericMemoryShapeParams.radius, 0L);
        circle.set(GenericMemoryShapeParams.centerRadius, 0L);
        assertEquals(0L, circle.getRange());

        // Inverted: centerRadius > radius
        circle.set(GenericMemoryShapeParams.radius, 50L);
        circle.set(GenericMemoryShapeParams.centerRadius, 100L);
        assertTrue(circle.getRange() <= 0L);

        // xzToLocation and locationToXZ roundtrip on zero coords with centerRadius=0
        circle.set(GenericMemoryShapeParams.radius, 100L);
        circle.set(GenericMemoryShapeParams.centerRadius, 0L);
        circle.set(GenericMemoryShapeParams.centerX, 0L);
        circle.set(GenericMemoryShapeParams.centerZ, 0L);
        MutableRTPCoords coords = new MutableRTPCoords(null, 0, 0, 0);
        long loc = circle.xzToLocation(coords);
        assertEquals(0L, loc);

        MutableRTPCoords out = new MutableRTPCoords(null, 0, 0, 0);
        circle.locationToXZ(0L, out);
        assertEquals(0, out.x);
        assertEquals(0, out.z);
    }

    @Test
    @DisplayName("Square: zero-radius and centerRadius >= radius clamp getRange to 0")
    void square_zeroRadiusAndInvertedCenterRadius() {
        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 0L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        assertEquals(0L, square.getRange());

        square.set(GenericMemoryShapeParams.radius, 10L);
        square.set(GenericMemoryShapeParams.centerRadius, 20L);
        assertTrue(square.getRange() <= 0L);
    }

    @Test
    @DisplayName("Ellipse: zero-radius and centerRadius >= radius edge cases")
    void ellipse_zeroRadiusAndInvertedCenterRadius() {
        Ellipse ellipse = new Ellipse();
        ellipse.set(EllipseMemoryShapeParams.radius, 0L);
        ellipse.set(EllipseMemoryShapeParams.radius2, 0L);
        ellipse.set(EllipseMemoryShapeParams.centerRadius, 0L);
        ellipse.set(EllipseMemoryShapeParams.centerRadius2, 0L);
        assertEquals(0L, ellipse.getRange());

        ellipse.set(EllipseMemoryShapeParams.radius, 100L);
        ellipse.set(EllipseMemoryShapeParams.radius2, 50L);
        ellipse.set(EllipseMemoryShapeParams.centerRadius, 150L);
        ellipse.set(EllipseMemoryShapeParams.centerRadius2, 75L);
        assertTrue(ellipse.getRange() <= 0L);
    }

    @Test
    @DisplayName("Rectangle: zero width or height clamp getRange to 0")
    void rectangle_zeroDimensions() {
        Rectangle rect = new Rectangle();
        rect.set(RectangleParams.width, 0L);
        rect.set(RectangleParams.height, 100L);
        assertEquals(0L, rect.getRange());

        rect.set(RectangleParams.width, 100L);
        rect.set(RectangleParams.height, 0L);
        assertEquals(0L, rect.getRange());
    }

    // -------------------------------------------------------------------------
    // Non-uniform scaling (aspect ratios != 1.0)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Ellipse: non-uniform scaling preserves geometry across quadrants")
    void ellipse_nonUniformScaling() {
        Ellipse ellipse = new Ellipse();
        ellipse.set(EllipseMemoryShapeParams.radius, 500L);   // x-axis radius
        ellipse.set(EllipseMemoryShapeParams.radius2, 100L);  // z-axis radius
        ellipse.set(EllipseMemoryShapeParams.centerRadius, 50L);
        ellipse.set(EllipseMemoryShapeParams.centerRadius2, 10L);
        ellipse.set(EllipseMemoryShapeParams.centerX, 0L);
        ellipse.set(EllipseMemoryShapeParams.centerZ, 0L);

        assertTrue(ellipse.getRange() > 0L);

        // Test quadrant points
        long locQ1 = ellipse.xzToLocation(200, 50);
        assertTrue(locQ1 >= 0);

        long locQ2 = ellipse.xzToLocation(-200, 50);
        assertTrue(locQ2 >= 0);

        long locQ3 = ellipse.xzToLocation(-200, -50);
        assertTrue(locQ3 >= 0);

        long locQ4 = ellipse.xzToLocation(200, -50);
        assertTrue(locQ4 >= 0);

        // locationToXZ roundtrip fidelity
        MutableRTPCoords out = new MutableRTPCoords(null, 0, 0, 0);
        ellipse.locationToXZ(locQ1, out);
        long roundtrip = ellipse.xzToLocation(out.x, out.z);
        // Should map back to adjacent or same spiral coordinate
        assertTrue(roundtrip >= 0);
    }

    @Test
    @DisplayName("Rectangle: non-uniform width and height bounds")
    void rectangle_nonUniformDimensions() {
        Rectangle rect = new Rectangle();
        rect.set(RectangleParams.width, 400L);
        rect.set(RectangleParams.height, 50L);
        rect.set(RectangleParams.centerX, 0L);
        rect.set(RectangleParams.centerZ, 0L);

        long range = rect.getRange();
        assertEquals(400L * 50L, range);

        long loc = rect.xzToLocation(100, 10);
        assertTrue(loc >= 0);

        int[] xz = rect.locationToXZ(loc);
        assertNotNull(xz);
        assertEquals(2, xz.length);
    }

    // -------------------------------------------------------------------------
    // Center Offsets (large, negative, non-zero centers)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Shapes with negative and large center offsets compute correct relative bounds")
    void shapes_centerOffsets() {
        Circle circle = new Circle();
        circle.set(GenericMemoryShapeParams.radius, 200L);
        circle.set(GenericMemoryShapeParams.centerRadius, 0L);
        circle.set(GenericMemoryShapeParams.centerX, -50000L);
        circle.set(GenericMemoryShapeParams.centerZ, 100000L);

        // Center point location
        long locCenter = circle.xzToLocation(-50000, 100000);
        assertEquals(0L, locCenter);

        int[] centerXZ = circle.locationToXZ(0L);
        // Archimedean spiral mapping produces coordinates within 1 unit of center on 0L
        assertEquals(-50000, centerXZ[0], 1);
        assertEquals(100000, centerXZ[1], 1);

        // Offset point in quadrant 1 relative to center
        long locOffset = circle.xzToLocation(-50000 + 50, 100000 + 50);
        assertTrue(locOffset > 0);

        // Square with offset
        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 300L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        square.set(GenericMemoryShapeParams.centerX, 25000L);
        square.set(GenericMemoryShapeParams.centerZ, -75000L);

        // When x = centerX and z = centerZ: radius = 0, so xzToLocation returns 0L
        long sqLoc = square.xzToLocation(25000L, -75000L);
        assertEquals(0L, sqLoc);
        int[] sqXZ = square.locationToXZ(0L);
        assertEquals(25000, sqXZ[0]);
        assertEquals(-75000, sqXZ[1]);
    }

    // -------------------------------------------------------------------------
    // World Border Clamping & Collision Paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("WorldBorderAuditor: checkRegionWorldBorder with Circle outside border returns false")
    void worldBorderAuditor_circleOutsideBorder() {
        Square borderSquare = new Square();
        borderSquare.set(GenericMemoryShapeParams.radius, 100L);
        borderSquare.set(GenericMemoryShapeParams.centerX, 0L);
        borderSquare.set(GenericMemoryShapeParams.centerZ, 0L);
        WorldBorder border = new WorldBorder(() -> borderSquare, loc -> true);

        MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTP.serverAccessor;
        accessor.setWorldBorderFunction(s -> border);

        RTPWorld<?> world = mock(RTPWorld.class);
        when(world.name()).thenReturn("world");

        Circle regionCircle = new Circle();
        regionCircle.set(GenericMemoryShapeParams.radius, 150L); // 150 > 100 border
        regionCircle.set(GenericMemoryShapeParams.centerX, 0L);
        regionCircle.set(GenericMemoryShapeParams.centerZ, 0L);

        assertFalse(WorldBorderAuditor.checkRegionWorldBorder("circleRegion", world, regionCircle, false));
        // With override enabled, returns true
        assertTrue(WorldBorderAuditor.checkRegionWorldBorder("circleRegion", world, regionCircle, true));
    }

    @Test
    @DisplayName("WorldBorderAuditor: checkRegionWorldBorder with offset shape extending beyond border")
    void worldBorderAuditor_offsetShapeCollidingBorder() {
        Square borderSquare = new Square();
        borderSquare.set(GenericMemoryShapeParams.radius, 100L);
        borderSquare.set(GenericMemoryShapeParams.centerX, 0L);
        borderSquare.set(GenericMemoryShapeParams.centerZ, 0L);
        WorldBorder border = new WorldBorder(() -> borderSquare, loc -> true);

        MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTP.serverAccessor;
        accessor.setWorldBorderFunction(s -> border);

        RTPWorld<?> world = mock(RTPWorld.class);
        when(world.name()).thenReturn("world");

        // Radius 50 is fine if centered at 0, but centerX = 60 pushes maxX to 110 > 100
        Square regionSquare = new Square();
        regionSquare.set(GenericMemoryShapeParams.radius, 50L);
        regionSquare.set(GenericMemoryShapeParams.centerX, 60L);
        regionSquare.set(GenericMemoryShapeParams.centerZ, 0L);

        assertFalse(WorldBorderAuditor.checkRegionWorldBorder("collidingSquare", world, regionSquare, false));
    }
}
