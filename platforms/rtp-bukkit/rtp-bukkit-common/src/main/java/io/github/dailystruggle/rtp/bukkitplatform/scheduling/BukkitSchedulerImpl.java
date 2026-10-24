package io.github.dailystruggle.rtp.bukkitplatform.scheduling;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.tools.GenerationBudget;
import io.github.dailystruggle.rtp.common.tools.ServerHealthGate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class BukkitSchedulerImpl implements RTPScheduler {
  private final JavaPlugin plugin;
  private final AtomicBoolean tickWatchStarted = new AtomicBoolean(false);

  public BukkitSchedulerImpl(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public TrackedRTPTask runTaskAsynchronously(Runnable task) {
    if (task == null) return null;
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
    if (task == null) return;
    // If we are already on the main thread, execute instantly (0ms delay)
    if (org.bukkit.Bukkit.isPrimaryThread()) {
      task.run();
    } else {
      // Otherwise, queue it for the next tick (~50ms delay)
      org.bukkit.Bukkit.getScheduler().runTask(plugin, task);
    }
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
  public void runTaskLater(io.github.dailystruggle.rtp.api.world.RTPWorld<?> world, int cx, int cz, Runnable task, long delay) {
    runTaskLater(task, delay);
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

  /**
   * Whether the server's chunk system serves requests first-come, first-served, so background
   * fill must wait for RTP's earlier loads (vanilla Spigot). Paper's chunk system prioritises
   * player chunks; its subclasses return {@code false}.
   */
  protected boolean holdsBackGeneration() {
    return true;
  }

  @Override
  public boolean isGenerationSaturated(io.github.dailystruggle.rtp.api.world.RTPWorld<?> world) {
    boolean holdsBack = holdsBackGeneration();
    GenerationBudget.configureServerAdmission(holdsBack);
    if (!holdsBack) return false;
    startTickWatch();
    return GenerationBudget.server().saturated();
  }

  /**
   * Marks tick progress for {@link ServerHealthGate#tickStalled}: a 1-tick task that stops
   * running means the main thread is stuck. With nobody online the server may pause between
   * ticks (vanilla {@code pause-when-empty-seconds}), so the watch records idle instead.
   */
  private void startTickWatch() {
    if (!tickWatchStarted.compareAndSet(false, true)) return;
    try {
      Bukkit.getScheduler().runTaskTimer(plugin, () -> {
        ServerHealthGate.tickFinished();
        if (!Bukkit.getOnlinePlayers().isEmpty()) ServerHealthGate.tickStarted();
      }, 1L, 1L);
    } catch (Throwable t) {
      tickWatchStarted.set(false);
      RTPRuntime.log(java.util.logging.Level.FINE,
          "[RTP] tick watch not started yet: " + t.getClass().getSimpleName() + ": " + t.getMessage());
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

    if (delayTicks < 1 && RTPRuntime.serverAccessor().isPrimaryThread()) {
      trackedTask.run();
    } else if (delayTicks > 0) {
      Bukkit.getScheduler().runTaskLater(plugin, trackedTask, delayTicks);
    } else {
      Bukkit.getScheduler().runTask(plugin, trackedTask);
    }
  }
}
