package io.github.dailystruggle.rtp.api.schematic;

/**
 * Minimal 3D integer block-coordinate carrier so platform or plugin location types (such as
 * {@code RTPLocation}) can be passed directly to {@link SchematicPlacementPlanner}.
 *
 * @governedBy ADR-115
 * @governedBy leafrtp-ADR-041
 * @trace REQ-RTP-S-005
 */
public interface SchematicCoordinate {
  /** @return world block X coordinate. */
  int x();

  /** @return world block Y coordinate. */
  int y();

  /** @return world block Z coordinate. */
  int z();
}
