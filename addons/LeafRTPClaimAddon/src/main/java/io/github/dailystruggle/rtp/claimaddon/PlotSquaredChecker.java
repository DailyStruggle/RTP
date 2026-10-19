package io.github.dailystruggle.rtp.claimaddon;

import java.lang.reflect.Method;

/**
 * Checker for PlotSquared plots and plot roads.
 *
 * <p>PlotSquared 6/7 ({@code com.plotsquared.core}) maps a block to its plot through static
 * {@code com.plotsquared.core.location.Location.at(String world, int x, int y, int z)}; that
 * location answers {@code isPlotRoad()} and {@code getPlot()} ({@code null} on roads and outside
 * plot areas). Inside a plot area an unclaimed plot is still a {@code Plot}, so ownership is
 * {@code Plot#hasOwner()}; {@code isBasePlot()} only describes merge structure. Several 4-argument
 * {@code at} overloads take a {@code String} first, so the method is resolved by exact signature.
 * Resolved reflectively so RTP carries no compile-time dependency on PlotSquared.
 *
 * <p>Owned plots and plot roads are rejected; unowned plots and land outside plot areas are not.
 * Fails open (returns false) when PlotSquared is not enabled. Fails closed (returns true, per
 * REQ-RTP-S-003) when it is enabled but this API chain cannot be resolved or the lookup throws.
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
      Class<?> pLocClass = Class.forName("com.plotsquared.core.location.Location");
      Method at = pLocClass.getMethod("at", String.class, int.class, int.class, int.class);
      Object pLoc =
          at.invoke(
              null,
              location.getWorld().getName(),
              location.getBlockX(),
              location.getBlockY(),
              location.getBlockZ());
      if (pLoc == null) throw new IllegalStateException("Location.at(...) is null");
      if (Boolean.TRUE.equals(pLocClass.getMethod("isPlotRoad").invoke(pLoc))) return true;
      Method getPlot = pLocClass.getMethod("getPlot");
      Object plot = getPlot.invoke(pLoc);
      if (plot == null) return false;
      return Boolean.TRUE.equals(getPlot.getReturnType().getMethod("hasOwner").invoke(plot));
    } catch (Throwable t) {
      return ClaimCheckFailure.handle(PLUGIN_NAME, t);
    }
  }
}
