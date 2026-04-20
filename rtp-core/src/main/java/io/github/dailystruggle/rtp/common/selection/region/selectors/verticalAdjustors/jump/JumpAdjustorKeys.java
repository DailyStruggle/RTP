package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump;

/**
 * Represents the configurable parameters for the JumpAdjustor.
 */
public enum JumpAdjustorKeys {
  /**
   * The minimum Y-level to consider for a valid teleport location.
   */
  minY,

  /**
   * The maximum Y-level to consider for a valid teleport location.
   */
  maxY,

  /**
   * The step size to use when scanning for a safe location.
   */
  step,

  /**
   * Whether a valid location must have direct access to the sky.
   */
  requireSkyLight
}
