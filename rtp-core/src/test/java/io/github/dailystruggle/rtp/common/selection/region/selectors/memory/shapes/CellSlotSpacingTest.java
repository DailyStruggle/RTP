package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REROLL cell-slot spacing (ADR-088 section 10) on the 16,384-block test-server circle
 * (P=32, S=1024, uniquePlacements and spatialResolution "auto", expand off).
 *
 * <p>Every pick sits >= 2 chunks inside an aligned 8x8-chunk cell, and no cell repeats within a
 * pass, so any two picks of a pass are >= 5 chunks apart on some axis: more than 64 blocks, hence
 * zero pairs within 48 blocks over the whole run, not only back-to-back.
 */
class CellSlotSpacingTest {

  private static final long RADIUS = 1024L;
  private static final long CENTER_RADIUS = 64L;
  private static final int LANDINGS = 4096;
  /** Draws the FLAT_STRIDE backlog takes up front on the test server (backlogCacheCap). */
  private static final int BACKLOG_HEAD_START = 20_000;
  private static final long[] SEEDS = {1L, 2L, 3L, 4L};

  /** Circle that records every raw key {@code sample()} hands to {@code resolve()}. */
  private static final class RecordingCircle extends CircleOptimizedDualLayer {
    final List<Long> drawn = new ArrayList<>();

    RecordingCircle() {
      super("CIRCLE");
    }

    @Override
    protected double sample(double range) {
      double v = super.sample(range);
      drawn.add((long) v);
      return v;
    }
  }

  private static RecordingCircle circle(long seed, String mode) {
    RecordingCircle c = new RecordingCircle();
    c.set(GenericMemoryShapeParams.radius, RADIUS);
    c.set(GenericMemoryShapeParams.centerRadius, CENTER_RADIUS);
    c.set(GenericMemoryShapeParams.mode, mode);
    c.set(GenericMemoryShapeParams.expand, false);
    c.set(GenericMemoryShapeParams.uniquePlacements, "auto");
    c.setSpatialResolution(c.resolveSpatialResolution("auto"));
    c.setRng(new Random(seed));
    return c;
  }

  /** Blocky synthetic terrain: about 40% of 8x8-chunk cells are unsafe (ocean-like patches). */
  private static boolean terrainBad(int cx, int cz) {
    long h = (long) (cx >> 3) * 0x9E3779B97F4A7C15L ^ (long) (cz >> 3) * 0xC2B2AE3D27D4EB4FL;
    h ^= h >>> 29;
    h *= 0xBF58476D1CE4E5B9L;
    h ^= h >>> 32;
    return Math.floorMod(h, 100L) < 40L;
  }

  /** Smallest Chebyshev chunk distance between any two points, via an 8-chunk grid. */
  private static long minChebyshev(List<int[]> pts) {
    Map<Long, List<int[]>> grid = new HashMap<>();
    long best = Long.MAX_VALUE;
    for (int[] p : pts) {
      int gx = Math.floorDiv(p[0], 8);
      int gz = Math.floorDiv(p[1], 8);
      for (int ox = -1; ox <= 1; ox++) {
        for (int oz = -1; oz <= 1; oz++) {
          List<int[]> bucket = grid.get(((long) (gx + ox) << 32) ^ ((gz + oz) & 0xFFFFFFFFL));
          if (bucket == null) continue;
          for (int[] q : bucket) {
            best = Math.min(best, Math.max(Math.abs(p[0] - q[0]), Math.abs(p[1] - q[1])));
          }
        }
      }
      grid.computeIfAbsent(((long) gx << 32) ^ (gz & 0xFFFFFFFFL), k -> new ArrayList<>()).add(p);
    }
    return best;
  }

  /** Pairs whose closest blocks are within {@code blocks}: chunk gap g on an axis leaves (g-1)*16+1 blocks. */
  private static int pairsWithinBlocks(List<int[]> pts, int blocks) {
    int count = 0;
    for (int i = 0; i < pts.size(); i++) {
      for (int j = i + 1; j < pts.size(); j++) {
        int[] a = pts.get(i);
        int[] b = pts.get(j);
        long gx = Math.max(0, Math.abs(a[0] - b[0]) * 16L - 15L);
        long gz = Math.max(0, Math.abs(a[1] - b[1]) * 16L - 15L);
        if (gx * gx + gz * gz <= (long) blocks * blocks) count++;
      }
    }
    return count;
  }

