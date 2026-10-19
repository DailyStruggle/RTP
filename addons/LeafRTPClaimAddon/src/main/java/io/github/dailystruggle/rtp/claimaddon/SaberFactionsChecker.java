package io.github.dailystruggle.rtp.claimaddon;

import com.massivecraft.factions.Board;
import com.massivecraft.factions.FLocation;
import com.massivecraft.factions.Faction;

/**
 * Checker for SaberFactions territory, compiled against {@code com.github.SaberLLC:Saber-Factions}.
 *
 * <p>SaberFactions is a FactionsUUID fork registered under the {@code Factions} plugin name:
 * {@link Board#getInstance()}, {@code getFactionAt(new FLocation(Location))} and
 * {@link Faction#isWilderness()}. FactionsUUID has the same shape. Only the plugin's own packages
 * of the shaded jar are on the compile classpath (see {@code build.gradle}). API types are touched
 * only in {@link Api}, after the enabled check (ADR-118).
 *
 * <p>Fails open (returns false) when Factions is not enabled. Fails closed (returns true, per
 * REQ-RTP-S-003) through {@link ClaimCheckFailure} when it is enabled but the API is incompatible
 * or the lookup throws.
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
      return Api.isInClaim(location);
    } catch (Throwable t) {
      return ClaimCheckFailure.handle(INTEGRATION, t);
    }
  }

  /** Loaded on first call, so Factions classes link only while the plugin is enabled. */
  private static final class Api {
    private Api() {}

    static boolean isInClaim(org.bukkit.Location location) {
      Board board = Board.getInstance();
      if (board == null) throw new IllegalStateException("Board.getInstance() is null");
      Faction faction = board.getFactionAt(new FLocation(location));
      // Unclaimed land maps to the wilderness faction; null means no faction owns it.
      // "in a claim" means the land belongs to a real (non-wilderness) faction.
      return faction != null && !faction.isWilderness();
    }
  }
}
