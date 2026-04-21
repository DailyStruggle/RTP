package io.github.dailystruggle.rtp.bukkit.tools.softdepends.claims;

import com.griefdefender.api.GriefDefender;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.Objects;
import java.util.logging.Level;

/** Checker for GriefDefender claims */
public class GriefDefenderChecker {
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
      return !Objects.requireNonNull(GriefDefender.getCore().getClaimAt(location)).isWilderness();
    } catch (Throwable t) {
      exists = false;
      RTP.log(
          Level.SEVERE,
          "[RTP] Critical architectural incompatibility detected. Disabling GriefDefender integration for this session to prevent server instability.",
          t);
    }
    return false;
  }
}
