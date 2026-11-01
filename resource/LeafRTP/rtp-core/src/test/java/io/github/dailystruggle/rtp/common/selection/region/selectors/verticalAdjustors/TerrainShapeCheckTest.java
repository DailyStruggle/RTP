package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.TerrainShapeCheck.SquareReader;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.TerrainShapeCheck.Verdict;
import java.util.Arrays;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** REQ-RTP-F-015 / ADR-120: pure mountainside rule and plane fit {@link TerrainShapeCheck}. */
@DisplayName("REQ-RTP-F-015 terrain shape check over the 5x5 square")
class TerrainShapeCheckTest {

  private static final int MIN = -63;
  private static final int MAX = 319;
  private static final int Y = 64;
  private static final int NO_FLOOR = Integer.MIN_VALUE;
  private static final double TAN30 = TerrainShapeCheck.tanOf(30);
  private static final int SIDE = TerrainShapeCheck.SQUARE_SIDE;
  private static final int X0 = TerrainShapeCheck.CENTRE_ORIGIN + 7;
  private static final int Z0 = TerrainShapeCheck.CENTRE_ORIGIN + 7;

  /**
   * One floor per column (feet-Y {@code h}), or none. Fails the test when the check reads an
   * unreadable column, so clipping cannot leak.
   */
  private static class Square implements SquareReader {
    final int[] h = new int[SIDE * SIDE];
    IntBinaryOperator readableIf = (x, z) -> 1;
    long floorTests;

    Square(IntBinaryOperator height) {
      for (int z = 0; z < SIDE; z++) for (int x = 0; x < SIDE; x++) h[z * SIDE + x] = height.applyAsInt(x, z);
    }

    static Square flat() {
      return new Square((x, z) -> Y);
    }

    Square set(int x, int z, int floor) {
      h[z * SIDE + x] = floor;
      return this;
    }

    Square readableOnly(IntBinaryOperator pred) {
      readableIf = pred;
      return this;
    }

    @Override
    public boolean readable(int sx, int sz) {
      return sx >= 0 && sz >= 0 && sx < SIDE && sz < SIDE && readableIf.applyAsInt(sx, sz) != 0;
    }

    @Override
    public boolean isFloor(int sx, int feetY, int sz) {
      if (!readable(sx, sz)) throw new AssertionError("read unreadable column " + sx + "," + sz);
      floorTests++;
      return h[sz * SIDE + sx] == feetY;
    }
  }

  private static Verdict eval(Square s) {
    return TerrainShapeCheck.evaluate(s, 7, 7, Y, TAN30, MIN, MAX);
  }

  private static Verdict rule(Square s) {
    return TerrainShapeCheck.rule(s, 7, 7, Y, TAN30, MIN, MAX);
  }

  /** Raises the 16-block sample of direction (dx, dz) by {@code rise}; the plane fit never sees it. */
  private static Square raise(Square s, int dx, int dz, int rise) {
    return s.set(X0 + 16 * dx, Z0 + 16 * dz, Y + rise);
  }

  @Test
  @DisplayName("flat ground accepts with all 8 directions measured and zero grade")
  void flatAccepts() {
    Verdict v = eval(Square.flat());
    assertEquals(0, v.steep());
    assertTrue(v.full());
    assertTrue(v.planeKnown());
    assertEquals(0.0, v.grade(), 1e-9);
    assertFalse(v.reject(TAN30));
  }

  @Test
  @DisplayName("a 45 deg hillside rejects through the rule (6 of 8 steep)")
  void hillsideRejectsByRule() {
    Square s = new Square((x, z) -> Y + (x - X0));
    Verdict r = rule(s);
    assertEquals(6, r.steep(), "E, W and the four diagonals rise 16 per 16-22.6 blocks");
    assertTrue(r.ruleRejects());
    Verdict v = eval(s);
    assertTrue(v.planeKnown(), "staged check fits the plane first");
    assertTrue(v.reject(TAN30));
  }

