package com.bekvon.bukkit.residence;

import com.bekvon.bukkit.residence.protection.ResidenceManager;

/**
 * Test stand-in of the Residence 6.0 main class: static {@code getInstance()} and instance
 * {@code getResidenceManager()}. The real class extends {@code JavaPlugin} and needs CMILib.
 */
public class Residence {
  private static Residence instance;
  private final ResidenceManager residenceManager;

  public Residence(ResidenceManager residenceManager) {
    this.residenceManager = residenceManager;
  }

  public static Residence getInstance() {
    return instance;
  }

  public static void setInstance(Residence value) {
    instance = value;
  }

  public ResidenceManager getResidenceManager() {
    return residenceManager;
  }
}
