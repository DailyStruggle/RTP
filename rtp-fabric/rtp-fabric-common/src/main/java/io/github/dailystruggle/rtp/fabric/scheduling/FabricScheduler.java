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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;

/**
 * Fabric {@link RTPScheduler} (MULTI_PLATFORM_PLAN Phase 2 Step C).
 * Async: a private cached thread-pool executor (see {@link #ASYNC_EXECUTOR}).
 * We deliberately do NOT use {@code net.minecraft.Util#backgroundExecutor()} —
 * its intermediary mapping ({@code class_156.method_18349}) drifts across MC
 * patch versions and triggers {@link NoSuchMethodError} at runtime, mirroring
 * the {@code SharedConstants} drift documented in {@code RTPFabricMod}. A
 * private executor is loader-API independent and version-stable.
 * Sync: {@link MinecraftServer#execute}
 * (inline if already on server thread). Delayed/repeating: tick-counted entries
 * drained by {@link #tick(MinecraftServer)} from {@code END_SERVER_TICK}.
 * Region-aware overloads delegate (no Folia regions on Fabric). Server ref is
 * set in {@link #setServer} from {@code SERVER_STARTED}; until then sync paths
 * fail loud per REQ-RTP-S-006. {@link #cancelTask} expects the Integer task id;
 * unknown types are tolerated as no-ops.
 */
public class FabricScheduler implements RTPScheduler {

  /**
   * Private cached thread pool used for all async work. Replaces
   * {@code net.minecraft.Util.backgroundExecutor()} which goes through
   * intermediary-named MC bytecode whose method id drifts between MC
   * releases. Daemon threads so a stuck task can't keep the JVM alive.
   */
  private static final Executor ASYNC_EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
    private final AtomicInteger n = new AtomicInteger(1);
    @Override public Thread newThread(Runnable r) {
      Thread t = new Thread(r, "RTP-Fabric-Async-" + n.getAndIncrement());
      t.setDaemon(true);
      return t;
    }
  });

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
    ASYNC_EXECUTOR.execute(tracked);
    return tracked;
  }

  @Override
  public Object runTaskTimerAsynchronously(Runnable task, long delay, long period) {
    // Async repeating: schedule on the tick queue but dispatch each fire to the worker pool.
    return scheduleTimer(() -> ASYNC_EXECUTOR.execute(task), delay, period);
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
