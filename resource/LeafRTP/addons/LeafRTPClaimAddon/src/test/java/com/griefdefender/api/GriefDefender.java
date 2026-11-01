package com.griefdefender.api;

/** Test stand-in of the GriefDefender 2.1 API entry point: static {@code getCore()}. */
public class GriefDefender {
  private static Core core;

  public static Core getCore() {
    return core;
  }

  public static void setCore(Core value) {
    core = value;
  }
}
