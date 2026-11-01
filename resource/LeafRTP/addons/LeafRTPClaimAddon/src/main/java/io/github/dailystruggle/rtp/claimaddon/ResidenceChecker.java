package io.github.dailystruggle.rtp.claimaddon;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.protection.ResidenceManager;

/**
 * Checker for Residence claims, compiled against {@code com.github.Zrips:Residence}.
 *
 * <p>{@code Residence.getInstance().getResidenceManager().getByLoc(Location)} returns the
 * {@code ClaimedResidence} at a location, or {@code null} when unclaimed. Older builds made
 * {@code getResidenceManager()} static; against them the call fails closed. API types are touched
 * only in {@link Api}, after the enabled check (ADR-118).
 *
 * <p>Fails open (returns false) when Residence is not enabled. Fails closed (returns true, per
 * REQ-RTP-S-003) through {@link ClaimCheckFailure} when it is enabled but the API is incompatible
 * or the lookup throws.
 */
public class ResidenceChecker {
  private ResidenceChecker() {}

  /** Bukkit plugin name from Residence's {@code plugin.yml} (case-sensitive lookup). */
  static final String PLUGIN_NAME = "Residence";

  /**
   * Check if a location is within a Residence claim.
   *
   * @param location the location to check
   * @return true if in a claim, or if the lookup fails while Residence is enabled
   */
  public static Boolean isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords location) {
    if (location == null) return false;
    org.bukkit.Location loc = ClaimLocationResolver.toLocation(location);
    if (loc == null) return false;
    return isInClaim(loc);
  }

  /**
   * Check if a location is within a Residence claim.
   *
   * @param location the location to check
   * @return true if in a claim, or if the lookup fails while Residence is enabled
   */
  public static Boolean isInClaim(org.bukkit.Location location) {
    if (location == null || location.getWorld() == null) return false;
    if (!ClaimPlugins.isEnabled(PLUGIN_NAME)) return false;
    try {
      return Api.isInClaim(location);
    } catch (Throwable t) {
      return ClaimCheckFailure.handle(PLUGIN_NAME, t);
    }
  }

  /** Loaded on first call, so Residence classes link only while the plugin is enabled. */
  private static final class Api {
    private Api() {}

    static boolean isInClaim(org.bukkit.Location location) {
      Residence residence = Residence.getInstance();
      if (residence == null) throw new IllegalStateException("Residence.getInstance() is null");
      ResidenceManager manager = residence.getResidenceManager();
      if (manager == null) throw new IllegalStateException("getResidenceManager() is null");
      // "in a claim" means a ClaimedResidence covers this location.
      return manager.getByLoc(location) != null;
    }
  }
}
