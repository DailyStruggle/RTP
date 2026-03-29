package io.github.dailystruggle.rtp.folia.scheduling;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class FoliaSchedulerImpl implements RTPScheduler {
  private final JavaPlugin plugin;

  public FoliaSchedulerImpl(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public void runTaskAsynchronously(Runnable task) {
    Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
  }

  @Override
  public void runTask(Runnable task) {
    Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> task.run());
  }

  @Override
  public void runTaskLater(Runnable task, long delay) {
    Bukkit.getGlobalRegionScheduler()
        .runDelayed(plugin, scheduledTask -> task.run(), Math.max(1, delay));
  }

  @Override
  public Object runTaskTimer(Runnable task, long delay, long period) {
    return Bukkit.getGlobalRegionScheduler()
        .runAtFixedRate(
            plugin, scheduledTask -> task.run(), Math.max(1, delay), Math.max(1, period));
  }

  @Override
  public void cancelTask(Object task) {
    if (task instanceof ScheduledTask) {
      ((ScheduledTask) task).cancel();
    }
  }

  @Override
  public void scheduleTeleport(
      io.github.dailystruggle.rtp.api.entity.RTPPlayer player,
      io.github.dailystruggle.rtp.common.tasks.RTPRunnable task,
      long delayTicks) {
    org.bukkit.entity.Player bukkitPlayer = Bukkit.getPlayer(player.uuid());
    if (bukkitPlayer == null) return;

    String taskId = UUID.randomUUID().toString();
    TrackedRTPTask trackedTask = new TrackedRTPTask(task, taskId);
    if (RTPAPI.serverAccessor != null) {
      RTPAPI.serverAccessor.registerAction(trackedTask);
    }

    if (delayTicks <= 0) delayTicks = 1;
    bukkitPlayer
        .getScheduler()
        .runDelayed(plugin, (scheduledTask) -> trackedTask.run(), null, delayTicks);
  }
}
