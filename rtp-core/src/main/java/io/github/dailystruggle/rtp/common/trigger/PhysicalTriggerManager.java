package io.github.dailystruggle.rtp.common.trigger;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.event.PlayerMoveEvent;
import io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Thread-safe manager for physical world triggers (portals, pressure plates, step-in zones) (ADR-093).
 */
public class PhysicalTriggerManager implements AutoCloseable {

  private final Map<String, PhysicalTriggerSpec> triggers = new ConcurrentHashMap<>();
  private final Map<String, Map<UUID, Long>> cooldowns = new ConcurrentHashMap<>();
  private AutoCloseable moveRegistration;

  public void start() {
    if (moveRegistration == null && RTPAPI.playerMoveEvents != null) {
      moveRegistration = RTPAPI.playerMoveEvents.watchAll(this::onPlayerMove);
    }
  }

  public void registerTrigger(PhysicalTriggerSpec trigger) {
    Objects.requireNonNull(trigger, "trigger must not be null");
    triggers.put(trigger.id().toLowerCase(), trigger);
  }

  public boolean unregisterTrigger(String triggerId) {
    if (triggerId == null) return false;
    cooldowns.remove(triggerId.toLowerCase());
    return triggers.remove(triggerId.toLowerCase()) != null;
  }

  public PhysicalTriggerSpec getTrigger(String triggerId) {
    if (triggerId == null) return null;
    return triggers.get(triggerId.toLowerCase());
  }

  public Collection<PhysicalTriggerSpec> getTriggers() {
    return Collections.unmodifiableCollection(triggers.values());
  }

  public void clear() {
    triggers.clear();
    cooldowns.clear();
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
      if (!trigger.contains(world, x, y, z)) continue;

      // Check cooldown
      Map<UUID, Long> playerCooldowns =
          cooldowns.computeIfAbsent(trigger.id().toLowerCase(), k -> new ConcurrentHashMap<>());
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
          actionService.trigger(trigger.actionId(), List.of(pid), ActionContext.EMPTY);
        } catch (Throwable t) {
          RTP.log(Level.WARNING, "[RTP] Physical trigger " + trigger.id() + " failed: " + t.getMessage(), t);
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
    clear();
  }
}
