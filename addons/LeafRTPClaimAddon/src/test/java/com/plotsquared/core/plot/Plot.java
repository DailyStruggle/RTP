package com.plotsquared.core.plot;

import java.util.UUID;

/**
 * Test stub of PlotSquared 7 {@code Plot}. As in the real class, an unclaimed plot is still a
 * {@code Plot}, {@code isBasePlot()} describes merge structure (true for every unmerged plot) and
 * ownership is {@code hasOwner()}. There is no {@code isClaimed()}.
 */
public class Plot {
  private final UUID owner;

  public Plot(UUID owner) {
    this.owner = owner;
  }

  public boolean hasOwner() {
    return owner != null;
  }

  public UUID getOwner() {
    return owner;
  }

  public UUID getOwnerAbs() {
    return owner;
  }

  public boolean isBasePlot() {
    return true;
  }
}
