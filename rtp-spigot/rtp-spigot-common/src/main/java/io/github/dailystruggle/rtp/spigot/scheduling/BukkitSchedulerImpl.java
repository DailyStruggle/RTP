package io.github.dailystruggle.rtp.spigot.scheduling;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class BukkitSchedulerImpl implements RTPScheduler {
  private final JavaPlugin plugin;

  public BukkitSchedulerImpl(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public TrackedRTPTask runTaskAsynchronously(Runnable task) {
    String taskId = UUID.randomUUID().toString();
    TrackedRTPTask trackedTask = new TrackedRTPTask(task instanceof io.github.dailystruggle.rtp.common.tasks.RTPRunnable ? (io.github.dailystruggle.rtp.common.tasks.RTPRunnable) task : new io.github.dailystruggle.rtp.common.tasks.RTPRunnable() {
      @Override
      public void run() {
        task.run();
      }
    }, taskId);
    if (RTPAPI.serverAccessor != null) {
      RTPAPI.serverAccessor.registerAction(trackedTask);
    }
    Bukkit.getScheduler().runTaskAsynchronously(plugin, trackedTask);
    return trackedTask;
  }

  @Override
  public void runTask(Runnable task) {
    Bukkit.getScheduler().runTask(plugin, task);
  }

  @Override
  public void runTask(io.github.dailystruggle.rtp.api.world.RTPLocation location, Runnable task) {
    runTask(task);
  }

  @Override
  public void runTask(io.github.dailystruggle.rtp.api.world.RTPWorld<?> world, int cx, int cz, Runnable task) {
    runTask(task);
  }

  @Override
  public Object runTaskTimer(io.github.dailystruggle.rtp.api.world.RTPWorld<?> world, int cx, int cz, Runnable task, long delay, long period) {
    return runTaskTimer(task, delay, period);
  }

  @Override
  public void runTaskLater(Runnable task, long delay) {
    Bukkit.getScheduler().runTaskLater(plugin, task, delay);
  }

  @Override
  public Object runTaskTimer(Runnable task, long delay, long period) {
    return Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
  }

  @Override
  public Object runTaskTimerAsynchronously(Runnable task, long delay, long period) {
    return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
  }

  @Override
  public void cancelTask(Object task) {
    if (task instanceof org.bukkit.scheduler.BukkitTask) {
      ((org.bukkit.scheduler.BukkitTask) task).cancel();
    } else if (task instanceof Integer) {
      Bukkit.getScheduler().cancelTask((Integer) task);
    }
  }

  @Override
  public void scheduleTeleport(
      io.github.dailystruggle.rtp.api.entity.RTPPlayer player,
      io.github.dailystruggle.rtp.common.tasks.RTPRunnable task,
      long delayTicks) {
    String taskId = UUID.randomUUID().toString();
    TrackedRTPTask trackedTask = new TrackedRTPTask(task, taskId);
    if (RTPAPI.serverAccessor != null) {
      RTPAPI.serverAccessor.registerAction(trackedTask);
    }

    if (delayTicks < 1 && RTP.serverAccessor.isPrimaryThread()) {
      trackedTask.run();
    } else if (delayTicks > 0) {
      Bukkit.getScheduler().runTaskLater(plugin, trackedTask, delayTicks);
    } else {
      Bukkit.getScheduler().runTask(plugin, trackedTask);
    }
  }
}
