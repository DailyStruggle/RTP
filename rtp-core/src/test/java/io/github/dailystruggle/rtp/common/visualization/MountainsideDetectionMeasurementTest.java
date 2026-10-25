package io.github.dailystruggle.rtp.common.visualization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.anvil.AnvilChunkView;
import io.github.dailystruggle.rtp.anvil.AnvilPrefilter;
import io.github.dailystruggle.rtp.anvil.AnvilReader;
import io.github.dailystruggle.rtp.common.tools.ChartOutputHelper;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Measures whether terrain-scale mountainsides (ADR-119) can be detected before load from a sparse
 * set of known chunk floors, the only elevation data RTP holds in memory.
 *
 * <p>Mountainside: in at least half of the 8 compass directions, the floor 1 or 2 chunks away
 * (16-32 blocks; diagonals sqrt(2) further) differs by a slope of at least 30 degrees. The 30
 * degree cutoff on chunk-centre samples catches steeper faces whose peak sits between samples.
 * Plateaus and basins are flat at chunk scale and stay below the cutoff; only their rims qualify.
 *
 * <p>Truth uses every chunk's top-surface centre floor (liquid surface counts, so water is flat).
 * Prediction hides all but a seeded random sample, rebuilds the rest by inverse-distance weighting
 * and applies the same rule. Unknown chunks are never rejected (S-001: the landing-spot check
 * stays authoritative; this layer can only skip candidates).
 */
@Tag("slow")
public class MountainsideDetectionMeasurementTest {

  private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");
  private static final Path REGION_DIR = Path.of(System.getProperty("rtp.elevation.regionDir",
      System.getProperty("user.home", "")
          + "/Documents/GameServers/Minecraft/testServer/RTP-Paper/26.3"
          + "/world/dimensions/minecraft/overworld/region"));

  private static final int MIN_Y = -64;
  private static final int MAX_Y = 319;
  private static final int NONE = Integer.MIN_VALUE;
  private static final int[][] DIRS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
  private static final int MIN_STEEP_DIRS = 4;
  private static final double TRUTH_DEG = 30.0;
  private static final double[] SAMPLE_FRACTIONS = {0.05, 0.10, 0.25};
  private static final double[] PREDICT_DEG = {20.0, 25.0, 30.0};
  private static final int IDW_RADIUS = 8; // chunks
  private static final int[] PLANE_RADII = {2, 3, 4}; // chunks
  private static final int IMAGE_PLANE_RADIUS = 3;
  private static final double IMAGE_PLANE_DEG = 25.0;
  private static final long SEED = 119L;
  private static final int[] CONTOUR_EDGES = {MIN_Y, 62, 65, 72, 88, 112, 144, 192, MAX_Y + 1};
  private static final Color[] CONTOUR_COLORS = {
      new Color(20, 50, 120), new Color(60, 120, 200), new Color(110, 170, 90),
      new Color(150, 190, 100), new Color(200, 190, 110), new Color(170, 120, 70),
      new Color(140, 130, 125), new Color(240, 240, 245)};

  /** Biomes whose generator builds terrain-scale relief (vanilla mountain/windswept families). */
  private static final Set<String> MOUNTAIN_BIOMES = Set.of("jagged_peaks", "frozen_peaks", "stony_peaks",
      "snowy_slopes", "grove", "meadow", "cherry_grove", "windswept_hills", "windswept_gravelly_hills",
      "windswept_forest", "windswept_savanna");
  private static final Set<String> PEAK_BIOMES = Set.of("jagged_peaks", "frozen_peaks", "stony_peaks",
      "snowy_slopes");

  /** biome[i] indexes biomeNames; -1 when the chunk has no biome container at its floor. */
  record Grid(int[] y, int[] biome, List<String> biomeNames, int w, int h, int originX, int originZ) {}

