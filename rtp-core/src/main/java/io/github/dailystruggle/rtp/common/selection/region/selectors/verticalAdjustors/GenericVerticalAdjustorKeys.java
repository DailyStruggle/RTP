package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors;

/**
 * Represents the generic configurable parameters common to most vertical adjustors.
 */
public enum GenericVerticalAdjustorKeys {
  /**
   * The minimum Y-level to consider for a valid teleport location.
   */
  minY,

  /**
   * The maximum Y-level to consider for a valid teleport location.
   */
  maxY,

  /**
   * The direction to search for a safe spot (e.g., UP, DOWN, or a specific strategy).
   */
  direction,

  /**
   * Whether a valid location must have direct access to the sky.
   */
  requireSkyLight
}
