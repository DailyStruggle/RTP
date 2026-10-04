package io.github.dailystruggle.rtp.common.visualization;

import io.github.dailystruggle.mapsapi.image.ImageMapCanvas;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * Diagnostic framing utility that frames rendered {@link ImageMapCanvas} visualizations
 * with a standardized diagnostic header, subtitle, right-hand telemetry/legend sidebar,
 * and bottom status bar matching {@link ComprehensiveRegionImageExporter}.
 *
 * <p>Operates 100% off-tick with zero main-thread chunk I/O (Rule S-005).</p>
 */
public final class FramedVisualizationExporter {

  private static final Color BG_DARK = new Color(0x0C, 0x10, 0x17);
  private static final Color PANEL_BG = new Color(0x16, 0x1B, 0x22);
  private static final Color PANEL_BORDER = new Color(0x30, 0x36, 0x3D);
  private static final Color TEXT_PRIMARY = new Color(0xF0, 0xF6, 0xFC);
  private static final Color TEXT_MUTED = new Color(0x8B, 0x94, 0x9E);
  private static final Color TEXT_ACCENT = new Color(0x58, 0xA6, 0xFF);

  // Swatch colors
  private static final Color COLOR_L1 = new Color(0x2E, 0xCC, 0x71);       // Emerald
  private static final Color COLOR_L2 = new Color(0x34, 0x98, 0xDB);       // Cyan
  private static final Color COLOR_L3 = new Color(0x9B, 0x59, 0xB6);       // Purple
  private static final Color COLOR_ARRIVAL = new Color(0xF1, 0xC4, 0x0F);  // Gold
  private static final Color COLOR_HAZARD = new Color(0xE7, 0x4C, 0x3C);   // Crimson
  private static final Color COLOR_SAFE = new Color(0x2E, 0xCC, 0x71);     // Green
  private static final Color COLOR_OUTSIDE = new Color(0x14, 0x14, 0x14);  // Dark Void
  private static final Color COLOR_MSPT = new Color(0xE7, 0x4C, 0x3C);     // Red
  private static final Color COLOR_HEAP = new Color(0x2E, 0xCC, 0x71);     // Green

  private FramedVisualizationExporter() {
    throw new AssertionError("Non-instantiable utility class.");
  }

  /**
   * Wraps an inner visualization canvas with a standardized diagnostic frame containing
   * titles, subtitle metadata, a dedicated right-side legend panel, and a footer status bar.
   *
   * @param innerCanvas the rendered visualization canvas
   * @param kind the visualization kind
   * @param typeName display name of the visualization (e.g. "pipeline", "biomes", "bad-locations")
   * @param region target RTP region (may be null if unassociated)
   * @return a new framed BufferedImage
   */
  public static BufferedImage frame(
      ImageMapCanvas innerCanvas,
      ChartSpec.Kind kind,
      String typeName,
      Region region
  ) {
    if (innerCanvas == null) {
      throw new IllegalArgumentException("innerCanvas shall not be null");
    }

    BufferedImage mapImage = innerCanvas.getImage();
    int mapW = mapImage.getWidth();
    int mapH = mapImage.getHeight();

    final int mapX = 30;
    final int mapY = 110;
    final int sideGap = 24;
    final int sideW = 280;
    final int rightMargin = 30;
    final int bottomMargin = 60;

    final int canvasWidth = mapX + mapW + sideGap + sideW + rightMargin;
    final int canvasHeight = mapY + mapH + bottomMargin;

    BufferedImage framed = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = framed.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

    // Dark canvas backdrop
    g.setColor(BG_DARK);
    g.fillRect(0, 0, canvasWidth, canvasHeight);

    // Draw the visualization inside its viewport
    g.drawImage(mapImage, mapX, mapY, null);

    // Border around the viewport
    g.setColor(PANEL_BORDER);
    g.drawRect(mapX, mapY, mapW, mapH);

    // Top Header & Subtitle
    renderHeader(g, kind, typeName, region, mapX);

    // Right-Hand Sidebar (Legend & Telemetry)
    renderSidebar(g, kind, typeName, region, mapX, mapY, mapW, mapH, sideGap, sideW);

    // Bottom Status Bar
    renderFooter(g, typeName, mapX, mapY + mapH + 20);

    g.dispose();
    return framed;
  }

