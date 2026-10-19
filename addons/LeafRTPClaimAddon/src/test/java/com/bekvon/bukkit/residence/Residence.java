package com.bekvon.bukkit.residence;

import com.bekvon.bukkit.residence.protection.ResidenceManager;

/**
 * Test stub of the Residence 5.x/6.x main class: static {@code getInstance()} and an instance
 * {@code getResidenceManager()}. The static setters are test hooks.
 */
public class Residence {
  private static Residence instance;
  private static ResidenceManager residenceManager;

  public static void setInstance(Residence inst) {
    instance = inst;
  }

  public static Residence getInstance() {
    return instance;
  }

  public static void setResidenceManager(ResidenceManager manager) {
    residenceManager = manager;
  }

  public ResidenceManager getResidenceManager() {
    return residenceManager;
  }
}
