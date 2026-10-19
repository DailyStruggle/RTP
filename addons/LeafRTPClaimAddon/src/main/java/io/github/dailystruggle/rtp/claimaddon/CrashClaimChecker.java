package io.github.dailystruggle.rtp.claimaddon;

import java.lang.reflect.Method;

/**
 * Checker for CrashClaim claims.
 *
 * <p>CrashClaim ({@code net.crashcraft.crashclaim.CrashClaim}) exposes its API via the static
 * {@code CrashClaim.getPlugin()} accessor, whose {@code getApi()} returns a
 * {@code net.crashcraft.crashclaim.api.CrashClaimAPI} with {@code getClaim(org.bukkit.Location)}
 * returning the {@code Claim} at a location, or {@code null} when unclaimed. Resolved reflectively
 * so RTP carries no compile-time dependency on CrashClaim.
 *
 * <p>Fails open (returns false) when CrashClaim is not enabled. Fails closed (returns true, per
 * REQ-RTP-S-003) when it is enabled but this API chain cannot be resolved or the lookup throws.
 */
public class CrashClaimChecker {
  private CrashClaimChecker() {}

  /** Bukkit plugin name from CrashClaim's {@code plugin.yml} (case-sensitive lookup). */
  static final String PLUGIN_NAME = "CrashClaim";

  /**
   * Check if a location is within a CrashClaim claim.
   *
   * @param location the location to check
   * @return true if in a claim, or if the lookup fails while CrashClaim is enabled
   */
  public static Boolean isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords location) {
    if (location == null) return false;
    org.bukkit.Location loc = ClaimLocationResolver.toLocation(location);
    if (loc == null) return false;
    return isInClaim(loc);
  }

  /**
   * Check if a location is within a CrashClaim claim.
   *
   * @param location the location to check
   * @return true if in a claim, or if the lookup fails while CrashClaim is enabled
   */
  public static Boolean isInClaim(org.bukkit.Location location) {
    if (location == null || location.getWorld() == null) return false;
    if (!ClaimPlugins.isEnabled(PLUGIN_NAME)) return false;
    try {
      Class<?> crashClaimClass = Class.forName("net.crashcraft.crashclaim.CrashClaim");
      Object plugin = crashClaimClass.getMethod("getPlugin").invoke(null);
      if (plugin == null) throw new IllegalStateException("CrashClaim.getPlugin() is null");
      Method getApi = crashClaimClass.getMethod("getApi");
      Object api = getApi.invoke(plugin);
      if (api == null) throw new IllegalStateException("CrashClaim.getApi() is null");
      Method getClaim = getApi.getReturnType().getMethod("getClaim", org.bukkit.Location.class);
      // "in a claim" means a Claim covers this location.
      return getClaim.invoke(api, location) != null;
    } catch (Throwable t) {
      return ClaimCheckFailure.handle(PLUGIN_NAME, t);
    }
  }
}