  private static boolean inRing(int[] p) {
    long d = (long) p[0] * p[0] + (long) p[1] * p[1];
    return d >= CENTER_RADIUS * CENTER_RADIUS && d <= RADIUS * RADIUS;
  }

  @Test
  @DisplayName("ADR-088: 16 in-cell offsets sit >= 2 chunks inside their 8x8 cell under every bin orientation")
  void insetLanesStayInsideCellsEverywhere() {
    assertEquals(16, AbstractDualLayerShape.SPACING_INSET_LANES.length);
    RecordingCircle c = circle(1L, "REROLL");
    long range = c.getRange();
    int stride = c.deriveEffectiveStride(range);
    assertEquals(32, c.getPointEdgeChunks());
    assertEquals(1024, stride);
    Set<Integer> orientations = new HashSet<>();
    for (long base = 0; base < range; base += AbstractDualLayerShape.SPACING_CELL_KEYS) {
      for (int lane : AbstractDualLayerShape.SPACING_INSET_LANES) {
        int[] p = c.locationToXZ(base + lane);
        int lx = Math.floorMod(p[0], 8);
        int lz = Math.floorMod(p[1], 8);
        assertTrue(lx >= 2 && lx <= 5 && lz >= 2 && lz <= 5,
            "key " + (base + lane) + " -> " + p[0] + "," + p[1] + " is not inset");
      }
      orientations.add(MemoryShape.orientationFor(Math.floorDiv(c.locationToXZ(base)[0], 32),
          Math.floorDiv(c.locationToXZ(base)[1], 32)));
    }
    assertTrue(orientations.size() > 1, "scan must cover re-oriented bins");
  }

  @Test
  @DisplayName("REQ-RTP-F-007 / ADR-088: REROLL 4,096 landings after a 20,000-draw backlog have 0 pairs within 48 blocks")
  void rerollRunHasNoPairsWithin48Blocks() {
    for (long seed : SEEDS) {
      RecordingCircle c = circle(seed, "REROLL");
      long range = c.getRange();
      // FLAT_STRIDE: the backlog and live searches draw from the same select() stream.
      for (int i = 0; i < BACKLOG_HEAD_START; i++) c.rand();
      List<int[]> landings = new ArrayList<>();
      int lag1 = 0;
      int[] prev = null;
      while (landings.size() < LANDINGS) {
        long key = c.rand();
        if (key < 0) continue;
        int[] p = c.locationToXZ(key);
        if (terrainBad(p[0], p[1])) {
          c.addBadLocation(key, LocationGenerator.FailTypes.safety, 0L);
          continue;
        }
        if (prev != null) {
          long dx = p[0] - prev[0];
          long dz = p[1] - prev[1];
          if (dx * dx + dz * dz < 256L) lag1++;
        }
        prev = p;
        landings.add(p);
      }

      Set<Long> cells = new HashSet<>();
      for (long k : c.drawn) {
        assertTrue(cells.add(k >>> AbstractDualLayerShape.SPACING_CELL_SHIFT), "cell repeated within a pass: key " + k);
      }
      assertTrue(c.drawn.size() < range / AbstractDualLayerShape.SPACING_CELL_KEYS, "run must fit in one pass");

      long minCheb = minChebyshev(landings);
      int within48 = pairsWithinBlocks(landings, 48);
      System.out.printf("[DEBUG_LOG] seed=%d draws=%d pass=%d minChebyshev=%d chunks within48=%d lag1<256b=%d%n",
          seed, c.drawn.size(), range / AbstractDualLayerShape.SPACING_CELL_KEYS, minCheb, within48, lag1);
      assertTrue(minCheb >= 5, "landings closer than 5 chunks: " + minCheb);
      assertEquals(0, within48);
      assertTrue(lag1 <= 2, "window lattice should keep back-to-back landings apart, was " + lag1);
    }
  }

