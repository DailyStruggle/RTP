package io.github.dailystruggle.rtp.common.tasks.teleport;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide respawn-teleport bookkeeping (ADR-115 seam 4), formerly the
 * {@code DeathTeleports.effectInFlight()} / {@code DeathTeleports.pending()} statics. Both collections
 * are concurrent and never replaced.
 */
public final class DeathTeleports {
  private static final Set<UUID> EFFECT_IN_FLIGHT = ConcurrentHashMap.newKeySet();
  private static final Map<UUID, TeleportPipelineTask> PENDING = new ConcurrentHashMap<>();

  private DeathTeleports() {
  }

  /** Players whose death effect is running before their respawn teleport. */
  public static Set<UUID> effectInFlight() {
    return EFFECT_IN_FLIGHT;
  }

  /** Respawn teleports waiting for the player to respawn, keyed by player. */
  public static Map<UUID, TeleportPipelineTask> pending() {
    return PENDING;
  }
}
