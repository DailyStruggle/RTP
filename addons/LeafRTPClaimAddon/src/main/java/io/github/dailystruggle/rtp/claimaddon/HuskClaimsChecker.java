package io.github.dailystruggle.rtp.claimaddon;

import java.lang.reflect.Method;

/**
 * Checker for HuskClaims claims.
 *
 * <p>HuskClaims ({@code net.william278.huskclaims}) exposes a Bukkit API singleton via static
 * {@code BukkitHuskClaimsAPI.getInstance()}. {@code getPosition(org.bukkit.Location)} adapts a
 * Bukkit location into a HuskClaims {@code Position}, and {@code isClaimAt(Position)} returns
 * whether a claim covers it. {@code isClaimAt} also has an {@code (OnlineUser)} overload, so the
 * method is resolved by the declared {@code Position} type, never by name and arity. Resolved
 * reflectively so RTP carries no compile-time dependency on HuskClaims.
 *
 * <p>Fails open (returns false) when HuskClaims is not enabled. Fails closed (returns true, per
 * REQ-RTP-S-003) when it is enabled but this API chain cannot be resolved or the lookup throws.
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
      Class<?> apiClass = Class.forName("net.william278.huskclaims.api.BukkitHuskClaimsAPI");
      Object api = apiClass.getMethod("getInstance").invoke(null);
      if (api == null) throw new IllegalStateException("BukkitHuskClaimsAPI.getInstance() is null");
      Method getPosition = apiClass.getMethod("getPosition", org.bukkit.Location.class);
      Object position = getPosition.invoke(api, location);
      if (position == null) throw new IllegalStateException("getPosition(Location) is null");
      Method isClaimAt = apiClass.getMethod("isClaimAt", getPosition.getReturnType());
      Object result = isClaimAt.invoke(api, position);
      if (!(result instanceof Boolean claimed)) {
        throw new IllegalStateException("isClaimAt(Position) returned " + result);
      }
      // "in a claim" means a claim covers this position.
      return claimed;
    } catch (Throwable t) {
      return ClaimCheckFailure.handle(PLUGIN_NAME, t);
    }
  }
}