  @Test
  @DisplayName("REQ-RTP-F-007: cell-slot spacing keeps the landing spread even by area")
  void cellSlotSpacingKeepsAreaUniformity() {
    RecordingCircle c = circle(7L, "REROLL");
    int rings = 4;
    int[] counts = new int[rings];
    int n = 0;
    double inner = CENTER_RADIUS * CENTER_RADIUS;
    double outer = RADIUS * RADIUS;
    while (n < 20_000) {
      long key = c.rand();
      if (key < 0) continue;
      int[] p = c.locationToXZ(key);
      if (!inRing(p)) continue;
      double f = ((double) p[0] * p[0] + (double) p[1] * p[1] - inner) / (outer - inner);
      counts[Math.min(rings - 1, (int) (f * rings))]++;
      n++;
    }
    for (int r = 0; r < rings; r++) {
      double share = (double) counts[r] / n;
      System.out.printf("[DEBUG_LOG] equal-area ring %d share=%.4f%n", r, share);
      assertEquals(0.25, share, 0.02, "ring " + r);
    }
  }

  @Test
  @DisplayName("ADR-088: BINNED_AMORTIZED REROLL draws keep distinct cells and the 5-chunk floor")
  void binnedRerollKeepsCellFloor() {
    RecordingCircle c = circle(3L, "REROLL");
    List<int[]> pts = new ArrayList<>();
    Set<Long> cells = new HashSet<>();
    for (int i = 0; i < 8192; i++) {
      long key = c.selectL3Candidate();
      if (key < 0) continue;
      assertTrue(cells.add(key >>> AbstractDualLayerShape.SPACING_CELL_SHIFT), "cell repeated: key " + key);
      int[] p = c.locationToXZ(key);
      if (inRing(p)) pts.add(p);
    }
    long minCheb = minChebyshev(pts);
    System.out.printf("[DEBUG_LOG] binned draws=%d minChebyshev=%d%n", pts.size(), minCheb);
    assertTrue(minCheb >= 5, "binned draws closer than 5 chunks: " + minCheb);
  }

  @Test
  @DisplayName("ADR-088: REROLL SQUARE draws keep distinct cells and the 5-chunk floor")
  void squareRerollKeepsCellFloor() {
    SquareOptimizedDualLayer s = new SquareOptimizedDualLayer("SQUARE");
    s.set(GenericMemoryShapeParams.radius, RADIUS);
    s.set(GenericMemoryShapeParams.centerRadius, CENTER_RADIUS);
    s.set(GenericMemoryShapeParams.mode, "REROLL");
    s.set(GenericMemoryShapeParams.expand, false);
    s.set(GenericMemoryShapeParams.uniquePlacements, "auto");
    s.setSpatialResolution(s.resolveSpatialResolution("auto"));
    s.setRng(new Random(5L));
    assertTrue(s.cellSpacingApplies(s.deriveEffectiveStride(s.getRange()), s.getRange()));
    List<int[]> pts = new ArrayList<>();
    Set<Long> chunks = new HashSet<>();
    for (int i = 0; i < 30_000; i++) {
      long key = s.rand();
      if (key < 0) continue;
      int[] p = s.locationToXZ(key);
      assertTrue(chunks.add(((long) p[0] << 32) ^ (p[1] & 0xFFFFFFFFL)), "chunk repeated");
      pts.add(p);
    }
    long minCheb = minChebyshev(pts);
    System.out.printf("[DEBUG_LOG] square draws=%d minChebyshev=%d%n", pts.size(), minCheb);
    assertTrue(minCheb >= 5, "square draws closer than 5 chunks: " + minCheb);
  }

  @Test
  @DisplayName("ACCUMULATE keeps the per-lane stride path (cell-slot spacing needs a fixed key domain)")
  void accumulateIsUnchanged() {
    RecordingCircle c = circle(1L, "ACCUMULATE");
    assertTrue(!c.cellSpacingApplies(c.deriveEffectiveStride(c.getRange()), c.getRange()));
    RecordingCircle r = circle(1L, "REROLL");
    assertTrue(r.cellSpacingApplies(r.deriveEffectiveStride(r.getRange()), r.getRange()));
  }
}
