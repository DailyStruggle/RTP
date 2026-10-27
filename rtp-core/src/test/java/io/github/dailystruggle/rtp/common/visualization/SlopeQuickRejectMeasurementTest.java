package io.github.dailystruggle.rtp.common.visualization;

import io.github.dailystruggle.rtp.anvil.AnvilChunkView;
import io.github.dailystruggle.rtp.anvil.AnvilPrefilter;
import io.github.dailystruggle.rtp.anvil.AnvilReader;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.AbstractVerticalAdjustor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Measures how many slope rejections (ADR-119 {@code slopeOk}) a chunk-level elevation table could
 * make before the chunk is loaded, and what the avoided loads cost.
 *
 * <p>Ground truth per chunk: the linear vert's 5 test columns, each at its top-down surface floor;
 * the chunk is slope-valid when any non-liquid column passes {@code slopeOk}. Liquid-only chunks
 * are excluded (the unsafe-block check rejects them anyway). Quick-reject classifiers only see what
 * a memory table would hold: one centre floor Y per chunk, or one per-chunk relief value.
 */
@Tag("slow")
class SlopeQuickRejectMeasurementTest {

  private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");
  private static final Path REGION_DIR = Path.of(System.getProperty("rtp.elevation.regionDir",
      System.getProperty("user.home", "")
          + "/Documents/GameServers/Minecraft/testServer/RTP-Paper/26.3"
          + "/world/dimensions/minecraft/overworld/region"));

  private static final int MIN_Y = -64;
  private static final int MAX_Y = 319;
  private static final int NONE = Integer.MIN_VALUE;
  private static final int[][] TEST_COLUMNS = {{7, 7}, {2, 2}, {12, 12}, {2, 12}, {12, 2}};
  /** {maxSlope, slopeRadius}; the first is the documented example config. */
  private static final int[][] CONFIGS = {{2, 2}, {1, 2}, {3, 4}};
  private static final int[] CONTOUR_EDGES = {MIN_Y, 62, 65, 72, 88, 112, 144, 192, MAX_Y + 1};

  /** Per land chunk: centre floor (liquid counts), in-chunk relief of the 5 columns, truth bits. */
  record Sample(int cx, int cz, int centreFloor, int relief, boolean[] slopeValid) {}

