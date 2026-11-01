package io.github.dailystruggle.rtp.bukkit.bukkitListeners;

import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.teleport.DeathTeleports;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

// get queued location
public final class OnPlayerRespawn implements Listener {

  public OnPlayerRespawn() {
    // Explicit public constructor for event listener
  }

  @EventHandler(priority = EventPriority.HIGH)
  public void onPlayerRespawn(PlayerRespawnEvent event) {
    Player player = event.getPlayer();
    TeleportPipelineTask pendingDeath = DeathTeleports.pending().remove(player.getUniqueId());
    if (pendingDeath != null) {
      pendingDeath.completeDeathTeleport(true);
    }
    if (player.hasPermission("rtp.personalqueue")) {
      Region region =
          ActiveSelection.get().getRegion(RTPRuntime.serverAccessor().getPlayer(player.getUniqueId()));
      if (region == null) return;
      // ADR-043: bucket-only opt-in (see OnPlayerJoin for the contract).
      region.openPersonalQueue(player.getUniqueId());
    }
  }
}
