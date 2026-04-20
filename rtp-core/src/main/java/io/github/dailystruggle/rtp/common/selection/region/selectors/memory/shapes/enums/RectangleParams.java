package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums;

/**
 * Represents the configurable parameters for a Rectangle shape.
 */
public enum RectangleParams {
  /**
   * The mode for handling biome selection.
   * @see io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode
   */
  mode,

  /**
   * The width of the rectangle.
   */
  width,

  /**
   * The height of the rectangle.
   */
  height,

  /**
   * The x-coordinate of the center of the rectangle.
   */
  centerX,

  /**
   * The z-coordinate of the center of the rectangle.
   */
  centerZ,

  /**
   * The rotation of the rectangle in degrees.
   */
  rotation,

  /**
   * Whether to ensure that each selected location is unique.
   */
  uniquePlacements
}