  @Test
  @DisplayName("ADR-119: share of slope failures a chunk elevation table rejects before load, and time saved")
  void measureQuickReject() throws IOException {
    Assumptions.assumeTrue(Files.isDirectory(REGION_DIR), "reference save not present");
    AnvilReader reader = AnvilReader.INSTANCE;
    List<Sample> samples = new ArrayList<>();
    long readNs = 0, parseNs = 0, slopeNs = 0, chunks = 0, liquidOnly = 0, regions = 0;

    try (var stream = Files.newDirectoryStream(REGION_DIR, "r.*.mca")) {
      for (Path file : stream) {
        Matcher m = REGION_FILE.matcher(file.getFileName().toString());
        if (!m.matches()) continue;
        int rx = Integer.parseInt(m.group(1));
        int rz = Integer.parseInt(m.group(2));
        long t0 = System.nanoTime();
        byte[] bytes = Files.readAllBytes(file);
        readNs += System.nanoTime() - t0;
        regions++;
        for (int cz = 0; cz < 32; cz++) {
          for (int cx = 0; cx < 32; cx++) {
            if (!reader.isChunkGenerated(bytes, cx, cz)) continue;
            AnvilChunkView view;
            long t1 = System.nanoTime();
            try {
              view = reader.readChunkView(bytes, cx, cz);
            } catch (IOException e) {
              continue; // corrupt chunk: no truth available, not part of the sample
            }
            long t2 = System.nanoTime();
            parseNs += t2 - t1;
            chunks++;

            int top = topY(view);
            int bottom = Math.max(MIN_Y, view.minHeight());
            int centre = NONE;
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            boolean[] valid = new boolean[CONFIGS.length];
            boolean anyLand = false;
            for (int[] col : TEST_COLUMNS) {
              int surface = surfaceFloor(view, col[0], col[1], top, bottom);
              if (surface == NONE) continue;
              if (col[0] == 7 && col[1] == 7) centre = surface;
              lo = Math.min(lo, surface);
              hi = Math.max(hi, surface);
              if (isLiquid(view.blockIdAt(col[0], surface - 1, col[1]))) continue;
              anyLand = true;
              AbstractVerticalAdjustor.FloorReader fr =
                  (lx, y, lz) -> !isAir(view.blockIdAt(lx, y - 1, lz)) && isAir(view.blockIdAt(lx, y, lz));
              for (int c = 0; c < CONFIGS.length; c++) {
                if (!valid[c] && AbstractVerticalAdjustor.slopeOk(
                    fr, col[0], surface, col[1], CONFIGS[c][1], CONFIGS[c][0], bottom + 1, top)) {
                  valid[c] = true;
                }
              }
            }
            slopeNs += System.nanoTime() - t2;
            if (centre == NONE) continue;
            if (!anyLand) {
              liquidOnly++;
              continue;
            }
            samples.add(new Sample(rx * 32 + cx, rz * 32 + cz, centre, hi - lo, valid));
          }
        }
      }
    }
    Assumptions.assumeTrue(!samples.isEmpty(), "reference save has no readable land chunks");

    // Memory table: centre floor per chunk, grid-indexed.
    int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
    for (Sample s : samples) {
      minX = Math.min(minX, s.cx);
      maxX = Math.max(maxX, s.cx);
      minZ = Math.min(minZ, s.cz);
      maxZ = Math.max(maxZ, s.cz);
    }
    int w = maxX - minX + 1, h = maxZ - minZ + 1;
    int[] grid = new int[w * h];
    Arrays.fill(grid, NONE);
    for (Sample s : samples) grid[(s.cz - minZ) * w + (s.cx - minX)] = s.centreFloor;

    // Neighbour max |dY| per sample, timed as the quick-reject lookup itself.
    int n = samples.size();
    int[] nbrDiff = new int[n];
    long t3 = System.nanoTime();
    for (int i = 0; i < n; i++) {
      Sample s = samples.get(i);
      int gx = s.cx - minX, gz = s.cz - minZ, y = s.centreFloor, d = -1;
      if (gx + 1 < w) d = diff(d, grid[gz * w + gx + 1], y);
      if (gx > 0) d = diff(d, grid[gz * w + gx - 1], y);
      if (gz + 1 < h) d = diff(d, grid[(gz + 1) * w + gx], y);
      if (gz > 0) d = diff(d, grid[(gz - 1) * w + gx], y);
      nbrDiff[i] = d; // -1 = no known neighbour, never rejected
    }
    double lookupNs = (System.nanoTime() - t3) / (double) n;
    int[] band8Jump = new int[n];
    int[] contourJump = new int[n];
    for (int i = 0; i < n; i++) {
      Sample s = samples.get(i);
      int gx = s.cx - minX, gz = s.cz - minZ;
      int b8 = Math.floorDiv(s.centreFloor - MIN_Y, 8), bc = contour(s.centreFloor);
      int j8 = -1, jc = -1;
      int[][] nb = {{gx + 1, gz}, {gx - 1, gz}, {gx, gz + 1}, {gx, gz - 1}};
      for (int[] p : nb) {
        if (p[0] < 0 || p[0] >= w || p[1] < 0 || p[1] >= h) continue;
        int y = grid[p[1] * w + p[0]];
        if (y == NONE) continue;
        j8 = Math.max(j8, Math.abs(Math.floorDiv(y - MIN_Y, 8) - b8));
        jc = Math.max(jc, Math.abs(contour(y) - bc));
      }
      band8Jump[i] = j8;
      contourJump[i] = jc;
    }

    double perChunkRead = readNs / (double) chunks, perChunkParse = parseNs / (double) chunks;
    double perChunkSlope = slopeNs / (double) chunks;
    System.out.printf("%n[DEBUG_LOG] === SLOPE QUICK-REJECT (ADR-119) ===%n");
    System.out.printf("[DEBUG_LOG] regions=%d chunks=%,d land=%,d liquid-only=%,d%n",
        regions, chunks, n, liquidOnly);
    System.out.printf("[DEBUG_LOG] cost/chunk: region read %.1f us | NBT parse %.1f us | 5-col surface+slope %.1f us | table lookup %.3f us%n",
        perChunkRead / 1e3, perChunkParse / 1e3, perChunkSlope / 1e3, lookupNs / 1e3);

    for (int c = 0; c < CONFIGS.length; c++) {
      long invalid = 0;
      for (Sample s : samples) if (!s.slopeValid[c]) invalid++;
      System.out.printf("[DEBUG_LOG] --- maxSlope=%d slopeRadius=%d: slope-invalid %,d of %,d land chunks (%.1f%%)%n",
          CONFIGS[c][0], CONFIGS[c][1], invalid, n, 100.0 * invalid / n);
      System.out.printf("[DEBUG_LOG] %-24s %8s %8s %8s %9s %9s%n",
          "classifier", "reject%", "recall", "falseRej", "loadsSaved", "precision");
      for (int t : new int[] {3, 5, 8, 12, 16, 24}) {
        final int tt = t;
        report("nbr |dY| >= " + t, samples, c, i -> nbrDiff[i] >= tt);
      }
      report("8-block band jump >= 1", samples, c, i -> band8Jump[i] >= 1);
      report("8-block band jump >= 2", samples, c, i -> band8Jump[i] >= 2);
      report("contour band jump >= 1", samples, c, i -> contourJump[i] >= 1);
      report("contour band jump >= 2", samples, c, i -> contourJump[i] >= 2);
      for (int t : new int[] {2, 4, 8}) {
        final int tt = t;
        report("in-chunk relief >= " + t, samples, c, i -> samples.get(i).relief >= tt);
      }
    }
  }