  @Test
  @DisplayName("ADR-119: mountainside (>=30 deg in >=4 of 8 directions) detection from sparse chunk floors")
  public void measureMountainsideDetection() throws Exception {
    Assumptions.assumeTrue(Files.isDirectory(REGION_DIR), "reference save not present");
    long t0 = System.nanoTime();
    Grid truthGrid = scan();
    int n = 0;
    for (int v : truthGrid.y) if (v != NONE) n++;
    Assumptions.assumeTrue(n > 0, "reference save has no readable chunks");
    System.out.printf("%n[DEBUG_LOG] === MOUNTAINSIDE DETECTION (ADR-119) ===%n");
    System.out.printf("[DEBUG_LOG] chunks=%,d grid=%dx%d scan=%.1fs%n",
        n, truthGrid.w, truthGrid.h, (System.nanoTime() - t0) / 1e9);

    int[] truthSteep = steepCounts(truthGrid.y, truthGrid.w, truthGrid.h, TRUTH_DEG);
    boolean[] truth = threshold(truthSteep);
    int[] steep45 = steepCounts(truthGrid.y, truthGrid.w, truthGrid.h, 45.0);
    long t30 = count(truth), t45 = count(threshold(steep45));
    System.out.printf("[DEBUG_LOG] truth: >=30deg in >=%d/8 dirs = %,d (%.2f%%); same rule at 45deg = %,d (%.2f%%)%n",
        MIN_STEEP_DIRS, t30, 100.0 * t30 / n, t45, 100.0 * t45 / n);
    long[] hist = new long[9];
    for (int i = 0; i < truthSteep.length; i++) if (truthGrid.y[i] != NONE) hist[truthSteep[i]]++;
    assertEquals(n, Arrays.stream(hist).sum(), "histogram must cover every readable chunk");
    assertTrue(t45 <= t30, "45-degree mountainsides must be a subset of 30-degree mountainsides");
    StringBuilder sb = new StringBuilder();
    for (int k = 0; k <= 8; k++) sb.append(String.format(" %d:%.1f%%", k, 100.0 * hist[k] / n));
    System.out.printf("[DEBUG_LOG] steep-direction histogram (truth, 30deg):%s%n", sb);

    System.out.printf("[DEBUG_LOG] %-22s %8s %8s %9s %9s %9s %10s %10s%n", "sample / predict",
        "reject%", "recall", "precision", "falseRej", "loadsSaved", "FP flat0", "FP 1-3dir");
    boolean[] idwImage = null, planeImage = null;
    for (double f : SAMPLE_FRACTIONS) {
      boolean[] sampled = sample(truthGrid.y, f);
      int[] rebuilt = idw(truthGrid.y, sampled, truthGrid.w, truthGrid.h);
      for (double deg : PREDICT_DEG) {
        boolean[] pred = threshold(steepCounts(rebuilt, truthGrid.w, truthGrid.h, deg));
        report(String.format("%2.0f%% idw / %2.0fdeg", f * 100, deg), truthGrid.y, truth, truthSteep, pred);
        if (f == 0.10 && deg == TRUTH_DEG) idwImage = pred;
      }
      for (int r : PLANE_RADII) {
        double[] grad = planeGradientDeg(truthGrid.y, sampled, truthGrid.w, truthGrid.h, r);
        for (double deg : PREDICT_DEG) {
          boolean[] pred = new boolean[grad.length];
          for (int i = 0; i < grad.length; i++) pred[i] = grad[i] >= deg; // NaN = unknown, never rejected
          report(String.format("%2.0f%% plane r%d / %2.0fdeg", f * 100, r, deg), truthGrid.y, truth, truthSteep, pred);
          if (f == 0.10 && r == IMAGE_PLANE_RADIUS && deg == IMAGE_PLANE_DEG) planeImage = pred;
        }
      }
    }

    assertNotNull(idwImage, "expected IDW comparison image");
    assertNotNull(planeImage, "expected plane-fit comparison image");
    boolean[][] biomePanels = biomeComparison(truthGrid, truth, truthSteep);
    BufferedImage bimg = render(truthGrid, truth, biomePanels[0], biomePanels[1], new String[] {
        "Truth: >=30deg toward >=4/8 neighbours, all chunks",
        "Chunk biome is mountain/windswept family",
        "Land biome transition into mountain family (8-nbr)"});
    boolean bwritten = ChartOutputHelper.writeReportOnly(bimg, "adr-119", "adr-119-biome-mountainside-map.png");
    System.out.printf("[DEBUG_LOG] Biome comparison image %s: %s (%,dx%,d)%n", bwritten ? "written" : "unchanged",
        ChartOutputHelper.getReportFile("adr-119", "adr-119-biome-mountainside-map.png").getAbsolutePath(),
        bimg.getWidth(), bimg.getHeight());

    BufferedImage img = render(truthGrid, truth, idwImage, planeImage, new String[] {
        "Truth: >=30deg toward >=4/8 neighbours, all chunks",
        "10% sample + IDW (r=" + IDW_RADIUS + "), same rule",
        String.format("10%% sample, plane fit r=%d, >=%.0fdeg", IMAGE_PLANE_RADIUS, IMAGE_PLANE_DEG)});
    boolean written = ChartOutputHelper.writeReportOnly(img, "adr-119", "adr-119-mountainside-map.png");
    System.out.printf("[DEBUG_LOG] Mountainside image %s: %s (%,dx%,d)%n", written ? "written" : "unchanged",
        ChartOutputHelper.getReportFile("adr-119", "adr-119-mountainside-map.png").getAbsolutePath(),
        img.getWidth(), img.getHeight());
  }

