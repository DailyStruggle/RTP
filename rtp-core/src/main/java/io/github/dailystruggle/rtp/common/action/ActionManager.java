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
  private final Map<String, java.util.Queue<ActionWaitQueueEntry>> waitQueues = new ConcurrentHashMap<>();

  /** Queued participant request awaiting action gate satisfaction (ADR-093). */
  public static final class ActionWaitQueueEntry {
    final List<UUID> participants;
    final ActionContext context;
    final CompletableFuture<ActionSessionResult> future;
    final long enqueuedAt;

    public ActionWaitQueueEntry(List<UUID> participants, ActionContext context, CompletableFuture<ActionSessionResult> future) {
      this.participants = List.copyOf(participants);
      this.context = (context != null) ? context : ActionContext.EMPTY;
      this.future = future;
      this.enqueuedAt = System.currentTimeMillis();
    }

    public List<UUID> participants() {
      return participants;
    }

    public ActionContext context() {
      return context;
    }

    public CompletableFuture<ActionSessionResult> future() {
      return future;
    }
  }

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

  /**
   * Default public constructor for ActionManager.
   */
  public ActionManager() {
    // Intentionally empty; fields initialized at point of declaration
  }

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
      registerActionCommand(definition);
      syncActionSubCmd();
    }
  }

  private void registerActionCommand(ActionDefinition definition) {
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

  private void syncActionSubCmd() {
    if (RTP.baseCommand != null) {
      io.github.dailystruggle.commandsapi.common.CommandsAPICommand actionSub =
          RTP.baseCommand.getCommandLookup().get("ACTION");
      if (actionSub == null) {
        actionSub = new io.github.dailystruggle.rtp.common.commands.action.ActionSubCmd(RTP.baseCommand);
        RTP.baseCommand.addSubCommand(actionSub);
        RTP.baseCommand.getCommandLookup().put("RUN", actionSub);
        RTP.baseCommand.getCommandLookup().put("TRIGGER", actionSub);
      }
      if (actionSub instanceof io.github.dailystruggle.rtp.common.commands.action.ActionSubCmd subCmd) {
        subCmd.syncActions();
      }
    }
  }

  /**
   * Re-registers all top-level action commands and synchronizes /rtp action subcommands.
   * Called during startup once commands are bound and after configuration reloads.
   */
  public void registerAllCommands() {
    for (ActionDefinition definition : definitions.values()) {
      registerActionCommand(definition);
    }
    syncActionSubCmd();
  }

  /**
   * Retrieves an action definition by id, if present.
   */
  @Override
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
   * Dispatches a group action for the specified players (ADR-093 / ADR-095).
   *
   * @param players  list of player UUIDs
   * @param actionId target action id
   * @return future completed with session result
   */
  public CompletableFuture<ActionSessionResult> dispatchGroupAction(List<UUID> players, String actionId) {
    return dispatchGroupAction(players, actionId, ActionContext.EMPTY);
  }

  /**
   * Dispatches a group action for the specified players with a given context.
   *
   * @param players  list of player UUIDs
   * @param actionId target action id
   * @param context  action context
   * @return future completed with session result
   */
  public CompletableFuture<ActionSessionResult> dispatchGroupAction(
      List<UUID> players, String actionId, ActionContext context) {
    if (players == null || players.isEmpty()) {
      return CompletableFuture.completedFuture(ActionSessionResult.failure("Players cannot be null or empty"));
    }
    return trigger(actionId, players, context);
  }

  @Override
  public boolean cancelParticipant(UUID participantId, String actionId) {
    if (participantId == null) return false;
    boolean cancelled = false;

    // 1. Check and remove from wait-queues (cancellation prior to teleportation)
    for (Map.Entry<String, java.util.Queue<ActionWaitQueueEntry>> qEntry : waitQueues.entrySet()) {
      String qActionId = qEntry.getKey();
      if (actionId != null && !actionId.isBlank() && !qActionId.equalsIgnoreCase(actionId.trim())) {
        continue;
      }
      java.util.Queue<ActionWaitQueueEntry> queue = qEntry.getValue();
      if (queue == null || queue.isEmpty()) continue;

      ActionDefinition def = definitions.get(qActionId);
      List<ActionWaitQueueEntry> toRemove = new ArrayList<>();
      for (ActionWaitQueueEntry entry : queue) {
        if (entry.participants.contains(participantId)) {
          toRemove.add(entry);
        }
      }

      for (ActionWaitQueueEntry entry : toRemove) {
        queue.remove(entry);
        cancelled = true;
        entry.future.complete(ActionSessionResult.failure("CANCELLED"));

        // Trigger onCancel lifecycle steps if defined
        if (def != null && def.lifecycle() != null && !def.lifecycle().onCancel().isEmpty()) {
          ActionGateContext gateCtx = new ActionGateContext(
              UUID.randomUUID(),
              def.id(),
              participantId,
              0L,
              def.confinement().durationSeconds(),
              0,
              true,
              0.0,
              null, null, null, null, null, null,
              entry.participants.size(),
              entry.context);

          Map<String, Object> tokens = new HashMap<>(entry.context.metadata());
          tokens.put("player", participantId);
          tokens.put("player_uuid", participantId);
          tokens.put("sender", participantId);
          tokens.put("sender_uuid", participantId);
          executeEnqueueSteps(def.lifecycle().onCancel(), gateCtx, tokens, entry.participants);
        }
      }
    }

    // 2. Check if participant is in an active session (allowed if action declares cancellable: true)
    UUID sessionId = participantToSession.get(participantId);
    if (sessionId != null) {
      ActionSessionImpl session = activeSessions.get(sessionId);
      if (session != null && (actionId == null || actionId.isBlank() || session.actionId().equalsIgnoreCase(actionId.trim()))) {
        ActionDefinition def = definitions.get(session.actionId().toLowerCase());
        boolean isCancellable = (def == null) || def.confinement().cancellable();
        if (isCancellable) {
          session.triggerCancel(participantId);
          cancelled = true;
        }
      }
    }

    return cancelled;
  }

  /**
   * Forfeits/surrenders an active match session for a participant, triggering onDeath/forfeit rules and disarming.
   */
  public boolean surrenderParticipant(UUID participantId, String actionId) {
    if (participantId == null) return false;
    UUID sessionId = participantToSession.get(participantId);
    if (sessionId != null) {
      ActionSessionImpl session = activeSessions.get(sessionId);
      if (session != null && (actionId == null || actionId.isBlank() || session.actionId().equalsIgnoreCase(actionId.trim()))) {
        // Identify opponent/winner if 2 participants
        UUID killerId = null;
        for (UUID pid : session.participants()) {
          if (!pid.equals(participantId)) {
            killerId = pid;
            break;
          }
        }
        session.triggerDeath(participantId, killerId);
        return true;
      }
    }
    return false;
  }

  /**
   * Routes a player death event to their active action session, triggering onDeath lifecycle steps (ADR-093 §5).
   *
   * @param victimId UUID of the participant who died
   * @param killerId UUID of the killer/winner if available, or null
   */
  public void handlePlayerDeath(UUID victimId, UUID killerId) {
    if (victimId == null) return;
    UUID sId = participantToSession.get(victimId);
    if (sId == null) return;
    ActionSessionImpl session = activeSessions.get(sId);
    if (session != null && session.isActive()) {
      session.triggerDeath(victimId, killerId);
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
      UUID serverId = new UUID(0, 0);
      accessor.executeCommand(serverId, "scoreboard objectives remove rtp_session_id");
      accessor.executeCommand(serverId, "scoreboard objectives remove rtp_time_left");
      accessor.executeCommand(serverId, "scoreboard objectives remove rtp_violations");
      accessor.executeCommand(serverId, "scoreboard objectives remove rtp_in_bounds");
      accessor.executeCommand(serverId, "scoreboard objectives remove rtp_dist_sq");
      accessor.executeCommand(serverId, "scoreboard objectives remove rtp_alive");

      // Sweep lingering session tags across all online entities/players
      for (io.github.dailystruggle.rtp.api.entity.RTPPlayer player : accessor.getOnlinePlayers()) {
        if (player != null) {
          accessor.executeCommand(serverId, "tag " + player.uuid() + " remove rtp_session_*");
        }
      }
      accessor.executeCommand(serverId, "tag @a remove rtp_session_*");
    } catch (Exception e) {
      RTP.log(Level.WARNING, "[RTP Action] Failed sweeping startup orphan scoreboards: " + e.getMessage());
    }
  }

  /**
   * Tick update for all active sessions and action wait-queues.
   */
  public void tick() {
    for (ActionSessionImpl session : activeSessions.values()) {
      session.tick();
    }
    processWaitQueues();
  }

  /**
   * Evaluates queued participant entries for all actions and triggers those whose gate conditions are met.
   */
  public void processWaitQueues() {
    for (ActionDefinition def : definitions.values()) {
      processWaitQueue(def);
    }
  }

  private void processWaitQueue(ActionDefinition def) {
    if (def == null) return;
    java.util.Queue<ActionWaitQueueEntry> queue = waitQueues.get(def.id().toLowerCase());
    if (queue == null || queue.isEmpty()) return;

    RTP.log(
        Level.FINE,
        "[action] processing wait queue for '"
            + def.id()
            + "': current queue size="
            + queue.size());

    // Prune entries where participants are no longer available or already in a session
    queue.removeIf(entry -> {
      if (entry.future.isDone()) return true;
      for (UUID pid : entry.participants) {
        if (participantToSession.containsKey(pid)) {
          entry.future.complete(ActionSessionResult.failure("Participant joined another session: " + pid));
          return true;
        }
      }
      return false;
    });

    if (queue.isEmpty()) return;

    List<ActionWaitQueueEntry> entryList = new ArrayList<>(queue);
    for (int i = 0; i < entryList.size(); i++) {
      ActionWaitQueueEntry head = entryList.get(i);
      if (!queue.contains(head)) continue;

      List<ActionWaitQueueEntry> candidateEntries = new ArrayList<>();
      candidateEntries.add(head);

      List<UUID> combinedParticipants = new ArrayList<>(head.participants);
      Map<String, Object> mergedMeta = new HashMap<>(head.context.metadata());
      List<Predicate<ActionGateContext>> mergedValidators = new ArrayList<>(head.context.gateValidators());

      // Attempt matching with subsequent entries
      for (int j = 0; j < entryList.size(); j++) {
        if (i == j) continue;
        ActionWaitQueueEntry other = entryList.get(j);
        if (!queue.contains(other) || candidateEntries.contains(other)) continue;

        candidateEntries.add(other);
        combinedParticipants.addAll(other.participants);
        mergedMeta.putAll(other.context.metadata());
        mergedValidators.addAll(other.context.gateValidators());

        UUID firstPid = combinedParticipants.isEmpty() ? null : combinedParticipants.get(0);

        // Build numbered tokens (_1, _2, etc.) for each candidate entry
        Map<String, Object> tokens = new HashMap<>();
        for (int idx = 0; idx < candidateEntries.size(); idx++) {
          int num = idx + 1;
          ActionWaitQueueEntry c = candidateEntries.get(idx);
          if (!c.participants.isEmpty()) {
            UUID pUuid = c.participants.get(0);
            tokens.put("player_" + num, pUuid);
            tokens.put("player_uuid_" + num, pUuid);
            tokens.put("sender_" + num, pUuid);
            tokens.put("sender_uuid_" + num, pUuid);
            if (RTP.serverAccessor != null) {
              io.github.dailystruggle.rtp.api.entity.RTPCommandSender sender = RTP.serverAccessor.getPlayer(pUuid);
              if (sender != null && sender.name() != null) {
                tokens.put("player_name_" + num, sender.name());
                tokens.put("sender_name_" + num, sender.name());
              }
            }
          }
          for (Map.Entry<String, Object> mEntry : c.context.metadata().entrySet()) {
            tokens.put(mEntry.getKey() + "_" + num, mEntry.getValue());
          }
        }

        // Apply merged metadata so global metadata is also accessible
        tokens.putAll(mergedMeta);

        if (firstPid != null) {
          tokens.put("player", firstPid);
          tokens.put("player_uuid", firstPid);
        }

        ActionContext combinedCtx = new ActionContext(tokens, mergedValidators);
        ActionGateContext preGateCtx = new ActionGateContext(
            UUID.randomUUID(),
            def.id(),
            firstPid,
            0L,
            def.confinement().durationSeconds(),
            0,
            true,
            0.0,
            null, null, null, null, null, null,
            combinedParticipants.size(),
            combinedCtx);

        boolean passed = GateEvaluator.evaluateAll(def.gates(), preGateCtx, externalPredicates, tokens);
        if (passed) {
          RTP.log(
              Level.FINE,
              "[action] wait queue satisfied for '"
                  + def.id()
                  + "'! Matching combined participants: "
                  + combinedParticipants);
          // Gate condition satisfied! Drain matching entries from queue
          for (ActionWaitQueueEntry c : candidateEntries) {
            queue.remove(c);
          }
          final List<ActionWaitQueueEntry> matchedEntries = List.copyOf(candidateEntries);
          executeDirect(def, combinedParticipants, combinedCtx).whenComplete((res, ex) -> {
            for (ActionWaitQueueEntry c : matchedEntries) {
              if (ex != null) {
                c.future.completeExceptionally(ex);
              } else {
                c.future.complete(res);
              }
            }
          });
          return;
        }

        // Check if gate requires more players than current candidateEntries size
        boolean requiresMorePlayers = false;
        for (Map<String, Object> gate : def.gates()) {
          Object pObj = gate.get("players");
          if (pObj != null) {
            String pStr = pObj.toString().trim();
            if (pStr.startsWith(">=")) {
              try {
                int req = Integer.parseInt(pStr.substring(2).trim());
                if (combinedParticipants.size() < req) {
                  requiresMorePlayers = true;
                }
              } catch (NumberFormatException ignored) {}
            }
          }
        }

        // If the gate requires more players, keep accumulating subsequent candidate entries!
        // Only backtrack/remove 'other' if we already have enough players but other gates (e.g. command) failed.
        if (!requiresMorePlayers) {
          candidateEntries.remove(other);
          combinedParticipants.subList(combinedParticipants.size() - other.participants.size(), combinedParticipants.size()).clear();
          mergedMeta = new HashMap<>(head.context.metadata());
          mergedValidators = new ArrayList<>(head.context.gateValidators());
        }
      }
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
      RTP.log(Level.WARNING, "[action] trigger failed - definition not found for: " + actionId);
      return CompletableFuture.completedFuture(
          ActionSessionResult.failure("Action definition not found: " + actionId));
    }

    RTP.log(
        Level.FINE,
        "[action] trigger: action="
            + def.id()
            + ", participants="
            + participants
            + ", placementEnabled="
            + def.placement().enabled()
            + ", gates="
            + def.gates().size());

    // Atomically reserve participants up front to prevent concurrent triggers bypassing the active check
    UUID pendingSessionId = UUID.randomUUID();
    List<UUID> reserved = new ArrayList<>(participants.size());
    for (UUID pid : participants) {
      if (participantToSession.putIfAbsent(pid, pendingSessionId) != null) {
        for (UUID r : reserved) {
          participantToSession.remove(r, pendingSessionId);
        }
        io.github.dailystruggle.rtp.api.server.RTPServerAccessor acc = RTP.serverAccessor;
        String pName = (acc != null && acc.getPlayer(pid) != null) ? acc.getPlayer(pid).name() : pid.toString();
        RTP.log(Level.WARNING, "[action] participant " + pName + " (" + pid + ") already in active session");
        return CompletableFuture.completedFuture(
            ActionSessionResult.failure("Participant is already in an active session: " + pName));
      }
      reserved.add(pid);
    }

    // 1. Evaluate pre-execution gate conditions before initiating placement (ADR-093)
    try {
      UUID firstPid = participants.isEmpty() ? null : participants.get(0);
      ActionGateContext preGateCtx = new ActionGateContext(
          UUID.randomUUID(),
          def.id(),
          firstPid,
          0L,
          def.confinement().durationSeconds(),
          0,
          true,
          0.0,
          null, null, null, null, null, null,
          participants.size(),
          effectiveContext);

      Map<String, Object> tokens = new HashMap<>(effectiveContext.metadata());
      if (firstPid != null) {
        tokens.put("player", firstPid);
        tokens.put("player_1", firstPid);
        tokens.put("player_uuid_1", firstPid);
      }
      for (Map.Entry<String, Object> mEntry : effectiveContext.metadata().entrySet()) {
        tokens.put(mEntry.getKey() + "_1", mEntry.getValue());
      }

      boolean gatePassed = GateEvaluator.evaluateAll(def.gates(), preGateCtx, externalPredicates, tokens);
      RTP.log(
          Level.FINE,
          "[action] pre-execution gates evaluation for '" + def.id() + "': passed=" + gatePassed);
      if (!gatePassed) {
        // Action gate condition not met: roll back upfront reservation as players are only waiting
        rollbackReservation(reserved, pendingSessionId);

        // Enqueue invocation into ActionWaitQueue
        CompletableFuture<ActionSessionResult> waitFuture = new CompletableFuture<>();
        ActionWaitQueueEntry entry = new ActionWaitQueueEntry(participants, effectiveContext, waitFuture);
        waitQueues.computeIfAbsent(def.id().toLowerCase(), k -> new java.util.concurrent.ConcurrentLinkedQueue<>()).add(entry);
        RTP.log(
            Level.FINE,
            "[action] enqueued participants "
                + participants
                + " into wait queue for action '"
                + def.id()
                + "'");

        // Execute onEnqueue / onWait lifecycle steps if defined (e.g. interactive click prompts or wait notifications)
        if (def.lifecycle() != null && !def.lifecycle().onEnqueue().isEmpty()) {
          executeEnqueueSteps(def.lifecycle().onEnqueue(), preGateCtx, tokens, participants);
        }

        // Immediately attempt processing the queue in case combined queued entries now satisfy the gate
        processWaitQueue(def);
        return waitFuture;
      }

      return executeDirectInternal(def, participants, effectiveContext, pendingSessionId);
    } catch (Throwable t) {
      rollbackReservation(reserved, pendingSessionId);
      RTP.log(Level.WARNING, "[action] trigger failed for '" + def.id() + "'", t);
      return CompletableFuture.completedFuture(
          ActionSessionResult.failure("Action trigger failed: " + t.getMessage()));
    }
  }

  private void executeEnqueueSteps(
      List<ActionDefinition.LifecycleStep> steps,
      ActionGateContext context,
      Map<String, Object> tokens,
      List<UUID> participants) {
    if (steps == null || steps.isEmpty()) return;
    for (ActionDefinition.LifecycleStep step : steps) {
      if (step == null) continue;
      if (step.gateConfig() != null && !step.gateConfig().isEmpty()
          && !GateEvaluator.evaluate(step.gateConfig(), context, externalPredicates, tokens)) {
        continue;
      }
      for (ActionDefinition.CommandAction action : step.actions()) {
        executeEnqueueAction(action, context, tokens, participants);
      }
    }
  }

  private void executeEnqueueAction(
      ActionDefinition.CommandAction action,
      ActionGateContext context,
      Map<String, Object> tokens,
      List<UUID> participants) {
    if (action == null) return;
    io.github.dailystruggle.rtp.api.server.RTPServerAccessor accessor = RTP.serverAccessor;
    if (accessor == null) return;

    // Any command with a target placeholder should be dropped if there is no target
    if (ActionPlaceholderSanitizer.hasMissingTarget(action.payload(), tokens)) {
      RTP.log(Level.FINE, "[RTP Action] Dropping enqueue command due to missing target: " + action.payload());
      return;
    }

    switch (action.type()) {
      case CONSOLE -> {
        String substituted = ActionPlaceholderSanitizer.substitute(action.payload(), tokens);
        if (ActionPlaceholderSanitizer.containsUnresolvedPrefix(substituted, "target")) {
          RTP.log(Level.FINE, "[RTP Action] Dropping enqueue console command with unresolved target: " + substituted);
          return;
        }
        try {
          accessor.executeCommand(new UUID(0, 0), substituted);
        } catch (Throwable t) {
          RTP.log(Level.WARNING, "[RTP Action] Enqueue command execution failed: " + substituted, t);
        }
      }
      case PLAYER -> {
        for (UUID pid : participants) {
          Map<String, Object> pTokens = new HashMap<>(tokens);
          pTokens.put("player", pid);
          pTokens.put("violator", pid);
          if (ActionPlaceholderSanitizer.hasMissingTarget(action.payload(), pTokens)) {
            continue;
          }
          String substituted = ActionPlaceholderSanitizer.substitute(action.payload(), pTokens);
          if (ActionPlaceholderSanitizer.containsUnresolvedPrefix(substituted, "target")) {
            continue;
          }
          try {
            accessor.executeCommand(pid, substituted);
          } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP Action] Enqueue player command execution failed: " + substituted, t);
          }
        }
      }
      case FOR_EACH -> {
        for (UUID pid : participants) {
          Map<String, Object> pTokens = new HashMap<>(tokens);
          pTokens.put("player", pid);
          pTokens.put("violator", pid);
          for (ActionDefinition.CommandAction sub : action.subActions()) {
            executeEnqueueAction(sub, context, pTokens, List.of(pid));
          }
        }
      }
      case ACTION -> {
        // Nested action trigger from enqueue if requested
        if (!action.payload().isBlank()) {
          trigger(action.payload(), participants, context.context());
        }
      }
      case MESSAGE -> {
        String substituted = ActionPlaceholderSanitizer.substitute(action.payload(), tokens);
        if (ActionPlaceholderSanitizer.containsUnresolvedPrefix(substituted, "target")) {
          return;
        }
        // If message addresses target (e.g. prompt to Bob), send to target if present; if target is missing/any, drop it
        if (action.payload().contains("[target") || action.payload().contains("challenged you")) {
          Object targetObj = tokens.get("target_uuid");
          if (targetObj == null) targetObj = tokens.get("target");
          if (targetObj == null) targetObj = tokens.get("target_name");
          if (targetObj == null || targetObj.toString().isBlank() || targetObj.toString().equalsIgnoreCase("any")) {
            // No target specified (e.g. open challenge matchmaking) - do not send invitation prompt to self or anyone
            return;
          }
          if (targetObj instanceof UUID tId) {
            accessor.sendMessage(tId, substituted);
            return;
          } else if (targetObj instanceof String tName) {
            io.github.dailystruggle.rtp.api.entity.RTPPlayer tp = accessor.getPlayer(tName);
            if (tp != null) {
              accessor.sendMessage(tp.uuid(), substituted);
              return;
            }
          }
          // Target could not be resolved, do not send to sender
          return;
        }

        Object pObj = tokens.get("player");
        if (pObj instanceof UUID pid) {
          accessor.sendMessage(pid, substituted);
        } else {
          // If no player token, send to all participants
          for (UUID pid : participants) {
            accessor.sendMessage(pid, substituted);
          }
        }
      }
    }
  }

  /**
   * Executes placement and session initialization for an action whose pre-execution gates passed.
   */
  public CompletableFuture<ActionSessionResult> executeDirect(
      ActionDefinition def,
      List<UUID> participants,
      ActionContext effectiveContext) {
    return executeDirectInternal(def, participants, effectiveContext, null);
  }

  private CompletableFuture<ActionSessionResult> executeDirectInternal(
      ActionDefinition def,
      List<UUID> participants,
      ActionContext effectiveContext,
      UUID preReservedSessionId) {

    RTP.log(
        Level.FINE,
        "[action] executeDirect: action="
            + def.id()
            + ", participants="
            + participants
            + ", placementEnabled="
            + def.placement().enabled());

    final UUID sessionId;
    List<UUID> localReserved = null;
    if (preReservedSessionId != null) {
      sessionId = preReservedSessionId;
    } else {
      sessionId = UUID.randomUUID();
      localReserved = new ArrayList<>(participants.size());
      for (UUID pid : participants) {
        if (participantToSession.putIfAbsent(pid, sessionId) != null) {
          rollbackReservation(localReserved, sessionId);
          io.github.dailystruggle.rtp.api.server.RTPServerAccessor acc = RTP.serverAccessor;
          String pName = (acc != null && acc.getPlayer(pid) != null) ? acc.getPlayer(pid).name() : pid.toString();
          return CompletableFuture.completedFuture(
              ActionSessionResult.failure("Participant is already in an active session: " + pName));
        }
        localReserved.add(pid);
      }
    }

    try {
      // If spatial placement is disabled (e.g. pure-scripting / external orchestrators), bypass GroupPlacementService
      if (!def.placement().enabled()) {
        ActionSessionImpl session =
            new ActionSessionImpl(
                sessionId,
                def,
                participants,
                effectiveContext,
                Collections.emptyMap(),
                null,
                0,
                0,
                null,
                this::handleDisarm,
                externalPredicates);

        activeSessions.put(sessionId, session);
        for (UUID pid : participants) {
          participantToSession.put(pid, sessionId);
        }

        session.arm();
        session.triggerStart();
        return CompletableFuture.completedFuture(ActionSessionResult.success(sessionId));
      }

      // Spatial Placement via Subspace Group Engine (ADR-095)
      GroupPlacementService groupService = RTP.groupPlacementService;
      if (groupService == null) {
        rollbackReservation(participants, sessionId);
        return CompletableFuture.completedFuture(
            ActionSessionResult.failure("GroupPlacementService is not available"));
      }

      final ActionDefinition.PlacementSpec pSpec = def.placement();
      String effectiveRegion = pSpec.region();
      if (pSpec.parameters().containsKey("memoryRegion")) {
        Object mr = pSpec.parameters().get("memoryRegion");
        if (mr != null && !mr.toString().isBlank()) effectiveRegion = mr.toString().trim();
      } else if (pSpec.parameters().containsKey("inheritRegionMemory")) {
        Object mr = pSpec.parameters().get("inheritRegionMemory");
        if (mr != null && !mr.toString().isBlank()) effectiveRegion = mr.toString().trim();
      }

      final Region parentRegion = (effectiveRegion != null && !effectiveRegion.isBlank() && RTP.selectionAPI != null)
          ? RTP.selectionAPI.getRegion(effectiveRegion)
          : null;

      // Check if a pre-validated placement is cached for this action (ADR-097)
      return tryCachedOrLivePlacement(def, participants, effectiveContext, groupService, parentRegion, sessionId)
          .whenComplete((res, ex) -> {
            if (ex != null || res == null || !res.success()) {
              rollbackReservation(participants, sessionId);
            }
          });
    } catch (Throwable t) {
      rollbackReservation(participants, sessionId);
      RTP.log(Level.WARNING, "[action] executeDirect failed for '" + def.id() + "'", t);
      return CompletableFuture.completedFuture(
          ActionSessionResult.failure("Action execution failed: " + t.getMessage()));
    }
  }

  private void rollbackReservation(List<UUID> participants, UUID sessionId) {
    if (participants == null || sessionId == null) return;
    for (UUID pid : participants) {
      participantToSession.remove(pid, sessionId);
    }
  }

  /**
   * Attempts to pop and revalidate cached placements; falls back to live bounded-retry placement
   * if all cached entries are exhausted or fail revalidation (ADR-097).
   */
  private CompletableFuture<ActionSessionResult> tryCachedOrLivePlacement(
      ActionDefinition def,
      List<UUID> participants,
      ActionContext effectiveContext,
      GroupPlacementService groupService,
      Region parentRegion,
      UUID sessionId) {

    PrevalidatedActionPlacement cached = pollCachedPlacement(def.id(), participants.size());
    if (cached != null) {
      return revalidateAndApply(cached, def, participants, effectiveContext, parentRegion, sessionId)
          .thenCompose(res -> {
            if (res.success()) {
              return CompletableFuture.completedFuture(res);
            }
            // If cached placement was invalidated upon recheck, try next cached or fall back to live placement
            return tryCachedOrLivePlacement(def, participants, effectiveContext, groupService, parentRegion, sessionId);
          });
    }

    return executeLivePlacement(def, participants, effectiveContext, groupService, parentRegion, sessionId);
  }

  /**
   * Checks whether a pre-validated candidate placement matching participant count is currently cached.
   */
  public boolean hasCachedPlacement(String actionId, int participantCount) {
    if (actionId == null) return false;
    java.util.Queue<PrevalidatedActionPlacement> queue = actionCaches.get(actionId.trim().toLowerCase());
    if (queue == null || queue.isEmpty()) return false;
    for (PrevalidatedActionPlacement p : queue) {
      if (p != null && p.placements().size() >= participantCount) {
        return true;
      }
    }
    return false;
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
      Region parentRegion,
      UUID sessionId) {

    List<RTPLocation> locList = new ArrayList<>(cached.placements().values());
    List<CompletableFuture<Boolean>> revalidationChecks = new ArrayList<>(participants.size());

    for (int i = 0; i < participants.size(); i++) {
      RTPLocation loc = locList.get(i);
      // 1. Asynchronous block column standability revalidation on demand (S-001 + S-005)
      if (parentRegion != null && parentRegion.candidateValidator() != null) {
        CompletableFuture<Boolean> standableCheck =
            parentRegion.candidateValidator().validateAsync(loc.x(), loc.z())
                .handle((standable, ex) -> {
                  if (ex != null || standable == null || standable.coords() == null) return false;
                  return Math.abs(standable.coords().y() - loc.y()) <= 1;
                });
        revalidationChecks.add(standableCheck);
      }

      // 2. External claim verification recheck (S-003)
      RTPCoords coords = new RTPCoords(loc.world().name(), loc.x(), loc.y(), loc.z());
      revalidationChecks.add(
          io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers.checkGlobalRegionVerifiers(coords));
    }

    // Accumulate all revalidation checks asynchronously without calling .join() (S-005 non-blocking)
    CompletableFuture<Boolean> allChecksPass =
        CompletableFuture.completedFuture(Boolean.TRUE);
    for (CompletableFuture<Boolean> check : revalidationChecks) {
      allChecksPass =
          allChecksPass.thenCombine(check, (accum, pass) -> accum && Boolean.TRUE.equals(pass));
    }

    return allChecksPass
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
                return ActionSessionResult.failure("Cached placement slot rejected by revalidation");
              }

              // Revalidation passed! Assign cached slots to participants, dispatch teleports, and start session
              Map<UUID, int[]> assignedSlots = new HashMap<>();
              String worldName = locList.get(0).world().name();

              for (int i = 0; i < participants.size(); i++) {
                UUID pid = participants.get(i);
                RTPLocation loc = locList.get(i);
                assignedSlots.put(pid, new int[] {loc.x(), loc.y(), loc.z()});
                if (RTP.serverAccessor != null) {
                  try {
                    io.github.dailystruggle.rtp.api.entity.RTPPlayer player = RTP.serverAccessor.getPlayer(pid);
                    if (player != null && player.isOnline()) {
                      player.setLocation(loc).whenComplete((ok, ex) -> {
                        if (loc.getReservation() != null) {
                          try {
                            loc.getReservation().close();
                          } catch (Throwable ignored) {
                          }
                        }
                      });
                    } else if (loc.getReservation() != null) {
                      loc.getReservation().close();
                    }
                  } catch (Throwable t) {
                    if (loc.getReservation() != null) {
                      loc.getReservation().close();
                    }
                  }
                } else if (loc.getReservation() != null) {
                  loc.getReservation().close();
                }
              }

              // Release any excess pre-validated slots that were not needed (S-002)
              for (int i = participants.size(); i < locList.size(); i++) {
                RTPLocation excessLoc = locList.get(i);
                if (excessLoc != null && excessLoc.getReservation() != null) {
                  try {
                    excessLoc.getReservation().close();
                  } catch (Throwable ignored) {
                  }
                }
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
      Region parentRegion,
      UUID sessionId) {

    ActionDefinition.PlacementSpec pSpec = def.placement();
    int centerRadius = pSpec.centerRadius();
    if (centerRadius <= 0 && pSpec.parameters() != null && pSpec.parameters().get("centerRadius") instanceof Number n) {
      centerRadius = n.intValue();
    }
    GroupProfileSpec profile =
        GroupProfileSpec.of(
            pSpec.shapeName(),
            pSpec.radius(),
            centerRadius,
            pSpec.minSeparation(),
            pSpec.elevationTolerance(),
            Math.max(1, participants.size()),
            pSpec.retries());

    AnchorSource anchorSource = resolveAnchorSource(pSpec, effectiveContext, participants, parentRegion);
    String targetRegion = (pSpec.region() != null && !pSpec.region().isBlank())
        ? pSpec.region()
        : ((parentRegion != null) ? parentRegion.name : null);
    GroupPlacementRequest request =
        GroupPlacementRequest.of(targetRegion, profile, participants, anchorSource);

    RTP.log(
        Level.FINE,
        "[action] live placement request: action="
            + def.id()
            + ", region="
            + targetRegion
            + ", profile="
            + profile
            + ", participants="
            + participants);

    return groupService
        .place(request)
        .thenApply(
            result -> {
              RTP.log(
                  Level.FINE,
                  "[action] live placement result: action="
                  + def.id()
                  + ", success="
                  + result.isSuccess()
                  + ", placements="
                  + result.placements().size()
                  + ", reason="
                  + result.reason());

              if (!result.isSuccess() || result.placements().isEmpty()) {
                return ActionSessionResult.failure(
                    "Spatial subspace placement failed: " + result.reason());
              }

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

                  // Dispatch teleport for online player and close chunk reservation (S-002 / S-005)
                  if (RTP.serverAccessor != null) {
                    try {
                      io.github.dailystruggle.rtp.api.entity.RTPPlayer player = RTP.serverAccessor.getPlayer(pid);
                      if (player != null && player.isOnline()) {
                        player.setLocation(loc).whenComplete((ok, ex) -> {
                          if (loc.getReservation() != null) {
                            try {
                              loc.getReservation().close();
                            } catch (Throwable ignored) {
                            }
                          }
                        });
                      } else if (loc.getReservation() != null) {
                        loc.getReservation().close();
                      }
                    } catch (Throwable t) {
                      if (loc.getReservation() != null) {
                        loc.getReservation().close();
                      }
                    }
                  } else if (loc.getReservation() != null) {
                    loc.getReservation().close();
                  }
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
   * Checks whether a participant is currently waiting in the wait queue for an action.
   */
  public boolean isQueued(String actionId, UUID participant) {
    if (actionId == null || participant == null) return false;
    java.util.Queue<ActionWaitQueueEntry> queue = waitQueues.get(actionId.trim().toLowerCase());
    if (queue == null || queue.isEmpty()) return false;
    for (ActionWaitQueueEntry entry : queue) {
      if (entry != null && !entry.future.isDone() && entry.participants.contains(participant)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Resolves the {@link AnchorSource} for an action from its declared {@code placement.anchor}
   * selector (ADR-095), sourcing live coordinates from the invocation context and static
   * landmark coordinates from the placement parameters. Falls back to the region queue.
   */
  public static AnchorSource resolveAnchorSource(
      ActionDefinition.PlacementSpec pSpec,
      ActionContext ctx,
      List<UUID> participants,
      Region parentRegion) {
    Object rawType = pSpec.parameters().get("anchor");
    String type = (rawType == null) ? "regionqueue" : rawType.toString().trim().toLowerCase();
    switch (type) {
      case "faction":
      case "claimboundary": {
        Map<String, Object> meta = (ctx != null) ? ctx.metadata() : Collections.emptyMap();
        Object boundaryObj = meta.get("claimBoundary");
        if (boundaryObj instanceof io.github.dailystruggle.rtp.api.claim.ClaimBoundary cb) {
          return AnchorSource.claimBoundary(cb);
        }

        // Attempt resolving via ClaimBoundaryRegistry from hooks facade
        try {
          if (io.github.dailystruggle.rtp.api.RTPAPI.hooks != null) {
            io.github.dailystruggle.rtp.api.hooks.ClaimBoundaryRegistry registry =
                io.github.dailystruggle.rtp.api.RTPAPI.hooks.claimBoundaries();
            if (registry != null && participants != null && !participants.isEmpty()) {
              UUID primaryId = participants.get(0);
              String targetWorld = (parentRegion != null && parentRegion.getWorld() != null)
                  ? parentRegion.getWorld().name()
                  : ((pSpec.region() != null) ? pSpec.region() : "world");
              String ns = "faction".equals(type) ? "factions" : null;
              Object customNs = pSpec.parameters().get("namespace");
              if (customNs != null) ns = customNs.toString().trim();

              java.util.Optional<io.github.dailystruggle.rtp.api.claim.ClaimBoundary> resolved =
                  registry.resolve(primaryId, targetWorld, ns);
              if (resolved.isPresent()) {
                return AnchorSource.claimBoundary(resolved.get());
              }
            }
          }
        } catch (Throwable t) {
          io.github.dailystruggle.rtp.common.RTP.log(
              java.util.logging.Level.WARNING,
              "[RTP] Failed to resolve claim boundary for action anchor",
              t);
        }

        return AnchorSource.claimHazard();
      }
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
        if (c == null && RTP.serverAccessor != null) {
          // Auto-discover a random online player (excluding participants if possible)
          List<io.github.dailystruggle.rtp.api.entity.RTPPlayer> online =
              new ArrayList<>(RTP.serverAccessor.getOnlinePlayers());
          if (participants != null && !participants.isEmpty()) {
            online.removeIf(p -> participants.contains(p.uuid()));
          }
          if (!online.isEmpty()) {
            Collections.shuffle(online);
            io.github.dailystruggle.rtp.api.world.RTPLocation loc = online.get(0).getLocation();
            if (loc != null && loc.world() != null) {
              c = new RTPCoords(loc.world().name(), loc.x(), loc.y(), loc.z());
            }
          }
        }
        if (c == null) {
          // Fail closed: do not silently fall back to random regionQueue when no target player is available
          return AnchorSource.entity(() -> null);
        }
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
        participantToSession.remove(pid, sessionId);
      }
    }
  }
}
