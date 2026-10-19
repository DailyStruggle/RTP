package io.github.dailystruggle.rtp.claimaddon;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Checker for KingdomsX (Kingdoms) claimed land.
 *
 * <p>KingdomsX ({@code org.kingdoms}) maps a Bukkit location to its land record through static
 * {@code org.kingdoms.constants.land.Land.getLand(org.bukkit.Location)}, as its API wiki shows. The
 * result is {@code null} for a chunk with no data; a non-null land can still be unclaimed, so the
 * verdict is {@code Land#isClaimed()}. {@code getLand} has other one-argument overloads, so it is
 * resolved by exact signature. Resolved reflectively so RTP carries no compile-time dependency on
 * KingdomsX.
 *
 * <p>Fails open (returns false) when Kingdoms is not enabled. Fails closed (returns true, per
 * REQ-RTP-S-003) when it is enabled but this API chain cannot be resolved or the lookup throws.
 */
public class KingdomsXChecker {
  private KingdomsXChecker() {}

  /** Bukkit plugin name from KingdomsX's {@code plugin.yml} (case-sensitive lookup). */
  static final String PLUGIN_NAME = "Kingdoms";

  private static final String INTEGRATION = "KingdomsX";

  /**
   * Check if a location is within KingdomsX claimed land.
   *
   * @param location the location to check
   * @return true if in a claim, or if the lookup fails while Kingdoms is enabled
   */
  public static Boolean isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords location) {
    if (location == null) return false;
    org.bukkit.Location loc = ClaimLocationResolver.toLocation(location);
    if (loc == null) return false;
    return isInClaim(loc);
  }

  /**
   * Check if a location is within KingdomsX claimed land.
   *
   * @param location the location to check
   * @return true if in a claim, or if the lookup fails while Kingdoms is enabled
   */
  public static Boolean isInClaim(org.bukkit.Location location) {
    if (location == null || location.getWorld() == null) return false;
    if (!ClaimPlugins.isEnabled(PLUGIN_NAME)) return false;
    try {
      Class<?> landClass = Class.forName("org.kingdoms.constants.land.Land");
      Method getLand = landClass.getMethod("getLand", org.bukkit.Location.class);
      if (!Modifier.isStatic(getLand.getModifiers())) {
        throw new NoSuchMethodException("static Land.getLand(Location)");
      }
      Object land = getLand.invoke(null, location);
      if (land == null) return false;
      return Boolean.TRUE.equals(landClass.getMethod("isClaimed").invoke(land));
    } catch (Throwable t) {
      return ClaimCheckFailure.handle(INTEGRATION, t);
    }
  }
}
