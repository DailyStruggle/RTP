package com.bekvon.bukkit.residence;

public class Residence {
  private static Object instance;
  private static Object residenceManager;

  public static void setInstance(Object inst) {
    instance = inst;
  }

  public static Object getInstance() {
    return instance;
  }

  public static void setResidenceManager(Object manager) {
    residenceManager = manager;
  }

  public static Object getResidenceManager() {
    return residenceManager;
  }
}
