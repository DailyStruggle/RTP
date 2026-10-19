package io.github.dailystruggle.rtp.claimaddon;

import java.util.Optional;

/**
 * Checker for HuskTowns claims.
 *
 * <p>HuskTowns 3.x ({@code net.william278.husktowns}) exposes a Bukkit API singleton via static
 * {@code BukkitHuskTownsAPI.getInstance()} (throws while the API is not registered).
 * {@code getClaimAt(org.bukkit.Location)} returns an {@link Optional} holding the town claim at
 * that location; a {@code getClaimAt(org.bukkit.Chunk)} overload exists but needs a loaded chunk,
 * so the {@code Location} overload is resolved by exact signature. Resolved reflectively so RTP
 * carries no compile-time dependency on HuskTowns.
 *
 * <p>Fails open (returns false) when HuskTowns is not enabled. Fails closed (returns true, per
 * REQ-RTP-S-003) when it is enabled but this API chain cannot be resolved or the lookup throws.
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
      Class<?> apiClass = Class.forName("net.william278.husktowns.api.BukkitHuskTownsAPI");
      Object api = apiClass.getMethod("getInstance").invoke(null);
      if (api == null) throw new IllegalStateException("BukkitHuskTownsAPI.getInstance() is null");
      Object result =
          apiClass.getMethod("getClaimAt", org.bukkit.Location.class).invoke(api, location);
      if (!(result instanceof Optional<?> claim)) {
        throw new IllegalStateException("getClaimAt(Location) returned " + result);
      }
      return claim.isPresent();
    } catch (Throwable t) {
      return ClaimCheckFailure.handle(PLUGIN_NAME, t);
    }
  }
}
