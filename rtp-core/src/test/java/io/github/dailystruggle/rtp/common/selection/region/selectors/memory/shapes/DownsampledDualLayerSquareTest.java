package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class DownsampledDualLayerSquareTest {

  @Test
  @DisplayName("Default initialization, getters, and setters")
  public void testGettersAndSetters() {
    DownsampledDualLayerSquare shape = new DownsampledDualLayerSquare("DOWNSAMPLED_TEST", 32);
    assertEquals(0, shape.getExplicitStride());
    assertTrue(shape.isAutoDeriveStrideFromRadius());

    shape.setExplicitStride(64);
    assertEquals(64, shape.getExplicitStride());

    shape.setExplicitStride(-5);
    assertEquals(1, shape.getExplicitStride());

    shape.setAutoDeriveStrideFromRadius(false);
    assertFalse(shape.isAutoDeriveStrideFromRadius());
    shape.setAutoDeriveStrideFromRadius(true);
    assertTrue(shape.isAutoDeriveStrideFromRadius());
  }

  @Test
  @DisplayName("deriveStrideFromUniqueRadius with various configurations")
  public void testDeriveStrideFromUniqueRadius() {
    DownsampledDualLayerSquare shape = new DownsampledDualLayerSquare("STRIDE_TEST", 32);

    // Default uniquePlacements = 0 (or <= 1) => returns 1
    shape.set(GenericMemoryShapeParams.uniquePlacements, 0);
    assertEquals(1, shape.deriveStrideFromUniqueRadius());

    shape.set(GenericMemoryShapeParams.uniquePlacements, 1);
    assertEquals(1, shape.deriveStrideFromUniqueRadius());

    // Explicit stride overrides
    DownsampledDualLayerSquare explicitShape = new DownsampledDualLayerSquare("EXPLICIT_TEST", 32);
    explicitShape.setExplicitStride(128);
    assertEquals(128, explicitShape.deriveStrideFromUniqueRadius());

    // uniquePlacements = 4 => shift = 64 - Long.numberOfLeadingZeros(3) = 64 - 62 = 2 => powerOfTwoRu = 4 => derivedStride = 16
    shape.set(GenericMemoryShapeParams.uniquePlacements, 4);
    assertEquals(16, shape.deriveStrideFromUniqueRadius());

    // uniquePlacements = 8 => shift = 64 - Long.numberOfLeadingZeros(7) = 3 => powerOfTwoRu = 8 => derivedStride = 64
    shape.set(GenericMemoryShapeParams.uniquePlacements, 8);
    assertEquals(64, shape.deriveStrideFromUniqueRadius());

    // uniquePlacements = 16 => shift = 4 => powerOfTwoRu = 16 => derivedStride = 256
    shape.set(GenericMemoryShapeParams.uniquePlacements, 16);
    assertEquals(256, shape.deriveStrideFromUniqueRadius());

    // Capped by maxStrideByBin (binArea / 2 = (16 * 16) / 2 = 128)
    DownsampledDualLayerSquare smallBin = new DownsampledDualLayerSquare("SMALL_BIN", 16);
    smallBin.set(GenericMemoryShapeParams.uniquePlacements, 16);
    assertEquals(128, smallBin.deriveStrideFromUniqueRadius());
  }

  @Test
  @DisplayName("sample with range <= 1.0 returns 0.0")
  public void testSampleEdgeCases() {
    DownsampledDualLayerSquare shape = new DownsampledDualLayerSquare("EDGE_TEST", 32);
    assertEquals(0.0, shape.sample(0.0));
    assertEquals(0.0, shape.sample(1.0));
    assertEquals(0.0, shape.sample(-10.0));
  }

  @Test
  @DisplayName("sample with stride <= 1 (full Feistel permutation across domain)")
  public void testSampleStrideOne() {
    DownsampledDualLayerSquare shape = new DownsampledDualLayerSquare("STRIDE_1", 32);
    shape.setExplicitStride(1);

    double domain = 64.0;
    Set<Double> seen = new HashSet<>();
    for (int i = 0; i < 64; i++) {
      double s = shape.sample(domain);
      assertTrue(s >= 0.0 && s < domain, "Sample out of bounds: " + s);
      seen.add(s);
    }
    // With Feistel permutation across [0, 64), 64 distinct draws should cover the domain exactly
    assertEquals(64, seen.size());
  }

  @Test
  @DisplayName("sample with dyadic striding and phase progression")
  public void testSampleDyadicStriding() {
    DownsampledDualLayerSquare shape = new DownsampledDualLayerSquare("DYADIC_TEST", 32);
    shape.setExplicitStride(16);

    double domain = 256.0;
    for (int i = 0; i < 300; i++) {
      double s = shape.sample(domain);
      assertTrue(s >= 0.0 && s < domain, "Sample out of bounds: " + s);
    }
  }

  @Test
  @DisplayName("sample with autoDeriveStrideFromRadius=false falls back to deriveAdaptiveStride")
  public void testSampleAdaptiveFallback() {
    DownsampledDualLayerSquare shape = new DownsampledDualLayerSquare("ADAPTIVE_TEST", 32);
    shape.setAutoDeriveStrideFromRadius(false);
    shape.setExplicitStride(0);

    double domain = 1024.0;
    for (int i = 0; i < 100; i++) {
      double s = shape.sample(domain);
      assertTrue(s >= 0.0 && s < domain, "Sample out of bounds: " + s);
    }
  }

  @Test
  @DisplayName("select integrates with DownsampledDualLayerSquare end-to-end")
  public void testSelectEndToEnd() {
    DownsampledDualLayerSquare shape = new DownsampledDualLayerSquare("SELECT_TEST", 32);
    shape.set(GenericMemoryShapeParams.radius, 512L);
    shape.set(GenericMemoryShapeParams.centerRadius, 64L);
    shape.set(GenericMemoryShapeParams.uniquePlacements, 8); // stride = 64

    for (int i = 0; i < 50; i++) {
      int[] res = shape.select();
      assertNotNull(res);
      assertEquals(2, res.length);
      int chebyshev = Math.max(Math.abs(res[0]), Math.abs(res[1]));
      assertTrue(chebyshev >= 64 && chebyshev <= 512, "Selected coords outside radius: " + res[0] + ", " + res[1]);
    }
  }
}
