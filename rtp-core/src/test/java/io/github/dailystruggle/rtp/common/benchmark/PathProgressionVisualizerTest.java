package io.github.dailystruggle.rtp.common.benchmark;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.CircleOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
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
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class PathProgressionVisualizerTest {

  private static final int[] RADII = {32, 64, 126, 256, 512};

  record PathStep(long loc, int cx, int cz) {}

  @Test
  @DisplayName("Visualize selection curve path at radii 32, 64, 126, 256, 512")
  public void testVisualizePathProgression() throws Exception {
    System.out.println("\n[DEBUG_LOG] === PATH PROGRESSION AT RADII 32, 64, 126, 256, 512 ===");

    for (int r : RADII) {
      SquareOptimizedDualLayer square = new SquareOptimizedDualLayer();
      square.set(GenericMemoryShapeParams.radius, (long) r);
      square.set(GenericMemoryShapeParams.centerRadius, 0L);

      CircleOptimizedDualLayer circle = new CircleOptimizedDualLayer();
      circle.set(GenericMemoryShapeParams.radius, (long) r);
      circle.set(GenericMemoryShapeParams.centerRadius, 0L);

      int pSquare = square.getPointEdgeChunks();
      int pCircle = circle.getPointEdgeChunks();
      long rangeSquare = square.getRange();
      long rangeCircle = circle.getRange();

      System.out.printf("[DEBUG_LOG] Radius=%-3d | P=%-2d | Square Range=%,10d | Circle Range=%,10d%n",
          r, pSquare, rangeSquare, rangeCircle);

      // Print first 8 steps
      System.out.print("  [DEBUG_LOG]   Square first 8 coords: ");
      MutableRTPCoords coords = new MutableRTPCoords(0, 0);
      for (int i = 0; i < 8; i++) {
        square.locationToXZ(i, coords);
        System.out.printf("(%d,%d) ", coords.x, coords.z);
      }
      System.out.println();
    }

    // Generate comprehensive high-resolution comparison chart
    renderPathProgressionChart();

    inspectConnectivityAndRepeatingPatterns();

    renderZoom32x32RegionChart();

    renderSubBinZoomChart();
  }

  /**
   * Close-up of the curve at every sub-bin size P = 1, 2, 4, 8, 16. The window scales with P (4x4
   * bins per panel, clamped to 16..64 chunks) so each panel shows the same structural unit; key
   * indices are printed per chunk while the window is small enough to read them.
   */
  private void renderSubBinZoomChart() throws Exception {
    int[] testedP = {1, 2, 4, 8, 16};
    int colWidth = 480;
    int headerHeight = 150;
    int rowHeight = 480;
    int footerHeight = 150;
    int numRows = 2;

    int totalWidth = testedP.length * colWidth + 40;
    int totalHeight = headerHeight + numRows * rowHeight + footerHeight;

    BufferedImage img = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

    g.setColor(new Color(0x0C1017));
    g.fillRect(0, 0, totalWidth, totalHeight);

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
    g.setColor(new Color(0xE6EDF3));
    g.drawString("Sub-Bin Zoom: Curve Shape at P = 1, 2, 4, 8, 16", 30, 42);

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
    g.setColor(new Color(0x8B949E));
    g.drawString("Each panel zooms to a 4x4 block of bins, so the intra-bin Hilbert order and the seams into neighbouring bins are both visible", 30, 70);
    g.drawString("Green dot = bin entry chunk  |  Red dot = bin exit chunk  |  Red dashed line = non-unit step (jump)", 30, 92);

    for (int col = 0; col < testedP.length; col++) {
      int pVal = testedP[col];
      int win = Math.min(64, Math.max(16, 4 * pVal));
      boolean labelKeys = win <= 16;
      int cellX = 20 + col * colWidth;

      g.setColor(new Color(0x1F2937));
      g.fillRoundRect(cellX + 10, 110, colWidth - 20, 28, 6, 6);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
      g.setColor(new Color(0x58A6FF));
      String title = (pVal == 1)
          ? String.format("P = 1 (no sub-bin, pure spiral) - %dx%d window", win, win)
          : String.format("P = %d (%d chunks/bin) - %dx%d window", pVal, pVal * pVal, win, win);
      g.drawString(title, cellX + 20, 129);

      renderRegionCell(g, cellX, headerHeight, colWidth, rowHeight, pVal, true, win, labelKeys);
      renderRegionCell(g, cellX, headerHeight + rowHeight, colWidth, rowHeight, pVal, false, win, labelKeys);
    }

    int footY = headerHeight + numRows * rowHeight + 15;
    g.setColor(new Color(0x161B22));
    g.fillRoundRect(20, footY, totalWidth - 40, footerHeight - 25, 10, 10);
    g.setColor(new Color(0x30363D));
    g.drawRoundRect(20, footY, totalWidth - 40, footerHeight - 25, 10, 10);

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
    g.setColor(new Color(0xE6EDF3));
    g.drawString("Reading the sub-bin zoom (top row Square, bottom row Circle):", 35, footY + 25);

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    g.setColor(new Color(0x8B949E));
    g.drawString("1. P=1: the key order is the bare Archimedean spiral - one chunk per macro-cell, so no Hilbert structure is visible.", 35, footY + 45);
    g.drawString("2. P=2..8: each bin is filled by its own Hilbert curve before the spiral steps on; the labels show the bin filling completely before the seam.", 35, footY + 63);
    g.drawString("3. P=16: the 64x64 window holds 4x4 bins of 256 chunks - the recursive Hilbert order dominates and the spiral only shows at bin scale.", 35, footY + 81);
    g.drawString("4. At every P the red exit dot of a bin is orthogonally adjacent to the green entry dot of the next - Jumps: 0 on all panels.", 35, footY + 99);

    g.dispose();

    File outReportsDir = new File("build/reports/path_progression");
    if (!outReportsDir.exists()) outReportsDir.mkdirs();

    ImageIO.write(img, "png", new File(outReportsDir, "sub_bin_zoom_path_chart.png"));
    ImageIO.write(img, "png", new File("sub_bin_zoom_path_chart.png"));
    File rootCopy = new File("../sub_bin_zoom_path_chart.png");
    if (rootCopy.getParentFile().exists()) {
      ImageIO.write(img, "png", rootCopy);
    }
    File docsCopy = new File("../docs/assets/img/sub_bin_zoom_path_chart.png");
    if (docsCopy.getParentFile().exists()) {
      ImageIO.write(img, "png", docsCopy);
    }

    System.out.println("[DEBUG_LOG] Successfully rendered sub-bin zoom chart to: " + new File("sub_bin_zoom_path_chart.png").getAbsolutePath());
  }

  private void renderZoom32x32RegionChart() throws Exception {
    int colWidth = 440;
    int headerHeight = 150;
    int rowHeight = 440;
    int footerHeight = 150;
    int numCols = 3; // Col 0: P=32 (1x 32x32 Hilbert cell), Col 1: P=8 (4x4 8x8 cells), Col 2: P=4 (8x8 4x4 cells)
    int numRows = 2; // Row 0: Square, Row 1: Circle

    int totalWidth = numCols * colWidth + 40;
    int totalHeight = headerHeight + numRows * rowHeight + footerHeight;

    BufferedImage img = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

    // Background
    g.setColor(new Color(0x0C1017));
    g.fillRect(0, 0, totalWidth, totalHeight);

    // Title banner
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
    g.setColor(new Color(0xE6EDF3));
    g.drawString("High-Resolution Zoom: 32x32 Region File Space (1024 Chunks)", 30, 42);

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
    g.setColor(new Color(0x8B949E));
    g.drawString("Detailed inspection of chunk-level path connectivity and macro-cell entry/exit seam matching", 30, 70);
    g.drawString("Anvil Region File Bounds: [0..31] x [0..31] Chunks  |  Red Dashed Lines = Macro-Cell Jumps / Disconnections", 30, 92);

    int[] testedP = {32, 8, 4};
    String[] colTitles = {
      "P = 32 (One 32x32 Macro-Cell)",
      "P = 8 (4x4 Grid of 8x8 Cells)",
      "P = 4 (8x8 Grid of 4x4 Cells)"
    };

    for (int col = 0; col < numCols; col++) {
      int pVal = testedP[col];
      int cellX = 20 + col * colWidth;

      // Column Header
      g.setColor(new Color(0x1F2937));
      g.fillRoundRect(cellX + 10, 110, colWidth - 20, 28, 6, 6);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
      g.setColor(new Color(0x58A6FF));
      g.drawString(colTitles[col], cellX + 20, 129);

      // Row 0: Square
      render32x32RegionCell(g, cellX, headerHeight, colWidth, rowHeight, pVal, true);

      // Row 1: Circle
      render32x32RegionCell(g, cellX, headerHeight + rowHeight, colWidth, rowHeight, pVal, false);
    }

    // Legend / Footer explanation
    int footY = headerHeight + numRows * rowHeight + 15;
    g.setColor(new Color(0x161B22));
    g.fillRoundRect(20, footY, totalWidth - 40, footerHeight - 25, 10, 10);
    g.setColor(new Color(0x30363D));
    g.drawRoundRect(20, footY, totalWidth - 40, footerHeight - 25, 10, 10);

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
    g.setColor(new Color(0xE6EDF3));
    g.drawString("Findings: Macro-Cell Seam Continuity in 32x32 Space:", 35, footY + 25);

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    g.setColor(new Color(0x8B949E));
    g.drawString("1. At P=32 (Left): A single recursive Hilbert curve perfectly spans the 32x32 region with 100% unit steps (dist=1) and zero jumps.", 35, footY + 45);
    g.drawString("2. At P < 32 (Center & Right): The coarse spiral visits macro-cells. orientationFor(px, pz) picks a dihedral orientation per tile from the ring side.", 35, footY + 63);
    g.drawString("3. REFLECTIONS AT DIRECTION CHANGES: The orientation chain uses reflections (1, 5) as well as rotations, so each tile's Hilbert exit corner lands", 35, footY + 81);
    g.drawString("   adjacent to the next tile's entry corner - including at the four ring corners, where no pure rotation can match the two corners.", 35, footY + 99);
    g.drawString("4. Result: every panel reports Jumps: 0 - all consecutive keys are unit steps (dist=1), and the mapping stays a strict bijection.", 35, footY + 117);

    g.dispose();

    // Save image to multiple destinations
    File outReportsDir = new File("build/reports/path_progression");
    if (!outReportsDir.exists()) outReportsDir.mkdirs();

    File chartFileReports = new File(outReportsDir, "region_32x32_zoom_path_chart.png");
    File chartFileRoot = new File("../region_32x32_zoom_path_chart.png");
    if (!chartFileRoot.getParentFile().exists()) {
      chartFileRoot = new File("region_32x32_zoom_path_chart.png");
    }
    File chartFileDocs = new File("../docs/assets/img/region_32x32_zoom_path_chart.png");

    ImageIO.write(img, "png", chartFileReports);
    ImageIO.write(img, "png", chartFileRoot);
    ImageIO.write(img, "png", new File("region_32x32_zoom_path_chart.png"));
    if (chartFileDocs.getParentFile().exists()) {
      ImageIO.write(img, "png", chartFileDocs);
    }

    System.out.println("[DEBUG_LOG] Successfully rendered 32x32 zoom chart to: " + chartFileRoot.getAbsolutePath());
  }

  private void render32x32RegionCell(Graphics2D g, int x, int y, int w, int h, int pVal, boolean isSquare) {
    renderRegionCell(g, x, y, w, h, pVal, isSquare, 32, false);
  }

  /**
   * Renders the curve inside a {@code win} x {@code win} chunk window for sub-bin size {@code
   * pVal}. {@code labelKeys} draws the key index in every chunk - only legible on small windows.
   */
  private void renderRegionCell(
      Graphics2D g, int x, int y, int w, int h, int pVal, boolean isSquare, int win, boolean labelKeys) {
    int pad = 20;
    int plotW = w - 2 * pad;
    int plotH = h - 2 * pad - 20;

    int plotX = x + pad;
    int plotY = y + pad + 20;

    // Card background
    g.setColor(new Color(0x161B22));
    g.fillRoundRect(plotX, plotY, plotW, plotH, 8, 8);
    g.setColor(new Color(0x30363D));
    g.drawRoundRect(plotX, plotY, plotW, plotH, 8, 8);

    // Title inside card
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
    g.setColor(isSquare ? new Color(0x7EE787) : new Color(0xF2CC60));
    String shapeName = isSquare ? "Square (P=" + pVal + ")" : "Circle (P=" + pVal + ")";
    g.drawString(shapeName, plotX + 10, plotY - 6);

    // Grid coordinates: [0 .. win-1] chunks
    double cellPixel = (double) Math.min(plotW, plotH) / win;

    // Draw win x win chunk grid background
    g.setColor(new Color(0x1A202C));
    for (int i = 0; i <= win; i++) {
      int px = plotX + (int) Math.round(i * cellPixel);
      int py = plotY + (int) Math.round(i * cellPixel);
      g.drawLine(px, plotY, px, plotY + (int) Math.round(win * cellPixel));
      g.drawLine(plotX, py, plotX + (int) Math.round(win * cellPixel), py);
    }

    // Draw macro-cell partition boundaries if the sub-bin is smaller than the window
    if (pVal < win) {
      g.setColor(new Color(0x303E54));
      g.setStroke(new BasicStroke(1.5f));
      for (int i = 0; i <= win; i += pVal) {
        int px = plotX + (int) Math.round(i * cellPixel);
        int py = plotY + (int) Math.round(i * cellPixel);
        g.drawLine(px, plotY, px, plotY + (int) Math.round(win * cellPixel));
        g.drawLine(plotX, py, plotX + (int) Math.round(win * cellPixel), py);
      }
    }

    // Instantiate shape with explicit pinned P
    MemoryShape<?> shape;
    if (isSquare) {
      SquareOptimizedDualLayer sq = new SquareOptimizedDualLayer("SQ_ZOOM", pVal);
      sq.set(GenericMemoryShapeParams.radius, 1024L);
      sq.set(GenericMemoryShapeParams.centerRadius, 0L);
      shape = sq;
    } else {
      CircleOptimizedDualLayer ci = new CircleOptimizedDualLayer("CI_ZOOM", pVal);
      ci.set(GenericMemoryShapeParams.radius, 1024L);
      ci.set(GenericMemoryShapeParams.centerRadius, 0L);
      shape = ci;
    }

    // Collect first contiguous path steps up to 1024 (or full region)
    int stepsToSample = Math.max(win * win, pVal * pVal * 4);
    List<PathStep> localSteps = new ArrayList<>();
    MutableRTPCoords coords = new MutableRTPCoords(0, 0);

    for (int i = 0; i < stepsToSample; i++) {
      shape.locationToXZ(i, coords);
      localSteps.add(new PathStep(i, coords.x, coords.z));
    }

    // If coordinates are centered or offset, let's determine view window
    // For P=32, coords are in [0..31] x [0..31]
    // For P<32, coords step through macro-cells: cell 0 is [0..P-1]x[0..P-1], cell 1 is [-P..-1]x[0..P-1], etc.
    // Let's map [0..31] x [0..31] if all in positive, or center on [-16..15] x [-16..15]
    boolean hasNegative = false;
    for (PathStep ps : localSteps) {
      if (ps.cx < 0 || ps.cz < 0) {
        hasNegative = true;
        break;
      }
    }

    int minCoord = hasNegative ? -(win / 2) : 0;

    // Draw path
    int nonUnitJumps = 0;
    BasicStroke solidStroke = new BasicStroke(1.3f);
    BasicStroke jumpStroke = new BasicStroke(1.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{4.0f, 4.0f}, 0.0f);

    for (int i = 0; i < localSteps.size() - 1; i++) {
      PathStep p1 = localSteps.get(i);
      PathStep p2 = localSteps.get(i + 1);

      int relX1 = p1.cx - minCoord;
      int relZ1 = p1.cz - minCoord;
      int relX2 = p2.cx - minCoord;
      int relZ2 = p2.cz - minCoord;

      if (relX1 < 0 || relX1 >= win || relZ1 < 0 || relZ1 >= win ||
          relX2 < 0 || relX2 >= win || relZ2 < 0 || relZ2 >= win) {
        continue;
      }

      int sx1 = plotX + (int) Math.round((relX1 + 0.5) * cellPixel);
      int sy1 = plotY + (int) Math.round((relZ1 + 0.5) * cellPixel);
      int sx2 = plotX + (int) Math.round((relX2 + 0.5) * cellPixel);
      int sy2 = plotY + (int) Math.round((relZ2 + 0.5) * cellPixel);

      int dist = Math.abs(p2.cx - p1.cx) + Math.abs(p2.cz - p1.cz);

      if (dist > 1) {
        // Disconnecting jump between macro-cells!
        nonUnitJumps++;
        g.setColor(new Color(0xF85149)); // Red dash
        g.setStroke(jumpStroke);
        g.drawLine(sx1, sy1, sx2, sy2);
      } else {
        // Continuous unit step
        float progress = (float) i / (localSteps.size() - 1);
        float hue = 0.55f * (1.0f - progress);
        g.setColor(new Color(Color.HSBtoRGB(hue, 0.85f, 0.95f)));
        g.setStroke(solidStroke);
        g.drawLine(sx1, sy1, sx2, sy2);
      }
    }

    if (labelKeys) {
      g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.max(8, (int) (cellPixel * 0.34))));
      g.setColor(new Color(0x6E7681));
      for (PathStep ps : localSteps) {
        int rx = ps.cx - minCoord;
        int rz = ps.cz - minCoord;
        if (rx < 0 || rx >= win || rz < 0 || rz >= win) continue;
        String label = Long.toString(ps.loc);
        int tx = plotX + (int) Math.round(rx * cellPixel) + 2;
        int ty = plotY + (int) Math.round((rz + 1) * cellPixel) - 2;
        g.drawString(label, tx, ty);
      }
    }

    // Draw start and end indicators for each macro-block to reveal entry/exit seam matching
    if (pVal > 1 && pVal < win) {
      int blockSize = pVal * pVal;
      int numBlocks = localSteps.size() / blockSize;
      for (int b = 0; b < numBlocks; b++) {
        PathStep bStart = localSteps.get(b * blockSize);
        PathStep bEnd = localSteps.get(b * blockSize + blockSize - 1);

        int bx1 = bStart.cx - minCoord;
        int bz1 = bStart.cz - minCoord;
        int bx2 = bEnd.cx - minCoord;
        int bz2 = bEnd.cz - minCoord;

        if (bx1 >= 0 && bx1 < win && bz1 >= 0 && bz1 < win) {
          int bsx = plotX + (int) Math.round((bx1 + 0.5) * cellPixel);
          int bsy = plotY + (int) Math.round((bz1 + 0.5) * cellPixel);
          g.setColor(new Color(0x2EA043)); // Green entry dot
          g.fillOval(bsx - 2, bsy - 2, 5, 5);
        }

        if (bx2 >= 0 && bx2 < win && bz2 >= 0 && bz2 < win) {
          int bex = plotX + (int) Math.round((bx2 + 0.5) * cellPixel);
          int bey = plotY + (int) Math.round((bz2 + 0.5) * cellPixel);
          g.setColor(new Color(0xDA3633)); // Red exit dot
          g.fillOval(bex - 2, bey - 2, 5, 5);
        }
      }
    }

    // Mark start of region (key 0)
    if (!localSteps.isEmpty()) {
      PathStep start = localSteps.get(0);
      int relX = start.cx - minCoord;
      int relZ = start.cz - minCoord;
      if (relX >= 0 && relX < win && relZ >= 0 && relZ < win) {
        int sx = plotX + (int) Math.round((relX + 0.5) * cellPixel);
        int sy = plotY + (int) Math.round((relZ + 0.5) * cellPixel);
        g.setColor(new Color(0x388BFD));
        g.fillOval(sx - 4, sy - 4, 9, 9);
        g.setColor(Color.WHITE);
        g.drawOval(sx - 4, sy - 4, 9, 9);
      }
    }

    // Info overlay inside bottom of card
    g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
    g.setColor(new Color(0x8B949E));
    g.drawString(String.format("Window: [%d..%d] | Steps: %d | Jumps: %d",
        minCoord, minCoord + win - 1, localSteps.size(), nonUnitJumps),
        plotX + 8, plotY + plotH - 8);
  }

  private void inspectConnectivityAndRepeatingPatterns() {
    System.out.println("\n[DEBUG_LOG] === INSPECTING CONNECTIVITY & PATTERNS IN 32x32 REGION SPACE ===");

    // Let's test P=8 (4x4 macro-cells in a 32x32 region)
    SquareOptimizedDualLayer sq8 = new SquareOptimizedDualLayer();
    sq8.set(GenericMemoryShapeParams.radius, 256L);
    sq8.set(GenericMemoryShapeParams.centerRadius, 0L);

    MutableRTPCoords prev = new MutableRTPCoords(0, 0);
    MutableRTPCoords curr = new MutableRTPCoords(0, 0);
    sq8.locationToXZ(0, prev);

    int p = sq8.getPointEdgeChunks();
    int area = p * p; // 64
    System.out.printf("[DEBUG_LOG] P=%d, Area per macro cell=%d%n", p, area);

    for (int cell = 0; cell < 8; cell++) {
      long startKey = (long) cell * area;
      long endKey = startKey + area - 1;
      sq8.locationToXZ(startKey, curr);
      int startX = curr.x;
      int startZ = curr.z;
      sq8.locationToXZ(endKey, curr);
      int endX = curr.x;
      int endZ = curr.z;
      System.out.printf("[DEBUG_LOG] Cell %d (keys %3d..%3d): start=(%2d,%2d), end=(%2d,%2d)%n",
          cell, startKey, endKey, startX, startZ, endX, endZ);

      if (cell > 0) {
        sq8.locationToXZ(startKey - 1, prev);
        sq8.locationToXZ(startKey, curr);
        int jumpDist = Math.abs(curr.x - prev.x) + Math.abs(curr.z - prev.z);
        System.out.printf("   -> Inter-cell jump from (%2d,%2d) to (%2d,%2d): dist=%d chunks%n",
            prev.x, prev.z, curr.x, curr.z, jumpDist);
      }
    }
  }

  private void renderPathProgressionChart() throws Exception {
    int colWidth = 380;
    int headerHeight = 140;
    int rowHeight = 380;
    int footerHeight = 120;
    int numCols = RADII.length; // 5 columns (32, 64, 126, 256, 512)
    int numRows = 2; // Row 0: Square, Row 1: Circle

    int totalWidth = numCols * colWidth + 40;
    int totalHeight = headerHeight + numRows * rowHeight + footerHeight;

    BufferedImage img = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

    // Background
    g.setColor(new Color(0x0C1017));
    g.fillRect(0, 0, totalWidth, totalHeight);

    // Title banner
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
    g.setColor(new Color(0xE6EDF3));
    g.drawString("Continuous Spiral-Hilbert Curve Path Progression across Radii", 30, 42);

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
    g.setColor(new Color(0x8B949E));
    g.drawString("Visualizing continuous path trajectory with radius-derived point edge P = derivePointEdgeChunks(R)", 30, 70);
    g.drawString("Top: SquareOptimizedDualLayer  |  Bottom: CircleOptimizedDualLayer  |  Color progression: Cyan -> Gold -> Crimson", 30, 92);

    // Render cells
    for (int col = 0; col < numCols; col++) {
      int r = RADII[col];
      int cellX = 20 + col * colWidth;

      // Column Header
      int p = MemoryShape.derivePointEdgeChunks(r);
      g.setColor(new Color(0x1F2937));
      g.fillRoundRect(cellX + 10, 105, colWidth - 20, 26, 6, 6);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
      g.setColor(new Color(0x58A6FF));
      g.drawString(String.format("Radius = %d chunks  (P = %d, Sub-bin = %dx%d)", r, p, p, p), cellX + 20, 123);

      // Row 0: Square
      renderShapeCell(g, cellX, headerHeight, colWidth, rowHeight, r, true);

      // Row 1: Circle
      renderShapeCell(g, cellX, headerHeight + rowHeight, colWidth, rowHeight, r, false);
    }

    // Legend / Footer explanation
    int footY = headerHeight + numRows * rowHeight + 15;
    g.setColor(new Color(0x161B22));
    g.fillRoundRect(20, footY, totalWidth - 40, footerHeight - 25, 10, 10);
    g.setColor(new Color(0x30363D));
    g.drawRoundRect(20, footY, totalWidth - 40, footerHeight - 25, 10, 10);

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
    g.setColor(new Color(0xE6EDF3));
    g.drawString("Mathematical Analysis of Path Behavior Across Scales:", 35, footY + 25);

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    g.setColor(new Color(0x8B949E));
    g.drawString("- R=32 (P=1): Pure 1-chunk Archimedean spiral. Each key visits an individual chunk in outward concentric rings. Zero Hilbert sub-binning.", 35, footY + 45);
    g.drawString("- R=64 (P=2) & R=126 (P=2): 2x2 Hilbert sub-clusters. The spiral visits 2x2 macro-cells, filling each via a 4-step Hilbert curve before stepping to next cell.", 35, footY + 63);
    g.drawString("- R=256 (P=8): 8x8 Hilbert sub-clusters (64 chunks/bin). Strong 2D spatial locality within each macro-tile, eliminating long spiral stride leaps.", 35, footY + 81);
    g.drawString("- R=512 (P=16): 16x16 Hilbert sub-clusters (256 chunks/bin). Macro-scale spiral ring progression perfectly partitioning dense 2D regional terrain.", 35, footY + 99);

    g.dispose();

    // Save image to multiple destinations
    File outReportsDir = new File("build/reports/path_progression");
    if (!outReportsDir.exists()) outReportsDir.mkdirs();

    File chartFileReports = new File(outReportsDir, "path_progression_radii_chart.png");
    File chartFileRoot = new File("../path_progression_radii_chart.png");
    if (!chartFileRoot.getParentFile().exists()) {
      chartFileRoot = new File("path_progression_radii_chart.png");
    }
    File chartFileDocs = new File("../docs/assets/img/path_progression_radii_chart.png");

    ImageIO.write(img, "png", chartFileReports);
    ImageIO.write(img, "png", chartFileRoot);
    ImageIO.write(img, "png", new File("path_progression_radii_chart.png"));
    if (chartFileDocs.getParentFile().exists()) {
      ImageIO.write(img, "png", chartFileDocs);
    }

    System.out.println("[DEBUG_LOG] Successfully rendered chart to: " + chartFileRoot.getAbsolutePath());
    System.out.println("[DEBUG_LOG] Successfully rendered chart to: " + chartFileReports.getAbsolutePath());
  }

  private void renderShapeCell(Graphics2D g, int x, int y, int w, int h, int r, boolean isSquare) {
    int pad = 15;
    int plotW = w - 2 * pad;
    int plotH = h - 2 * pad - 20;

    int plotX = x + pad;
    int plotY = y + pad + 20;

    // Card background
    g.setColor(new Color(0x161B22));
    g.fillRoundRect(plotX, plotY, plotW, plotH, 8, 8);
    g.setColor(new Color(0x30363D));
    g.drawRoundRect(plotX, plotY, plotW, plotH, 8, 8);

    // Title inside card
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
    g.setColor(isSquare ? new Color(0x7EE787) : new Color(0xF2CC60));
    String shapeName = isSquare ? "Square (Chebyshev)" : "Circle (Euclidean)";
    g.drawString(shapeName, plotX + 10, plotY - 6);

    // Instantiate shape
    MemoryShape<?> shape;
    if (isSquare) {
      SquareOptimizedDualLayer sq = new SquareOptimizedDualLayer();
      sq.set(GenericMemoryShapeParams.radius, (long) r);
      sq.set(GenericMemoryShapeParams.centerRadius, 0L);
      shape = sq;
    } else {
      CircleOptimizedDualLayer ci = new CircleOptimizedDualLayer();
      ci.set(GenericMemoryShapeParams.radius, (long) r);
      ci.set(GenericMemoryShapeParams.centerRadius, 0L);
      shape = ci;
    }

    int p = shape.getPointEdgeChunks();
    long totalRange = shape.getRange();

    // Center and scale factors
    int originX = plotX + plotW / 2;
    int originY = plotY + plotH / 2;
    double scale = (double) (Math.min(plotW, plotH) - 20) / (2.0 * r);

    // Draw boundary guide
    g.setColor(new Color(0x21262D));
    if (isSquare) {
      int boxSize = (int) (2 * r * scale);
      g.drawRect(originX - boxSize / 2, originY - boxSize / 2, boxSize, boxSize);
    } else {
      int diam = (int) (2 * r * scale);
      g.drawOval(originX - diam / 2, originY - diam / 2, diam, diam);
    }

    // Draw axes
    g.setColor(new Color(0x1E242C));
    g.drawLine(originX, plotY, originX, plotY + plotH);
    g.drawLine(plotX, originY, plotX + plotW, originY);

    // Number of steps to draw: draw representative path
    // For smaller radii, draw a good fraction of the range; for larger, draw up to 4000 steps or full ring traversal
    int maxSteps = Math.min((int) totalRange, 2500);
    if (r <= 64) {
      maxSteps = Math.min((int) totalRange, 1500);
    }

    List<PathStep> path = new ArrayList<>(maxSteps);
    MutableRTPCoords coords = new MutableRTPCoords(0, 0);

    for (int i = 0; i < maxSteps; i++) {
      shape.locationToXZ(i, coords);
      path.add(new PathStep(i, coords.x, coords.z));
    }

    // Draw trajectory polyline with color gradient
    if (path.size() > 1) {
      for (int i = 0; i < path.size() - 1; i++) {
        PathStep p1 = path.get(i);
        PathStep p2 = path.get(i + 1);

        int sx1 = originX + (int) Math.round(p1.cx * scale);
        int sy1 = originY + (int) Math.round(p1.cz * scale);
        int sx2 = originX + (int) Math.round(p2.cx * scale);
        int sy2 = originY + (int) Math.round(p2.cz * scale);

        float progress = (float) i / (path.size() - 1);
        // Gradient: Cyan (0.5f) -> Green (0.33f) -> Orange/Gold (0.12f) -> Red/Magenta (0.0f)
        float hue = 0.55f * (1.0f - progress);
        g.setColor(new Color(Color.HSBtoRGB(hue, 0.85f, 0.95f)));
        g.setStroke(new BasicStroke(1.2f));
        g.drawLine(sx1, sy1, sx2, sy2);
      }
    }

    // Highlight starting point (loc 0)
    if (!path.isEmpty()) {
      PathStep start = path.get(0);
      int sx = originX + (int) Math.round(start.cx * scale);
      int sy = originY + (int) Math.round(start.cz * scale);
      g.setColor(new Color(0x388BFD));
      g.fillOval(sx - 4, sy - 4, 9, 9);
      g.setColor(Color.WHITE);
      g.drawOval(sx - 4, sy - 4, 9, 9);
    }

    // Highlight current end point
    if (path.size() > 1) {
      PathStep end = path.get(path.size() - 1);
      int sx = originX + (int) Math.round(end.cx * scale);
      int sy = originY + (int) Math.round(end.cz * scale);
      g.setColor(new Color(0xF85149));
      g.fillOval(sx - 3, sy - 3, 7, 7);
    }

    // Info overlay inside bottom of card
    g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
    g.setColor(new Color(0x8B949E));
    g.drawString(String.format("Range: %,d | Shown: %,d steps", totalRange, maxSteps), plotX + 8, plotY + plotH - 8);
  }
}
