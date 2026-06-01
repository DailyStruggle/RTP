package io.github.dailystruggle.rtp.folia.scheduling;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.folia.world.FoliaRTPWorld;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

public class FoliaSchedulerImpl implements RTPScheduler {
  private final Plugin plugin;
  private final RegionScheduler regionScheduler;
  private final AsyncScheduler asyncScheduler;
  private final GlobalRegionScheduler globalScheduler;

  public FoliaSchedulerImpl(Plugin plugin) {
    this.plugin = plugin;
    this.regionScheduler = Bukkit.getRegionScheduler();
    this.asyncScheduler = Bukkit.getAsyncScheduler();
    this.globalScheduler = Bukkit.getGlobalRegionScheduler();
  }

  public FoliaSchedulerImpl(Plugin plugin, RegionScheduler rs, AsyncScheduler as, GlobalRegionScheduler gs) {
    this.plugin = plugin;
    this.regionScheduler = rs;
    this.asyncScheduler = as;
    this.globalScheduler = gs;
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
    if (!plugin.isEnabled()) {
      // Plugin is disabling/disabled; Folia's AsyncScheduler would throw IllegalPluginAccessException.
      // Drop the task silently — shutdown is an expected, non-teleport code path.
      return trackedTask;
    }
    asyncScheduler.runNow(plugin, scheduledTask -> trackedTask.run());
    return trackedTask;
  }

  @Override
  public void runTask(Runnable task) {
    if (org.bukkit.Bukkit.isGlobalTickThread()) {
      task.run();
    } else {
      if (!plugin.isEnabled()) return;
      globalScheduler.run(plugin, scheduledTask -> task.run());
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
        if (!plugin.isEnabled()) return;
        regionScheduler.run(plugin, foliaRTPWorld.world(), location.x() >> 4, location.z() >> 4, st -> task.run());
      }
    } else if (rtpWorld == null || rtpWorld.world() == null) {
      // TODO: THREAD-VIOLATION - Requires async bridge; null-world fallback crosses to @AsyncThread
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
        if (!plugin.isEnabled()) return;
        regionScheduler.run(plugin, foliaRTPWorld.world(), cx, cz, st -> task.run());
      }
    } else if (world == null || world.world() == null) {
      // TODO: THREAD-VIOLATION - Requires async bridge; null-world fallback crosses to @AsyncThread
      runTaskAsynchronously(task);
    } else {
      throw new IllegalArgumentException("World [" + world.name() + "] is not a Folia world");
    }
  }

  @Override
  public Object runTaskTimer(io.github.dailystruggle.rtp.api.world.RTPWorld<?> world, int cx, int cz, Runnable task, long delay, long period) {
    if (world instanceof FoliaRTPWorld && world.world() != null) {
      World bukkitWorld = ((FoliaRTPWorld) world).world();
      if (!plugin.isEnabled()) return null;
      return regionScheduler.runAtFixedRate(plugin, bukkitWorld, cx, cz, scheduledTask -> task.run(), Math.max(1, delay), Math.max(1, period));
    } else if (world == null || world.world() == null) {
      // TODO: THREAD-VIOLATION - Requires async bridge; null-world fallback crosses to @AsyncThread
      return runTaskTimerAsynchronously(task, delay, period);
    } else {
      throw new IllegalArgumentException("World [" + world.name() + "] is not a Folia world");
    }
  }

  @Override
  public void runTaskLater(io.github.dailystruggle.rtp.api.world.RTPWorld<?> world, int cx, int cz, Runnable task, long delay) {
    if (world instanceof FoliaRTPWorld && world.world() != null) {
      World bukkitWorld = ((FoliaRTPWorld) world).world();
      if (!plugin.isEnabled()) return;
      regionScheduler.runDelayed(plugin, bukkitWorld, cx, cz, scheduledTask -> task.run(), Math.max(1, delay));
    } else if (world == null || world.world() == null) {
      // TODO: THREAD-VIOLATION - Requires async bridge; null-world fallback crosses to @GlobalRegionThread
      runTaskLater(task, delay);
    } else {
      throw new IllegalArgumentException("World [" + world.name() + "] is not a Folia world");
    }
  }

  @Override
  public void runTaskLater(Runnable task, long delay) {
    if (!plugin.isEnabled()) return;
    globalScheduler.runDelayed(plugin, scheduledTask -> task.run(), Math.max(1, delay));
  }

  @Override
  public Object runTaskTimer(Runnable runnable, long delay, long period) {
    // Convert Bukkit ticks to Folia's expected tick format safely (minimum 1 tick)
    long safeDelay = Math.max(1, delay);
    long safePeriod = Math.max(1, period);

    if (!plugin.isEnabled()) return null;
    return globalScheduler.runAtFixedRate(
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

    if (!plugin.isEnabled()) return null;
    return asyncScheduler.runAtFixedRate(
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
  public void runTaskForPlayer(
      io.github.dailystruggle.rtp.api.entity.RTPPlayer player,
      io.github.dailystruggle.rtp.common.tasks.RTPRunnable task,
      long delayTicks) {
    String taskId = UUID.randomUUID().toString();
    TrackedRTPTask trackedTask = new TrackedRTPTask(task, taskId);
    if (RTPAPI.serverAccessor != null) {
      RTPAPI.serverAccessor.registerAction(trackedTask);
    }

    if (delayTicks <= 0) delayTicks = 1;
    if (!plugin.isEnabled()) return;

    // Delegate the entity-scheduler hop to FoliaRTPPlayer so the underlying
    // Bukkit Player reference stays encapsulated. Non-Folia implementations
    // (synthetic / mock players from test harnesses) and entities that the
    // scheduler refused fall through to the global region scheduler so the
    // runnable still fires (REQ-RTP-S-004: don't silently drop tasks).
    boolean scheduled = false;
    if (player instanceof io.github.dailystruggle.rtp.folia.entity.FoliaRTPPlayer foliaPlayer) {
      scheduled = foliaPlayer.scheduleOnSelf(trackedTask::run, null, delayTicks);
    }
    if (!scheduled) {
      globalScheduler.runDelayed(plugin, (scheduledTask) -> trackedTask.run(), delayTicks);
    }
  }
}
