package io.github.dailystruggle.rtp.claimaddon;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import java.lang.reflect.Method;
import org.bukkit.Location;

/**
 * Checker for UltimateClaims claims (Craftaro, formerly Songoda).
 *
 * <p>API chain, from the plugin source: static {@code UltimateClaims.getInstance()}, instance
 * {@code getClaimManager()}, then {@code ClaimManager#getClaim(String world, int chunkX, int
 * chunkZ)}, which returns the {@code Claim} covering that chunk or {@code null}. The main class is
 * {@code com.craftaro.ultimateclaims.UltimateClaims} (2.x) or
 * {@code com.songoda.ultimateclaims.UltimateClaims} (before the 2023 package move). There is no
 * {@code getClaim(Location)}; the {@code Chunk} overloads need a loaded chunk (S-005), so only the
 * coordinate overload is used. Resolved reflectively so RTP carries no compile-time dependency.
 *
 * <p>Fails open (returns false) when UltimateClaims is not enabled. Fails closed (returns true,
 * per REQ-RTP-S-003) when it is enabled but this API chain cannot be resolved or the lookup throws.
 */
public class UltimateClaimsChecker {
  private UltimateClaimsChecker() {}

  /** Bukkit plugin name from UltimateClaims' {@code plugin.yml} (case-sensitive lookup). */
  static final String PLUGIN_NAME = "UltimateClaims";

  static final String[] MAIN_CLASSES = {
    "com.craftaro.ultimateclaims.UltimateClaims",
    "com.songoda.ultimateclaims.UltimateClaims"
  };

  /**
   * Check if an RTP coordinate is within an UltimateClaims claim.
   *
   * @param location the coordinate to check
   * @return true if in a claim, or if the lookup fails while UltimateClaims is enabled
   */
  public static Boolean isInClaim(RTPCoords location) {
    if (location == null) return false;
    Location loc = ClaimLocationResolver.toLocation(location);
    if (loc == null) return false;
    return isInClaim(loc);
  }

  /**
   * Check if a Bukkit location is within an UltimateClaims claim.
   *
   * @param location the location to check
   * @return true if in a claim, or if the lookup fails while UltimateClaims is enabled
   */
  public static Boolean isInClaim(Location location) {
    if (location == null || location.getWorld() == null) return false;
    if (!ClaimPlugins.isEnabled(PLUGIN_NAME)) return false;
    try {
      Class<?> mainClass = mainClass();
      Object plugin = mainClass.getMethod("getInstance").invoke(null);
      if (plugin == null) throw new IllegalStateException("UltimateClaims.getInstance() is null");
      Method getClaimManager = mainClass.getMethod("getClaimManager");
      Object claimManager = getClaimManager.invoke(plugin);
      if (claimManager == null) throw new IllegalStateException("getClaimManager() is null");
      Method getClaim =
          getClaimManager.getReturnType().getMethod("getClaim", String.class, int.class, int.class);
      Object claim =
          getClaim.invoke(
              claimManager,
              location.getWorld().getName(),
              location.getBlockX() >> 4,
              location.getBlockZ() >> 4);
      return claim != null;
    } catch (Throwable t) {
      return ClaimCheckFailure.handle(PLUGIN_NAME, t);
    }
  }

  private static Class<?> mainClass() throws ClassNotFoundException {
    for (String name : MAIN_CLASSES) {
      try {
        return Class.forName(name);
      } catch (ClassNotFoundException ignored) {
        // Ignored: try the pre-2023 package next
      }
    }
    throw new ClassNotFoundException(String.join(" / ", MAIN_CLASSES));
  }
}
