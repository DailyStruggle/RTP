package io.github.dailystruggle.rtp.bukkit.tools.softdepends.claims;

import io.github.dailystruggle.rtp.common.RTP;
import java.util.logging.Level;
import me.william278.husktowns.HuskTownsAPI;

/** Checker for HuskTowns claims */
public class HuskTownsChecker {
  private static boolean exists = true;

  public static Boolean isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords location) {
    if (!exists) return false;
    org.bukkit.World world = org.bukkit.Bukkit.getWorld(location.worldName());
    if (world == null) return false;
    return isInClaim(new org.bukkit.Location(world, location.x(), location.y(), location.z()));
  }

  public static Boolean isInClaim(org.bukkit.Location location) {
    if (!exists) return false;
    try {
      return !HuskTownsAPI.getInstance().isWilderness(location);
    } catch (Throwable t) {
      exists = false;
      RTP.log(
          Level.SEVERE,
          "[RTP] Critical architectural incompatibility detected. Disabling HuskTowns integration for this session to prevent server instability.",
          t);
    }
    return false;
  }
}
