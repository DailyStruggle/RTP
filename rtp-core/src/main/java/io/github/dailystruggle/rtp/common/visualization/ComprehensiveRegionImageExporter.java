package io.github.dailystruggle.rtp.common.visualization;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Diagnostic exporter rendering high-detail visualization charts and metadata to disk.
 *
 * <p>Captures:
 * <ul>
 *   <li>Desaturated regional biome field and translucent red hazard overlay.</li>
 *   <li>Archimedean-spiral selection-order path (ADR-001) showing chunk progression.</li>
 *   <li>Pre-warmed queue candidates (L1 Hot, L2 Cold, L3 Backlog) and landing points.</li>
 *   <li>Nearest Neighbor (NN) distance distribution and Clark-Evans spatial dispersion statistics.</li>
 *   <li>Multi-tier queue capacity gauges and memory run table summaries.</li>
 * </ul>
 *
 * <p>Operates 100% off-tick with zero main-thread chunk I/O (Rule S-005).</p>
 */
public final class ComprehensiveRegionImageExporter {

  private static final Color BG_DARK = new Color(0x0C, 0x10, 0x17);
  private static final Color PANEL_BG = new Color(0x16, 0x1B, 0x22);
  private static final Color PANEL_BORDER = new Color(0x30, 0x36, 0x3D);
  private static final Color TEXT_PRIMARY = new Color(0xF0, 0xF6, 0xFC);
  private static final Color TEXT_MUTED = new Color(0x8B, 0x94, 0x9E);
  private static final Color COLOR_HAZARD = new Color(0xE7, 0x4C, 0x3C, 180);
  private static final Color COLOR_OUTSIDE = new Color(0x10, 0x14, 0x1C);

  // Markers
  private static final Color COLOR_L1 = new Color(0x2E, 0xCC, 0x71); // Emerald
  private static final Color COLOR_L2 = new Color(0x34, 0x98, 0xDB); // Cyan
  private static final Color COLOR_L3 = new Color(0x9B, 0x59, 0xB6); // Purple

  private ComprehensiveRegionImageExporter() {
    throw new AssertionError("Non-instantiable utility class.");
  }

  public record ExportResult(
      File imageFile,
      File jsonFile,
      int totalPoints,
      double nnMean,
      double clarkEvansR,
      long badChunksCount,
      long executionTimeMs
  ) {}

  public record Point2D(int x, int z) {}

  /**
   * Generates and writes comprehensive diagnostic image and JSON files to plugins/RTP/charts/.
   *
   * @param region target RTP region with an active MemoryShape
   * @param outputDirectory target output directory (e.g. plugins/RTP/charts)
   * @return summary outcome details
   * @throws IOException on file write errors
   */
  public static ExportResult exportAll(Region region, File outputDirectory) throws IOException {
    return exportAll(region, outputDirectory, null, null, 1.0);
  }

  /**
   * Generates and writes comprehensive diagnostic image and JSON files to plugins/RTP/charts/
   * with custom width/height and zoom parameters.
   *
   * @param region target RTP region with an active MemoryShape
   * @param outputDirectory target output directory (e.g. plugins/RTP/charts)
   * @param customWidth explicit image/map width (null for auto)
   * @param customHeight explicit image/map height (null for auto)
   * @param zoom zoom scale factor (default 1.0)
   * @return summary outcome details
   * @throws IOException on file write errors
   */
  public static ExportResult exportAll(
      Region region,
      File outputDirectory,
      Integer customWidth,
      Integer customHeight,
      double zoom
  ) throws IOException {
    if (region == null || !(region.shape instanceof MemoryShape<?> memoryShape)) {
      throw new IllegalArgumentException("Region must have a valid MemoryShape");
    }

    long startTime = System.currentTimeMillis();
    if (!outputDirectory.exists()) {
      outputDirectory.mkdirs();
    }

    String timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.now());
    String baseName = region.name + "_all_" + timestamp;
    File imageFile = new File(outputDirectory, baseName + ".png");
    File jsonFile = new File(outputDirectory, baseName + ".json");

