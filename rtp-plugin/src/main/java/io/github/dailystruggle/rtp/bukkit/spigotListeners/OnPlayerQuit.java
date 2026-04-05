package io.github.dailystruggle.rtp.bukkit.spigotListeners;

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
    RTP.getInstance().invulnerablePlayers.remove(uuid);
    RTP.getInstance().processingPlayers.remove(uuid);

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
  }
}
