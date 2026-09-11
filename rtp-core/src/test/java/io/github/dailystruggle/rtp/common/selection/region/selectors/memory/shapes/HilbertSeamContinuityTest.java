package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Measures key locality of the spiral-addressed Hilbert curve: Chebyshev distance between the
 * chunk coordinates of consecutive locations. Ring corners are the seams of interest.
 */
public class HilbertSeamContinuityTest {

  @Test
  @DisplayName("Spiral-Hilbert seam continuity and strict bijection for pointEdgeChunks 8")
  public void testSeamContinuityP8() {
    measure(8);
  }

  @Test
  @DisplayName("Spiral-Hilbert seam continuity and strict bijection for pointEdgeChunks 16")
  public void testSeamContinuityP16() {
    measure(16);
  }

  private void measure(int pointEdgeChunks) {
    SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("SEAM", pointEdgeChunks);
    shape.set(GenericMemoryShapeParams.radius, 256L);
    shape.set(GenericMemoryShapeParams.centerRadius, 64L);
    shape.set(GenericMemoryShapeParams.expand, false);

    long range = shape.getRange();
    assertTrue(range > 0, "range must be positive");

    Set<Long> seen = new HashSet<>((int) (range * 2));
    long sum = 0L;
    long max = 0L;
    long jumps = 0L;
    int prevX = 0;
    int prevZ = 0;

    for (long loc = 0; loc < range; loc++) {
      int[] xz = shape.locationToXZ(loc);
      long packed = (((long) xz[0]) << 32) ^ (xz[1] & 0xFFFFFFFFL);
      assertTrue(seen.add(packed), "duplicate chunk for location " + loc);
      assertEquals(loc, shape.xzToLocation(xz[0], xz[1]), "round trip failed for location " + loc);

      if (loc > 0) {
        long d = Math.max(Math.abs(xz[0] - prevX), Math.abs(xz[1] - prevZ));
        sum += d;
        if (d > max) max = d;
        if (d > 1) jumps++;
      }
      prevX = xz[0];
      prevZ = xz[1];
    }

    double mean = (double) sum / (double) (range - 1);
    System.out.println(
        "[DEBUG_LOG] p="
            + pointEdgeChunks
            + " range="
            + range
            + " meanDistance="
            + mean
            + " maxDistance="
            + max
            + " stepsOverOne="
            + jumps);
  }
}
