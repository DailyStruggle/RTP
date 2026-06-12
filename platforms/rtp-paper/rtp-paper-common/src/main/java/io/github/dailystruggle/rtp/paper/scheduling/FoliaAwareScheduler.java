package io.github.dailystruggle.rtp.paper.scheduling;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Basic Folia-aware {@link RTPScheduler} for the free (lite) build (ADR-024 / ADR-061).
 *
 * <p>The free LeafRTP build ships the Paper adapter, which the Bukkit-family {@code
 * BukkitSchedulerImpl} drives through {@code Bukkit.getScheduler()} — calls that throw on
 * Folia. This implementation provides a correctness-first regionized path so the free build
 * runs on Folia without locking the operator out of the software: it routes work through the
 * regionized scheduler statics that paper-api exposes (global / region / async) and the
 * per-entity scheduler for player work.
 *
 * <p>This is deliberately the <em>basic</em> path. The tuned {@code rtp-folia} adapter
 * (off-tick pre-filter integration, region-aware queue saturation, the Folia thread checker)
 * remains a LeafRTP-Pro early-access feature and is not bundled in the lite jar.
 *
 * <p>Only instantiated when {@code BukkitServerProvider.isFolia()} is true, so the regionized
 * statics are guaranteed to be live.
 */
public class FoliaAwareScheduler implements RTPScheduler {
  private final Plugin plugin;
  private final RegionScheduler regionScheduler;
  private final AsyncScheduler asyncScheduler;
  private final GlobalRegionScheduler globalScheduler;

  // Constructor signature mirrors the other RTPScheduler impls so BootstrapSupport's
  // getDeclaredConstructor(JavaPlugin.class) reflective instantiation resolves it.
  public FoliaAwareScheduler(JavaPlugin plugin) {
    this.plugin = plugin;
    this.regionScheduler = Bukkit.getRegionScheduler();
    this.asyncScheduler = Bukkit.getAsyncScheduler();
    this.globalScheduler = Bukkit.getGlobalRegionScheduler();
  }

  private static World bukkitWorld(RTPWorld<?> world) {
    if (world == null) return null;
    Object handle = world.world();
    return (handle instanceof World) ? (World) handle : null;
  }

  @Override
  public TrackedRTPTask runTaskAsynchronously(Runnable task) {
    String taskId = UUID.randomUUID().toString();
    TrackedRTPTask trackedTask =
        new TrackedRTPTask(
            task instanceof RTPRunnable
                ? (RTPRunnable) task
                : new RTPRunnable() {
                  @Override
                  public void run() {
                    task.run();
                  }
                },
            taskId);
    if (RTPAPI.serverAccessor != null) {
      RTPAPI.serverAccessor.registerAction(trackedTask);
    }
    if (!plugin.isEnabled()) {
      // Plugin disabling/disabled; Folia's AsyncScheduler would throw. Shutdown is an
      // expected, non-teleport path — drop silently rather than spam the log.
      return trackedTask;
    }
    asyncScheduler.runNow(plugin, scheduledTask -> trackedTask.run());
    return trackedTask;
  }

  @Override
  public void runTask(Runnable task) {
    // paper-api (the lite compile target) does not expose Folia's isGlobalTickThread(), so the
    // "already on the global thread, run inline" optimization the tuned rtp-folia adapter uses is
    // unavailable here. Correctness-first: always hop to the global region scheduler.
    if (!plugin.isEnabled()) return;
    globalScheduler.run(plugin, scheduledTask -> task.run());
  }

  @Override
  public void runTask(RTPLocation location, Runnable task) {
    World world = bukkitWorld(location == null ? null : location.world());
    if (world == null) {
      runTaskAsynchronously(task);
      return;
    }
    int cx = location.x() >> 4;
    int cz = location.z() >> 4;
    if (Bukkit.isOwnedByCurrentRegion(world, cx, cz)) {
      task.run();
    } else {
      if (!plugin.isEnabled()) return;
      regionScheduler.run(plugin, world, cx, cz, st -> task.run());
    }
  }

