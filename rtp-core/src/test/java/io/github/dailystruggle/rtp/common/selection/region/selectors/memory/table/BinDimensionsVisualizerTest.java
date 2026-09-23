package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Visualizes and unit-tests bin behavior across different dimensional scales:
 * 1. Physical MCA / Linear Region Files: 32x32 Chunks (1,024 chunks / 512x512 blocks)
 * 2. Boundary / Edge Truncation: Partial Bins at arbitrary world borders / radius cutoffs
 * 3. Spatial Resolution & Macro-Cells: Variable cell dimensions (P = 8, 16, 32, 64)
 */
public class BinDimensionsVisualizerTest {

  @Test
  @DisplayName("Visualize bin dimension behaviors: 32x32 physical vs boundary-truncated vs multi-scale macro-cells")
  public void testDrawBinDimensionsBehavior() throws Exception {
    System.out.println("\n[DEBUG_LOG] === BIN DIMENSIONS BEHAVIOR VISUALIZER ===");

    // 1. Verify and compute partial bin truncation on AnvilRegionBinHazardTable
    // Suppose total range = 2,500 chunks (not a multiple of 1,024)
    long nonMultipleRange = 2500L;
    AnvilRegionBinHazardTable table = new AnvilRegionBinHazardTable(nonMultipleRange);
    assertEquals(3, table.binCount()); // 1024 + 1024 + 452 = 2500
    assertEquals(1024, table.binCapacity(0));
    assertEquals(1024, table.binCapacity(1));
    assertEquals(452, table.binCapacity(2)); // Truncated partial bin!

    System.out.printf("[DEBUG_LOG] Table for range %,d chunks: binCount=%d, bin[0]=%d, bin[1]=%d, bin[2]=%d (partial)%n",
        nonMultipleRange, table.binCount(), table.binCapacity(0), table.binCapacity(1), table.binCapacity(2));

    // 2. Render visual chart demonstrating:
    // Panel 1: Standard 32x32 Anvil/Linear physical bin (1024 chunks) with dyadic bisection probe lattice
    // Panel 2: Boundary Truncation: Worldborder slicing through a 32x32 bin creating a partial bin (e.g. 452 chunks)
    // Panel 3: Multi-Scale Macro-Cells (P=8, P=16, P=32, P=64) within the spatial manifold
    // Panel 4: World Grid showing inner full 32x32 bins vs outer clipped/truncated boundary bins
    renderBinBehaviorChart();

    assertTrue(table.binCount() > 0);
  }

