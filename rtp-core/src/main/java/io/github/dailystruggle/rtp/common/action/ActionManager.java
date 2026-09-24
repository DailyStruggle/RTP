package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ActionGateContext;
import io.github.dailystruggle.rtp.api.action.ActionService;
import io.github.dailystruggle.rtp.api.action.ActionSession;
import io.github.dailystruggle.rtp.api.action.ActionSessionResult;
import io.github.dailystruggle.rtp.api.group.AnchorSource;
import io.github.dailystruggle.rtp.api.group.GroupPlacementRequest;
import io.github.dailystruggle.rtp.api.group.GroupPlacementService;
import io.github.dailystruggle.rtp.api.group.GroupProfileSpec;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Core implementation of {@link ActionService} (ADR-093).
 */
public final class ActionManager implements ActionService {

  private final Map<String, ActionDefinition> definitions = new ConcurrentHashMap<>();
  private final Map<UUID, ActionSessionImpl> activeSessions = new ConcurrentHashMap<>();
  private final Map<UUID, UUID> participantToSession = new ConcurrentHashMap<>();
  private final Map<String, Predicate<ActionGateContext>> externalPredicates = new ConcurrentHashMap<>();
  private final Map<String, java.util.Queue<PrevalidatedActionPlacement>> actionCaches = new ConcurrentHashMap<>();

  /** Holds a pre-validated candidate placement awaiting dispatch with live reservations (ADR-097). */
  public static final class PrevalidatedActionPlacement {
    final Map<UUID, RTPLocation> placements;
    final int anchorX;
    final int anchorZ;

    public PrevalidatedActionPlacement(Map<UUID, RTPLocation> placements, int anchorX, int anchorZ) {
      this.placements = placements;
      this.anchorX = anchorX;
      this.anchorZ = anchorZ;
    }

    public Map<UUID, RTPLocation> placements() {
      return placements;
    }

    public void release() {
      for (RTPLocation loc : placements.values()) {
        if (loc != null && loc.getReservation() != null) {
          try {
            loc.getReservation().close();
          } catch (Throwable ignored) {
          }
        }
      }
    }
  }

  public ActionManager() {}

  /**
   * Clears and releases all cached pre-validated placements across all actions (ADR-097, S-002).
   */
  public void clearCaches() {
    for (java.util.Queue<PrevalidatedActionPlacement> queue : actionCaches.values()) {
      PrevalidatedActionPlacement item;
      while ((item = queue.poll()) != null) {
        item.release();
      }
    }
    actionCaches.clear();
  }

  /**
   * Offers a pre-validated candidate placement into the action's cache if space permits (ADR-097).
   * If the cache is full, the offered placement is released immediately to prevent ticket leaks (S-002).
   */
  public boolean offerCachedPlacement(String actionId, PrevalidatedActionPlacement placement) {
    if (actionId == null || placement == null) return false;
    ActionDefinition def = definitions.get(actionId.trim().toLowerCase());
    int maxCapacity = (def != null) ? def.placement().cacheSize() : 0;
    if (maxCapacity <= 0) {
      placement.release();
      return false;
    }
    java.util.Queue<PrevalidatedActionPlacement> queue =
        actionCaches.computeIfAbsent(actionId.trim().toLowerCase(), k -> new java.util.concurrent.ConcurrentLinkedQueue<>());
    if (queue.size() < maxCapacity) {
      return queue.offer(placement);
    }
    placement.release();
    return false;
  }

  /**
   * Returns the count of pre-validated placements currently held in cache for the specified action.
   */
  public int getCachedPlacementCount(String actionId) {
    if (actionId == null) return 0;
    java.util.Queue<PrevalidatedActionPlacement> queue = actionCaches.get(actionId.trim().toLowerCase());
    return (queue != null) ? queue.size() : 0;
  }