    // 1. Determine bounding box
    long range = memoryShape.getRange();
    if (range <= 0) {
      throw new IllegalArgumentException("Region range must be positive");
    }

    int sampleCount = 4096;
    long step = Math.max(1L, range / sampleCount);
    int minX = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int minZ = Integer.MAX_VALUE;
    int maxZ = Integer.MIN_VALUE;
    int samples = 0;

    for (long i = 0L; i < range; i += step) {
      int[] xz = memoryShape.locationToXZ(i);
      if (xz == null || xz.length < 2) continue;
      if (xz[0] < minX) minX = xz[0];
      if (xz[0] > maxX) maxX = xz[0];
      if (xz[1] < minZ) minZ = xz[1];
      if (xz[1] > maxZ) maxZ = xz[1];
      samples++;
    }

    if (samples == 0) {
      minX = -100; maxX = 100;
      minZ = -100; maxZ = 100;
    }

    int extentX = maxX - minX;
    int extentZ = maxZ - minZ;
    int pad = Math.max(16, Math.max(extentX, extentZ) / 20);
    minX -= pad; maxX += pad;
    minZ -= pad; maxZ += pad;

    // Apply zoom if requested (zoom > 1.0 zooms in around center, zoom < 1.0 zooms out)
    if (zoom > 0.0 && Math.abs(zoom - 1.0) > 0.0001) {
      double centerX = (minX + maxX) / 2.0;
      double centerZ = (minZ + maxZ) / 2.0;
      double halfW = ((maxX - minX) / 2.0) / zoom;
      double halfH = ((maxZ - minZ) / 2.0) / zoom;
      minX = (int) Math.round(centerX - halfW);
      maxX = (int) Math.round(centerX + halfW);
      minZ = (int) Math.round(centerZ - halfH);
      maxZ = (int) Math.round(centerZ + halfH);
    }

    long boundW = Math.max(1L, (long) maxX - minX);
    long boundH = Math.max(1L, (long) maxZ - minZ);

    long diameterBlocks = Math.max(boundW, boundH);
    long diameterChunks = Math.max(1L, diameterBlocks / 16L);

    // Map viewport resolution:
    // Region part of the display must maintain the same proportions (boundW : boundH) as the region.
    // When auto-sized, the largest side is capped at 4096.
    int mapW;
    int mapH;
    final int sideGap = 24;
    final int sideW = 260;
    final int rightMargin = 30;
    final int mapX = 30;
    final int mapY = 110;

    if (customWidth != null && customWidth > 0 && customHeight != null && customHeight > 0) {
      // Both dimensions explicitly specified
      int targetW = Math.max(16, customWidth);
      if (targetW > mapX + sideGap + sideW + rightMargin + 50) {
        mapW = targetW - (mapX + sideGap + sideW + rightMargin);
      } else {
        mapW = targetW;
      }
      int targetH = Math.max(16, customHeight);
      if (targetH > mapY + 60 + 50) {
        mapH = targetH - (mapY + 60);
      } else {
        mapH = targetH;
      }
    } else if (customWidth != null && customWidth > 0) {
      // Custom width specified; derive height proportionally from region bounds
      int targetW = Math.max(16, customWidth);
      if (targetW > mapX + sideGap + sideW + rightMargin + 50) {
        mapW = targetW - (mapX + sideGap + sideW + rightMargin);
      } else {
        mapW = targetW;
      }
      mapW = Math.max(16, mapW);
      mapH = (int) Math.max(16L, Math.round((double) mapW * boundH / boundW));
    } else if (customHeight != null && customHeight > 0) {
      // Custom height specified; derive width proportionally from region bounds
      int targetH = Math.max(16, customHeight);
      if (targetH > mapY + 60 + 50) {
        mapH = targetH - (mapY + 60);
      } else {
        mapH = targetH;
      }
      mapH = Math.max(16, mapH);
      mapW = (int) Math.max(16L, Math.round((double) mapH * boundW / boundH));
    } else {
      // Auto limit: preserve boundW : boundH proportions, capped at 4096 on the largest side
      long largestSide = Math.max(700L, Math.min(4096L, 2L * diameterChunks));
      if (boundW >= boundH) {
        mapW = (int) largestSide;
        mapH = (int) Math.max(16L, Math.round((double) largestSide * boundH / boundW));
      } else {
        mapH = (int) largestSide;
        mapW = (int) Math.max(16L, Math.round((double) largestSide * boundW / boundH));
      }
    }

