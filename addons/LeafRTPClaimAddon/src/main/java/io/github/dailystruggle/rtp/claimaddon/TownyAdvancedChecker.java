package io.github.dailystruggle.rtp.claimaddon;

import com.palmergames.bukkit.towny.TownyAPI;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.logging.Level;
import org.bukkit.Location;

/** Checker for TownyAdvanced towns */
public class TownyAdvancedChecker {
  private static boolean exists = true;

  public static boolean isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords location) {
    if (!exists || location == null) return false;
    org.bukkit.Location loc = ClaimLocationResolver.toLocation(location);
    if (loc == null) return false;
    return isInClaim(loc);
  }

  public static boolean isInClaim(Location location) {
    if (!exists) return false;
    try {
      return !TownyAPI.getInstance().isWilderness(location);
    } catch (Throwable t) {
      exists = false;
      RTP.log(
          Level.SEVERE,
          "[RTP] Critical architectural incompatibility detected. Disabling TownyAdvanced integration for this session to prevent server instability.",
          t);
    }
    return false;
  }
}