  /**
   * Registers or updates an action definition in the registry, and registers its command if configured.
   */
  public void registerAction(ActionDefinition definition) {
    if (definition != null) {
      definitions.put(definition.id().toLowerCase(), definition);
      if (definition.command().isConfigured() && RTP.serverAccessor != null) {
        try {
          io.github.dailystruggle.rtp.common.commands.action.ActionCommand actionCmd =
              new io.github.dailystruggle.rtp.common.commands.action.ActionCommand(definition);
          List<String> names = new ArrayList<>();
          names.add(definition.command().name());
          names.addAll(definition.command().aliases());
          RTP.serverAccessor.registerCommands(actionCmd, names.toArray(new String[0]));
        } catch (Exception e) {
          RTP.log(Level.WARNING, "[RTP Action] Failed registering command for action " + definition.id() + ": " + e.getMessage());
        }
      }
    }
  }

  /**
   * Retrieves an action definition by id, if present.
   */
  public Optional<ActionDefinition> getAction(String id) {
    if (id == null) return Optional.empty();
    return Optional.ofNullable(definitions.get(id.toLowerCase()));
  }

  /**
   * Clears all registered definitions (e.g. before reload).
   */
  public void clearDefinitions() {
    definitions.clear();
  }

  @Override
  public Set<String> getActionIds() {
    return Collections.unmodifiableSet(definitions.keySet());
  }

  @Override
  public void registerPredicate(String name, Predicate<ActionGateContext> predicate) {
    if (name != null && predicate != null) {
      externalPredicates.put(name.trim().toLowerCase(), predicate);
    }
  }

  @Override
  public Optional<ActionSession> getSession(UUID sessionId) {
    if (sessionId == null) return Optional.empty();
    return Optional.ofNullable(activeSessions.get(sessionId));
  }

  @Override
  public Optional<ActionSession> getSessionForParticipant(UUID participantId) {
    if (participantId == null) return Optional.empty();
    UUID sId = participantToSession.get(participantId);
    if (sId == null) return Optional.empty();
    return getSession(sId);
  }

  @Override
  public void disarm(UUID sessionId) {
    if (sessionId == null) return;
    ActionSessionImpl session = activeSessions.get(sessionId);
    if (session != null) {
      session.disarm();
    }
  }

  /**
   * Startup orphan cleanup (ADR-093 Section 4).
   * Sweeps and removes lingering rtp_session_* tags and ephemeral objectives.
   */
  public void sweepStartupOrphans() {
    RTPServerAccessor accessor = RTP.serverAccessor;
    if (accessor == null) return;
    try {
      accessor.executeCommand(new UUID(0, 0), "scoreboard objectives remove rtp_session_id");
      accessor.executeCommand(new UUID(0, 0), "scoreboard objectives remove rtp_time_left");
      accessor.executeCommand(new UUID(0, 0), "scoreboard objectives remove rtp_violations");
      accessor.executeCommand(new UUID(0, 0), "scoreboard objectives remove rtp_in_bounds");
      accessor.executeCommand(new UUID(0, 0), "scoreboard objectives remove rtp_dist_sq");
      accessor.executeCommand(new UUID(0, 0), "scoreboard objectives remove rtp_alive");
    } catch (Exception e) {
      RTP.log(Level.WARNING, "[RTP Action] Failed sweeping startup orphan scoreboards: " + e.getMessage());
    }
  }

  /**
   * Tick update for all active sessions.
   */
  public void tick() {
    for (ActionSessionImpl session : activeSessions.values()) {
      session.tick();
    }
  }

