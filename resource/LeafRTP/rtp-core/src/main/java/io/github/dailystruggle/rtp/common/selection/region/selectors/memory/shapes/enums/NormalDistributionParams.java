package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums;

/**
 * Represents the configurable parameters for shapes that use a normal (Gaussian)
 * distribution for location selection.
 */
public enum NormalDistributionParams {
  /**
   * The mode for handling biome selection and bad location avoidance.
   * @see io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode
   */
  mode,

  /**
   * The outer radius of the shape.
   */
  radius,

  /**
   * The inner radius of the shape, creating a hollow center.
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
   * If true, each selected location is marked as "bad" to prevent it from being
   * selected again.
   */
  uniquePlacements,

  /**
   * The mean (center) of the normal distribution, as a value from 0.0 to 1.0,
   * representing the distance from the centerRadius to the main radius.
   */
  mean,

  /**
   * The standard deviation of the normal distribution, controlling how tightly
   * the selections cluster around the mean.
   */
  deviation,

  /**
   * If true, the shape's effective area will expand to compensate for discovered
   * bad locations.
   */
  expand
}
