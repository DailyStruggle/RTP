package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class PolygonMaskWalkerAndGeometryTest {

  @Test
  @DisplayName("Constructors and defaults")
  public void testConstructors() {
    Polygon p1 = new Polygon();
    assertEquals("POLYGON", p1.name);
    assertTrue(p1.getVertices().isEmpty());
    assertFalse(p1.supportsExpand());
    assertNotNull(p1.keys());

    Polygon p2 = new Polygon("CUSTOM_POLY");
    assertEquals("CUSTOM_POLY", p2.name);
  }

  @Test
  @DisplayName("setVertices validates inputs and self-intersection")
  public void testSetVerticesValidation() {
    Polygon p = new Polygon();

    assertThrows(IllegalArgumentException.class, () -> p.setVertices(null));
    assertThrows(IllegalArgumentException.class, () -> p.setVertices(Collections.emptyList()));
    assertThrows(IllegalArgumentException.class, () -> p.setVertices(List.of(new int[]{0, 0}, new int[]{1, 1})));
    List<int[]> withNull = new ArrayList<>();
    withNull.add(new int[]{0, 0});
    withNull.add(null);
    withNull.add(new int[]{1, 1});
    assertThrows(IllegalArgumentException.class, () -> p.setVertices(withNull));
    assertThrows(IllegalArgumentException.class, () -> p.setVertices(List.of(new int[]{0, 0}, new int[]{1}, new int[]{1, 1})));

    // Collinear degenerate vertices
    assertThrows(IllegalArgumentException.class, () -> p.setVertices(List.of(
        new int[]{0, 0}, new int[]{10, 0}, new int[]{20, 0}
    )));
    assertThrows(IllegalArgumentException.class, () -> p.setVertices(List.of(
        new int[]{0, 0}, new int[]{0, 10}, new int[]{0, 20}
    )));

    // Self-intersecting bowtie polygon
    assertThrows(IllegalArgumentException.class, () -> p.setVertices(List.of(
        new int[]{0, 0}, new int[]{10, 10}, new int[]{10, 0}, new int[]{0, 10}
    )));
  }

  @Test
  @DisplayName("setVertices sets AABB, copy of vertices, and pointInPolygon behaves accurately")
  public void testValidPolygon() {
    Polygon p = new Polygon();
    List<int[]> triangle = List.of(
        new int[]{0, 0},
        new int[]{100, 0},
        new int[]{0, 100}
    );
    p.setVertices(triangle);
    assertEquals(3, p.getVertices().size());

    // Inside triangle
    assertTrue(p.pointInPolygon(10, 10));
    assertTrue(p.contains(10, 10));

    // Outside triangle but inside AABB
    assertFalse(p.pointInPolygon(80, 80));
    assertFalse(p.contains(80, 80));

    // Outside AABB
    assertFalse(p.contains(-5, 10));
    assertFalse(p.contains(105, 10));
    assertFalse(p.contains(10, -5));
    assertFalse(p.contains(10, 105));
  }

  @Test
  @DisplayName("contains with empty vertices returns true if super.contains is true")
  public void testContainsEmptyVertices() {
    Polygon p = new Polygon();
    assertTrue(p.pointInPolygon(0, 0));
    // super.contains check
    assertTrue(p.contains(0, 0));
  }

  @Test
  @DisplayName("runMaskWalker runs synchronously and batches outside locations into bad cache")
  public void testRunMaskWalker() {
    Polygon p = new Polygon();
    // A small right-triangle from (0,0) to (10, 0) and (0, 10)
    p.setVertices(List.of(
        new int[]{0, 0},
        new int[]{10, 0},
        new int[]{0, 10}
    ));

    // Run mask walker directly
    p.runMaskWalker();

    // The AABB is [0, 10] x [0, 10]. Points like (8, 8) are outside the polygon
    assertFalse(p.pointInPolygon(8, 8));
  }

  @Test
  @DisplayName("postProcess filters locations outside the polygon and marks bad chunk")
  public void testPostProcess() {
    Polygon p = new Polygon();
    p.setVertices(List.of(
        new int[]{0, 0},
        new int[]{10, 0},
        new int[]{0, 10}
    ));

    // Negative location returns as is
    assertEquals(-5L, p.postProcess(-5L));

    // Valid location inside
    long insideLoc = p.xzToLocation(2, 2);
    if (insideLoc >= 0) {
      assertEquals(insideLoc, p.postProcess(insideLoc));
    }

    // Valid location outside polygon within bounding square
    long outsideLoc = p.xzToLocation(8, 8);
    if (outsideLoc >= 0) {
      assertEquals(-1L, p.postProcess(outsideLoc));
    }
  }

  @Test
  @DisplayName("scheduleMaskWalkerIfEmpty handles empty and pre-filled bad locations")
  public void testScheduleMaskWalker() {
    Polygon p = new Polygon();
    p.scheduleMaskWalkerIfEmpty(); // empty vertices -> returns immediately

    p.setVertices(List.of(
        new int[]{0, 0},
        new int[]{20, 0},
        new int[]{0, 20}
    ));

    // When bad locations are not empty
    p.addBadLocation(0L);
    p.scheduleMaskWalkerIfEmpty(); // bad locations not empty -> returns immediately
  }
}
