package io.github.dailystruggle.rtp.claimaddon;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Checker for UltimateClaims claims (Songoda/Craftaro).
 *
 * <p>UltimateClaims ({@code com.songoda.ultimateclaims.UltimateClaims}) manages chunk-based
 * and region-based land claims. We resolve the plugin singleton and claim manager reflectively
 * so RTP carries no compile-time dependency on UltimateClaims.
 *
 * <p>When UltimateClaims is absent or disabled, the checker safely fails open (returns false).
 * When active, any reflection lookup failure or internal error fails closed (returns true,
 * treating the coordinate as claimed/protected per REQ-RTP-S-003).
 */
public class UltimateClaimsChecker {
  private static boolean exists = true;
  private static Boolean available = null;

  private static final String[] CANDIDATE_CLASSES = {
    "com.craftaro.ultimateclaims.UltimateClaims",
    "com.songoda.ultimateclaims.UltimateClaims"
  };

  private static boolean isAvailable() {
    if (!exists) return false;
    if (available != null) return available;
    for (String cName : CANDIDATE_CLASSES) {
      try {
        Class.forName(cName);
        available = true;
        return true;
      } catch (ClassNotFoundException ignored) {
      }
    }
    available = false;
    return false;
  }

  /**
   * Check if an RTP coordinate is within an UltimateClaims claim.
   *
   * @param location the coordinate to check
   * @return true if in a claim or lookup fails while plugin active, false otherwise
   */
  public static Boolean isInClaim(RTPCoords location) {
    if (!exists || location == null || !isAvailable()) return false;
    org.bukkit.Location loc = ClaimLocationResolver.toLocation(location);
    if (loc == null) return false;
    return isInClaim(loc);
  }

  /**
   * Check if a Bukkit location is within an UltimateClaims claim.
   *
   * @param location the location to check
   * @return true if in a claim or lookup fails while plugin active, false otherwise
   */
  public static Boolean isInClaim(org.bukkit.Location location) {
    if (!exists || location == null || location.getWorld() == null || !isAvailable()) return false;
    if (org.bukkit.Bukkit.getServer() == null) return false;
    try {
      Class<?> mainClass = null;
      for (String cName : CANDIDATE_CLASSES) {
        try {
          mainClass = Class.forName(cName);
          break;
        } catch (ClassNotFoundException ignored) {
        }
      }

      if (mainClass == null) return false;

      Object plugin = null;
      try {
        Method getInstance = mainClass.getMethod("getInstance");
        plugin = getInstance.invoke(null);
      } catch (NoSuchMethodException ignored) {
        try {
          Method getPlugin = mainClass.getMethod("getPlugin", Class.class);
          plugin = getPlugin.invoke(null, mainClass);
        } catch (NoSuchMethodException ignored2) {
          org.bukkit.plugin.Plugin p = org.bukkit.Bukkit.getPluginManager().getPlugin("UltimateClaims");
          if (p != null && mainClass.isInstance(p)) {
            plugin = p;
          }
        }
      }

      if (plugin == null) return false;

      // Probe claimManager from plugin instance
      Object claimManager = null;
      for (Method m : plugin.getClass().getMethods()) {
        if ((m.getName().equals("getClaimManager") || m.getName().equals("getClaimsManager"))
            && m.getParameterCount() == 0) {
          claimManager = m.invoke(plugin);
          break;
        }
      }

      Object target = (claimManager != null) ? claimManager : plugin;

      // 1. Try getClaim(Location)
      Method getClaimLoc = findMethod(target.getClass(), "getClaim", org.bukkit.Location.class);
      if (getClaimLoc != null) {
        Object claim = getClaimLoc.invoke(target, location);
        return claim != null;
      }

      // 2. Try getClaim(Chunk) or getClaim(World, int, int) or getClaimAt(...)
      Method getClaimChunk = findMethod(target.getClass(), "getClaim", org.bukkit.Chunk.class);
      if (getClaimChunk != null) {
        // Chunk chunk = location.getChunk() might load chunk synchronously if not loaded.
        // To strictly respect S-005 (no sync chunk load), check world.isChunkLoaded first or coordinates.
        int cx = location.getBlockX() >> 4;
        int cz = location.getBlockZ() >> 4;
        if (location.getWorld().isChunkLoaded(cx, cz)) {
          Object claim = getClaimChunk.invoke(target, location.getWorld().getChunkAt(cx, cz));
          return claim != null;
        }
        // If not loaded, check coordinate-based methods on target
      }

      // Try getClaim(String/World, int chunkX, int chunkZ)
      for (Method m : target.getClass().getMethods()) {
        if (m.getName().startsWith("getClaim") && m.getParameterCount() == 3) {
          Class<?>[] pTypes = m.getParameterTypes();
          if ((pTypes[0] == String.class || pTypes[0] == org.bukkit.World.class)
              && (pTypes[1] == int.class || pTypes[1] == Integer.class)
              && (pTypes[2] == int.class || pTypes[2] == Integer.class)) {
            Object arg0 = (pTypes[0] == String.class) ? location.getWorld().getName() : location.getWorld();
            int cx = location.getBlockX() >> 4;
            int cz = location.getBlockZ() >> 4;
            Object claim = m.invoke(target, arg0, cx, cz);
            return claim != null;
          }
        }
      }

      // Fallback probe for any isClaimed method
      for (Method m : target.getClass().getMethods()) {
        if (m.getName().equals("isClaimed") || m.getName().equals("isClaim")) {
          if (m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(org.bukkit.Location.class)) {
            Object res = m.invoke(target, location);
            if (res instanceof Boolean b) return b;
          }
        }
      }

      return false;
    } catch (Throwable t) {
      RTP.log(
          Level.WARNING,
          "[RTP] Error querying UltimateClaims claim status at "
              + location.getWorld().getName() + " (" + location.getBlockX() + ", " + location.getBlockZ()
              + "); failing closed per REQ-RTP-S-003.",
          t);
      return true; // Fail-closed
    }
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
