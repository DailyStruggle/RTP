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
  requireSkyLight,

  /**
   * Largest floor-height difference, in blocks, allowed between the landing spot and the floors
   * {@link #slopeRadius} blocks away in each cardinal direction. Absent or negative disables the
   * check (ADR-119). No default entry, so unset verts keep their cache key.
   */
  maxSlope,

  /**
   * Horizontal distance of the slope samples, clamped to [1, 8]; 2 when absent (ADR-119).
   */
  slopeRadius
}