  private static void renderHeader(Graphics2D g, ChartSpec.Kind kind, String typeName, Region region, int mapX) {
    String regionName = (region != null) ? region.name : "Global";
    String title = "RTP Diagnostic Visualization: " + formatTitle(typeName) + " (" + regionName + ")";

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
    g.setColor(TEXT_PRIMARY);
    g.drawString(title, mapX, 48);

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
    g.setColor(TEXT_MUTED);

    String worldName = (region != null && region.getWorld() != null) ? region.getWorld().name() : "N/A";
    long range = (region != null && region.shape instanceof MemoryShape<?> ms) ? ms.getRange() : 0L;
    String subtitle = String.format(Locale.ROOT,
        "World: %s | Range: %,d chunks | Type: %s (%s)",
        worldName, range, typeName, kind != null ? kind.name() : "CUSTOM");
    g.drawString(subtitle, mapX, 74);
  }

  private static String formatTitle(String typeName) {
    if (typeName == null || typeName.isEmpty()) return "Chart";
    return switch (typeName.toLowerCase(Locale.ROOT)) {
      case "pipeline" -> "Pipeline & Queue Telemetry";
      case "biomes" -> "Regional Biome Distribution";
      case "bad-locations" -> "Safety & Hazard Classification";
      case "sparkline" -> "Server & Heap Telemetry";
      case "walk", "walk-path" -> "Archimedean Selection Trajectory";
      case "heatmap", "selection-heatmap" -> "Selection Density & Location Heatmap";
      case "comprehensive" -> "Comprehensive Diagnostic";
      default -> typeName.substring(0, 1).toUpperCase(Locale.ROOT) + typeName.substring(1);
    };
  }

