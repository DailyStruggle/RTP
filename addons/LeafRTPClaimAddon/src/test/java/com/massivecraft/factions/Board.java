package com.massivecraft.factions;

import java.util.function.Function;

/**
 * Test stand-in of the SaberFactions 4.1 {@code Board}: static {@code getInstance()} and
 * {@code getFactionAt(FLocation)}. The real class initialises the plugin's board storage.
 */
public class Board {
  private static Board instance;
  private final Function<FLocation, Faction> lookup;

  public Board(Function<FLocation, Faction> lookup) {
    this.lookup = lookup;
  }

  public static Board getInstance() {
    return instance;
  }

  public static void setInstance(Board value) {
    instance = value;
  }

  public Faction getFactionAt(FLocation location) {
    return lookup.apply(location);
  }
}
