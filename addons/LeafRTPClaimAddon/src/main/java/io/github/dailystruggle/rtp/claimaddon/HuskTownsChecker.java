package io.github.dailystruggle.rtp.claimaddon;

import net.william278.husktowns.api.BukkitHuskTownsAPI;

/**
 * Checker for HuskTowns claims, compiled against {@code net.william278.husktowns:husktowns-bukkit}.
 *
 * <p>{@link BukkitHuskTownsAPI#getInstance()} (throws while unregistered) answers
 * {@code getClaimAt(Location)} with the town claim at that location, if any. The
 * {@code getClaimAt(Chunk)} overload needs a loaded chunk and is not used. API types are touched
 * only in {@link Api}, after the enabled check (ADR-118).
 *
 * <p>Fails open (returns false) when HuskTowns is not enabled. Fails closed (returns true, per
 * REQ-RTP-S-003) through {@link ClaimCheckFailure} when it is enabled but the API is not
 * registered, is incompatible, or the lookup throws.
 */
public class HuskTownsChecker {
  private HuskTownsChecker() {}

  /** Bukkit plugin name from HuskTowns' {@code plugin.yml} (case-sensitive lookup). */
  static final String PLUGIN_NAME = "HuskTowns";

  /**
   * Check if an RTP coordinate is within a HuskTowns claim.
   *
   * @param location the coordinate to check
   * @return true if in a claim, or if the lookup fails while HuskTowns is enabled
   */
  public static Boolean isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords location) {
    if (location == null) return false;
    org.bukkit.Location loc = ClaimLocationResolver.toLocation(location);
    if (loc == null) return false;
    return isInClaim(loc);
  }

  /**
   * Check if a Bukkit location is within a HuskTowns claim.
   *
   * @param location the location to check
   * @return true if in a claim, or if the lookup fails while HuskTowns is enabled
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

  /** Loaded on first call, so HuskTowns classes link only while the plugin is enabled. */
  private static final class Api {
    private Api() {}

    static boolean isClaimAt(org.bukkit.Location location) {
      BukkitHuskTownsAPI api = BukkitHuskTownsAPI.getInstance();
      if (api == null) throw new IllegalStateException("BukkitHuskTownsAPI.getInstance() is null");
      return api.getClaimAt(location).isPresent();
    }
  }
}