  /** Centre-column top-surface floor per chunk, laid out on a grid spanning all region files. */
  private static Grid scan() throws IOException {
    List<Path> files = new ArrayList<>();
    int minRx = Integer.MAX_VALUE, maxRx = Integer.MIN_VALUE, minRz = Integer.MAX_VALUE, maxRz = Integer.MIN_VALUE;
    try (var stream = Files.newDirectoryStream(REGION_DIR, "r.*.mca")) {
      for (Path file : stream) {
        Matcher m = REGION_FILE.matcher(file.getFileName().toString());
        if (!m.matches()) continue;
        int rx = Integer.parseInt(m.group(1)), rz = Integer.parseInt(m.group(2));
        minRx = Math.min(minRx, rx);
        maxRx = Math.max(maxRx, rx);
        minRz = Math.min(minRz, rz);
        maxRz = Math.max(maxRz, rz);
        files.add(file);
      }
    }
    if (files.isEmpty()) return new Grid(new int[0], new int[0], List.of(), 0, 0, 0, 0);
    int w = (maxRx - minRx + 1) * 32, h = (maxRz - minRz + 1) * 32;
    int[] y = new int[w * h];
    Arrays.fill(y, NONE);
    int[] biome = new int[w * h];
    Arrays.fill(biome, -1);
    List<String> biomeNames = new ArrayList<>();
    Map<String, Integer> biomeIndex = new HashMap<>();
    AnvilReader reader = AnvilReader.INSTANCE;
    for (Path file : files) {
      Matcher m = REGION_FILE.matcher(file.getFileName().toString());
      if (!m.matches()) continue;
      int rx = Integer.parseInt(m.group(1)), rz = Integer.parseInt(m.group(2));
      byte[] bytes = Files.readAllBytes(file);
      for (int cz = 0; cz < 32; cz++) {
        for (int cx = 0; cx < 32; cx++) {
          if (!reader.isChunkGenerated(bytes, cx, cz)) continue;
          AnvilChunkView view;
          try {
            view = reader.readChunkView(bytes, cx, cz);
          } catch (IOException e) {
            continue; // corrupt chunk: stays unknown, never classified
          }
          if (view == null) continue;
          int floor = surfaceFloor(view, 7, 7);
          if (floor == NONE) continue;
          int gx = (rx - minRx) * 32 + cx, gz = (rz - minRz) * 32 + cz;
          y[gz * w + gx] = floor;
          String b = view.getBiomeAt(7, floor - 1, 7);
          if (b != null) {
            String key = b.startsWith("minecraft:") ? b.substring("minecraft:".length()) : b;
            biome[gz * w + gx] = biomeIndex.computeIfAbsent(key, k -> {
              biomeNames.add(k);
              return biomeNames.size() - 1;
            });
          }
        }
      }
    }
    return new Grid(y, biome, biomeNames, w, h, minRx * 32, minRz * 32);
  }

