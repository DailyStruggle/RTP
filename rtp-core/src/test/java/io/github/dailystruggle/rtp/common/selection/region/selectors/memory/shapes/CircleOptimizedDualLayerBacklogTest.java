package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class CircleOptimizedDualLayerBacklogTest {

  @Test
  @DisplayName("CircleOptimizedDualLayer constructors and curve name")
  public void testConstructors() {
    CircleOptimizedDualLayer shape1 = new CircleOptimizedDualLayer();
    assertEquals("CIRCLE_OPTIMIZED_DUAL_LAYER", shape1.name);
    assertEquals("SPIRAL_HILBERT", shape1.getCurveName());

    CircleOptimizedDualLayer shape2 = new CircleOptimizedDualLayer("CUSTOM_CIRCLE");
    assertEquals("CUSTOM_CIRCLE", shape2.name);

    CircleOptimizedDualLayer shape3 = new CircleOptimizedDualLayer("FIXED_P", 16);
    assertEquals(16, shape3.getPointEdgeChunks());

    assertThrows(IllegalArgumentException.class, () -> new CircleOptimizedDualLayer("INVALID_P", 15));
  }

  @Test
  @DisplayName("selectBacklogCandidate in standard mode and accumulate mode")
  public void testSelectBacklogCandidate() {
    CircleOptimizedDualLayer shape = new CircleOptimizedDualLayer("BACKLOG_TEST", 16);
    shape.set(GenericMemoryShapeParams.radius, 128L);
    shape.set(GenericMemoryShapeParams.centerRadius, 32L);

    // Standard mode (none / reject)
    shape.set(GenericMemoryShapeParams.mode, Mode.NONE);
    long candidate = shape.selectBacklogCandidate();
    assertTrue(candidate == -1L || (candidate >= 0 && candidate < shape.getRange()));

    // Accumulate mode
    shape.set(GenericMemoryShapeParams.mode, Mode.ACCUMULATE);
    long accCandidate = shape.selectBacklogCandidate();
    assertTrue(accCandidate == -1L || (accCandidate >= 0 && accCandidate < shape.getRange()));

    // When range <= 0
    CircleOptimizedDualLayer zeroRange = new CircleOptimizedDualLayer("ZERO_RANGE", 16);
    zeroRange.set(GenericMemoryShapeParams.radius, 10L);
    zeroRange.set(GenericMemoryShapeParams.centerRadius, 20L);
    assertEquals(-1L, zeroRange.selectBacklogCandidate());
  }

  @Test
  @DisplayName("sampleQuantileLocation with different quantiles")
  public void testSampleQuantileLocation() {
    CircleOptimizedDualLayer shape = new CircleOptimizedDualLayer("LUT_TEST", 16);
    shape.set(GenericMemoryShapeParams.radius, 128L);
    shape.set(GenericMemoryShapeParams.centerRadius, 32L);

    long loc = shape.sampleQuantileLocation(0.5);
    assertTrue(loc >= 0);

    long locLow = shape.sampleQuantileLocation(0.0);
    assertTrue(locLow >= 0);

    long locHigh = shape.sampleQuantileLocation(1.0);
    assertTrue(locHigh >= 0);
  }

  @Test
  @DisplayName("locationToXZ and xzToLocation round trip with bounds check")
  public void testLocationCoordinatesRoundTrip() {
    CircleOptimizedDualLayer shape = new CircleOptimizedDualLayer("COORDS_TEST", 16);
    shape.set(GenericMemoryShapeParams.radius, 256L);
    shape.set(GenericMemoryShapeParams.centerRadius, 64L);
    shape.set(GenericMemoryShapeParams.centerX, 100L);
    shape.set(GenericMemoryShapeParams.centerZ, -200L);

    MutableRTPCoords out = new MutableRTPCoords(0, 0);
    // Negative location gives (0, 0)
    shape.locationToXZ(-1L, out);
    assertEquals(0, out.x);
    assertEquals(0, out.z);

    // Sample valid location
    long range = shape.getRange();
    assertTrue(range > 0);
    shape.locationToXZ(range / 2, out);
    long recovered = shape.xzToLocation(out.x, out.z);
    // If inside valid circle ring, recovered should equal or be valid location
    long distSq = (long) (out.x - 100) * (out.x - 100) + (long) (out.z - (-200)) * (out.z - (-200));
    if (distSq >= 64 * 64 && distSq <= 256 * 256) {
      assertEquals(range / 2, recovered);
    } else {
      assertEquals(-1L, recovered);
    }
  }

  @Test
  @DisplayName("deriveEffectiveStride branches on spatialResolution, expand, and defaults")
  public void testDeriveEffectiveStride() {
    CircleOptimizedDualLayer shape = new CircleOptimizedDualLayer("STRIDE_BRANCH_TEST", 16);
    shape.set(GenericMemoryShapeParams.radius, 512L);
    shape.set(GenericMemoryShapeParams.centerRadius, 64L);
    shape.set(GenericMemoryShapeParams.expand, false);

    // Default
    assertEquals(1, shape.deriveEffectiveStride(shape.getRange()));

    // spatialResolution > 1
    shape.setSpatialResolution(8L);
    assertEquals(64, shape.deriveEffectiveStride(shape.getRange()));
    shape.setSpatialResolution(1L);

    // Expand mode with uniquePlacements
    shape.set(GenericMemoryShapeParams.expand, true);
    shape.set(GenericMemoryShapeParams.uniquePlacements, 4L); // footprint 7x7=49 -> 64
    assertEquals(64, shape.deriveEffectiveStride(shape.getRange()));
  }

  @Test
  @DisplayName("Effective radius expands when expand=true and bad locations exist")
  public void testEffectiveRadiusExpansion() {
    CircleOptimizedDualLayer shape = new CircleOptimizedDualLayer("EXPAND_TEST", 16);
    shape.set(GenericMemoryShapeParams.radius, 128L);
    shape.set(GenericMemoryShapeParams.centerRadius, 32L);
    shape.set(GenericMemoryShapeParams.expand, true);

    long initialEff = shape.getEffectiveRadius();
    assertEquals(128L, initialEff);

    // Add bad locations
    for (int i = 0; i < 50; i++) {
      shape.addBadLocation(i * 10L);
    }
    // Effective radius should expand
    long expandedEff = shape.getEffectiveRadius();
    assertTrue(expandedEff >= initialEff);
  }
}
