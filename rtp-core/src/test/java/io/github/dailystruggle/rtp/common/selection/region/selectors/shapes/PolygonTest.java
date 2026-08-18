package io.github.dailystruggle.rtp.common.selection.region.selectors.shapes;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Polygon;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Polygon} memory shape (ADR-034): vertex validation,
 * point-in-polygon containment, AABB bounds, and random selection.
 */
public class PolygonTest {

  @BeforeAll
  static void setupServer() {
    MockRTPServerAccessor accessor =
        new MockRTPServerAccessor(new java.io.File("target/test-data"));
    RTP.serverAccessor = accessor;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
  }

  // ---------------------------------------------------------------------------
  // Validation
  // ---------------------------------------------------------------------------

  @Test
  void setVertices_lessThanThreeVertices_rejected() {
    Polygon p = new Polygon();
    assertThrows(IllegalArgumentException.class,
        () -> p.setVertices(Arrays.asList(new int[] {0, 0}, new int[] {10, 0})));
    assertThrows(IllegalArgumentException.class,
        () -> p.setVertices(Collections.emptyList()));
    assertThrows(IllegalArgumentException.class, () -> p.setVertices(null));
  }

  @Test
  void setVertices_collinear_rejected() {
    Polygon p = new Polygon();
    // All vertices on z=0 line -> zero-extent AABB on z-axis.
    assertThrows(IllegalArgumentException.class,
        () -> p.setVertices(Arrays.asList(
            new int[] {0, 0}, new int[] {10, 0}, new int[] {20, 0})));
  }

  @Test
  void setVertices_selfIntersecting_rejected() {
    Polygon p = new Polygon();
    // Bowtie: edges (0,0)->(10,10) and (10,0)->(0,10) cross.
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> p.setVertices(Arrays.asList(
            new int[] {0, 0},
            new int[] {10, 0},
            new int[] {0, 10},
            new int[] {10, 10})));
    assertTrue(ex.getMessage().toLowerCase().contains("self-intersect"),
        "Expected self-intersection message, got: " + ex.getMessage());
  }

  // ---------------------------------------------------------------------------
  // Point-in-polygon correctness
  // ---------------------------------------------------------------------------

  @Test
  void pointInPolygon_convexSquare_correctness() {
    Polygon p = new Polygon();
    p.setVertices(Arrays.asList(
        new int[] {0, 0},
        new int[] {100, 0},
        new int[] {100, 100},
        new int[] {0, 100}));

    assertTrue(p.pointInPolygon(50, 50), "Center should be inside");
    assertFalse(p.pointInPolygon(-1, 50), "Left of polygon should be outside");
    assertFalse(p.pointInPolygon(101, 50), "Right of polygon should be outside");
    assertFalse(p.pointInPolygon(50, -1), "Below polygon should be outside");
    assertFalse(p.pointInPolygon(50, 200), "Above polygon should be outside");
  }

  @Test
  void pointInPolygon_concavePentagon_notchExcluded() {
    // L-shape:
    //   (0,0) - (100,0) - (100,40) - (40,40) - (40,100) - (0,100) - close
    // The "notch" at (60,60)..(100,100) is outside.
    Polygon p = new Polygon();
    p.setVertices(Arrays.asList(
        new int[] {0, 0},
        new int[] {100, 0},
        new int[] {100, 40},
        new int[] {40, 40},
        new int[] {40, 100},
        new int[] {0, 100}));

    assertTrue(p.pointInPolygon(20, 20), "Lower-left interior");
    assertTrue(p.pointInPolygon(20, 80), "Upper-left interior");
    assertTrue(p.pointInPolygon(80, 20), "Lower-right interior");
    assertFalse(p.pointInPolygon(80, 80), "Notch should be excluded");
    assertFalse(p.pointInPolygon(60, 60), "Notch interior should be excluded");
  }

  @Test
  void pointInPolygon_triangle_correctness() {
    Polygon p = new Polygon();
    p.setVertices(Arrays.asList(
        new int[] {0, 0},
        new int[] {100, 0},
        new int[] {50, 100}));

    assertTrue(p.pointInPolygon(50, 30), "Inside triangle");
    assertFalse(p.pointInPolygon(0, 100), "Outside (top-left corner of AABB)");
    assertFalse(p.pointInPolygon(100, 100), "Outside (top-right corner of AABB)");
  }

  // ---------------------------------------------------------------------------
  // AABB mirroring onto inherited Square
  // ---------------------------------------------------------------------------

  @Test
  void setVertices_aabbMirroredOntoSquare() {
    Polygon p = new Polygon();
    p.setVertices(Arrays.asList(
        new int[] {-50, -30},
        new int[] {70, -30},
        new int[] {70, 90},
        new int[] {-50, 90}));

    Object radius = p.getData(GenericMemoryShapeParams.radius);
    Object cx = p.getData(GenericMemoryShapeParams.centerX);
    Object cz = p.getData(GenericMemoryShapeParams.centerZ);
    Object expand = p.getData(GenericMemoryShapeParams.expand);

    assertNotNull(radius);
    // half-extents: x = (70 - -50 + 1) / 2 = 60, z = (90 - -30 + 1) / 2 = 60
    // radius = max(60, 60) = 60
    assertEquals(60, ((Number) radius).intValue());
    assertEquals(10, ((Number) cx).intValue());  // (-50 + 70) / 2 = 10
    assertEquals(30, ((Number) cz).intValue());  // (-30 + 90) / 2 = 30
    assertEquals(Boolean.FALSE, expand);
  }

  // ---------------------------------------------------------------------------
  // expand forced to false on rand()
  // ---------------------------------------------------------------------------

  @Test
  void rand_forcesExpandFalseEvenIfSetTrue() {
    Polygon p = new Polygon();
    p.setVertices(Arrays.asList(
        new int[] {0, 0},
        new int[] {200, 0},
        new int[] {200, 200},
        new int[] {0, 200}));
    p.setRng(new java.util.Random(42));

    // Admin somehow flips expand=true after construction.
    p.set(GenericMemoryShapeParams.expand, true);

    // Exercise rand() a few times to trigger the override.
    for (int i = 0; i < 10; i++) {
      p.rand();
    }

    assertEquals(Boolean.FALSE, p.getData(GenericMemoryShapeParams.expand),
        "Polygon.rand() must reset expand to false");
  }

  @Test
  void contains_outsidePolygonButInsideAabb_returnsFalse() {
    // Concave L; corner notch is in the AABB but outside the polygon.
    Polygon p = new Polygon();
    p.setVertices(Arrays.asList(
        new int[] {0, 0},
        new int[] {100, 0},
        new int[] {100, 40},
        new int[] {40, 40},
        new int[] {40, 100},
        new int[] {0, 100}));

    // (80, 80) is well inside the AABB but inside the notch (outside polygon).
    assertFalse(p.contains(80, 80),
        "Point in notch must be reported as outside the polygon");
    // (20, 20) is inside both.
    assertTrue(p.contains(20, 20));
  }

  @Test
  void getVertices_returnsAssignedVerticesInOrder() {
    Polygon p = new Polygon();
    List<int[]> input = Arrays.asList(
        new int[] {0, 0},
        new int[] {10, 0},
        new int[] {10, 10},
        new int[] {0, 10});
    p.setVertices(input);
    List<int[]> out = p.getVertices();
    assertEquals(4, out.size());
    assertArrayEquals(new int[] {0, 0}, out.get(0));
    assertArrayEquals(new int[] {10, 0}, out.get(1));
    assertArrayEquals(new int[] {10, 10}, out.get(2));
    assertArrayEquals(new int[] {0, 10}, out.get(3));
  }
}
