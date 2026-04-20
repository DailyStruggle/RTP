package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums;

/**
 * Represents the generic configurable parameters common to most memory-based shapes
 * like circles and squares.
 */
public enum GenericMemoryShapeParams {
  /**
   * The mode for handling biome selection and bad location avoidance.
   * @see io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode
   */
  mode,

  /**
   * The outer radius of the shape, defining its maximum extent from the center.
   */
  radius,

  /**
   * The inner radius of the shape, defining a hollow area in the center where
   * teleports will not occur.
   */
  centerRadius,

  /**
   * The x-coordinate of the shape's center.
   */
  centerX,

  /**
   * The z-coordinate of the shape's center.
   */
  centerZ,

  /**
   * A weighting factor that influences the distribution of random selections.
   * A value greater than 1.0 biases selections towards the outer edge, while a
   * value less than 1.0 biases them towards the center.
   */
  weight,

  /**
   * If true, each selected location is marked as "bad" to prevent it from being
   * selected again.
   */
  uniquePlacements,

  /**
   * If true, the shape's effective area will expand to compensate for discovered
   * bad locations, attempting to maintain the original number of valid spots.
   */
  expand
}
