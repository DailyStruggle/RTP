package io.github.dailystruggle.rtp.bukkit.bukkitListeners;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
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
    if (RTP.getInstance().queuedPlayers.contains(uuid)) return;

    RTP.getInstance().invulnerablePlayers.remove(uuid);
    RTP.getInstance().processingPlayers.remove(uuid);

    // ADR-055: forget any native PvP combat tag so a reconnecting player is
    // never spuriously gated and the tracker map does not leak across sessions.
    io.github.dailystruggle.rtp.common.pvp.PvPGate.nativeTracker().clear(uuid);

    TeleportData data = RTP.getInstance().latestTeleportData.get(uuid);
    if (data != null && !data.completed) {
      if (data.nextTask instanceof TeleportPipelineTask task) {
        task.setCancelled(true);
        if (task.coords() != null) {
          RTP.scheduler.runTask(
                  task.region().getWorld(), task.coords().x() >> 4, task.coords().z() >> 4, task);
        } else {
          RTP.scheduler.runTask(task);
        }
      }
    }

    new RTPTeleportCancel(uuid).run();

    // CHECKLIST-maps-api.md Stage 2.2 / REQ-RTP-MAP-003 — bridge the
    // PlayerQuitEvent into the MapDispatch lifecycle bus so any registered
    // MapBindingLifecycle (e.g. BukkitMapBinding) can release per-viewer
    // cached MapHandles. Mirrors the registration pattern used by
    // commands-api / effects-api: maps-api defines the SPI, rtp-core owns
    // the registry, the platform listener forwards the event. Best-effort,
    // guarded — the quit listener must never throw.
    try {
      io.github.dailystruggle.rtp.common.commands.maps.MapDispatch.firePlayerQuit(uuid);
    } catch (Throwable t) {
      RTP.log(java.util.logging.Level.WARNING,
          "[RTP] MapDispatch.firePlayerQuit failed for " + uuid + ": " + t, t);
    }

    // ADR-043 — Close the player's personal coordinate bucket on every
    // region. The bucket was opened by OnPlayerJoin / OnPlayerRespawn /
    // OnPlayerChangeWorld under the `rtp.personalqueue` opt-in, and
    // leaking it across a disconnect would strand banked coordinates'
    // chunk reservations (S-002 adjacent) and grow
    // `perPlayerLocationQueue` without bound under churn. Idempotent,
    // best-effort, guarded — the quit listener must never throw.
    try {
      for (io.github.dailystruggle.rtp.common.selection.region.Region region :
          RTP.selectionAPI.permRegionLookup.values()) {
        try {
          region.closePersonalQueue(uuid);
        } catch (Throwable t) {
          RTP.log(java.util.logging.Level.WARNING,
              "[RTP] closePersonalQueue failed for " + uuid + " in region "
                  + region.name + ": " + t, t);
        }
      }
    } catch (Throwable ignored) {
      // never let the quit listener throw
    }

    // ADR-023 — Login Reserve Cache lazy refill: a slot just opened up, so
    // dispatch a single-entry promotion on every region that has the buffer
    // enabled (in practice only the default-world region per ADR-023).
    try {
      for (io.github.dailystruggle.rtp.common.selection.region.Region region :
          RTP.selectionAPI.permRegionLookup.values()) {
        if (region.queueManager.loginLocations == null) continue;
        new io.github.dailystruggle.rtp.common.selection.region.LoginCacheTask(region)
            .promoteUpTo(1);
      }
    } catch (Throwable t) {
      RTP.getInstance(); // no-op guard; quit listener must never throw
    }
  }
}