  private static void renderSidebar(
      Graphics2D g,
      ChartSpec.Kind kind,
      String typeName,
      Region region,
      int mapX, int mapY, int mapW, int mapH,
      int sideGap, int sideW
  ) {
    int sideX = mapX + mapW + sideGap;
    int sideY = mapY;
    int sideH = mapH;

    g.setColor(PANEL_BG);
    g.fillRoundRect(sideX, sideY, sideW, sideH, 12, 12);
    g.setColor(PANEL_BORDER);
    g.drawRoundRect(sideX, sideY, sideW, sideH, 12, 12);

    int textX = sideX + 16;
    int currY = sideY + 28;

    // Legend Title
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
    g.setColor(TEXT_PRIMARY);
    g.drawString("Visualization Legend", textX, currY);
    currY += 24;

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));

    if (kind == ChartSpec.Kind.REGION_COMPOSITE) {
      currY = renderPipelineLegend(g, region, textX, currY);
    } else if (kind == ChartSpec.Kind.REGION_BIOMES) {
      currY = renderBiomesLegend(g, region, textX, currY);
    } else if (kind == ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE) {
      currY = renderBadLocationsLegend(g, region, textX, currY);
    } else if (kind == ChartSpec.Kind.METRIC_SPARKLINE) {
      currY = renderSparklineLegend(g, textX, currY);
    } else if (kind == ChartSpec.Kind.REGION_WALK_PATH) {
      currY = renderWalkPathLegend(g, region, textX, currY);
    } else if (kind == ChartSpec.Kind.SELECTION_HEATMAP || kind == ChartSpec.Kind.BAD_POINTS_HEATMAP) {
      currY = renderSelectionHeatmapLegend(g, region, textX, currY);
    } else {
      currY = renderGenericLegend(g, textX, currY);
    }

    // Region Information section
    if (region != null && currY < sideY + sideH - 120) {
      currY = Math.max(currY + 16, sideY + sideH - 150);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
      g.setColor(TEXT_PRIMARY);
      g.drawString("Region Metadata", textX, currY);
      currY += 20;

      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
      g.setColor(TEXT_MUTED);
      g.drawString("Name: " + region.name, textX, currY);
      currY += 18;
      if (region.shape instanceof MemoryShape<?> ms) {
        long badChunks = ms.getEffectiveBadCount();
        double badPct = ms.getRange() > 0 ? (100.0 * badChunks / ms.getRange()) : 0.0;
        g.drawString(String.format(Locale.ROOT, "Range: %,d chunks", ms.getRange()), textX, currY);
        currY += 18;
        g.drawString(String.format(Locale.ROOT, "Hazard density: %.2f%%", badPct), textX, currY);
      }
    }
  }

  private static int renderPipelineLegend(Graphics2D g, Region region, int x, int y) {
    int l1Count = 0;
    int l2Count = 0;
    int l3Count = 0;
    if (region != null && region.queueManager != null) {
      if (region.queueManager.keptLocations != null) l1Count = region.queueManager.keptLocations.size();
      if (region.queueManager.unkeptLocations != null) l2Count = region.queueManager.unkeptLocations.size();
    }

    drawLegendEntry(g, x, y, COLOR_L1, "L1 Hot (Warmed): " + l1Count);
    y += 20;
    drawLegendEntry(g, x, y, COLOR_L2, "L2 Cold (Pre-verified): " + l2Count);
    y += 20;
    drawLegendEntry(g, x, y, COLOR_L3, "L3 Backlog Candidates");
    y += 20;
    drawLegendEntry(g, x, y, COLOR_ARRIVAL, "Gold Dot: Recent Arrivals");
    y += 20;
    drawLegendEntry(g, x, y, COLOR_HAZARD, "Red Tint: Discarded Hazard");
    y += 26;

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
    g.setColor(TEXT_PRIMARY);
    g.drawString("Queue Health Gauges", x, y);
    y += 20;
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    g.setColor(TEXT_MUTED);
    g.drawString("Bottom bars indicate capacity", x, y);
    y += 18;
    g.drawString("L1 (Top) -> L2 (Mid) -> L3 (Bottom)", x, y);
    y += 22;
    return y;
  }

  private static int renderBiomesLegend(Graphics2D g, Region region, int x, int y) {
    g.setColor(TEXT_MUTED);
    g.drawString("Pre-filtered Biome Palette", x, y);
    y += 20;
    drawLegendEntry(g, x, y, new Color(0x2E, 0xCC, 0x71), "Temperate / Plains / Forest");
    y += 20;
    drawLegendEntry(g, x, y, new Color(0x34, 0x98, 0xDB), "Ocean / Aquatic / River");
    y += 20;
    drawLegendEntry(g, x, y, new Color(0xF3, 0x9C, 0x12), "Desert / Warm / Savanna");
    y += 20;
    drawLegendEntry(g, x, y, new Color(0x95, 0xA5, 0xA6), "Mountain / Snowy / Tundra");
    y += 20;
    drawLegendEntry(g, x, y, COLOR_OUTSIDE, "Dark Void: Outside Shape Domain");
    y += 26;

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
    g.setColor(TEXT_PRIMARY);
    g.drawString("Anvil & Linear Sampling", x, y);
    y += 20;
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    g.setColor(TEXT_MUTED);
    g.drawString("100% off-tick region sampling", x, y);
    y += 18;
    g.drawString("Desaturated to highlight topology", x, y);
    y += 22;
    return y;
  }

  private static int renderBadLocationsLegend(Graphics2D g, Region region, int x, int y) {
    long badChunks = 0L;
    long totalRange = 0L;
    if (region != null && region.shape instanceof MemoryShape<?> ms) {
      badChunks = ms.getEffectiveBadCount();
      totalRange = ms.getRange();
    }
    double badPct = totalRange > 0 ? (100.0 * badChunks / totalRange) : 0.0;

    drawLegendEntry(g, x, y, COLOR_SAFE, "Green: Safe Candidate Chunk");
    y += 20;
    drawLegendEntry(g, x, y, COLOR_HAZARD, "Red: Discarded Hazard Chunk");
    y += 20;
    drawLegendEntry(g, x, y, COLOR_OUTSIDE, "Black: Outside Spatial Domain");
    y += 26;

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
    g.setColor(TEXT_PRIMARY);
    g.drawString("Hazard Metrics", x, y);
    y += 20;
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    g.setColor(TEXT_MUTED);
    g.drawString(String.format(Locale.ROOT, "Hazard Chunks: %,d", badChunks), x, y);
    y += 18;
    g.drawString(String.format(Locale.ROOT, "Hazard Density: %.2f%%", badPct), x, y);
    y += 18;
    g.drawString("Persistent bitmap screening", x, y);
    y += 22;
    return y;
  }

  private static int renderSparklineLegend(Graphics2D g, int x, int y) {
    drawLegendEntry(g, x, y, COLOR_MSPT, "Top: MSPT (Tick Duration, ms)");
    y += 20;
    drawLegendEntry(g, x, y, COLOR_HEAP, "Bottom: Heap Memory (MB)");
    y += 20;
    drawLegendEntry(g, x, y, Color.WHITE, "White Lines: Axis & Baselines");
    y += 26;

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
    g.setColor(TEXT_PRIMARY);
    g.drawString("Performance Observability", x, y);
    y += 20;
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    g.setColor(TEXT_MUTED);
    g.drawString("Horizontal axis: Rolling history", x, y);
    y += 18;
    g.drawString("Independent auto-scaled y-axes", x, y);
    y += 18;
    g.drawString("Gaps denote missing sample points", x, y);
    y += 22;
    return y;
  }

  private static int renderWalkPathLegend(Graphics2D g, Region region, int x, int y) {
    drawSpiralLegendEntry(g, x, y, "Spiral Order (Early -> Late)");
    y += 20;
    drawLegendEntry(g, x, y, COLOR_SAFE, "Cyan -> Warm: Safe Steps");
    y += 20;
    drawLegendEntry(g, x, y, COLOR_HAZARD, "Crimson: Hazard / Discarded");
    y += 20;
    drawLegendEntry(g, x, y, new Color(0x48, 0x4F, 0x58), "Dim Gray: Out of Bounds Stride");
    y += 26;

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
    g.setColor(TEXT_PRIMARY);
    g.drawString("Spiral Selection Order", x, y);
    y += 20;
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    g.setColor(TEXT_MUTED);
    g.drawString("Archimedean spiral 1D mapping (ADR-001)", x, y);
    y += 18;
    g.drawString("Uniform spatial candidate search", x, y);
    y += 18;
    g.drawString("1:1 resolution walk trajectory", x, y);
    y += 22;
    return y;
  }

  private static int renderSelectionHeatmapLegend(Graphics2D g, Region region, int x, int y) {
    drawHeatGradientBar(g, x, y, "Heat: Low -> Med -> High Peak");
    y += 24;

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
    g.setColor(TEXT_PRIMARY);
    g.drawString("Selection Locations", x, y);
    y += 20;

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
    drawLegendEntry(g, x, y, new Color(0x00, 0xE6, 0x76), "Emerald: L1 Warmed Queue");
    y += 20;
    drawLegendEntry(g, x, y, new Color(0x29, 0xB6, 0xF6), "Sky Blue: L2 Pre-verified");
    y += 20;
    drawLegendEntry(g, x, y, new Color(0xFF, 0xD6, 0x00), "Gold: Verified Landings");
    y += 20;
    drawLegendEntry(g, x, y, new Color(0x00, 0xE5, 0xFF), "Cyan: Candidate Selections");
    y += 20;
    drawLegendEntry(g, x, y, new Color(0xFF, 0x17, 0x44), "Crimson: Hazard Discard");
    y += 26;

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
    g.setColor(TEXT_PRIMARY);
    g.drawString("Density Diagnostics", x, y);
    y += 20;
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    g.setColor(TEXT_MUTED);
    g.drawString("Kernel density estimation (KDE)", x, y);
    y += 18;
    g.drawString("Dark outline shows domain contour", x, y);
    y += 18;
    g.drawString("Identifies spatial collision hotspots", x, y);
    y += 22;
    return y;
  }

  private static void drawHeatGradientBar(Graphics2D g, int x, int y, String label) {
    for (int i = 0; i < 16; i++) {
      double t = i / 15.0;
      int rgb = io.github.dailystruggle.mapsapi.render.SelectionHeatmapRenderer.sampleHeatRamp(t);
      g.setColor(new Color(rgb));
      g.fillRect(x + i, y - 10, 1, 10);
    }
    g.setColor(PANEL_BORDER);
    g.drawRect(x, y - 10, 16, 10);
    g.setColor(TEXT_MUTED);
    g.drawString(label, x + 22, y);
  }

  private static int renderGenericLegend(Graphics2D g, int x, int y) {
    g.setColor(TEXT_MUTED);
    g.drawString("Standard visual diagnostic", x, y);
    y += 20;
    drawLegendEntry(g, x, y, TEXT_ACCENT, "Active Model Elements");
    y += 20;
    drawLegendEntry(g, x, y, COLOR_OUTSIDE, "Domain Background");
    y += 22;
    return y;
  }

  private static void renderFooter(Graphics2D g, String typeName, int x, int y) {
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    g.setColor(TEXT_MUTED);
    String footer = String.format(Locale.ROOT,
        "Exported via /rtp visualization export %s (100%% off-tick, zero chunk I/O)", typeName);
    g.drawString(footer, x, y);
  }

  private static void drawLegendEntry(Graphics2D g, int x, int y, Color color, String label) {
    g.setColor(color);
    g.fillRect(x, y - 10, 10, 10);
    g.setColor(PANEL_BORDER);
    g.drawRect(x, y - 10, 10, 10);
    g.setColor(TEXT_MUTED);
    g.drawString(label, x + 16, y);
  }

  private static void drawSpiralLegendEntry(Graphics2D g, int x, int y, String label) {
    for (int i = 0; i < 10; i++) {
      float t = i / 9.0f;
      float hue = 0.58f - 0.58f * t;
      g.setColor(new Color(Color.HSBtoRGB(hue, 0.85f, 0.95f)));
      g.fillRect(x + i, y - 10, 1, 10);
    }
    g.setColor(PANEL_BORDER);
    g.drawRect(x, y - 10, 10, 10);
    g.setColor(TEXT_MUTED);
    g.drawString(label, x + 16, y);
  }
}
