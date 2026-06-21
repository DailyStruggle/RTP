package io.github.dailystruggle.rtp.claimaddon;

import io.github.dailystruggle.rtp.common.RTP;
import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Checker for Factions territory via the FactionsBridge abstraction layer.
 *
 * <p>FactionsBridge ({@code cc.javajobs.factionsbridge}) is a standalone plugin that bridges many
 * Factions forks (FactionsUUID, SaberFactions, FactionsX, KingdomsX, etc.) behind a single API. We
 * resolve its API reflectively so RTP does not take a compile-time dependency on FactionsBridge (it
 * ships no resolvable API artifact and its set of supported forks changes over time). When
 * FactionsBridge is absent the reflective lookup fails once, the integration disables itself for the
 * session, and the verifier becomes a no-op.
 */
public class FactionsBridgeChecker {
  private static boolean exists = true;

  /**
   * Check if a location is within a Factions claim (via FactionsBridge).
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
   * Check if a location is within a Factions claim (via FactionsBridge).
   *
   * @param location the location to check
   * @return true if in a claim, false otherwise
   */
  public static Boolean isInClaim(org.bukkit.Location location) {
    if (!exists) return false;
    try {
      Class<?> bridgeClass = Class.forName("cc.javajobs.factionsbridge.FactionsBridge");
      Object api = bridgeClass.getMethod("getFactionsAPI").invoke(null);
      if (api == null) return false;
      Method getFactionAt = api.getClass().getMethod("getFactionAt", org.bukkit.Location.class);
      Object faction = getFactionAt.invoke(api, location);
      if (faction == null) return false;
      Object wilderness = faction.getClass().getMethod("isWilderness").invoke(faction);
      // "in a claim" means the land belongs to a real (non-wilderness) faction.
      return !Boolean.TRUE.equals(wilderness);
    } catch (Throwable t) {
      exists = false;
      RTP.log(
          Level.SEVERE,
          "[RTP] Critical architectural incompatibility detected. Disabling FactionsBridge integration for this session to prevent server instability.",
          t);
    }
    return false;
  }
}
