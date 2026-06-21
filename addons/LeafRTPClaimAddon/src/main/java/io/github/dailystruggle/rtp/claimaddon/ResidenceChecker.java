package io.github.dailystruggle.rtp.claimaddon;

import io.github.dailystruggle.rtp.common.RTP;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.logging.Level;

/**
 * Checker for Residence claims.
 *
 * <p>Residence ({@code com.bekvon.bukkit.residence.Residence}) exposes its residence manager either
 * as a static {@code Residence.getResidenceManager()} (legacy) or as an instance method reached via
 * {@code Residence.getInstance().getResidenceManager()} (current). Either way,
 * {@code ResidenceManager#getByLoc(org.bukkit.Location)} returns the {@code ClaimedResidence} at a
 * location, or {@code null} when unclaimed. We resolve this reflectively so RTP carries no
 * compile-time dependency on Residence; when Residence is absent the reflective lookup fails once,
 * the integration disables itself for the session, and the verifier becomes a no-op.
 */
public class ResidenceChecker {
  private static boolean exists = true;

  /**
   * Check if a location is within a Residence claim.
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
   * Check if a location is within a Residence claim.
   *
   * @param location the location to check
   * @return true if in a claim, false otherwise
   */
  public static Boolean isInClaim(org.bukkit.Location location) {
    if (!exists) return false;
    try {
      Class<?> residenceClass = Class.forName("com.bekvon.bukkit.residence.Residence");
      Method managerMethod = residenceClass.getMethod("getResidenceManager");
      Object manager;
      if (Modifier.isStatic(managerMethod.getModifiers())) {
        manager = managerMethod.invoke(null);
      } else {
        Object instance = residenceClass.getMethod("getInstance").invoke(null);
        manager = managerMethod.invoke(instance);
      }
      if (manager == null) return false;
      Object residence =
          manager
              .getClass()
              .getMethod("getByLoc", org.bukkit.Location.class)
              .invoke(manager, location);
      // "in a claim" means a ClaimedResidence covers this location.
      return residence != null;
    } catch (Throwable t) {
      exists = false;
      RTP.log(
          Level.SEVERE,
          "[RTP] Critical architectural incompatibility detected. Disabling Residence integration for this session to prevent server instability.",
          t);
    }
    return false;
  }
}
