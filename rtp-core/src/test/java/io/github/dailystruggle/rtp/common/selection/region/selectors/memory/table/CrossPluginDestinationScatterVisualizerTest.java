package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import io.github.dailystruggle.rtp.common.benchmark.LosslessChunkOutcomeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class CrossPluginDestinationScatterVisualizerTest {

  public static void main(String[] args) throws Exception {
    new CrossPluginDestinationScatterVisualizerTest().testRenderCrossPluginDestinationScatterChart();
  }

  record Point2D(long x, long z) {}

  record EngineStats(
      String label,
      String displayName,
      String subtitle,
      Color accentColor,
      List<Point2D> points,
      Map<Point2D, Integer> pointFrequencies,
      int totalAttempts,
      int uniqueCount,
      int duplicateCount,
      double duplicatePercent,
      double clarkEvansR,
      double nnMin,
      double nnP50,
      double nnMean,
      double avgConsecutiveHop,
      double minChebyshevR,
      double maxChebyshevR,
      double innerRingDensity,
      double outerRingDensity,
      boolean centerVoidEnforced,
      int waterLandings,
      double waterPercent,
      double tvDistance,
      String notes) {}

  @Test
  @DisplayName("Render Dark UI Front-Page Cross-Plugin Destination Scatter Comparison Chart")
  public void testRenderCrossPluginDestinationScatterChart() throws Exception {
    System.out.println("[DEBUG_LOG] === RENDERING CROSS-PLUGIN DESTINATIONS SCATTER CHART ===");

    // 0. Load lossless terrain outcome map to render actual landmasses underneath points
    File cacheFile = new File("build/cache/mca_world_r1024_outcomes.bin");
    Path regionDir = Path.of("C:\\GameServers\\Minecraft\\testServer\\RTP-Folia\\26.1\\world\\dimensions\\minecraft\\overworld\\region");
    if (!Files.isDirectory(regionDir)) {
      regionDir = Path.of("testdata-world/overworld/region");
    }
    LosslessChunkOutcomeMap outcomeMap = null;
    try {
      outcomeMap = LosslessChunkOutcomeMap.getOrCreate(cacheFile, regionDir);
    } catch (Throwable t) {
      System.out.println("[DEBUG_LOG] Note: Outcome map failed to load, falling back to dark background: " + t.getMessage());
    }

    File runCsv = new File("C:/GameServers/Minecraft/testServer/RTP-Paper/26.1/plugins/StressTestRTP/runs/20260923-000854.csv");
    if (!runCsv.exists()) {
      runCsv = new File("C:\\GameServers\\Minecraft\\testServer\\RTP-Paper\\26.1\\plugins\\StressTestRTP\\runs\\20260923-000854.csv");
    }
    if (!runCsv.exists()) {
      runCsv = new File("C:/GameServers/Minecraft/testServer/RTP-Folia/26.1/plugins/StressTestRTP/runs/20260922-224940.csv");
    }

    System.out.println("[DEBUG_LOG] Resolved runCsv: " + runCsv.getAbsolutePath() + " (exists=" + runCsv.exists() + ", len=" + runCsv.length() + ")");

    List<File> csvFiles = new ArrayList<>();
    if (runCsv.exists()) {
      csvFiles.add(runCsv);
    } else {
      File single = new File("20260922-181155.csv");
      if (single.exists()) csvFiles.add(single);
    }

    Map<String, List<Point2D>> rawPoints = new LinkedHashMap<>();
    rawPoints.put("rtp", new ArrayList<>());
    rawPoints.put("ezrtp", new ArrayList<>());
    rawPoints.put("justrtp", new ArrayList<>());

    for (File f : csvFiles) {
      if (!f.exists()) continue;
      System.out.println("[DEBUG_LOG] Ingesting placements from: " + f.getAbsolutePath() + " (" + f.length() + " bytes)");
      try (BufferedReader br = new BufferedReader(new FileReader(f))) {
        String headerLine = br.readLine();
        if (headerLine == null) continue;

        String[] headers = headerLine.split(",");
        int targetIdx = -1;
        int successIdx = -1;
        int toXIdx = -1;
        int toZIdx = -1;
        for (int i = 0; i < headers.length; i++) {
          String h = headers[i].trim();
          if (h.equalsIgnoreCase("target_label")) targetIdx = i;
          else if (h.equalsIgnoreCase("success")) successIdx = i;
          else if (h.equalsIgnoreCase("to_x")) toXIdx = i;
          else if (h.equalsIgnoreCase("to_z")) toZIdx = i;
        }

        if (targetIdx == -1 || toXIdx == -1 || toZIdx == -1) continue;

        String line;
        while ((line = br.readLine()) != null) {
          String[] cols = line.split(",", -1);
          if (cols.length <= Math.max(targetIdx, Math.max(toXIdx, toZIdx))) continue;
          if (successIdx != -1 && cols.length > successIdx) {
            String s = cols[successIdx].trim();
            if (!s.equalsIgnoreCase("true")) continue;
          }
          String target = cols[targetIdx].trim().toLowerCase();
          try {
            String sx = cols[toXIdx].trim();
            String sz = cols[toZIdx].trim();
            if (sx.isEmpty() || sz.isEmpty()) continue;

            double x = Double.parseDouble(sx);
            double z = Double.parseDouble(sz);
            if (!Double.isNaN(x) && !Double.isNaN(z)) {
              // Ignore unplaced default origin markers (0.000, 0.000) from aborted teleports
              if (Math.abs(x) < 0.1 && Math.abs(z) < 0.1) continue;
              if (rawPoints.containsKey(target)) {
                // Round to Minecraft integer block coordinates
                rawPoints.get(target).add(new Point2D(Math.round(x), Math.round(z)));
              }
            }
          } catch (NumberFormatException ignored) {
          }
        }
      }
    }

    // Process statistics for each engine
    List<EngineStats> statsList = new ArrayList<>();

    // 1. LeafRTP-Pro (rtp)
    statsList.add(computeStats(
        "rtp",
        "1. LeafRTP-Pro (Dyadic Stride Feistel)",
        "Equalized square mode; strict center void (r >= 1024); uniform spatial density",
        new Color(0x38EF7D), // Emerald Green
        rawPoints.get("rtp"),
        16384.0,
        1024.0,
        outcomeMap,
        "Paper 26.1 run; 15.98 TP/s; 0 duplicate landings (100% unique); 0 proximity collisions (<=48b)"));

    // 2. EzRTP (ezrtp)
    statsList.add(computeStats(
        "ezrtp",
        "2. EzRTP (Square Search Mode)",
        "Equalized square search; water platforms off; 8 timeouts; 107 proximity collisions",
        new Color(0x4FACFE), // Blue
        rawPoints.get("ezrtp"),
        16384.0,
        1024.0,
        outcomeMap,
        "Paper 26.1 run; ice platforms disabled; 8 failures / timeouts; 107 proximity collisions"));

    // 3. JustRTP (justrtp)
    statsList.add(computeStats(
        "justrtp",
        "3. JustRTP (Square Shape Mode)",
        "Equalized square box; 167 duplicate landings (4.11%); 324 proximity collisions (<=48b)",
        new Color(0xFFA726), // Amber
        rawPoints.get("justrtp"),
        16384.0,
        1024.0,
        outcomeMap,
        "Paper 26.1 run; 167 duplicate landings (4.11%); 32 failures / timeouts; severe clustering"));

    // Render chart
    renderChart(statsList, outcomeMap);
  }

  private EngineStats computeStats(
      String label,
      String displayName,
      String subtitle,
      Color accent,
      List<Point2D> pts,
      double outerRadius,
      double expectedCenterRadius,
      LosslessChunkOutcomeMap outcomeMap,
      String notes) {

    int n = pts.size();
    if (n == 0) {
      return new EngineStats(label, displayName, subtitle, accent, pts, new LinkedHashMap<>(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, 0, 0.0, 0.0, notes);
    }

    Map<Point2D, Integer> freqMap = new LinkedHashMap<>();
    for (Point2D p : pts) {
      freqMap.put(p, freqMap.getOrDefault(p, 0) + 1);
    }

    int uniqueCount = freqMap.size();
    int duplicateCount = n - uniqueCount;
    double duplicatePercent = (100.0 * duplicateCount) / n;

    // Consecutive hop jump distance
    double totalHop = 0;
    for (int i = 0; i < n - 1; i++) {
      Point2D p1 = pts.get(i);
      Point2D p2 = pts.get(i + 1);
      totalHop += Math.hypot(p1.x - p2.x, p1.z - p2.z);
    }
    double avgConsecutiveHop = n > 1 ? totalHop / (n - 1) : 0;

    // Chebyshev bounds
    double minChebyshev = Double.MAX_VALUE;
    double maxChebyshev = 0;
    for (Point2D p : pts) {
      double r = Math.max(Math.abs(p.x), Math.abs(p.z));
      if (r < minChebyshev) minChebyshev = r;
      if (r > maxChebyshev) maxChebyshev = r;
    }

    // Nearest Neighbor distances
    double[] nn = new double[n];
    double sumNN = 0;
    for (int i = 0; i < n; i++) {
      Point2D pi = pts.get(i);
      double minD = Double.MAX_VALUE;
      for (int j = 0; j < n; j++) {
        if (i == j) continue;
        double d = Math.hypot(pi.x - pts.get(j).x, pi.z - pts.get(j).z);
        if (d < minD) minD = d;
      }
      nn[i] = minD;
      sumNN += minD;
    }
    Arrays.sort(nn);
    double nnMin = nn[0];
    double nnP50 = nn[n / 2];
    double nnMean = sumNN / n;

    // Clark-Evans Ratio R: observed_mean / expected_mean
    double totalArea = Math.pow(2 * outerRadius, 2) - Math.pow(2 * Math.min(minChebyshev, expectedCenterRadius), 2);
    double densityRho = n / totalArea;
    double expectedMeanNN = 0.5 / Math.sqrt(densityRho);
    double clarkEvansR = nnMean / expectedMeanNN;

    // Radial ring density
    double rInnerMin = expectedCenterRadius;
    double rInnerMax = expectedCenterRadius + (outerRadius - expectedCenterRadius) * 0.25;
    double rOuterMin = outerRadius * 0.75;
    double rOuterMax = outerRadius;

    double areaInner = (Math.pow(2 * rInnerMax, 2) - Math.pow(2 * rInnerMin, 2)) / 1_000_000.0;
    double areaOuter = (Math.pow(2 * rOuterMax, 2) - Math.pow(2 * rOuterMin, 2)) / 1_000_000.0;

    int countInner = 0;
    int countOuter = 0;
    for (Point2D p : pts) {
      double r = Math.max(Math.abs(p.x), Math.abs(p.z));
      if (r >= rInnerMin && r < rInnerMax) countInner++;
      if (r >= rOuterMin && r <= rOuterMax) countOuter++;
    }
    double innerDensity = areaInner > 0 ? countInner / areaInner : 0;
    double outerDensity = areaOuter > 0 ? countOuter / areaOuter : 0;

    boolean centerVoid = minChebyshev >= (expectedCenterRadius - 5.0);

    // Water/Ocean Landings check using LosslessChunkOutcomeMap
    int waterCount = 0;
    if (outcomeMap != null) {
      for (Point2D p : pts) {
        int cx = (int) Math.floor(p.x / 16.0);
        int cz = (int) Math.floor(p.z / 16.0);
        byte oc = outcomeMap.getOutcome(cx, cz);
        if (oc == LosslessChunkOutcomeMap.OUTCOME_WATER) {
          waterCount++;
        }
      }
    }
    double waterPercent = n > 0 ? (100.0 * waterCount) / n : 0.0;

    // Total Variation (TV) Distance vs Ideal Uniform 2D Distribution:
    // Partition the square [-16384, +16384]^2 \ [-1024, +1024]^2 into a 16x16 grid (256 cells)
    // Expected probability per valid cell = 1 / valid_cells_count
    int gridBins = 16;
    double binSpan = (2.0 * outerRadius) / gridBins; // 2048 blocks per grid cell
    int[][] gridCounts = new int[gridBins][gridBins];
    boolean[][] validCells = new boolean[gridBins][gridBins];
    int validCellsCount = 0;

    for (int gx = 0; gx < gridBins; gx++) {
      double cellMinX = -outerRadius + gx * binSpan;
      double cellMaxX = cellMinX + binSpan;
      for (int gz = 0; gz < gridBins; gz++) {
        double cellMinZ = -outerRadius + gz * binSpan;
        double cellMaxZ = cellMinZ + binSpan;
        // Cell center
        double midX = (cellMinX + cellMaxX) / 2.0;
        double midZ = (cellMinZ + cellMaxZ) / 2.0;
        if (Math.max(Math.abs(midX), Math.abs(midZ)) >= expectedCenterRadius) {
          validCells[gx][gz] = true;
          validCellsCount++;
        }
      }
    }

    for (Point2D p : pts) {
      int gx = (int) Math.floor((p.x + outerRadius) / binSpan);
      int gz = (int) Math.floor((p.z + outerRadius) / binSpan);
      if (gx >= 0 && gx < gridBins && gz >= 0 && gz < gridBins) {
        gridCounts[gx][gz]++;
      }
    }

    double tvDistance = 0.0;
    if (validCellsCount > 0 && n > 0) {
      double pUniform = 1.0 / validCellsCount;
      double sumAbsDiff = 0.0;
      for (int gx = 0; gx < gridBins; gx++) {
        for (int gz = 0; gz < gridBins; gz++) {
          if (validCells[gx][gz]) {
            double pActual = (double) gridCounts[gx][gz] / n;
            sumAbsDiff += Math.abs(pActual - pUniform);
          }
        }
      }
      tvDistance = 0.5 * sumAbsDiff; // TV distance is in [0, 1]
    }

    EngineStats stats = new EngineStats(
        label,
        displayName,
        subtitle,
        accent,
        pts,
        freqMap,
        n,
        uniqueCount,
        duplicateCount,
        duplicatePercent,
        clarkEvansR,
        nnMin,
        nnP50,
        nnMean,
        avgConsecutiveHop,
        minChebyshev,
        maxChebyshev,
        innerDensity,
        outerDensity,
        centerVoid,
        waterCount,
        waterPercent,
        tvDistance,
        notes);

    System.out.printf("[DEBUG_LOG] ENGINE STATS: %s (label=%s)%n", stats.displayName(), stats.label());
    System.out.printf("  Sample Size: %d, Unique: %d, Duplicates: %d (%.2f%%)%n", stats.totalAttempts(), stats.uniqueCount(), stats.duplicateCount(), stats.duplicatePercent());
    System.out.printf("  NN Dist: min=%.1f, p50=%.1f, mean=%.1f blk%n", stats.nnMin(), stats.nnP50(), stats.nnMean());
    System.out.printf("  Clark-Evans R: %.4f%n", stats.clarkEvansR());
    System.out.printf("  TV Distance vs Uniform: %.4f%n", stats.tvDistance());
    System.out.printf("  Water Landings: %d (%.2f%%)%n", stats.waterLandings(), stats.waterPercent());
    System.out.printf("  Avg Consecutive Jump: %.1f blk%n", stats.avgConsecutiveHop());
    System.out.printf("  Chebyshev Bounds: [%.1f, %.1f]%n", stats.minChebyshevR(), stats.maxChebyshevR());

    return stats;
  }

  private void renderChart(List<EngineStats> statsList, LosslessChunkOutcomeMap outcomeMap) throws Exception {
    int panelDim = 520;
    int margin = 30;
    int numPanels = statsList.size();
    int totalWidth = panelDim * numPanels + margin * (numPanels + 1);
    int headerHeight = 110;
    int footerHeight = 240;
    int totalHeight = headerHeight + panelDim + footerHeight + margin * 2;

    BufferedImage img = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

    // Dark Background
    g.setColor(new Color(0x0C, 0x10, 0x17));
    g.fillRect(0, 0, totalWidth, totalHeight);

    // Title Header
    g.setColor(Color.WHITE);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
    g.drawString("CROSS-PLUGIN SPATIAL DISTRIBUTION: ALL PLACEMENTS COMPARISON", margin, 38);

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
    g.setColor(new Color(0x90, 0xA4, 0xAE));
    g.drawString(
        "Folia 26.1.2 Benchmark: 4,096 Teleports per Plugin | Terrain Landmass Background | Duplicate Collisions Highlighted",
        margin, 62);

    // Legend for points
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
    int legX = margin;
    int legY = 88;
    g.setColor(new Color(0xFF, 0x17, 0x44));
    g.fillOval(legX, legY - 8, 8, 8);
    g.setColor(new Color(0xCF, 0xD8, 0xDC));
    g.drawString("Duplicate Landing (Exact same block coordinate)", legX + 14, legY);

    legX += 280;
    g.setColor(new Color(0x38, 0xEF, 0x7D));
    g.fillRect(legX, legY - 7, 6, 6);
    legX += 10;
    g.setColor(new Color(0x4F, 0xAC, 0xFE));
    g.fillRect(legX, legY - 7, 6, 6);
    legX += 10;
    g.setColor(new Color(0xFF, 0xA7, 0x26));
    g.fillRect(legX, legY - 7, 6, 6);
    legX += 14;
    g.setColor(new Color(0xCF, 0xD8, 0xDC));
    g.drawString("Unique Landing (Engine Color)", legX, legY);

    legX += 200;
    g.setColor(new Color(0x18, 0x2A, 0x1E));
    g.fillRect(legX, legY - 7, 10, 10);
    g.setColor(new Color(0x28, 0x3E, 0x30));
    g.drawRect(legX, legY - 7, 10, 10);
    g.setColor(new Color(0xCF, 0xD8, 0xDC));
    g.drawString("Solid Land", legX + 16, legY);

    legX += 90;
    g.setColor(new Color(0x0C, 0x14, 0x20));
    g.fillRect(legX, legY - 7, 10, 10);
    g.setColor(new Color(0x18, 0x24, 0x34));
    g.drawRect(legX, legY - 7, 10, 10);
    g.setColor(new Color(0xCF, 0xD8, 0xDC));
    g.drawString("Ocean / Water", legX + 16, legY);

    int panelY = headerHeight + 10;

    // Render each panel
    for (int i = 0; i < numPanels; i++) {
      int panelX = margin + i * (panelDim + margin);
      EngineStats stats = statsList.get(i);
      renderSpatialPanel(g, panelX, panelY, panelDim, stats, outcomeMap);
      renderFooterPanel(g, panelX, panelY + panelDim + 16, panelDim, footerHeight - 16, stats);
    }

    g.dispose();

    // Save outputs
    File[] targets = new File[] {
        new File("cross_plugin_destinations_scatter_chart.png"),
        new File("cross_plugin_destinations_scatter_chart_16k.png"),
        new File("../cross_plugin_destinations_scatter_chart.png"),
        new File("../cross_plugin_destinations_scatter_chart_16k.png"),
        new File("docs/assets/img/cross_plugin_destinations_scatter_chart.png"),
        new File("docs/assets/img/cross_plugin_destinations_scatter_chart_16k.png"),
        new File("../docs/assets/img/cross_plugin_destinations_scatter_chart.png"),
        new File("../docs/assets/img/cross_plugin_destinations_scatter_chart_16k.png"),
        new File("rtp-core/docs/assets/img/cross_plugin_destinations_scatter_chart.png"),
        new File("rtp-core/docs/assets/img/cross_plugin_destinations_scatter_chart_16k.png"),
        new File("../rtp-core/docs/assets/img/cross_plugin_destinations_scatter_chart.png"),
        new File("../rtp-core/docs/assets/img/cross_plugin_destinations_scatter_chart_16k.png"),
        new File("build/reports/player_distribution/cross_plugin_destinations_scatter_chart.png"),
        new File("../build/reports/player_distribution/cross_plugin_destinations_scatter_chart.png")
    };

    for (File tf : targets) {
      if (tf.getParentFile() != null && !tf.getParentFile().exists()) {
        tf.getParentFile().mkdirs();
      }
      ImageIO.write(img, "PNG", tf);
      if (tf.exists()) {
        System.out.printf("[DEBUG_LOG] Saved chart (%,d bytes) to: %s%n", tf.length(), tf.getAbsolutePath());
      }
    }
  }

  private void renderSpatialPanel(Graphics2D g, int x, int y, int size, EngineStats stats, LosslessChunkOutcomeMap outcomeMap) {
    // Card background
    g.setColor(new Color(0x13, 0x1B, 0x24));
    g.fillRoundRect(x, y, size, size, 12, 12);
    g.setColor(new Color(0x23, 0x31, 0x40));
    g.drawRoundRect(x, y, size, size, 12, 12);

    // Card Header Banner
    g.setColor(new Color(0x18, 0x22, 0x2E));
    g.fillRoundRect(x + 2, y + 2, size - 4, 48, 10, 10);
    g.setColor(stats.accentColor);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
    g.drawString(stats.displayName, x + 14, y + 23);

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
    g.setColor(new Color(0xCF, 0xD8, 0xDC));
    g.drawString(stats.subtitle, x + 14, y + 39);

    // Inner Plot Box
    int pad = 58;
    int plotSize = size - pad - 16;
    int plotX = x + 16;
    int plotY = y + pad;

    g.setColor(new Color(0x0A, 0x0E, 0x14));
    g.fillRect(plotX, plotY, plotSize, plotSize);

    // Max display coordinate range (18,000 blocks to cover 16,384 comfortably across all panels)
    double plotRange = 18000.0;

    // Render Landmasses & Oceans from LosslessChunkOutcomeMap if available
    if (outcomeMap != null) {
      BufferedImage terrainImg = new BufferedImage(plotSize, plotSize, BufferedImage.TYPE_INT_RGB);
      int rChunks = 1024;
      for (int py = 0; py < plotSize; py++) {
        double blockZ = -plotRange + ((double) py / plotSize) * (2.0 * plotRange);
        int cz = (int) Math.floor(blockZ / 16.0);
        for (int px = 0; px < plotSize; px++) {
          double blockX = -plotRange + ((double) px / plotSize) * (2.0 * plotRange);
          int cx = (int) Math.floor(blockX / 16.0);
          int rgb;
          if (cx >= -rChunks && cx < rChunks && cz >= -rChunks && cz < rChunks) {
            byte outcome = outcomeMap.getOutcome(cx, cz);
            if (outcome == LosslessChunkOutcomeMap.OUTCOME_SAFE) {
              rgb = 0x122416; // Dark moss/forest green for solid land
            } else if (outcome == LosslessChunkOutcomeMap.OUTCOME_WATER) {
              rgb = 0x0A131F; // Deep dark navy for ocean/water
            } else {
              rgb = 0x181216; // Void/lava/other
            }
          } else {
            rgb = 0x080B10; // Out of worldbounds
          }
          terrainImg.setRGB(px, py, rgb);
        }
      }
      g.drawImage(terrainImg, plotX, plotY, null);
    }

    g.setColor(new Color(0x1B, 0x26, 0x32));
    g.drawRect(plotX, plotY, plotSize, plotSize);

    // Plot Grid / Center Axes
    g.setColor(new Color(0x18, 0x24, 0x30));
    g.drawLine(plotX + plotSize / 2, plotY, plotX + plotSize / 2, plotY + plotSize);
    g.drawLine(plotX, plotY + plotSize / 2, plotX + plotSize, plotY + plotSize / 2);

    // Grid concentric box
    g.setColor(new Color(0x14, 0x1E, 0x28));
    g.drawRect(plotX + plotSize / 4, plotY + plotSize / 4, plotSize / 2, plotSize / 2);

    // Outer Target Bounding Box Outline
    double targetR = 16384.0;
    int bX1 = plotX + (int) ((-targetR + plotRange) / (2.0 * plotRange) * plotSize);
    int bZ1 = plotY + (int) ((-targetR + plotRange) / (2.0 * plotRange) * plotSize);
    int bSize = (int) ((2.0 * targetR) / (2.0 * plotRange) * plotSize);
    g.setColor(new Color(stats.accentColor.getRed(), stats.accentColor.getGreen(), stats.accentColor.getBlue(), 60));
    g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{4.0f, 4.0f}, 0.0f));
    g.drawRect(bX1, bZ1, bSize, bSize);
    g.setStroke(new BasicStroke(1.0f));

    // Center Exclusion Box Outline (1024 for all engines to match equalized center void)
    double centerR = 1024.0;
    int cX1 = plotX + (int) ((-centerR + plotRange) / (2.0 * plotRange) * plotSize);
    int cZ1 = plotY + (int) ((-centerR + plotRange) / (2.0 * plotRange) * plotSize);
    int cSize = Math.max(3, (int) ((2.0 * centerR) / (2.0 * plotRange) * plotSize));
    g.setColor(new Color(0xFF, 0x52, 0x52, 100));
    g.drawRect(cX1, cZ1, cSize, cSize);

    // Plot Destination Scatter Points (Unique points in engine color, duplicates highlighted in Red)
    Color dotCol = new Color(stats.accentColor.getRed(), stats.accentColor.getGreen(), stats.accentColor.getBlue(), 140);
    for (Map.Entry<Point2D, Integer> entry : stats.pointFrequencies.entrySet()) {
      Point2D pt = entry.getKey();
      int count = entry.getValue();
      int px = plotX + (int) ((pt.x + plotRange) / (2.0 * plotRange) * plotSize);
      int pz = plotY + (int) ((pt.z + plotRange) / (2.0 * plotRange) * plotSize);
      if (px >= plotX && px < plotX + plotSize && pz >= plotY && pz < plotY + plotSize) {
        if (count > 1) {
          // Highlight duplicate landing hotspots with glowing red halo
          g.setColor(new Color(0xFF, 0x17, 0x44, 220));
          g.fillOval(px - 3, pz - 3, 7, 7);
          g.setColor(Color.WHITE);
          g.fillRect(px - 1, pz - 1, 3, 3);
        } else {
          g.setColor(dotCol);
          g.fillRect(px - 1, pz - 1, 3, 3);
        }
      }
    }

    // Trajectory Jump Trails (First 15 teleports)
    int trailLen = Math.min(stats.points.size(), 15);
    for (int i = 0; i < trailLen - 1; i++) {
      Point2D p1 = stats.points.get(i);
      Point2D p2 = stats.points.get(i + 1);
      int p1x = plotX + (int) ((p1.x + plotRange) / (2.0 * plotRange) * plotSize);
      int p1z = plotY + (int) ((p1.z + plotRange) / (2.0 * plotRange) * plotSize);
      int p2x = plotX + (int) ((p2.x + plotRange) / (2.0 * plotRange) * plotSize);
      int p2z = plotY + (int) ((p2.z + plotRange) / (2.0 * plotRange) * plotSize);

      float progress = (float) i / (trailLen - 1);
      g.setColor(new Color(1.0f, 0.9f - progress * 0.4f, 0.2f + progress * 0.5f, 0.85f));
      g.setStroke(new BasicStroke(1.5f));
      g.drawLine(p1x, p1z, p2x, p2z);
      g.setColor(Color.WHITE);
      g.fillOval(p2x - 2, p2z - 2, 4, 4);
    }

    // Zoom badge / scale indicator
    g.setColor(new Color(0x0C, 0x14, 0x1E, 200));
    g.fillRoundRect(plotX + 8, plotY + 8, 140, 22, 6, 6);
    g.setColor(new Color(0x90, 0xA4, 0xAE));
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
    g.drawString(String.format("Scale: ±%,.0f blk box", plotRange), plotX + 14, plotY + 23);
  }

  private void renderFooterPanel(Graphics2D g, int x, int y, int width, int height, EngineStats stats) {
    g.setColor(new Color(0x13, 0x1B, 0x24));
    g.fillRoundRect(x, y, width, height, 12, 12);
    g.setColor(new Color(0x23, 0x31, 0x40));
    g.drawRoundRect(x, y, width, height, 12, 12);

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
    g.setColor(stats.accentColor);
    g.drawString("SPATIAL & STATISTICAL METRICS", x + 16, y + 20);

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
    int lineY = y + 38;
    int col1 = x + 16;
    int col2 = x + width / 2 + 10;

    // Line 1: Sample Size & Unique Points
    g.setColor(new Color(0xB0, 0xBE, 0xC5));
    g.drawString(String.format("Sample Size (N): %,d", stats.totalAttempts), col1, lineY);
    g.drawString(String.format("Unique Points: %,d", stats.uniqueCount), col2, lineY);

    lineY += 17;
    // Line 2: Duplicates & Proximity
    if (stats.duplicateCount == 0) {
      g.setColor(new Color(0x38, 0xEF, 0x7D));
      g.drawString("Duplicates: 0 (0.0% - Zero Dupes)", col1, lineY);
    } else {
      g.setColor(new Color(0xFF, 0x52, 0x52));
      g.drawString(String.format("Duplicates: %,d (%.2f%%)", stats.duplicateCount, stats.duplicatePercent), col1, lineY);
    }
    if (stats.nnMin >= 48.0) {
      g.setColor(new Color(0x38, 0xEF, 0x7D));
      g.drawString("Proximity (<=48b): 0 collisions", col2, lineY);
    } else {
      g.setColor(new Color(0xFF, 0x52, 0x52));
      g.drawString(String.format("Proximity (<=48b): Collisions (min=%.0fb)", stats.nnMin), col2, lineY);
    }

    lineY += 17;
    // Line 3: Nearest Neighbor Distances (min, p50, mean)
    g.setColor(new Color(0xB0, 0xBE, 0xC5));
    g.drawString(String.format("NN Dist: min=%.0f, p50=%.1f, mean=%.1f blk", stats.nnMin, stats.nnP50, stats.nnMean), col1, lineY);

    lineY += 17;
    // Line 4: Clark-Evans R & Total Variation (TV) vs Ideal Uniform Distribution
    if (Math.abs(stats.clarkEvansR - 1.0) < 0.05) {
      g.setColor(new Color(0x38, 0xEF, 0x7D));
      g.drawString(String.format("Clark-Evans R: %.4f (Ideal CSR)", stats.clarkEvansR), col1, lineY);
    } else {
      g.setColor(new Color(0xFF, 0x52, 0x52));
      g.drawString(String.format("Clark-Evans R: %.4f (Clustered)", stats.clarkEvansR), col1, lineY);
    }
    g.setColor(new Color(0xCF, 0xD8, 0xDC));
    g.drawString(String.format("TV Dist to Uniform: %.4f", stats.tvDistance), col2, lineY);

    lineY += 17;
    // Line 5: Water / Ocean Landings & Center Void
    if (stats.waterLandings == 0) {
      g.setColor(new Color(0x38, 0xEF, 0x7D));
      g.drawString("Water Landings: 0 (0.0% - Dry Only)", col1, lineY);
    } else {
      g.setColor(new Color(0x4F, 0xAC, 0xFE));
      g.drawString(String.format("Water Landings: %,d (%.1f%% ice-plat)", stats.waterLandings, stats.waterPercent), col1, lineY);
    }
    if (stats.centerVoidEnforced) {
      g.setColor(new Color(0x38, 0xEF, 0x7D));
      g.drawString("Center Void: Strict (r >= 1024)", col2, lineY);
    } else {
      g.setColor(new Color(0xFF, 0x52, 0x52));
      g.drawString("Center Void: Leaked (<1024 blk)", col2, lineY);
    }

    lineY += 17;
    // Line 6: Density Inner/Outer & Avg Consecutive Jump
    g.setColor(new Color(0xB0, 0xBE, 0xC5));
    g.drawString(String.format("Density Inner/Outer: %.1f / %.1f", stats.innerRingDensity, stats.outerRingDensity), col1, lineY);
    g.drawString(String.format("Avg Jump: %,.0f blk | Box: [%,.0f, %,.0f]", stats.avgConsecutiveHop, stats.minChebyshevR, stats.maxChebyshevR), col2, lineY);

    lineY += 22;
    // Notes / Behavioral Summary box
    int noteBoxH = 50;
    g.setColor(new Color(0x18, 0x22, 0x2E));
    g.fillRoundRect(x + 10, lineY - 14, width - 20, noteBoxH, 8, 8);
    g.setColor(new Color(0x23, 0x31, 0x40));
    g.drawRoundRect(x + 10, lineY - 14, width - 20, noteBoxH, 8, 8);

    g.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 10));
    g.setColor(new Color(0xEC, 0xF0, 0xF1));
    // Wrap finding if too long
    String noteStr = "Finding: " + stats.notes;
    if (g.getFontMetrics().stringWidth(noteStr) > width - 40) {
      int splitIdx = noteStr.indexOf(';', 40);
      if (splitIdx != -1) {
        g.drawString(noteStr.substring(0, splitIdx + 1), x + 16, lineY + 6);
        g.drawString(noteStr.substring(splitIdx + 1).trim(), x + 16, lineY + 22);
      } else {
        g.drawString(noteStr, x + 16, lineY + 14);
      }
    } else {
      g.drawString(noteStr, x + 16, lineY + 14);
    }
  }
}
