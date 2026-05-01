package io.github.dailystruggle.rtp.fabric.scheduling;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;

/**
 * Fabric implementation of {@link RTPScheduler}.
 *
 * <p>Threading model (Phase 2 Step C of MULTI_PLATFORM_PLAN.md):
 * <ul>
 *   <li><b>Async</b> — dispatched via {@link Util#backgroundExecutor()}, the canonical
 *       Mojang-provided shared executor for off-thread work on MC 1.21.1. Mirrors
 *       {@code rtp-paper-common}'s async chunk pool semantics.</li>
 *   <li><b>Sync</b> — dispatched via {@link MinecraftServer#execute(Runnable)} which queues
 *       the task onto the next tick on the server thread. If already on the server thread,
 *       runs inline (0-tick delay), matching {@code BukkitSchedulerImpl}.</li>
 *   <li><b>Delayed / repeating</b> — backed by a {@link ConcurrentHashMap} of tick-counted
 *       entries; {@link #tick(MinecraftServer)} is invoked from {@code ServerTickEvents
 *       .END_SERVER_TICK} (registered by {@code RTPFabricMod} in Step E) and drains due
 *       entries onto the server thread.</li>
 *   <li><b>Region-aware overloads</b> — Fabric has no Folia-style region threading; these
 *       delegate to the non-region overloads. Same convention as {@code BukkitSchedulerImpl}
 *       on Spigot/Paper.</li>
 * </ul>
 *
 * <p>Lifecycle: the {@link MinecraftServer} reference is set via {@link #setServer} from
 * {@code ServerLifecycleEvents.SERVER_STARTED} (Step E). Until then, sync paths fall back to
 * inline execution if already on the server thread, or {@link IllegalStateException} otherwise
 * (REQ-RTP-S-006 fail-loud).
 *
 * <p>Cancellation: {@link #cancelTask(Object)} accepts the {@link Integer} task id returned by
 * the timer methods. Unknown task types are tolerated as no-ops to match {@code
 * BukkitSchedulerImpl}'s lenient contract.
 */
public class FabricScheduler implements RTPScheduler {

  private volatile MinecraftServer server;

  private final AtomicInteger nextTaskId = new AtomicInteger(1);
  private final ConcurrentHashMap<Integer, ScheduledEntry> scheduled = new ConcurrentHashMap<>();

  /** Sets the live server reference. Called from {@code ServerLifecycleEvents.SERVER_STARTED}. */
  public void setServer(MinecraftServer server) {
    this.server = server;
  }

  /** Clears the server reference. Called from {@code ServerLifecycleEvents.SERVER_STOPPING}. */
  public void clearServer() {
    this.server = null;
    this.scheduled.clear();
  }

  /**
   * Drains due scheduled entries. Invoked from {@code ServerTickEvents.END_SERVER_TICK}.
   *
   * @param server the live server (passed through for parity with the event signature)
   */
  public void tick(MinecraftServer server) {
    if (scheduled.isEmpty()) return;
    List<Integer> toRemove = new ArrayList<>();
    for (var e : scheduled.entrySet()) {
      ScheduledEntry entry = e.getValue();
      if (entry.cancelled) {
        toRemove.add(e.getKey());
        continue;
      }
      if (--entry.remainingTicks <= 0) {
        try {
          entry.task.run();
        } catch (Throwable t) {
          RTP.log(java.util.logging.Level.WARNING,
              "[FabricScheduler] scheduled task threw", t);
        }
        if (entry.periodTicks > 0) {
          entry.remainingTicks = entry.periodTicks;
        } else {
          toRemove.add(e.getKey());
        }
      }
    }
    for (Integer id : toRemove) scheduled.remove(id);
  }

  // ---------------------------------------------------------------- async ---

  @Override
  public TrackedRTPTask runTaskAsynchronously(Runnable task) {
    String taskId = UUID.randomUUID().toString();
    TrackedRTPTask tracked = new TrackedRTPTask(asRtpRunnable(task), taskId);
    if (RTPAPI.serverAccessor != null) {
      RTPAPI.serverAccessor.registerAction(tracked);
    }
    Util.backgroundExecutor().execute(tracked);
    return tracked;
  }

  @Override
  public Object runTaskTimerAsynchronously(Runnable task, long delay, long period) {
    // Async repeating: schedule on the tick queue but dispatch each fire to the worker pool.
    return scheduleTimer(() -> Util.backgroundExecutor().execute(task), delay, period);
  }

  // ----------------------------------------------------------------- sync ---

  @Override
  public void runTask(Runnable task) {
    MinecraftServer s = this.server;
    if (s != null && Thread.currentThread() == s.getRunningThread()) {
      task.run();
      return;
    }
    if (s != null) {
      s.execute(task);
      return;
    }
    // No server yet — fail loud per REQ-RTP-S-006.
    throw new IllegalStateException(
        "FabricScheduler.runTask called before MinecraftServer started "
            + "(register ServerLifecycleEvents.SERVER_STARTED -> setServer first)");
  }

  @Override
  public void runTask(RTPLocation location, Runnable task) {
    runTask(task);
  }

  @Override
  public void runTask(RTPWorld<?> world, int cx, int cz, Runnable task) {
    runTask(task);
  }

  @Override
  public void runTaskLater(Runnable task, long delay) {
    scheduleTimer(task, delay, 0L);
  }

  @Override
  public void runTaskLater(RTPWorld<?> world, int cx, int cz, Runnable task, long delay) {
    runTaskLater(task, delay);
  }

  @Override
  public Object runTaskTimer(Runnable task, long delay, long period) {
    return scheduleTimer(task, delay, period);
  }

  @Override
  public Object runTaskTimer(RTPWorld<?> world, int cx, int cz, Runnable task, long delay, long period) {
    return runTaskTimer(task, delay, period);
  }

  @Override
  public void cancelTask(Object task) {
    if (task instanceof Integer id) {
      ScheduledEntry e = scheduled.get(id);
      if (e != null) e.cancelled = true;
    }
    // Other token types: no-op (matches BukkitSchedulerImpl's lenient contract).
  }

  @Override
  public void scheduleTeleport(RTPPlayer player, RTPRunnable task, long delayTicks) {
    String taskId = UUID.randomUUID().toString();
    TrackedRTPTask tracked = new TrackedRTPTask(task, taskId);
    if (RTPAPI.serverAccessor != null) {
      RTPAPI.serverAccessor.registerAction(tracked);
    }
    MinecraftServer s = this.server;
    if (delayTicks < 1 && s != null && Thread.currentThread() == s.getRunningThread()) {
      tracked.run();
    } else if (delayTicks > 0) {
      scheduleTimer(tracked, delayTicks, 0L);
    } else {
      runTask(tracked);
    }
  }

  // -------------------------------------------------------------- helpers ---

  private Integer scheduleTimer(Runnable task, long delay, long period) {
    int id = nextTaskId.getAndIncrement();
    ScheduledEntry e = new ScheduledEntry();
    e.task = task;
    e.remainingTicks = Math.max(1L, delay);
    e.periodTicks = Math.max(0L, period);
    scheduled.put(id, e);
    return id;
  }

  private static RTPRunnable asRtpRunnable(Runnable task) {
    if (task instanceof RTPRunnable r) return r;
    return new RTPRunnable() {
      @Override
      public void run() {
        task.run();
      }
    };
  }

  private static final class ScheduledEntry {
    Runnable task;
    long remainingTicks;
    long periodTicks;
    volatile boolean cancelled;
  }
}
