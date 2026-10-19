package io.github.dailystruggle.rtp.claimaddon;

import java.lang.reflect.Method;

/**
 * Checker for SaberFactions territory.
 *
 * <p>SaberFactions is a maintained FactionsUUID fork that registers under the {@code Factions}
 * plugin name and exposes the modern {@code com.massivecraft.factions} API: static
 * {@code Board.getInstance()}, {@code Board#getFactionAt(FLocation)} with
 * {@code new FLocation(org.bukkit.Location)}, and {@code Faction#isWilderness()}. FactionsUUID has
 * the same shape. Methods are resolved on the declared API types, not on implementation classes.
 * We resolve that API reflectively to avoid a compile-time clash with the legacy
 * {@code com.massivecraft:Factions} artifact (both ship the {@code com.massivecraft.factions}
 * package with divergent method surfaces).
 *
 * <p>Fails open (returns false) when Factions is not enabled. Fails closed (returns true, per
 * REQ-RTP-S-003) when it is enabled but this API chain cannot be resolved or the lookup throws.
 */
public class SaberFactionsChecker {
  private SaberFactionsChecker() {}

  /** Bukkit plugin name from SaberFactions' {@code plugin.yml} (case-sensitive lookup). */
  static final String PLUGIN_NAME = "Factions";

  private static final String INTEGRATION = "SaberFactions";

  /**
   * Check if a location is within a SaberFactions claim.
   *
   * @param location the location to check
   * @return true if in a claim, or if the lookup fails while Factions is enabled
   */
  public static Boolean isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords location) {
    if (location == null) return false;
    org.bukkit.Location loc = ClaimLocationResolver.toLocation(location);
    if (loc == null) return false;
    return isInClaim(loc);
  }

  /**
   * Check if a location is within a SaberFactions claim.
   *
   * @param location the location to check
   * @return true if in a claim, or if the lookup fails while Factions is enabled
   */
  public static Boolean isInClaim(org.bukkit.Location location) {
    if (location == null || location.getWorld() == null) return false;
    if (!ClaimPlugins.isEnabled(PLUGIN_NAME)) return false;
    try {
      Class<?> boardClass = Class.forName("com.massivecraft.factions.Board");
      Class<?> flocationClass = Class.forName("com.massivecraft.factions.FLocation");
      Object board = boardClass.getMethod("getInstance").invoke(null);
      if (board == null) throw new IllegalStateException("Board.getInstance() is null");
      Object flocation =
          flocationClass.getConstructor(org.bukkit.Location.class).newInstance(location);
      Method getFactionAt = boardClass.getMethod("getFactionAt", flocationClass);
      Object faction = getFactionAt.invoke(board, flocation);
      // Unclaimed land maps to the wilderness faction; null means no faction owns it.
      if (faction == null) return false;
      Object wilderness = getFactionAt.getReturnType().getMethod("isWilderness").invoke(faction);
      // "in a claim" means the land belongs to a real (non-wilderness) faction.
      return !Boolean.TRUE.equals(wilderness);
    } catch (Throwable t) {
      return ClaimCheckFailure.handle(INTEGRATION, t);
    }
  }
}
