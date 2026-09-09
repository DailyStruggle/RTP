package io.github.dailystruggle.rtp.common.benchmark;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.CircleOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

public class CircleComparisonBenchmarkTest {

  private static final int R = 256;
  private static final int CR = 32;

  @Test
  public void runCircleComparison() throws Exception {
    Path regionDir = Path.of("C:\\GameServers\\Minecraft\\testServer\\RTP-Folia\\26.1\\world\\dimensions\\minecraft\\overworld\\region");
    if (!java.nio.file.Files.isDirectory(regionDir)) {
      regionDir = Path.of("testdata-world/overworld/region");
    }
    System.out.println("[DEBUG_LOG] Loading region files from: " + regionDir);
    RealWorldVerdictMask mask = RealWorldVerdictMask.load(regionDir, RealWorldVerdictMask.SHIPPED_UNSAFE_BLOCKS, 256);
    System.out.println("[DEBUG_LOG] Loaded " + mask.regionFilesRead() + " region files, usable share: " + mask.usableShareOfGenerated());
    System.out.println("[DEBUG_LOG] Inscribed radius of save: " + mask.inscribedRadius() + " chunks");

    // Diagnostic of the circular area
    long cntUsable = 0, cntWater = 0, cntLava = 0, cntOther = 0, cntUngenerated = 0, cntIsolated = 0, totalCircle = 0;
    for (int cx = -R; cx <= R; cx++) {
      for (int cz = -R; cz <= R; cz++) {
        long dSq = (long) cx * cx + (long) cz * cz;
        if (dSq > (long) R * R || dSq < (long) CR * CR) continue;
        totalCircle++;
        RealWorldVerdictMask.Cell c = mask.cellAt(cx, cz);
        switch (c) {
          case USABLE -> cntUsable++;
          case WATER -> cntWater++;
          case LAVA -> cntLava++;
          case OTHER_UNSAFE -> cntOther++;
          case UNGENERATED -> cntUngenerated++;
          case ISOLATED -> cntIsolated++;
        }
      }
    }
    System.out.printf("[DEBUG_LOG] Circle Area Breakdown (total %d chunks):%n", totalCircle);
    System.out.printf("  USABLE:      %d (%.2f%%)%n", cntUsable, 100.0 * cntUsable / totalCircle);
    System.out.printf("  WATER:       %d (%.2f%%)%n", cntWater, 100.0 * cntWater / totalCircle);
    System.out.printf("  LAVA:        %d (%.2f%%)%n", cntLava, 100.0 * cntLava / totalCircle);
    System.out.printf("  OTHER:       %d (%.2f%%)%n", cntOther, 100.0 * cntOther / totalCircle);
    System.out.printf("  ISOLATED:    %d (%.2f%%)%n", cntIsolated, 100.0 * cntIsolated / totalCircle);
    System.out.printf("  UNGENERATED: %d (%.2f%%)%n", cntUngenerated, 100.0 * cntUngenerated / totalCircle);

    // 1. Classic Circle (res = 3, default)
    System.out.println("[DEBUG_LOG] Simulating Classic Circle (res = 3)...");
    Circle classicCircle = new Circle("CLASSIC_CIRCLE");
    classicCircle.set(GenericMemoryShapeParams.radius, (long) R);
    classicCircle.set(GenericMemoryShapeParams.centerRadius, (long) CR);
    classicCircle.set(GenericMemoryShapeParams.centerX, 0L);
    classicCircle.set(GenericMemoryShapeParams.centerZ, 0L);
    ShapeResult resClassic = evaluateShape(classicCircle, mask, 3L);

    // 2. Optimized Circle (res = 16)
    System.out.println("[DEBUG_LOG] Simulating Optimized Circle (res = 16)...");
    CircleOptimizedDualLayer optCircle16 = new CircleOptimizedDualLayer("OPT_CIRCLE_16", 32);
    optCircle16.set(GenericMemoryShapeParams.radius, (long) R);
    optCircle16.set(GenericMemoryShapeParams.centerRadius, (long) CR);
    optCircle16.set(GenericMemoryShapeParams.centerX, 0L);
    optCircle16.set(GenericMemoryShapeParams.centerZ, 0L);
    ShapeResult resOpt16 = evaluateShape(optCircle16, mask, 16L);

    // 3. Optimized Circle (res = 32)
    System.out.println("[DEBUG_LOG] Simulating Optimized Circle (res = 32)...");
    CircleOptimizedDualLayer optCircle32 = new CircleOptimizedDualLayer("OPT_CIRCLE_32", 32);
    optCircle32.set(GenericMemoryShapeParams.radius, (long) R);
    optCircle32.set(GenericMemoryShapeParams.centerRadius, (long) CR);
    optCircle32.set(GenericMemoryShapeParams.centerX, 0L);
    optCircle32.set(GenericMemoryShapeParams.centerZ, 0L);
    ShapeResult resOpt32 = evaluateShape(optCircle32, mask, 32L);

    // Render PNGs
    File outDir = new File("C:\\GameServers\\Minecraft\\testServer\\RTP-Folia\\26.1\\plugins\\RTP\\database\\regionData\\debug");
    if (!outDir.exists()) outDir.mkdirs();

    ShapeResult resTruth = buildTruthResult(mask);

    renderPNG(resClassic, new File(outDir, "simulation_circle_classic_res3.png"), "Classic Circle (Polar Spiral, res = 3)", 3);
    renderPNG(resOpt16, new File(outDir, "simulation_circle_optimized_res16.png"), "Optimized Circle (Dual-Layer Square, res = 16)", 16);
    renderPNG(resOpt32, new File(outDir, "simulation_circle_optimized_res32.png"), "Optimized Circle (Dual-Layer Square, res = 32)", 32);

    renderComparisonPNG(resTruth, resClassic, resOpt16, resOpt32, new File(outDir, "simulation_circle_comparison_side_by_side.png"));

    // Also write to workspace build/reports/
    File localDir = new File("build/reports/circle_comparison");
    localDir.mkdirs();
    renderComparisonPNG(resTruth, resClassic, resOpt16, resOpt32, new File(localDir, "simulation_circle_comparison_side_by_side.png"));

    // Print Metrics
    System.out.println("\n=================================================================================================================================");
    System.out.println("METRICS SUMMARY");
    System.out.println("=================================================================================================================================");
    System.out.printf("%-30s | %-12s | %-15s | %-15s | %-14s | %-14s | %-14s | %-14s%n",
        "Model", "Total Chunks", "Unique Visited", "Unvisited Holes", "Raw Runs", "Coalesced Runs", "Bad Chunks", "Safe Land");
    printMetricRow("Ground Truth (Physical World)", resTruth);
    printMetricRow("Classic Circle (res = 3)", resClassic);
    printMetricRow("Optimized Circle (res = 16)", resOpt16);
    printMetricRow("Optimized Circle (res = 32)", resOpt32);
    System.out.println("=================================================================================================================================\n");

    // Print ASCII Downsampled Maps
    printAsciiMaps(resTruth, resClassic, resOpt16, resOpt32);
  }

