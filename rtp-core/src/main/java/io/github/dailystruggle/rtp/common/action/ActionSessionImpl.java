package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ActionGateContext;
import io.github.dailystruggle.rtp.api.action.ActionSession;
import io.github.dailystruggle.rtp.api.action.ConfinementBoundary;
import io.github.dailystruggle.rtp.api.event.PlayerMoveEvent;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Implementation of an active scripted action session (ADR-093).
 */
public final class ActionSessionImpl implements ActionSession {

  private final UUID sessionId;
  private final ActionDefinition definition;
  private final List<UUID> participants;
  private final ActionContext context;
  private final long startTimeMillis;
  private final long durationSeconds;
  private final Map<UUID, int[]> assignedSlots; // participant -> [worldX, worldY, worldZ]
  private final String worldName;
  private final int anchorX;
  private final int anchorZ;
  private final Region parentRegion;

  private final Map<UUID, AtomicInteger> violations = new ConcurrentHashMap<>();
  private final Map<UUID, AutoCloseable> moveWatchers = new ConcurrentHashMap<>();
  private final AtomicBoolean active = new AtomicBoolean(true);
  private final Consumer<UUID> onDisarmCallback;
  private final Map<String, Predicate<ActionGateContext>> externalPredicates;

  public ActionSessionImpl(
      UUID sessionId,
      ActionDefinition definition,
      List<UUID> participants,
      ActionContext context,
      Map<UUID, int[]> assignedSlots,
      String worldName,
      int anchorX,
      int anchorZ,
      Region parentRegion,
      Consumer<UUID> onDisarmCallback,
      Map<String, Predicate<ActionGateContext>> externalPredicates) {

    this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
    this.definition = Objects.requireNonNull(definition, "definition must not be null");
    this.participants = List.copyOf(participants);
    this.context = Objects.requireNonNull(context, "context must not be null");
    this.assignedSlots = Map.copyOf(assignedSlots);
    this.worldName = worldName;
    this.anchorX = anchorX;
    this.anchorZ = anchorZ;
    this.parentRegion = parentRegion;
    this.onDisarmCallback = onDisarmCallback;
    this.externalPredicates = externalPredicates;

    this.startTimeMillis = System.currentTimeMillis();
    this.durationSeconds = definition.confinement().durationSeconds();

    for (UUID p : participants) {
      violations.put(p, new AtomicInteger(0));
    }
  }

  @Override
  public UUID sessionId() {
    return sessionId;
  }

  @Override
  public String actionId() {
    return definition.id();
  }

  @Override
  public List<UUID> participants() {
    return participants;
  }

  @Override
  public long elapsedSeconds() {
    return Math.max(0L, (System.currentTimeMillis() - startTimeMillis) / 1000L);
  }

  @Override
  public long remainingSeconds() {
    if (durationSeconds < 0L) return -1L;
    return Math.max(0L, durationSeconds - elapsedSeconds());
  }

  @Override
  public boolean isActive() {
    return active.get();
  }

  @Override
  public int getViolations(UUID participantId) {
    AtomicInteger cnt = violations.get(participantId);
    return (cnt == null) ? 0 : cnt.get();
  }

  /**
   * Arms movement confinement watchers for all participants (ADR-075).
   */
  public void arm() {
    for (UUID participant : participants) {
      AutoCloseable reg = RTP.serverAccessor != null
          ? io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.watch(participant, this::onPlayerMove)
          : null;
      if (reg != null) {
        moveWatchers.put(participant, reg);
      }
    }
  }

  /**
   * Tick update for session expiration and timeout checks.
   */
  public void tick() {
    if (!active.get()) return;
    if (durationSeconds >= 0L && remainingSeconds() <= 0L) {
      triggerExpire();
    }
  }

  private void onPlayerMove(PlayerMoveEvent event) {
    if (!active.get()) return;
    UUID pid = event.playerId();
    if (!participants.contains(pid)) return;

    boolean inBounds = checkInBounds(event.toX(), event.toZ());
    if (!inBounds) {
      int breachCount = violations.computeIfAbsent(pid, k -> new AtomicInteger(0)).incrementAndGet();
      triggerBoundaryViolation(pid, event.toX(), event.toZ(), breachCount);
    }
  }

  private boolean checkInBounds(int x, int z) {
    ConfinementBoundary boundary = definition.confinement().boundary();
    return switch (boundary) {
      case REGION -> {
        if (parentRegion == null) yield true;
        // ADR-093 Section 8: Live evaluation against MemoryShape accounts for expand: true
        yield parentRegion.getShape().contains(x, z);
      }
      case SUBSPACE -> {
        // Footprint radius: subspaceChunkRadius chunks
        int rBlocks = definition.placement().subspaceChunkRadius() * 16;
        yield Math.abs(x - anchorX) <= rBlocks && Math.abs(z - anchorZ) <= rBlocks;
      }
      case LEASH -> {
        double leash = definition.confinement().leashRadius();
        double dx = x - anchorX;
        double dz = z - anchorZ;
        yield (dx * dx + dz * dz) <= (leash * leash);
      }
    };
  }

  public void triggerStart() {
    executeLifecycleSteps(definition.lifecycle().onStart(), null);
  }

  public void triggerBoundaryViolation(UUID violatorId, int x, int z, int breachCount) {
    double distSq = (double) (x - anchorX) * (x - anchorX) + (double) (z - anchorZ) * (z - anchorZ);
    ActionGateContext gateCtx = new ActionGateContext(
        sessionId,
        definition.id(),
        violatorId,
        elapsedSeconds(),
        remainingSeconds(),
        breachCount,
        false,
        distSq);

    executeLifecycleSteps(definition.lifecycle().onBoundaryViolation(), gateCtx);
  }