  private static boolean isWaterBiome(String b) {
    return b.contains("ocean") || b.contains("river") || b.endsWith("beach");
  }

  /**
   * Scores biome-only predictors (full biome knowledge, no elevation) against the elevation truth,
   * plus a per-biome mountainside rate table. Returns {mountain-family, transition-into-mountain}
   * predictions for the image.
   */
  private static boolean[][] biomeComparison(Grid g, boolean[] truth, int[] truthSteep) {
    int nb = g.biomeNames.size();
    boolean[] mountain = new boolean[nb], peak = new boolean[nb], water = new boolean[nb];
    for (int k = 0; k < nb; k++) {
      String b = g.biomeNames.get(k);
      mountain[k] = MOUNTAIN_BIOMES.contains(b);
      peak[k] = PEAK_BIOMES.contains(b);
      water[k] = isWaterBiome(b);
    }
    long withBiome = 0, totalTruth = 0;
    long[] chunks = new long[nb], ms = new long[nb];
    for (int i = 0; i < g.y.length; i++) {
      if (g.y[i] == NONE) continue;
      if (truth[i]) totalTruth++;
      int b = g.biome[i];
      if (b < 0) continue;
      withBiome++;
      chunks[b]++;
      if (truth[i]) ms[b]++;
    }
    System.out.printf("%n[DEBUG_LOG] === BIOME vs MOUNTAINSIDE (full biome knowledge) ===%n");
    System.out.printf("[DEBUG_LOG] chunks with floor biome=%,d distinct biomes=%d mountainsides=%,d%n",
        withBiome, nb, totalTruth);
    Integer[] order = new Integer[nb];
    for (int k = 0; k < nb; k++) order[k] = k;
    Arrays.sort(order, (a, b) -> Long.compare(chunks[b], chunks[a]));
    System.out.printf("[DEBUG_LOG] %-26s %9s %7s %13s %15s%n", "biome", "chunks", "share", "mountainside%",
        "share of all MS");
    for (int k : order) {
      if (chunks[k] == 0) continue;
      System.out.printf("[DEBUG_LOG] %-26s %,9d %6.2f%% %12.1f%% %14.1f%%%n", g.biomeNames.get(k), chunks[k],
          100.0 * chunks[k] / withBiome, 100.0 * ms[k] / chunks[k], 100.0 * ms[k] / Math.max(1, totalTruth));
    }

    int n = g.y.length;
    boolean[] selfMountain = new boolean[n], selfPeak = new boolean[n], nearMountain = new boolean[n];
    boolean[] landTrans1 = new boolean[n], landTrans3 = new boolean[n], intoMountain = new boolean[n];
    boolean[] mountainEdge = new boolean[n], mountainOrTrans = new boolean[n];
    for (int gz = 0; gz < g.h; gz++) {
      for (int gx = 0; gx < g.w; gx++) {
        int i = gz * g.w + gx, b = g.biome[i];
        if (g.y[i] == NONE || b < 0) continue; // unknown: never rejected
        selfMountain[i] = mountain[b];
        selfPeak[i] = peak[b];
        int landDiff = 0;
        boolean nbrMountain = false, nbrNonMountain = false;
        for (int[] d : DIRS) {
          int nx = gx + d[0], nz = gz + d[1];
          if (nx < 0 || nz < 0 || nx >= g.w || nz >= g.h) continue;
          int nbB = g.biome[nz * g.w + nx];
          if (nbB < 0) continue;
          if (mountain[nbB]) nbrMountain = true;
          else nbrNonMountain = true;
          if (!water[b] && !water[nbB] && nbB != b) landDiff++;
        }
        nearMountain[i] = mountain[b] || nbrMountain;
        landTrans1[i] = landDiff >= 1;
        landTrans3[i] = landDiff >= 3;
        intoMountain[i] = !water[b] && landDiff >= 1 && (mountain[b] ? nbrNonMountain : nbrMountain);
        mountainEdge[i] = mountain[b] ? nbrNonMountain : nbrMountain;
        mountainOrTrans[i] = mountain[b] || intoMountain[i];
      }
    }
    System.out.printf("[DEBUG_LOG] %-30s %8s %8s %9s %9s %9s %10s %10s%n", "biome predictor",
        "reject%", "recall", "precision", "falseRej", "loadsSaved", "FP flat0", "FP 1-3dir");
    report("self peak/slopes", g.y, truth, truthSteep, selfPeak, 30);
    report("self mountain family", g.y, truth, truthSteep, selfMountain, 30);
    report("self or 8-nbr mountain", g.y, truth, truthSteep, nearMountain, 30);
    report("land transition >=1 nbr", g.y, truth, truthSteep, landTrans1, 30);
    report("land transition >=3 nbr", g.y, truth, truthSteep, landTrans3, 30);
    report("transition into mountain", g.y, truth, truthSteep, intoMountain, 30);
    report("mountain family boundary", g.y, truth, truthSteep, mountainEdge, 30);
    report("mountain or into-mountain", g.y, truth, truthSteep, mountainOrTrans, 30);
    return new boolean[][] {selfMountain, intoMountain};
  }

