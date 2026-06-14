package io.github.dailystruggle.rtp.bukkit.tools.softdepends.claims;

import io.github.dailystruggle.rtp.common.RTP;
import java.util.logging.Level;

/**
 * Checker for CrashClaim claims.
 *
 * <p>CrashClaim ({@code net.crashcraft.crashclaim.CrashClaim}) exposes its API via the static
 * {@code CrashClaim.getPlugin()} accessor, whose {@code getApi()} returns a {@code CrashClaimAPI}
 * with {@code getClaim(org.bukkit.Location)} returning the {@code Claim} at a location, or
 * {@code null} when unclaimed. We resolve this reflectively so RTP carries no compile-time
 * dependency on CrashClaim; when CrashClaim is absent the reflective lookup fails once, the
 * integration disables itself for the session, and the verifier becomes a no-op.
 */
public class CrashClaimChecker {
  private static boolean exists = true;

  /**
   * Check if a location is within a CrashClaim claim.
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
   * Check if a location is within a CrashClaim claim.
   *
   * @param location the location to check
   * @return true if in a claim, false otherwise
   */
  public static Boolean isInClaim(org.bukkit.Location location) {
    if (!exists) return false;
    try {
      Class<?> crashClaimClass = Class.forName("net.crashcraft.crashclaim.CrashClaim");
      Object plugin = crashClaimClass.getMethod("getPlugin").invoke(null);
      if (plugin == null) return false;
      Object api = plugin.getClass().getMethod("getApi").invoke(plugin);
      if (api == null) return false;
      Object claim =
          api.getClass().getMethod("getClaim", org.bukkit.Location.class).invoke(api, location);
      // "in a claim" means a Claim covers this location.
      return claim != null;
    } catch (Throwable t) {
      exists = false;
      RTP.log(
          Level.SEVERE,
          "[RTP] Critical architectural incompatibility detected. Disabling CrashClaim integration for this session to prevent server instability.",
          t);
    }
    return false;
  }
}
