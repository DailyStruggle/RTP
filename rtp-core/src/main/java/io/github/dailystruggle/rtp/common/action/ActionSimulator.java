package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.bit.RecordingDispatcher;
import io.github.dailystruggle.bit.SimulatedClock;
import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ActionGateContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Runs an action definition against deterministic participants without dispatching server effects. */
public final class ActionSimulator {
  private static final int MAX_PLAYERS = 16;
  private static final int MAX_SCHEDULED_TASKS = 1_000;
  private static final UUID SESSION_ID = new UUID(0L, 0L);
  private static final Set<String> SCRIPT_EVENTS = Set.of("violate", "die", "expire");

  private ActionSimulator() {}

  public static Result simulate(ActionDefinition definition, int players, List<String> script) {
    if (definition == null) throw new IllegalArgumentException("Action definition is required.");
    if (players < 1 || players > MAX_PLAYERS) {
      throw new IllegalArgumentException("players must be between 1 and " + MAX_PLAYERS + ".");
    }
    List<String> events = script == null ? List.of() : List.copyOf(script);
    for (String event : events) {
      if (event == null || !SCRIPT_EVENTS.contains(event.trim().toLowerCase())) {
        throw new IllegalArgumentException("Unsupported simulation event: " + event);
      }
    }

    SimulationState state = new SimulationState(players);
    state.recordUngatedLifecycle("onCancel", definition.lifecycle().onCancel());
    List<UUID> participantIds = state.participants();
    ActionSessionImpl session = new ActionSessionImpl(
        SESSION_ID,
        definition,
        participantIds,
        ActionContext.EMPTY,
        Map.of(),
        "simulation",
        0,
        0,
        null,
        ignored -> {},
        Map.of(),
        state);

    UUID firstParticipant = participantIds.get(0);
    ActionGateContext preGateContext = new ActionGateContext(
        SESSION_ID,
        definition.id(),
        firstParticipant,
        0L,
        definition.confinement().durationSeconds(),
        0,
        true,
        0.0,
        null,
        null,
        null,
        null,
        null,
        null,
        players,
        ActionContext.EMPTY);
    Map<String, Object> preGateTokens = new HashMap<>();
    preGateTokens.put("player", firstParticipant);
    preGateTokens.put("player_1", firstParticipant);
    preGateTokens.put("player_uuid_1", firstParticipant);
    if (!GateEvaluator.evaluateAll(definition.gates(), preGateContext, Map.of(), preGateTokens, state)) {
      state.notes.add("Action pre-execution gates failed; lifecycle simulation was not run.");
      return new Result(definition.id(), players, events, state.dispatcher.commands(),
          state.gateOutcomes, state.notes);
    }

    session.triggerStart();
    for (String event : events) {
      if (!session.isActive()) {
        state.notes.add("Remaining script events were ignored after the action session disarmed.");
        break;
      }
      switch (event.trim().toLowerCase()) {
        case "violate" -> {
          int nextViolation = session.getViolations(participantIds.get(0)) + 1;
          session.triggerBoundaryViolation(participantIds.get(0), "simulation", 1_000 + nextViolation, 64,
              2_000 + nextViolation, nextViolation);
        }
        case "die" -> {
          state.recordUngatedLifecycle("onDeath", definition.lifecycle().onDeath());
          UUID winner = participantIds.size() > 1 ? participantIds.get(1) : null;
          session.triggerDeath(participantIds.get(0), winner);
        }
        case "expire" -> {
          state.recordUngatedLifecycle("onExpire", definition.lifecycle().onExpire());
          session.triggerExpire();
        }
        default -> throw new IllegalArgumentException("Unsupported simulation event: " + event);
      }
    }
    state.drainScheduledTasks();
    if (session.isActive()) session.disarm();

    return new Result(definition.id(), players, events, state.dispatcher.commands(),
        state.gateOutcomes, state.notes);
  }

  public record GateOutcome(String gate, boolean passed, String reason) {}

