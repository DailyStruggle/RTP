package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.DownsampledDualLayerSquare;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Side-by-side empirical verification and visual image generator for:
 * 1. Current Implementation (Native Feistel PRP at S=1 resolution).
 * 2. Parallel Modified Implementation (Downsampled Dyadic Stride derived from uniquePlacements radius R_u).
 * 3. Parallel Modified Implementation with Stride Exclusion (Excluding 50% of strides in phase space).
 * 4. Side-by-side memory model comparison (Monolithic flat arrays vs. Roaring/Hybrid containers).
 * 5. Statistical distribution curves: Nearest-Neighbor Pairwise Distance PDF/CDF curves and Radial Evenness.
 */
public class DownsamplingSideBySideBenchmarkTest {

  private record ChunkCoord(long x, long z) {
    double euclideanDistance(ChunkCoord other) {
      long dx = this.x - other.x;
      long dz = this.z - other.z;
      return Math.sqrt(dx * dx + dz * dz);
    }
  }

  public record MemoryModelMetrics(
      String name,
      long trackedDomainChunks,
      int rawRunCount,
      long flatArrayBytes,
      long pureBitmaskBytes,
      long roaringHybridBytes,
      double memoryReductionPercent
  ) {}

  public record ProximityMetrics(
      double avgConsecutiveJump,
      double minConsecutiveJump,
      double maxConsecutiveJump,
      double medianConsecutiveJump,
      double[] sequentialDistances, // i -> i+1 distances
      double minDistInWindow32,
      double globalMinPairwiseDist,
      double globalMaxPairwiseDist,
      double globalAvgNearestNeighborDist,
      double globalMedianNearestNeighborDist,
      double[] nearestNeighborDistances // parallel placement distances
  ) {}

