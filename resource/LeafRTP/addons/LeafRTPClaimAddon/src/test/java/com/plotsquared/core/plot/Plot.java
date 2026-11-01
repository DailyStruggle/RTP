package com.plotsquared.core.plot;

/** Test stand-in of the PlotSquared {@code Plot}: an unclaimed plot is still a plot, without an owner. */
public class Plot {
  private final boolean owned;

  public Plot(boolean owned) {
    this.owned = owned;
  }

  public boolean hasOwner() {
    return owned;
  }
}