  private static void printMetricRow(String name, ShapeResult r) {
    System.out.printf("%-30s | %-12d | %-15d | %-15d | %-14d | %-14d | %-14d | %-14d%n",
        name, r.totalDiskChunks, r.uniqueVisitedChunks, r.unvisitedHoleChunks, r.rawRuns, r.coalescedRuns, r.badChunks, r.safeChunks);
  }

  private static class ShapeResult {
    String name;
    long spatialResolution;
    long totalDiskChunks;
    long uniqueVisitedChunks;
    long unvisitedHoleChunks;
    long rawRuns;
    long coalescedRuns;
    long badChunks;
    long safeChunks;
    byte[][] grid; // 0 = out, 1 = safe, 2 = bad, 3 = unvisited/unreachable hole
    int dim;
    int origin;
  }

  private ShapeResult evaluateShape(MemoryShape shape, RealWorldVerdictMask mask, long resolution) {
    long range = shape.getRange();
    MutableRTPCoords coords = new MutableRTPCoords(0, 0);

    int dim = 2 * R + 2;
    int origin = R;

    // Track which (cx, cz) locations the shape can ACTUALLY visit
    java.util.Set<Long> visitedCoords = new java.util.HashSet<>();
    List<Long> badKeys = new ArrayList<>();

    for (long loc = 0; loc < range; loc++) {
      shape.locationToXZ(loc, coords);
      int cx = coords.x;
      int cz = coords.z;
      long distSq = (long) cx * cx + (long) cz * cz;
      if (distSq > (long) R * R || distSq < (long) CR * CR) {
        continue;
      }
      visitedCoords.add((((long) cx) << 32) | (cz & 0xFFFFFFFFL));

      boolean safe = mask.isOccupied(cx, cz);
      if (!safe) {
        badKeys.add(loc);
      }
    }

    // Sort badKeys
    long[] rawBad = badKeys.stream().mapToLong(Long::longValue).sorted().toArray();

    // Count raw uncoalesced runs (consecutive 1D keys)
    long rawRuns = 0;
    if (rawBad.length > 0) {
      rawRuns = 1;
      for (int i = 1; i < rawBad.length; i++) {
        if (rawBad[i] != rawBad[i - 1] + 1) {
          rawRuns++;
        }
      }
    }

    // Coalesce runs
    List<long[]> coalesced = new ArrayList<>();
    if (rawBad.length > 0) {
      long curStart = rawBad[0];
      long curLen = 1;
      for (int i = 1; i < rawBad.length; i++) {
        long next = rawBad[i];
        if (next == curStart + curLen) {
          curLen++;
        } else {
          long gap = next - (curStart + curLen);
          long admissible = MemoryShape.computeAdmissibleGap(resolution, curLen, 1L);
          if (gap <= admissible) {
            curLen = (next + 1) - curStart;
          } else {
            coalesced.add(new long[]{curStart, curLen});
            curStart = next;
            curLen = 1;
          }
        }
      }
      coalesced.add(new long[]{curStart, curLen});
    }

    // Track which locations are covered by coalesced bad runs
    java.util.Set<Long> coalescedBadCoords = new java.util.HashSet<>();
    for (long[] run : coalesced) {
      long start = run[0];
      long len = run[1];
      for (long loc = start; loc < start + len && loc < range; loc++) {
        shape.locationToXZ(loc, coords);
        int cx = coords.x;
        int cz = coords.z;
        long distSq = (long) cx * cx + (long) cz * cz;
        if (distSq <= (long) R * R && distSq >= (long) CR * CR) {
          coalescedBadCoords.add((((long) cx) << 32) | (cz & 0xFFFFFFFFL));
        }
      }
    }

    byte[][] grid = new byte[dim][dim];
    long totalDisk = 0;
    long safeCount = 0;
    long badCount = 0;
    long unvisitedCount = 0;

    for (int cx = -R; cx <= R; cx++) {
      for (int cz = -R; cz <= R; cz++) {
        long distSq = (long) cx * cx + (long) cz * cz;
        if (distSq > (long) R * R || distSq < (long) CR * CR) continue;
        totalDisk++;

        int gx = cx + origin;
        int gz = cz + origin;
        long coordKey = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);

        if (!visitedCoords.contains(coordKey)) {
          // Unreachable / unvisited hole in the mathematical mapping!
          grid[gx][gz] = 3; // unmapped void
          unvisitedCount++;
        } else if (coalescedBadCoords.contains(coordKey)) {
          grid[gx][gz] = 2; // bad / hazard / coalesced
          badCount++;
        } else {
          grid[gx][gz] = 1; // safe playable land offered
          safeCount++;
        }
      }
    }

