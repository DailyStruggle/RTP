package io.github.dailystruggle.rtp.folia.scheduling;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.folia.world.FoliaRTPWorld;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

public class FoliaSchedulerImpl implements RTPScheduler {
  private final JavaPlugin plugin;

  public FoliaSchedulerImpl(JavaPlugin plugin) {
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
    Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> trackedTask.run());
    return trackedTask;
  }

  @Override
  public void runTask(Runnable task) {
    if (org.bukkit.Bukkit.isGlobalTickThread()) {
      task.run();
    } else {
      Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> task.run());
    }
  }

  @Override
  public void runTask(io.github.dailystruggle.rtp.api.world.RTPLocation location, Runnable task) {
    RTPWorld<?> rtpWorld = location.world();
    if (rtpWorld instanceof FoliaRTPWorld foliaRTPWorld && rtpWorld.world() != null) {
      if (org.bukkit.Bukkit.isOwnedByCurrentRegion(foliaRTPWorld.world(), location.x() >> 4, location.z() >> 4)) {
        // The current thread already owns the region! Execute immediately to save overhead.
        task.run();
      } else {
        // We are on the wrong thread; bounce it to the correct Region Scheduler
        Bukkit.getRegionScheduler().run(plugin, foliaRTPWorld.world(), location.x() >> 4, location.z() >> 4, st -> task.run());
      }
    } else if (rtpWorld == null || rtpWorld.world() == null) {
      runTaskAsynchronously(task);
    } else {
      throw new IllegalArgumentException("World [" + rtpWorld.name() + "] is not a Folia world");
    }
  }

  @Override
  public void runTask(io.github.dailystruggle.rtp.api.world.RTPWorld<?> world, int cx, int cz, Runnable task) {
    if (world instanceof FoliaRTPWorld foliaRTPWorld && world.world() != null) {
      if (org.bukkit.Bukkit.isOwnedByCurrentRegion(foliaRTPWorld.world(), cx, cz)) {
        // The current thread already owns the region! Execute immediately to save overhead.
        task.run();
      } else {
        // We are on the wrong thread; bounce it to the correct Region Scheduler
        Bukkit.getRegionScheduler().run(plugin, foliaRTPWorld.world(), cx, cz, st -> task.run());
      }
    } else if (world == null || world.world() == null) {
      runTaskAsynchronously(task);
    } else {
      throw new IllegalArgumentException("World [" + world.name() + "] is not a Folia world");
    }
  }

  @Override
  public Object runTaskTimer(io.github.dailystruggle.rtp.api.world.RTPWorld<?> world, int cx, int cz, Runnable task, long delay, long period) {
    if (world instanceof FoliaRTPWorld && world.world() != null) {
      World bukkitWorld = ((FoliaRTPWorld) world).world();
      return Bukkit.getRegionScheduler().runAtFixedRate(plugin, bukkitWorld, cx, cz, scheduledTask -> task.run(), Math.max(1, delay), Math.max(1, period));
    } else if (world == null || world.world() == null) {
      return runTaskTimerAsynchronously(task, delay, period);
    } else {
      throw new IllegalArgumentException("World [" + world.name() + "] is not a Folia world");
    }
  }

  @Override
  public void runTaskLater(io.github.dailystruggle.rtp.api.world.RTPWorld<?> world, int cx, int cz, Runnable task, long delay) {
    if (world instanceof FoliaRTPWorld && world.world() != null) {
      World bukkitWorld = ((FoliaRTPWorld) world).world();
      Bukkit.getRegionScheduler().runDelayed(plugin, bukkitWorld, cx, cz, scheduledTask -> task.run(), Math.max(1, delay));
    } else if (world == null || world.world() == null) {
      runTaskLater(task, delay);
    } else {
      throw new IllegalArgumentException("World [" + world.name() + "] is not a Folia world");
    }
  }

  @Override
  public void runTaskLater(Runnable task, long delay) {
    Bukkit.getGlobalRegionScheduler()
        .runDelayed(plugin, scheduledTask -> task.run(), Math.max(1, delay));
  }

  @Override
  public Object runTaskTimer(Runnable runnable, long delay, long period) {
    // Convert Bukkit ticks to Folia's expected tick format safely (minimum 1 tick)
    long safeDelay = Math.max(1, delay);
    long safePeriod = Math.max(1, period);

    return org.bukkit.Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            scheduledTask -> runnable.run(),
            safeDelay,
            safePeriod
    );
  }

  @Override
  public Object runTaskTimerAsynchronously(Runnable runnable, long delay, long period) {
    // Convert Bukkit ticks to milliseconds (1 tick = 50ms)
    long delayMs = Math.max(1, delay * 50);
    long periodMs = Math.max(1, period * 50);

    return org.bukkit.Bukkit.getAsyncScheduler().runAtFixedRate(
            plugin,
            scheduledTask -> runnable.run(),
            delayMs,
            periodMs,
            java.util.concurrent.TimeUnit.MILLISECONDS
    );
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
