package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.util.ArrayDeque;
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
 * Consecutive-landing spacing on the shipped default circle (1,024 to 16,384 blocks, ACCUMULATE,
 * expand off, uniquePlacements and spatialResolution "auto", FLAT_STRIDE backlog), against a
 * uniform scatter over the same ring and the same synthetic terrain.
 *
 * <p>Metrics, all in teleport order: {@code near64} = landings within 256 blocks of one of the
 * previous 64 (the stress-run chart metric); {@code lag1} = back-to-back pairs under 256 blocks
 * (the FRONT_PAGE "consecutive spots" wording); {@code dups} = landings on an already-used chunk;
 * {@code lane} = share of back-to-back keys in the same stride residue (diagnostic only: the
 * ACCUMULATE remap shifts residues without moving keys far, so it is stricter than spacing).
 *
 * <p>Stride lanes (ADR-088): only a full-bin stride S=P^2 makes a lane one key per tile, a
 * P-chunk lattice that breaks only where the spiral re-orients tiles at ring corners (share
 * ~1/rings). Any sub-bin stride lands keys in Hilbert sub-squares of differing reflection and
 * breaks the floor inside tiles. The derived stride is capped at P^2, so the raw draw stream
 * keeps back-to-back spacing.
 *
 * <p>ACCUMULATE shifts each sampled good-index by the rejections before it. Two nearby picks
 * differ only by the rejections learned between them, a few Hilbert steps, so back-to-back
 * spacing survives terrain rejection; FAST and COLD serves share one draw stream, so mixing
 * them adds no repeats. The 64-lookback metric is not a lane promise: windows of 16-64 draws
 * change lane, and keys of different lanes fall at random relative to each other.
 *
 * <p>Known limit pinned here: every merged rejection renumbers good indices, so with
 * {@code expand: false} (landings unmarked) used chunks repeat at about the uniform rate.
 * Marking landings there is not a fix: without expand the range never grows back, so the marks
 * drain the good domain until searches stop finding locations ({@code MemoryShape.resolve}).
 */
class ConsecutiveLandingSpacingSimTest {

  private static final long RADIUS = 1024L;
  private static final long CENTER_RADIUS = 64L;
  private static final int LANDINGS = 4096;
  private static final int LOOKBACK = 64;
  private static final int BACKLOG_CAP = 1000;
  /** 256 blocks = 16 chunks, compared squared in chunk units. */
  private static final long NEAR_CHUNKS_SQ = 16L * 16L;
  private static final long[] SEEDS = {1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L};

  // --- fixtures ---

  private static CircleOptimizedDualLayer shippedDefaultCircle(long seed) {
    return circle(seed, RADIUS, 0);
  }