    mapW = Math.max(16, mapW);
    mapH = Math.max(16, mapH);

    final int canvasWidth = mapX + mapW + sideGap + sideW + rightMargin;
    final int canvasHeight = mapY + mapH + 60;
    final double pxPerChunk = (double) mapW / diameterChunks;

    // 2. Collect queue points and recent teleport samples
    List<Point2D> l1Points = new ArrayList<>();
    List<Point2D> l2Points = new ArrayList<>();
    List<Point2D> l3Points = new ArrayList<>();
    List<Point2D> allPoints = new ArrayList<>();

    if (region.queueManager != null) {
      if (region.queueManager.keptLocations != null) {
        for (int i = 0; i < region.queueManager.keptLocations.size(); i++) {
          RTPLocation loc = region.queueManager.keptLocations.get(i);
          if (loc != null && loc.coords() != null) {
            Point2D pt = new Point2D(loc.coords().x(), loc.coords().z());
            l1Points.add(pt);
            allPoints.add(pt);
          }
        }
      }
      if (region.queueManager.unkeptLocations != null) {
        for (int i = 0; i < region.queueManager.unkeptLocations.size(); i++) {
          RTPLocation loc = region.queueManager.unkeptLocations.get(i);
          if (loc != null && loc.coords() != null) {
            Point2D pt = new Point2D(loc.coords().x(), loc.coords().z());
            l2Points.add(pt);
            allPoints.add(pt);
          }
        }
      }
    }

    // 3. Selection order path: trace the actual Archimedean spiral (ADR-001).
    List<Point2D> selectionPath = new ArrayList<>();
    List<Float> selectionProgress = new ArrayList<>();
    // Uniform arc-length sampling: advancing one chunk along a ring increments
    // the 1D index by a CONSTANT ~PI (independent of radius; see
    // Circle.xzToLocation). A fixed on-map pixel step therefore maps to a
    // constant index stride, so this walks the entire spiral from index 0 to
    // range without skipping revolutions near the center.
    final double desiredPixelStep = 1.5;
    final double chunksPerSegment = Math.max(0.25, desiredPixelStep / Math.max(0.25, pxPerChunk));
    final long stepIndex = Math.max(1L, (long) (Math.PI * chunksPerSegment));
    final int maxPathSegments = 300000;
    MutableRTPCoords pathCoords = new MutableRTPCoords(0, 0);
    long pathIndex = 0L;
    while (pathIndex < range && selectionPath.size() < maxPathSegments) {
      memoryShape.locationToXZ(pathIndex, pathCoords);
      selectionPath.add(new Point2D(pathCoords.x, pathCoords.z));
      selectionProgress.add((float) ((double) pathIndex / range));
      pathIndex += stepIndex;
    }
    // Pin the final ring so the path visibly reaches the outer edge (0 -> max)
    // instead of stopping one stride short.
    if (selectionPath.size() < maxPathSegments) {
      memoryShape.locationToXZ(range - 1, pathCoords);
      selectionPath.add(new Point2D(pathCoords.x, pathCoords.z));
      selectionProgress.add(1.0f);
    }

    // 4. Calculate spatial statistics (NN & Clark-Evans)
    double nnMean = 0.0;
    double nnMin = 0.0;
    double nnP50 = 0.0;
    double clarkEvansR = 1.0;
    if (allPoints.size() >= 2) {
      double[] nearestDists = new double[allPoints.size()];
      double sumDist = 0.0;
      for (int i = 0; i < allPoints.size(); i++) {
        Point2D p1 = allPoints.get(i);
        double minD = Double.MAX_VALUE;
        for (int j = 0; j < allPoints.size(); j++) {
          if (i == j) continue;
          Point2D p2 = allPoints.get(j);
          double dx = (double) p1.x - p2.x;
          double dz = (double) p1.z - p2.z;
          double dist = Math.sqrt(dx * dx + dz * dz);
          if (dist < minD) minD = dist;
        }
        nearestDists[i] = minD;
        sumDist += minD;
      }
      Arrays.sort(nearestDists);
      nnMin = nearestDists[0];
      nnP50 = nearestDists[nearestDists.length / 2];
      nnMean = sumDist / allPoints.size();

      double area = (double) boundW * boundH;
      if (area > 0) {
        double density = (double) allPoints.size() / area;
        double expectedMean = 1.0 / (2.0 * Math.sqrt(density));
        if (expectedMean > 0) {
          clarkEvansR = nnMean / expectedMean;
        }
      }
    }