  public void triggerExpire() {
    executeLifecycleSteps(definition.lifecycle().onExpire(), null);
    disarm();
  }

  public void triggerDeath(UUID victimId, UUID winnerId) {
    Map<String, Object> extra = new HashMap<>();
    if (victimId != null) extra.put("victim", victimId);
    if (winnerId != null) extra.put("winner", winnerId);

    executeLifecycleStepsWithTokens(definition.lifecycle().onDeath(), null, extra);
    disarm();
  }

  @Override
  public void pullBack(UUID participantId) {
    if (participantId == null) return;
    int[] slot = assignedSlots.get(participantId);
    if (slot == null) return;

    RTPServerAccessor accessor = RTP.serverAccessor;
    if (accessor != null) {
      // Dispatch safe teleport back to slot without rubberbanding
      io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld(worldName);
      io.github.dailystruggle.rtp.api.entity.RTPPlayer player = accessor.getPlayer(participantId);
      if (world != null && player != null) {
        player.setLocation(new io.github.dailystruggle.rtp.api.world.RTPLocation(
            world, slot[0], slot[1], slot[2]));
      }
    }
  }

  @Override
  public void disarm() {
    if (!active.compareAndSet(true, false)) return;

    for (AutoCloseable watcher : moveWatchers.values()) {
      try {
        watcher.close();
      } catch (Exception ignored) {
        // Safe close
      }
    }
    moveWatchers.clear();

    if (onDisarmCallback != null) {
      onDisarmCallback.accept(sessionId);
    }
  }

  private void executeLifecycleSteps(List<ActionDefinition.LifecycleStep> steps, ActionGateContext gateCtx) {
    executeLifecycleStepsWithTokens(steps, gateCtx, Collections.emptyMap());
  }

  private void executeLifecycleStepsWithTokens(
      List<ActionDefinition.LifecycleStep> steps,
      ActionGateContext gateCtx,
      Map<String, Object> additionalTokens) {

    if (steps == null || steps.isEmpty()) return;

    Map<String, Object> baseTokens = new HashMap<>(additionalTokens);
    baseTokens.put("session_id", sessionId.toString().substring(0, 8));
    if (gateCtx != null && gateCtx.participantId() != null) {
      baseTokens.put("violator", gateCtx.participantId());
    }

    for (ActionDefinition.LifecycleStep step : steps) {
      if (gateCtx != null && !GateEvaluator.evaluate(step.gateConfig(), gateCtx, externalPredicates)) {
        continue; // Gate failed; skip step
      }

      for (ActionDefinition.CommandAction cmd : step.actions()) {
        executeGuardedAction(cmd, baseTokens);
      }
    }
  }

  private void executeGuardedAction(ActionDefinition.CommandAction cmd, Map<String, Object> tokens) {
    try {
      switch (cmd.type()) {
        case CONSOLE -> {
          String raw = ActionPlaceholderSanitizer.substitute(cmd.payload(), tokens);
          dispatchConsoleCommand(raw);
        }
        case PLAYER -> {
          Object pObj = tokens.get("player");
          if (pObj == null) pObj = tokens.get("violator");
          if (pObj instanceof UUID pid) {
            String raw = ActionPlaceholderSanitizer.substitute(cmd.payload(), tokens);
            dispatchPlayerCommand(pid, raw);
          } else {
            // No explicit subject (e.g. onStart / onExpire broadcast): apply to every participant.
            for (UUID pid : participants) {
              Map<String, Object> perPlayerTokens = new HashMap<>(tokens);
              perPlayerTokens.put("player", pid);
              String raw = ActionPlaceholderSanitizer.substitute(cmd.payload(), perPlayerTokens);
              dispatchPlayerCommand(pid, raw);
            }
          }
        }
        case ACTION -> {
          String actionName = cmd.payload().trim().toUpperCase();
          switch (actionName) {
            case "PULL_BACK" -> {
              Object pObj = tokens.get("violator");
              if (pObj == null) pObj = tokens.get("player");
              if (pObj instanceof UUID pid) {
                pullBack(pid);
              }
            }
            case "DISARM" -> disarm();
            default -> RTP.log(Level.WARNING, "[RTP Action] Unknown action command: " + actionName);
          }
        }
        case FOR_EACH -> {
          for (UUID participant : participants) {
            Map<String, Object> perPlayerTokens = new HashMap<>(tokens);
            perPlayerTokens.put("player", participant);
            for (ActionDefinition.CommandAction sub : cmd.subActions()) {
              executeGuardedAction(sub, perPlayerTokens);
            }
          }
        }
      }
    } catch (Exception e) {
      // Guarded execution: ADR-093 Section 2. Failures log WARNING and never crash script
      RTP.log(Level.WARNING, "[RTP Action] Command execution failed for step: " + cmd.payload(), e);
    }
  }

  private void dispatchConsoleCommand(String commandLine) {
    RTPServerAccessor accessor = RTP.serverAccessor;
    if (accessor != null && commandLine != null && !commandLine.isBlank()) {
      accessor.executeCommand(new UUID(0, 0), commandLine);
    }
  }

  private void dispatchPlayerCommand(UUID playerId, String commandLine) {
    RTPServerAccessor accessor = RTP.serverAccessor;
    if (accessor != null && commandLine != null && !commandLine.isBlank()) {
      accessor.executeCommand(playerId, commandLine);
    }
  }
}