  @Override
  public CompletableFuture<ActionSessionResult> trigger(
      String actionId, List<UUID> participants, ActionContext context) {

    if (actionId == null || actionId.isBlank()) {
      return CompletableFuture.completedFuture(ActionSessionResult.failure("Action ID cannot be null or blank"));
    }
    if (participants == null || participants.isEmpty()) {
      return CompletableFuture.completedFuture(ActionSessionResult.failure("Participants cannot be null or empty"));
    }
    final ActionContext effectiveContext = (context != null) ? context : ActionContext.EMPTY;

    ActionDefinition def = definitions.get(actionId.trim().toLowerCase());
    if (def == null) {
      return CompletableFuture.completedFuture(
          ActionSessionResult.failure("Action definition not found: " + actionId));
    }

    // Verify none of the participants are currently in an active action session
    for (UUID pid : participants) {
      if (participantToSession.containsKey(pid)) {
        return CompletableFuture.completedFuture(
            ActionSessionResult.failure("Participant is already in an active session: " + pid));
      }
    }

    // Spatial Placement via Subspace Group Engine (ADR-095)
    GroupPlacementService groupService = RTP.groupPlacementService;
    if (groupService == null) {
      return CompletableFuture.completedFuture(
          ActionSessionResult.failure("GroupPlacementService is not available"));
    }

    final ActionDefinition.PlacementSpec pSpec = def.placement();
    final Region parentRegion = (pSpec.region() != null)
        ? RTP.selectionAPI.getRegion(pSpec.region())
        : null;

    // Check if a pre-validated placement is cached for this action (ADR-097)
    PrevalidatedActionPlacement cached = pollCachedPlacement(actionId, participants.size());
    if (cached != null) {
      return revalidateAndApply(cached, def, participants, effectiveContext, parentRegion)
          .thenCompose(res -> {
            if (res.success()) {
              return CompletableFuture.completedFuture(res);
            }
            // If cached placement was invalidated upon recheck, fall back to live placement
            return executeLivePlacement(def, participants, effectiveContext, groupService, parentRegion);
          });
    }

    return executeLivePlacement(def, participants, effectiveContext, groupService, parentRegion);
  }

  /**
   * Polls a pre-validated candidate placement from the action cache matching participant count.
   */
  private PrevalidatedActionPlacement pollCachedPlacement(String actionId, int participantCount) {
    java.util.Queue<PrevalidatedActionPlacement> queue = actionCaches.get(actionId.trim().toLowerCase());
    if (queue == null) return null;
    PrevalidatedActionPlacement p;
    while ((p = queue.poll()) != null) {
      if (p.placements().size() >= participantCount) {
        return p;
      }
      // Wrong size or stale, release chunk tickets immediately (S-002)
      p.release();
    }
    return null;
  }

