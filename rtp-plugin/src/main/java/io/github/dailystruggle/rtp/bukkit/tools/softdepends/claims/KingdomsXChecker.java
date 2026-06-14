package io.github.dailystruggle.rtp.bukkit.tools.softdepends.claims;

import io.github.dailystruggle.rtp.common.RTP;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.logging.Level;

/**
 * Checker for KingdomsX (Kingdoms) claimed land.
 *
 * <p>KingdomsX ({@code org.kingdoms}) maps a Bukkit location to a chunk via
 * {@code SimpleChunkLocation.of(org.bukkit.Location)}; the resulting chunk's {@code getLand()}
 * returns the {@code Land} record for that chunk (never {@code null} for a loaded chunk), and a land
 * with a non-{@code null} owning kingdom is claimed. We resolve this reflectively (by name/arity, so
 * obfuscation-stable across builds) so RTP carries no compile-time dependency on KingdomsX. When
 * KingdomsX is absent the reflective lookup fails once, the integration disables itself for the
 * session, and the verifier becomes a no-op.
 */
public class KingdomsXChecker {
  private static boolean exists = true;

  /**
   * Check if a location is within KingdomsX claimed land.
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
   * Check if a location is within KingdomsX claimed land.
   *
   * @param location the location to check
   * @return true if in a claim, false otherwise
   */
  public static Boolean isInClaim(org.bukkit.Location location) {
    if (!exists) return false;
    try {
      Class<?> chunkClass =
          Class.forName("org.kingdoms.constants.land.location.SimpleChunkLocation");
      Method of = findMethod(chunkClass, "of", 1, true);
      if (of == null) return false;
      Object chunk = of.invoke(null, location);
      if (chunk == null) return false;
      Method getLand = findMethod(chunk.getClass(), "getLand", 0, false);
      if (getLand == null) return false;
      Object land = getLand.invoke(chunk);
      if (land == null) return false;
      // Prefer an explicit isClaimed() when present; otherwise a non-null owning kingdom claims it.
      Method isClaimed = findMethod(land.getClass(), "isClaimed", 0, false);
      if (isClaimed != null) {
        return Boolean.TRUE.equals(isClaimed.invoke(land));
      }
      Method getKingdom = findMethod(land.getClass(), "getKingdom", 0, false);
      if (getKingdom == null) return false;
      return getKingdom.invoke(land) != null;
    } catch (Throwable t) {
      exists = false;
      RTP.log(
          Level.SEVERE,
          "[RTP] Critical architectural incompatibility detected. Disabling KingdomsX integration for this session to prevent server instability.",
          t);
    }
    return false;
  }

  private static Method findMethod(Class<?> type, String name, int paramCount, boolean isStatic) {
    for (Method m : type.getMethods()) {
      if (m.getName().equals(name)
          && m.getParameterCount() == paramCount
          && Modifier.isStatic(m.getModifiers()) == isStatic) {
        return m;
      }
    }
    return null;
  }
}
