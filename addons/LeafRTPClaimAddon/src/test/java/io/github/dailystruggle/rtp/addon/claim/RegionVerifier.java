package io.github.dailystruggle.rtp.addon.claim;

import io.github.dailystruggle.rtp.api.world.RTPLocation;

/**
 * Common interface for region claim verifiers.
 *
 * <p>Returns {@code true} if the candidate location is valid/allowable for teleport (e.g. wilderness),
 * and {@code false} if the candidate location is rejected (e.g. protected claim land per Rule S-003,
 * or fail-closed on exception per ADR-019 / ADR-069).
 */
public interface RegionVerifier {

  /**
   * Verify whether the specified location is permitted for RTP.
   *
   * @param location the location to verify
   * @return false when inside protected land (Rule S-003) or on error (fail closed), true in wilderness
   */
  boolean verify(RTPLocation location);
}
