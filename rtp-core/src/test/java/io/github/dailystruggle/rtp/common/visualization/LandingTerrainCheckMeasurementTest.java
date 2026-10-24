package io.github.dailystruggle.rtp.common.visualization;

import io.github.dailystruggle.rtp.anvil.AnvilChunkView;
import io.github.dailystruggle.rtp.anvil.AnvilPrefilter;
import io.github.dailystruggle.rtp.anvil.AnvilReader;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.AbstractVerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.TerrainShapeCheck;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.TerrainShapeCheck.SquareReader;
import io.github.dailystruggle.rtp.common.tools.ChartOutputHelper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Scores block-based landing terrain checks (ADR-119 successor candidates) on the 5x5 chunk square
 * a teleport already pins (`viewDistanceTeleport` radius 2). Reads blocks only, never generator
 * noise, so the same checks apply to custom generators.
 *
 * <p>Landing: a seeded random vert-stencil column of a seeded random chunk, feet on the terrain
 * surface, standable and not on liquid. Chunks whose 5x5 square is not fully generated are skipped,
 * since at runtime the square is always resident.
 *
 * <p>Truths (top-down surface floors, trees passable; measurement only, never shipped):
 * T1 chunk mountainside (ADR-119 rule on chunk-centre floors), T2 the same rule centred on the
 * landing column, T3 cliff edge (a surface floor within 4 blocks differs by >= 5).
 *
 * <p>Cost: {@code reads} counts block-class lookups per check (platform-neutral unit; real cost is
 * reads x one snapshot read). {@code ns} is the check alone on warm, pre-classified blocks;
 * {@code nbrCh} is the neighbour chunks a check reads, each one extra chunk decode.
 *
 * <p>Clipped checks ({@code clip ...}) see only the chunks in the landing chunk's region file, as
 * the L2/L3 pre-compute does (one region file per probe drain). Region edge and corner chunks get
 * a partial square; directions and plane points outside the file are left out, never guessed.
 */
public class LandingTerrainCheckMeasurementTest {

  private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");
  private static final Path REGION_DIR = Path.of(System.getProperty("rtp.elevation.regionDir",
      "/home/johngoldsmith/Documents/GameServers/Minecraft/testServer/RTP-Paper/26.3"
          + "/world/dimensions/minecraft/overworld/region"));
  private static final int PER_REGION = Integer.getInteger("rtp.landing.perRegion", 10);
  private static final int MAX_LANDINGS = Integer.getInteger("rtp.landing.max", 5000);
  private static final long SEED = 119L;

  private static final int MIN_Y = -64;
  private static final int MAX_Y = 319;
  private static final int HEIGHT = MAX_Y - MIN_Y + 1;
  private static final int NONE = Integer.MIN_VALUE;
  private static final int PATCH_CHUNKS = 5;
  private static final int SIDE = PATCH_CHUNKS * 16;
  /** Vert stencil columns (ADR-119); each landing uses one, so spot and chunk truths differ. */
  private static final int[][] STENCIL = {{7, 7}, {2, 2}, {12, 12}, {2, 12}, {12, 2}};
  private static final int[][] DIRS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
  private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
  private static final double TAN30 = Math.tan(Math.toRadians(30.0));
  private static final int MIN_STEEP_DIRS = 4;
  private static final int LAYER_WINDOW = 48;
  private static final int TIMING_WARMUP = 200;
  private static final int CENTRE = 2 * PATCH_CHUNKS + 2;
  /** Edge and corner oversample per region file (alternating), on top of the uniform picks. */
  private static final int EXTRA_PER_REGION = Integer.getInteger("rtp.landing.extraPerRegion", 6);
  /**
   * Share of chunks per position for a 5x5 square in a 32x32 region file: a chunk is clipped on an
   * axis when its region-local coordinate is 0, 1, 30 or 31.
   */
  private static final double[] POS_SHARE = {(28.0 / 32) * (28.0 / 32), 2 * (28.0 / 32) * (4.0 / 32),
      (4.0 / 32) * (4.0 / 32)};

  private static final byte KNOWN = 1, AIR = 2, WALK = 4, TERRAIN = 8, LIQUID = 16;

  /** Non-colliding ground cover a player walks through. */
  private static final Set<String> WALK_THROUGH = Set.of("SHORT_GRASS", "TALL_GRASS", "FERN", "LARGE_FERN",
      "SNOW", "DEAD_BUSH", "BUSH", "FIREFLY_BUSH", "SHORT_DRY_GRASS", "TALL_DRY_GRASS", "PINK_PETALS",
      "WILDFLOWERS", "LEAF_LITTER", "SWEET_BERRY_BUSH", "VINE", "GLOW_LICHEN", "SUGAR_CANE", "TORCH",
      "BROWN_MUSHROOM", "RED_MUSHROOM", "DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET",
      "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY", "SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY",
      "TORCHFLOWER", "PITCHER_PLANT", "CLOSED_EYEBLOSSOM", "OPEN_EYEBLOSSOM", "WITHER_ROSE", "SEAGRASS",
      "TALL_SEAGRASS", "HANGING_ROOTS", "CAVE_VINES", "CAVE_VINES_PLANT", "SPORE_BLOSSOM", "SNOW_LAYER");

  /**
   * {@code pos}: 0 interior, 1 region edge, 2 region corner; {@code measured}: directions the clipped
   * rule could measure; {@code extra}: from the edge/corner oversample, excluded from overall rates.
   */
  record Landing(int gcx, int gcz, int lx, int lz, int y, boolean t1, boolean t2, boolean t3, int pos,
      int measured, boolean extra) {}

  private static final String[] POS_NAMES = {"interior", "edge", "corner"};
  /** Clipped check name -> full-square check it degrades from. */
  private static final Map<String, String> CLIP_OF = new LinkedHashMap<>();

  /** A check returns true when it would reject the landing. */
  interface Check {
    boolean reject(Patch p, int y);
  }

  record NamedCheck(String name, Check check) {}

