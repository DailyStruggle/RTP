package io.github.dailystruggle.rtp.common.trigger;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.event.PlayerMoveEvent;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Thread-safe manager for physical world triggers (portals, pressure plates, step-in zones) (ADR-093).
 * Supports both instant triggers and batched wave accumulator spatial lobbies.
 */
public class PhysicalTriggerManager implements AutoCloseable {

  private final Map<String, PhysicalTriggerSpec> triggers = new ConcurrentHashMap<>();
  private final Map<String, Map<UUID, Long>> cooldowns = new ConcurrentHashMap<>();
  private final Map<String, Set<UUID>> occupants = new ConcurrentHashMap<>();
  private final Map<String, Long> waveRemainingSeconds = new ConcurrentHashMap<>();
  private AutoCloseable moveRegistration;
  private Object accumulatorTaskHandle;

  public void start() {
    if (moveRegistration == null && RTPAPI.playerMoveEvents != null) {
      moveRegistration = RTPAPI.playerMoveEvents.watchAll(this::onPlayerMove);
    }
    if (accumulatorTaskHandle == null && RTP.scheduler != null) {
      accumulatorTaskHandle = RTP.scheduler.runTaskTimerAsynchronously(this::tickWaveAccumulator, 20L, 20L);
    }
  }

  public void registerTrigger(PhysicalTriggerSpec trigger) {
    Objects.requireNonNull(trigger, "trigger must not be null");
    String key = trigger.id().toLowerCase();
    triggers.put(key, trigger);
    if (trigger.batchIntervalSeconds() > 0) {
      waveRemainingSeconds.putIfAbsent(key, trigger.batchIntervalSeconds());
      occupants.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
    }
  }

  public boolean unregisterTrigger(String triggerId) {
    if (triggerId == null) return false;
    String key = triggerId.toLowerCase();
    cooldowns.remove(key);
    occupants.remove(key);
    waveRemainingSeconds.remove(key);
    return triggers.remove(key) != null;
  }

  public PhysicalTriggerSpec getTrigger(String triggerId) {
    if (triggerId == null) return null;
    return triggers.get(triggerId.toLowerCase());
  }

  public Collection<PhysicalTriggerSpec> getTriggers() {
    return Collections.unmodifiableCollection(triggers.values());
  }

  public Set<UUID> getOccupants(String triggerId) {
    if (triggerId == null) return Collections.emptySet();
    Set<UUID> set = occupants.get(triggerId.toLowerCase());
    return set == null ? Collections.emptySet() : Collections.unmodifiableSet(set);
  }

  public long getWaveRemainingSeconds(String triggerId) {
    if (triggerId == null) return 0L;
    return waveRemainingSeconds.getOrDefault(triggerId.toLowerCase(), 0L);
  }

  public void clear() {
    triggers.clear();
    cooldowns.clear();
    occupants.clear();
    waveRemainingSeconds.clear();
  }

