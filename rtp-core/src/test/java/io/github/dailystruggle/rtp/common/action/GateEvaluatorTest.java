package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionGateContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GateEvaluatorTest {

  @Test
  @DisplayName("Empty or null gate configs evaluate to true (ungated)")
  void testUngated() {
    ActionGateContext ctx = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 0, true, 0.0);

    assertTrue(GateEvaluator.evaluate(null, ctx, null));
    assertTrue(GateEvaluator.evaluate(Map.of(), ctx, null));
  }

  @Test
  @DisplayName("Scoreboard gate evaluates violations count correctly")
  void testScoreboardGate() {
    ActionGateContext ctxLow = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 1, false, 25.0);
    ActionGateContext ctxHigh = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 3, false, 25.0);

    Map<String, Object> gateConfig = Map.of(
        "scoreboard", Map.of("objective", "rtp_violations", "matches", "< 2"));

    assertTrue(GateEvaluator.evaluate(gateConfig, ctxLow, null));
    assertFalse(GateEvaluator.evaluate(gateConfig, ctxHigh, null));
  }

  @Test
  @DisplayName("Spatial gate validates withinBoundary and distance")
  void testSpatialGate() {
    ActionGateContext inBounds = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 0, true, 16.0);
    ActionGateContext outOfBoundsFar = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 1, false, 10000.0);

    Map<String, Object> gateConfig = Map.of(
        "spatial", Map.of("withinBoundary", true, "distance", "<= 32"));

    assertTrue(GateEvaluator.evaluate(gateConfig, inBounds, null));
    assertFalse(GateEvaluator.evaluate(gateConfig, outOfBoundsFar, null));
  }

  @Test
  @DisplayName("Time gate checks elapsed and remaining thresholds")
  void testTimeGate() {
    ActionGateContext early = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 15L, 285L, 0, true, 0.0);
    ActionGateContext late = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 45L, 5L, 0, true, 0.0);

    Map<String, Object> elapsedGate = Map.of(
        "time", Map.of("elapsed", ">= 30s"));
    assertFalse(GateEvaluator.evaluate(elapsedGate, early, null));
    assertTrue(GateEvaluator.evaluate(elapsedGate, late, null));

    Map<String, Object> remainingGate = Map.of(
        "time", Map.of("remaining", "<= 10s"));
    assertFalse(GateEvaluator.evaluate(remainingGate, early, null));
    assertTrue(GateEvaluator.evaluate(remainingGate, late, null));
  }

  @Test
  @DisplayName("External predicate gate invokes registered callback")
  void testExternalPredicateGate() {
    ActionGateContext ctx = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 0, true, 0.0);

    Map<String, Object> gateConfig = Map.of("predicate", "my_custom_check");
    Map<String, java.util.function.Predicate<ActionGateContext>> registry = new HashMap<>();

    // Not registered yet -> fails closed
    assertFalse(GateEvaluator.evaluate(gateConfig, ctx, registry));

    // Register returning true
    registry.put("my_custom_check", c -> true);
    assertTrue(GateEvaluator.evaluate(gateConfig, ctx, registry));

    // Register returning false
    registry.put("my_custom_check", c -> false);
    assertFalse(GateEvaluator.evaluate(gateConfig, ctx, registry));
  }

  @Test
  @DisplayName("Unrecognized gate type fails closed (ADR-093)")
  void testUnrecognizedGateFailsClosed() {
    ActionGateContext ctx = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 0, true, 0.0);

    Map<String, Object> unknownGate = Map.of("unknown_future_gate", Map.of("key", "val"));
    assertFalse(GateEvaluator.evaluate(unknownGate, ctx, null));
  }
}
