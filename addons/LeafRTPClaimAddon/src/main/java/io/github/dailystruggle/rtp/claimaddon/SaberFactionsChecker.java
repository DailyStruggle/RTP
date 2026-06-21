package io.github.dailystruggle.rtp.claimaddon;

import io.github.dailystruggle.rtp.common.RTP;
import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Checker for SaberFactions territory.
 *
 * <p>SaberFactions is a maintained FactionsUUID fork that registers under the {@code Factions}
 * plugin name and exposes the modern {@code com.massivecraft.factions} API
 * ({@code Board.getInstance().getFactionAt(FLocation)} returning a {@code Faction} with
 * {@code isWilderness()}). We resolve that API reflectively to avoid a compile-time clash with the
 * legacy {@code com.massivecraft:Factions} artifact (both ship the {@code com.massivecraft.factions}
 * package with divergent method surfaces). When SaberFactions is absent the reflective lookup fails
 * once, the integration disables itself for the session, and the verifier becomes a no-op.
 */
public class SaberFactionsChecker {
  private static boolean exists = true;

  /**
   * Check if a location is within a SaberFactions claim.
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
   * Check if a location is within a SaberFactions claim.
   *
   * @param location the location to check
   * @return true if in a claim, false otherwise
   */
  public static Boolean isInClaim(org.bukkit.Location location) {
    if (!exists) return false;
    try {
      Class<?> boardClass = Class.forName("com.massivecraft.factions.Board");
      Class<?> flocationClass = Class.forName("com.massivecraft.factions.FLocation");
      Object board = boardClass.getMethod("getInstance").invoke(null);
      Object flocation =
          flocationClass.getConstructor(org.bukkit.Location.class).newInstance(location);
      Method getFactionAt = boardClass.getMethod("getFactionAt", flocationClass);
      Object faction = getFactionAt.invoke(board, flocation);
      if (faction == null) return false;
      Object wilderness = faction.getClass().getMethod("isWilderness").invoke(faction);
      // "in a claim" means the land belongs to a real (non-wilderness) faction.
      return !Boolean.TRUE.equals(wilderness);
    } catch (Throwable t) {
      exists = false;
      RTP.log(
          Level.SEVERE,
          "[RTP] Critical architectural incompatibility detected. Disabling SaberFactions integration for this session to prevent server instability.",
          t);
    }
    return false;
  }
}
