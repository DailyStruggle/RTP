package io.github.dailystruggle.mapsapi.render;

import io.github.dailystruggle.mapsapi.MapCanvas;

/**
 * Platform-neutral drawing primitives and color transformation utilities
 * for {@link MapCanvas} (ADR-089).
 *
 * <p>Provides:
 * <ul>
 *   <li>RGB desaturation (for background biomes).
 *   <li>RGB alpha tinting / blending (for translucent hazard overlays).
 *   <li>High-contrast outlined point markers (for candidates / teleports).
 *   <li>Bresenham line vectors (for arrival trajectories).
 *   <li>Multi-tier health / capacity gauge bars (for L1/L2/L3 queue gauges).
 * </ul>
 */
public final class CanvasDrawing {

  private CanvasDrawing() {
    throw new AssertionError("Non-instantiable utility class.");
  }

  /**
   * Desaturates a 24-bit RGB colour towards grayscale.
   *
   * @param rgb              input 0xRRGGBB colour
   * @param saturationFactor 1.0 = unchanged, 0.0 = completely grayscale, 0.5 = 50% desaturated
   * @return desaturated 24-bit RGB colour
   */
  public static int desaturate(int rgb, float saturationFactor) {
    if (saturationFactor >= 1.0f) return rgb & 0xFFFFFF;
    int r = (rgb >> 16) & 0xFF;
    int g = (rgb >> 8) & 0xFF;
    int b = rgb & 0xFF;

    // Standard perceptual luminance
    int gray = (int) (0.299f * r + 0.587f * g + 0.114f * b);

    int outR = Math.min(255, Math.max(0, (int) (gray + (r - gray) * saturationFactor)));
    int outG = Math.min(255, Math.max(0, (int) (gray + (g - gray) * saturationFactor)));
    int outB = Math.min(255, Math.max(0, (int) (gray + (b - gray) * saturationFactor)));

    return (outR << 16) | (outG << 8) | outB;
  }

  /**
   * Blends an overlay tint colour into a base colour with a given weight factor.
   *
   * @param baseRgb    background colour (0xRRGGBB)
   * @param tintRgb    overlay tint colour (0xRRGGBB)
   * @param tintFactor weight of tint: 0.0 = 100% base, 1.0 = 100% tint
   * @return blended 24-bit RGB colour
   */
  public static int blend(int baseRgb, int tintRgb, float tintFactor) {
    if (tintFactor <= 0.0f) return baseRgb & 0xFFFFFF;
    if (tintFactor >= 1.0f) return tintRgb & 0xFFFFFF;

    int r0 = (baseRgb >> 16) & 0xFF;
    int g0 = (baseRgb >> 8) & 0xFF;
    int b0 = baseRgb & 0xFF;

    int r1 = (tintRgb >> 16) & 0xFF;
    int g1 = (tintRgb >> 8) & 0xFF;
    int b1 = tintRgb & 0xFF;

    int r = (int) (r0 + (r1 - r0) * tintFactor);
    int g = (int) (g0 + (g1 - g0) * tintFactor);
    int b = (int) (b0 + (b1 - b0) * tintFactor);

    return (r << 16) | (g << 8) | b;
  }

  /**
   * Draws an outlined high-contrast point marker on the canvas.
   *
   * @param canvas    target canvas
   * @param cx        center X coordinate
   * @param cy        center Y coordinate
   * @param fillRgb   interior fill colour (0xRRGGBB)
   * @param borderRgb 1-pixel border outline colour (0xRRGGBB, typically black or dark gray)
   * @param radius    radius in pixels (e.g. 1 for a 3x3 square, 2 for a 5x5 marker)
   */
  public static void drawOutlinedPoint(MapCanvas canvas, int cx, int cy, int fillRgb, int borderRgb, int radius) {
    if (canvas == null) return;
    if (radius <= 0) {
      canvas.setPixelRgb(cx, cy, fillRgb);
      return;
    }

    for (int dy = -radius; dy <= radius; dy++) {
      for (int dx = -radius; dx <= radius; dx++) {
        int px = cx + dx;
        int py = cy + dy;

        // Border: outer perimeter
        boolean isBorder = (Math.abs(dx) == radius || Math.abs(dy) == radius);
        if (isBorder) {
          canvas.setPixelRgb(px, py, borderRgb);
        } else {
          canvas.setPixelRgb(px, py, fillRgb);
        }
      }
    }
  }

  /**
   * Draws a line between two points using Bresenham's integer algorithm.
   *
   * @param canvas target canvas
   * @param x0     start X
   * @param y0     start Y
   * @param x1     end X
   * @param y1     end Y
   * @param rgb    line colour (0xRRGGBB)
   */
  public static void drawLine(MapCanvas canvas, int x0, int y0, int x1, int y1, int rgb) {
    if (canvas == null) return;
    int dx = Math.abs(x1 - x0);
    int dy = Math.abs(y1 - y0);
    int sx = x0 < x1 ? 1 : -1;
    int sy = y0 < y1 ? 1 : -1;
    int err = dx - dy;

    int curX = x0;
    int curY = y0;
    while (true) {
      canvas.setPixelRgb(curX, curY, rgb);
      if (curX == x1 && curY == y1) break;
      int e2 = 2 * err;
      if (e2 > -dy) {
        err -= dy;
        curX += sx;
      }
      if (e2 < dx) {
        err += dx;
        curY += sy;
      }
    }
  }

  /**
   * Draws a segmented capacity/health gauge bar.
   *
   * @param canvas     target canvas
   * @param x          top-left X
   * @param y          top-left Y
   * @param width      bar total width in pixels
   * @param height     bar total height in pixels
   * @param fillFraction fraction filled [0.0, 1.0]
   * @param fillRgb    filled segment colour (0xRRGGBB)
   * @param emptyRgb   empty segment colour (0xRRGGBB, typically dark gray)
   * @param borderRgb  outer 1-pixel frame colour (0xRRGGBB)
   */
  public static void drawGaugeBar(MapCanvas canvas, int x, int y, int width, int height,
                                  double fillFraction, int fillRgb, int emptyRgb, int borderRgb) {
    if (canvas == null || width < 4 || height < 3) return;
    double clamped = Math.max(0.0, Math.min(1.0, fillFraction));

    // 1. Draw outer 1-pixel border
    for (int px = x; px < x + width; px++) {
      canvas.setPixelRgb(px, y, borderRgb);
      canvas.setPixelRgb(px, y + height - 1, borderRgb);
    }
    for (int py = y; py < y + height; py++) {
      canvas.setPixelRgb(x, py, borderRgb);
      canvas.setPixelRgb(x + width - 1, py, borderRgb);
    }

    // 2. Interior fill
    int innerWidth = width - 2;
    int filledWidth = (int) Math.round(innerWidth * clamped);

    for (int py = y + 1; py < y + height - 1; py++) {
      for (int px = x + 1; px < x + width - 1; px++) {
        int relX = px - (x + 1);
        if (relX < filledWidth) {
          canvas.setPixelRgb(px, py, fillRgb);
        } else {
          canvas.setPixelRgb(px, py, emptyRgb);
        }
      }
    }
  }
}
