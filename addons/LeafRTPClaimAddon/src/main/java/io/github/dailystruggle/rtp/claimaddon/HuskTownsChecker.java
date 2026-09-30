package io.github.dailystruggle.rtp.claimaddon;

import io.github.dailystruggle.rtp.common.RTP;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Checker for HuskTowns claims.
 *
 * <p>HuskTowns ({@code net.william278.husktowns}) exposes a Bukkit API singleton via
 * {@code BukkitHuskTownsAPI.getInstance()}. {@code #getClaimAt(org.bukkit.Location)} returns an
 * {@link Optional} containing the town claim at that location if one exists.
 *
 * <p>Resolved reflectively so RTP carries no compile-time dependency on HuskTowns.
 * When HuskTowns is absent, the lookup fails gracefully once, disables itself for the session,
 * and the verifier becomes a no-op (honoring REQ-RTP-S-003 and S-004).
 */
public class HuskTownsChecker {
  private static boolean exists = true;

  /**
   * Check if an RTP coordinate is within a HuskTowns claim.
   *
   * @param location the coordinate to check
   * @return true if in a claim, false otherwise
   */
  public static Boolean isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords location) {
    if (!exists || location == null) return false;
    if (org.bukkit.Bukkit.getServer() == null) return false;
    org.bukkit.World world = org.bukkit.Bukkit.getWorld(location.worldName());
    if (world == null) return false;
    return isInClaim(new org.bukkit.Location(world, location.x(), location.y(), location.z()));
  }

  /**
   * Check if a Bukkit location is within a HuskTowns claim.
   *
   * @param location the location to check
   * @return true if in a claim, false otherwise
   */
  public static Boolean isInClaim(org.bukkit.Location location) {
    if (!exists) return false;
    try {
      Class<?> apiClass = Class.forName("net.william278.husktowns.api.BukkitHuskTownsAPI");
      Object api = apiClass.getMethod("getInstance").invoke(null);
      if (api == null) return false;

      Method getClaimAt = findMethod(api.getClass(), "getClaimAt", org.bukkit.Location.class);
      if (getClaimAt == null) return false;

      Object result = getClaimAt.invoke(api, location);
      if (result instanceof Optional<?> opt) {
        return opt.isPresent();
      }
      return result != null;
    } catch (Throwable t) {
      exists = false;
      RTP.log(
          Level.SEVERE,
          "[RTP] Critical architectural incompatibility detected. Disabling HuskTowns integration for this session to prevent server instability.",
          t);
    }
    return false;
  }

  private static Method findMethod(Class<?> type, String name, Class<?> paramType) {
    for (Method m : type.getMethods()) {
      if (m.getName().equals(name)
          && m.getParameterCount() == 1
          && m.getParameterTypes()[0].isAssignableFrom(paramType)) {
        return m;
      }
    }
    return null;
  }
}
