package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.bit.Vocabulary;

import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ActionGateContext;
import io.github.dailystruggle.rtp.api.action.ActionSession;
import io.github.dailystruggle.rtp.api.action.ConfinementBoundary;
import io.github.dailystruggle.rtp.api.event.PlayerMoveEvent;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
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
import java.util.regex.Pattern;

/**
 * Implementation of an active scripted action session (ADR-093).
 */
public final class ActionSessionImpl implements ActionSession {

  public static final Vocabulary ACTION_NAMES = new Vocabulary("action name", List.of("PULL_BACK", "DISARM", "ELIMINATE"));
  public static final Vocabulary MANAGED_OBJECTIVES = new Vocabulary("managed scoreboard objective", List.of(
      "rtp_session_id", "rtp_time_left", "rtp_violations", "rtp_in_bounds", "rtp_dist_sq", "rtp_alive"));

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final String FOR_EACH_PLAYER_SCOPE = "_for_each_player_scope";

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
  private final Map<UUID, Integer> participantBreachType = new ConcurrentHashMap<>();
  private final Map<UUID, Long> lastDamageSeconds = new ConcurrentHashMap<>();
  private final AtomicBoolean active = new AtomicBoolean(true);
  private final AtomicBoolean ending = new AtomicBoolean(false);
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean orphanReported = new AtomicBoolean(false);
  private final java.util.Set<UUID> pullBackInFlight = ConcurrentHashMap.newKeySet();
  private final Consumer<UUID> onDisarmCallback;
  private final Map<String, Predicate<ActionGateContext>> externalPredicates;
  private final Simulation simulation;

