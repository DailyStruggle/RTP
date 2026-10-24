package io.github.dailystruggle.rtp.claimaddon;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.plugin.ServicesManager;
import pl.minecodes.plots.api.plot.PlotServiceApi;

/**
 * Checker for mineCodes minePlots plots, compiled against {@code pl.minecodes.plots:plugin-api}.
 *
 * <p>{@link PlotServiceApi} comes from the Bukkit {@link ServicesManager};
 * {@code getPlot(Location)} returns the plot at a location or {@code null}.
 *
 * <p>minePlots absent or disabled: not claimed. Enabled but the service is not registered: the
 * location is rejected (REQ-RTP-S-003), warned once. Other failures follow
 * {@link ClaimCheckFailure}: the location is rejected, with an incompatible API logged once at
 * SEVERE and per-call errors throttled.
 */
public class MinePlotsChecker {
  private MinePlotsChecker() {}

  /** Bukkit plugin name from minePlots' {@code plugin.yml} (case-sensitive lookup). */
  static final String PLUGIN_NAME = "minePlots";

  private static volatile boolean warnedMissingService = false;

  /**
   * Check if an RTP coordinate is within a minePlots plot.
   *
   * @param location the coordinate to check
   * @return true if in a plot or the lookup failed while minePlots is enabled; false otherwise
   */
  public static boolean isInClaim(RTPCoords location) {
    if (location == null) return false;
    Location loc = ClaimLocationResolver.toLocation(location);
    if (loc == null) return false;
    return isInClaim(loc);
  }

  /**
   * Check if a Bukkit location is within a minePlots plot.
   *
   * @param location the location to check
   * @return true if in a plot or the lookup failed while minePlots is enabled; false otherwise
   */
  public static boolean isInClaim(Location location) {
    if (location == null || location.getWorld() == null) return false;
    if (!ClaimPlugins.isEnabled(PLUGIN_NAME)) return false;

    try {
      PlotServiceApi service =
          ClaimPlugins.server()
              .map(Server::getServicesManager)
              .map(servicesManager -> servicesManager.load(PlotServiceApi.class))
              .orElse(null);
      if (service == null) {
        if (!warnedMissingService) {
          warnedMissingService = true;
          RTP.log(
              Level.WARNING,
              "[RTP] minePlots is enabled but PlotServiceApi is not registered in the"
                  + " ServicesManager; rejecting destinations per REQ-RTP-S-003 until it is.");
        }
        return true;
      }
      warnedMissingService = false;
      return service.getPlot(location) != null;
    } catch (Throwable t) {
      return ClaimCheckFailure.handle(PLUGIN_NAME, t);
    }
  }
}