  /**
   * recall = slope failures caught / all slope failures; falseRej = valid chunks rejected / valid.
   * loadsSaved = 1 - (chunk loads per accepted placement with the filter / without), from the
   * sample frequencies: without = 1/P(valid), with = P(pass)/P(valid and pass).
   */
  private static void report(String name, List<Sample> samples, int c, java.util.function.IntPredicate reject) {
    long n = samples.size(), rej = 0, invalid = 0, caught = 0, valid = 0, validRej = 0;
    for (int i = 0; i < n; i++) {
      boolean r = reject.test(i);
      boolean v = samples.get(i).slopeValid[c];
      if (r) rej++;
      if (v) {
        valid++;
        if (r) validRej++;
      } else {
        invalid++;
        if (r) caught++;
      }
    }
    double loadsBase = n / (double) Math.max(1, valid);
    long pass = n - rej, validPass = valid - validRej;
    double loadsWith = validPass == 0 ? Double.POSITIVE_INFINITY : pass / (double) validPass;
    System.out.printf("[DEBUG_LOG] %-24s %7.1f%% %7.1f%% %7.1f%% %8.1f%% %8.1f%%%n", name,
        100.0 * rej / n, 100.0 * caught / Math.max(1, invalid), 100.0 * validRej / Math.max(1, valid),
        100.0 * (1 - loadsWith / loadsBase), 100.0 * caught / Math.max(1, rej));
  }

  private static int diff(int d, int other, int y) {
    return other == NONE ? d : Math.max(d, Math.abs(other - y));
  }

  private static int contour(int y) {
    for (int i = CONTOUR_EDGES.length - 2; i >= 0; i--) if (y >= CONTOUR_EDGES[i]) return i;
    return 0;
  }

  private static int topY(AnvilChunkView view) {
    int top = Integer.MIN_VALUE;
    for (var s : view.sections()) top = Math.max(top, s.sectionY());
    return top == Integer.MIN_VALUE ? NONE : Math.min(MAX_Y, top * 16 + 15);
  }

  /** Feet-Y one above the highest non-air block, or NONE. */
  private static int surfaceFloor(AnvilChunkView view, int lx, int lz, int top, int bottom) {
    if (top == NONE) return NONE;
    for (int y = top; y >= bottom; y--) {
      if (!isAir(view.blockIdAt(lx, y, lz))) return y + 1;
    }
    return NONE;
  }

  private static boolean isAir(String raw) {
    if (raw == null) return true; // absent section
    String r = AnvilPrefilter.DEFAULT_RECONCILER.apply(raw);
    return "AIR".equals(r) || "CAVE_AIR".equals(r) || "VOID_AIR".equals(r);
  }

  private static boolean isLiquid(String raw) {
    if (raw == null) return false;
    String r = AnvilPrefilter.DEFAULT_RECONCILER.apply(raw);
    return "WATER".equals(r) || "LAVA".equals(r);
  }
}
