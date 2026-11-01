package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.AbstractVerticalAdjustor.FloorReader;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** REQ-RTP-F-015 / ADR-119: pure landing-spot slope check {@link AbstractVerticalAdjustor#slopeOk}. */
@DisplayName("REQ-RTP-F-015 landing-spot slope check")
class ReqRtpSlopeLandingTest {

  private static final int MIN = -64;
  private static final int MAX = 319;

  /** Solid blocks as a set of (x, y, z); everything else is air. */
  private static final class Grid {
    final Set<Long> solid = new HashSet<>();
    int reads;

    static long key(int x, int y, int z) {
      return ((long) x << 40) ^ ((long) (y + 1024) << 20) ^ z;
    }

    Grid column(int x, int z, int fromY, int toY) {
      for (int y = fromY; y <= toY; y++) solid.add(key(x, y, z));
      return this;
    }

    Grid fill(int fromY, int toY) {
      for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) column(x, z, fromY, toY);
      return this;
    }

    Grid clear(int x, int z) {
      solid.removeIf(k -> (k >> 40) == x && (k & 0xFFFFF) == z);
      return this;
    }

    boolean air(int x, int y, int z) {
      reads++;
      return !solid.contains(key(x, y, z));
    }

    FloorReader reader() {
      return (x, y, z) -> !air(x, y - 1, z) && air(x, y, z);
    }
  }

  private static boolean ok(Grid g, int lx, int y, int lz, int radius, int maxSlope) {
    return AbstractVerticalAdjustor.slopeOk(g.reader(), lx, y, lz, radius, maxSlope, MIN, MAX);
  }

  @Test
  @DisplayName("flat terrain passes at maxSlope 0")
  void flatPasses() {
    Grid g = new Grid().fill(0, 63);
    assertTrue(ok(g, 7, 64, 7, 2, 0));
  }

  @Test
  @DisplayName("a step of exactly maxSlope passes")
  void stepAtLimitPasses() {
    Grid g = new Grid().fill(0, 63);
    g.clear(9, 7).column(9, 7, 0, 65); // floor at 66 two blocks east: delta 2
    assertTrue(ok(g, 7, 64, 7, 2, 2));
  }

  @Test
  @DisplayName("a step of maxSlope + 1 fails, up or down")
  void stepOverLimitFails() {
    Grid up = new Grid().fill(0, 63);
    up.clear(9, 7).column(9, 7, 0, 66); // floor 67: delta 3
    assertFalse(ok(up, 7, 64, 7, 2, 2));

    Grid down = new Grid().fill(0, 63);
    down.clear(7, 5).column(7, 5, 0, 60); // floor 61 two blocks north: delta 3
    assertFalse(ok(down, 7, 64, 7, 2, 2));
  }

  @Test
  @DisplayName("a missing floor (cliff into void) fails")
  void cliffFails() {
    Grid g = new Grid().fill(0, 63);
    g.clear(5, 7); // west sample column is all air
    assertFalse(ok(g, 7, 64, 7, 2, 5));
  }

  @Test
  @DisplayName("chunk edge: a side clamped to distance 0 is skipped, others clamp to [0,15]")
  void chunkEdgeClamp() {
    Grid g = new Grid().fill(0, 63);
    // At (0,0) the west and north samples clamp to the candidate column itself and are skipped,
    // so a void just outside the chunk cannot be seen; (2,0) and (0,2) are flat.
    assertTrue(ok(g, 0, 64, 0, 2, 0));
    // At (14,7) radius 4 clamps east to x=15; a step there is still detected.
    g.clear(15, 7).column(15, 7, 0, 70);
    assertFalse(ok(g, 14, 64, 7, 4, 2));
  }

  @Test
  @DisplayName("cave layer: a floor or ceiling outside y +- maxSlope is ignored")
  void caveLayerIgnoresOtherLayers() {
    // Nether-like stack: floor at y=40, roof from 48 up, upper surface at 100.
    Grid g = new Grid().fill(0, 39).fill(48, 99);
    assertTrue(ok(g, 7, 40, 7, 2, 1), "cave floor is flat; roof and surface ignored");

    // Neighbour column drops to a lower cave floor at 30: no floor within 40 +- 2.
    g.clear(9, 7).column(9, 7, 0, 29).column(9, 7, 48, 99);
    assertFalse(ok(g, 7, 40, 7, 2, 2));
  }

  @Test
  @DisplayName("reads are bounded by 4 * (2 * maxSlope + 1) floor tests")
  void readsBounded() {
    Grid g = new Grid(); // all air: every sample searches the full window and fails at the first
    int maxSlope = 3;
    assertFalse(ok(g, 7, 64, 7, 2, maxSlope));
    assertTrue(g.reads <= 2 * (2 * maxSlope + 1), "first failing side stops the check");

    Grid flat = new Grid().fill(0, 63);
    flat.reads = 0;
    assertTrue(ok(flat, 7, 64, 7, 2, maxSlope));
    assertEquals(8, flat.reads, "flat: one floor test (two reads) per side");
  }
}
