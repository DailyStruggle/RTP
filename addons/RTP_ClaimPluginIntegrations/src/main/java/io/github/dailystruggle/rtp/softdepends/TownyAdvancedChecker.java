package io.github.dailystruggle.rtp.softdepends;

import com.palmergames.bukkit.towny.TownyAPI;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.logging.Level;
import org.bukkit.Location;

public class TownyAdvancedChecker {
  private static boolean exists = true;

  public static boolean isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords location) {
    if (!exists) return false;
    org.bukkit.World world = org.bukkit.Bukkit.getWorld(location.worldName());
    if (world == null) return false;
    return isInClaim(new org.bukkit.Location(world, location.x(), location.y(), location.z()));
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
