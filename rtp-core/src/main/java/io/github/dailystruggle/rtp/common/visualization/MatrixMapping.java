package io.github.dailystruggle.rtp.common.visualization;

import io.github.dailystruggle.mapsapi.BiomeColorSource;
import io.github.dailystruggle.mapsapi.render.CanvasDrawing;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;

import java.awt.Color;
import java.awt.image.BufferedImage;

/**
 * Reusable matrix mapping functions for coordinate translations and pixel color mapping loops
 * across visualizers and exporters (ADR-089).
 */
public final class MatrixMapping {

  private MatrixMapping() {
    throw new AssertionError("Non-instantiable utility class.");
  }

  /**
   * Translates block X to pixel X on a viewport.
   */
  public static int worldToPixelX(int bx, int minX, long boundW, int viewportW) {
    if (boundW <= 1L || viewportW <= 1) return 0;
    return (int) (((long) bx - minX) * (viewportW - 1) / boundW);
  }

  /**
   * Translates block Z to pixel Y on a viewport.
   */
  public static int worldToPixelY(int bz, int minZ, long boundH, int viewportH) {
    if (boundH <= 1L || viewportH <= 1) return 0;
    return (int) (((long) bz - minZ) * (viewportH - 1) / boundH);
  }

  /**
   * Translates pixel X to block X within bounding bounds.
   */
  public static int pixelToWorldX(int px, int minX, long boundW, int viewportW) {
    if (viewportW <= 1) return minX;
    return (int) (minX + px * boundW / (viewportW - 1));
  }

  /**
   * Translates pixel Y to block Z within bounding bounds.
   */
  public static int pixelToWorldZ(int py, int minZ, long boundH, int viewportH) {
    if (viewportH <= 1) return minZ;
    return (int) (minZ + py * boundH / (viewportH - 1));
  }

  /**
   * Populates a 1D row-major RGB pixel array of size {@code mapW * mapH} by mapping each pixel
   * to world coordinates, sampling biome and hazard state from {@link MemoryShape}, and blending
   * desaturated biomes with hazards and outside backgrounds.
   *
   * @param rgbArray    destination RGB array (must have length at least {@code mapW * mapH})
   * @param memoryShape active memory shape
   * @param minX        bounding minX
   * @param minZ        bounding minZ
   * @param boundW      bounding width
   * @param boundH      bounding height
   * @param mapW        map viewport width
   * @param mapH        map viewport height
   * @param outsideColor color for out-of-shape pixels
   */
  public static void mapTerrainAndHazards(
      int[] rgbArray,
      MemoryShape<?> memoryShape,
      int minX,
      int minZ,
      long boundW,
      long boundH,
      int mapW,
      int mapH,
      Color outsideColor
  ) {
    int outsideRgb = outsideColor.getRGB();

    for (int py = 0; py < mapH; py++) {
      int bz = pixelToWorldZ(py, minZ, boundH, mapH);
      int rowOffset = py * mapW;
      for (int px = 0; px < mapW; px++) {
        int bx = pixelToWorldX(px, minX, boundW, mapW);
        int idx = rowOffset + px;

        if (memoryShape.contains(bx, bz)) {
          String biomeName = memoryShape.biomeAt(bx, bz);
          int rawBiome = (biomeName != null) ? (BiomeColorSource.resolve(biomeName) & 0xFFFFFF) : 0x2ECC71;
          int desat = CanvasDrawing.desaturate(rawBiome, 0.45f);

          int cause = memoryShape.causeAt(bx, bz);
          if (cause >= 0) {
            rgbArray[idx] = CanvasDrawing.blend(desat, 0xE74C3C, 0.45f);
          } else {
            rgbArray[idx] = desat;
          }
        } else {
          rgbArray[idx] = outsideRgb;
        }
      }
    }
  }

  /**
   * Renders terrain and hazard field to a new {@link BufferedImage}.
   */
  public static BufferedImage renderMapBuffer(
      MemoryShape<?> memoryShape,
      int minX,
      int minZ,
      long boundW,
      long boundH,
      int mapW,
      int mapH,
      Color outsideColor
  ) {
    BufferedImage buffer = new BufferedImage(mapW, mapH, BufferedImage.TYPE_INT_RGB);
    int[] rgbArray = new int[mapW * mapH];
    mapTerrainAndHazards(rgbArray, memoryShape, minX, minZ, boundW, boundH, mapW, mapH, outsideColor);
    buffer.setRGB(0, 0, mapW, mapH, rgbArray, 0, mapW);
    return buffer;
  }
}