  /** 5x5 chunk square around the landing chunk, with lazily classified blocks. */
  static final class Patch {
    final AnvilChunkView[] views = new AnvilChunkView[PATCH_CHUNKS * PATCH_CHUNKS];
    final int[] topY = new int[PATCH_CHUNKS * PATCH_CHUNKS];
    final byte[] cls = new byte[SIDE * SIDE * HEIGHT];
    final Map<String, Byte> memo = new HashMap<>();
    long reads;
    /** Chunks a clipped check may read (same region file as the landing chunk). */
    final boolean[] clip = new boolean[PATCH_CHUNKS * PATCH_CHUNKS];
    /** While set, a read outside {@link #clip} fails the test (clipped checks must not leak). */
    boolean strict;
    /** Bitmask of square chunks read since last cleared. */
    int touched;
    /** Chunk-local landing column and its square coordinates. */
    int lx, lz, x0, z0;

    void reset() {
      Arrays.fill(cls, (byte) 0);
    }

    byte at(int px, int y, int pz) {
      reads++;
      int ci = (pz >> 4) * PATCH_CHUNKS + (px >> 4);
      if (strict && !clip[ci]) throw new AssertionError("clipped check read outside its region file");
      touched |= 1 << ci;
      if (y < MIN_Y) return 0; // bedrock floor: solid
      if (y > MAX_Y) return AIR | WALK | TERRAIN;
      int idx = ((pz * SIDE) + px) * HEIGHT + (y - MIN_Y);
      byte c = cls[idx];
      if (c != 0) return c;
      String raw = views[ci].blockIdAt(px & 15, y, pz & 15);
      c = raw == null ? (byte) (KNOWN | AIR | WALK | TERRAIN) : memo.computeIfAbsent(raw, Patch::classify);
      cls[idx] = c;
      return c;
    }

    static byte classify(String raw) {
      String r = AnvilPrefilter.DEFAULT_RECONCILER.apply(raw);
      boolean air = "AIR".equals(r) || "CAVE_AIR".equals(r) || "VOID_AIR".equals(r);
      boolean walk = air || WALK_THROUGH.contains(r) || r.endsWith("_TULIP") || r.endsWith("_SAPLING")
          || r.endsWith("_CARPET");
      boolean terrain = walk || r.endsWith("_LEAVES") || r.endsWith("_LOG") || r.endsWith("_WOOD")
          || r.endsWith("_STEM") || r.endsWith("MUSHROOM_BLOCK");
      boolean liquid = "WATER".equals(r) || "LAVA".equals(r) || "BUBBLE_COLUMN".equals(r);
      byte c = KNOWN;
      if (air) c |= AIR;
      if (walk) c |= WALK;
      if (terrain) c |= TERRAIN;
      if (liquid) c |= LIQUID;
      return c;
    }

    boolean is(int px, int y, int pz, byte flag) {
      return (at(px, y, pz) & flag) != 0;
    }

    /** Column inside the square and in a chunk a clipped check may read. */
    boolean has(int px, int pz) {
      return px >= 0 && pz >= 0 && px < SIDE && pz < SIDE && clip[(pz >> 4) * PATCH_CHUNKS + (px >> 4)];
    }

    /** Top-down terrain surface feet-Y (trees passable), or NONE. */
    int surface(int px, int pz) {
      int top = topY[(pz >> 4) * PATCH_CHUNKS + (px >> 4)];
      for (int y = top; y >= MIN_Y; y--) if (!is(px, y, pz, TERRAIN)) return y + 1;
      return NONE;
    }

    /** Terrain floor nearest {@code yRef} within the window, following the layer; NONE if absent. */
    int floorNear(int px, int pz, int yRef, int window) {
      for (int d = 0; d <= window; d++) {
        int up = yRef + d;
        if (!is(px, up - 1, pz, TERRAIN) && is(px, up, pz, TERRAIN)) return up;
        int down = yRef - d;
        if (d > 0 && !is(px, down - 1, pz, TERRAIN) && is(px, down, pz, TERRAIN)) return down;
      }
      return NONE;
    }
  }

