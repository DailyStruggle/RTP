package com.massivecraft.factions;

/**
 * Test stub of FactionsUUID / SaberFactions {@code Board}: abstract, static {@code getInstance()},
 * abstract {@code getFactionAt(FLocation)}.
 */
public abstract class Board {
  private static Board instance;

  public static Board getInstance() {
    return instance;
  }

  public static void setInstance(Board value) {
    instance = value;
  }

  public abstract Faction getFactionAt(FLocation flocation);
}