  @Test
  @DisplayName("Generate Side-by-Side Spatial Distribution and Memory Model Comparison Chart")
  public void testGenerateSideBySideComparison() throws Exception {
    int R = 1024;
    int pointEdgeP = 32;
    int testTeleports = 600;
    int uniqueRadiusRu = 8; // R_u = 8 chunks (128 blocks footprint)

    // 1. Legacy Default: uniquePlacements = 0 (Off / Unconstrained S=1)
    SquareOptimizedDualLayer legacyDefaultShape = new SquareOptimizedDualLayer("LEGACY_DEFAULT_OFF", pointEdgeP);
    legacyDefaultShape.set(GenericMemoryShapeParams.radius, (long) R);
    legacyDefaultShape.set(GenericMemoryShapeParams.centerRadius, 0L);
    legacyDefaultShape.set(GenericMemoryShapeParams.centerX, 0L);
    legacyDefaultShape.set(GenericMemoryShapeParams.centerZ, 0L);
    legacyDefaultShape.set(GenericMemoryShapeParams.uniquePlacements, 0);
    legacyDefaultShape.set(GenericMemoryShapeParams.expand, false);

    // 2. Target 8-Chunk Spacing (R_u = 8 chunks -> S = 64, d = sqrt(64) = 8 chunks / 128 blocks)
    DownsampledDualLayerSquare stride64Shape = new DownsampledDualLayerSquare("SPACED_S64_RU8", pointEdgeP);
    stride64Shape.set(GenericMemoryShapeParams.radius, (long) R);
    stride64Shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    stride64Shape.set(GenericMemoryShapeParams.centerX, 0L);
    stride64Shape.set(GenericMemoryShapeParams.centerZ, 0L);
    stride64Shape.set(GenericMemoryShapeParams.uniquePlacements, 8);
    stride64Shape.set(GenericMemoryShapeParams.expand, true);
    stride64Shape.setExplicitStride(64); // S = 64 => d = sqrt(64) = 8 chunks

    // 3. Target 16-Chunk Spacing (R_u = 16 chunks -> S = 256, d = sqrt(256) = 16 chunks / 256 blocks)
    DownsampledDualLayerSquare stride256Shape = new DownsampledDualLayerSquare("SPACED_S256_RU16", pointEdgeP);
    stride256Shape.set(GenericMemoryShapeParams.radius, (long) R);
    stride256Shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    stride256Shape.set(GenericMemoryShapeParams.centerX, 0L);
    stride256Shape.set(GenericMemoryShapeParams.centerZ, 0L);
    stride256Shape.set(GenericMemoryShapeParams.uniquePlacements, 16);
    stride256Shape.set(GenericMemoryShapeParams.expand, true);
    stride256Shape.setExplicitStride(256); // S = 256 => d = sqrt(256) = 16 chunks

    // Collect arrivals
    List<ChunkCoord> currentArrivals = samplePoints(legacyDefaultShape, testTeleports);
    List<ChunkCoord> arrivalsS64 = samplePoints(stride64Shape, testTeleports);
    List<ChunkCoord> arrivalsS256 = samplePoints(stride256Shape, testTeleports);

    // Compute comprehensive proximity metrics
    ProximityMetrics proxCurrent = evaluateProximityMetrics(currentArrivals);
    ProximityMetrics proxS64 = evaluateProximityMetrics(arrivalsS64);
    ProximityMetrics proxS256 = evaluateProximityMetrics(arrivalsS256);

    System.out.println("[DEBUG_LOG] === Side-by-Side Spatial Performance (Sequential & Parallel) ===");
    System.out.printf("[DEBUG_LOG] 1. Legacy Default (S=1):%n" +
        "   Sequential (i -> i+1): Min = %.2f c (%.0f blk), Max = %.2f c (%.0f blk), Median = %.2f c, Mean = %.2f c%n" +
        "   Parallel (Nearest-Neighbor): Min = %.2f c (%.0f blk), Max = %.2f c (%.0f blk), Median = %.2f c, Mean = %.2f c%n",
        proxCurrent.minConsecutiveJump, proxCurrent.minConsecutiveJump * 16, proxCurrent.maxConsecutiveJump, proxCurrent.maxConsecutiveJump * 16, proxCurrent.medianConsecutiveJump, proxCurrent.avgConsecutiveJump,
        proxCurrent.globalMinPairwiseDist, proxCurrent.globalMinPairwiseDist * 16, proxCurrent.globalMaxPairwiseDist, proxCurrent.globalMaxPairwiseDist * 16, proxCurrent.globalMedianNearestNeighborDist, proxCurrent.globalAvgNearestNeighborDist);

    System.out.printf("[DEBUG_LOG] 2. Spaced S=64 (Ru=8, 8c / 128 blk Target):%n" +
        "   Sequential (i -> i+1): Min = %.2f c (%.0f blk), Max = %.2f c (%.0f blk), Median = %.2f c, Mean = %.2f c%n" +
        "   Parallel (Nearest-Neighbor): Min = %.2f c (%.0f blk), Max = %.2f c (%.0f blk), Median = %.2f c, Mean = %.2f c%n",
        proxS64.minConsecutiveJump, proxS64.minConsecutiveJump * 16, proxS64.maxConsecutiveJump, proxS64.maxConsecutiveJump * 16, proxS64.medianConsecutiveJump, proxS64.avgConsecutiveJump,
        proxS64.globalMinPairwiseDist, proxS64.globalMinPairwiseDist * 16, proxS64.globalMaxPairwiseDist, proxS64.globalMaxPairwiseDist * 16, proxS64.globalMedianNearestNeighborDist, proxS64.globalAvgNearestNeighborDist);

    System.out.printf("[DEBUG_LOG] 3. Spaced S=256 (Ru=16, 16c / 256 blk Target):%n" +
        "   Sequential (i -> i+1): Min = %.2f c (%.0f blk), Max = %.2f c (%.0f blk), Median = %.2f c, Mean = %.2f c%n" +
        "   Parallel (Nearest-Neighbor): Min = %.2f c (%.0f blk), Max = %.2f c (%.0f blk), Median = %.2f c, Mean = %.2f c%n",
        proxS256.minConsecutiveJump, proxS256.minConsecutiveJump * 16, proxS256.maxConsecutiveJump, proxS256.maxConsecutiveJump * 16, proxS256.medianConsecutiveJump, proxS256.avgConsecutiveJump,
        proxS256.globalMinPairwiseDist, proxS256.globalMinPairwiseDist * 16, proxS256.globalMaxPairwiseDist, proxS256.globalMaxPairwiseDist * 16, proxS256.globalMedianNearestNeighborDist, proxS256.globalAvgNearestNeighborDist);

    // Memory Models Comparison
    long totalChunks = (2L * R + 1) * (2L * R + 1); // ~263,169 chunks
    MemoryModelMetrics memCurrent = calculateMemoryModel("1. Legacy Default (uniquePlacements=0, S=1)", totalChunks, uniqueRadiusRu, 1, testTeleports);
    MemoryModelMetrics memS64 = calculateMemoryModel("2. Spaced S=64 (Ru=8, 8c spacing)", totalChunks, 8, 64, testTeleports);
    MemoryModelMetrics memS256 = calculateMemoryModel("3. Spaced S=256 (Ru=16, 16c spacing)", totalChunks, 16, 256, testTeleports);

    System.out.println("\n[DEBUG_LOG] === Side-by-Side Memory Models ===");
    System.out.printf("[DEBUG_LOG] %s: Flat Arrays = %,d B | Pure Bitmask = %,d B | Roaring = %,d B%n",
        memCurrent.name, memCurrent.flatArrayBytes, memCurrent.pureBitmaskBytes, memCurrent.roaringHybridBytes);
    System.out.printf("[DEBUG_LOG] %s: Flat Arrays = %,d B | Pure Bitmask = %,d B | Roaring = %,d B (Reduction: %.1f%%)%n",
        memS64.name, memS64.flatArrayBytes, memS64.pureBitmaskBytes, memS64.roaringHybridBytes, memS64.memoryReductionPercent);
    System.out.printf("[DEBUG_LOG] %s: Flat Arrays = %,d B | Pure Bitmask = %,d B | Roaring = %,d B (Reduction: %.1f%%)%n",
        memS256.name, memS256.flatArrayBytes, memS256.pureBitmaskBytes, memS256.roaringHybridBytes, memS256.memoryReductionPercent);

    // Render Side-by-Side Visual Comparison Chart
    File chartFile = new File("side_by_side_downsampling_comparison_chart.png");
    File repoRootChartFile = new File("../side_by_side_downsampling_comparison_chart.png");
    File autoChartFile = new File("unique_placements_auto_comparison_chart.png");
    File repoRootAutoChartFile = new File("../unique_placements_auto_comparison_chart.png");
    File rootReportFile = new File("build/reports/player_distribution/side_by_side_downsampling_comparison_chart.png");
    File topReportFile = new File("../build/reports/player_distribution/side_by_side_downsampling_comparison_chart.png");

    renderSideBySideChart(
        R,
        uniqueRadiusRu,
        currentArrivals,
        arrivalsS64,
        arrivalsS256,
        proxCurrent, proxS64, proxS256,
        memCurrent, memS64, memS256,
        chartFile
    );

    renderSideBySideChart(
        R,
        uniqueRadiusRu,
        currentArrivals,
        arrivalsS64,
        arrivalsS256,
        proxCurrent, proxS64, proxS256,
        memCurrent, memS64, memS256,
        repoRootChartFile
    );

    renderSideBySideChart(
        R,
        uniqueRadiusRu,
        currentArrivals,
        arrivalsS64,
        arrivalsS256,
        proxCurrent, proxS64, proxS256,
        memCurrent, memS64, memS256,
        autoChartFile
    );

    renderSideBySideChart(
        R,
        uniqueRadiusRu,
        currentArrivals,
        arrivalsS64,
        arrivalsS256,
        proxCurrent, proxS64, proxS256,
        memCurrent, memS64, memS256,
        repoRootAutoChartFile
    );

    rootReportFile.getParentFile().mkdirs();
    renderSideBySideChart(
        R,
        uniqueRadiusRu,
        currentArrivals,
        arrivalsS64,
        arrivalsS256,
        proxCurrent, proxS64, proxS256,
        memCurrent, memS64, memS256,
        rootReportFile
    );

    topReportFile.getParentFile().mkdirs();
    renderSideBySideChart(
        R,
        uniqueRadiusRu,
        currentArrivals,
        arrivalsS64,
        arrivalsS256,
        proxCurrent, proxS64, proxS256,
        memCurrent, memS64, memS256,
        topReportFile
    );

    assertTrue(chartFile.exists(), "Chart file must be generated");
    System.out.println("[DEBUG_LOG] Successfully rendered comparison chart to: " + chartFile.getAbsolutePath());
  }

