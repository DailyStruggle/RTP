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
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;
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
  private final Map<UUID, Boolean> participantInBoundsState = new ConcurrentHashMap<>();
  private final Map<UUID, Long> lastDamageSeconds = new ConcurrentHashMap<>();
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

  public ActionContext context() {
    return context;
  }

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
   * Arms movement confinement watchers for all participants (ADR-075) and tracks session in MemoryTracker.
   */
  public void arm() {
    long maxLifespanMs = (durationSeconds > 0L)
        ? (durationSeconds + 30L) * 1000L
        : 30_000L;
    MemoryTracker.track(this, "ActionSession-" + sessionId, maxLifespanMs);

    // If placement is disabled or worldName is null, movement confinement is not monitored
    if (!definition.placement().enabled() || worldName == null) {
      return;
    }

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
   * Tick update for session expiration, continuous boundary confinement, and timeout checks.
   */
  public void tick() {
    if (!active.get()) return;
    checkContinuousConfinement();
    updateDynamicScoreboards();
    if (durationSeconds >= 0L && remainingSeconds() <= 0L) {
      triggerExpire();
    }
  }

  private void checkContinuousConfinement() {
    if (!definition.placement().enabled() || worldName == null) return;
    RTPServerAccessor accessor = RTP.serverAccessor;
    if (accessor == null) return;

    double damageAmount = definition.confinement().damageAmount();
    double damageBuffer = definition.confinement().damageBuffer();
    long damageIntervalSec = definition.confinement().damageIntervalSeconds();
    double maxDistanceOutside = definition.confinement().maxDistanceOutside();
    long nowSec = System.currentTimeMillis() / 1000L;

    for (UUID pid : participants) {
      io.github.dailystruggle.rtp.api.entity.RTPPlayer p = accessor.getPlayer(pid);
      if (p == null || !p.isOnline()) continue;
      io.github.dailystruggle.rtp.api.world.RTPLocation loc = p.getLocation();
      if (loc == null) continue;

      int lx = (int) Math.floor(loc.x());
      int lz = (int) Math.floor(loc.z());
      boolean inBounds = checkInBounds(loc.world().name(), lx, lz);
      Boolean prevInBounds = participantInBoundsState.put(pid, inBounds);

      if (!inBounds) {
        double distOutside = calculateDistanceOutside(loc.x(), loc.z());

        // If player just transitioned from in-bounds to out-of-bounds while standing still, trigger violation
        if (prevInBounds == null || prevInBounds) {
          int breachCount = violations.computeIfAbsent(pid, k -> new AtomicInteger(0)).incrementAndGet();
          triggerBoundaryViolation(pid, loc.world().name(), lx, (int) loc.y(), lz, breachCount);
        }

        // Hard boundary limit enforcement: check relative distance or static initial boundary ceiling
        double distFromInitial = calculateDistanceOutsideInitial(loc.x(), loc.z());
        if (maxDistanceOutside > 0.0 && (distOutside > maxDistanceOutside || distFromInitial > maxDistanceOutside)) {
          executeOutsideActions(pid);
        }

        // Declarative damage application when outside border buffer
        if (damageAmount > 0.0) {
          if (distOutside > damageBuffer) {
            long lastDmg = lastDamageSeconds.getOrDefault(pid, 0L);
            if (nowSec - lastDmg >= damageIntervalSec) {
              lastDamageSeconds.put(pid, nowSec);
              accessor.damagePlayer(pid, damageAmount);
            }
          }
        }
      }
    }
  }

  private double calculateDistanceOutside(double px, double pz) {
    ConfinementBoundary boundary = definition.confinement().boundary();
    double rBlocks = currentBoundaryRadius();
    double dx = Math.abs(px - anchorX);
    double dz = Math.abs(pz - anchorZ);

    if (boundary == ConfinementBoundary.LEASH ||
        (boundary == ConfinementBoundary.SHAPE && "CIRCLE".equalsIgnoreCase(definition.confinement().shapeName()))) {
      double distFromCenter = Math.sqrt((px - anchorX) * (px - anchorX) + (pz - anchorZ) * (pz - anchorZ));
      return Math.max(0.0, distFromCenter - rBlocks);
    } else {
      // Chebyshev / square distance
      double chebyshev = Math.max(dx, dz);
      return Math.max(0.0, chebyshev - rBlocks);
    }
  }

  private void onPlayerMove(PlayerMoveEvent event) {
    if (!active.get()) return;
    UUID pid = event.playerId();
    if (!participants.contains(pid)) return;

    boolean inBounds = checkInBounds(event.worldName(), event.toX(), event.toZ());
    participantInBoundsState.put(pid, inBounds);
    if (!inBounds) {
      double distOutside = calculateDistanceOutside(event.toX(), event.toZ());
      int breachCount = violations.computeIfAbsent(pid, k -> new AtomicInteger(0)).incrementAndGet();
      triggerBoundaryViolation(pid, event.worldName(), event.toX(), event.toY(), event.toZ(), breachCount);

      // Hard boundary limit enforcement: check relative distance or static initial boundary ceiling
      double maxDistanceOutside = definition.confinement().maxDistanceOutside();
      double distFromInitial = calculateDistanceOutsideInitial(event.toX(), event.toZ());
      if (maxDistanceOutside > 0.0 && (distOutside > maxDistanceOutside || distFromInitial > maxDistanceOutside)) {
        executeOutsideActions(pid);
      }

      // Declarative damage check on move
      double damageAmount = definition.confinement().damageAmount();
      if (damageAmount > 0.0) {
        if (distOutside > definition.confinement().damageBuffer()) {
          long nowSec = System.currentTimeMillis() / 1000L;
          long lastDmg = lastDamageSeconds.getOrDefault(pid, 0L);
          if (nowSec - lastDmg >= definition.confinement().damageIntervalSeconds()) {
            lastDamageSeconds.put(pid, nowSec);
            if (RTP.serverAccessor != null) {
              RTP.serverAccessor.damagePlayer(pid, damageAmount);
            }
          }
        }
      }
    }
  }

  private double calculateDistanceOutsideInitial(double px, double pz) {
    ConfinementBoundary boundary = definition.confinement().boundary();
    double rBlocks = initialBoundaryRadius();
    double dx = Math.abs(px - anchorX);
    double dz = Math.abs(pz - anchorZ);

    if (boundary == ConfinementBoundary.LEASH ||
        (boundary == ConfinementBoundary.SHAPE && "CIRCLE".equalsIgnoreCase(definition.confinement().shapeName()))) {
      double distFromCenter = Math.sqrt((px - anchorX) * (px - anchorX) + (pz - anchorZ) * (pz - anchorZ));
      return Math.max(0.0, distFromCenter - rBlocks);
    } else {
      // Chebyshev / square distance
      double chebyshev = Math.max(dx, dz);
      return Math.max(0.0, chebyshev - rBlocks);
    }
  }

  private void executeOutsideActions(UUID pid) {
    if (pid == null) return;
    List<ActionDefinition.CommandAction> actions = definition.confinement().outsideActions();
    if (actions == null || actions.isEmpty()) {
      pullBack(pid);
      return;
    }

    Map<String, Object> tokens = new HashMap<>(context.metadata());
    tokens.put("session_id", sessionId.toString().substring(0, 8));
    tokens.put("player", pid);
    tokens.put("violator", pid);
    tokens.put("player_uuid", pid);
    if (worldName != null) {
      tokens.put("world", worldName);
    }
    if (RTP.serverAccessor != null) {
      io.github.dailystruggle.rtp.api.entity.RTPPlayer player = RTP.serverAccessor.getPlayer(pid);
      if (player != null && player.name() != null) {
        tokens.put("player_name", player.name());
        tokens.put("violator_name", player.name());
      }
    }

    for (ActionDefinition.CommandAction cmd : actions) {
      if (cmd != null) {
        executeGuardedAction(cmd, tokens);
      }
    }
  }

  private boolean checkInBounds(String moveWorld, int x, int z) {
    // If movement occurs in a different world than the session world, participant is out of bounds
    if (worldName != null && moveWorld != null && !worldName.equals(moveWorld)) {
      return false;
    }

    ConfinementBoundary boundary = definition.confinement().boundary();
    return switch (boundary) {
      case REGION -> {
        if (parentRegion == null) yield true;
        // ADR-093 Section 8: Live evaluation against MemoryShape accounts for expand: true
        yield parentRegion.getShape().contains(x, z);
      }
      case SUBSPACE -> {
        double rBlocks = currentBoundaryRadius();
        yield Math.abs(x - anchorX) <= rBlocks && Math.abs(z - anchorZ) <= rBlocks;
      }
      case LEASH -> {
        double leash = currentBoundaryRadius();
        double dx = (double) x - anchorX;
        double dz = (double) z - anchorZ;
        yield (dx * dx + dz * dz) <= (leash * leash);
      }
      case SHAPE -> {
        double rBlocks = currentBoundaryRadius();
        int centerR = definition.confinement().centerRadius();
        double dx = (double) x - anchorX;
        double dz = (double) z - anchorZ;
        String sName = definition.confinement().shapeName();
        if ("CIRCLE".equalsIgnoreCase(sName)) {
          double distSq = dx * dx + dz * dz;
          if (distSq > rBlocks * rBlocks) yield false;
          if (centerR > 0 && distSq < (double) centerR * centerR) yield false;
          yield true;
        } else {
          // Default to SQUARE / Chebyshev distance
          double absX = Math.abs(dx);
          double absZ = Math.abs(dz);
          if (absX > rBlocks || absZ > rBlocks) yield false;
          if (centerR > 0 && Math.max(absX, absZ) < centerR) yield false;
          yield true;
        }
      }
    };
  }

  /**
   * Returns the static initial boundary radius in blocks before any shrinking occurs.
   */
  public double initialBoundaryRadius() {
    ConfinementBoundary boundary = definition.confinement().boundary();
    double baseRadius;
    if (boundary == ConfinementBoundary.SHAPE) {
      baseRadius = definition.confinement().radius() > 0
          ? (double) definition.confinement().radius()
          : (double) definition.placement().radius();
    } else if (boundary == ConfinementBoundary.LEASH) {
      baseRadius = definition.confinement().leashRadius();
    } else {
      baseRadius = definition.confinement().radius() > 0
          ? (double) definition.confinement().radius()
          : (double) definition.placement().radius();
    }

    return definition.confinement().initialSize() > 0.0
        ? definition.confinement().initialSize() / 2.0
        : baseRadius;
  }

  /**
   * Returns the current mathematical boundary radius in blocks, linearly interpolated
   * if shrinkTo and shrinkOverSeconds are configured.
   */
  public double currentBoundaryRadius() {
    double initialRadius = initialBoundaryRadius();

    double shrinkToRadius = definition.confinement().shrinkTo() > 0.0
        ? definition.confinement().shrinkTo() / 2.0
        : initialRadius;

    long shrinkOver = definition.confinement().shrinkOverSeconds();
    if (shrinkOver <= 0L || Math.abs(initialRadius - shrinkToRadius) < 1e-6) {
      return initialRadius;
    }

    long elapsed = elapsedSeconds();
    if (elapsed <= 0L) {
      return initialRadius;
    }
    if (elapsed >= shrinkOver) {
      return shrinkToRadius;
    }

    double progress = (double) elapsed / (double) shrinkOver;
    return initialRadius + progress * (shrinkToRadius - initialRadius);
  }

  public void triggerStart() {
    sendConfinementWorldBorder();
    initializeScoreboards();
    UUID firstParticipant = participants.isEmpty() ? new UUID(0, 0) : participants.get(0);
    double initialY = 64.0;
    int[] firstSlot = assignedSlots.get(firstParticipant);
    if (firstSlot != null && firstSlot.length >= 2) {
      initialY = (double) firstSlot[1];
    }
    ActionGateContext startGateCtx = new ActionGateContext(
        sessionId,
        definition.id(),
        firstParticipant,
        0L,
        durationSeconds,
        0,
        true,
        0.0,
        (double) anchorX,
        initialY,
        (double) anchorZ,
        (double) anchorX,
        initialY,
        (double) anchorZ,
        participants.size(),
        context);
    executeLifecycleSteps(definition.lifecycle().onStart(), startGateCtx);

    // If session has zero or negative duration (one-shot placement without confinement),
    // automatically disarm now so participants and scoreboards are cleanly released.
    if (durationSeconds <= 0L) {
      disarm();
    }
  }

  private static final java.util.concurrent.atomic.AtomicBoolean SCOREBOARD_OBJECTIVES_INITIALIZED =
      new java.util.concurrent.atomic.AtomicBoolean(false);

  public static void resetScoreboardObjectivesInitializedForTesting() {
    SCOREBOARD_OBJECTIVES_INITIALIZED.set(false);
  }

  private void initializeScoreboards() {
    RTPServerAccessor accessor = RTP.serverAccessor;
    if (accessor == null) return;

    // ADR-093 §4: Ensure dummy objectives exist once per plugin lifecycle
    if (SCOREBOARD_OBJECTIVES_INITIALIZED.compareAndSet(false, true)) {
      accessor.ensureScoreboardObjective("rtp_session_id", "dummy");
      accessor.ensureScoreboardObjective("rtp_time_left", "dummy");
      accessor.ensureScoreboardObjective("rtp_violations", "dummy");
      accessor.ensureScoreboardObjective("rtp_in_bounds", "dummy");
      accessor.ensureScoreboardObjective("rtp_dist_sq", "dummy");
      accessor.ensureScoreboardObjective("rtp_alive", "dummy");
    }

    String sessionTag = "rtp_session_" + sessionId.toString().substring(0, 8);
    int rawHash = sessionId.hashCode();
    int sessionHash = (rawHash == Integer.MIN_VALUE) ? Integer.MAX_VALUE : Math.abs(rawHash);
    int aliveCount = countAliveParticipants();
    long timeLeft = durationSeconds >= 0L ? durationSeconds : 999999L;

    for (UUID pid : participants) {
      accessor.addScoreboardTag(pid, sessionTag);
      accessor.setScoreboardScore(pid, "rtp_session_id", sessionHash);
      accessor.setScoreboardScore(pid, "rtp_time_left", (int) Math.min(Integer.MAX_VALUE, timeLeft));
      accessor.setScoreboardScore(pid, "rtp_violations", 0);
      accessor.setScoreboardScore(pid, "rtp_in_bounds", 1);
      accessor.setScoreboardScore(pid, "rtp_dist_sq", 0);
      accessor.setScoreboardScore(pid, "rtp_alive", aliveCount);
    }
  }

  private int countAliveParticipants() {
    RTPServerAccessor accessor = RTP.serverAccessor;
    if (accessor == null) return participants.size();
    int count = 0;
    for (UUID pid : participants) {
      io.github.dailystruggle.rtp.api.entity.RTPPlayer p = accessor.getPlayer(pid);
      if (p != null && p.isOnline()) {
        count++;
      }
    }
    return count > 0 ? count : participants.size();
  }

  private void updateDynamicScoreboards() {
    RTPServerAccessor accessor = RTP.serverAccessor;
    if (accessor == null) return;

    long timeLeft = durationSeconds >= 0L ? Math.max(0L, remainingSeconds()) : 999999L;
    int aliveCount = countAliveParticipants();

    for (UUID pid : participants) {
      accessor.setScoreboardScore(pid, "rtp_time_left", (int) Math.min(Integer.MAX_VALUE, timeLeft));
      accessor.setScoreboardScore(pid, "rtp_alive", aliveCount);

      io.github.dailystruggle.rtp.api.entity.RTPPlayer p = accessor.getPlayer(pid);
      if (p != null && p.isOnline()) {
        io.github.dailystruggle.rtp.api.world.RTPLocation loc = p.getLocation();
        if (loc != null) {
          double dx = (double) loc.x() - anchorX;
          double dz = (double) loc.z() - anchorZ;
          long distSq = (long) (dx * dx + dz * dz);
          accessor.setScoreboardScore(pid, "rtp_dist_sq", (int) Math.min(Integer.MAX_VALUE, distSq));
        }
      }
    }
  }

  private void updateParticipantBreachScoreboard(UUID violatorId, int breachCount, double distSq) {
    RTPServerAccessor accessor = RTP.serverAccessor;
    if (accessor == null || violatorId == null) return;
    accessor.setScoreboardScore(violatorId, "rtp_violations", breachCount);
    accessor.setScoreboardScore(violatorId, "rtp_in_bounds", 0);
    accessor.setScoreboardScore(violatorId, "rtp_dist_sq", (int) Math.min(Integer.MAX_VALUE, (long) distSq));
  }

  private void cleanupScoreboards() {
    RTPServerAccessor accessor = RTP.serverAccessor;
    if (accessor == null) return;
    String sessionTag = "rtp_session_" + sessionId.toString().substring(0, 8);

    for (UUID pid : participants) {
      accessor.removeScoreboardTag(pid, sessionTag);
      accessor.resetScoreboardScore(pid, "rtp_session_id");
      accessor.resetScoreboardScore(pid, "rtp_time_left");
      accessor.resetScoreboardScore(pid, "rtp_violations");
      accessor.resetScoreboardScore(pid, "rtp_in_bounds");
      accessor.resetScoreboardScore(pid, "rtp_dist_sq");
      accessor.resetScoreboardScore(pid, "rtp_alive");
    }
  }

  private void sendConfinementWorldBorder() {
    if (!definition.placement().enabled() || worldName == null) {
      return;
    }
    if (definition.confinement() == null || definition.confinement().boundary() == null) {
      return;
    }
    double defaultSize;
    switch (definition.confinement().boundary()) {
      case SHAPE -> {
        double r = definition.confinement().radius() > 0
            ? (double) definition.confinement().radius()
            : (double) definition.placement().radius();
        defaultSize = r * 2.0;
      }
      case SUBSPACE -> {
        int rBlocks = definition.confinement().radius() > 0
            ? definition.confinement().radius()
            : definition.placement().radius();
        defaultSize = rBlocks * 2.0;
      }
      case LEASH -> {
        double r = definition.confinement().leashRadius();
        defaultSize = r * 2.0;
      }
      case REGION -> {
        // Parent region bounding box or default
        defaultSize = 512.0;
      }
      default -> {
        return;
      }
    }

    double initialSize = definition.confinement().initialSize() > 0.0
        ? definition.confinement().initialSize()
        : defaultSize;
    double shrinkTo = definition.confinement().shrinkTo() > 0.0
        ? definition.confinement().shrinkTo()
        : initialSize;
    long shrinkOver = definition.confinement().shrinkOverSeconds();
    double damageAmount = definition.confinement().damageAmount();
    double damageBuffer = definition.confinement().damageBuffer();

    RTPServerAccessor accessor = RTP.serverAccessor;
    if (accessor != null) {
      for (UUID participant : participants) {
        accessor.sendWorldBorder(
            participant, anchorX, anchorZ, initialSize, shrinkTo, shrinkOver, damageAmount, damageBuffer);
      }
    }
  }

  private void resetConfinementWorldBorder() {
    RTPServerAccessor accessor = RTP.serverAccessor;
    if (accessor != null) {
      for (UUID participant : participants) {
        accessor.resetWorldBorder(participant);
      }
    }
  }

  public void triggerBoundaryViolation(UUID violatorId, int x, int z, int breachCount) {
    triggerBoundaryViolation(violatorId, worldName, x, 64, z, breachCount);
  }

  public void triggerBoundaryViolation(UUID violatorId, String eventWorld, int x, int y, int z, int breachCount) {
    if (violatorId != null) {
      violations.computeIfAbsent(violatorId, k -> new AtomicInteger(0)).set(breachCount);
    }
    double distSq = (double) (x - anchorX) * (x - anchorX) + (double) (z - anchorZ) * (z - anchorZ);
    updateParticipantBreachScoreboard(violatorId, breachCount, distSq);
    int[] slot = (violatorId != null) ? assignedSlots.get(violatorId) : null;
    double slotY = (slot != null && slot.length >= 2) ? (double) slot[1] : 64.0;
    ActionGateContext gateCtx = new ActionGateContext(
        sessionId,
        definition.id(),
        violatorId,
        elapsedSeconds(),
        remainingSeconds(),
        breachCount,
        false,
        distSq,
        (double) x,
        (double) y,
        (double) z,
        (double) anchorX,
        slotY,
        (double) anchorZ);

    executeLifecycleSteps(definition.lifecycle().onBoundaryViolation(), gateCtx);
  }

  public void triggerExpire() {
    try {
      executeLifecycleSteps(definition.lifecycle().onExpire(), null);
    } catch (Exception e) {
      RTP.log(Level.WARNING, "[RTP Action] Error executing onExpire lifecycle steps", e);
    } finally {
      disarm();
    }
  }

  public void triggerCancel(UUID participantId) {
    try {
      Map<String, Object> extra = new HashMap<>();
      if (participantId != null) {
        extra.put("player", participantId);
        extra.put("player_uuid", participantId);
        extra.put("canceller", participantId);
      }
      List<ActionDefinition.LifecycleStep> steps = definition.lifecycle().onCancel();
      if (steps.isEmpty()) {
        steps = definition.lifecycle().onExpire();
      }
      executeLifecycleStepsWithTokens(steps, null, extra);
    } catch (Exception e) {
      RTP.log(Level.WARNING, "[RTP Action] Error executing onCancel lifecycle steps", e);
    } finally {
      disarm();
    }
  }

  public void triggerDeath(UUID victimId, UUID winnerId) {
    try {
      Map<String, Object> extra = new HashMap<>();
      if (victimId != null) extra.put("victim", victimId);
      if (winnerId != null) extra.put("winner", winnerId);

      executeLifecycleStepsWithTokens(definition.lifecycle().onDeath(), null, extra);
    } catch (Exception e) {
      RTP.log(Level.WARNING, "[RTP Action] Error executing onDeath lifecycle steps", e);
    } finally {
      disarm();
    }
  }

  @Override
  public void pullBack(UUID participantId) {
    if (participantId == null) return;
    int[] slot = assignedSlots.get(participantId);
    if (slot == null) return;

    RTPServerAccessor accessor = RTP.serverAccessor;
    if (accessor != null) {
      // Dispatch safe teleport back to slot without rubberbanding (ADR-093 §8, S-001, S-004, S-005)
      io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld(worldName);
      io.github.dailystruggle.rtp.api.entity.RTPPlayer player = accessor.getPlayer(participantId);
      if (world != null && player != null && player.isOnline()) {
        io.github.dailystruggle.rtp.api.world.RTPLocation targetLoc =
            new io.github.dailystruggle.rtp.api.world.RTPLocation(world, slot[0], slot[1], slot[2]);
        player.setLocation(targetLoc).whenComplete((success, ex) -> {
          if (ex != null) {
            RTP.log(Level.WARNING, "[RTP] Action pull-back failed for player " + participantId, ex);
          } else if (Boolean.FALSE.equals(success)) {
            RTP.log(Level.WARNING, "[RTP] Action pull-back rejected for player " + participantId);
          } else {
            participantInBoundsState.put(participantId, true);
          }
        });
      }
    }
  }

  @Override
  public void disarm() {
    if (!active.compareAndSet(true, false)) return;

    MemoryTracker.untrack(this);
    resetConfinementWorldBorder();
    cleanupScoreboards();

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

    Map<String, Object> baseTokens = new HashMap<>(context.metadata());
    baseTokens.putAll(additionalTokens);
    baseTokens.put("session_id", sessionId.toString().substring(0, 8));
    if (gateCtx != null && gateCtx.participantId() != null) {
      baseTokens.put("violator", gateCtx.participantId());
    }

    // Populate cluster tokens ([cluster_1], [cluster_2], [cluster_<name>], [players])
    baseTokens.put("players", participants);
    baseTokens.put("participants", participants);
    baseTokens.put("all", participants);

    if (!participants.isEmpty()) {
      baseTokens.put("sender", participants.get(0));
      baseTokens.put("challenger", participants.get(0));
      if (participants.size() > 1) {
        baseTokens.put("target", participants.get(1));
        baseTokens.put("opponent", participants.get(1));
      }
    }

    // Populate numbered participant tokens ([player_1], [player_2], [sender_1], [sender_2], etc.)
    for (int i = 0; i < participants.size(); i++) {
      int num = i + 1;
      UUID pid = participants.get(i);
      baseTokens.put("player_" + num, pid);
      baseTokens.put("player_uuid_" + num, pid);
      baseTokens.put("sender_" + num, pid);
      baseTokens.put("sender_uuid_" + num, pid);
      if (RTP.serverAccessor != null) {
        io.github.dailystruggle.rtp.api.entity.RTPPlayer player = RTP.serverAccessor.getPlayer(pid);
        if (player != null && player.name() != null) {
          baseTokens.put("player_name_" + num, player.name());
          baseTokens.put("sender_name_" + num, player.name());
        }
      }
    }

    List<List<UUID>> clusters = context.clusters();
    for (int i = 0; i < clusters.size(); i++) {
      baseTokens.put("cluster_" + (i + 1), clusters.get(i));
      baseTokens.put("group_" + (i + 1), clusters.get(i));
    }

    Map<String, List<UUID>> named = context.namedClusters();
    for (Map.Entry<String, List<UUID>> entry : named.entrySet()) {
      baseTokens.put("cluster_" + entry.getKey().toLowerCase(), entry.getValue());
      baseTokens.put("group_" + entry.getKey().toLowerCase(), entry.getValue());
    }

    for (ActionDefinition.LifecycleStep step : steps) {
      if (step == null) continue;
      try {
        if (gateCtx != null && !GateEvaluator.evaluate(step.gateConfig(), gateCtx, externalPredicates, baseTokens)) {
          continue; // Gate failed; skip step
        }

        if (step.delaySeconds() > 0L) {
          // Schedule delayed execution
          long delayTicks = Math.max(1L, step.delaySeconds() * 20L);
          RTP.scheduler.runTaskLater(() -> {
            if (!active.get()) return;
            try {
              // Re-evaluate gate if context present (e.g. opt-out check)
              ActionGateContext currentGateCtx = gateCtx;
              if (currentGateCtx != null && currentGateCtx.participantId() != null) {
                int currentViolations = getViolations(currentGateCtx.participantId());
                currentGateCtx = new ActionGateContext(
                    currentGateCtx.sessionId(),
                    currentGateCtx.actionId(),
                    currentGateCtx.participantId(),
                    elapsedSeconds(),
                    remainingSeconds(),
                    currentViolations,
                    currentGateCtx.inBounds(),
                    currentGateCtx.distanceSqFromAnchor(),
                    currentGateCtx.currentX(),
                    currentGateCtx.currentY(),
                    currentGateCtx.currentZ(),
                    currentGateCtx.anchorX(),
                    currentGateCtx.anchorY(),
                    currentGateCtx.anchorZ());
              }
              if (currentGateCtx != null && !GateEvaluator.evaluate(step.gateConfig(), currentGateCtx, externalPredicates, baseTokens)) {
                return;
              }
              if (step.actions() != null) {
                for (ActionDefinition.CommandAction cmd : step.actions()) {
                  if (cmd != null) {
                    executeGuardedAction(cmd, baseTokens);
                  }
                }
              }
            } catch (Exception ex) {
              RTP.log(Level.WARNING, "[RTP Action] Error executing delayed lifecycle step", ex);
            }
          }, delayTicks);
        } else {
          if (step.actions() != null) {
            for (ActionDefinition.CommandAction cmd : step.actions()) {
              if (cmd != null) {
                executeGuardedAction(cmd, baseTokens);
              }
            }
          }
        }
      } catch (Exception ex) {
        RTP.log(Level.WARNING, "[RTP Action] Error evaluating lifecycle step", ex);
      }
    }
  }

  private void executeGuardedAction(ActionDefinition.CommandAction cmd, Map<String, Object> tokens) {
    try {
      if (ActionPlaceholderSanitizer.hasMissingTarget(cmd.payload(), tokens)) {
        RTP.log(Level.FINE, "[RTP Action] Dropping lifecycle command due to missing target: " + cmd.payload());
        return;
      }
      switch (cmd.type()) {
        case CONSOLE -> {
          // If [player] / [player_name] / [player_uuid] is present but no single player token exists, dispatch for each participant
          boolean hasPlayerToken = cmd.payload().contains("[player]")
              || cmd.payload().contains("[player_name]")
              || cmd.payload().contains("[player_uuid]");
          if (hasPlayerToken && !tokens.containsKey("player") && !participants.isEmpty()) {
            for (UUID pid : participants) {
              Map<String, Object> perPlayerTokens = new HashMap<>(tokens);
              perPlayerTokens.put("player", pid);
              if (ActionPlaceholderSanitizer.hasMissingTarget(cmd.payload(), perPlayerTokens)) {
                continue;
              }
              String raw = ActionPlaceholderSanitizer.substitute(cmd.payload(), perPlayerTokens);
              if (ActionPlaceholderSanitizer.containsUnresolvedPrefix(raw, "target")) {
                continue;
              }
              dispatchConsoleCommand(raw);
            }
          } else {
            String raw = ActionPlaceholderSanitizer.substitute(cmd.payload(), tokens);
            if (ActionPlaceholderSanitizer.containsUnresolvedPrefix(raw, "target")) {
              return;
            }
            if (ActionPlaceholderSanitizer.containsAnyUnresolvedPlaceholder(raw)) {
              RTP.log(Level.WARNING, "[action] dropping console command with unresolved placeholder: " + raw);
              return;
            }
            dispatchConsoleCommand(raw);
          }
        }
        case PLAYER -> {
          Object pObj = tokens.get("player");
          if (pObj == null) pObj = tokens.get("violator");
          if (pObj instanceof UUID pid) {
            String raw = ActionPlaceholderSanitizer.substitute(cmd.payload(), tokens);
            if (!ActionPlaceholderSanitizer.containsUnresolvedPrefix(raw, "target")) {
              dispatchPlayerCommand(pid, raw);
            }
          } else {
            // No explicit subject (e.g. onStart / onExpire broadcast): apply to every participant.
            for (UUID pid : participants) {
              Map<String, Object> perPlayerTokens = new HashMap<>(tokens);
              perPlayerTokens.put("player", pid);
              if (ActionPlaceholderSanitizer.hasMissingTarget(cmd.payload(), perPlayerTokens)) {
                continue;
              }
              String raw = ActionPlaceholderSanitizer.substitute(cmd.payload(), perPlayerTokens);
              if (ActionPlaceholderSanitizer.containsUnresolvedPrefix(raw, "target")) {
                continue;
              }
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
        case MESSAGE -> {
          String raw = ActionPlaceholderSanitizer.substitute(cmd.payload(), tokens);
          if (ActionPlaceholderSanitizer.containsUnresolvedPrefix(raw, "target")) {
            return;
          }
          Object pObj = tokens.get("player");
          if (pObj == null) pObj = tokens.get("violator");
          if (pObj instanceof UUID pid) {
            dispatchPlayerMessage(pid, raw);
          } else {
            // Broadcast to all participants
            for (UUID pid : participants) {
              dispatchPlayerMessage(pid, raw);
            }
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
      RTP.log(Level.FINE, "[action] dispatching console command: " + commandLine);
      accessor.executeCommand(new UUID(0, 0), commandLine);
    }
  }

  private void dispatchPlayerCommand(UUID playerId, String commandLine) {
    RTPServerAccessor accessor = RTP.serverAccessor;
    if (accessor != null && commandLine != null && !commandLine.isBlank()) {
      RTP.log(Level.FINE, "[action] dispatching player command for " + playerId + ": " + commandLine);
      // If a command begins with 'msg ' or 'tell ' without a target player, fallback to sending message directly
      String trimmed = commandLine.trim();
      if ((trimmed.startsWith("msg ") || trimmed.startsWith("tell ")) && !trimmed.contains("[player]")) {
        String[] parts = trimmed.split("\\s+", 3);
        // parts[0] is 'msg' or 'tell'. If parts.length < 3, there's no recipient before the message text.
        // e.g. "msg &aArrived near spawn." -> parts = ["msg", "&aArrived", "near spawn."]
        // If parts[1] starts with formatting/color (&, §) or doesn't look like a player name, send message directly.
        if (parts.length > 1 && (parts[1].startsWith("&") || parts[1].startsWith("§"))) {
          String msgText = trimmed.substring(parts[0].length()).trim();
          accessor.sendMessage(playerId, msgText);
          return;
        }
      }
      accessor.executeCommand(playerId, commandLine);
    }
  }

  private void dispatchPlayerMessage(UUID playerId, String message) {
    RTPServerAccessor accessor = RTP.serverAccessor;
    if (accessor != null && message != null && !message.isBlank()) {
      accessor.sendMessage(playerId, message);
    }
  }
}
