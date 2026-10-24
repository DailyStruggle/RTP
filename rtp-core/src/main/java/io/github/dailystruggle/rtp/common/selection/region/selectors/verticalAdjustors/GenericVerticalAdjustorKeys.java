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
  slopeRadius,

  /**
   * Steepest hill, in degrees, a landing may sit on: refuses mountainsides and cliff edges over
   * the 5x5-chunk square (ADR-120). Absent or negative disables the check. No default entry, so
   * unset verts keep their cache key.
   */
  maxHillAngle
}