  @Override
  public void runTask(RTPWorld<?> world, int cx, int cz, Runnable task) {
    World bukkitWorld = bukkitWorld(world);
    if (bukkitWorld == null) {
      runTaskAsynchronously(task);
      return;
    }
    if (Bukkit.isOwnedByCurrentRegion(bukkitWorld, cx, cz)) {
      task.run();
    } else {
      if (!plugin.isEnabled()) return;
      regionScheduler.run(plugin, bukkitWorld, cx, cz, st -> task.run());
    }
  }

  @Override
  public Object runTaskTimer(RTPWorld<?> world, int cx, int cz, Runnable task, long delay, long period) {
    World bukkitWorld = bukkitWorld(world);
    if (bukkitWorld == null) {
      return runTaskTimerAsynchronously(task, delay, period);
    }
    if (!plugin.isEnabled()) return null;
    return regionScheduler.runAtFixedRate(
        plugin, bukkitWorld, cx, cz, st -> task.run(), Math.max(1, delay), Math.max(1, period));
  }

  @Override
  public void runTaskLater(RTPWorld<?> world, int cx, int cz, Runnable task, long delay) {
    World bukkitWorld = bukkitWorld(world);
    if (bukkitWorld == null) {
      runTaskLater(task, delay);
      return;
    }
    if (!plugin.isEnabled()) return;
    regionScheduler.runDelayed(plugin, bukkitWorld, cx, cz, st -> task.run(), Math.max(1, delay));
  }

  @Override
  public void runTaskLater(Runnable task, long delay) {
    if (!plugin.isEnabled()) return;
    globalScheduler.runDelayed(plugin, scheduledTask -> task.run(), Math.max(1, delay));
  }

  @Override
  public Object runTaskTimer(Runnable runnable, long delay, long period) {
    if (!plugin.isEnabled()) return null;
    return globalScheduler.runAtFixedRate(
        plugin, scheduledTask -> runnable.run(), Math.max(1, delay), Math.max(1, period));
  }

  @Override
  public Object runTaskTimerAsynchronously(Runnable runnable, long delay, long period) {
    if (!plugin.isEnabled()) return null;
    // Async scheduler is wall-clock based: 1 tick == 50 ms.
    return asyncScheduler.runAtFixedRate(
        plugin,
        scheduledTask -> runnable.run(),
        Math.max(1, delay * 50),
        Math.max(1, period * 50),
        TimeUnit.MILLISECONDS);
  }

  @Override
  public void cancelTask(Object task) {
    if (task instanceof ScheduledTask) {
      ((ScheduledTask) task).cancel();
    }
  }

  @Override
  public void runTaskForPlayer(
      io.github.dailystruggle.rtp.api.entity.RTPPlayer player, RTPRunnable task, long delayTicks) {
    String taskId = UUID.randomUUID().toString();
    TrackedRTPTask trackedTask = new TrackedRTPTask(task, taskId);
    if (RTPAPI.serverAccessor != null) {
      RTPAPI.serverAccessor.registerAction(trackedTask);
    }
    if (delayTicks <= 0) delayTicks = 1;
    if (!plugin.isEnabled()) return;

    // Prefer the player's own entity scheduler so the runnable lands on the region thread
    // that owns the player (correct for teleports). Synthetic/mock players or a player the
    // scheduler refuses (e.g. mid-disconnect) fall through to the global region scheduler so
    // the task still fires — REQ-RTP-S-004: never silently drop a teleport task.
    boolean scheduled = false;
    Player bukkitPlayer = null;
    if (player instanceof io.github.dailystruggle.rtp.bukkitplatform.entity.BukkitRTPPlayer) {
      bukkitPlayer =
          ((io.github.dailystruggle.rtp.bukkitplatform.entity.BukkitRTPPlayer) player).player();
    }
    if (bukkitPlayer != null) {
      final TrackedRTPTask t = trackedTask;
      ScheduledTask st =
          bukkitPlayer.getScheduler().runDelayed(plugin, ignored -> t.run(), null, delayTicks);
      scheduled = (st != null);
    }
    if (!scheduled) {
      globalScheduler.runDelayed(plugin, scheduledTask -> trackedTask.run(), delayTicks);
    }
  }
}