  private void renderBinBehaviorChart() throws Exception {
    int panelWidth = 440;
    int panelHeight = 440;
    int headerHeight = 130;
    int footerHeight = 120;
    int numCols = 4;
    int totalWidth = numCols * panelWidth + 50;
    int totalHeight = headerHeight + panelHeight + footerHeight;

    BufferedImage img = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

    // Dark theme background
    g.setColor(new Color(0x0C1017));
    g.fillRect(0, 0, totalWidth, totalHeight);

    // Title banner
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
    g.setColor(new Color(0xE6EDF3));
    g.drawString("Bin Dimensions & Boundary Behavior in RTP", 30, 42);

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
    g.setColor(new Color(0x8B949E));
    g.drawString("Visualizing why a bin is NOT always a uniform 32x32 chunk square across physical, boundary, and mathematical contexts", 30, 68);
    g.drawString("Columns: [1] Physical 32x32 Anvil/Linear  |  [2] Boundary Truncated Edge Bins  |  [3] Multi-Scale Macro-Cells  |  [4] World Grid Layout", 30, 90);

    // Render Panel 1: Standard Physical 32x32 Bin
    renderPanel1Physical32x32(g, 20, headerHeight, panelWidth, panelHeight);

    // Render Panel 2: Boundary Truncated Partial Bin
    renderPanel2TruncatedBin(g, 20 + panelWidth + 10, headerHeight, panelWidth, panelHeight);

    // Render Panel 3: Multi-Scale Macro-Cells
    renderPanel3MacroCells(g, 20 + (panelWidth + 10) * 2, headerHeight, panelWidth, panelHeight);

    // Render Panel 4: World Grid Layout (Full vs Partial Bins)
    renderPanel4WorldGrid(g, 20 + (panelWidth + 10) * 3, headerHeight, panelWidth, panelHeight);

    // Footer
    int footY = headerHeight + panelHeight + 15;
    g.setColor(new Color(0x161B22));
    g.fillRoundRect(20, footY, totalWidth - 40, footerHeight - 25, 8, 8);
    g.setColor(new Color(0x30363D));
    g.drawRoundRect(20, footY, totalWidth - 40, footerHeight - 25, 8, 8);

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
    g.setColor(new Color(0x58A6FF));
    g.drawString("Architectural Findings:", 35, footY + 24);

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    g.setColor(new Color(0x8B949E));
    g.drawString("- Physical Storage (.mca / .linear): Strictly 32x32 chunks (1,024 chunks / 512x512 blocks) matching Minecraft region files.", 35, footY + 44);
    g.drawString("- Boundary Hazard Tables: Clipped/truncated partial bins whenever totalRange % 1024 != 0 (e.g. 452 chunks remaining), preventing out-of-bounds reads.", 35, footY + 62);
    g.drawString("- Spatial Memory / Downsampling: Macro-cells scale adaptively (P=8, 16, 32, 64) via spatialResolution, decoupling spatial indexing from physical file boundaries.", 35, footY + 80);

    g.dispose();

    // Save image to build reports, docs/assets/img, and root
    File outReportsDir = new File("build/reports/bin_dimensions");
    if (!outReportsDir.exists()) outReportsDir.mkdirs();

    File chartReports = new File(outReportsDir, "bin_dimensions_behavior_chart.png");
    File chartRoot = new File("bin_dimensions_behavior_chart.png");
    ImageIO.write(img, "png", chartReports);
    ImageIO.write(img, "png", chartRoot);

    File[] candidateDocsDirs = new File[] {
        new File("docs/assets/img"),
        new File("../docs/assets/img")
    };
    for (File dir : candidateDocsDirs) {
      if (dir.exists()) {
        ImageIO.write(img, "png", new File(dir, "bin_dimensions_behavior_chart.png"));
      }
    }

    System.out.println("[DEBUG_LOG] Successfully rendered bin dimensions chart to: " + chartRoot.getAbsolutePath());
  }

  private void renderPanel1Physical32x32(Graphics2D g, int x, int y, int w, int h) {
    drawPanelBox(g, x, y, w, h, "1. Physical Region Bin (32x32)");

    int innerPad = 40;
    int gridX = x + innerPad;
    int gridY = y + innerPad;
    int gridSize = w - innerPad * 2;
    float cellSize = (float) gridSize / 32;

    // Draw 32x32 background chunks
    for (int rz = 0; rz < 32; rz++) {
      for (int rx = 0; rx < 32; rx++) {
        g.setColor(((rx + rz) % 2 == 0) ? new Color(0x1B222D) : new Color(0x161B22));
        g.fillRect((int) (gridX + rx * cellSize), (int) (gridY + rz * cellSize),
            (int) Math.ceil(cellSize), (int) Math.ceil(cellSize));
      }
    }

    // Grid border
    g.setColor(new Color(0x388BFD));
    g.setStroke(new BasicStroke(2f));
    g.drawRect(gridX, gridY, gridSize, gridSize);

    // Draw dyadic probe sample locations (e.g. 16 trials with jitter)
    g.setColor(new Color(0x3FB950));
    int[] probeSamples = {0, 512, 256, 768, 128, 640, 384, 896, 64, 576, 320, 832, 192, 704, 448, 960};
    for (int i = 0; i < probeSamples.length; i++) {
      int offset = (probeSamples[i] + 37) % 1024; // with phase jitter
      int cx = offset & 31;
      int cz = (offset >>> 5) & 31;
      int px = (int) (gridX + cx * cellSize + cellSize / 2);
      int py = (int) (gridY + cz * cellSize + cellSize / 2);

      g.setColor(new Color(0x2EA043));
      g.fillOval(px - 4, py - 4, 8, 8);
      g.setColor(Color.WHITE);
      g.drawOval(px - 4, py - 4, 8, 8);
    }

    // Label
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
    g.setColor(new Color(0xE6EDF3));
    g.drawString("Dimension: 32 x 32 = 1,024 chunks", gridX, gridY + gridSize + 20);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
    g.setColor(new Color(0x8B949E));
    g.drawString("Capacity: 100% full (Standard Anvil / Linear MCA)", gridX, gridY + gridSize + 36);
  }

