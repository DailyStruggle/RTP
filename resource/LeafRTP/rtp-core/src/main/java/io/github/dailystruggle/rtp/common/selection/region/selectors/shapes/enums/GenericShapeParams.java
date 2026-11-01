package io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.enums;

/**
 * Represents the generic configurable parameters common to most shapes.
 */
public enum GenericShapeParams {
  /**
   * The UUID of the world this shape belongs to.
   */
  WORLDID,

  /**
   * The radius of the shape, defining its extent from the center.
   */
  RADIUS,

  /**
   * The x-coordinate of the shape's center.
   */
  CX,

  /**
   * The z-coordinate of the shape's center.
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
   * Whether the shape's boundaries should be overridden by the world border.
   */
  WORLDBORDEROVERRIDE
}
