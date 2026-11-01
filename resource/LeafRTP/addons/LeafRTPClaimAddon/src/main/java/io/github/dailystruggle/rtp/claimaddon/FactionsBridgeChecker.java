package io.github.dailystruggle.rtp.claimaddon;

import java.lang.reflect.Method;

/**
 * Checker for Factions territory via the FactionsBridge abstraction layer.
 *
 * <p>FactionsBridge ({@code cc.javajobs.factionsbridge}) is a standalone plugin that bridges many
 * Factions forks (FactionsUUID, SaberFactions, FactionsX, KingdomsX, etc.) behind a single API:
 * static {@code FactionsBridge.getFactionsAPI()} returns a {@code FactionsAPI} whose
 * {@code getFactionAt(org.bukkit.Location)} returns the {@code Faction} there, answering
 * {@code isWilderness()}. Methods are resolved on the declared API interfaces, not on the bridge's
 * implementation classes. We resolve its API reflectively so RTP does not take a compile-time
 * dependency on FactionsBridge (it ships no resolvable API artifact and its set of supported forks
 * changes over time).
 *
 * <p>Fails open (returns false) when FactionsBridge is not enabled. Fails closed (returns true,
 * per REQ-RTP-S-003) when it is enabled but this API chain cannot be resolved or the lookup throws.
 */
public class FactionsBridgeChecker {
  private FactionsBridgeChecker() {}

  /** Bukkit plugin name from FactionsBridge's {@code plugin.yml} (case-sensitive lookup). */
  static final String PLUGIN_NAME = "FactionsBridge";

  /**
   * Check if a location is within a Factions claim (via FactionsBridge).
   *
   * @param location the location to check
   * @return true if in a claim, or if the lookup fails while FactionsBridge is enabled
   */
  public static Boolean isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords location) {
    if (location == null) return false;
    org.bukkit.Location loc = ClaimLocationResolver.toLocation(location);
    if (loc == null) return false;
    return isInClaim(loc);
  }

  /**
   * Check if a location is within a Factions claim (via FactionsBridge).
   *
   * @param location the location to check
   * @return true if in a claim, or if the lookup fails while FactionsBridge is enabled
   */
  public static Boolean isInClaim(org.bukkit.Location location) {
    if (location == null || location.getWorld() == null) return false;
    if (!ClaimPlugins.isEnabled(PLUGIN_NAME)) return false;
    try {
      Class<?> bridgeClass = Class.forName("cc.javajobs.factionsbridge.FactionsBridge");
      Method getFactionsApi = bridgeClass.getMethod("getFactionsAPI");
      Object api = getFactionsApi.invoke(null);
      if (api == null) throw new IllegalStateException("FactionsBridge.getFactionsAPI() is null");
      Method getFactionAt =
          getFactionsApi.getReturnType().getMethod("getFactionAt", org.bukkit.Location.class);
      Object faction = getFactionAt.invoke(api, location);
      // getFactionAt is @Nullable in the FactionsBridge API: no faction owns this land.
      if (faction == null) return false;
      Object wilderness = getFactionAt.getReturnType().getMethod("isWilderness").invoke(faction);
      // "in a claim" means the land belongs to a real (non-wilderness) faction.
      return !Boolean.TRUE.equals(wilderness);
    } catch (Throwable t) {
      return ClaimCheckFailure.handle(PLUGIN_NAME, t);
    }
  }
}
