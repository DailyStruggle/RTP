package io.github.dailystruggle.rtp.common.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.bit.GateOutcomeCounter;
import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ActionGateContext;
import io.github.dailystruggle.rtp.api.action.ConfinementBoundary;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.common.bit.BitSeverityPolicy;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ADR-121 action runtime CBIT counters")
class ActionCbitCountersTest {
  private RTPServerAccessor savedAccessor;
  private RTPScheduler savedScheduler;

  @BeforeEach
  void setUp() {
    savedAccessor = RTPRuntime.serverAccessor();
    savedScheduler = RTPRuntime.scheduler();
    RTPRuntime.setServerAccessor(null);
    ActionCbitCounters.reset();
    BitSeverityPolicy.resetDefaults();
  }

  @AfterEach
  void tearDown() {
    ActionCbitCounters.reset();
    BitSeverityPolicy.resetDefaults();
    RTPRuntime.setServerAccessor(savedAccessor);
    RTPRuntime.setScheduler(savedScheduler);
  }

  @Test
  void missingExternalObjectiveReadIsCountedPerObjective() {
    UUID participant = UUID.randomUUID();
    RTPServerAccessor accessor = mock(RTPServerAccessor.class);
    when(accessor.getScoreboardScore(participant, "missing_objective")).thenReturn(null);
    RTPRuntime.setServerAccessor(accessor);
    ActionGateContext context = new ActionGateContext(
        UUID.randomUUID(), "objective_counter_test", participant, 0L, 10L, 0, true, 0.0);
    Map<String, Object> gate = Map.of("scoreboard", Map.of(
        "objective", "missing_objective", "matches", ">= 0"));

    assertTrue(GateEvaluator.evaluate(gate, context, null));

    assertEquals(1L, ActionCbitCounters.snapshot().missingObjectiveReads().get("missing_objective"));
    assertTrue(hasCode(ActionCbitCounters.OBJECTIVE_MISSING.key()));
  }

  @Test
  void gateOutcomeFindingsAppearOnlyAfterConfiguredSampleCount() {
    assertEquals(200, ActionCbitCounters.minimumGateSamples());
    ActionCbitCounters.setMinimumGateSamples(2);
    ActionGateContext context = new ActionGateContext(
        UUID.randomUUID(), "gate_counter_test", UUID.randomUUID(), 0L, 10L, 0, true, 0.0);
    Map<String, Object> never = Map.of("spatial", Map.of("withinBoundary", false));
    Map<String, Object> always = Map.of("spatial", Map.of("withinBoundary", true));

    assertFalse(GateEvaluator.evaluate(never, context, null));
    assertTrue(ActionCbitCounters.report().findings().stream()
        .noneMatch(finding -> finding.code().equals(ActionCbitCounters.GATE_NEVER_PASSED.key())));
    assertFalse(GateEvaluator.evaluate(never, context, null));
    assertTrue(GateEvaluator.evaluate(always, context, null));
    assertTrue(GateEvaluator.evaluate(always, context, null));

    assertTrue(hasCode(ActionCbitCounters.GATE_NEVER_PASSED.key()));
    assertTrue(hasCode(ActionCbitCounters.GATE_ALWAYS_PASSED.key()));
    assertTrue(ActionCbitCounters.snapshot().gateOutcomes().stream()
        .anyMatch(outcome -> outcome.outcome() == GateOutcomeCounter.Outcome.NEVER_PASSED));
    assertTrue(ActionCbitCounters.snapshot().gateOutcomes().stream()
        .anyMatch(outcome -> outcome.outcome() == GateOutcomeCounter.Outcome.ALWAYS_PASSED));
  }

  @Test
  void unresolvedPlaceholderAtConsoleDispatchIsCounted() {
    ActionSessionImpl session = session("placeholder_counter_test", 60L, List.of(
        ActionDefinition.CommandAction.console("say [not_a_token]")));
    try {
      session.triggerStart();

      assertEquals(1L, ActionCbitCounters.snapshot().unresolvedPlaceholders());
      assertTrue(hasCode(ActionCbitCounters.PLACEHOLDER_UNRESOLVED.key()));
    } finally {
      session.disarm();
    }
  }

  @Test
  void unknownActionIsCountedAtDispatch() {
    ActionSessionImpl session = session("unknown_action_counter_test", 60L, List.of(
        ActionDefinition.CommandAction.action("not_registered")));
    try {
      session.triggerStart();

      assertEquals(1L, ActionCbitCounters.snapshot().unknownActions());
      assertTrue(hasCode(ActionCbitCounters.UNKNOWN_ACTION.key()));
    } finally {
      session.disarm();
    }
  }

  @Test
  void activeSessionReapedByMemoryTrackerIsCountedOnlyOnce() throws InterruptedException {
    MockRTPServerAccessor accessor = new MockRTPServerAccessor(new File("."));
    RTPRuntime.setServerAccessor(accessor);
    RTPRuntime.setScheduler(accessor.getMockScheduler());
    MemoryTracker.reset();
    ActionSessionImpl session = session("orphan_counter_test", 60L, List.of());
    session.arm(0L);
    session.triggerStart();
    try {
      long trackedAtMillis = System.currentTimeMillis();
      long deadlineNanos = System.nanoTime() + 1_000_000_000L;
      while (System.currentTimeMillis() <= trackedAtMillis && System.nanoTime() < deadlineNanos) {
        Thread.sleep(5L);
      }
      MemoryTracker.runDiagnostics();

      assertFalse(session.isActive());
      assertEquals(1L, ActionCbitCounters.snapshot().orphanedSessions());
      assertTrue(hasCode(ActionCbitCounters.SESSION_ORPHANED.key()));
    } finally {
      session.disarm();
      MemoryTracker.reset();
    }
  }

  private static boolean hasCode(String code) {
    return ActionCbitCounters.report().findings().stream().anyMatch(finding -> finding.code().equals(code));
  }

  private static ActionSessionImpl session(
      String id, long durationSeconds, List<ActionDefinition.CommandAction> startActions) {
    ActionDefinition.PlacementSpec placement = new ActionDefinition.PlacementSpec(
        true, "default", "CIRCLE", 100, 0, 16, 8, Map.of(), 3, 0);
    ActionDefinition.ConfinementSpec confinement = new ActionDefinition.ConfinementSpec(
        ConfinementBoundary.SUBSPACE, durationSeconds, 50.0, 0.0, 0.0, 0L, true,
        "SQUARE", 50, 0, 0.0, 0.0, 1L, 0.0, List.of());
    ActionDefinition.LifecycleStep onStart = new ActionDefinition.LifecycleStep(Map.of(), startActions, 0L);
    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        startActions.isEmpty() ? List.of() : List.of(onStart), List.of(), List.of(), List.of(), List.of(), List.of());
    ActionDefinition definition = new ActionDefinition(id, id, "", "", placement, confinement, lifecycle);
    return new ActionSessionImpl(UUID.randomUUID(), definition, List.of(), ActionContext.EMPTY, Map.of(),
        null, 0, 0, null, null, Map.of());
  }
}
