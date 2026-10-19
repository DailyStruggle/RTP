package io.github.dailystruggle.rtp.claimaddon;

import com.plotsquared.core.plot.Plot;

/**
 * Checker for PlotSquared plots and plot roads, compiled against
 * {@code com.intellectualsites.plotsquared:plotsquared-core}.
 *
 * <p>{@code com.plotsquared.core.location.Location.at(String world, int x, int y, int z)} answers
 * {@code isPlotRoad()} and {@code getPlot()} ({@code null} on roads and outside plot areas).
 * Inside a plot area an unclaimed plot is still a {@link Plot}, so ownership is
 * {@link Plot#hasOwner()}; {@code isBasePlot()} only describes merge structure. API types are
 * touched only in {@link Api}, after the enabled check (ADR-118).
 *
 * <p>Owned plots and plot roads are rejected; unowned plots and land outside plot areas are not.
 * Fails open (returns false) when PlotSquared is not enabled. Fails closed (returns true, per
 * REQ-RTP-S-003) through {@link ClaimCheckFailure} when it is enabled but the API is incompatible
 * or the lookup throws.
 */
public class PlotSquaredChecker {
  private PlotSquaredChecker() {}

  /** Bukkit plugin name from PlotSquared's {@code plugin.yml} (case-sensitive lookup). */
  static final String PLUGIN_NAME = "PlotSquared";

  /**
   * Check if an RTP coordinate is within an owned plot or on a plot road.
   *
   * @param location the coordinate to check
   * @return true if in an owned plot or on a road, or if the lookup fails while PlotSquared is
   *     enabled
   */
  public static Boolean isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords location) {
    if (location == null) return false;
    org.bukkit.Location loc = ClaimLocationResolver.toLocation(location);
    if (loc == null) return false;
    return isInClaim(loc);
  }

  /**
   * Check if a Bukkit location is within an owned plot or on a plot road.
   *
   * @param location the location to check
   * @return true if in an owned plot or on a road, or if the lookup fails while PlotSquared is
   *     enabled
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

  /** Loaded on first call, so PlotSquared classes link only while the plugin is enabled. */
  private static final class Api {
    private Api() {}

    static boolean isInClaim(org.bukkit.Location location) {
      com.plotsquared.core.location.Location pLoc =
          com.plotsquared.core.location.Location.at(
              location.getWorld().getName(),
              location.getBlockX(),
              location.getBlockY(),
              location.getBlockZ());
      if (pLoc == null) throw new IllegalStateException("Location.at(...) is null");
      if (pLoc.isPlotRoad()) return true;
      Plot plot = pLoc.getPlot();
      return plot != null && plot.hasOwner();
    }
  }
}
