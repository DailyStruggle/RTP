package io.github.dailystruggle.rtp.common.action;

import java.util.Map;

/** Records evaluated gate outcomes and substitutes command-gate dispatch during simulation. */
interface GateTrace {
  record Outcome(String gate, boolean passed, String reason) {}

  void onOutcome(Map<String, Object> gateConfig, boolean passed, String reason);

  default boolean simulateCommand(String command) {
    return true;
  }
}