  /** Per cell, directions whose 1- or 2-chunk neighbour sits at >= deg slope; -1 for unknown cells. */
  private static int[] steepCounts(int[] y, int w, int h, double deg) {
    double tan = Math.tan(Math.toRadians(deg));
    int[] out = new int[y.length];
    for (int gz = 0; gz < h; gz++) {
      for (int gx = 0; gx < w; gx++) {
        int i = gz * w + gx;
        if (y[i] == NONE) {
          out[i] = -1;
          continue;
        }
        int steep = 0;
        for (int[] d : DIRS) {
          double step = 16.0 * Math.hypot(d[0], d[1]);
          for (int r = 1; r <= 2; r++) {
            int nx = gx + d[0] * r, nz = gz + d[1] * r;
            if (nx < 0 || nz < 0 || nx >= w || nz >= h) break;
            int ny = y[nz * w + nx];
            if (ny == NONE) continue;
            if (Math.abs(ny - y[i]) >= tan * step * r) {
              steep++;
              break;
            }
          }
        }
        out[i] = steep;
      }
    }
    return out;
  }

  private static boolean[] threshold(int[] steep) {
    boolean[] out = new boolean[steep.length];
    for (int i = 0; i < steep.length; i++) out[i] = steep[i] >= MIN_STEEP_DIRS;
    return out;
  }

  private static boolean[] sample(int[] y, double fraction) {
    Random rnd = new Random(SEED);
    boolean[] s = new boolean[y.length];
    for (int i = 0; i < y.length; i++) s[i] = y[i] != NONE && rnd.nextDouble() < fraction;
    return s;
  }

  /** Sampled cells keep their floor; other known cells get 1/d^2 IDW within IDW_RADIUS, else NONE. */
  private static int[] idw(int[] y, boolean[] sampled, int w, int h) {
    int[] out = new int[y.length];
    Arrays.fill(out, NONE);
    int r2 = IDW_RADIUS * IDW_RADIUS;
    for (int gz = 0; gz < h; gz++) {
      for (int gx = 0; gx < w; gx++) {
        int i = gz * w + gx;
        if (y[i] == NONE) continue;
        if (sampled[i]) {
          out[i] = y[i];
          continue;
        }
        double sw = 0, sy = 0;
        for (int dz = -IDW_RADIUS; dz <= IDW_RADIUS; dz++) {
          int nz = gz + dz;
          if (nz < 0 || nz >= h) continue;
          for (int dx = -IDW_RADIUS; dx <= IDW_RADIUS; dx++) {
            int nx = gx + dx, d2 = dx * dx + dz * dz;
            if (nx < 0 || nx >= w || d2 > r2) continue;
            int j = nz * w + nx;
            if (!sampled[j]) continue;
            double wt = 1.0 / d2;
            sw += wt;
            sy += wt * y[j];
          }
        }
        if (sw > 0) out[i] = (int) Math.round(sy / sw);
      }
    }
    return out;
  }

