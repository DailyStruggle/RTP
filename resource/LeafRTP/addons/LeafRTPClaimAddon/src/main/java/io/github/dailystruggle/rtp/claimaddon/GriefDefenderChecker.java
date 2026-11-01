package io.github.dailystruggle.rtp.claimaddon;

import com.griefdefender.api.GriefDefender;
import com.griefdefender.api.claim.Claim;

/** Checker for GriefDefender claims */
public class GriefDefenderChecker {
  private GriefDefenderChecker() {}

  public static Boolean isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords location) {
    if (location == null) return false;
    org.bukkit.Location loc = ClaimLocationResolver.toLocation(location);
    if (loc == null) return false;
    return isInClaim(loc);
  }

  public static Boolean isInClaim(org.bukkit.Location location) {
    try {
      Claim claim = GriefDefender.getCore().getClaimAt(location);
      // Null when GriefDefender does not manage this world: no claims, not "in a claim".
      if (claim == null) return false;
      return !claim.isWilderness();
    } catch (Throwable t) {
      return ClaimCheckFailure.handle("GriefDefender", t);
    }
  }
}
