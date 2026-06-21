package io.github.dailystruggle.rtp.claimaddon;

import io.github.dailystruggle.rtp.common.RTP;
import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Checker for HuskClaims claims.
 *
 * <p>HuskClaims ({@code net.william278.huskclaims}) exposes a Bukkit API singleton via
 * {@code BukkitHuskClaimsAPI.getInstance()}. {@code #getPosition(org.bukkit.Location)} adapts a
 * Bukkit location into a HuskClaims {@code Position}, and {@code #isClaimAt(Position)} returns
 * whether a claim covers it. We resolve this reflectively so RTP carries no compile-time dependency
 * on HuskClaims; the {@code isClaimAt} lookup is by name/arity to avoid pinning the {@code Position}
 * parameter type. When HuskClaims is absent the reflective lookup fails once, the integration
 * disables itself for the session, and the verifier becomes a no-op.
 */
public class HuskClaimsChecker {
  private static boolean exists = true;

  /**
   * Check if a location is within a HuskClaims claim.
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
   * Check if a location is within a HuskClaims claim.
   *
   * @param location the location to check
   * @return true if in a claim, false otherwise
   */
  public static Boolean isInClaim(org.bukkit.Location location) {
    if (!exists) return false;
    try {
      Class<?> apiClass = Class.forName("net.william278.huskclaims.api.BukkitHuskClaimsAPI");
      Object api = apiClass.getMethod("getInstance").invoke(null);
      if (api == null) return false;
      Object position =
          api.getClass()
              .getMethod("getPosition", org.bukkit.Location.class)
              .invoke(api, location);
      if (position == null) return false;
      Method isClaimAt = findMethod(api.getClass(), "isClaimAt");
      if (isClaimAt == null) return false;
      Object result = isClaimAt.invoke(api, position);
      // "in a claim" means a claim covers this position.
      return Boolean.TRUE.equals(result);
    } catch (Throwable t) {
      exists = false;
      RTP.log(
          Level.SEVERE,
          "[RTP] Critical architectural incompatibility detected. Disabling HuskClaims integration for this session to prevent server instability.",
          t);
    }
    return false;
  }

  private static Method findMethod(Class<?> type, String name) {
    for (Method m : type.getMethods()) {
      if (m.getName().equals(name) && m.getParameterCount() == 1) return m;
    }
    return null;
  }
}
