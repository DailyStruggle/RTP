package io.github.dailystruggle.rtp.claimaddon;

import net.william278.huskclaims.api.BukkitHuskClaimsAPI;

/**
 * Checker for HuskClaims claims, compiled against {@code net.william278.huskclaims:huskclaims-bukkit}.
 *
 * <p>{@link BukkitHuskClaimsAPI#getInstance()} (throws while unregistered) adapts a Bukkit location
 * with {@code getPosition(Location)}; {@code isClaimAt(Position)} says whether a claim covers it.
 * API types are touched only in {@link Api}, after the enabled check (ADR-118).
 *
 * <p>Fails open (returns false) when HuskClaims is not enabled. Fails closed (returns true, per
 * REQ-RTP-S-003) through {@link ClaimCheckFailure} when it is enabled but the API is not
 * registered, is incompatible, or the lookup throws.
 */
public class HuskClaimsChecker {
  private HuskClaimsChecker() {}

  /** Bukkit plugin name from HuskClaims' {@code plugin.yml} (case-sensitive lookup). */
  static final String PLUGIN_NAME = "HuskClaims";

  /**
   * Check if a location is within a HuskClaims claim.
   *
   * @param location the location to check
   * @return true if in a claim, or if the lookup fails while HuskClaims is enabled
   */
  public static Boolean isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords location) {
    if (location == null) return false;
    org.bukkit.Location loc = ClaimLocationResolver.toLocation(location);
    if (loc == null) return false;
    return isInClaim(loc);
  }

  /**
   * Check if a location is within a HuskClaims claim.
   *
   * @param location the location to check
   * @return true if in a claim, or if the lookup fails while HuskClaims is enabled
   */
  public static Boolean isInClaim(org.bukkit.Location location) {
    if (location == null || location.getWorld() == null) return false;
    if (!ClaimPlugins.isEnabled(PLUGIN_NAME)) return false;
    try {
      return Api.isClaimAt(location);
    } catch (Throwable t) {
      return ClaimCheckFailure.handle(PLUGIN_NAME, t);
    }
  }

  /** Loaded on first call, so HuskClaims classes link only while the plugin is enabled. */
  private static final class Api {
    private Api() {}

    static boolean isClaimAt(org.bukkit.Location location) {
      BukkitHuskClaimsAPI api = BukkitHuskClaimsAPI.getInstance();
      if (api == null) throw new IllegalStateException("BukkitHuskClaimsAPI.getInstance() is null");
      return api.isClaimAt(api.getPosition(location));
    }
  }
}
