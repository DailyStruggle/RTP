package io.github.dailystruggle.rtp.claimaddon;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Checker for MinePlots plots (plot and land claim management).
 *
 * <p>MinePlots manages plots and claims in dedicated worlds. We resolve the plugin singleton
 * and plot manager reflectively so RTP carries no compile-time dependency on MinePlots.
 *
 * <p>When MinePlots is absent or disabled, the checker safely fails open (returns false).
 * When active, any reflection lookup failure or internal error fails closed (returns true,
 * treating the coordinate as claimed/protected per REQ-RTP-S-003).
 */
public class MinePlotsChecker {
  private MinePlotsChecker() {}

  private static boolean exists = true;
  private static Boolean available = null;

  private static final String[] CANDIDATE_CLASSES = {
    "pl.minecodes.plots.api.PlotServiceApi",
    "pl.themolka.mineplots.MinePlots",
    "net.mineplots.MinePlots",
    "me.mineplots.MinePlots",
    "com.mineplots.MinePlots"
  };

  private static boolean isAvailable() {
    if (!exists) return false;
    if (available != null) return available;
    for (String cName : CANDIDATE_CLASSES) {
      try {
        Class.forName(cName);
        available = true;
        return true;
      } catch (ClassNotFoundException ignored) {
        // Ignored: probe next candidate class
      }
    }
    available = false;
    return false;
  }

  /**
   * Check if an RTP coordinate is within a MinePlots plot or claim.
   *
   * @param location the coordinate to check
   * @return true if in a claim or lookup fails while plugin active, false otherwise
   */
  public static Boolean isInClaim(RTPCoords location) {
    if (!exists || location == null || !isAvailable()) return false;
    org.bukkit.Location loc = ClaimLocationResolver.toLocation(location);
    if (loc == null) return false;
    return isInClaim(loc);
  }

  /**
   * Check if a Bukkit location is within a MinePlots plot or claim.
   *
   * @param location the location to check
   * @return true if in a claim or lookup fails while plugin active, false otherwise
   */
  public static Boolean isInClaim(org.bukkit.Location location) {
    if (!exists || location == null || location.getWorld() == null || !isAvailable()) return false;
    if (org.bukkit.Bukkit.getServer() == null) return false;
    try {
      Class<?> mainClass = null;
      for (String cName : CANDIDATE_CLASSES) {
        try {
          mainClass = Class.forName(cName);
          break;
        } catch (ClassNotFoundException ignored) {
          // Ignored: continue searching candidate main classes
        }
      }

      if (mainClass == null) return false;

      Object serviceOrPlugin = null;
      // First, try Bukkit ServicesManager for PlotServiceApi
      try {
        Class<?> serviceClass = Class.forName("pl.minecodes.plots.api.PlotServiceApi");
        Method loadMethod = org.bukkit.plugin.ServicesManager.class.getMethod("load", Class.class);
        serviceOrPlugin = loadMethod.invoke(org.bukkit.Bukkit.getServicesManager(), serviceClass);
      } catch (Throwable ignored) {
        // Ignored: ServicesManager lookup optional
      }

      if (serviceOrPlugin == null) {
        try {
          Method getInstance = mainClass.getMethod("getInstance");
          serviceOrPlugin = getInstance.invoke(null);
        } catch (NoSuchMethodException ignored) {
          try {
            Method getPlugin = mainClass.getMethod("getPlugin", Class.class);
            serviceOrPlugin = getPlugin.invoke(null, mainClass);
          } catch (NoSuchMethodException ignored2) {
            org.bukkit.plugin.Plugin p = org.bukkit.Bukkit.getPluginManager().getPlugin("MinePlots");
            if (p != null && mainClass.isInstance(p)) {
              serviceOrPlugin = p;
            }
          }
        }
      }

      if (serviceOrPlugin == null) return false;

      // Probe plot manager / plot service from plugin instance if needed
      Object plotManager = null;
      for (Method m : serviceOrPlugin.getClass().getMethods()) {
        if ((m.getName().equals("getPlotManager")
                || m.getName().equals("getPlots")
                || m.getName().equals("getPlotService"))
            && m.getParameterCount() == 0) {
          plotManager = m.invoke(serviceOrPlugin);
          break;
        }
      }

      Object target = (plotManager != null) ? plotManager : serviceOrPlugin;

      // 1. Try getPlot(Location)
      Method getPlotLoc = findMethod(target.getClass(), "getPlot", org.bukkit.Location.class);
      if (getPlotLoc != null) {
        Object plot = getPlotLoc.invoke(target, location);
        if (plot != null) {
          // If plot object exists, verify if it is claimed or whether plot presence implies protection
          Method isClaimed = findZeroArgMethod(plot.getClass(), "isClaimed");
          if (isClaimed != null) {
            Object res = isClaimed.invoke(plot);
            if (res instanceof Boolean b) return b;
          }
          return true;
        }
      }

      // 2. Try getPlotAt(Location)
      Method getPlotAtLoc = findMethod(target.getClass(), "getPlotAt", org.bukkit.Location.class);
      if (getPlotAtLoc != null) {
        Object plot = getPlotAtLoc.invoke(target, location);
        if (plot != null) {
          Method isClaimed = findZeroArgMethod(plot.getClass(), "isClaimed");
          if (isClaimed != null) {
            Object res = isClaimed.invoke(plot);
            if (res instanceof Boolean b) return b;
          }
          return true;
        }
      }

      // 3. Fallback: isPlot(Location) or isClaimed(Location)
      for (Method m : target.getClass().getMethods()) {
        if ((m.getName().equals("isPlot") || m.getName().equals("isClaimed"))
            && m.getParameterCount() == 1
            && m.getParameterTypes()[0].isAssignableFrom(org.bukkit.Location.class)) {
          Object res = m.invoke(target, location);
          if (res instanceof Boolean b) return b;
        }
      }

      return false;
    } catch (Throwable t) {
      RTP.log(
          Level.WARNING,
          "[RTP] Error querying MinePlots plot status at "
              + location.getWorld().getName() + " (" + location.getBlockX() + ", " + location.getBlockZ()
              + "); failing closed per REQ-RTP-S-003.",
          t);
      return true; // Fail-closed
    }
  }

  private static Method findMethod(Class<?> type, String name, Class<?> paramType) {
    for (Method m : type.getMethods()) {
      if (m.getName().equals(name)
          && m.getParameterCount() == 1
          && m.getParameterTypes()[0].isAssignableFrom(paramType)) {
        return m;
      }
    }
    return null;
  }

  private static Method findZeroArgMethod(Class<?> type, String name) {
    for (Method m : type.getMethods()) {
      if (m.getName().equals(name) && m.getParameterCount() == 0) {
        return m;
      }
    }
    return null;
  }
}