    // 5. Setup high-resolution Graphics2D canvas
    BufferedImage image = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

    // Background
    g.setColor(BG_DARK);
    g.fillRect(0, 0, canvasWidth, canvasHeight);

    // Render Terrain & Hazards into map viewport
    renderMapField(g, memoryShape, minX, minZ, boundW, boundH, mapX, mapY, mapW, mapH);

    // Render coordinate grid and bounds
    g.setColor(PANEL_BORDER);
    g.drawRect(mapX, mapY, mapW, mapH);

    // Draw the Archimedean spiral selection-order path, colored by progression
    // (cool = selected first, warm = selected last). Clip to the map viewport so
    // no segment can bleed into the telemetry sidebar.
    if (selectionPath.size() > 1) {
      java.awt.Shape prevClip = g.getClip();
      g.setClip(mapX, mapY, mapW + 1, mapH + 1);
      g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      for (int i = 0; i < selectionPath.size() - 1; i++) {
        Point2D p0 = selectionPath.get(i);
        Point2D p1 = selectionPath.get(i + 1);
        int sx0 = mapX + MatrixMapping.worldToPixelX(p0.x, minX, boundW, mapW);
        int sy0 = mapY + MatrixMapping.worldToPixelY(p0.z, minZ, boundH, mapH);
        int sx1 = mapX + MatrixMapping.worldToPixelX(p1.x, minX, boundW, mapW);
        int sy1 = mapY + MatrixMapping.worldToPixelY(p1.z, minZ, boundH, mapH);
        g.setColor(progressColor(selectionProgress.get(i)));
        g.drawLine(sx0, sy0, sx1, sy1);
      }
      g.setClip(prevClip);
    }

    // Draw candidate markers
    drawPoints(g, l3Points, COLOR_L3, minX, minZ, boundW, boundH, mapX, mapY, mapW, mapH, 3);
    drawPoints(g, l2Points, COLOR_L2, minX, minZ, boundW, boundH, mapX, mapY, mapW, mapH, 4);
    drawPoints(g, l1Points, COLOR_L1, minX, minZ, boundW, boundH, mapX, mapY, mapW, mapH, 5);

    // Render Headers, Sidebar Telemetry, and Legends
    renderUIOverlays(g, region, memoryShape, minX, maxX, minZ, maxZ, allPoints.size(),
        l1Points.size(), l2Points.size(), l3Points.size(), nnMean, nnP50, clarkEvansR,
        mapX, mapY, mapW, mapH, canvasWidth);

    g.dispose();

    // 6. Write image to file
    ImageIO.write(image, "png", imageFile);

    // 7. Write accompanying JSON diagnostic summary
    writeDiagnosticJson(jsonFile, region, memoryShape, allPoints, l1Points.size(),
        l2Points.size(), l3Points.size(), nnMean, clarkEvansR);

    long executionTimeMs = System.currentTimeMillis() - startTime;
    long badChunks = memoryShape.getEffectiveBadCount();

