package io.github.dailystruggle.rtp.common.benchmark;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class NativeVsUniqueVsL3VisualizerTest {

  private static final int R = 256; // 256 chunks radius (512 x 512 chunks = 8192 x 8192 blocks)
  private static final int DIAMETER = R * 2;
  private static final int SAMPLE_COUNT = 5000;

  record ChunkPoint(int cx, int cz) {}

  record ModelResult(
      String title,
      String subtitle,
      List<ChunkPoint> points,
      int totalDraws,
      int uniqueChunks,
      int duplicateChunks,
      double duplicateRate,
      double avgDistanceBetweenConsecutive) {}

  @Test
  @DisplayName("4-Way Selection Comparison: Native vs. Unique Placements vs. L3 Versions")
  public void testCompareNativeAndUniqueAndL3() throws Exception {
    System.out.println("\n[DEBUG_LOG] === 4-WAY SELECTION COMPARISON: NATIVE vs UNIQUE vs L3 ===");

    // 1. Lossless Chunk Outcome Map for R=1024 Chunks (covers R=256 cleanly)
    File cacheFile = new File("build/cache/mca_world_r1024_outcomes.bin");
    Path regionDir = Path.of("C:\\GameServers\\Minecraft\\testServer\\RTP-Folia\\26.1\\world\\dimensions\\minecraft\\overworld\\region");
    if (!Files.isDirectory(regionDir)) {
      regionDir = Path.of("testdata-world/overworld/region");
    }

    System.out.println("[DEBUG_LOG] Loading lossless chunk outcome map...");
    long startMapNs = System.nanoTime();
    LosslessChunkOutcomeMap outcomeMap = LosslessChunkOutcomeMap.getOrCreate(cacheFile, regionDir);
    System.out.printf("[DEBUG_LOG] Lossless chunk outcome map ready in %.2f ms%n", (System.nanoTime() - startMapNs) / 1e6);

    // 2. Run Model 1: Native Sampling (No Unique Placements)
    System.out.println("[DEBUG_LOG] Running Model 1: Native Sampling (Standard select())...");
    ModelResult res1 = runNativeSampling(outcomeMap, false);

    // 3. Run Model 2: Native Sampling WITH Unique Placements (uniquePlacements = 1)
    System.out.println("[DEBUG_LOG] Running Model 2: Native Sampling WITH Unique Placements (uniquePlacements = 1)...");
    ModelResult res2 = runNativeSampling(outcomeMap, true);

    // 4. Run Model 3: L3 Harvesting (No Unique Placements)
    System.out.println("[DEBUG_LOG] Running Model 3: L3 Harvesting (selectL3Candidate())...");
    ModelResult res3 = runL3Sampling(outcomeMap, false);

    // 5. Run Model 4: L3 Harvesting WITH Unique Placements
    System.out.println("[DEBUG_LOG] Running Model 4: L3 Harvesting WITH Unique Placements (uniquePlacements = 1)...");
    ModelResult res4 = runL3Sampling(outcomeMap, true);

    // 6. Print summary metrics
    printMetrics(res1);
    printMetrics(res2);
    printMetrics(res3);
    printMetrics(res4);

    // 7. Render 4-Panel High-Resolution Comparison Chart
    System.out.println("[DEBUG_LOG] Rendering 4-Panel Comparison Chart...");
    renderComparisonChart(outcomeMap, res1, res2, res3, res4);
  }

  private ModelResult runNativeSampling(LosslessChunkOutcomeMap outcomeMap, boolean uniquePlacements) {
    SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("NATIVE_SQUARE_" + uniquePlacements, 32);
    shape.set(GenericMemoryShapeParams.radius, (long) R);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.mode, "ACCUMULATE");
    if (uniquePlacements) {
      shape.set(GenericMemoryShapeParams.uniquePlacements, 1);
    }

    List<ChunkPoint> points = new ArrayList<>(SAMPLE_COUNT);
    Set<ChunkPoint> uniqueSet = new HashSet<>(SAMPLE_COUNT);
    int duplicates = 0;
    double totalJumpDist = 0;

    for (int i = 0; i < SAMPLE_COUNT; i++) {
      int[] sel = null;
      for (int attempt = 0; attempt < 50; attempt++) {
        int[] cand = shape.select();
        if (cand == null || cand.length < 2) continue;
        if (outcomeMap.isSafe(cand[0], cand[1])) {
          sel = cand;
          break;
        }
      }
      if (sel == null) break;

      ChunkPoint pt = new ChunkPoint(sel[0], sel[1]);
      if (!uniqueSet.add(pt)) {
        duplicates++;
      }
      if (!points.isEmpty()) {
        ChunkPoint prev = points.get(points.size() - 1);
        double dist = Math.hypot(pt.cx - prev.cx, pt.cz - prev.cz);
        totalJumpDist += dist;
      }
      points.add(pt);
    }

    double avgDist = points.size() > 1 ? totalJumpDist / (points.size() - 1) : 0;
    double dupRate = points.isEmpty() ? 0 : 100.0 * duplicates / points.size();

    String title = uniquePlacements ? "2. Native + Unique Placements" : "1. Native Sampling";
    String subtitle = uniquePlacements ? "select() with uniquePlacements=1" : "Standard select() (rand())";

    return new ModelResult(title, subtitle, points, points.size(), uniqueSet.size(), duplicates, dupRate, avgDist);
  }

  private ModelResult runL3Sampling(LosslessChunkOutcomeMap outcomeMap, boolean uniquePlacements) {
    SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("L3_SQUARE_" + uniquePlacements, 32);
    shape.set(GenericMemoryShapeParams.radius, (long) R);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.mode, "ACCUMULATE");

    List<ChunkPoint> points = new ArrayList<>(SAMPLE_COUNT);
    Set<ChunkPoint> uniqueSet = new HashSet<>(SAMPLE_COUNT);
    int duplicates = 0;
    double totalJumpDist = 0;
    MutableRTPCoords coords = new MutableRTPCoords(0, 0);

    for (int i = 0; i < SAMPLE_COUNT; i++) {
      ChunkPoint sel = null;
      for (int attempt = 0; attempt < 50; attempt++) {
        long loc = shape.selectL3Candidate();
        if (loc < 0) continue;
        shape.locationToXZ(loc, coords);
        int cx = coords.x;
        int cz = coords.z;

        if (outcomeMap.isSafe(cx, cz)) {
          sel = new ChunkPoint(cx, cz);
          if (uniquePlacements) {
            shape.addBadChunkRadius(loc, 1);
          }
          break;
        }
      }
      if (sel == null) break;

      if (!uniqueSet.add(sel)) {
        duplicates++;
      }
      if (!points.isEmpty()) {
        ChunkPoint prev = points.get(points.size() - 1);
        double dist = Math.hypot(sel.cx - prev.cx, sel.cz - prev.cz);
        totalJumpDist += dist;
      }
      points.add(sel);
    }

    double avgDist = points.size() > 1 ? totalJumpDist / (points.size() - 1) : 0;
    double dupRate = points.isEmpty() ? 0 : 100.0 * duplicates / points.size();

    String title = uniquePlacements ? "4. L3 + Unique Placements" : "3. L3 Dyadic Harvester";
    String subtitle = uniquePlacements ? "selectL3Candidate() + uniquePlacements" : "selectL3Candidate() (PRP Stride)";

    return new ModelResult(title, subtitle, points, points.size(), uniqueSet.size(), duplicates, dupRate, avgDist);
  }

  private void printMetrics(ModelResult res) {
    System.out.printf("--- %s (%s) ---%n", res.title, res.subtitle);
    System.out.printf("  Total Draws:       %,d%n", res.totalDraws);
    System.out.printf("  Unique Landings:   %,d%n", res.uniqueChunks);
    System.out.printf("  Duplicate Chunks:  %,d (%.2f%%)%n", res.duplicateChunks, res.duplicateRate);
    System.out.printf("  Avg Hop Distance:  %.1f chunks (%.0f blocks)%n%n", res.avgDistanceBetweenConsecutive, res.avgDistanceBetweenConsecutive * 16);
  }

  private void renderComparisonChart(
      LosslessChunkOutcomeMap outcomeMap,
      ModelResult m1, ModelResult m2, ModelResult m3, ModelResult m4) throws Exception {

    int panelSize = 560;
    int headerHeight = 110;
    int footerHeight = 140;
    int padding = 20;

    int totalWidth = panelSize * 4 + padding * 5;
    int totalHeight = headerHeight + panelSize + footerHeight;

    BufferedImage img = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

    // Background
    g.setColor(new Color(15, 20, 25));
    g.fillRect(0, 0, totalWidth, totalHeight);

    // Header
    g.setColor(new Color(230, 240, 255));
    g.setFont(new Font("SansSerif", Font.BOLD, 26));
    g.drawString("RTP SELECTION MODEL BENCHMARK: NATIVE vs UNIQUE PLACEMENTS vs L3 HARVESTING", padding, 45);

    g.setColor(new Color(160, 180, 200));
    g.setFont(new Font("SansSerif", Font.PLAIN, 15));
    g.drawString(
        String.format("Comparing 5,000 Selections across World Radius R = %d Chunks (8,192 Blocks Across) | Real Overworld Terrain Mask", R),
        padding, 75);

    ModelResult[] models = new ModelResult[] { m1, m2, m3, m4 };
    Color[] accentColors = new Color[] {
        new Color(70, 180, 255),   // Native: Cyan
        new Color(80, 230, 140),   // Native+Unique: Emerald
        new Color(220, 140, 255),  // L3: Purple
        new Color(255, 195, 70)    // L3+Unique: Gold
    };

    // Render Panels
    for (int p = 0; p < 4; p++) {
      int px = padding + p * (panelSize + padding);
      int py = headerHeight;
      ModelResult m = models[p];
      Color accent = accentColors[p];

      renderPanel(g, px, py, panelSize, outcomeMap, m, accent);
      renderFooterMetrics(g, px, py + panelSize + 15, panelSize, m, accent);
    }

    g.dispose();

    // Save image to multiple paths
    File outRoot = new File("../native_vs_unique_vs_l3_comparison_chart.png");
    if (!outRoot.getParentFile().exists()) outRoot = new File("native_vs_unique_vs_l3_comparison_chart.png");
    ImageIO.write(img, "png", outRoot);
    System.out.printf("[DEBUG_LOG] Saved comparison chart to %s (%d KB)%n",
        outRoot.getAbsolutePath(), outRoot.length() / 1024);

    File outDocs = new File("../docs/assets/img/native_vs_unique_vs_l3_comparison_chart.png");
    if (!outDocs.getParentFile().exists()) outDocs = new File("docs/assets/img/native_vs_unique_vs_l3_comparison_chart.png");
    outDocs.getParentFile().mkdirs();
    ImageIO.write(img, "png", outDocs);

    File outServer = new File("C:\\GameServers\\Minecraft\\testServer\\RTP-Folia\\26.1\\plugins\\RTP\\database\\regionData\\debug\\native_vs_unique_vs_l3_comparison_chart.png");
    if (outServer.getParentFile().exists()) {
      ImageIO.write(img, "png", outServer);
    }
  }

  private void renderPanel(
      Graphics2D g, int x, int y, int size,
      LosslessChunkOutcomeMap outcomeMap, ModelResult m, Color accent) {

    // Panel border and background
    g.setColor(new Color(24, 30, 38));
    g.fillRect(x, y, size, size);

    // Render terrain backdrop (downsampled from outcomeMap for R=256)
    // Map bounding box: [-R, R] -> [0, size]
    BufferedImage terrainBuf = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
    int[] pixels = new int[size * size];

    for (int sy = 0; sy < size; sy++) {
      int cz = -R + (int) ((double) sy / size * DIAMETER);
      for (int sx = 0; sx < size; sx++) {
        int cx = -R + (int) ((double) sx / size * DIAMETER);
        boolean safe = outcomeMap.isSafe(cx, cz);
        pixels[sy * size + sx] = safe ? 0x1A3828 : 0x14223A; // Green land vs Navy water
      }
    }
    terrainBuf.setRGB(0, 0, size, size, pixels, 0, size);
    g.drawImage(terrainBuf, x, y, null);

    // Draw candidate arrival points
    g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 180));
    for (ChunkPoint pt : m.points) {
      int sx = x + (int) ((double) (pt.cx + R) / DIAMETER * size);
      int sy = y + (int) ((double) (pt.cz + R) / DIAMETER * size);
      if (sx >= x && sx < x + size && sy >= y && sy < y + size) {
        g.fillRect(sx - 1, sy - 1, 3, 3);
      }
    }

    // Draw consecutive trajectory vector lines for the first 15 arrivals
    if (m.points.size() >= 2) {
      int trailLen = Math.min(15, m.points.size());
      g.setStroke(new BasicStroke(2.0f));
      for (int i = 0; i < trailLen - 1; i++) {
        ChunkPoint p1 = m.points.get(i);
        ChunkPoint p2 = m.points.get(i + 1);
        int sx1 = x + (int) ((double) (p1.cx + R) / DIAMETER * size);
        int sy1 = y + (int) ((double) (p1.cz + R) / DIAMETER * size);
        int sx2 = x + (int) ((double) (p2.cx + R) / DIAMETER * size);
        int sy2 = y + (int) ((double) (p2.cz + R) / DIAMETER * size);

        float progress = (float) i / (trailLen - 1);
        g.setColor(new Color(1.0f, 0.9f - progress * 0.4f, 0.2f + progress * 0.5f, 0.9f));
        g.drawLine(sx1, sy1, sx2, sy2);
      }

      // Draw numbered node badges for the first 5 hops
      for (int i = 0; i < Math.min(5, m.points.size()); i++) {
        ChunkPoint pt = m.points.get(i);
        int sx = x + (int) ((double) (pt.cx + R) / DIAMETER * size);
        int sy = y + (int) ((double) (pt.cz + R) / DIAMETER * size);
        g.setColor(Color.WHITE);
        g.fillOval(sx - 7, sy - 7, 14, 14);
        g.setColor(Color.BLACK);
        g.drawOval(sx - 7, sy - 7, 14, 14);
        g.setFont(new Font("SansSerif", Font.BOLD, 9));
        g.drawString(String.valueOf(i + 1), sx - 3, sy + 4);
      }
    }

    // Panel border
    g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 120));
    g.setStroke(new BasicStroke(2.0f));
    g.drawRect(x, y, size, size);

    // Panel Header overlay
    g.setColor(new Color(15, 20, 25, 220));
    g.fillRect(x + 2, y + 2, size - 4, 46);

    g.setColor(accent);
    g.setFont(new Font("SansSerif", Font.BOLD, 15));
    g.drawString(m.title, x + 12, y + 22);

    g.setColor(new Color(200, 215, 230));
    g.setFont(new Font("SansSerif", Font.PLAIN, 12));
    g.drawString(m.subtitle, x + 12, y + 40);
  }

  private void renderFooterMetrics(Graphics2D g, int x, int y, int width, ModelResult m, Color accent) {
    g.setColor(new Color(22, 28, 36));
    g.fillRect(x, y, width, 115);

    g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 80));
    g.setStroke(new BasicStroke(1.0f));
    g.drawRect(x, y, width, 115);

    g.setFont(new Font("SansSerif", Font.BOLD, 13));
    g.setColor(new Color(230, 240, 255));
    g.drawString("PERFORMANCE & ACCURACY", x + 10, y + 20);

    g.setFont(new Font("SansSerif", Font.PLAIN, 12));
    g.setColor(new Color(180, 195, 210));
    g.drawString(String.format("Total Selections:  %,d", m.totalDraws), x + 10, y + 42);
    g.drawString(String.format("Unique Landings:   %,d", m.uniqueChunks), x + 10, y + 62);

    // Duplicate badge
    if (m.duplicateChunks == 0) {
      g.setColor(new Color(80, 240, 140));
      g.drawString("Duplicate Chunks:  0 (0.0% - ZERO DUPES)", x + 10, y + 82);
    } else {
      g.setColor(new Color(255, 110, 110));
      g.drawString(String.format("Duplicate Chunks:  %,d (%.2f%%)", m.duplicateChunks, m.duplicateRate), x + 10, y + 82);
    }

    g.setColor(new Color(255, 220, 120));
    g.drawString(String.format("Avg Hop Distance:  %.1f chunks (~%.0f blocks)",
        m.avgDistanceBetweenConsecutive, m.avgDistanceBetweenConsecutive * 16), x + 10, y + 102);
  }
}