  @Test
  @DisplayName("staged evaluate matches the eager rule + plane on random terrain at 30, 45 and 60 deg")
  void stagedMatchesEager() {
    java.util.Random rnd = new java.util.Random(120);
    for (int trial = 0; trial < 3000; trial++) {
      double ax = rnd.nextGaussian() * 0.6, az = rnd.nextGaussian() * 0.6;
      int noise = rnd.nextInt(6);
      long seed = rnd.nextLong();
      Square s = new Square((x, z) -> {
        long h = (seed ^ (x * 73856093L) ^ (z * 19349663L)) * 0x9E3779B97F4A7C15L;
        return Y + (int) Math.round(ax * (x - X0) + az * (z - Z0)) + (int) Math.floorMod(h >>> 40, noise + 1);
      });
      int cut = rnd.nextInt(4);
      int lo = TerrainShapeCheck.CENTRE_ORIGIN - 16, hi = TerrainShapeCheck.CENTRE_ORIGIN + 32;
      if (cut == 1) s.readableOnly((x, z) -> x < hi ? 1 : 0);
      if (cut == 2) s.readableOnly((x, z) -> x >= lo && z < hi ? 1 : 0);
      if (cut == 3) s.readableOnly((x, z) -> (x >> 4) == 4 && (z >> 4) == 2 ? 0 : 1);
      int lx = rnd.nextInt(16), lz = rnd.nextInt(16);
      for (double deg : new double[] {30, 45, 60}) {
        double tan = TerrainShapeCheck.tanOf(deg);
        Verdict rule = TerrainShapeCheck.rule(s, lx, lz, Y, tan, MIN, MAX);
        double g = TerrainShapeCheck.planeGrade(s, lx, lz, Y, MIN, MAX);
        boolean eager = rule.ruleRejects() || (!Double.isNaN(g) && g >= tan);
        Verdict staged = TerrainShapeCheck.evaluate(s, lx, lz, Y, tan, MIN, MAX);
        assertEquals(eager, staged.reject(tan), "trial " + trial + " at " + deg + " deg");
        assertEquals(rule.measured(), staged.measured());
        assertTrue(staged.steep() <= rule.steep());
      }
    }
  }

  @Test
  @DisplayName("staged evaluate stops before the 32-block ring once the 3x3 decides")
  void stagedSkipsOuterRing() {
    int lo = TerrainShapeCheck.CENTRE_ORIGIN - 16, hi = TerrainShapeCheck.CENTRE_ORIGIN + 32;
    // Four steep 16-block samples decide the rule: no 32-block sample may be read.
    Square s = Square.flat().readableOnly((x, z) -> 1);
    raise(s, 1, 0, 30);
    raise(s, 0, 1, 30);
    raise(s, -1, 0, 30);
    raise(s, 0, -1, 30);
    Square guarded = new Square((x, z) -> s.h[z * SIDE + x]) {
      @Override
      public boolean isFloor(int sx, int feetY, int sz) {
        if (sx < lo || sz < lo || sx >= hi || sz >= hi) throw new AssertionError("read outer ring " + sx + "," + sz);
        return super.isFloor(sx, feetY, sz);
      }
    };
    Verdict v = eval(guarded);
    assertTrue(v.ruleRejects());
    assertEquals(8, v.measured());
  }

  @Test
  @DisplayName("a 35 deg slope along one axis passes the rule but the plane fit rejects")
  void axisSlopeRejectsByPlane() {
    double t = Math.tan(Math.toRadians(35));
    Square s = new Square((x, z) -> Y + (int) Math.round(t * (x - X0)));
    Verdict r = rule(s);
    assertEquals(2, r.steep(), "only E and W are steep");
    assertFalse(r.ruleRejects());
    Verdict v = eval(s);
    assertTrue(v.planeKnown());
    assertEquals(t, v.grade(), 0.05);
    assertTrue(v.reject(TAN30));
    assertFalse(v.reject(TerrainShapeCheck.tanOf(40)), "a laxer limit accepts the same slope");
  }

  @Test
  @DisplayName("a 10-block cliff 4 blocks away rejects through the plane fit")
  void cliffRejectsByPlane() {
    Verdict v = eval(new Square((x, z) -> x >= X0 + 4 ? Y + 10 : Y));
    assertFalse(v.ruleRejects(), "only one direction sees the step at 16 blocks");
    assertEquals(0.75, v.grade(), 1e-6);
    assertTrue(v.reject(TAN30));
  }

  @Test
  @DisplayName("a floorless sample counts steep; over a quarter floorless plane points is a layer break")
  void missingFloorsAreSteep() {
    Square s = Square.flat().set(X0 + 16, Z0, NO_FLOOR);
    assertEquals(1, rule(s).steep());

    Square holes = new Square((x, z) -> x < X0 - 2 ? NO_FLOOR : Y);
    double g = TerrainShapeCheck.planeGrade(holes, 7, 7, Y, MIN, MAX);
    assertEquals(Double.POSITIVE_INFINITY, g, "3 of 9 plane columns floorless");
  }