    return new ExportResult(imageFile, jsonFile, allPoints.size(), nnMean, clarkEvansR, badChunks, executionTimeMs);
  }

  private static void renderMapField(
      Graphics2D g,
      MemoryShape<?> memoryShape,
      int minX, int minZ, long boundW, long boundH,
      int mapX, int mapY, int mapW, int mapH
  ) {
    BufferedImage mapBuffer = MatrixMapping.renderMapBuffer(
        memoryShape, minX, minZ, boundW, boundH, mapW, mapH, COLOR_OUTSIDE);
    g.drawImage(mapBuffer, mapX, mapY, null);
  }

  private static void drawPoints(
      Graphics2D g,
      List<Point2D> points,
      Color color,
      int minX, int minZ, long boundW, long boundH,
      int mapX, int mapY, int mapW, int mapH,
      int radius
  ) {
    for (Point2D pt : points) {
      int sx = mapX + MatrixMapping.worldToPixelX(pt.x, minX, boundW, mapW);
      int sy = mapY + MatrixMapping.worldToPixelY(pt.z, minZ, boundH, mapH);

      // Dark border
      g.setColor(new Color(0, 0, 0, 200));
      g.fillOval(sx - radius - 1, sy - radius - 1, (radius + 1) * 2, (radius + 1) * 2);

      // Fill color
      g.setColor(color);
      g.fillOval(sx - radius, sy - radius, radius * 2, radius * 2);
    }
  }

  private static void renderUIOverlays(
      Graphics2D g,
      Region region,
      MemoryShape<?> memoryShape,
      int minX, int maxX, int minZ, int maxZ,
      int totalPoints, int l1Count, int l2Count, int l3Count,
      double nnMean, double nnP50, double clarkEvansR,
      int mapX, int mapY, int mapW, int mapH, int canvasWidth
  ) {
    // Top Title & Subtitle
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
    g.setColor(TEXT_PRIMARY);
    g.drawString("RTP Diagnostic Export: " + region.name, mapX, 48);

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
    g.setColor(TEXT_MUTED);
    String subtitle = String.format(Locale.ROOT,
        "World: %s | Range: %,d chunks | Bounds: [%d, %d] to [%d, %d]",
        region.getWorld().name(), memoryShape.getRange(), minX, minZ, maxX, maxZ);
    g.drawString(subtitle, mapX, 74);

    // Sidebar panel on right
    int sideX = mapX + mapW + 24;
    int sideY = mapY;
    int sideW = canvasWidth - sideX - 30;
    int sideH = mapH;

    g.setColor(PANEL_BG);
    g.fillRoundRect(sideX, sideY, sideW, sideH, 12, 12);
    g.setColor(PANEL_BORDER);
    g.drawRoundRect(sideX, sideY, sideW, sideH, 12, 12);

    int textX = sideX + 16;
    int currY = sideY + 28;

    // Section 1: Telemetry & Queues
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
    g.setColor(TEXT_PRIMARY);
    g.drawString("Queue Observability", textX, currY);
    currY += 24;

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
    drawLegendEntry(g, textX, currY, COLOR_L1, "L1 Hot (Warmed): " + l1Count);
    currY += 20;
    drawLegendEntry(g, textX, currY, COLOR_L2, "L2 Cold (Pre-verified): " + l2Count);
    currY += 20;
    drawLegendEntry(g, textX, currY, COLOR_L3, "L3 Backlog: " + l3Count);
    currY += 20;
    drawSpiralLegendEntry(g, textX, currY, "Spiral Selection Order (early -> late)");
    currY += 34;

    // Section 2: Spatial Dispersion & Statistics
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
    g.setColor(TEXT_PRIMARY);
    g.drawString("Spatial Dispersion", textX, currY);
    currY += 24;

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
    g.setColor(TEXT_MUTED);
    g.drawString(String.format(Locale.ROOT, "Active Candidates: %,d", totalPoints), textX, currY);
    currY += 20;
    g.drawString(String.format(Locale.ROOT, "NN Distance (Mean): %.1f blocks", nnMean), textX, currY);
    currY += 20;
    g.drawString(String.format(Locale.ROOT, "NN Distance (Median): %.1f blocks", nnP50), textX, currY);
    currY += 20;

    String csrStatus = (Math.abs(clarkEvansR - 1.0) < 0.15) ? "Uniform (Ideal CSR)" : "Clustered/Dispersed";
    g.drawString(String.format(Locale.ROOT, "Clark-Evans R: %.3f", clarkEvansR), textX, currY);
    currY += 16;
    g.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 11));
    g.setColor(new Color(0x7E, 0xE7, 0x87));
    g.drawString("[" + csrStatus + "]", textX, currY);
    currY += 34;

    // Section 3: Memory & Hazard Filter
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
    g.setColor(TEXT_PRIMARY);
    g.drawString("Safety & Memory", textX, currY);
    currY += 24;

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
    g.setColor(TEXT_MUTED);
    long badChunks = memoryShape.getEffectiveBadCount();
    double badPercent = (memoryShape.getRange() > 0) ? (100.0 * badChunks / memoryShape.getRange()) : 0.0;
    g.drawString(String.format(Locale.ROOT, "Hazard Chunks: %,d", badChunks), textX, currY);
    currY += 20;
    g.drawString(String.format(Locale.ROOT, "Hazard Density: %.2f%%", badPercent), textX, currY);
    currY += 20;
    drawLegendEntry(g, textX, currY, COLOR_HAZARD, "Red Tint: Discarded Hazard");
    currY += 34;

    // Bottom Status Bar
    int barY = mapY + mapH + 20;
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    g.setColor(TEXT_MUTED);
    g.drawString("Exported via /rtp visualization export all (100% off-tick, zero chunk I/O)", mapX, barY + 16);
  }

  /**
   * Maps a selection-progress fraction [0,1] to a cool-to-warm color so the
   * spiral order (first selected -> last selected) reads at a glance.
   */
  private static Color progressColor(float t) {
    float clamped = Math.max(0f, Math.min(1f, t));
    // Hue sweep from cyan (0.55) toward magenta/red (0.95).
    float hue = 0.55f + 0.40f * clamped;
    return new Color((Color.HSBtoRGB(hue, 0.75f, 1.0f) & 0xFFFFFF) | 0x8C000000, true);
  }

  /** Legend entry whose swatch is the spiral cool-to-warm progression gradient. */
  private static void drawSpiralLegendEntry(Graphics2D g, int x, int y, String label) {
    for (int i = 0; i < 10; i++) {
      g.setColor(progressColor(i / 9.0f));
      g.fillRect(x + i, y - 10, 1, 10);
    }
    g.setColor(PANEL_BORDER);
    g.drawRect(x, y - 10, 10, 10);
    g.setColor(TEXT_MUTED);
    g.drawString(label, x + 16, y);
  }

  private static void drawLegendEntry(Graphics2D g, int x, int y, Color color, String label) {
    g.setColor(color);
    g.fillRect(x, y - 10, 10, 10);
    g.setColor(PANEL_BORDER);
    g.drawRect(x, y - 10, 10, 10);
    g.setColor(TEXT_MUTED);
    g.drawString(label, x + 16, y);
  }

  private static void writeDiagnosticJson(
      File jsonFile,
      Region region,
      MemoryShape<?> shape,
      List<Point2D> points,
      int l1Count, int l2Count, int l3Count,
      double nnMean, double clarkEvansR
  ) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("region", region.name);
    root.put("world", region.getWorld().name());
    root.put("rangeChunks", shape.getRange());
    root.put("badLocationsCount", shape.getEffectiveBadCount());

    Map<String, Object> queueMap = new LinkedHashMap<>();
    queueMap.put("l1HotCount", l1Count);
    queueMap.put("l2ColdCount", l2Count);
    queueMap.put("l3BacklogCount", l3Count);
    queueMap.put("totalCandidates", points.size());
    root.put("queues", queueMap);

    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("nearestNeighborMean", nnMean);
    metrics.put("clarkEvansR", clarkEvansR);
    root.put("spatialMetrics", metrics);

    List<Map<String, Integer>> pointList = new ArrayList<>();
    for (Point2D pt : points) {
      Map<String, Integer> p = new LinkedHashMap<>();
      p.put("x", pt.x);
      p.put("z", pt.z);
      pointList.add(p);
    }
    root.put("candidatePoints", pointList);

    try (FileWriter writer = new FileWriter(jsonFile, StandardCharsets.UTF_8)) {
      com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
      gson.toJson(root, writer);
    } catch (IOException ex) {
      RTP.log(java.util.logging.Level.WARNING, "Failed to write diagnostic JSON: " + ex.getMessage(), ex);
    }
  }
}
