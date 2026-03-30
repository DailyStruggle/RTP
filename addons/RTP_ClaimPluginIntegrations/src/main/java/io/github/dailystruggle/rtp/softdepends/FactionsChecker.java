package io.github.dailystruggle.rtp.softdepends;

import com.massivecraft.factions.Board;
import com.massivecraft.factions.FLocation;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.logging.Level;

/** Checker for Factions claims */
public class FactionsChecker {
  private static boolean exists = true;

  /**
   * Check if a location is within a Factions claim
   *
   * @param location the location to check
   * @return true if in a claim, false otherwise
   */
  public static Boolean isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords location) {
    if (!exists) return false;
    org.bukkit.World world = org.bukkit.Bukkit.getWorld(location.worldName());
    if (world == null) return false;
    return isInClaim(new org.bukkit.Location(world, location.x(), location.y(), location.z()));
  }

  /**
   * Check if a location is within a Factions claim
   *
   * @param location the location to check
   * @return true if in a claim, false otherwise
   */
  public static Boolean isInClaim(org.bukkit.Location location) {
    if (!exists) return false;
    try {
      FLocation fLocation = new FLocation(location);
      return Board.getInstance().getFactionAt(fLocation).getOwnerList(fLocation).isEmpty();
    } catch (Throwable t) {
      exists = false;
      RTP.log(
          Level.SEVERE,
          "[RTP] Critical architectural incompatibility detected. Disabling Factions integration for this session to prevent server instability.",
          t);
    }
    return false;
  }
}
