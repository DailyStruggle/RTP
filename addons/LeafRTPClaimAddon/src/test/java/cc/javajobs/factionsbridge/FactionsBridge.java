package cc.javajobs.factionsbridge;

import cc.javajobs.factionsbridge.bridge.infrastructure.struct.FactionsAPI;

/** Test stub of the FactionsBridge main class: static {@code getFactionsAPI()}. */
public class FactionsBridge {
  private static FactionsAPI factionsAPI;

  public static FactionsAPI getFactionsAPI() {
    return factionsAPI;
  }

  public static void setFactionsAPI(FactionsAPI value) {
    factionsAPI = value;
  }
}
