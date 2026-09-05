package io.github.dailystruggle.rtp.claimaddon;

import io.github.dailystruggle.rtp.common.RTP;
import java.util.logging.Level;
import me.angeschossen.lands.api.integration.LandsIntegration;
import org.bukkit.plugin.Plugin;

/** Checker for Lands claims */
@SuppressWarnings("deprecation")
public class LandsChecker {
  private static LandsIntegration landsIntegration = null;
  private static boolean exists = true;

  /**
   * Setup Lands integration
   *
   * @param yourPlugin the plugin instance
   */
  public static void landsSetup(Plugin yourPlugin) {
    try {
      landsIntegration = new LandsIntegration(yourPlugin);
    } catch (Throwable ignored) {
      // Lands API not present or initialization failed; checker remains a no-op.
      landsIntegration = null;
    }
  }

  public static Boolean isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords location) {
    if (!exists || landsIntegration == null) return false;
    org.bukkit.World world = org.bukkit.Bukkit.getWorld(location.worldName());
    if (world == null) return false;
    return isInClaim(new org.bukkit.Location(world, location.x(), location.y(), location.z()));
  }

  /**
   * Check if a location is within a Lands claim
   *
   * @param location the location to check
   * @return true if in a claim, false otherwise
   */
  public static Boolean isInClaim(org.bukkit.Location location) {
    if (!exists || landsIntegration == null) return false;
    try {
      int chunkX = location.getBlockX() >> 4;
      int chunkZ = location.getBlockZ() >> 4;
      if (location.getWorld() == null) return false;
      return landsIntegration.isClaimed(location.getWorld(), chunkX, chunkZ);
    } catch (Throwable t) {
      exists = false;
      RTP.log(
          Level.WARNING,
          "[RTP] Lands integration encountered an error during claim check. Disabling Lands integration.",
          t);
    }
    return false;
  }
}
