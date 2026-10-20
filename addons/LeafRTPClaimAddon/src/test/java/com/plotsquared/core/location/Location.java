package com.plotsquared.core.location;

import com.plotsquared.core.plot.Plot;

/**
 * Test stand-in of the PlotSquared 7 {@code Location}: static {@code at(String, int, int, int)},
 * {@code isPlotRoad()} and {@code getPlot()} (null on roads and outside plot areas).
 */
public class Location {
  /** Resolves a block position to its plot-area cell. */
  public interface Resolver {
    Location at(String world, int x, int y, int z);
  }

  private static final Resolver OUTSIDE = (w, x, y, z) -> new Location(false, null);
  private static Resolver resolver = OUTSIDE;

  private final boolean road;
  private final Plot plot;

  public Location(boolean road, Plot plot) {
    this.road = road;
    this.plot = plot;
  }

  public static void setResolver(Resolver value) {
    resolver = value != null ? value : OUTSIDE;
  }

  public static Location at(String world, int x, int y, int z) {
    return resolver.at(world, x, y, z);
  }

  public boolean isPlotRoad() {
    return road;
  }

  public Plot getPlot() {
    return plot;
  }
}