  private void renderPanel2TruncatedBin(Graphics2D g, int x, int y, int w, int h) {
    drawPanelBox(g, x, y, w, h, "2. Boundary Truncated Bin");

    int innerPad = 40;
    int gridX = x + innerPad;
    int gridY = y + innerPad;
    int gridSize = w - innerPad * 2;
    float cellSize = (float) gridSize / 32;

    int activeChunks = 452; // partial bin capacity

    for (int rz = 0; rz < 32; rz++) {
      for (int rx = 0; rx < 32; rx++) {
        int idx = rz * 32 + rx;
        if (idx < activeChunks) {
          g.setColor(((rx + rz) % 2 == 0) ? new Color(0x1E3A5F) : new Color(0x182E4B));
        } else {
          // Clipped / out-of-bounds region
          g.setColor(new Color(0x21171A));
        }
        g.fillRect((int) (gridX + rx * cellSize), (int) (gridY + rz * cellSize),
            (int) Math.ceil(cellSize), (int) Math.ceil(cellSize));
      }
    }

    // Border around physical boundary
    g.setColor(new Color(0x484F58));
    g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{4, 4}, 0));
    g.drawRect(gridX, gridY, gridSize, gridSize);

    // Truncation line
    int splitY = (int) (gridY + (activeChunks / 32f) * cellSize);
    g.setColor(new Color(0xF85149));
    g.setStroke(new BasicStroke(2f));
    g.drawLine(gridX, splitY, gridX + gridSize, splitY);

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
    g.setColor(new Color(0xF85149));
    g.drawString("<- World Border / Radius Cutoff", gridX + 10, splitY - 8);

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
    g.setColor(new Color(0xE6EDF3));
    g.drawString("Dimension: Partial (452 / 1,024 chunks)", gridX, gridY + gridSize + 20);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
    g.setColor(new Color(0x8B949E));
    g.drawString("AnvilRegionBinHazardTable capacity clamped", gridX, gridY + gridSize + 36);
  }

  private void renderPanel3MacroCells(Graphics2D g, int x, int y, int w, int h) {
    drawPanelBox(g, x, y, w, h, "3. Multi-Scale Macro-Cells");

    int innerPad = 40;
    int gridX = x + innerPad;
    int gridY = y + innerPad;
    int gridSize = w - innerPad * 2;

    // Draw 4 quadrants representing different macro-cell scales:
    // Top-Left: P = 8 (8x8 chunks)
    // Top-Right: P = 16 (16x16 chunks)
    // Bottom-Left: P = 32 (32x32 chunks)
    // Bottom-Right: P = 64 (64x64 chunks)

    int half = gridSize / 2;

    // Top-Left: P=8
    drawMacroQuadrant(g, gridX, gridY, half, half, 8, "P = 8 (8x8 chunks)", new Color(0x238636));

    // Top-Right: P=16
    drawMacroQuadrant(g, gridX + half, gridY, half, half, 16, "P = 16 (16x16 chunks)", new Color(0x1F6FEB));

    // Bottom-Left: P=32
    drawMacroQuadrant(g, gridX, gridY + half, half, half, 32, "P = 32 (Standard)", new Color(0xA371F7));

    // Bottom-Right: P=64
    drawMacroQuadrant(g, gridX + half, gridY + half, half, half, 64, "P = 64 (Macro-Tile)", new Color(0xD29922));

    // Center divider
    g.setColor(new Color(0x30363D));
    g.setStroke(new BasicStroke(2f));
    g.drawLine(gridX, gridY + half, gridX + gridSize, gridY + half);
    g.drawLine(gridX + half, gridY, gridX + half, gridY + gridSize);

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
    g.setColor(new Color(0xE6EDF3));
    g.drawString("Dimension: Variable P in {8, 16, 32, 64}", gridX, gridY + gridSize + 20);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
    g.setColor(new Color(0x8B949E));
    g.drawString("Parameterized via spatialResolution / Stride", gridX, gridY + gridSize + 36);
  }

  private void drawMacroQuadrant(Graphics2D g, int qx, int qy, int qw, int qh, int p, String label, Color c) {
    g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 35));
    g.fillRect(qx + 2, qy + 2, qw - 4, qh - 4);
    g.setColor(c);
    g.setStroke(new BasicStroke(1.5f));
    g.drawRect(qx + 2, qy + 2, qw - 4, qh - 4);

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
    g.drawString(label, qx + 8, qy + 18);
  }

  private void renderPanel4WorldGrid(Graphics2D g, int x, int y, int w, int h) {
    drawPanelBox(g, x, y, w, h, "4. World Grid (Full vs Clipped)");

    int innerPad = 40;
    int gridX = x + innerPad;
    int gridY = y + innerPad;
    int gridSize = w - innerPad * 2;

    int binsAcross = 6;
    float binStep = (float) gridSize / binsAcross;

    // Draw bins: center 4x4 are full 32x32, perimeter bins are clipped by circular world radius
    for (int bz = 0; bz < binsAcross; bz++) {
      for (int bx = 0; bx < binsAcross; bx++) {
        float cx = gridX + bx * binStep;
        float cz = gridY + bz * binStep;

        float distFromCenter = (float) Math.hypot((bx - 2.5), (bz - 2.5));
        if (distFromCenter <= 1.8f) {
          // Full 32x32 bin
          g.setColor(new Color(0x1B472C));
          g.fillRect((int) cx + 2, (int) cz + 2, (int) binStep - 4, (int) binStep - 4);
          g.setColor(new Color(0x2EA043));
        } else if (distFromCenter <= 2.8f) {
          // Partial / boundary-truncated bin
          g.setColor(new Color(0x5A3E1B));
          g.fillRect((int) cx + 2, (int) cz + 2, (int) binStep - 4, (int) binStep - 4);
          g.setColor(new Color(0xD29922));
        } else {
          // Out of bounds bin
          g.setColor(new Color(0x1C1F26));
          g.fillRect((int) cx + 2, (int) cz + 2, (int) binStep - 4, (int) binStep - 4);
          g.setColor(new Color(0x30363D));
        }
        g.drawRect((int) cx + 2, (int) cz + 2, (int) binStep - 4, (int) binStep - 4);
      }
    }

    // World border radius circle
    g.setColor(new Color(0x58A6FF));
    g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{6, 6}, 0));
    g.drawOval(gridX + 15, gridY + 15, gridSize - 30, gridSize - 30);

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
    g.setColor(new Color(0xE6EDF3));
    g.drawString("Green: Full 32x32 | Orange: Partial", gridX, gridY + gridSize + 20);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
    g.setColor(new Color(0x8B949E));
    g.drawString("Blue Circle: Effective world boundary", gridX, gridY + gridSize + 36);
  }

  private void drawPanelBox(Graphics2D g, int x, int y, int w, int h, String title) {
    g.setColor(new Color(0x161B22));
    g.fillRoundRect(x, y, w, h, 8, 8);
    g.setColor(new Color(0x30363D));
    g.setStroke(new BasicStroke(1f));
    g.drawRoundRect(x, y, w, h, 8, 8);

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
    g.setColor(new Color(0x58A6FF));
    g.drawString(title, x + 15, y + 24);
  }
}