  @Test
  @DisplayName("ADR-119: block-based landing terrain checks on the pinned 5x5 chunk square")
  public void measureLandingTerrainChecks() throws Exception {
    Assumptions.assumeTrue(Files.isDirectory(REGION_DIR), "reference save not present");
    List<int[]> regions = listRegions();
    Assumptions.assumeTrue(!regions.isEmpty(), "reference save has no region files");
    List<NamedCheck> checks = checks();
    int nc = checks.size();

    Random rnd = new Random(SEED);
    List<int[]> picks = new ArrayList<>();
    for (int[] r : regions) {
      for (int k = 0; k < PER_REGION; k++) {
        int[] col = STENCIL[rnd.nextInt(STENCIL.length)];
        picks.add(new int[] {r[0] * 32 + rnd.nextInt(32), r[1] * 32 + rnd.nextInt(32), col[0], col[1], 0});
      }
    }
    java.util.Collections.shuffle(picks, rnd);
    if (picks.size() > MAX_LANDINGS) picks = new ArrayList<>(picks.subList(0, MAX_LANDINGS));
    // Extra edge and corner picks (flag 1) enlarge those classes; the overall table uses flag 0 only.
    Random extraRnd = new Random(SEED + 1);
    for (int[] r : regions) {
      for (int k = 0; k < EXTRA_PER_REGION; k++) {
        int[] col = STENCIL[extraRnd.nextInt(STENCIL.length)];
        boolean corner = k % 2 == 1, bandOnX = extraRnd.nextBoolean();
        int a = band(extraRnd), b = corner ? band(extraRnd) : 2 + extraRnd.nextInt(28);
        int lcx = bandOnX ? a : b, lcz = bandOnX ? b : a;
        picks.add(new int[] {r[0] * 32 + lcx, r[1] * 32 + lcz, col[0], col[1], 1});
      }
    }
    picks.sort((a, b) -> {
      int c = Integer.compare(Math.floorDiv(a[1], 32), Math.floorDiv(b[1], 32));
      return c != 0 ? c : Integer.compare(Math.floorDiv(a[0], 32), Math.floorDiv(b[0], 32));
    });

    Map<Long, byte[]> regionCache = new LinkedHashMap<>(32, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Long, byte[]> e) {
        return size() > 12;
      }
    };
    Patch patch = new Patch();
    List<Landing> landings = new ArrayList<>();
    List<boolean[]> verdicts = new ArrayList<>();
    List<long[]> reads = new ArrayList<>();
    List<long[]> nanos = new ArrayList<>();
    List<int[]> nbrChunks = new ArrayList<>();
    List<int[]> touchMasks = new ArrayList<>();
    long[] decode = new long[4]; // {views decoded, nanos, column probes read, nanos}
    int skippedPatch = 0, skippedFloor = 0;
    long t0 = System.nanoTime();
    for (int[] pick : picks) {
      if (!loadPatch(patch, regionCache, pick[0], pick[1], decode)) {
        skippedPatch++;
        continue;
      }
      patch.reset();
      patch.lx = pick[2];
      patch.lz = pick[3];
      patch.x0 = 32 + pick[2];
      patch.z0 = 32 + pick[3];
      int y = patch.surface(patch.x0, patch.z0);
      if (y == NONE || !standable(patch, patch.x0, y, patch.z0) || patch.is(patch.x0, y - 1, patch.z0, LIQUID)) {
        skippedFloor++;
        continue;
      }
      int lcx = Math.floorMod(pick[0], 32), lcz = Math.floorMod(pick[1], 32);
      int pos = (lcx < 2 || lcx > 29 ? 1 : 0) + (lcz < 2 || lcz > 29 ? 1 : 0);
      for (int dz = -2; dz <= 2; dz++) {
        for (int dx = -2; dx <= 2; dx++) {
          patch.clip[(dz + 2) * PATCH_CHUNKS + dx + 2] = lcx + dx >= 0 && lcx + dx < 32 && lcz + dz >= 0
              && lcz + dz < 32;
        }
      }
      Landing l = new Landing(pick[0], pick[1], pick[2], pick[3], y, truthChunk(patch), truthSpot(patch, y),
          truthCliff(patch, y), pos,
          TerrainShapeCheck.rule(square(patch, true, false), patch.lx, patch.lz, y, TAN30, MIN_Y, MAX_Y).measured(),
          pick[4] == 1);
      boolean[] v = new boolean[nc];
      long[] rd = new long[nc], ns = new long[nc];
      int[] nb = new int[nc], tm = new int[nc];
      for (int c = 0; c < nc; c++) { // cold pass: classifies blocks, counts reads
        patch.reads = 0;
        patch.touched = 0;
        v[c] = checks.get(c).check.reject(patch, y);
        rd[c] = patch.reads;
        tm[c] = patch.touched;
        nb[c] = Integer.bitCount(patch.touched & ~(1 << CENTRE));
      }
      for (int c = 0; c < nc; c++) { // warm pass: check cost alone
        long s = System.nanoTime();
        boolean again = checks.get(c).check.reject(patch, y);
        ns[c] = System.nanoTime() - s;
        if (again != v[c]) throw new AssertionError("non-deterministic check " + checks.get(c).name);
      }
      landings.add(l);
      verdicts.add(v);
      reads.add(rd);
      nanos.add(ns);
      nbrChunks.add(nb);
      touchMasks.add(tm);
    }
    // Overall table: uniform picks only, so rates match a random landing.
    List<Landing> all = landings;
    List<Integer> mainIdx = new ArrayList<>();
    for (int i = 0; i < all.size(); i++) if (!all.get(i).extra) mainIdx.add(i);
    landings = new ArrayList<>();
    List<boolean[]> allVerdicts = verdicts;
    List<long[]> allReads = reads, allNanos = nanos;
    List<int[]> allNbr = nbrChunks;
    verdicts = new ArrayList<>();
    reads = new ArrayList<>();
    nanos = new ArrayList<>();
    nbrChunks = new ArrayList<>();
    for (int i : mainIdx) {
      landings.add(all.get(i));
      verdicts.add(allVerdicts.get(i));
      reads.add(allReads.get(i));
      nanos.add(allNanos.get(i));
      nbrChunks.add(allNbr.get(i));
    }
    int n = landings.size();
    Assumptions.assumeTrue(n > 0, "no landing with a fully generated 5x5 square");