  /** Shipped defaults at {@code radius} chunks; {@code forcedStride > 0} replaces the derived stride. */
  private static CircleOptimizedDualLayer circle(long seed, long radius, int forcedStride) {
    CircleOptimizedDualLayer c = forcedStride <= 0
        ? new CircleOptimizedDualLayer("CIRCLE")
        : new CircleOptimizedDualLayer("CIRCLE") {
          @Override
          public int deriveEffectiveStride(long domainSize) {
            return forcedStride;
          }
        };
    c.set(GenericMemoryShapeParams.radius, radius);
    c.set(GenericMemoryShapeParams.centerRadius, CENTER_RADIUS);
    c.set(GenericMemoryShapeParams.mode, "ACCUMULATE");
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

  private static boolean inRing(int[] p) {
    return inRing(p, RADIUS);
  }

  private static boolean inRing(int[] p, long radius) {
    long d = (long) p[0] * p[0] + (long) p[1] * p[1];
    return d >= CENTER_RADIUS * CENTER_RADIUS && d <= radius * radius;
  }

  // --- metrics ---

  private static final class Stats {
    double near64;
    double lag1;
    double dups;
    double lane;
    double nearFast;
    double nearCold;
    long fast;
    long cold;
    int runs;

    Stats add(List<int[]> seq, List<Long> keys, int stride, boolean[] fromCold) {
      runs++;
      Set<Long> used = new HashSet<>();
      int laneSame = 0;
      for (int i = 0; i < seq.size(); i++) {
        int[] a = seq.get(i);
        if (!used.add(((long) a[0] << 32) ^ (a[1] & 0xFFFFFFFFL))) dups++;
        if (i == 0) continue;
        if (keys != null && Math.floorMod(keys.get(i), stride) == Math.floorMod(keys.get(i - 1), stride)) {
          laneSame++;
        }
        if (distSq(a, seq.get(i - 1)) < NEAR_CHUNKS_SQ) lag1++;
        for (int j = Math.max(0, i - LOOKBACK); j < i; j++) {
          if (distSq(a, seq.get(j)) < NEAR_CHUNKS_SQ) {
            near64++;
            if (fromCold != null) {
              if (fromCold[i]) nearCold++;
              else nearFast++;
            }
            break;
          }
        }
      }
      if (keys != null && seq.size() > 1) lane += (double) laneSame / (seq.size() - 1);
      if (fromCold != null) {
        for (int i = 0; i < seq.size(); i++) {
          if (fromCold[i]) cold++;
          else fast++;
        }
      }
      return this;
    }

    double mean(double v) {
      return v / runs;
    }

    @Override
    public String toString() {
      return String.format(
          "near64=%.1f lag1=%.2f dups=%.2f lane=%.3f (per run of %d, %d runs)",
          mean(near64), mean(lag1), mean(dups), mean(lane), LANDINGS, runs);
    }
  }

  private static long distSq(int[] a, int[] b) {
    long dx = a[0] - b[0];
    long dz = a[1] - b[1];
    return dx * dx + dz * dz;
  }

  private static Stats uniformBaseline(boolean withTerrain) {
    Stats st = new Stats();
    for (long s : SEEDS) {
      Random r = new Random(s * 0x5DEECE66DL + 11L);
      List<int[]> seq = new ArrayList<>(LANDINGS);
      while (seq.size() < LANDINGS) {
        int[] p = {
          (int) (r.nextInt((int) (2 * RADIUS + 1)) - RADIUS),
          (int) (r.nextInt((int) (2 * RADIUS + 1)) - RADIUS)
        };
        if (!inRing(p) || (withTerrain && terrainBad(p[0], p[1]))) continue;
        seq.add(p);
      }
      st.add(seq, null, 1, null);
    }
    return st;
  }

  /** Live (COLD) search: draw until the terrain accepts; each reject is learned like the pipeline does. */
  private static long coldSearch(AbstractDualLayerShape s, boolean withTerrain) {
    for (int attempt = 0; attempt < 10_000; attempt++) {
      long key = s.rand();
      if (key < 0) continue;
      int[] c = s.locationToXZ(key);
      if (withTerrain && terrainBad(c[0], c[1])) {
        s.addBadLocation(key, LocationGenerator.FailTypes.safety, 0L);
        continue;
      }
      return key;
    }
    return -1L;
  }

  private static Stats liveStream(boolean withTerrain) {
    return liveStream(withTerrain, 0);
  }

  private static Stats liveStream(boolean withTerrain, int forcedStride) {
    Stats st = new Stats();
    for (long s : SEEDS) {
      CircleOptimizedDualLayer c = circle(s, RADIUS, forcedStride);
      int stride = c.deriveEffectiveStride(c.getRange());
      List<int[]> seq = new ArrayList<>();
      List<Long> keys = new ArrayList<>();
      while (seq.size() < LANDINGS) {
        long key = coldSearch(c, withTerrain);
        if (key < 0) break;
        keys.add(key);
        seq.add(c.locationToXZ(key));
      }
      st.add(seq, keys, stride, null);
    }
    return st;
  }

  /**
   * FLAT_STRIDE serve model: the backlog is refilled from the same {@code select()} stream as
   * live search and drained FIFO (Region.pollContiguousValidatedHead); the region-file prefilter
   * drops unsafe entries at verification. One refill draw per request leaves the backlog short
   * once its head start is spent, so later requests fall back to COLD searches.
   */
  private static Stats fastColdServe() {
    Stats st = new Stats();
    for (long s : SEEDS) {
      CircleOptimizedDualLayer c = shippedDefaultCircle(s);
      int stride = c.deriveEffectiveStride(c.getRange());
      ArrayDeque<Long> backlog = new ArrayDeque<>();
      for (int i = 0; i < BACKLOG_CAP; i++) {
        long key = c.rand();
        if (key >= 0) backlog.add(key);
      }
      List<int[]> seq = new ArrayList<>();
      List<Long> keys = new ArrayList<>();
      boolean[] fromCold = new boolean[LANDINGS];
      while (seq.size() < LANDINGS) {
        long refill = c.rand();
        if (refill >= 0 && backlog.size() < BACKLOG_CAP) backlog.add(refill);
        long key = -1L;
        while (!backlog.isEmpty()) {
          long k = backlog.poll();
          int[] p = c.locationToXZ(k);
          if (terrainBad(p[0], p[1])) {
            c.addBadLocation(k, LocationGenerator.FailTypes.safety, 0L);
            continue;
          }
          key = k;
          break;
        }
        if (key < 0) {
          key = coldSearch(c, true);
          if (key < 0) break;
          fromCold[seq.size()] = true;
        }
        keys.add(key);
        seq.add(c.locationToXZ(key));
      }
      st.add(seq, keys, stride, fromCold);
    }
    return st;
  }

  // --- tests ---

  @Test
  @DisplayName("Shipped default 1,024-16,384 block circle engages full-bin stride S=1024 on P=32 bins")
  void derivedParameters() {
    CircleOptimizedDualLayer c = shippedDefaultCircle(1L);
    long range = c.getRange();
    int stride = c.deriveEffectiveStride(range);
    System.out.printf("[DEBUG_LOG] P=%d res=%d range=%d stride=%d sqrt(S)=%.1f chunks%n",
        c.getPointEdgeChunks(), c.spatialResolution(), range, stride, Math.sqrt(stride));
    assertEquals(32, c.getPointEdgeChunks());
    assertEquals(1024, stride);
  }

  /** Smallest squared chunk distance between two in-ring keys of one stride lane. */
  private static long minSameLaneDistSq(AbstractDualLayerShape c, int phase, long range, int stride) {
    int cell = (int) Math.ceil(Math.sqrt(stride));
    Map<Long, List<int[]>> grid = new HashMap<>();
    long minSq = Long.MAX_VALUE;
    for (long key = phase; key < range; key += stride) {
      int[] p = c.locationToXZ(key);
      if (!inRing(p)) continue;
      int gx = Math.floorDiv(p[0], cell);
      int gz = Math.floorDiv(p[1], cell);
      for (int ox = -1; ox <= 1; ox++) {
        for (int oz = -1; oz <= 1; oz++) {
          List<int[]> bucket = grid.get(((long) (gx + ox) << 32) ^ ((gz + oz) & 0xFFFFFFFFL));
          if (bucket == null) continue;
          for (int[] q : bucket) minSq = Math.min(minSq, distSq(p, q));
        }
      }
      grid.computeIfAbsent(((long) gx << 32) ^ (gz & 0xFFFFFFFFL), k -> new ArrayList<>()).add(p);
    }
    return minSq;
  }

  @Test
  @DisplayName("Shipped S=1024 lanes: no lane holds two keys under 256 blocks apart; seams only dip below sqrt(S)")
  void shippedStrideLanesKeep256BlockFloor() {
    CircleOptimizedDualLayer c = shippedDefaultCircle(1L);
    long range = c.getRange();
    int stride = c.deriveEffectiveStride(range);
    int under256 = 0;
    int underSqrtS = 0;
    long worstSq = Long.MAX_VALUE;
    int worstPhase = -1;
    for (int phase = 0; phase < stride; phase++) {
      long minSq = minSameLaneDistSq(c, phase, range, stride);
      if (minSq < NEAR_CHUNKS_SQ) under256++;
      if (minSq < stride) underSqrtS++;
      if (minSq < worstSq) {
        worstSq = minSq;
        worstPhase = phase;
      }
    }
    long phase0 = minSameLaneDistSq(c, 0, range, stride);
    System.out.printf(
        "[DEBUG_LOG] lanes=%d under256Blocks=%d underSqrtS=%d worstPhase=%d worstDist=%.2f chunks phase0=%.2f%n",
        stride, under256, underSqrtS, worstPhase, Math.sqrt(worstSq), Math.sqrt(phase0));
    assertEquals(0, under256, "no lane may hold two in-ring keys under 256 blocks apart");
    assertTrue(worstSq >= NEAR_CHUNKS_SQ, "worst same-lane pair must stay at least 256 blocks apart");
  }

  @Test
  @DisplayName("Raw draw stream with no rejections keeps lane, never repeats and has no back-to-back pair under 256 blocks")
  void rawDrawStreamKeepsBackToBackSpacing() {
    Stats uniform = uniformBaseline(false);
    Stats leaf = liveStream(false);
    System.out.printf("[DEBUG_LOG] raw     leaf: %s%n[DEBUG_LOG] raw  uniform: %s%n", leaf, uniform);
    assertTrue(leaf.mean(leaf.lane) > 0.9, "phase window should hold across back-to-back raw draws");
    assertEquals(0.0, leaf.dups, "keyed shuffle must not repeat a chunk within a cycle");
    assertTrue(leaf.lag1 < 0.05 * uniform.lag1 + 0.1, "back-to-back landings under 256 blocks, was " + leaf.lag1);
  }

  @Test
  @DisplayName("ACCUMULATE with terrain rejection keeps back-to-back spacing; renumbering repeats used chunks (known limit)")
  void terrainRejectionKeepsBackToBackSpacing() {
    Stats uniform = uniformBaseline(true);
    Stats leaf = liveStream(true);
    System.out.printf("[DEBUG_LOG] terrain    leaf: %s%n[DEBUG_LOG] terrain uniform: %s%n", leaf, uniform);
    assertTrue(leaf.lag1 < 0.25 * uniform.lag1,
        "back-to-back pairs under 256 blocks should stay far below uniform, was " + leaf.lag1 + " vs " + uniform.lag1);
    assertTrue(leaf.near64 < uniform.near64, "64-lookback near repeats must not exceed uniform");
    // Known limit: flip to assertEquals(0.0, leaf.dups) only with a fix that consumes no good area.
    assertTrue(leaf.dups > 0, "renumbered good indices land on already-used chunks while expand is off");
  }

  @Test
  @DisplayName("FLAT_STRIDE FAST serves interleaved with COLD searches keep back-to-back spacing and add no repeats")
  void fastColdInterleaveKeepsBackToBackSpacing() {
    Stats uniform = uniformBaseline(true);
    Stats coldOnly = liveStream(true);
    Stats leaf = fastColdServe();
    System.out.printf(
        "[DEBUG_LOG] serve    leaf: %s fast=%d cold=%d nearFast=%.1f nearCold=%.1f%n"
            + "[DEBUG_LOG] cold-only:     %s%n[DEBUG_LOG] serve uniform: %s%n",
        leaf, leaf.fast, leaf.cold, leaf.mean(leaf.nearFast), leaf.mean(leaf.nearCold), coldOnly, uniform);
    assertTrue(leaf.cold > 0 && leaf.fast > 0, "model must mix FAST and COLD serves");
    assertTrue(leaf.lag1 < 0.25 * uniform.lag1,
        "back-to-back pairs under 256 blocks should stay far below uniform, was " + leaf.lag1 + " vs " + uniform.lag1);
    assertTrue(leaf.near64 < uniform.near64, "64-lookback near repeats must not exceed uniform");
    assertTrue(leaf.mean(leaf.dups) <= coldOnly.mean(coldOnly.dups) + 1.0,
        "one shared draw stream: mixing must not add repeats beyond the ACCUMULATE renumbering");
  }

  // --- dyadic stride lattice ---

  /** Same-lane floor violations over a sample of lanes, split by Hilbert tile orientation. */
  private static final class LaneScan {
    int edge;
    int stride;
    long rings;
    long keys;
    /** Keys with a same-lane neighbour under sqrt(S) chunks in a tile of the same orientation. */
    long interior;
    /** Keys whose every under-floor same-lane neighbour sits in a tile of another orientation. */
    long seam;
    long minSq = Long.MAX_VALUE;

    double interiorShare() {
      return (double) interior / keys;
    }

    double seamShare() {
      return (double) seam / keys;
    }

    @Override
    public String toString() {
      return String.format("P=%d S=%d rings=%d keys=%d interior=%d (%.4f%%) seam=%d (%.4f%%) min=%.2f chunks",
          edge, stride, rings, keys, interior, 100.0 * interiorShare(), seam, 100.0 * seamShare(),
          Math.sqrt(minSq));
    }
  }

  private static int orientationAt(int[] p, int edge) {
    return MemoryShape.orientationFor(Math.floorDiv(p[0], edge), Math.floorDiv(p[1], edge));
  }

  /** Scans lanes of stride {@code P^2 / binDivisor}, P being the bin edge the shape picks for this radius. */
  private static LaneScan scanLanes(long radius, int binDivisor, int lanes) {
    int edge = circle(1L, radius, 0).getPointEdgeChunks();
    int stride = edge * edge / binDivisor;
    CircleOptimizedDualLayer c = circle(1L, radius, stride);
    long range = c.getRange();
    long floorSq = stride;
    int cell = (int) Math.ceil(Math.sqrt(stride));
    Random pick = new Random(42L);
    Set<Integer> phases = new HashSet<>();
    while (phases.size() < Math.min(lanes, stride)) phases.add(pick.nextInt(stride));
    LaneScan out = new LaneScan();
    out.edge = edge;
    out.stride = stride;
    out.rings = c.getEffectiveKOuter(c.getEffectiveRadius(), edge);
    for (int phase : phases) {
      Map<Long, List<int[]>> grid = new HashMap<>();
      List<int[]> pts = new ArrayList<>();
      for (long key = phase; key < range; key += stride) {
        int[] p = c.locationToXZ(key);
        if (!inRing(p, radius)) continue;
        pts.add(p);
        grid.computeIfAbsent(((long) Math.floorDiv(p[0], cell) << 32) ^ (Math.floorDiv(p[1], cell) & 0xFFFFFFFFL),
            k -> new ArrayList<>()).add(p);
      }
      for (int[] p : pts) {
        out.keys++;
        int gx = Math.floorDiv(p[0], cell);
        int gz = Math.floorDiv(p[1], cell);
        int o = orientationAt(p, edge);
        boolean under = false;
        boolean sameOrientation = false;
        for (int ox = -1; ox <= 1; ox++) {
          for (int oz = -1; oz <= 1; oz++) {
            List<int[]> bucket = grid.get(((long) (gx + ox) << 32) ^ ((gz + oz) & 0xFFFFFFFFL));
            if (bucket == null) continue;
            for (int[] q : bucket) {
              if (q == p) continue;
              long d = distSq(p, q);
              out.minSq = Math.min(out.minSq, d);
              if (d < floorSq) {
                under = true;
                if (orientationAt(q, edge) == o) sameOrientation = true;
              }
            }
          }
        }
        if (under && sameOrientation) out.interior++;
        else if (under) out.seam++;
      }
    }
    return out;
  }

  @Test
  @DisplayName("Shipped auto resolution derives the full-bin cell stride P^2 = 1024 without clamping")
  void resolutionCellStrideReachesFullBin() {
    CircleOptimizedDualLayer c = shippedDefaultCircle(1L);
    long res = c.spatialResolution();
    long cellDim = 1L << (64 - Long.numberOfLeadingZeros(res - 1L));
    int edge = c.getPointEdgeChunks();
    int stride = c.deriveEffectiveStride(c.getRange());
    System.out.printf("[DEBUG_LOG] res=%d cellStride=%d binArea=%d derived=%d log2=%d%n",
        res, cellDim * cellDim, edge * edge, stride, Integer.numberOfTrailingZeros(stride));
    assertEquals((long) edge * edge, cellDim * cellDim, "auto resolution requests one key lane per bin");
    assertEquals(edge * edge, stride, "the bin cap must not clamp a full-bin stride");
  }

  @Test
  @DisplayName("Full-bin stride S=P^2: same-lane keys only break the P-chunk floor at orientation seams, shrinking as rings grow")
  void fullBinStrideViolatesOnlyAtSeamsAndScalesDown() {
    long[] radii = {512L, 1024L, 2048L, 4096L};
    LaneScan[] scans = new LaneScan[radii.length];
    for (int i = 0; i < radii.length; i++) {
      scans[i] = scanLanes(radii[i], 1, 64);
      System.out.printf("[DEBUG_LOG] full-bin R=%d: %s seam*rings=%.3f%n", radii[i], scans[i],
          scans[i].seamShare() * scans[i].rings);
      assertEquals(0L, scans[i].interior, "same-orientation tiles translate the lane by multiples of P");
      assertTrue(scans[i].seam > 0, "spiral ring corners re-orient the tile and do break the floor");
    }
    for (int i = 1; i < radii.length; i++) {
      if (scans[i].rings > scans[i - 1].rings) {
        assertTrue(scans[i].seamShare() < scans[i - 1].seamShare(), "seam share must fall as rings are added");
      }
    }
  }

  @Test
  @DisplayName("Sub-bin strides S=P^2/2 and S=P^2/4 break the floor inside tiles at a share that does not shrink with radius")
  void subBinStrideViolatesInsideTilesAtEveryRadius() {
    long[] radii = {512L, 1024L, 2048L};
    for (long radius : radii) {
      int shipped = shippedDerivedStride(radius);
      LaneScan half = scanLanes(radius, 2, 16);
      LaneScan quarter = scanLanes(radius, 4, 16);
      System.out.printf("[DEBUG_LOG] R=%d shippedStride=%d%n[DEBUG_LOG]   half-bin:    %s%n"
          + "[DEBUG_LOG]   quarter-bin: %s%n", radius, shipped, half, quarter);
      assertEquals(half.stride * 2, shipped, "shipped defaults derive the full-bin stride");
      assertTrue(half.interiorShare() > 0.5, "two keys per tile land in mirrored Hilbert halves");
      assertTrue(quarter.interiorShare() > 0.5, "sub-quadrant reflections break power-of-four strides too");
    }
  }

  private static int shippedDerivedStride(long radius) {
    CircleOptimizedDualLayer c = circle(1L, radius, 0);
    return c.deriveEffectiveStride(c.getRange());
  }

  @Test
  @DisplayName("Full-bin stride beats the former half-bin stride on back-to-back and 64-lookback spacing")
  void fullBinStrideRawStreamKeepsConsecutiveSpacing() {
    Stats uniform = uniformBaseline(false);
    Stats half = liveStream(false, 512);
    Stats full = liveStream(false);
    System.out.printf("[DEBUG_LOG] raw S=512:  %s%n[DEBUG_LOG] raw S=1024: %s%n[DEBUG_LOG] raw uniform: %s%n",
        half, full, uniform);
    assertEquals(0.0, full.dups, "keyed shuffle must not repeat a chunk within a cycle");
    assertTrue(full.lag1 < half.lag1, "full-bin lanes should beat half-bin lanes back-to-back");
    assertTrue(full.near64 < half.near64, "full-bin lanes should beat half-bin lanes on the 64-lookback metric");
  }

  @Test
  @DisplayName("BINNED_AMORTIZED backlog draws rotate stride lane on every draw")
  void binnedBacklogRotatesLaneEveryDraw() {
    Stats uniform = uniformBaseline(false);
    Stats leaf = new Stats();
    for (long s : SEEDS) {
      CircleOptimizedDualLayer c = shippedDefaultCircle(s);
      int stride = AbstractDualLayerShape.deriveAdaptiveStride(c.getEffectiveRange());
      List<int[]> seq = new ArrayList<>();
      List<Long> keys = new ArrayList<>();
      for (int guard = 0; seq.size() < LANDINGS && guard < LANDINGS * 4; guard++) {
        long key = c.selectL3Candidate();
        if (key < 0) continue;
        int[] p = c.locationToXZ(key);
        if (!inRing(p)) continue;
        keys.add(key);
        seq.add(p);
      }
      leaf.add(seq, keys, stride, null);
    }
    System.out.printf("[DEBUG_LOG] binned    leaf: %s%n[DEBUG_LOG] binned uniform: %s%n", leaf, uniform);
    assertTrue(leaf.mean(leaf.lane) < 0.01, "binned draws should never share a lane back-to-back");
    double ratio = leaf.near64 / uniform.near64;
    assertTrue(ratio > 0.75, "near-repeat count should be at least close to uniform, was ratio " + ratio);
  }
}
