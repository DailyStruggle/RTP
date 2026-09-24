package io.github.dailystruggle.rtp.api.action;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

/**
 * Spatial boundary containment rules for scripted actions (ADR-093).
 */
@PublicApi
public enum ConfinementBoundary {
  /**
   * Confined to the localized subspace footprint allocated for the session.
   */
  SUBSPACE,

  /**
   * Confined to the mathematical boundary of the parent region (MemoryShape.contains).
   */
  REGION,

  /**
   * Confined radially to a leash distance from the session anchor coordinate.
   */
  LEASH
}
