package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PointEdgeDerivationTest {

  @Test
  @DisplayName("derivePointEdgeChunks returns expected values across boundary radii")
  void testDerivePointEdgeChunksValues() {
    assertEquals(1, MemoryShape.derivePointEdgeChunks(62));
    assertEquals(8, MemoryShape.derivePointEdgeChunks(256));
    assertEquals(32, MemoryShape.derivePointEdgeChunks(1024));
    assertEquals(32, MemoryShape.derivePointEdgeChunks(100000));
  }

  @Test
  @DisplayName("every returned value is a power of two and <= 32")
  void testPowerOfTwoAndCeiling() {
    for (long r = 0; r <= 2048; r++) {
      int p = MemoryShape.derivePointEdgeChunks(r);
      assertTrue(p >= 1 && p <= 32, "P must be between 1 and 32: " + p);
      assertEquals(1, Integer.bitCount(p), "P must be power of two: " + p);
    }
  }

  @Test
  @DisplayName("no-arg SquareOptimizedDualLayer derives point edge from radius")
  void testNoArgSquareDerivation() {
    SquareOptimizedDualLayer square62 = new SquareOptimizedDualLayer();
    square62.set(GenericMemoryShapeParams.radius, 62L);
    assertEquals(1, square62.getPointEdgeChunks());

    SquareOptimizedDualLayer square1024 = new SquareOptimizedDualLayer();
    square1024.set(GenericMemoryShapeParams.radius, 1024L);
    assertEquals(32, square1024.getPointEdgeChunks());
  }

  @Test
  @DisplayName("explicitly constructed SquareOptimizedDualLayer pins point edge chunks")
  void testExplicitSquarePinned() {
    SquareOptimizedDualLayer square = new SquareOptimizedDualLayer("X", 8);
    assertEquals(8, square.getPointEdgeChunks());
    square.set(GenericMemoryShapeParams.radius, 62L);
    assertEquals(8, square.getPointEdgeChunks());
    square.set(GenericMemoryShapeParams.radius, 1024L);
    assertEquals(8, square.getPointEdgeChunks());
    square.set(GenericMemoryShapeParams.radius, 100000L);
    assertEquals(8, square.getPointEdgeChunks());
  }

  @Test
  @DisplayName("getRange() is strictly positive and locationToXZ(0) round-trips to 0 for square and circle")
  void testPositiveRangeAndRoundTripLocationZero() {
    long[] testRadii = {62L, 256L, 1024L};

    for (long r : testRadii) {
      // Test SquareOptimizedDualLayer
      SquareOptimizedDualLayer square = new SquareOptimizedDualLayer();
      square.set(GenericMemoryShapeParams.radius, r);
      square.set(GenericMemoryShapeParams.centerRadius, 0L);
      assertTrue(square.getRange() > 0, "Square getRange() must be strictly positive for radius " + r);

      int[] coordsSquare = square.locationToXZ(0L);
      assertNotNull(coordsSquare);
      long locSquare = square.xzToLocation(coordsSquare[0], coordsSquare[1]);
      assertEquals(0L, locSquare, "Square locationToXZ(0) round-trip failed for radius " + r);

      // Test CircleOptimizedDualLayer
      CircleOptimizedDualLayer circle = new CircleOptimizedDualLayer();
      circle.set(GenericMemoryShapeParams.radius, r);
      circle.set(GenericMemoryShapeParams.centerRadius, 0L);
      assertTrue(circle.getRange() > 0, "Circle getRange() must be strictly positive for radius " + r);

      int[] coordsCircle = circle.locationToXZ(0L);
      assertNotNull(coordsCircle);
      long locCircle = circle.xzToLocation(coordsCircle[0], coordsCircle[1]);
      assertEquals(0L, locCircle, "Circle locationToXZ(0) round-trip failed for radius " + r);
    }
  }
}
