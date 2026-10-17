package io.github.dailystruggle.rtp.claimaddon;

import java.lang.reflect.Method;

/**
 * Checker for PlotSquared plots and plot roads.
 *
 * <p>PlotSquared ({@code com.plotsquared}) manages designated plot worlds where player builds
 * are confined to claimed plots separated by roads. We resolve PlotSquared's location and plot
 * models reflectively so RTP carries no compile-time dependency on PlotSquared.
 *
 * <p>When inside a plot world, any location that is claimed ({@code plot.isClaimed() || plot.isBasePlot()})
 * or is on a plot road ({@code pLoc.isPlotRoad()}) is treated as non-RTP-safe to prevent landing
 * players on claimed property or interfering with plot bounds.
 *
 * <p>When PlotSquared is absent or the location is outside any PlotArea, the verifier safely returns false.
 */
public class PlotSquaredChecker {
  private PlotSquaredChecker() {}

  private static boolean exists = true;
  private static Boolean available = null;

  private static boolean isAvailable() {
    if (!exists) return false;
    if (available != null) return available;
    try {
      Class.forName("com.plotsquared.core.location.Location");
      available = true;
    } catch (Throwable t) {
      available = false;
    }
    return available;
  }

  /**
   * Check if an RTP coordinate is within a claimed plot or plot road.
   *
   * @param location the coordinate to check
   * @return true if in a claimed plot or road, false otherwise
   */
  public static Boolean isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords location) {
    if (!exists || location == null || !isAvailable()) return false;
    org.bukkit.Location loc = ClaimLocationResolver.toLocation(location);
    if (loc == null) return false;
    return isInClaim(loc);
  }

  /**
   * Check if a Bukkit location is within a claimed plot or plot road.
   *
   * @param location the location to check
   * @return true if in a claimed plot or road, false otherwise
   */
  public static Boolean isInClaim(org.bukkit.Location location) {
    if (!exists || location == null || location.getWorld() == null || !isAvailable()) return false;
    try {
      Class<?> pLocClass = Class.forName("com.plotsquared.core.location.Location");
      Method atMethod = findAtMethod(pLocClass);
      if (atMethod == null) return false;

      Object pLoc;
      if (atMethod.getParameterCount() == 4) {
        pLoc = atMethod.invoke(null, location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
      } else if (atMethod.getParameterCount() == 3) {
        pLoc = atMethod.invoke(null, location.getWorld().getName(), location.getBlockX(), location.getBlockZ());
      } else {
        return false;
      }
      if (pLoc == null) return false;

      // Check if location is a road
      Method isPlotRoad = findMethod(pLoc.getClass(), "isPlotRoad");
      if (isPlotRoad != null) {
        Object isRoad = isPlotRoad.invoke(pLoc);
        if (Boolean.TRUE.equals(isRoad)) return true;
      }

      // Check if location has an active plot
      Method getPlot = findMethod(pLoc.getClass(), "getPlot");
      if (getPlot != null) {
        Object plot = getPlot.invoke(pLoc);
        if (plot != null) {
          Method isClaimed = findMethod(plot.getClass(), "isClaimed");
          if (isClaimed != null && Boolean.TRUE.equals(isClaimed.invoke(plot))) {
            return true;
          }
          Method isBasePlot = findMethod(plot.getClass(), "isBasePlot");
          if (isBasePlot != null && Boolean.TRUE.equals(isBasePlot.invoke(plot))) {
            return true;
          }
          // If a plot object exists and is not unclaimed/empty
          Method hasOwner = findMethod(plot.getClass(), "hasOwner");
          if (hasOwner != null && Boolean.TRUE.equals(hasOwner.invoke(plot))) {
            return true;
          }
        }
      }

      return false;
    } catch (Throwable t) {
      return ClaimCheckFailure.handle("PlotSquared", t, () -> exists = false);
    }
  }

  private static Method findAtMethod(Class<?> pLocClass) {
    for (Method m : pLocClass.getMethods()) {
      if ("at".equals(m.getName())) {
        Class<?>[] pts = m.getParameterTypes();
        if (pts.length == 4 && pts[0] == String.class) {
          return m;
        }
        if (pts.length == 3 && pts[0] == String.class) {
          return m;
        }
      }
    }
    return null;
  }

  private static Method findMethod(Class<?> type, String name) {
    for (Method m : type.getMethods()) {
      if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
    }
    return null;
  }
}