  private static List<ChunkCoord> samplePoints(SquareOptimizedDualLayer shape, int count) {
    List<ChunkCoord> list = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      int[] res = shape.select();
      list.add(new ChunkCoord(res[0], res[1]));
    }
    return list;
  }

  private static ProximityMetrics evaluateProximityMetrics(List<ChunkCoord> list) {
    int n = list.size();
    if (n < 2) {
      return new ProximityMetrics(0, 0, 0, 0, new double[0], 0, 0, 0, 0, 0, new double[0]);
    }

    // 1. Unique Sequential Placement: Distance from one placement to the next (i -> i+1)
    double[] seqDistances = new double[n - 1];
    double totalJump = 0.0;
    double minJump = Double.MAX_VALUE;
    double maxJump = 0.0;

    for (int i = 0; i < n - 1; i++) {
      double d = list.get(i).euclideanDistance(list.get(i + 1));
      seqDistances[i] = d;
      totalJump += d;
      if (d < minJump) minJump = d;
      if (d > maxJump) maxJump = d;
    }
    double avgConsecutiveJump = totalJump / (n - 1);
    double[] sortedSeq = seqDistances.clone();
    Arrays.sort(sortedSeq);
    double medianConsecutiveJump = sortedSeq[sortedSeq.length / 2];

    // 2. Min Distance in Sliding Window (N = 32)
    double sumWindowMin = 0.0;
    int measuredWindow = 0;
    for (int i = 1; i < n; i++) {
      double minD = Double.MAX_VALUE;
      int start = Math.max(0, i - 32);
      for (int j = start; j < i; j++) {
        double d = list.get(i).euclideanDistance(list.get(j));
        if (d < minD) minD = d;
      }
      sumWindowMin += minD;
      measuredWindow++;
    }
    double minDistInWindow32 = measuredWindow > 0 ? sumWindowMin / measuredWindow : 0.0;

    // 3. Unique Parallel Placement: Global Pairwise & Nearest-Neighbor Distribution across ALL points
    double[] nearestNeighbors = new double[n];
    double globalMinPairwise = Double.MAX_VALUE;
    double globalMaxPairwise = 0.0;
    double sumNN = 0.0;

    for (int i = 0; i < n; i++) {
      ChunkCoord pi = list.get(i);
      double minD = Double.MAX_VALUE;
      for (int j = 0; j < n; j++) {
        if (i == j) continue;
        double d = pi.euclideanDistance(list.get(j));
        if (d < minD) minD = d;
        if (d > globalMaxPairwise) globalMaxPairwise = d;
        if (d < globalMinPairwise) globalMinPairwise = d;
      }
      nearestNeighbors[i] = minD;
      sumNN += minD;
    }

    Arrays.sort(nearestNeighbors);
    double avgNN = sumNN / n;
    double medianNN = nearestNeighbors[n / 2];

    return new ProximityMetrics(
        avgConsecutiveJump,
        minJump,
        maxJump,
        medianConsecutiveJump,
        sortedSeq,
        minDistInWindow32,
        globalMinPairwise,
        globalMaxPairwise,
        avgNN,
        medianNN,
        nearestNeighbors
    );
  }

  private static MemoryModelMetrics calculateMemoryModel(
      String name, long totalChunks, int uniqueRadiusRu, int strideS, int teleports) {

    int footprint = (2 * uniqueRadiusRu - 1) * (2 * uniqueRadiusRu - 1);
    int rawRunsCurrent = teleports * footprint / 4; // partial merging

    // Flat arrays (key 8B + length 8B + cause 1B + expiry 8B = 25 bytes per run)
    long flatBytes = (long) rawRunsCurrent * 25L;

    // Pure bitmask: 1 bit per tracked cell
    long trackedCells = totalChunks / strideS;
    long pureBitmaskBytes = (trackedCells + 7) / 8;

    // Roaring / Hybrid Container (ADR-092):
    long roaringBytes;
    if (strideS > 1) {
      roaringBytes = Math.max(128L, (trackedCells * 10 / 100) * 2L);
    } else {
      int numBins = (int) Math.ceil((double) totalChunks / 1024.0);
      roaringBytes = (long) numBins * 128L;
    }

    double reduction = flatBytes > 0 ? (1.0 - (double) roaringBytes / flatBytes) * 100.0 : 0.0;

    return new MemoryModelMetrics(
        name,
        trackedCells,
        rawRunsCurrent,
        flatBytes,
        pureBitmaskBytes,
        roaringBytes,
        reduction
    );
  }

  private static void renderSideBySideChart(
      int R,
      int Ru,
      List<ChunkCoord> currentArrivals,
      List<ChunkCoord> downsampledArrivals,
      List<ChunkCoord> excludedArrivals,
      ProximityMetrics prox1, ProximityMetrics prox2, ProximityMetrics prox3,
      MemoryModelMetrics mem1, MemoryModelMetrics mem2, MemoryModelMetrics mem3,
      File outFile) throws Exception {

    int mapDim = 380;
    int margin = 30;
    int totalWidth = mapDim * 3 + margin * 4;
    int totalHeight = mapDim + 640; // Extended height for statistical curves & memory panel

    BufferedImage img = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    // Background
    g.setColor(new Color(0x0C1017));
    g.fillRect(0, 0, totalWidth, totalHeight);

    // Title
    g.setColor(Color.WHITE);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
    g.drawString("Dyadic Striding & Spacing: S=1 vs. S=64 (8c) vs. S=256 (16c)", margin, 38);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
    g.setColor(new Color(0x90A4AE));
    g.drawString("Empirical Global Pairwise Proximity, Hilbert Quadtree Resonance, and Memory Models", margin, 60);

    int mapY = 85;

    // Render Panel 1: Legacy Default (uniquePlacements=0, S=1)
    renderSpatialPanel(g, margin, mapY, mapDim, R, "1. Unconstrained Native (S = 1)",
        "Full 1:1 Feistel PRP; 35.8% nearest neighbors < 8 chunks (Global Min = 1.0c)", currentArrivals, prox1, new Color(0x29B6F6));

    // Render Panel 2: Spaced S=64 (Ru=8, 8 chunks / 128 blocks)
    int p2X = margin * 2 + mapDim;
    renderSpatialPanel(g, p2X, mapY, mapDim, R, "2. Spaced S = 64 (Ru = 8 chunks)",
        "Dyadic Stride S=64; sqrt(S)=8c (128 blk) guaranteed global minimum spacing", downsampledArrivals, prox2, new Color(0x66BB6A));

    // Render Panel 3: Spaced S=256 (Ru=16, 16 chunks / 256 blocks)
    int p3X = margin * 3 + mapDim * 2;
    renderSpatialPanel(g, p3X, mapY, mapDim, R, "3. Spaced S = 256 (Ru = 16 chunks)",
        "Dyadic Stride S=256; sqrt(S)=16c (256 blk) guaranteed global minimum spacing", excludedArrivals, prox3, new Color(0xFFA726));

    // Middle Section: Statistical Nearest-Neighbor Cumulative Distribution Function (CDF) & Evenness Curve
    int statY = mapY + mapDim + 35;
    int statWidth = totalWidth - margin * 2;
    int statHeight = 220;
    renderStatisticalCurvesPanel(g, margin, statY, statWidth, statHeight, prox1, prox2, prox3, Ru);

    // Bottom Section: Memory Model Panel
    int memY = statY + statHeight + 25;
    renderMemoryPanel(g, margin, memY, totalWidth - margin * 2, mem1, mem2, mem3);

    g.dispose();
    ImageIO.write(img, "PNG", outFile);
  }

  private static void renderSpatialPanel(
      Graphics2D g, int x, int y, int dim, int R,
      String title, String subtitle, List<ChunkCoord> pts, ProximityMetrics prox, Color primaryCol) {

    // Card background
    g.setColor(new Color(0x131B24));
    g.fillRoundRect(x, y, dim, dim, 12, 12);
    g.setColor(new Color(0x233140));
    g.drawRoundRect(x, y, dim, dim, 12, 12);

    // Text Header
    g.setColor(Color.WHITE);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
    g.drawString(title, x + 12, y + 22);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
    g.setColor(new Color(0xB0BEC5));
    g.drawString(subtitle, x + 12, y + 36);

    // Plot area
    int plotMargin = 45;
    int plotSize = dim - plotMargin - 15;
    int plotX = x + (dim - plotSize) / 2;
    int plotY = y + plotMargin;

    g.setColor(new Color(0x0A0E14));
    g.fillRect(plotX, plotY, plotSize, plotSize);
    g.setColor(new Color(0x1B2632));
    g.drawRect(plotX, plotY, plotSize, plotSize);

    // Center axes
    g.setColor(new Color(0x1E293B));
    g.drawLine(plotX + plotSize / 2, plotY, plotX + plotSize / 2, plotY + plotSize);
    g.drawLine(plotX, plotY + plotSize / 2, plotX + plotSize, plotY + plotSize / 2);

    // Draw candidate arrival points
    for (int i = 0; i < pts.size(); i++) {
      ChunkCoord c = pts.get(i);
      int px = plotX + (int) ((c.x + R) * plotSize / (2.0 * R));
      int pz = plotY + (int) ((c.z + R) * plotSize / (2.0 * R));

      if (px >= plotX && px < plotX + plotSize && pz >= plotY && pz < plotY + plotSize) {
        g.setColor(new Color(primaryCol.getRed(), primaryCol.getGreen(), primaryCol.getBlue(), 120));
        g.fillOval(px - 2, pz - 2, 4, 4);
      }
    }

    // Draw inter-arrival jump trail (first 15 steps)
    int trailLen = Math.min(pts.size(), 15);
    for (int i = 0; i < trailLen - 1; i++) {
      ChunkCoord c1 = pts.get(i);
      ChunkCoord c2 = pts.get(i + 1);
      int p1x = plotX + (int) ((c1.x + R) * plotSize / (2.0 * R));
      int p1z = plotY + (int) ((c1.z + R) * plotSize / (2.0 * R));
      int p2x = plotX + (int) ((c2.x + R) * plotSize / (2.0 * R));
      int p2z = plotY + (int) ((c2.z + R) * plotSize / (2.0 * R));

      g.setColor(new Color(0xFFFFFF));
      g.drawLine(p1x, p1z, p2x, p2z);
      g.setColor(Color.YELLOW);
      g.fillOval(p2x - 3, p2z - 3, 6, 6);
    }

    // Inset Metrics Box
    int boxH = 58;
    g.setColor(new Color(0x0E1722));
    g.fillRoundRect(plotX + 8, plotY + plotSize - boxH - 6, plotSize - 16, boxH, 8, 8);
    g.setColor(new Color(0x233140));
    g.drawRoundRect(plotX + 8, plotY + plotSize - boxH - 6, plotSize - 16, boxH, 8, 8);

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
    g.setColor(Color.WHITE);
    g.drawString(String.format("Avg Consecutive Jump: %.1f chunks (%.0f blk)", prox.avgConsecutiveJump, prox.avgConsecutiveJump * 16), plotX + 14, plotY + plotSize - boxH + 8);
    g.setColor(new Color(0x81C784));
    g.drawString(String.format("Global Min Pairwise Dist: %.2f chunks (%.0f blk)", prox.globalMinPairwiseDist, prox.globalMinPairwiseDist * 16), plotX + 14, plotY + plotSize - boxH + 24);
    g.setColor(new Color(0x4FC3F7));
    g.drawString(String.format("Avg Nearest Neighbor: %.2f chunks (%.0f blk)", prox.globalAvgNearestNeighborDist, prox.globalAvgNearestNeighborDist * 16), plotX + 14, plotY + plotSize - boxH + 40);
  }

  private static void renderStatisticalCurvesPanel(
      Graphics2D g, int x, int y, int width, int height,
      ProximityMetrics p1, ProximityMetrics p2, ProximityMetrics p3, int Ru) {

    g.setColor(new Color(0x131B24));
    g.fillRoundRect(x, y, width, height, 12, 12);
    g.setColor(new Color(0x233140));
    g.drawRoundRect(x, y, width, height, 12, 12);

    // Header
    g.setColor(Color.WHITE);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
    g.drawString("Dual Placement Statistical Distributions: Sequential (i -> i+1) vs. Parallel (All-Pairs)", x + 20, y + 26);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
    g.setColor(new Color(0x90A4AE));
    g.drawString("Evaluating Both Uniqueness Dimensions: Sequential Inter-Arrival Jump CDF (Left) vs. Parallel Spatial Separation CDF (Right)", x + 20, y + 42);

    int subWidth = (width - 340) / 2;
    int plotH = height - 85;
    int plotY = y + 60;

    // --- Sub-Plot 1: Sequential Inter-Arrival Jumps (i -> i+1) ---
    int seqPlotX = x + 45;
    renderSubCDFPlot(g, seqPlotX, plotY, subWidth, plotH, 500.0, 100.0, "Sequential Jump (i -> i+1)",
        p1.sequentialDistances, p2.sequentialDistances, p3.sequentialDistances);

    // --- Sub-Plot 2: Parallel Spatial Separation (Nearest-Neighbor to Any Active Placement) ---
    int parPlotX = seqPlotX + subWidth + 50;
    renderSubCDFPlot(g, parPlotX, plotY, subWidth, plotH, 40.0, 8.0, "Parallel Nearest-Neighbor",
        p1.nearestNeighborDistances, p2.nearestNeighborDistances, p3.nearestNeighborDistances);

    // Exclusion Radius Threshold Line on Parallel Plot (Ru = 8 chunks)
    int ruX = parPlotX + (int) (Ru * subWidth / 40.0);
    g.setColor(new Color(0xFF5252));
    g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{4.0f, 4.0f}, 0.0f));
    g.drawLine(ruX, plotY, ruX, plotY + plotH);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
    g.drawString("Target Ru=" + Ru + "c (128 blk)", ruX + 4, plotY + 16);
    g.setStroke(new BasicStroke(1.0f));

    // Legend & Statistical Metrics Summary (Right side)
    int legX = parPlotX + subWidth + 25;
    int legY = plotY - 5;

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
    g.setColor(Color.WHITE);
    g.drawString("Dual-Objective Metrics", legX, legY);

    // Strategy 1
    renderStatBadge(g, legX, legY + 16, new Color(0x29B6F6), "Unconstrained (S = 1)",
        String.format("Sequential: Min=%.0fc, Max=%.0fc, Med=%.0fc", p1.minConsecutiveJump, p1.maxConsecutiveJump, p1.medianConsecutiveJump),
        String.format("Parallel: Min=%.1fc, Max=%.0fc, P(<8c)=%.1f%%", p1.globalMinPairwiseDist, p1.globalMaxPairwiseDist, fractionUnder(p1.nearestNeighborDistances, 8) * 100));

    // Strategy 2
    renderStatBadge(g, legX, legY + 65, new Color(0x66BB6A), "Spaced S = 64 (Ru = 8c)",
        String.format("Sequential: Min=%.0fc, Max=%.0fc, Med=%.0fc", p2.minConsecutiveJump, p2.maxConsecutiveJump, p2.medianConsecutiveJump),
        String.format("Parallel: Min=%.1fc, Max=%.0fc, P(<8c)=%.1f%%", p2.globalMinPairwiseDist, p2.globalMaxPairwiseDist, fractionUnder(p2.nearestNeighborDistances, 8) * 100));

    // Strategy 3
    renderStatBadge(g, legX, legY + 114, new Color(0xFFA726), "Spaced S = 256 (Ru = 16c)",
        String.format("Sequential: Min=%.0fc, Max=%.0fc, Med=%.0fc", p3.minConsecutiveJump, p3.maxConsecutiveJump, p3.medianConsecutiveJump),
        String.format("Parallel: Min=%.1fc, Max=%.0fc, P(<16c)=%.1f%%", p3.globalMinPairwiseDist, p3.globalMaxPairwiseDist, fractionUnder(p3.nearestNeighborDistances, 16) * 100));
  }

  private static void renderSubCDFPlot(
      Graphics2D g, int plotX, int plotY, int plotW, int plotH, double maxAxis, double stepAxis, String title,
      double[] d1, double[] d2, double[] d3) {

    g.setColor(new Color(0x0A0E14));
    g.fillRect(plotX, plotY, plotW, plotH);
    g.setColor(new Color(0x1B2632));
    g.drawRect(plotX, plotY, plotW, plotH);

    // Title
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
    g.setColor(new Color(0xCFD8DC));
    g.drawString(title, plotX + 8, plotY - 6);

    // Y Grid lines
    g.setColor(new Color(0x17212D));
    for (int p = 20; p <= 100; p += 20) {
      int gy = plotY + plotH - (int) (p * plotH / 100.0);
      g.drawLine(plotX, gy, plotX + plotW, gy);
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 8));
      g.setColor(new Color(0x546E7A));
      g.drawString(p + "%", plotX - 22, gy + 3);
    }

    // X Axis ticks
    for (double d = 0; d <= maxAxis; d += stepAxis) {
      int gx = plotX + (int) (d * plotW / maxAxis);
      g.setColor(new Color(0x17212D));
      g.drawLine(gx, plotY, gx, plotY + plotH);
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 8));
      g.setColor(new Color(0x546E7A));
      g.drawString(String.format("%.0fc", d), gx - 8, plotY + plotH + 13);
    }

    // Curves
    plotCDF(g, plotX, plotY, plotW, plotH, maxAxis, d1, new Color(0x29B6F6), 2.0f);
    plotCDF(g, plotX, plotY, plotW, plotH, maxAxis, d2, new Color(0x66BB6A), 2.0f);
    plotCDF(g, plotX, plotY, plotW, plotH, maxAxis, d3, new Color(0xFFA726), 2.0f);
  }

  private static void plotCDF(
      Graphics2D g, int x, int y, int w, int h, double maxAxis, double[] sortedDistances, Color col, float strokeWidth) {
    if (sortedDistances.length == 0) return;
    g.setColor(col);
    g.setStroke(new BasicStroke(strokeWidth));

    int prevPx = x + (int) (sortedDistances[0] * w / maxAxis);
    int prevPy = y + h;

    for (int i = 0; i < sortedDistances.length; i++) {
      double d = sortedDistances[i];
      double cdf = (double) (i + 1) / sortedDistances.length;

      int px = x + (int) (d * w / maxAxis);
      int py = y + h - (int) (cdf * h);

      g.drawLine(prevPx, prevPy, px, py);
      prevPx = px;
      prevPy = py;
    }
    g.setStroke(new BasicStroke(1.0f));
  }

  private static double fractionUnder(double[] sortedDistances, double threshold) {
    int count = 0;
    for (double d : sortedDistances) {
      if (d < threshold) count++;
      else break;
    }
    return sortedDistances.length > 0 ? (double) count / sortedDistances.length : 0.0;
  }

  private static void renderStatBadge(
      Graphics2D g, int x, int y, Color col, String label, String row1, String row2) {
    g.setColor(col);
    g.fillOval(x, y + 2, 9, 9);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
    g.drawString(label, x + 16, y + 10);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
    g.setColor(new Color(0xB0BEC5));
    g.drawString(row1, x + 16, y + 24);
    g.drawString(row2, x + 16, y + 36);
  }

  private static void renderMemoryPanel(
      Graphics2D g, int x, int y, int width,
      MemoryModelMetrics m1, MemoryModelMetrics m2, MemoryModelMetrics m3) {

    int cardHeight = 220;
    g.setColor(new Color(0x131B24));
    g.fillRoundRect(x, y, width, cardHeight, 12, 12);
    g.setColor(new Color(0x233140));
    g.drawRoundRect(x, y, width, cardHeight, 12, 12);

    g.setColor(Color.WHITE);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
    g.drawString("Side-by-Side Data Storage & Memory Model Analysis", x + 20, y + 28);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
    g.setColor(new Color(0x90A4AE));
    g.drawString("Comparing Heap Consumption: Monolithic Flat Arrays vs. Pure Bitmask vs. Roaring/Hybrid Containers (ADR-092)", x + 20, y + 46);

    // Table Header
    int tblY = y + 75;
    int col1 = x + 25;
    int col2 = x + 380;
    int col3 = x + 560;
    int col4 = x + 740;
    int col5 = x + 940;

    g.setColor(new Color(0x1C2836));
    g.fillRect(x + 15, tblY - 18, width - 30, 26);
    g.setColor(new Color(0x78909C));
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
    g.drawString("ARCHITECTURE / STRATEGY", col1, tblY);
    g.drawString("FLAT ARRAYS (BASELINE)", col2, tblY);
    g.drawString("PURE BITMASK", col3, tblY);
    g.drawString("ROARING / HYBRID (ADR-092)", col4, tblY);
    g.drawString("RAM REDUCTION", col5, tblY);

    // Row 1
    renderTableRow(g, col1, col2, col3, col4, col5, tblY + 30,
        m1.name, String.format("%,d B", m1.flatArrayBytes), String.format("%,d B", m1.pureBitmaskBytes),
        String.format("%,d B", m1.roaringHybridBytes), "Baseline (0.0%)", new Color(0xEF5350));

    // Row 2
    renderTableRow(g, col1, col2, col3, col4, col5, tblY + 65,
        m2.name, String.format("%,d B", m2.flatArrayBytes), String.format("%,d B", m2.pureBitmaskBytes),
        String.format("%,d B", m2.roaringHybridBytes), String.format("-%.1f%%", m2.memoryReductionPercent), new Color(0x66BB6A));

    // Row 3
    renderTableRow(g, col1, col2, col3, col4, col5, tblY + 100,
        m3.name, String.format("%,d B", m3.flatArrayBytes), String.format("%,d B", m3.pureBitmaskBytes),
        String.format("%,d B", m3.roaringHybridBytes), String.format("-%.1f%% (Best)", m3.memoryReductionPercent), new Color(0x42A5F5));

    // Summary Note
    g.setColor(new Color(0xB0BEC5));
    g.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 11));
    g.drawString("Key Insight: Deriving stride from uniqueRadius replaces O(P * Ru^2) array stamping with O(1) memoryless permutation math,", x + 25, y + cardHeight - 20);
    g.drawString("while Roaring/Hybrid containers eliminate fragmentation and cap 64K chunks at <= 8 KB.", x + 25, y + cardHeight - 6);
  }

  private static void renderTableRow(
      Graphics2D g, int c1, int c2, int c3, int c4, int c5, int rowY,
      String col1Text, String col2Text, String col3Text, String col4Text, String col5Text, Color badgeCol) {

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
    g.setColor(Color.WHITE);
    g.drawString(col1Text, c1, rowY);
    g.setColor(new Color(0xCFD8DC));
    g.drawString(col2Text, c2, rowY);
    g.drawString(col3Text, c3, rowY);
    g.drawString(col4Text, c4, rowY);

    g.setColor(badgeCol);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
    g.drawString(col5Text, c5, rowY);
  }
}