  /**
   * Least-squares plane through the sampled floors within r chunks of each known cell (no
   * interpolation); returns the plane's slope angle in degrees, NaN when fewer than 4 samples.
   */
  private static double[] planeGradientDeg(int[] y, boolean[] sampled, int w, int h, int r) {
    double[] out = new double[y.length];
    Arrays.fill(out, Double.NaN);
    int r2 = r * r;
    for (int gz = 0; gz < h; gz++) {
      for (int gx = 0; gx < w; gx++) {
        int i = gz * w + gx;
        if (y[i] == NONE) continue;
        int n = 0;
        double sx = 0, sz = 0, sy = 0, sxx = 0, szz = 0, sxz = 0, sxy = 0, szy = 0;
        for (int dz = -r; dz <= r; dz++) {
          int nz = gz + dz;
          if (nz < 0 || nz >= h) continue;
          for (int dx = -r; dx <= r; dx++) {
            int nx = gx + dx;
            if (nx < 0 || nx >= w || dx * dx + dz * dz > r2) continue;
            int j = nz * w + nx;
            if (!sampled[j]) continue;
            double v = y[j];
            n++;
            sx += dx;
            sz += dz;
            sy += v;
            sxx += dx * dx;
            szz += dz * dz;
            sxz += dx * dz;
            sxy += dx * v;
            szy += dz * v;
          }
        }
        if (n < 4) continue;
        // Centred normal equations for y = a + b*dx + c*dz.
        double cxx = sxx - sx * sx / n, czz = szz - sz * sz / n, cxz = sxz - sx * sz / n;
        double cxy = sxy - sx * sy / n, czy = szy - sz * sy / n;
        double det = cxx * czz - cxz * cxz;
        if (Math.abs(det) < 1e-9) continue; // collinear samples
        double b = (cxy * czz - czy * cxz) / det, c = (czy * cxx - cxy * cxz) / det;
        out[i] = Math.toDegrees(Math.atan(Math.hypot(b, c) / 16.0));
      }
    }
    return out;
  }

  /**
   * recall = mountainsides caught / all; falseRej = non-mountainside rejected / non-mountainside.
   * loadsSaved = 1 - (loads per accepted placement with filter / without); accepted = not
   * mountainside. FP columns split false rejects by how many truth directions were steep.
   */
  private static void report(String name, int[] y, boolean[] truth, int[] truthSteep, boolean[] pred) {
    report(name, y, truth, truthSteep, pred, 22);
  }

  private static void report(String name, int[] y, boolean[] truth, int[] truthSteep, boolean[] pred, int nameWidth) {
    long n = 0, rej = 0, pos = 0, caught = 0, neg = 0, negRej = 0, fpFlat = 0, fpNear = 0;
    for (int i = 0; i < y.length; i++) {
      if (y[i] == NONE) continue;
      n++;
      if (pred[i]) rej++;
      if (truth[i]) {
        pos++;
        if (pred[i]) caught++;
      } else {
        neg++;
        if (pred[i]) {
          negRej++;
          if (truthSteep[i] == 0) fpFlat++;
          else fpNear++;
        }
      }
    }
    double loadsBase = n / (double) Math.max(1, neg);
    double loadsWith = (neg - negRej) == 0 ? Double.POSITIVE_INFINITY : (n - rej) / (double) (neg - negRej);
    System.out.printf("[DEBUG_LOG] %-" + nameWidth + "s %7.2f%% %7.1f%% %8.1f%% %8.2f%% %8.2f%% %9.1f%% %9.1f%%%n", name,
        100.0 * rej / n, 100.0 * caught / Math.max(1, pos), 100.0 * caught / Math.max(1, rej),
        100.0 * negRej / Math.max(1, neg), 100.0 * (1 - loadsWith / loadsBase),
        100.0 * fpFlat / Math.max(1, negRej), 100.0 * fpNear / Math.max(1, negRej));
  }