    long nT1 = landings.stream().filter(Landing::t1).count();
    long nT2 = landings.stream().filter(Landing::t2).count();
    long nT3 = landings.stream().filter(Landing::t3).count();
    long nAny = landings.stream().filter(l -> l.t1 || l.t2 || l.t3).count();
    System.out.printf("%n[DEBUG_LOG] === LANDING TERRAIN CHECKS (ADR-119, block-based, 5x5 square) ===%n");
    System.out.printf("[DEBUG_LOG] save=%s%n", REGION_DIR);
    System.out.printf("[DEBUG_LOG] landings=%,d (+%,d edge/corner oversample) skipped: square not generated=%,d,"
        + " landing not standable/liquid=%,d; %.1fs%n",
        n, all.size() - n, skippedPatch, skippedFloor, (System.nanoTime() - t0) / 1e9);
    System.out.printf("[DEBUG_LOG] per-chunk decode, region bytes in memory: full view %.0f us (%,d), column probe"
        + " %.0f us (%,d)%n", decode[1] / 1e3 / Math.max(1, decode[0]), decode[0],
        decode[3] / 1e3 / Math.max(1, decode[2]), decode[2]);
    System.out.printf("[DEBUG_LOG] truth: T1 chunk mountainside=%.2f%%  T2 spot mountainside=%.2f%%  T3 cliff<=4b>=5=%.2f%%  any=%.2f%%%n",
        pct(nT1, n), pct(nT2, n), pct(nT3, n), pct(nAny, n));
    System.out.printf("[DEBUG_LOG] truth overlap: T1&T2=%.2f%% T1&T3=%.2f%% T2&T3=%.2f%%%n",
        pct(landings.stream().filter(l -> l.t1 && l.t2).count(), n),
        pct(landings.stream().filter(l -> l.t1 && l.t3).count(), n),
        pct(landings.stream().filter(l -> l.t2 && l.t3).count(), n));
    System.out.printf("[DEBUG_LOG] %-30s %7s %6s | %6s %6s | %6s %6s | %6s %6s | %6s %6s | %7s %7s | %8s %8s | %5s%n",
        "check", "reject%", "picks", "T1rec", "T1fr", "T2rec", "T2fr", "T3rec", "T3fr", "ANYrec", "ANYfr",
        "reads", "rd p99", "ns", "ns p99", "nbrCh");
    for (int c = 0; c < nc; c++) {
      long rej = 0;
      long[][] conf = new long[4][4]; // [truth][tp, pos, fp, neg]
      long[] rd = new long[n];
      long nbSum = 0;
      List<Long> nsList = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        nbSum += nbrChunks.get(i)[c];
        Landing l = landings.get(i);
        boolean r = verdicts.get(i)[c];
        if (r) rej++;
        boolean[] t = {l.t1, l.t2, l.t3, l.t1 || l.t2 || l.t3};
        for (int k = 0; k < 4; k++) {
          if (t[k]) {
            conf[k][1]++;
            if (r) conf[k][0]++;
          } else {
            conf[k][3]++;
            if (r) conf[k][2]++;
          }
        }
        rd[i] = reads.get(i)[c];
        if (i >= Math.min(TIMING_WARMUP, n / 4)) nsList.add(nanos.get(i)[c]);
      }
      long[] nsArr = nsList.stream().mapToLong(Long::longValue).toArray();
      double picksPer = rej >= n ? Double.POSITIVE_INFINITY : n / (double) (n - rej);
      System.out.printf("[DEBUG_LOG] %-30s %6.2f%% %6.2f | %5.1f%% %5.1f%% | %5.1f%% %5.1f%% | %5.1f%% %5.1f%% | %5.1f%% %5.1f%% | %7.0f %7d | %8.0f %8d | %5.1f%n",
          checks.get(c).name, pct(rej, n), picksPer,
          pct(conf[0][0], conf[0][1]), pct(conf[0][2], conf[0][3]),
          pct(conf[1][0], conf[1][1]), pct(conf[1][2], conf[1][3]),
          pct(conf[2][0], conf[2][1]), pct(conf[2][2], conf[2][3]),
          pct(conf[3][0], conf[3][1]), pct(conf[3][2], conf[3][3]),
          mean(rd), p99(rd), mean(nsArr), p99(nsArr), nbSum / (double) n);
    }
    System.out.printf("[DEBUG_LOG] rec = truth landings rejected; fr = false rejects among non-truth landings;"
        + " picks = expected picks per accepted landing (1 / (1 - reject)); nbrCh = neighbour chunks read%n");

    reportByPosition(checks, all, allVerdicts);
    List<int[]> mainMasks = new ArrayList<>();
    for (int i : mainIdx) mainMasks.add(touchMasks.get(i));
    reportAngles(checks, landings, verdicts, mainMasks);

    File csv = ChartOutputHelper.getReportFile("adr-119", "landing-terrain-checks.csv");
    writeCsv(csv, checks, all, allVerdicts, allReads);
    System.out.printf("[DEBUG_LOG] per-landing CSV: %s%n", csv.getAbsolutePath());
  }

  private static List<NamedCheck> checks() {
    List<NamedCheck> out = new ArrayList<>();
    int[][] slope = {{2, 1}, {2, 2}, {2, 3}, {4, 3}, {8, 4}};
    for (int[] s : slope) {
      int radius = s[0], maxSlope = s[1];
      out.add(new NamedCheck("slopeOk r" + radius + " max" + maxSlope, (p, y) -> {
        AbstractVerticalAdjustor.FloorReader reader = (lx, fy, lz) ->
            !p.is(lx + 32, fy - 1, lz + 32, AIR) && p.is(lx + 32, fy, lz + 32, AIR);
        return !AbstractVerticalAdjustor.slopeOk(reader, p.lx, y, p.lz, radius, maxSlope, MIN_Y + 1, MAX_Y);
      }));
    }
    for (int[] g : new int[][] {{8, 2}, {24, 4}}) {
      for (double deg : new double[] {20, 25, 30}) {
        int r = g[0], step = g[1];
        double tan = Math.tan(Math.toRadians(deg));
        out.add(new NamedCheck(String.format("plane r%d s%d >=%.0fdeg", r, step, deg), (p, y) -> {
          double[] f = planeFit(p, y, r, step);
          return f == null || f[0] >= tan;
        }));
      }
    }
    for (double rms : new double[] {3, 5}) {
      double tan = Math.tan(Math.toRadians(25));
      out.add(new NamedCheck(String.format("plane r24 >=25deg|rms>=%.0f", rms), (p, y) -> {
        double[] f = planeFit(p, y, 24, 4);
        return f == null || f[0] >= tan || f[1] >= rms;
      }));
    }
    for (int h : new int[] {8, 12}) {
      out.add(new NamedCheck("relHeight r24 |d|>=" + h, (p, y) -> {
        double rel = relativeHeight(p, y, 24, 16);
        return Double.isNaN(rel) || Math.abs(rel) >= h;
      }));
    }
    for (int need : new int[] {150, 600}) {
      out.add(new NamedCheck("walk +1/-3 N=" + need, (p, y) -> !walkable(p, y, need, 3, true)));
    }
    for (int need : new int[] {300, 1000}) {
      out.add(new NamedCheck("walk +1/-1 N=" + need + " no-edge", (p, y) -> !walkable(p, y, need, 1, false)));
    }
    // Shipped check (TerrainShapeCheck) on the full square, then on the region-clipped square.
    out.add(new NamedCheck("square rule (layer-follow)", (p, y) -> ruleOf(p, y, false).steep() >= MIN_STEEP_DIRS));
    out.add(new NamedCheck("square rule + plane r8 30deg",
        (p, y) -> TerrainShapeCheck.evaluate(square(p, false, false), p.lx, p.lz, y, TAN30, MIN_Y, MAX_Y)
            .reject(TAN30)));

    // Region-clipped variants: only the landing chunk's region file is readable.
    Check clipPlane = (p, y) -> {
      double g = TerrainShapeCheck.planeGrade(square(p, true, false), p.lx, p.lz, y, MIN_Y, MAX_Y);
      return !Double.isNaN(g) && g >= TAN30;
    };
    Check clipFixed = (p, y) -> ruleOf(p, y, true).steep() >= MIN_STEEP_DIRS;
    Check clipScaled = (p, y) -> ruleOf(p, y, true).ruleRejects();
    Check clipDefer = (p, y) -> {
      TerrainShapeCheck.Verdict v = ruleOf(p, y, true);
      return v.full() && v.steep() >= MIN_STEEP_DIRS;
    };
    addClipped(out, "clip rule fixed >=4", "square rule (layer-follow)", clipFixed);
    addClipped(out, "clip rule scaled >=half", "square rule (layer-follow)", clipScaled);
    addClipped(out, "clip rule defer <8 dirs", "square rule (layer-follow)", clipDefer);
    addClipped(out, "clip plane r8 30deg", "plane r8 s2 >=30deg", clipPlane);
    addClipped(out, "clip fixed + plane", "square rule + plane r8 30deg",
        (p, y) -> clipFixed.reject(p, y) || clipPlane.reject(p, y));
    addClipped(out, "clip scaled + plane", "square rule + plane r8 30deg",
        (p, y) -> TerrainShapeCheck.evaluate(square(p, true, false), p.lx, p.lz, y, TAN30, MIN_Y, MAX_Y)
            .reject(TAN30));
    // Same, with the live/probe floor test (non-air below, air at feet): trees and cover are floor.
    addClipped(out, "clip scaled + plane air-floor", "square rule + plane r8 30deg",
        (p, y) -> TerrainShapeCheck.evaluate(square(p, true, true), p.lx, p.lz, y, TAN30, MIN_Y, MAX_Y)
            .reject(TAN30));
    addAngleChecks(out);
    return out;
  }

  /** Limits for the angle study: does a steeper limit decide from the 3x3 square more often? */
  private static final int[] ANGLES = {30, 45, 60};

  /**
   * Per angle, on the full square with the shipped air floor test: eager rule + plane, the staged
   * {@link TerrainShapeCheck#evaluate} (must agree with eager on every landing), and staged over
   * the 3x3 only (32-block samples unreadable).
   */
  private static void addAngleChecks(List<NamedCheck> out) {
    for (int deg : ANGLES) {
      double tan = TerrainShapeCheck.tanOf(deg);
      Check eager = (p, y) -> {
        SquareReader r = square(p, false, true);
        double g = TerrainShapeCheck.planeGrade(r, p.lx, p.lz, y, MIN_Y, MAX_Y);
        return TerrainShapeCheck.rule(r, p.lx, p.lz, y, tan, MIN_Y, MAX_Y).ruleRejects()
            || (!Double.isNaN(g) && g >= tan);
      };
      out.add(new NamedCheck("angle " + deg + " eager 5x5", eager));
      out.add(new NamedCheck("angle " + deg + " staged 5x5", (p, y) -> {
        boolean staged = TerrainShapeCheck.evaluate(square(p, false, true), p.lx, p.lz, y, tan, MIN_Y, MAX_Y)
            .reject(tan);
        int touched = p.touched;
        if (staged != eager.reject(p, y)) throw new AssertionError("staged != eager at " + deg + " deg");
        p.touched = touched; // cost of the staged check alone
        return staged;
      }));
      out.add(new NamedCheck("angle " + deg + " staged 3x3", (p, y) -> {
        SquareReader full = square(p, false, true);
        SquareReader inner = new SquareReader() {
          @Override
          public boolean readable(int sx, int sz) {
            return sx >= 16 && sz >= 16 && sx < 64 && sz < 64;
          }

          @Override
          public boolean isFloor(int sx, int feetY, int sz) {
            return full.isFloor(sx, feetY, sz);
          }
        };
        return TerrainShapeCheck.evaluate(inner, p.lx, p.lz, y, tan, MIN_Y, MAX_Y).reject(tan);
      }));
    }
  }

  /** Angle study: neighbour chunks touched, share decided inside the 3x3, 3x3-only agreement. */
  private static void reportAngles(List<NamedCheck> checks, List<Landing> landings, List<boolean[]> verdicts,
                                   List<int[]> masks) {
    int ring2 = 0;
    for (int cz = 0; cz < PATCH_CHUNKS; cz++) {
      for (int cx = 0; cx < PATCH_CHUNKS; cx++) {
        if (cx == 0 || cz == 0 || cx == PATCH_CHUNKS - 1 || cz == PATCH_CHUNKS - 1) ring2 |= 1 << (cz * PATCH_CHUNKS + cx);
      }
    }
    int n = landings.size();
    System.out.printf("%n[DEBUG_LOG] === ANGLE STUDY (maxHillAngle, full square, shipped air floor test) ===%n");
    System.out.printf("[DEBUG_LOG] %-6s %8s | %9s %9s %9s | %9s %9s | %8s %8s %8s %8s%n", "angle", "reject%",
        "nbr eager", "nbr stgd", "nbr 3x3", "in3x3 rej", "in3x3 acc", "3x3 rej%", "3x3 miss", "3x3 extra",
        "agree");
    for (int deg : ANGLES) {
      int e = indexOf(checks, "angle " + deg + " eager 5x5");
      int s = indexOf(checks, "angle " + deg + " staged 5x5");
      int t = indexOf(checks, "angle " + deg + " staged 3x3");
      long rej = 0, rej3 = 0, miss = 0, extra = 0, inRej = 0, inAcc = 0, acc = 0;
      long nbE = 0, nbS = 0, nbT = 0;
      for (int i = 0; i < n; i++) {
        boolean ve = verdicts.get(i)[e], vt = verdicts.get(i)[t];
        int[] m = masks.get(i);
        nbE += Integer.bitCount(m[e] & ~(1 << CENTRE));
        nbS += Integer.bitCount(m[s] & ~(1 << CENTRE));
        nbT += Integer.bitCount(m[t] & ~(1 << CENTRE));
        boolean inner = (m[s] & ring2) == 0;
        if (ve) {
          rej++;
          if (inner) inRej++;
        } else {
          acc++;
          if (inner) inAcc++;
        }
        if (vt) rej3++;
        if (ve && !vt) miss++;
        if (!ve && vt) extra++;
      }
      System.out.printf("[DEBUG_LOG] %-6s %7.2f%% | %9.2f %9.2f %9.2f | %8.1f%% %8.1f%% | %7.2f%% %7.1f%% %8.2f%% %7.2f%%%n",
          deg + "deg", pct(rej, n), nbE / (double) n, nbS / (double) n, nbT / (double) n, pct(inRej, rej),
          pct(inAcc, acc), pct(rej3, n), pct(miss, rej), pct(extra, n - rej), pct(n - miss - extra, n));
    }
    System.out.printf("[DEBUG_LOG] nbr = neighbour chunks touched per check; in3x3 = staged verdicts that never"
        + " touched the outer ring (of rejects / accepts); 3x3 miss = 5x5 rejects the 3x3-only check accepts;"
        + " 3x3 extra = 5x5 accepts it rejects; agree = same verdict%n");
  }

  private static int indexOf(List<NamedCheck> checks, String name) {
    for (int i = 0; i < checks.size(); i++) if (checks.get(i).name.equals(name)) return i;
    throw new IllegalArgumentException(name);
  }

  private static TerrainShapeCheck.Verdict ruleOf(Patch p, int y, boolean clipped) {
    return TerrainShapeCheck.rule(square(p, clipped, false), p.lx, p.lz, y, TAN30, MIN_Y, MAX_Y);
  }

  /**
   * Patch as a {@link SquareReader}: {@code clipped} limits readable columns to the landing's
   * region file; {@code airFloor} uses the shipped live/probe floor test instead of the
   * trees-passable terrain floor the truths use.
   */
  static SquareReader square(Patch p, boolean clipped, boolean airFloor) {
    return new SquareReader() {
      @Override
      public boolean readable(int sx, int sz) {
        return clipped ? p.has(sx, sz) : sx >= 0 && sz >= 0 && sx < SIDE && sz < SIDE;
      }

      @Override
      public boolean isFloor(int sx, int feetY, int sz) {
        byte f = airFloor ? AIR : TERRAIN;
        return !p.is(sx, feetY - 1, sz, f) && p.is(sx, feetY, sz, f);
      }
    };
  }

  /** Adds a clipped check that fails the run if it reads a chunk outside the landing's region file. */
  private static void addClipped(List<NamedCheck> out, String name, String fullName, Check c) {
    CLIP_OF.put(name, fullName);
    out.add(new NamedCheck(name, (p, y) -> {
      p.strict = true;
      try {
        return c.reject(p, y);
      } finally {
        p.strict = false;
      }
    }));
  }

  /**
   * Interior / edge / corner breakdown of each clipped check against its full-square check, over
   * all landings including the oversample. {@code flip} = landings where the two verdicts differ;
   * {@code weighted} rows combine classes by their share of chunks in a region file.
   */
  private static void reportByPosition(List<NamedCheck> checks, List<Landing> all, List<boolean[]> verdicts) {
    Map<String, Integer> idx = new HashMap<>();
    for (int c = 0; c < checks.size(); c++) idx.put(checks.get(c).name, c);
    long[] posN = new long[3];
    long[][] measuredHist = new long[3][DIRS.length + 1];
    for (Landing l : all) {
      posN[l.pos]++;
      measuredHist[l.pos][l.measured]++;
    }
    System.out.printf("%n[DEBUG_LOG] === REGION-CLIPPED SQUARE (one region file per pre-compute drain) ===%n");
    for (int k = 0; k < 3; k++) {
      StringBuilder sb = new StringBuilder();
      for (int m = 0; m <= DIRS.length; m++) {
        if (measuredHist[k][m] > 0) sb.append(String.format(" %d:%.0f%%", m, pct(measuredHist[k][m], posN[k])));
      }
      System.out.printf("[DEBUG_LOG] %-8s share of chunks=%5.1f%% landings=%,5d measured dirs:%s%n", POS_NAMES[k],
          100 * POS_SHARE[k], posN[k], sb);
    }
    System.out.printf("[DEBUG_LOG] %-26s %-9s %7s %7s | %6s %6s | %6s %6s | %6s%n", "check", "position", "reject%",
        "full%", "T2rec", "T2fr", "T3rec", "T3fr", "flip");
    for (Map.Entry<String, String> e : CLIP_OF.entrySet()) {
      int c = idx.get(e.getKey()), f = idx.get(e.getValue());
      double[] w = new double[7];
      for (int k = 0; k < 3; k++) {
        long n = 0, rej = 0, full = 0, flip = 0, t2 = 0, t2c = 0, n2 = 0, n2r = 0, t3 = 0, t3c = 0, n3 = 0, n3r = 0;
        for (int i = 0; i < all.size(); i++) {
          Landing l = all.get(i);
          if (l.pos != k) continue;
          boolean r = verdicts.get(i)[c], rf = verdicts.get(i)[f];
          n++;
          if (r) rej++;
          if (rf) full++;
          if (r != rf) flip++;
          if (l.t2) {
            t2++;
            if (r) t2c++;
          } else {
            n2++;
            if (r) n2r++;
          }
          if (l.t3) {
            t3++;
            if (r) t3c++;
          } else {
            n3++;
            if (r) n3r++;
          }
        }
        double[] row = {pct(rej, n), pct(full, n), pct(t2c, t2), pct(n2r, n2), pct(t3c, t3), pct(n3r, n3),
            pct(flip, n)};
        for (int j = 0; j < row.length; j++) w[j] += POS_SHARE[k] * row[j];
        System.out.printf("[DEBUG_LOG] %-26s %-9s %6.2f%% %6.2f%% | %5.1f%% %5.1f%% | %5.1f%% %5.1f%% | %5.1f%%%n",
            e.getKey(), POS_NAMES[k], row[0], row[1], row[2], row[3], row[4], row[5], row[6]);
      }
      System.out.printf("[DEBUG_LOG] %-26s %-9s %6.2f%% %6.2f%% | %5.1f%% %5.1f%% | %5.1f%% %5.1f%% | %5.1f%%%n",
          e.getKey(), "weighted", w[0], w[1], w[2], w[3], w[4], w[5], w[6]);
    }
    System.out.printf("[DEBUG_LOG] full%% = the full-square check's reject rate on the same landings;"
        + " weighted recall/fr average per-class rates by chunk share (approximate)%n");
  }

  /**
   * Least-squares plane over layer-following floors on a grid within {@code r} blocks (step
   * {@code step}). Returns {gradient (blocks per block), RMS residual}, or null when more than a
   * quarter of the points have no floor within the window (a layer break counts as steep).
   */
  static double[] planeFit(Patch p, int y, int r, int step) {
    int pts = 0, missing = 0;
    double sx = 0, sz = 0, sy = 0, sxx = 0, szz = 0, sxz = 0, sxy = 0, szy = 0, syy = 0;
    for (int dz = -r; dz <= r; dz += step) {
      for (int dx = -r; dx <= r; dx += step) {
        pts++;
        int f = p.floorNear(p.x0 + dx, p.z0 + dz, y, LAYER_WINDOW);
        if (f == NONE) {
          missing++;
          continue;
        }
        double v = f - y;
        sx += dx;
        sz += dz;
        sy += v;
        sxx += dx * dx;
        szz += dz * dz;
        sxz += dx * dz;
        sxy += dx * v;
        szy += dz * v;
        syy += v * v;
      }
    }
    if (missing * 4 > pts) return null;
    double n = pts - missing;
    double cxx = sxx - sx * sx / n, czz = szz - sz * sz / n, cxz = sxz - sx * sz / n;
    double cxy = sxy - sx * sy / n, czy = szy - sz * sy / n, cyy = syy - sy * sy / n;
    double det = cxx * czz - cxz * cxz;
    if (Math.abs(det) < 1e-9) return null;
    double b = (cxy * czz - czy * cxz) / det, c = (czy * cxx - cxy * cxz) / det;
    double sse = Math.max(0, cyy - b * cxy - c * czy);
    return new double[] {Math.hypot(b, c), Math.sqrt(sse / n)};
  }

  /** Landing Y minus the mean floor on a ring; NaN when more than a quarter of the ring is missing. */
  static double relativeHeight(Patch p, int y, int r, int samples) {
    double sum = 0;
    int got = 0;
    for (int k = 0; k < samples; k++) {
      double a = 2 * Math.PI * k / samples;
      int f = p.floorNear(p.x0 + (int) Math.round(r * Math.cos(a)), p.z0 + (int) Math.round(r * Math.sin(a)),
          y, LAYER_WINDOW);
      if (f == NONE) continue;
      sum += f;
      got++;
    }
    if (got * 4 < samples * 3) return Double.NaN;
    return y - sum / got;
  }

  /**
   * Bounded 4-connected fill over the square: a neighbour is reachable by walking level, dropping
   * up to {@code maxDrop} blocks, or jumping 1 block with head room. Passes at {@code need}
   * columns, or on reaching the square's edge when {@code edgePasses}. Liquid surfaces count as
   * floor (swimmable). {@code maxDrop = 1} makes every edge two-way (walk back without digging).
   */
  static boolean walkable(Patch p, int y0, int need, int maxDrop, boolean edgePasses) {
    int[] visited = new int[SIDE * SIDE];
    Arrays.fill(visited, NONE);
    int[] queue = new int[SIDE * SIDE];
    int head = 0, tail = 0;
    visited[p.z0 * SIDE + p.x0] = y0;
    queue[tail++] = p.z0 * SIDE + p.x0;
    int count = 1;
    while (head < tail) {
      int cur = queue[head++];
      int x = cur % SIDE, z = cur / SIDE, y = visited[cur];
      for (int[] d : CARDINAL) {
        int nx = x + d[0], nz = z + d[1];
        if (nx < 0 || nz < 0 || nx >= SIDE || nz >= SIDE) continue;
        int ni = nz * SIDE + nx;
        if (visited[ni] != NONE) continue;
        int ny = stepTo(p, x, z, y, nx, nz, maxDrop);
        if (ny == NONE) continue;
        visited[ni] = ny;
        queue[tail++] = ni;
        if (++count >= need) return true;
        if (edgePasses && (nx == 0 || nz == 0 || nx == SIDE - 1 || nz == SIDE - 1)) return true;
      }
    }
    return false;
  }

  private static int stepTo(Patch p, int x, int z, int y, int nx, int nz, int maxDrop) {
    if (p.is(nx, y, nz, WALK) && p.is(nx, y + 1, nz, WALK)) {
      for (int k = 0; k <= maxDrop; k++) if (!p.is(nx, y - k - 1, nz, WALK)) return y - k;
      return NONE; // drop deeper than maxDrop
    }
    if (p.is(nx, y + 1, nz, WALK) && p.is(nx, y + 2, nz, WALK) && p.is(x, y + 2, z, WALK)) return y + 1;
    return NONE;
  }

  private static boolean standable(Patch p, int px, int y, int pz) {
    return !p.is(px, y - 1, pz, WALK) && p.is(px, y, pz, WALK) && p.is(px, y + 1, pz, WALK);
  }

  /** T1: ADR-119 mountainside rule on the centre floors of the 5x5 chunks. */
  private static boolean truthChunk(Patch p) {
    int[] f = new int[PATCH_CHUNKS * PATCH_CHUNKS];
    for (int cz = 0; cz < PATCH_CHUNKS; cz++) {
      for (int cx = 0; cx < PATCH_CHUNKS; cx++) f[cz * PATCH_CHUNKS + cx] = p.surface(cx * 16 + 7, cz * 16 + 7);
    }
    int y0 = f[2 * PATCH_CHUNKS + 2];
    if (y0 == NONE) return false;
    int steep = 0;
    for (int[] d : DIRS) {
      double step = 16.0 * Math.hypot(d[0], d[1]);
      for (int r = 1; r <= 2; r++) {
        int ny = f[(2 + d[1] * r) * PATCH_CHUNKS + 2 + d[0] * r];
        if (ny == NONE) continue;
        if (Math.abs(ny - y0) >= TAN30 * step * r) {
          steep++;
          break;
        }
      }
    }
    return steep >= MIN_STEEP_DIRS;
  }

  /** T2: the same rule centred on the landing column, top-down surface floors. */
  private static boolean truthSpot(Patch p, int y) {
    int steep = 0;
    for (int[] d : DIRS) {
      double step = 16.0 * Math.hypot(d[0], d[1]);
      for (int r = 1; r <= 2; r++) {
        int ny = p.surface(p.x0 + d[0] * 16 * r, p.z0 + d[1] * 16 * r);
        if (ny == NONE) continue;
        if (Math.abs(ny - y) >= TAN30 * step * r) {
          steep++;
          break;
        }
      }
    }
    return steep >= MIN_STEEP_DIRS;
  }

  /** T3: cliff edge, any top-down surface floor within 4 blocks differs by 5 or more. */
  private static boolean truthCliff(Patch p, int y) {
    for (int dz = -4; dz <= 4; dz++) {
      for (int dx = -4; dx <= 4; dx++) {
        int f = p.surface(p.x0 + dx, p.z0 + dz);
        if (f != NONE && Math.abs(f - y) >= 5) return true;
      }
    }
    return false;
  }

  private static List<int[]> listRegions() throws IOException {
    List<int[]> out = new ArrayList<>();
    try (var stream = Files.newDirectoryStream(REGION_DIR, "r.*.mca")) {
      for (Path file : stream) {
        Matcher m = REGION_FILE.matcher(file.getFileName().toString());
        if (m.matches()) out.add(new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
      }
    }
    out.sort((a, b) -> a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(a[0], b[0]));
    return out;
  }

  /** Region-local chunk coordinate that clips a 5x5 square: 0, 1, 30 or 31. */
  private static int band(Random rnd) {
    int v = rnd.nextInt(4);
    return v < 2 ? v : 28 + v;
  }

  /** Loads the 5x5 square; {@code decode} accumulates view and column-probe decode counts and nanos. */
  private static boolean loadPatch(Patch p, Map<Long, byte[]> cache, int gcx, int gcz, long[] decode)
      throws IOException {
    for (int dz = -2; dz <= 2; dz++) {
      for (int dx = -2; dx <= 2; dx++) {
        int cx = gcx + dx, cz = gcz + dz;
        int rx = Math.floorDiv(cx, 32), rz = Math.floorDiv(cz, 32);
        long key = ((long) rx << 32) ^ (rz & 0xffffffffL);
        byte[] bytes = cache.get(key);
        if (bytes == null) {
          Path f = REGION_DIR.resolve("r." + rx + "." + rz + ".mca");
          bytes = Files.isRegularFile(f) ? Files.readAllBytes(f) : new byte[0];
          cache.put(key, bytes);
        }
        int lcx = Math.floorMod(cx, 32), lcz = Math.floorMod(cz, 32);
        if (!AnvilReader.INSTANCE.isChunkGenerated(bytes, lcx, lcz)) return false;
        AnvilChunkView view;
        try {
          long s = System.nanoTime();
          view = AnvilReader.readChunkView(bytes, lcx, lcz);
          decode[1] += System.nanoTime() - s;
          decode[0]++;
          s = System.nanoTime();
          AnvilReader.readColumnProbe(bytes, lcx, lcz, MIN_Y, MAX_Y); // the pre-compute's per-chunk read
          decode[3] += System.nanoTime() - s;
          decode[2]++;
        } catch (IOException e) {
          return false; // corrupt chunk: square incomplete, landing skipped
        }
        if (view == null || view.sections().isEmpty()) return false;
        int top = Integer.MIN_VALUE;
        for (var s : view.sections()) top = Math.max(top, s.sectionY());
        int ci = (dz + 2) * PATCH_CHUNKS + dx + 2;
        p.views[ci] = view;
        p.topY[ci] = Math.min(MAX_Y, top * 16 + 15);
      }
    }
    return true;
  }

  private static void writeCsv(File csv, List<NamedCheck> checks, List<Landing> landings, List<boolean[]> verdicts,
      List<long[]> reads) throws IOException {
    StringBuilder sb = new StringBuilder("chunkX,chunkZ,localX,localZ,y,t1,t2,t3,position,measuredDirs,oversample");
    for (NamedCheck c : checks) sb.append(",\"").append(c.name).append("\",\"reads ").append(c.name).append('"');
    sb.append('\n');
    for (int i = 0; i < landings.size(); i++) {
      Landing l = landings.get(i);
      sb.append(l.gcx).append(',').append(l.gcz).append(',').append(l.lx).append(',').append(l.lz).append(',')
          .append(l.y).append(',')
          .append(l.t1 ? 1 : 0).append(',').append(l.t2 ? 1 : 0).append(',').append(l.t3 ? 1 : 0).append(',')
          .append(POS_NAMES[l.pos]).append(',').append(l.measured).append(',').append(l.extra ? 1 : 0);
      for (int c = 0; c < checks.size(); c++) {
        sb.append(',').append(verdicts.get(i)[c] ? 1 : 0).append(',').append(reads.get(i)[c]);
      }
      sb.append('\n');
    }
    Files.createDirectories(csv.toPath().getParent());
    Files.writeString(csv.toPath(), sb.toString(), StandardCharsets.UTF_8);
  }

  private static double pct(long a, long b) {
    return b == 0 ? 0 : 100.0 * a / b;
  }

  private static double mean(long[] v) {
    if (v.length == 0) return 0;
    double s = 0;
    for (long x : v) s += x;
    return s / v.length;
  }

  private static long p99(long[] v) {
    if (v.length == 0) return 0;
    long[] c = v.clone();
    Arrays.sort(c);
    return c[Math.min(c.length - 1, (int) Math.ceil(c.length * 0.99) - 1)];
  }
}