  public void onPlayerMove(PlayerMoveEvent event) {
    if (event == null || triggers.isEmpty()) return;

    String world = event.worldName();
    int x = event.toX();
    int y = event.toY();
    int z = event.toZ();
    UUID pid = event.playerId();
    long now = System.currentTimeMillis();

    for (PhysicalTriggerSpec trigger : triggers.values()) {
      String key = trigger.id().toLowerCase();
      boolean inside = trigger.contains(world, x, y, z);

      if (trigger.batchIntervalSeconds() > 0) {
        // Wave batch accumulator trigger
        Set<UUID> triggerOccupants = occupants.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        if (inside) {
          triggerOccupants.add(pid);
        } else {
          triggerOccupants.remove(pid);
        }
      } else {
        // Instant trigger
        if (!inside) continue;

        // Check cooldown
        Map<UUID, Long> playerCooldowns =
            cooldowns.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        Long lastTrigger = playerCooldowns.get(pid);
        if (lastTrigger != null && (now - lastTrigger) < trigger.cooldownSeconds() * 1000L) {
          continue;
        }

        // Mark cooldown
        playerCooldowns.put(pid, now);

        // Trigger the action
        io.github.dailystruggle.rtp.api.action.ActionService actionService = RTPAPI.actions();
        if (actionService != null) {
          try {
            java.util.concurrent.CompletableFuture<io.github.dailystruggle.rtp.api.action.ActionSessionResult> future =
                actionService.trigger(trigger.actionId(), List.of(pid), ActionContext.EMPTY);
            if (future != null) {
              future.whenComplete((result, ex) -> {
                if (ex != null) {
                  RTP.log(Level.WARNING, "[RTP] Physical trigger " + trigger.id() + " failed: " + ex.getMessage(), ex);
                } else if (result != null && !result.success()) {
                  RTP.log(Level.WARNING, "[RTP] Physical trigger " + trigger.id() + " rejected: " + result.failureReason());
                } else if (result != null && result.success()) {
                  RTP.log(Level.FINE, "[RTP] Physical trigger " + trigger.id() + " succeeded, session: " + result.sessionId());
                }
              });
            }
          } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP] Physical trigger " + trigger.id() + " failed: " + t.getMessage(), t);
          }
        }
      }
    }
  }

  /**
   * Ticks active batch triggers and wave accumulators (ADR-093 / Section 2.2 & 2.3).
   * Can be called directly by scheduler or test suites.
   */
  public void tickWaveAccumulator() {
    if (triggers.isEmpty()) return;

    RTPServerAccessor accessor = RTP.serverAccessor;

    for (PhysicalTriggerSpec trigger : triggers.values()) {
      if (trigger.batchIntervalSeconds() <= 0) continue;

      String key = trigger.id().toLowerCase();
      Set<UUID> currentOccupants = occupants.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());

      // Verify and cull occupants who logged off or left the bounding box
      if (accessor != null) {
        List<UUID> toRemove = new ArrayList<>();
        for (UUID pid : currentOccupants) {
          RTPPlayer player = accessor.getPlayer(pid);
          if (player == null || !player.isOnline()) {
            toRemove.add(pid);
            continue;
          }
          RTPLocation loc = player.getLocation();
          if (loc == null || loc.world() == null || !trigger.contains(loc.world().name(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
            toRemove.add(pid);
          }
        }
        currentOccupants.removeAll(toRemove);
      }

      if (currentOccupants.isEmpty()) {
        // Reset countdown when zone is empty
        waveRemainingSeconds.put(key, trigger.batchIntervalSeconds());
        continue;
      }

      long remaining = waveRemainingSeconds.getOrDefault(key, trigger.batchIntervalSeconds()) - 1;
      if (remaining > 0) {
        waveRemainingSeconds.put(key, remaining);

        // Broadcast lobby feedback lifecycle countdown to occupants
        broadcastLobbyFeedback(trigger, currentOccupants, remaining);
      } else {
        // Interval expired: gather occupants and dispatch group action
        List<UUID> playersToDispatch = new ArrayList<>(currentOccupants);
        currentOccupants.clear();
        waveRemainingSeconds.put(key, trigger.batchIntervalSeconds());

        // Check & apply cooldowns
        long now = System.currentTimeMillis();
        Map<UUID, Long> playerCooldowns =
            cooldowns.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        playersToDispatch.removeIf(pid -> {
          Long last = playerCooldowns.get(pid);
          return last != null && (now - last) < trigger.cooldownSeconds() * 1000L;
        });

        for (UUID pid : playersToDispatch) {
          playerCooldowns.put(pid, now);
        }

        if (!playersToDispatch.isEmpty()) {
          RTP.log(Level.INFO, "[RTP] Physical trigger wave dispatching " + playersToDispatch.size()
              + " occupants for action '" + trigger.actionId() + "' in zone '" + trigger.id() + "'");

          java.util.concurrent.CompletableFuture<io.github.dailystruggle.rtp.api.action.ActionSessionResult> future = null;
          if (RTP.actionManager != null) {
            future = RTP.actionManager.dispatchGroupAction(playersToDispatch, trigger.actionId());
          } else if (RTPAPI.actions() != null) {
            future = RTPAPI.actions().trigger(trigger.actionId(), playersToDispatch, ActionContext.EMPTY);
          }

          if (future != null) {
            future.whenComplete((result, ex) -> {
              if (ex != null) {
                RTP.log(Level.WARNING, "[RTP] Physical trigger wave dispatch failed for action '"
                    + trigger.actionId() + "': " + ex.getMessage(), ex);
              } else if (result != null && !result.success()) {
                RTP.log(Level.WARNING, "[RTP] Physical trigger wave dispatch rejected for action '"
                    + trigger.actionId() + "': " + result.failureReason());
              } else if (result != null && result.success()) {
                RTP.log(Level.FINE, "[RTP] Physical trigger wave dispatch succeeded for action '"
                    + trigger.actionId() + "', session: " + result.sessionId());
              }
            });
          }
        }
      }
    }
  }

  private void broadcastLobbyFeedback(PhysicalTriggerSpec trigger, Set<UUID> occupants, long remainingSeconds) {
    RTPServerAccessor accessor = RTP.serverAccessor;
    if (accessor == null || occupants.isEmpty()) return;

    // Check if action defines onEnqueue / onWait lifecycles
    ActionDefinition actionDef = (RTP.actionManager != null)
        ? RTP.actionManager.getAction(trigger.actionId()).orElse(null)
        : null;

    if (actionDef != null && actionDef.lifecycle() != null && !actionDef.lifecycle().onEnqueue().isEmpty()) {
      // Execute lifecycle steps with countdown tokens
      Map<String, Object> tokens = new HashMap<>();
      tokens.put("action", trigger.actionId());
      tokens.put("trigger", trigger.id());
      tokens.put("countdown", remainingSeconds);
      tokens.put("occupants", occupants.size());
      tokens.put("time_remaining", remainingSeconds);

      for (ActionDefinition.LifecycleStep step : actionDef.lifecycle().onEnqueue()) {
        for (ActionDefinition.CommandAction cmd : step.actions()) {
          for (UUID pid : occupants) {
            Map<String, Object> perPlayerTokens = new HashMap<>(tokens);
            perPlayerTokens.put("player", pid);
            String raw = io.github.dailystruggle.rtp.common.action.ActionPlaceholderSanitizer.substitute(cmd.payload(), perPlayerTokens);
            if (cmd.type() == ActionDefinition.ActionType.MESSAGE) {
              accessor.sendMessage(pid, raw);
            } else if (cmd.type() == ActionDefinition.ActionType.CONSOLE) {
              accessor.executeCommand(new UUID(0, 0), raw);
            }
          }
        }
      }
    } else {
      // Default lobby feedback countdown notification
      if (remainingSeconds <= 5 || remainingSeconds % 10 == 0) {
        String msg = "[RTP] Teleporting in " + remainingSeconds + "s... (" + occupants.size() + " in queue)";
        for (UUID pid : occupants) {
          accessor.sendMessage(pid, msg);
        }
      }
    }
  }

  @Override
  public void close() {
    if (moveRegistration != null) {
      try {
        moveRegistration.close();
      } catch (Exception ignored) {
      }
      moveRegistration = null;
    }
    if (accumulatorTaskHandle != null && RTP.scheduler != null) {
      try {
        RTP.scheduler.cancelTask(accumulatorTaskHandle);
      } catch (Exception ignored) {
      }
      accumulatorTaskHandle = null;
    }
    clear();
  }
}