  /**
   * Revalidates a cached placement prior to participant dispatch (ADR-097, S-001, S-003, S-005).
   * Verifies resident block standability and checks live external claim verifiers off-tick.
   */
  private CompletableFuture<ActionSessionResult> revalidateAndApply(
      PrevalidatedActionPlacement cached,
      ActionDefinition def,
      List<UUID> participants,
      ActionContext effectiveContext,
      Region parentRegion) {

    List<RTPLocation> locList = new ArrayList<>(cached.placements().values());
    List<CompletableFuture<Boolean>> verifierChecks = new ArrayList<>(participants.size());

    for (int i = 0; i < participants.size(); i++) {
      RTPLocation loc = locList.get(i);
      // 1. Resident block standability recheck
      if (parentRegion != null && parentRegion.candidateValidator() != null) {
        try {
          var standable = parentRegion.candidateValidator().validate(loc.x(), loc.z());
          if (standable == null || Math.abs(standable.coords().y() - loc.y()) > 1) {
            cached.release();
            return CompletableFuture.completedFuture(
                ActionSessionResult.failure("Cached placement block column invalidated"));
          }
        } catch (Throwable t) {
          cached.release();
          return CompletableFuture.completedFuture(
              ActionSessionResult.failure("Candidate validator threw during recheck: " + t.getMessage()));
        }
      }

      // 2. External claim verification recheck (S-003)
      RTPCoords coords = new RTPCoords(loc.world().name(), loc.x(), loc.y(), loc.z());
      verifierChecks.add(
          io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers.checkGlobalRegionVerifiers(coords));
    }

    // Accumulate verifier check results asynchronously without calling .join() (S-005 non-blocking)
    CompletableFuture<Boolean> allVerifiersPass =
        CompletableFuture.completedFuture(Boolean.TRUE);
    for (CompletableFuture<Boolean> check : verifierChecks) {
      allVerifiersPass =
          allVerifiersPass.thenCombine(check, (accum, pass) -> accum && Boolean.TRUE.equals(pass));
    }

    return allVerifiersPass
        .thenApply(
            passed -> {
              if (!Boolean.TRUE.equals(passed)) {
                // Learn claim hazard dynamically in spatial memory (ADR-079, ADR-095)
                if (parentRegion != null
                    && parentRegion.getShape()
                        instanceof
                        io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes
                            .MemoryShape<?> memShape) {
                  for (RTPLocation rejectedLoc : locList) {
                    int cx = rejectedLoc.x() >> 4;
                    int cz = rejectedLoc.z() >> 4;
                    long locKey = memShape.xzToLocation(cx, cz);
                    memShape.addBadChunk(
                        locKey,
                        io.github.dailystruggle.rtp.common.selection.region.LocationGenerator
                            .FailTypes.safetyExternal);
                  }
                }
                cached.release();
                return ActionSessionResult.failure("Cached placement slot rejected by verifier");
              }

              // Revalidation passed! Assign cached slots to participants and start session
              UUID sessionId = UUID.randomUUID();
              Map<UUID, int[]> assignedSlots = new HashMap<>();
              String worldName = locList.get(0).world().name();

              for (int i = 0; i < participants.size(); i++) {
                UUID pid = participants.get(i);
                RTPLocation loc = locList.get(i);
                assignedSlots.put(pid, new int[] {loc.x(), loc.y(), loc.z()});
              }

              ActionSessionImpl session =
                  new ActionSessionImpl(
                      sessionId,
                      def,
                      participants,
                      effectiveContext,
                      assignedSlots,
                      worldName,
                      cached.anchorX,
                      cached.anchorZ,
                      parentRegion,
                      this::handleDisarm,
                      externalPredicates);

              activeSessions.put(sessionId, session);
              for (UUID pid : participants) {
                participantToSession.put(pid, sessionId);
              }

              session.arm();
              session.triggerStart();
              return ActionSessionResult.success(sessionId);
            })
        .exceptionally(
            ex -> {
              cached.release();
              return ActionSessionResult.failure("Revalidation error: " + ex.getMessage());
            });
  }

  /**
   * Executes live spatial placement via the subspace group engine with bounded retries.
   */
  private CompletableFuture<ActionSessionResult> executeLivePlacement(
      ActionDefinition def,
      List<UUID> participants,
      ActionContext effectiveContext,
      GroupPlacementService groupService,
      Region parentRegion) {

    ActionDefinition.PlacementSpec pSpec = def.placement();
    GroupProfileSpec profile =
        GroupProfileSpec.of(
            pSpec.shapeName(),
            pSpec.radius(),
            pSpec.minSeparation(),
            pSpec.elevationTolerance(),
            Math.max(1, participants.size()),
            pSpec.retries());

    AnchorSource anchorSource = resolveAnchorSource(pSpec, effectiveContext);
    GroupPlacementRequest request =
        GroupPlacementRequest.of(pSpec.region(), profile, participants, anchorSource);

    return groupService
        .place(request)
        .thenApply(
            result -> {
              if (!result.isSuccess() || result.placements().isEmpty()) {
                return ActionSessionResult.failure(
                    "Spatial subspace placement failed: " + result.reason());
              }

              UUID sessionId = UUID.randomUUID();
              Map<UUID, int[]> assignedSlots = new HashMap<>();
              String worldName = null;
              int minX = Integer.MAX_VALUE;
              int minZ = Integer.MAX_VALUE;
              int maxX = Integer.MIN_VALUE;
              int maxZ = Integer.MIN_VALUE;

              for (Map.Entry<UUID, RTPLocation> entry : result.placements().entrySet()) {
                UUID pid = entry.getKey();
                RTPLocation loc = entry.getValue();
                if (loc != null && loc.world() != null) {
                  int wx = loc.x();
                  int wy = loc.y();
                  int wz = loc.z();
                  assignedSlots.put(pid, new int[] {wx, wy, wz});
                  worldName = loc.world().name();
                  minX = Math.min(minX, wx);
                  minZ = Math.min(minZ, wz);
                  maxX = Math.max(maxX, wx);
                  maxZ = Math.max(maxZ, wz);
                }
              }

              int anchorX = (minX + maxX) / 2;
              int anchorZ = (minZ + maxZ) / 2;

              ActionSessionImpl session =
                  new ActionSessionImpl(
                      sessionId,
                      def,
                      participants,
                      effectiveContext,
                      assignedSlots,
                      worldName,
                      anchorX,
                      anchorZ,
                      parentRegion,
                      this::handleDisarm,
                      externalPredicates);

              activeSessions.put(sessionId, session);
              for (UUID pid : participants) {
                participantToSession.put(pid, sessionId);
              }

              session.arm();
              session.triggerStart();

              return ActionSessionResult.success(sessionId);
            });
  }

