package io.github.dailystruggle.rtp.api.schematic;

/**
 * How a schematic's bounding box is positioned relative to the arrival
 * {@link io.github.dailystruggle.rtp.api.world.RTPLocation}.
 *
 * <p>See ADR-058.
 */
public enum PasteAnchor {
  /**
   * The arrival block is the horizontal center of the footprint and the vertical
   * bottom of the schematic (a platform/pad placed under the player's feet).
   */
  BOTTOM_CENTER,
  /**
   * The arrival block is the horizontal center of the footprint, and the schematic is
   * shifted vertically so that the topmost solid block in its center column sits exactly
   * one block below the arrival Y. This makes the arrival point a standable surface (solid
   * footing below the feet, schematic air at the feet and head) so the safety check would
   * pass, without any iterative re-paste. Falls back to {@link #BOTTOM_CENTER} when the
   * center column contains no solid block. See ADR-058.
   */
  SURFACE_CENTER,
  /** The arrival block is the geometric center of the schematic's bounding box. */
  CENTER,
  /** The schematic's own origin is placed at the arrival block (no re-anchoring). */
  ORIGIN
}