  interface Simulation {
    long currentTimeMillis();
    void schedule(long delayTicks, Runnable task);
    void dispatch(String command);
    void note(String message);
    String playerName(UUID participantId);
    GateTrace gateTrace();
  }

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
    this(sessionId, definition, participants, context, assignedSlots, worldName, anchorX, anchorZ,
        parentRegion, onDisarmCallback, externalPredicates, null);
  }

  ActionSessionImpl(
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
      Map<String, Predicate<ActionGateContext>> externalPredicates,
      Simulation simulation) {

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
    this.simulation = simulation;

    this.startTimeMillis = currentTimeMillis();
    this.durationSeconds = definition.confinement().durationSeconds();

    for (UUID p : participants) {
      violations.put(p, new AtomicInteger(0));
    }
  }

  private long currentTimeMillis() {
    return simulation == null ? System.currentTimeMillis() : simulation.currentTimeMillis();
  }

  private RTPServerAccessor accessor() {
    return simulation == null ? RTPRuntime.serverAccessor() : null;
  }

  private GateTrace gateTrace() {
    return simulation == null ? null : simulation.gateTrace();
  }

  private String playerName(UUID participantId) {
    if (simulation != null) return simulation.playerName(participantId);
    RTPServerAccessor accessor = accessor();
    io.github.dailystruggle.rtp.api.entity.RTPPlayer player = accessor == null ? null : accessor.getPlayer(participantId);
    return player == null ? null : player.name();
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
    return Math.max(0L, (currentTimeMillis() - startTimeMillis) / 1000L);
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
    arm(memoryTrackerLifespanMillis());
  }

  void arm(long reaperWindowMillis) {
    if (simulation != null) return;
    MemoryTracker.track(this, "ActionSession-" + sessionId, reaperWindowMillis, orphanReaperCallback());

    // If placement is disabled or worldName is null, movement confinement is not monitored
    if (!definition.placement().enabled() || worldName == null) {
      return;
    }

    for (UUID participant : participants) {
      AutoCloseable reg = accessor() != null
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
    tick(currentTimeMillis());
  }

  void tick(long nowMillis) {
    if (!active.get()) return;
    checkContinuousConfinement();
    updateDynamicScoreboards();
    if (durationSeconds >= 0L && remainingSeconds() <= 0L) {
      triggerExpire();
    }
  }

  private long memoryTrackerLifespanMillis() {
    return durationSeconds > 0L ? (durationSeconds + 30L) * 1000L : 30_000L;
  }

  private Runnable orphanReaperCallback() {
    AtomicBoolean sessionActive = active;
    AtomicBoolean sessionStarted = started;
    AtomicBoolean reported = orphanReported;
    String actionId = definition.id();
    return () -> {
      if (sessionActive.get() && sessionStarted.get() && reported.compareAndSet(false, true)) {
        ActionCbitCounters.recordOrphanedSession(actionId);
      }
    };
  }

  private void checkContinuousConfinement() {
    if (!definition.placement().enabled() || worldName == null) return;
    RTPServerAccessor accessor = accessor();
    if (accessor == null) return;

    double damageAmount = definition.confinement().damageAmount();
    double damageBuffer = definition.confinement().damageBuffer();
    long damageIntervalSec = definition.confinement().damageIntervalSeconds();
    double maxDistanceOutside = definition.confinement().maxDistanceOutside();
    long nowSec = currentTimeMillis() / 1000L;

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
        if (damageAmount > 0.0 && distOutside > damageBuffer) {
          long lastDmg = lastDamageSeconds.getOrDefault(pid, 0L);
          if (nowSec - lastDmg >= damageIntervalSec) {
            lastDamageSeconds.put(pid, nowSec);
            accessor.damagePlayer(pid, damageAmount);
          }
        }
      }
    }
  }

  private double calculateDistanceOutside(double px, double pz) {
    ConfinementBoundary boundary = definition.confinement().boundary();
    if (boundary == ConfinementBoundary.NONE) {
      return 0.0;
    }
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
    if (pullBackInFlight.contains(pid)) return;

    int breachType = getBoundaryBreachType(event.worldName(), event.toX(), event.toZ());
    Integer prevBreach = participantBreachType.put(pid, breachType);
    participantInBoundsState.put(pid, breachType == 0);

    if (breachType != 0) {
      double distOutside = calculateDistanceOutside(event.toX(), event.toZ());
      if (prevBreach == null || prevBreach != breachType) {
        int breachCount = violations.computeIfAbsent(pid, k -> new AtomicInteger(0)).incrementAndGet();
        triggerBoundaryViolation(pid, event.worldName(), event.toX(), event.toY(), event.toZ(), breachCount);
      }

      // Hard boundary limit enforcement: check relative distance or static initial boundary ceiling
      double maxDistanceOutside = definition.confinement().maxDistanceOutside();
      double distFromInitial = calculateDistanceOutsideInitial(event.toX(), event.toZ());
      if (maxDistanceOutside > 0.0 && (distOutside > maxDistanceOutside || distFromInitial > maxDistanceOutside)) {
        executeOutsideActions(pid);
      }

      // Declarative damage check on move
      double damageAmount = definition.confinement().damageAmount();
      if (damageAmount > 0.0 && distOutside > definition.confinement().damageBuffer()) {
        long nowSec = currentTimeMillis() / 1000L;
        long lastDmg = lastDamageSeconds.getOrDefault(pid, 0L);
        if (nowSec - lastDmg >= definition.confinement().damageIntervalSeconds()) {
          lastDamageSeconds.put(pid, nowSec);
          RTPServerAccessor accessor = accessor();
          if (accessor != null) {
            accessor.damagePlayer(pid, damageAmount);
          }
        }
      }
    }
  }

  private double calculateDistanceOutsideInitial(double px, double pz) {
    ConfinementBoundary boundary = definition.confinement().boundary();
    if (boundary == ConfinementBoundary.NONE) {
      return 0.0;
    }
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
    String name = playerName(pid);
    if (name != null) {
      tokens.put("player_name", name);
      tokens.put("violator_name", name);
    }

    for (ActionDefinition.CommandAction cmd : actions) {
      if (cmd != null) {
        executeGuardedAction(cmd, tokens);
      }
    }
  }

  private int getBoundaryBreachType(String moveWorld, int x, int z) {
    if (worldName != null && moveWorld != null && !worldName.equalsIgnoreCase(moveWorld)) {
      return 1; // WRONG_WORLD
    }

    ConfinementBoundary boundary = definition.confinement().boundary();
    if (boundary == null || boundary == ConfinementBoundary.NONE) return 0;
    return switch (boundary) {
      case REGION -> {
        if (parentRegion == null) yield 0;
        yield parentRegion.getShape().contains(x, z) ? 0 : 3;
      }
      case SUBSPACE -> {
        double rBlocks = currentBoundaryRadius();
        yield (Math.abs(x - anchorX) <= rBlocks && Math.abs(z - anchorZ) <= rBlocks) ? 0 : 3;
      }
      case LEASH -> {
        double leash = currentBoundaryRadius();
        double dx = (double) x - (double) anchorX;
        double dz = (double) z - (double) anchorZ;
        yield ((dx * dx + dz * dz) <= (leash * leash)) ? 0 : 3;
      }
      case SHAPE -> {
        double rBlocks = currentBoundaryRadius();
        int centerR = definition.confinement().centerRadius() > 0
            ? definition.confinement().centerRadius()
            : definition.placement().centerRadius();
        double dx = (double) x - (double) anchorX;
        double dz = (double) z - (double) anchorZ;
        String sName = definition.confinement().shapeName();
        if ("CIRCLE".equalsIgnoreCase(sName)) {
          double distSq = dx * dx + dz * dz;
          if (centerR > 0 && distSq < (double) centerR * centerR) yield 2; // INNER_EXCLUSION
          if (distSq > rBlocks * rBlocks) yield 3; // OUTER_BOUNDARY
          yield 0;
        } else {
          double absX = Math.abs(dx);
          double absZ = Math.abs(dz);
          if (centerR > 0 && Math.max(absX, absZ) < centerR) yield 2; // INNER_EXCLUSION
          if (absX > rBlocks || absZ > rBlocks) yield 3; // OUTER_BOUNDARY
          yield 0;
        }
      }
      default -> 0;
    };
  }

  boolean checkInBounds(String moveWorld, int x, int z) {
    return getBoundaryBreachType(moveWorld, x, z) == 0;
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
    started.set(true);
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
    RTPServerAccessor accessor = accessor();
    if (accessor == null) return;

    // ADR-093 §4: Ensure dummy objectives exist once per plugin lifecycle
    if (SCOREBOARD_OBJECTIVES_INITIALIZED.compareAndSet(false, true)) {
      for (String objective : MANAGED_OBJECTIVES.values()) {
        accessor.ensureScoreboardObjective(objective, "dummy");
      }
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
    RTPServerAccessor accessor = accessor();
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
    RTPServerAccessor accessor = accessor();
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
          double dx = (double) loc.x() - (double) anchorX;
          double dz = (double) loc.z() - (double) anchorZ;
          long distSq = (long) (dx * dx + dz * dz);
          accessor.setScoreboardScore(pid, "rtp_dist_sq", (int) Math.min(Integer.MAX_VALUE, distSq));
        }
      }
    }
  }

  private void updateParticipantBreachScoreboard(UUID violatorId, int breachCount, double distSq) {
    RTPServerAccessor accessor = accessor();
    if (accessor == null || violatorId == null) return;
    accessor.setScoreboardScore(violatorId, "rtp_violations", breachCount);
    accessor.setScoreboardScore(violatorId, "rtp_in_bounds", 0);
    accessor.setScoreboardScore(violatorId, "rtp_dist_sq", (int) Math.min(Integer.MAX_VALUE, (long) distSq));
  }

  private void cleanupScoreboards() {
    RTPServerAccessor accessor = accessor();
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

    RTPServerAccessor accessor = accessor();
    if (accessor != null) {
      for (UUID participant : participants) {
        accessor.sendWorldBorder(
            participant, anchorX, anchorZ, initialSize, shrinkTo, shrinkOver, damageAmount, damageBuffer);
      }
    }
  }

  private void resetConfinementWorldBorder() {
    RTPServerAccessor accessor = accessor();
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
    if (!ending.compareAndSet(false, true)) return;
    try {
      executeTerminalSteps(definition.lifecycle().onExpire(), null, Collections.emptyMap());
    } catch (Exception e) {
      RTPRuntime.log(Level.WARNING, "[RTP Action] Error executing onExpire lifecycle steps", e);
    } finally {
      disarm();
    }
  }

  public void triggerCancel(UUID participantId) {
    if (!ending.compareAndSet(false, true)) return;
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
      executeTerminalSteps(steps, participantId, extra);
    } catch (Exception e) {
      RTPRuntime.log(Level.WARNING, "[RTP Action] Error executing onCancel lifecycle steps", e);
    } finally {
      disarm();
    }
  }

  public void triggerDeath(UUID victimId, UUID winnerId) {
    if (!ending.compareAndSet(false, true)) return;
    try {
      Map<String, Object> extra = new HashMap<>();
      if (victimId != null) extra.put("victim", victimId);
      if (winnerId != null) extra.put("winner", winnerId);

      executeTerminalSteps(definition.lifecycle().onDeath(), victimId, extra);
    } catch (Exception e) {
      RTPRuntime.log(Level.WARNING, "[RTP Action] Error executing onDeath lifecycle steps", e);
    } finally {
      disarm();
    }
  }

  @Override
  public void pullBack(UUID participantId) {
    if (participantId == null) return;
    if (!pullBackInFlight.add(participantId)) return;
    int[] slot = assignedSlots.get(participantId);
    if (slot == null) {
      pullBackInFlight.remove(participantId);
      return;
    }

    RTPServerAccessor accessor = accessor();
    if (accessor != null) {
      // Dispatch safe teleport back to slot without rubberbanding (ADR-093 §8, S-001, S-004, S-005)
      io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld(worldName);
      io.github.dailystruggle.rtp.api.entity.RTPPlayer player = accessor.getPlayer(participantId);
      if (world != null && player != null && player.isOnline()) {
        io.github.dailystruggle.rtp.api.world.RTPLocation targetLoc =
            new io.github.dailystruggle.rtp.api.world.RTPLocation(world, slot[0], slot[1], slot[2]);
        player.setLocation(targetLoc).whenComplete((success, ex) -> {
          try {
            if (ex != null) {
              RTPRuntime.log(Level.WARNING, "[RTP] Action pull-back failed for player " + participantId, ex);
            } else if (Boolean.FALSE.equals(success)) {
              RTPRuntime.log(Level.WARNING, "[RTP] Action pull-back rejected for player " + participantId);
            } else {
              participantBreachType.put(participantId, 0);
              participantInBoundsState.put(participantId, true);
            }
          } finally {
            pullBackInFlight.remove(participantId);
          }
        });
      } else {
        pullBackInFlight.remove(participantId);
      }
    } else {
      pullBackInFlight.remove(participantId);
    }
  }

  public void eliminate(UUID participantId) {
    if (participantId == null) return;
    RTPServerAccessor accessor = accessor();
    if (accessor != null) {
      accessor.executeCommand(new UUID(0, 0), "gamemode survival " + participantId);
    }
    pullBack(participantId);
    int alive = 0;
    UUID remaining = null;
    for (UUID pid : participants) {
      if (!pid.equals(participantId)) {
        if (accessor != null) {
          io.github.dailystruggle.rtp.api.entity.RTPPlayer p = accessor.getPlayer(pid);
          if (p != null && p.isOnline()) {
            alive++;
            remaining = pid;
          }
        } else {
          alive++;
          remaining = pid;
        }
      }
    }
    if (alive <= 1 && participants.size() > 1) {
      triggerDeath(participantId, remaining);
    }
  }

  @Override
  public void disarm() {
    ending.set(true);
    if (!active.compareAndSet(true, false)) return;

    pullBackInFlight.clear();
    participantBreachType.clear();
    if (simulation == null) MemoryTracker.untrack(this);
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
    executeLifecycleStepsWithTokens(steps, gateCtx, Collections.emptyMap(), false);
  }

  private void executeLifecycleSteps(
      List<ActionDefinition.LifecycleStep> steps, ActionGateContext gateCtx, boolean terminal) {
    executeLifecycleStepsWithTokens(steps, gateCtx, Collections.emptyMap(), terminal);
  }

  private void executeLifecycleStepsWithTokens(
      List<ActionDefinition.LifecycleStep> steps,
      ActionGateContext gateCtx,
      Map<String, Object> additionalTokens,
      boolean terminal) {

    if (steps == null || steps.isEmpty()) return;

    Map<String, Object> baseTokens = buildBaseTokens(additionalTokens, gateCtx);
    for (ActionDefinition.LifecycleStep step : steps) {
      executeStep(step, gateCtx, baseTokens, terminal);
    }
  }

  private void executeTerminalSteps(
      List<ActionDefinition.LifecycleStep> steps,
      UUID subject,
      Map<String, Object> additionalTokens) {
    if (steps == null || steps.isEmpty()) return;

    Map<String, Object> baseTokens = buildBaseTokens(additionalTokens, null);
    for (ActionDefinition.LifecycleStep step : steps) {
      if (step == null) continue;
      if (step.gateConfig().isEmpty()) {
        executeStep(step, null, baseTokens, true);
      } else if (subject != null) {
        executeStep(step, terminalGateContext(subject), baseTokens, true);
      } else {
        for (UUID participantId : participants) {
          if (!isOnlineParticipant(participantId)) continue;
          Map<String, Object> scopedTokens = new HashMap<>(baseTokens);
          scopedTokens.put("player", participantId);
          scopedTokens.put("player_uuid", participantId);
          scopedTokens.put(FOR_EACH_PLAYER_SCOPE, participantId);
          executeStep(step, terminalGateContext(participantId), scopedTokens, true);
        }
      }
    }
  }

  private boolean isOnlineParticipant(UUID participantId) {
    RTPServerAccessor accessor = accessor();
    if (accessor == null) return true;
    io.github.dailystruggle.rtp.api.entity.RTPPlayer player = accessor.getPlayer(participantId);
    return player != null && player.isOnline();
  }

  private ActionGateContext terminalGateContext(UUID participantId) {
    Double currentX = null;
    Double currentY = null;
    Double currentZ = null;
    Double anchorY = null;
    double distanceSq = Double.POSITIVE_INFINITY;
    int[] slot = assignedSlots.get(participantId);
    if (slot != null && slot.length >= 3) anchorY = (double) slot[1];

    RTPServerAccessor accessor = accessor();
    io.github.dailystruggle.rtp.api.entity.RTPPlayer player = accessor == null ? null : accessor.getPlayer(participantId);
    io.github.dailystruggle.rtp.api.world.RTPLocation location =
        player != null && player.isOnline() ? player.getLocation() : null;
    if (location != null) {
      currentX = (double) location.x();
      currentY = (double) location.y();
      currentZ = (double) location.z();
    } else if (slot != null && slot.length >= 3) {
      currentX = (double) slot[0];
      currentY = (double) slot[1];
      currentZ = (double) slot[2];
    }

    if (currentX != null && currentZ != null) {
      double dx = currentX - anchorX;
      double dz = currentZ - anchorZ;
      distanceSq = dx * dx + dz * dz;
    }
    return new ActionGateContext(
        sessionId,
        definition.id(),
        participantId,
        elapsedSeconds(),
        remainingSeconds(),
        getViolations(participantId),
        participantInBoundsState.getOrDefault(participantId, true),
        distanceSq,
        currentX,
        currentY,
        currentZ,
        (double) anchorX,
        anchorY,
        (double) anchorZ,
        participants.size(),
        context);
  }

  private Map<String, Object> buildBaseTokens(
      Map<String, Object> additionalTokens,
      ActionGateContext gateCtx) {
    Map<String, Object> baseTokens = new HashMap<>(context.metadata());
    if (additionalTokens != null) baseTokens.putAll(additionalTokens);
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
      String name = playerName(pid);
      if (name != null) {
        baseTokens.put("player_name_" + num, name);
        baseTokens.put("sender_name_" + num, name);
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

    return baseTokens;
  }

  private void executeStep(
      ActionDefinition.LifecycleStep step,
      ActionGateContext gateCtx,
      Map<String, Object> baseTokens,
      boolean terminal) {
    if (step == null) return;
    try {
      if (gateCtx != null
          && !GateEvaluator.evaluate(step.gateConfig(), gateCtx, externalPredicates, baseTokens, gateTrace())) {
        return;
      }

      if (step.delaySeconds() > 0L) {
        // Schedule delayed execution
        long delayTicks = Math.max(1L, step.delaySeconds() * 20L);
        Runnable delayedTask = () -> {
          if (!terminal && !active.get()) return;
          if (terminal) {
            RTPServerAccessor accessor = accessor();
            Object pObj = baseTokens.get("player");
            if (pObj == null) pObj = baseTokens.get("violator");
            if (pObj instanceof UUID pid && accessor != null) {
              io.github.dailystruggle.rtp.api.entity.RTPPlayer p = accessor.getPlayer(pid);
              if (p == null || !p.isOnline()) return;
            } else if (!participants.isEmpty() && accessor != null) {
              boolean anyOnline = false;
              for (UUID pid : participants) {
                io.github.dailystruggle.rtp.api.entity.RTPPlayer p = accessor.getPlayer(pid);
                if (p != null && p.isOnline()) {
                  anyOnline = true;
                  break;
                }
              }
              if (!anyOnline) return;
            }
          }
          try {
            // Re-evaluate the gate with current time and violation data, retaining the terminal snapshot.
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
                  currentGateCtx.anchorZ(),
                  currentGateCtx.participantCount(),
                  currentGateCtx.context());
            }
            if (currentGateCtx != null
                && !GateEvaluator.evaluate(step.gateConfig(), currentGateCtx, externalPredicates, baseTokens, gateTrace())) {
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
            RTPRuntime.log(Level.WARNING, "[RTP Action] Error executing delayed lifecycle step", ex);
          }
        };
        if (simulation == null) {
          RTPRuntime.scheduler().runTaskLater(delayedTask, delayTicks);
        } else {
          simulation.schedule(delayTicks, delayedTask);
        }
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
      RTPRuntime.log(Level.WARNING, "[RTP Action] Error evaluating lifecycle step", ex);
    }
  }

  private void executeGuardedAction(ActionDefinition.CommandAction cmd, Map<String, Object> tokens) {
    try {
      if (ActionPlaceholderSanitizer.hasMissingTarget(cmd.payload(), tokens)) {
        if (simulation != null) {
          simulation.note("Lifecycle command was omitted because its target was not specified: " + cmd.payload());
        } else {
          RTPRuntime.log(Level.FINE, "[RTP Action] Dropping lifecycle command due to missing target: " + cmd.payload());
        }
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
              RTPServerAccessor accessor = accessor();
              if (accessor != null) {
                io.github.dailystruggle.rtp.api.entity.RTPPlayer player = accessor.getPlayer(pid);
                if (player != null && !player.isOnline()) {
                  continue;
                }
              }
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
              if (simulation != null) simulation.note("Lifecycle command was omitted because a target placeholder was unresolved.");
              return;
            }
            if (ActionPlaceholderSanitizer.containsAnyUnresolvedPlaceholder(raw)) {
              if (simulation != null) {
                simulation.note("Lifecycle command was omitted because a placeholder was unresolved: " + raw);
              } else {
                ActionCbitCounters.recordUnresolvedPlaceholders(raw);
                RTPRuntime.log(Level.WARNING, "[action] dropping console command with unresolved placeholder: " + raw);
              }
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
            case "ELIMINATE" -> {
              Object pObj = tokens.get("violator");
              if (pObj == null) pObj = tokens.get("player");
              if (pObj instanceof UUID pid) {
                eliminate(pid);
              }
            }
            default -> {
              if (simulation != null) {
                simulation.note("Unknown action was omitted: " + actionName);
              } else {
                ActionCbitCounters.recordUnknownAction(actionName);
                RTPRuntime.log(Level.WARNING, "[RTP Action] Unknown action command: " + actionName);
              }
            }
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
        case MESSAGE_TARGET -> {
          String raw = ActionPlaceholderSanitizer.substitute(cmd.payload(), tokens);
          if (ActionPlaceholderSanitizer.containsUnresolvedPrefix(raw, "target")) {
            return;
          }
          Object targetObj = tokens.get("target_uuid");
          if (targetObj == null) targetObj = tokens.get("target");
          if (targetObj == null) targetObj = tokens.get("target_name");
          if (targetObj != null && !targetObj.toString().isBlank() && !targetObj.toString().equalsIgnoreCase("any")) {
            if (targetObj instanceof UUID tId) {
              dispatchPlayerMessage(tId, raw);
            } else if (targetObj instanceof String tName) {
              RTPServerAccessor accessor = accessor();
              if (accessor != null) {
                io.github.dailystruggle.rtp.api.entity.RTPPlayer tp = accessor.getPlayer(tName);
                if (tp != null) {
                  dispatchPlayerMessage(tp.uuid(), raw);
                }
              } else if (simulation != null) {
                simulation.note("Message (not sent to " + tName + "): " + raw);
              }
            }
          }
        }
        case FOR_EACH -> {
          Object scopedParticipant = tokens.get(FOR_EACH_PLAYER_SCOPE);
          List<UUID> scopedParticipants = scopedParticipant instanceof UUID pid ? List.of(pid) : participants;
          for (UUID participant : scopedParticipants) {
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
      if (simulation != null) {
        simulation.note("Command execution failed for step " + cmd.payload() + ": " + e.getMessage());
      } else {
        RTPRuntime.log(Level.WARNING, "[RTP Action] Command execution failed for step: " + cmd.payload(), e);
      }
    }
  }

  private void dispatchConsoleCommand(String commandLine) {
    if (simulation != null) {
      if (commandLine != null && !commandLine.isBlank()) simulation.dispatch(commandLine);
      return;
    }
    RTPServerAccessor accessor = accessor();
    if (accessor != null && commandLine != null && !commandLine.isBlank()) {
      ActionCbitCounters.recordUnresolvedPlaceholders(commandLine);
      RTPRuntime.log(Level.FINE, "[action] dispatching console command: " + commandLine);
      accessor.executeCommand(new UUID(0, 0), commandLine);
    }
  }

  private void dispatchPlayerCommand(UUID playerId, String commandLine) {
    if (simulation != null) {
      if (commandLine != null && !commandLine.isBlank()) simulation.dispatch(commandLine);
      return;
    }
    RTPServerAccessor accessor = accessor();
    if (accessor != null && commandLine != null && !commandLine.isBlank()) {
      ActionCbitCounters.recordUnresolvedPlaceholders(commandLine);
      RTPRuntime.log(Level.FINE, "[action] dispatching player command for " + playerId + ": " + commandLine);
      // If a command begins with 'msg ' or 'tell ' without a target player, fallback to sending message directly
      String trimmed = commandLine.trim();
      if ((trimmed.startsWith("msg ") || trimmed.startsWith("tell ")) && !trimmed.contains("[player]")) {
        String[] parts = WHITESPACE.split(trimmed, 3);
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
    if (simulation != null) {
      if (message != null && !message.isBlank()) simulation.note("Message (not sent): " + message);
      return;
    }
    RTPServerAccessor accessor = accessor();
    if (accessor != null && message != null && !message.isBlank()) {
      ActionCbitCounters.recordUnresolvedPlaceholders(message);
      accessor.sendMessage(playerId, message);
    }
  }
}
