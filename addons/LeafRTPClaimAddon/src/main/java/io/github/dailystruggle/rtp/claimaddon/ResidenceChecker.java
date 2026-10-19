package io.github.dailystruggle.rtp.claimaddon;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Checker for Residence claims.
 *
 * <p>Residence ({@code com.bekvon.bukkit.residence.Residence}) exposes its residence manager as an
 * instance method reached via static {@code Residence.getInstance().getResidenceManager()}
 * (current source; older builds made {@code getResidenceManager()} static, which is also
 * accepted). {@code ResidenceManager#getByLoc(org.bukkit.Location)} returns the
 * {@code ClaimedResidence} at a location, or {@code null} when unclaimed. Resolved reflectively so
 * RTP carries no compile-time dependency on Residence.
 *
 * <p>Fails open (returns false) when Residence is not enabled. Fails closed (returns true, per
 * REQ-RTP-S-003) when it is enabled but this API chain cannot be resolved or the lookup throws.
 */
public class ResidenceChecker {
  private ResidenceChecker() {}

  /** Bukkit plugin name from Residence's {@code plugin.yml} (case-sensitive lookup). */
  static final String PLUGIN_NAME = "Residence";

  /**
   * Check if a location is within a Residence claim.
   *
   * @param location the location to check
   * @return true if in a claim, or if the lookup fails while Residence is enabled
   */
  public static Boolean isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords location) {
    if (location == null) return false;
    org.bukkit.Location loc = ClaimLocationResolver.toLocation(location);
    if (loc == null) return false;
    return isInClaim(loc);
  }

  /**
   * Check if a location is within a Residence claim.
   *
   * @param location the location to check
   * @return true if in a claim, or if the lookup fails while Residence is enabled
   */
  public static Boolean isInClaim(org.bukkit.Location location) {
    if (location == null || location.getWorld() == null) return false;
    if (!ClaimPlugins.isEnabled(PLUGIN_NAME)) return false;
    try {
      Class<?> residenceClass = Class.forName("com.bekvon.bukkit.residence.Residence");
      Method managerMethod = residenceClass.getMethod("getResidenceManager");
      Object manager;
      if (Modifier.isStatic(managerMethod.getModifiers())) {
        manager = managerMethod.invoke(null);
      } else {
        Object instance = residenceClass.getMethod("getInstance").invoke(null);
        if (instance == null) throw new IllegalStateException("Residence.getInstance() is null");
        manager = managerMethod.invoke(instance);
      }
      if (manager == null) throw new IllegalStateException("getResidenceManager() is null");
      Method getByLoc =
          managerMethod.getReturnType().getMethod("getByLoc", org.bukkit.Location.class);
      // "in a claim" means a ClaimedResidence covers this location.
      return getByLoc.invoke(manager, location) != null;
    } catch (Throwable t) {
      return ClaimCheckFailure.handle(PLUGIN_NAME, t);
    }
  }
}
