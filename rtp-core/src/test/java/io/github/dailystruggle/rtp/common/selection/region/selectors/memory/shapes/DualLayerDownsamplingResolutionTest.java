package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class DualLayerDownsamplingResolutionTest {

  @Test
  @DisplayName("Default fixed radius without expand resolves to full 1:1 resolution (stride S = 1)")
  public void testDefaultFixedRadiusResolution() {
    SquareOptimizedDualLayer square = new SquareOptimizedDualLayer("TEST_SQUARE", 32);
    square.set(GenericMemoryShapeParams.radius, 256L);
    square.set(GenericMemoryShapeParams.centerRadius, 64L);
    square.set(GenericMemoryShapeParams.expand, false);

    // Default spatialResolution=1, uniquePlacements="auto" with expand=false still gives stride S=1
    assertEquals(1, square.deriveEffectiveStride(square.getRange()));

    CircleOptimizedDualLayer circle = new CircleOptimizedDualLayer("TEST_CIRCLE", 32);
    circle.set(GenericMemoryShapeParams.radius, 256L);
    circle.set(GenericMemoryShapeParams.centerRadius, 64L);
    circle.set(GenericMemoryShapeParams.expand, false);

    assertEquals(1, circle.deriveEffectiveStride(circle.getRange()));
  }

  @Test
  @DisplayName("Default shape settings have uniquePlacements set to 0 (disabled by default in geometric shapes)")
  public void testDefaultUniquePlacementsIsAuto() {
    Square square = new Square();
    assertEquals(0, square.getData().get(GenericMemoryShapeParams.uniquePlacements));

    Circle circle = new Circle();
    assertEquals(0, circle.getData().get(GenericMemoryShapeParams.uniquePlacements));

    SquareOptimizedDualLayer squareDual = new SquareOptimizedDualLayer("SQUARE_DUAL", 32);
    assertEquals(0, squareDual.getData().get(GenericMemoryShapeParams.uniquePlacements));

    // When expand=true and uniquePlacements is explicitly set to "auto", stride derives to 256
    squareDual.set(GenericMemoryShapeParams.radius, 1024L);
    squareDual.set(GenericMemoryShapeParams.centerRadius, 64L);
    squareDual.set(GenericMemoryShapeParams.expand, true);
    squareDual.set(GenericMemoryShapeParams.uniquePlacements, "auto");
    assertEquals(256, squareDual.deriveEffectiveStride(squareDual.getRange()));
  }

  @Test
  @DisplayName("Explicit spatialResolution override dictates power-of-four dyadic stride")
  public void testExplicitSpatialResolutionOverride() {
    SquareOptimizedDualLayer square = new SquareOptimizedDualLayer("TEST_SQUARE", 32);
    square.set(GenericMemoryShapeParams.radius, 1024L);
    square.set(GenericMemoryShapeParams.centerRadius, 64L);
    square.set(GenericMemoryShapeParams.expand, false);

    // spatialResolution = 4 chunks -> cellDim = 4, stride = 16
    square.setSpatialResolution(4L);
    assertEquals(16, square.deriveEffectiveStride(square.getRange()));

    // spatialResolution = 8 chunks -> cellDim = 8, stride = 64
    square.setSpatialResolution(8L);
    assertEquals(64, square.deriveEffectiveStride(square.getRange()));

    // spatialResolution = 16 chunks -> cellDim = 16, stride = 256
    square.setSpatialResolution(16L);
    assertEquals(256, square.deriveEffectiveStride(square.getRange()));
  }

  @Test
  @DisplayName("Expand mode automatically derives dyadic stride from uniquePlacements radius")
  public void testExpandModeDerivation() {
    SquareOptimizedDualLayer square = new SquareOptimizedDualLayer("TEST_SQUARE", 32);
    square.set(GenericMemoryShapeParams.radius, 1024L);
    square.set(GenericMemoryShapeParams.centerRadius, 64L);
    square.set(GenericMemoryShapeParams.expand, true);

    // uniquePlacements = 4 chunks footprint (7x7 = 49) -> rounds to stride 64
    square.set(GenericMemoryShapeParams.uniquePlacements, 4);
    assertEquals(64, square.deriveEffectiveStride(square.getRange()));

    // uniquePlacements = 8 chunks footprint (15x15 = 225) -> rounds to stride 256
    square.set(GenericMemoryShapeParams.uniquePlacements, 8);
    assertEquals(256, square.deriveEffectiveStride(square.getRange()));

    // uniquePlacements = "auto" -> derives power-of-two view distance (10 -> 8 chunks -> stride 256)
    square.set(GenericMemoryShapeParams.uniquePlacements, "auto");
    assertEquals(256, square.deriveEffectiveStride(square.getRange()));
  }

  @Test
  @DisplayName("Sample produces valid coordinates within bounds across downsampled strides")
  public void testSampleCoordinatesWithinBounds() {
    SquareOptimizedDualLayer square = new SquareOptimizedDualLayer("TEST_SQUARE", 32);
    square.set(GenericMemoryShapeParams.radius, 256L);
    square.set(GenericMemoryShapeParams.centerRadius, 64L);
    square.set(GenericMemoryShapeParams.expand, true);
    square.set(GenericMemoryShapeParams.uniquePlacements, "auto"); // S = 256

    for (int i = 0; i < 100; i++) {
      int[] res = square.select();
      int cx = res[0];
      int cz = res[1];
      int chebyshev = Math.max(Math.abs(cx), Math.abs(cz));
      assertTrue(chebyshev >= 64, "Location must be outside center radius");
    }
  }
}
