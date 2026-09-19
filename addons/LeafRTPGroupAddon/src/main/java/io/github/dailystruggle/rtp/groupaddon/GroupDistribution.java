package io.github.dailystruggle.rtp.groupaddon;

/**
 * Geometric distribution types for placing participants within a subspace.
 */
public enum GroupDistribution {
  /** Tight co-op group clustered around the anchor. */
  CLUSTER,
  /** Opposing poles (e.g., theta and theta + pi) for 1v1 PvP duels. */
  OPPOSING_POLES,
  /** Perimeter ring at fixed radius around a central anchor. */
  RING,
  /** Regular grid spacing for squads / teams. */
  GRID
}
