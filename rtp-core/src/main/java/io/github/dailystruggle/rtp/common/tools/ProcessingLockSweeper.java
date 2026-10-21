package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.common.network.LobbyDispatchRetryQueue;
import io.github.dailystruggle.rtp.common.network.NetworkModeBootstrap;
import io.github.dailystruggle.rtp.common.network.NetworkStatusCache;

import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.tasks.teleport.DeathTeleports;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Safety net for leaked {@code processingPlayers} entries (a player refused as "already
 * teleporting" until they quit). Runs inside the {@link MemoryTracker} sweep.
 *
 * <p>An entry is released only when it has no owner on two consecutive sweeps: not queued, no
 * death hold, no live tracked pipeline or TeleportData, no cross-server enrolment or retry. A
 * legitimate long wait always has an owner, so it is never cut. Every release logs a WARNING
 * naming the player (S-004), so the leaking path stays visible.
 */
public final class ProcessingLockSweeper {

  /** Locks seen unowned on the previous sweep; released if still unowned on the next. */
  private static final Set<UUID> suspects = ConcurrentHashMap.newKeySet();

  private ProcessingLockSweeper() {
  }

  /**
   * Releases locks unowned on two consecutive sweeps.
   *
   * @param livePipelines players with a live, non-leaking tracked pipeline task
   * @param liveData      players with a live tracked TeleportData
   * @return number of locks released
   */
  static int sweep(Set<UUID> livePipelines, Set<UUID> liveData) {
    TeleportSessions sessions = TeleportSessions.current();
    if (sessions == null) {
      return 0;
    }
    Set<UUID> locks = TeleportSessions.current().processingPlayers;
    suspects.retainAll(locks);
    int released = 0;
    for (UUID id : locks) {
      if (unownedTwice(id, livePipelines, liveData) && locks.remove(id)) {
        released++;
        RTPRuntime.log(Level.WARNING, "[RTP] released stale teleport lock for player " + id
            + " (no queue, pipeline, death hold or network enrolment owned it across two"
            + " sweeps); the player can /sessions again (S-004)");
      }
    }
    return released;
  }

  /** Updates the suspect set for one lock; true when it is unowned on a second consecutive sweep. */
  private static boolean unownedTwice(UUID id, Set<UUID> livePipelines, Set<UUID> liveData) {
    if (liveData.contains(id) || isHeld(id, livePipelines)) {
      suspects.remove(id);
      return false;
    }
    if (suspects.add(id)) {
      return false; // first unowned sighting: grace for in-between handoffs
    }
    suspects.remove(id);
    return true;
  }

  /**
   * True when something other than a TeleportData record owns the player's lock.
   *
   * @param livePipelines players with a live, non-leaking tracked pipeline task
   */
  static boolean isHeld(UUID id, Set<UUID> livePipelines) {
    if (id == null) {
      return false;
    }
    if (livePipelines.contains(id)) {
      return true;
    }
    TeleportSessions sessions = TeleportSessions.current();
    if (sessions != null && TeleportSessions.current().queuedPlayers.contains(id)) {
      return true;
    }
    if (DeathTeleports.effectInFlight().contains(id) || DeathTeleports.pending().containsKey(id)) {
      return true;
    }
    return heldByNetwork(id);
  }

  private static boolean heldByNetwork(UUID id) {
    NetworkModeBootstrap live = NetworkModeBootstrap.LIVE;
    if (live == null) {
      return false;
    }
    try {
      NetworkStatusCache cache = live.statusCache();
      if (cache != null
          && cache.get(id).map(NetworkStatusCache.QueueStatus::nonTerminal).orElse(false)) {
        return true;
      }
      LobbyDispatchRetryQueue retry = live.lobbyRetryQueue();
      return retry != null && retry.holds(id);
    } catch (RuntimeException e) {
      // Unknown network state: keep the lock rather than risk a duplicate cross-server request.
      return true;
    }
  }

  /** Clears sweep state. Visible for tests. */
  static void reset() {
    suspects.clear();
  }
}