  @Test
  @DisplayName("mutation: one sample block decides 3 vs 4 steep of 8")
  void singleBlockFlipsVerdict() {
    Square s = Square.flat();
    raise(s, 1, 0, 10);
    raise(s, 0, 1, 10);
    raise(s, -1, 0, 10);
    Verdict three = eval(s);
    assertEquals(3, three.steep());
    assertFalse(three.reject(TAN30));
    raise(s, 0, -1, 10);
    Verdict four = eval(s);
    assertEquals(4, four.steep());
    assertTrue(four.reject(TAN30));
    // Threshold boundary: rise 9 is below tan30 * 16 = 9.24, so the 4th direction is not steep.
    raise(s, 0, -1, 9);
    assertEquals(3, eval(s).steep());
  }

  @Test
  @DisplayName("32-block sample is used when the 16-block one is level")
  void farSampleCounts() {
    Square s = Square.flat();
    s.set(X0 + 32, Z0, Y + 19); // 19 >= tan30 * 32 = 18.5
    assertEquals(1, rule(s).steep());
    s.set(X0 + 32, Z0, Y + 18);
    assertEquals(0, rule(s).steep());
  }

  @Test
  @DisplayName("region edge at the centre chunk measures 5 of 8; corner 3; one chunk in all 8")
  void clipping() {
    int east = TerrainShapeCheck.CENTRE_ORIGIN + 16;
    Square edge = Square.flat().readableOnly((x, z) -> x < east ? 1 : 0);
    assertEquals(5, rule(edge).measured());
    assertFalse(eval(edge).full());

    Square corner = Square.flat().readableOnly((x, z) -> x < east && z < east ? 1 : 0);
    assertEquals(3, rule(corner).measured());

    Square near = Square.flat().readableOnly((x, z) -> x < east + 16 ? 1 : 0);
    assertEquals(8, rule(near).measured(), "32-block samples fall back to the 16-block ones");
    raise(near, 1, 0, 10);
    assertEquals(1, rule(near).steep(), "16-block sample still decides the clipped direction");
  }

  @Test
  @DisplayName("scaled threshold: steep >= half of measured directions")
  void scaledThreshold() {
    int east = TerrainShapeCheck.CENTRE_ORIGIN + 16;
    Square s = Square.flat().readableOnly((x, z) -> x < east ? 1 : 0);
    raise(s, -1, 0, 10);
    raise(s, 0, 1, 10);
    Verdict two = rule(s);
    assertEquals(5, two.measured());
    assertFalse(two.ruleRejects(), "2 of 5");
    raise(s, 0, -1, 10);
    assertTrue(rule(s).ruleRejects(), "3 of 5");
  }

  @Test
  @DisplayName("nothing readable around the centre: rule and plane abstain, landing accepted")
  void abstainsWithoutData() {
    int lo = TerrainShapeCheck.CENTRE_ORIGIN, hi = lo + 16;
    Square s = new Square((x, z) -> Y + 3 * x).readableOnly((x, z) -> x >= lo && x < hi && z >= lo && z < hi ? 1 : 0);
    Verdict v = TerrainShapeCheck.evaluate(s, 0, 0, Y, TAN30, MIN, MAX);
    assertEquals(0, v.measured());
    assertFalse(v.ruleRejects());
    assertFalse(v.planeKnown(), "25 of 81 plane points readable");
    assertTrue(Double.isNaN(v.grade()));
    assertFalse(v.reject(TAN30));
  }

  @Test
  @DisplayName("floor search follows the landing layer and ignores floors outside the window")
  void layerFollowing() {
    Square s = Square.flat();
    assertFalse(eval(s).reject(TAN30));
    int[] level = Arrays.copyOf(s.h, s.h.length);
    // Every neighbour floor just beyond the window: none is found, so all directions are steep.
    Arrays.fill(s.h, Y + TerrainShapeCheck.LAYER_WINDOW + 1);
    s.set(X0, Z0, Y);
    assertEquals(8, rule(s).steep(), "no floor within the window counts steep");
    System.arraycopy(level, 0, s.h, 0, level.length);
    assertEquals(0, rule(s).steep());
  }

  @Test
  @DisplayName("floor tests are bounded by (16 + 81) searches of 2 * window + 1")
  void readsBounded() {
    Square none = new Square((x, z) -> NO_FLOOR);
    TerrainShapeCheck.planeGrade(none, 7, 7, Y, MIN, MAX);
    TerrainShapeCheck.rule(none, 7, 7, Y, TAN30, MIN, MAX);
    long bound = (16 + 81) * (2L * TerrainShapeCheck.LAYER_WINDOW + 1);
    assertTrue(none.floorTests <= bound, none.floorTests + " > " + bound);
  }
}