  /**
   * Resolves the {@link AnchorSource} for an action from its declared {@code placement.anchor}
   * selector (ADR-095), sourcing live coordinates from the invocation context and static
   * landmark coordinates from the placement parameters. Falls back to the region queue.
   */
  private static AnchorSource resolveAnchorSource(
      ActionDefinition.PlacementSpec pSpec, ActionContext ctx) {
    Object rawType = pSpec.parameters().get("anchor");
    String type = (rawType == null) ? "regionqueue" : rawType.toString().trim().toLowerCase();
    switch (type) {
      case "claimhazard":
      case "claim":
      case "nearclaim":
        return AnchorSource.claimHazard();
      case "fixed":
      case "location":
      case "landmark": {
        RTPCoords c = readCoords(ctx, pSpec);
        return (c != null) ? AnchorSource.fixed(c) : AnchorSource.regionQueue();
      }
      case "entity":
      case "player":
      case "nearplayer": {
        RTPCoords c = readCoords(ctx, pSpec);
        if (c == null) return AnchorSource.regionQueue();
        final RTPCoords anchor = c;
        return AnchorSource.entity(() -> anchor);
      }
      default:
        return AnchorSource.regionQueue();
    }
  }

  /**
   * Reads anchor coordinates, preferring the live invocation context (e.g. a random target
   * player's location for {@code nearplayer}) over static placement parameters (a landmark).
   */
  private static RTPCoords readCoords(ActionContext ctx, ActionDefinition.PlacementSpec pSpec) {
    Map<String, Object> meta = (ctx != null) ? ctx.metadata() : Collections.emptyMap();
    Map<String, Object> params = pSpec.parameters();
    Integer x = readInt(meta.get("anchorX"));
    Integer y = readInt(meta.get("anchorY"));
    Integer z = readInt(meta.get("anchorZ"));
    Object w = meta.get("anchorWorld");
    if (x == null) x = readInt(params.get("anchorX"));
    if (y == null) y = readInt(params.get("anchorY"));
    if (z == null) z = readInt(params.get("anchorZ"));
    if (w == null) w = params.get("anchorWorld");
    if (x == null || z == null) return null;
    String worldName =
        (w != null) ? w.toString() : ((pSpec.region() != null) ? pSpec.region() : "world");
    return new RTPCoords(worldName, x, (y != null) ? y : 0, z);
  }

  private static Integer readInt(Object o) {
    if (o == null) return null;
    if (o instanceof Number n) return n.intValue();
    try {
      return Integer.parseInt(o.toString().trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private void handleDisarm(UUID sessionId) {
    ActionSessionImpl session = activeSessions.remove(sessionId);
    if (session != null) {
      for (UUID pid : session.participants()) {
        participantToSession.remove(pid);
      }
    }
  }
}
