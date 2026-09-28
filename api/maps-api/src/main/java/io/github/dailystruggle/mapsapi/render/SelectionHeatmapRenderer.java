package io.github.dailystruggle.mapsapi.render;

import io.github.dailystruggle.mapsapi.MapCanvas;
import io.github.dailystruggle.mapsapi.model.SelectionHeatmap;

/**
 * Stateless {@link ChartRenderer} for {@link SelectionHeatmap} (ADR-089, REQ-RTP-MAP-002).
 *
 * <p>Produces a crystal-clear selection density and location map:
 * <ul>
 *   <li><b>Context Backdrop:</b> Renders the region domain shape silhouette and desaturated
 *       terrain/biome colors so the geographic layout and boundary are instantly recognized.</li>
 *   <li><b>Continuous Heat Field:</b> Kernel density smoothing across candidate selections
 *       with a multi-stop vivid perceptual gradient (Deep Navy -> Cyan -> Green -> Yellow -> Orange -> Crimson -> White).</li>
 *   <li><b>High-Visibility Selection Markers:</b> Outlined, high-contrast markers for discrete
 *       selection points so operators can clearly and easily see exactly where selections are.</li>
 * </ul>
 *
 * <p>Operates 100% off-tick with zero main-thread chunk I/O (Rule S-005).</p>
 */
public final class SelectionHeatmapRenderer implements ChartRenderer<SelectionHeatmap> {

  public static final SelectionHeatmapRenderer INSTANCE = new SelectionHeatmapRenderer();

  private static final int COLOR_VOID = 0xFF0D1117;          // Outside region domain
  private static final int COLOR_DOMAIN_BASE = 0xFF1C2128;   // Inside region domain (empty)
  private static final int COLOR_DOMAIN_BORDER = 0xFF38434F; // Domain edge contour

  // Distinct marker colors per selection type
  private static final int COLOR_MARKER_CANDIDATE = 0xFF00E5FF; // Vivid cyan
  private static final int COLOR_MARKER_L1 = 0xFF00E676;        // Emerald
  private static final int COLOR_MARKER_L2 = 0xFF29B6F6;        // Sky blue
  private static final int COLOR_MARKER_ARRIVAL = 0xFFFFD600;   // Gold
  private static final int COLOR_MARKER_HAZARD = 0xFFFF1744;    // Vibrant crimson

  public SelectionHeatmapRenderer() {
    // Explicit public constructor
  }

  @Override
  public void render(MapCanvas canvas, SelectionHeatmap model) {
    if (canvas == null) {
      throw new IllegalArgumentException("canvas shall not be null");
    }
    if (model == null) {
      throw new IllegalArgumentException("model shall not be null");
    }

    final int cw = canvas.width();
    final int ch = canvas.height();
    final int mw = model.width();
    final int mh = model.height();

    final boolean[] insideDomain = model.insideDomain();
    final int[] biomeRgb = model.biomeRgb();
    final double[] density = model.densityGrid();
    final double maxDensity = Math.max(1.0, model.maxDensity());

    // 1. Render Base Backdrop (Domain mask + desaturated terrain + heat overlay)
    for (int y = 0; y < ch; y++) {
      int my = Math.min(mh - 1, (int) ((long) y * mh / ch));
      int myRow = my * mw;
      for (int x = 0; x < cw; x++) {
        int mx = Math.min(mw - 1, (int) ((long) x * mw / cw));
        int mIdx = myRow + mx;

        boolean inside = (mIdx < insideDomain.length) && insideDomain[mIdx];
        if (!inside) {
          canvas.setPixelRgb(x, y, COLOR_VOID);
          continue;
        }

        // Domain edge contour detection
        boolean edge = isDomainEdge(insideDomain, mx, my, mw, mh);
        if (edge) {
          canvas.setPixelRgb(x, y, COLOR_DOMAIN_BORDER);
          continue;
        }

        // Base terrain color
        int baseRgb = COLOR_DOMAIN_BASE;
        if (biomeRgb != null && mIdx < biomeRgb.length && biomeRgb[mIdx] != 0) {
          baseRgb = desaturateAndDim(biomeRgb[mIdx], 0.35f, 0.45f);
        }

        // Sample density
        double d = (mIdx < density.length) ? density[mIdx] : 0.0;
        if (d <= 0.0) {
          canvas.setPixelRgb(x, y, baseRgb);
        } else {
          // Normalized heat t in [0.0 .. 1.0]
          double t = Math.min(1.0, Math.max(0.0, d / maxDensity));
          int heatRgb = sampleHeatRamp(t);

          // Alpha blend heat over terrain based on intensity (0.45 min alpha to 0.90 max alpha)
          float alpha = (float) (0.40 + 0.55 * Math.pow(t, 0.6));
          int blended = blendRgb(baseRgb, heatRgb, alpha);
          canvas.setPixelRgb(x, y, blended);
        }
      }
    }

    // 2. High-Visibility Selection Point Markers
    // Render individual discrete selection points with crisp contrasting dark borders
    // so operators can immediately pinpoint exactly where selections took place.
    long bMinX = model.minX();
    long bMinZ = model.minZ();
    long boundW = model.boundW();
    long boundH = model.boundH();

    // Scale marker radius based on canvas resolution (1px for 128px, 2px for 512px, 3px for 1024px+)
    int markerRadius = Math.max(1, Math.min(5, cw / 256));

    for (SelectionHeatmap.SelectionPoint pt : model.points()) {
      int px = (int) ((pt.blockX() - bMinX) * (cw - 1) / boundW);
      int py = (int) ((pt.blockZ() - bMinZ) * (ch - 1) / boundH);

      if (px < 0 || px >= cw || py < 0 || py >= ch) continue;

      int fillColor = switch (pt.type()) {
        case QUEUE_L1 -> COLOR_MARKER_L1;
        case QUEUE_L2 -> COLOR_MARKER_L2;
        case ARRIVAL -> COLOR_MARKER_ARRIVAL;
        case HAZARD_DISCARD -> COLOR_MARKER_HAZARD;
        case CANDIDATE -> COLOR_MARKER_CANDIDATE;
      };

      // Draw outlined point marker: dark border around bright core
      drawOutlinedMarker(canvas, px, py, markerRadius, fillColor, cw, ch);
    }
  }