  public record Result(
      String actionId,
      int players,
      List<String> script,
      List<String> commands,
      List<GateOutcome> gateOutcomes,
      List<String> notes) {
    public Result {
      script = List.copyOf(script);
      commands = List.copyOf(commands);
      gateOutcomes = List.copyOf(gateOutcomes);
      notes = List.copyOf(notes);
    }

    public List<String> lines() {
      List<String> output = new ArrayList<>();
      output.add("SIMULATION ONLY - action=" + actionId + ", players=" + players + ", script=" + script);
      for (GateOutcome outcome : gateOutcomes) {
        output.add("Gate " + outcome.gate() + ": " + (outcome.passed() ? "PASS" : "FAIL")
            + " - " + outcome.reason());
      }
      for (String command : commands) output.add("Command (not dispatched): " + command);
      for (String note : notes) output.add("Note: " + note);
      if (gateOutcomes.isEmpty()) output.add("No lifecycle gates were evaluated.");
      if (commands.isEmpty()) output.add("No commands were produced.");
      return List.copyOf(output);
    }
  }

  private record ScheduledTask(long deadlineNanos, long sequence, Runnable task) {}

  private static final class SimulationState implements ActionSessionImpl.Simulation, GateTrace {
    private final SimulatedClock clock = new SimulatedClock();
    private final RecordingDispatcher dispatcher = new RecordingDispatcher();
    private final PriorityQueue<ScheduledTask> scheduled = new PriorityQueue<>(
        Comparator.comparingLong(ScheduledTask::deadlineNanos).thenComparingLong(ScheduledTask::sequence));
    private final Map<UUID, String> names = new HashMap<>();
    private final List<GateOutcome> gateOutcomes = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final List<UUID> participantIds;
    private long taskSequence;

    private SimulationState(int players) {
      List<UUID> ids = new ArrayList<>(players);
      for (int i = 1; i <= players; i++) {
        UUID id = UUID.nameUUIDFromBytes(("rtp-action-simulation-player-" + i).getBytes(StandardCharsets.UTF_8));
        ids.add(id);
        names.put(id, "sim-player-" + i);
      }
      participantIds = List.copyOf(ids);
    }

    private List<UUID> participants() {
      return participantIds;
    }

    @Override
    public long currentTimeMillis() {
      return TimeUnit.NANOSECONDS.toMillis(clock.nanoTime());
    }

    @Override
    public void schedule(long delayTicks, Runnable task) {
      if (task == null) throw new NullPointerException("task must not be null");
      long ticks = Math.max(1L, delayTicks);
      long delayMillis = ticks > Long.MAX_VALUE / 50L ? Long.MAX_VALUE : ticks * 50L;
      long delayNanos = TimeUnit.MILLISECONDS.toNanos(delayMillis);
      long now = clock.nanoTime();
      long deadline = delayNanos > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delayNanos;
      scheduled.add(new ScheduledTask(deadline, taskSequence++, task));
    }

    @Override
    public void dispatch(String command) {
      dispatcher.dispatch(command);
    }

    @Override
    public void note(String message) {
      notes.add(message);
    }

    @Override
    public String playerName(UUID participantId) {
      return names.get(participantId);
    }

    @Override
    public GateTrace gateTrace() {
      return this;
    }

    @Override
    public void onOutcome(Map<String, Object> gateConfig, boolean passed, String reason) {
      gateOutcomes.add(new GateOutcome(String.valueOf(gateConfig), passed, reason));
    }

    @Override
    public boolean simulateCommand(String command) {
      dispatcher.dispatch(command);
      return true;
    }

    private void recordUngatedLifecycle(String stage, List<ActionDefinition.LifecycleStep> steps) {
      if (steps != null && steps.stream().anyMatch(
          step -> step != null && step.gateConfig() != null && !step.gateConfig().isEmpty())) {
        notes.add(stage + " has gates, but that lifecycle stage has no gate context (gate not evaluated).");
      }
    }

    private void drainScheduledTasks() {
      int processed = 0;
      while (!scheduled.isEmpty() && processed < MAX_SCHEDULED_TASKS) {
        ScheduledTask next = scheduled.remove();
        long now = clock.nanoTime();
        if (next.deadlineNanos() > now) clock.advanceNanos(next.deadlineNanos() - now);
        next.task().run();
        processed++;
      }
      if (!scheduled.isEmpty()) {
        notes.add("Scheduled lifecycle work exceeded the simulation limit of " + MAX_SCHEDULED_TASKS + " tasks.");
      }
    }
  }
}
