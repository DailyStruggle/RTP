package io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.enums;

/**
 * Represents the configurable parameters for a Rectangle shape.
 */
public enum RectangleParams {
  /**
   * The width of the rectangle.
   */
  WIDTH,

  /**
   * The height of the rectangle.
   */
  HEIGHT,

  /**
   * The x-coordinate of the center of the rectangle.
   */
  CX,

  /**
   * The z-coordinate of the center of the rectangle.
   */
  CZ,

  /**
   * The minimum Y-level for a valid teleport location.
   */
  MINY,

  /**
   * The maximum Y-level for a valid teleport location.
   */
  MAXY,

  /**
   * Whether a valid location must have direct access to the sky.
   */
  REQUIRESKYLIGHT,

  /**
   * Whether the shape should be overridden by the world border.
   */
  WORLDBORDEROVERRIDE
}