  private static void drawOutlinedMarker(
      MapCanvas canvas, int cx, int cy, int radius, int fillRgb, int cw, int ch) {
    int r2 = radius * radius;
    int borderR2 = (radius + 1) * (radius + 1);

    int minY = Math.max(0, cy - radius - 1);
    int maxY = Math.min(ch - 1, cy + radius + 1);
    int minX = Math.max(0, cx - radius - 1);
    int maxX = Math.min(cw - 1, cx + radius + 1);

    for (int y = minY; y <= maxY; y++) {
      int dy = y - cy;
      for (int x = minX; x <= maxX; x++) {
        int dx = x - cx;
        int d2 = dx * dx + dy * dy;
        if (d2 <= r2) {
          canvas.setPixelRgb(x, y, fillRgb);
        } else if (d2 <= borderR2) {
          canvas.setPixelRgb(x, y, 0xFF000000); // 1-pixel crisp black outline
        }
      }
    }
  }

  private static boolean isDomainEdge(boolean[] insideDomain, int x, int y, int w, int h) {
    if (x == 0 || x == w - 1 || y == 0 || y == h - 1) return true;
    return !insideDomain[y * w + (x - 1)]
        || !insideDomain[y * w + (x + 1)]
        || !insideDomain[(y - 1) * w + x]
        || !insideDomain[(y + 1) * w + x];
  }

  /**
   * Continuous multi-stop perceptual heat ramp:
   * 0.0 -> Deep Navy (0x0D47A1)
   * 0.2 -> Cyan (0x00E5FF)
   * 0.4 -> Green (0x00E676)
   * 0.6 -> Yellow (0xFFEA00)
   * 0.8 -> Orange (0xFF6D00)
   * 0.95 -> Crimson (0xD50000)
   * 1.0 -> White Heat (0xFFFFFF)
   */
  public static int sampleHeatRamp(double t) {
    if (t <= 0.0) return 0xFF0D47A1;
    if (t >= 1.0) return 0xFFFFFFFF;

    if (t < 0.2) {
      float f = (float) (t / 0.2);
      return interpolateRgb(0xFF0D47A1, 0xFF00E5FF, f);
    } else if (t < 0.4) {
      float f = (float) ((t - 0.2) / 0.2);
      return interpolateRgb(0xFF00E5FF, 0xFF00E676, f);
    } else if (t < 0.6) {
      float f = (float) ((t - 0.4) / 0.2);
      return interpolateRgb(0xFF00E676, 0xFFFFEA00, f);
    } else if (t < 0.8) {
      float f = (float) ((t - 0.6) / 0.2);
      return interpolateRgb(0xFFFFEA00, 0xFFFF6D00, f);
    } else if (t < 0.95) {
      float f = (float) ((t - 0.8) / 0.15);
      return interpolateRgb(0xFFFF6D00, 0xFFD50000, f);
    } else {
      float f = (float) ((t - 0.95) / 0.05);
      return interpolateRgb(0xFFD50000, 0xFFFFFFFF, f);
    }
  }

  private static int interpolateRgb(int rgb1, int rgb2, float t) {
    int r1 = (rgb1 >> 16) & 0xFF;
    int g1 = (rgb1 >> 8) & 0xFF;
    int b1 = rgb1 & 0xFF;

    int r2 = (rgb2 >> 16) & 0xFF;
    int g2 = (rgb2 >> 8) & 0xFF;
    int b2 = rgb2 & 0xFF;

    int r = Math.min(255, Math.max(0, (int) (r1 + (r2 - r1) * t)));
    int g = Math.min(255, Math.max(0, (int) (g1 + (g2 - g1) * t)));
    int b = Math.min(255, Math.max(0, (int) (b1 + (b2 - b1) * t)));

    return 0xFF000000 | (r << 16) | (g << 8) | b;
  }

  private static int blendRgb(int bg, int fg, float alpha) {
    int rBg = (bg >> 16) & 0xFF;
    int gBg = (bg >> 8) & 0xFF;
    int bBg = bg & 0xFF;

    int rFg = (fg >> 16) & 0xFF;
    int gFg = (fg >> 8) & 0xFF;
    int bFg = fg & 0xFF;

    int r = (int) (rBg * (1.0f - alpha) + rFg * alpha);
    int g = (int) (gBg * (1.0f - alpha) + gFg * alpha);
    int b = (int) (bBg * (1.0f - alpha) + bFg * alpha);

    return 0xFF000000 | (r << 16) | (g << 8) | b;
  }

  private static int desaturateAndDim(int rgb, float satScale, float brightScale) {
    int r = (rgb >> 16) & 0xFF;
    int g = (rgb >> 8) & 0xFF;
    int b = rgb & 0xFF;

    float[] hsb = java.awt.Color.RGBtoHSB(r, g, b, null);
    hsb[1] = Math.max(0.0f, Math.min(1.0f, hsb[1] * satScale));
    hsb[2] = Math.max(0.0f, Math.min(1.0f, hsb[2] * brightScale));

    return java.awt.Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]);
  }
}
