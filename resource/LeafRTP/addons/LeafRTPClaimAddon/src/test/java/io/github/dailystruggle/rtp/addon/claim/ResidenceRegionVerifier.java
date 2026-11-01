package io.github.dailystruggle.rtp.addon.claim;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.claimaddon.ResidenceChecker;

/**
 * Region verifier for Residence claims.
 * Follows Rule S-003, ADR-019, and ADR-069.
 */
public class ResidenceRegionVerifier implements RegionVerifier {

  @Override
  public boolean verify(RTPLocation location) {
    if (location == null || location.world() == null) {
      return false;
    }
    try {
      RTPCoords coords = new RTPCoords(location.world().name(), location.x(), location.y(), location.z());
      Boolean inClaim = ResidenceChecker.isInClaim(coords);
      if (inClaim == null || inClaim) {
        return false;
      }
      return true;
    } catch (Throwable t) {
      return false;
    }
  }
}
