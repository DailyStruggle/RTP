package io.github.dailystruggle.rtp.claimaddon;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

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
  private static Boolean available = null;

  private static boolean isAvailable() {
    if (!exists) return false;
    if (available != null) return available;
    try {
      Class.forName("org.kingdoms.constants.land.location.SimpleChunkLocation");
      available = true;
    } catch (Throwable t) {
      available = false;
    }
    return available;
  }

  /**
   * Check if a location is within KingdomsX claimed land.
   *
   * @param location the location to check
   * @return true if in a claim, false otherwise
   */
  public static Boolean isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords location) {
    if (!exists || location == null || !isAvailable()) return false;
    org.bukkit.Location loc = ClaimLocationResolver.toLocation(location);
    if (loc == null) return false;
    return isInClaim(loc);
  }

  /**
   * Check if a location is within KingdomsX claimed land.
   *
   * @param location the location to check
   * @return true if in a claim, false otherwise
   */
  public static Boolean isInClaim(org.bukkit.Location location) {
    if (!exists || location == null || !isAvailable()) return false;
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
      return ClaimCheckFailure.handle("KingdomsX", t, () -> exists = false);
    }
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
