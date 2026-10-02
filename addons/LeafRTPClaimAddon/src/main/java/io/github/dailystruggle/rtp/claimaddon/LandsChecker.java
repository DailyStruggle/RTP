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
    if (!exists || landsIntegration == null || location == null) return false;
    org.bukkit.Location loc = ClaimLocationResolver.toLocation(location);
    if (loc == null) return false;
    return isInClaim(loc);
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

  /**
   * Resolves the claim boundary for Lands at the given coordinates.
   * Uses AdaptiveClaimProber against {@link #isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords)}.
   */
  public static java.util.Optional<io.github.dailystruggle.rtp.api.claim.ClaimBoundary> getBoundaryAt(String worldName, int x, int z) {
    if (!exists || landsIntegration == null || worldName == null) {
      return java.util.Optional.empty();
    }
    try {
      return AdaptiveClaimProber.probeBoundary(worldName, x, z, LandsChecker::isInClaim);
    } catch (Throwable t) {
      RTP.log(
          Level.WARNING,
          "[RTP] Lands integration encountered an error resolving boundary at (" + x + "," + z + ").",
          t);
      return java.util.Optional.empty();
    }
  }
}