    ShapeResult res = new ShapeResult();
    res.name = shape.name;
    res.spatialResolution = resolution;
    res.totalDiskChunks = totalDisk;
    res.uniqueVisitedChunks = visitedCoords.size();
    res.unvisitedHoleChunks = unvisitedCount;
    res.rawRuns = rawRuns;
    res.coalescedRuns = coalesced.size();
    res.badChunks = badCount;
    res.safeChunks = safeCount;
    res.grid = grid;
    res.dim = dim;
    res.origin = origin;
    return res;
  }

  private static void renderPNG(ShapeResult res, File outFile, String title, int resKnob) throws Exception {
    int scale = 1;
    int size = res.dim * scale;
    BufferedImage img = new BufferedImage(size, size + 60, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    g.setColor(new Color(0x101821));
    g.fillRect(0, 0, size, size + 60);

    for (int x = 0; x < res.dim; x++) {
      for (int z = 0; z < res.dim; z++) {
        byte val = res.grid[x][z];
        int rgb;
        if (val == 1) rgb = 0x2E7D32; // Green safe land
        else if (val == 2) rgb = 0x1565C0; // Blue hazard / water / coalesced
        else rgb = 0x101821; // background / donut hole / out of bounds
        img.setRGB(x, 50 + (res.dim - 1 - z), rgb);
      }
    }

    g.setColor(Color.WHITE);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
    g.drawString(title, 15, 25);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    g.setColor(new Color(0xC7D0DA));
    g.drawString(String.format("Runs: %,d  |  Safe Land: %,d chunks  |  Bad Chunks: %,d",
        res.coalescedRuns, res.safeChunks, res.badChunks), 15, 42);

    g.dispose();
    ImageIO.write(img, "png", outFile);
    System.out.println("[DEBUG_LOG] Saved PNG to " + outFile.getAbsolutePath());
  }

  private static ShapeResult buildTruthResult(RealWorldVerdictMask mask) {
    int dim = 2 * R + 2;
    int origin = R;
    byte[][] grid = new byte[dim][dim];
    long admitted = 0;
    long safe = 0;
    long bad = 0;

    for (int cx = -R; cx <= R; cx++) {
      for (int cz = -R; cz <= R; cz++) {
        long distSq = (long) cx * cx + (long) cz * cz;
        if (distSq > (long) R * R || distSq < (long) CR * CR) continue;
        admitted++;
        int gx = cx + origin;
        int gz = cz + origin;
        if (mask.isOccupied(cx, cz)) {
          grid[gx][gz] = 1;
          safe++;
        } else {
          grid[gx][gz] = 2;
          bad++;
        }
      }
    }

    ShapeResult res = new ShapeResult();
    res.name = "GROUND_TRUTH";
    res.spatialResolution = 0L;
    res.totalDiskChunks = admitted;
    res.uniqueVisitedChunks = admitted;
    res.unvisitedHoleChunks = 0;
    res.rawRuns = 0;
    res.coalescedRuns = 0;
    res.badChunks = bad;
    res.safeChunks = safe;
    res.grid = grid;
    res.dim = dim;
    res.origin = origin;
    return res;
  }

  private static void renderComparisonPNG(ShapeResult truth, ShapeResult c, ShapeResult opt16, ShapeResult opt32, File outFile) throws Exception {
    int dim = c.dim;
    int margin = 20;
    int totalWidth = dim * 4 + margin * 5;
    int totalHeight = dim + 90;

    BufferedImage img = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(new Color(0x0E141B));
    g.fillRect(0, 0, totalWidth, totalHeight);

    ShapeResult[] shapes = {truth, c, opt16, opt32};
    String[] titles = {
        "1. Ground Truth (Physical World)",
        "2. Classic Circle (Polar, res = 3)",
        "3. Optimized Circle (res = 16)",
        "4. Optimized Circle (res = 32)"
    };

    for (int i = 0; i < 4; i++) {
      ShapeResult sr = shapes[i];
      int offsetX = margin + i * (dim + margin);
      int offsetY = 65;

      for (int x = 0; x < dim; x++) {
        for (int z = 0; z < dim; z++) {
          byte val = sr.grid[x][z];
          int rgb;
          if (val == 1) rgb = 0x2E7D32; // Green safe land
          else if (val == 2) rgb = 0x1E88E5; // Blue hazard
          else if (val == 3) rgb = 0xD32F2F; // Red unvisited/unmapped holes in polar mapping
          else rgb = 0x18222D; // Donut / outside
          img.setRGB(offsetX + x, offsetY + (dim - 1 - z), rgb);
        }
      }

      g.setColor(Color.WHITE);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
      g.drawString(titles[i], offsetX, 25);

      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
      g.setColor(new Color(0x90CAF9));
      if (sr.coalescedRuns > 0) {
        g.drawString(String.format("Runs: %,d  (-%.1f%%)", sr.coalescedRuns,
            100.0 * (1.0 - (double) sr.coalescedRuns / c.coalescedRuns)), offsetX, 42);
      } else {
        g.drawString("Exact Terrain Model", offsetX, 42);
      }
      g.setColor(new Color(0xA5D6A7));
      g.drawString(String.format("Safe: %,d | Hazard: %,d", sr.safeChunks, sr.badChunks), offsetX, 56);
    }

    g.dispose();
    ImageIO.write(img, "png", outFile);
    System.out.println("[DEBUG_LOG] Saved comparison PNG to " + outFile.getAbsolutePath());
  }

  private static void printAsciiMaps(ShapeResult truth, ShapeResult c, ShapeResult opt16, ShapeResult opt32) {
    int sampleRows = 28;
    int dim = c.dim;
    int step = dim / sampleRows;

    System.out.println("2D DOWNSAMPLED MAP COMPARISON (Green # = Safe Land, Blue ~ = Hazard/Water, Space = Outside/Donut)");
    System.out.printf("%-26s   %-26s   %-26s   %-26s%n",
        "TRUTH (Physical World)", "CLASSIC (res = 3)", "OPTIMIZED (res = 16)", "OPTIMIZED (res = 32)");
    System.out.println("===============================================================================================================================");

    for (int r = 1; r < sampleRows - 1; r++) {
      int z = r * step;
      StringBuilder sb0 = new StringBuilder();
      StringBuilder sb1 = new StringBuilder();
      StringBuilder sb2 = new StringBuilder();
      StringBuilder sb3 = new StringBuilder();

      for (int col = 1; col < sampleRows - 1; col++) {
        int x = col * step;
        sb0.append(charFor(truth.grid[x][z]));
        sb1.append(charFor(c.grid[x][z]));
        sb2.append(charFor(opt16.grid[x][z]));
        sb3.append(charFor(opt32.grid[x][z]));
      }
      System.out.printf("%02d | %s |  %02d | %s |  %02d | %s |  %02d | %s |%n",
          r, sb0.toString(), r, sb1.toString(), r, sb2.toString(), r, sb3.toString());
    }
    System.out.println("===============================================================================================================================");
  }

  private static char charFor(byte b) {
    if (b == 1) return '#'; // safe land
    if (b == 2) return '~'; // water/hazard
    if (b == 3) return '.'; // unvisited hole / unreachable by mapping
    return ' '; // outside
  }
}