  private static BufferedImage render(Grid g, boolean[] truth, boolean[] idwPred, boolean[] planePred,
      String[] titles) {
    int scale = Math.max(1, Math.min(1100 / Math.max(1, g.w), 1100 / Math.max(1, g.h)));
    int pw = g.w * scale, ph = g.h * scale, gap = 20, header = 40, footer = 70;
    BufferedImage img = new BufferedImage(pw * 3 + gap * 4, ph + header + footer, BufferedImage.TYPE_INT_RGB);
    Graphics2D gr = img.createGraphics();
    gr.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    gr.setColor(new Color(24, 24, 28));
    gr.fillRect(0, 0, img.getWidth(), img.getHeight());
    Color tp = new Color(230, 30, 30), fp = new Color(255, 170, 0), fn = new Color(0, 220, 255);
    for (int panel = 0; panel < 3; panel++) {
      int ox = gap + panel * (pw + gap);
      boolean[] pred = panel == 1 ? idwPred : planePred;
      for (int gz = 0; gz < g.h; gz++) {
        for (int gx = 0; gx < g.w; gx++) {
          int i = gz * g.w + gx;
          if (g.y[i] == NONE) continue;
          Color c = dim(CONTOUR_COLORS[contour(g.y[i])]);
          if (panel == 0 && truth[i]) c = tp;
          if (panel > 0 && pred != null) {
            if (truth[i] && pred[i]) c = tp;
            else if (pred[i]) c = fp;
            else if (truth[i]) c = fn;
          }
          gr.setColor(c);
          gr.fillRect(ox + gx * scale, header + gz * scale, scale, scale);
        }
      }
    }
    gr.setColor(Color.WHITE);
    gr.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
    for (int t = 0; t < 3; t++) gr.drawString(titles[t], gap + t * (pw + gap), 26);
    gr.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
    int ly = header + ph + 28;
    legend(gr, gap, ly, tp, "mountainside (caught)");
    legend(gr, gap + 230, ly, fp, "false reject");
    legend(gr, gap + 400, ly, fn, "missed");
    gr.setColor(Color.LIGHT_GRAY);
    gr.drawString("Background: contour bands (dimmed). Chunk x " + g.originX + ".." + (g.originX + g.w - 1)
        + ", z " + g.originZ + ".." + (g.originZ + g.h - 1) + ", " + scale + " px/chunk", gap, ly + 26);
    gr.dispose();
    return img;
  }

  private static void legend(Graphics2D gr, int x, int y, Color c, String label) {
    gr.setColor(c);
    gr.fillRect(x, y - 12, 14, 14);
    gr.setColor(Color.WHITE);
    gr.drawString(label, x + 20, y);
  }

  private static Color dim(Color c) {
    return new Color(c.getRed() * 3 / 5, c.getGreen() * 3 / 5, c.getBlue() * 3 / 5);
  }

  private static long count(boolean[] b) {
    long c = 0;
    for (boolean v : b) if (v) c++;
    return c;
  }

  private static int contour(int y) {
    for (int i = CONTOUR_EDGES.length - 2; i >= 0; i--) if (y >= CONTOUR_EDGES[i]) return i;
    return 0;
  }

  /** Feet-Y one above the highest non-air block in the column, or NONE. */
  private static int surfaceFloor(AnvilChunkView view, int lx, int lz) {
    int top = Integer.MIN_VALUE;
    for (var s : view.sections()) top = Math.max(top, s.sectionY());
    if (top == Integer.MIN_VALUE) return NONE;
    int bottom = Math.max(MIN_Y, view.minHeight());
    for (int y = Math.min(MAX_Y, top * 16 + 15); y >= bottom; y--) {
      if (!isPassable(view.blockIdAt(lx, y, lz))) return y + 1;
    }
    return NONE;
  }

  /** Air plus tree canopy and ground cover, so forests read as the ground under them. */
  private static boolean isPassable(String raw) {
    if (raw == null) return true; // absent section
    String r = AnvilPrefilter.DEFAULT_RECONCILER.apply(raw);
    return "AIR".equals(r) || "CAVE_AIR".equals(r) || "VOID_AIR".equals(r)
        || r.endsWith("_LEAVES") || r.endsWith("_LOG") || r.endsWith("_WOOD") || r.endsWith("_STEM")
        || r.endsWith("MUSHROOM_BLOCK") || "VINE".equals(r) || "SHORT_GRASS".equals(r)
        || "TALL_GRASS".equals(r) || "SNOW".equals(r) || "FERN".equals(r) || "LARGE_FERN".equals(r);
  }
}
