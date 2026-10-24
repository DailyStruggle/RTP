package io.github.dailystruggle.rtp.bukkit.bukkitListeners;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.action.ActionServices;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.tasks.teleport.DeathTeleports;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public final class OnPlayerQuit implements Listener {
  @EventHandler(priority = EventPriority.LOWEST)
  public void onPlayerQuit(PlayerQuitEvent event) {
    UUID uuid = event.getPlayer().getUniqueId();
    TeleportSessions.current().queuedPlayers.remove(uuid);

    TeleportPipelineTask pendingDeath = DeathTeleports.pending().remove(uuid);
    if (pendingDeath != null) {
      pendingDeath.completeDeathTeleport(false);
    }

    TeleportSessions.current().invulnerablePlayers.remove(uuid);
    TeleportSessions.current().processingPlayers.remove(uuid);

    // ADR-055: forget any native PvP combat tag so a reconnecting player is
    // never spuriously gated and the tracker map does not leak across sessions.
    io.github.dailystruggle.rtp.common.pvp.PvPGate.nativeTracker().clear(uuid);

    TeleportData data = TeleportSessions.current().latestTeleportData.get(uuid);
    if (data != null) {
      if (!data.completed) {
        if (data.nextTask instanceof TeleportPipelineTask task) {
          task.setCancelled(true);
          if (task.coords() != null) {
            RTPRuntime.scheduler().runTask(
                    task.region().getWorld(), task.coords().x() >> 4, task.coords().z() >> 4, task);
          } else {
            RTPRuntime.scheduler().runTask(task);
          }
        }
      } else {
        // Player completed teleport: offload to disk file bin and evict from local heap
        if (RTP.getInstance().fileBinStore != null) {
          RTP.getInstance().fileBinStore.save(data);
        }
        TeleportSessions.current().latestTeleportData.remove(uuid);
        TeleportSessions.current().priorTeleportData.remove(uuid);
        TeleportSessions.current().regionTeleportTimes.remove(uuid);
      }
    }

    new RTPTeleportCancel(uuid).run();

    try {
      if (ActionServices.actionManager() != null) {
        ActionServices.actionManager().handlePlayerQuit(uuid);
      }
    } catch (Throwable ignored) {
      // never let the quit listener throw
    }

    // CHECKLIST-maps-api.md Stage 2.2 / REQ-RTP-MAP-003 - bridge the
    // PlayerQuitEvent into the MapDispatch lifecycle bus so any registered
    // MapBindingLifecycle (e.g. BukkitMapBinding) can release per-viewer
    // cached MapHandles. Mirrors the registration pattern used by
    // commands-api / effects-api: maps-api defines the SPI, rtp-core owns
    // the registry, the platform listener forwards the event. Best-effort,
    // guarded - the quit listener must never throw.
    try {
      io.github.dailystruggle.rtp.common.commands.maps.MapDispatch.firePlayerQuit(uuid);
    } catch (Throwable t) {
      RTP.log(java.util.logging.Level.WARNING,
          "[RTP] MapDispatch.firePlayerQuit failed for " + uuid + ": " + t, t);
    }

    // ADR-043 - Close the player's personal coordinate bucket on every
    // region. The bucket was opened by OnPlayerJoin / OnPlayerRespawn /
    // OnPlayerChangeWorld under the `rtp.personalqueue` opt-in, and
    // leaking it across a disconnect would strand banked coordinates'
    // chunk reservations (S-002 adjacent) and grow
    // `perPlayerLocationQueue` without bound under churn. Idempotent,
    // best-effort, guarded - the quit listener must never throw.
    try {
      java.util.Collection<io.github.dailystruggle.rtp.common.selection.region.Region> regions =
          new java.util.ArrayList<>(ActiveSelection.get().permRegionLookup.values());
      regions.addAll(ActiveSelection.get().tempRegions.values());
      for (io.github.dailystruggle.rtp.common.selection.region.Region region : regions) {
        try {
          region.closePersonalQueue(uuid);
          java.util.concurrent.CompletableFuture<?> fastLoc =
              region.queueManager.fastLocations.remove(uuid);
          if (fastLoc != null && !fastLoc.isDone()) {
            fastLoc.cancel(true);
          }
        } catch (Throwable t) {
          RTP.log(java.util.logging.Level.WARNING,
              "[RTP] closePersonalQueue failed for " + uuid + " in region "
                  + region.name + ": " + t, t);
        }
      }
    } catch (Throwable ignored) {
      // never let the quit listener throw
    }

    // ADR-023 - Login Reserve Cache lazy refill: a slot just opened up, so
    // dispatch a single-entry promotion on every region that has the buffer
    // enabled (in practice only the default-world region per ADR-023).
    try {
      for (io.github.dailystruggle.rtp.common.selection.region.Region region :
          ActiveSelection.get().permRegionLookup.values()) {
        if (region.queueManager.loginLocations == null) continue;
        new io.github.dailystruggle.rtp.common.selection.region.LoginCacheTask(region)
            .promoteUpTo(1);
      }
    } catch (Throwable t) {
      RTP.getInstance(); // no-op guard; quit listener must never throw
    }
  }
}
