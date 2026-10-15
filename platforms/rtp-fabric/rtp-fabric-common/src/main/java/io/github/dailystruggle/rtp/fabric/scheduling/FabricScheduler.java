package io.github.dailystruggle.rtp.fabric.scheduling;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.tick.TickBudget;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;

/**
 * Fabric {@link RTPScheduler}.
 * Async: a private bounded thread-pool executor (see {@link #ASYNC_EXECUTOR}).
 * We deliberately do NOT use {@code net.minecraft.Util#backgroundExecutor()} -
 * its intermediary mapping ({@code class_156.method_18349}) drifts across MC
 * patch versions and triggers {@link NoSuchMethodError} at runtime, mirroring
 * the {@code SharedConstants} drift documented in {@code RTPFabricMod}. A
 * private executor is loader-API independent and version-stable.
 * Sync: {@link MinecraftServer#execute}
 * (inline if already on server thread). Delayed/repeating: tick-counted entries
 * drained by {@link #tick(MinecraftServer)} from {@code END_SERVER_TICK} under
 * the {@link TickBudget#syncNanos()} budget; due callbacks past the budget run
 * first on the next tick.
 * Region-aware overloads delegate (no Folia regions on Fabric). Server ref is
 * set in {@link #setServer} from {@code SERVER_STARTED}; sync submissions made
 * before that point are buffered in {@link #preStartQueue} and drained once
 * the server thread becomes available, matching Bukkit's always-queue
 * {@code runTask} semantics. {@link #cancelTask} expects the Integer task id;
 * unknown types are tolerated as no-ops.
 */
public class FabricScheduler implements RTPScheduler {

  /** Async worker cap: half the cores (min 2) so RTP never starves the server or other mods. */
  static final int ASYNC_MAX_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);

  /**
   * Private bounded thread pool used for all async work. Replaces
   * {@code net.minecraft.Util.backgroundExecutor()} which goes through
   * intermediary-named MC bytecode whose method id drifts between MC
   * releases. Daemon threads so a stuck task can't keep the JVM alive.
   * Unbounded queue: excess work waits instead of spawning threads (with a
   * LinkedBlockingQueue the pool never grows past its core size, so core = max).
   */
  private static final ThreadPoolExecutor ASYNC_EXECUTOR = newAsyncExecutor();

  private static ThreadPoolExecutor newAsyncExecutor() {
    ThreadPoolExecutor pool = new ThreadPoolExecutor(
        ASYNC_MAX_THREADS, ASYNC_MAX_THREADS, 60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(),
        new ThreadFactory() {
          private final AtomicInteger n = new AtomicInteger(1);
          @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "RTP-Fabric-Async-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
          }
        });
    pool.allowCoreThreadTimeOut(true);
    return pool;
  }

  static ThreadPoolExecutor asyncExecutor() {
    return ASYNC_EXECUTOR;
  }

  /**
   * Wall-clock scheduled executor for asynchronous timers (e.g. heartbeat publishing).
   * Runs independently of server ticks so paused servers with no players continue
   * publishing state across the network (RTP-3).
   */
  private static final java.util.concurrent.ScheduledExecutorService ASYNC_TIMER_EXECUTOR =
      Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger(1);
        @Override public Thread newThread(Runnable r) {
          Thread t = new Thread(r, "RTP-Fabric-AsyncTimer-" + n.getAndIncrement());
          t.setDaemon(true);
          return t;
        }
      });

  private volatile MinecraftServer server;

  private final AtomicInteger nextTaskId = new AtomicInteger(1);
  private final ConcurrentHashMap<Integer, ScheduledEntry> scheduled = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Integer, java.util.concurrent.ScheduledFuture<?>> asyncTimers = new ConcurrentHashMap<>();

  /**
   * Due entries not yet run, FIFO. Entries left over when the sync budget ran
   * out stay at the head so they run before anything that becomes due later.
   */
  private final ConcurrentLinkedDeque<ScheduledEntry> pendingDue = new ConcurrentLinkedDeque<>();

  /**
   * Pre-start buffer. Tasks submitted to {@link #runTask(Runnable)} before
   * {@link #setServer(MinecraftServer)} has been invoked from
   * {@code ServerLifecycleEvents.SERVER_STARTED} are queued here and drained
   * once the server is available, mirroring Bukkit's {@code runTask} which
   * always queues onto the next tick rather than failing. This protects the
   * region pre-fill / database startup path from dropping work during the
   * narrow window between mod-init and {@code SERVER_STARTED}.
   */
  private final ConcurrentLinkedQueue<Runnable> preStartQueue = new ConcurrentLinkedQueue<>();

  /**
   * Sets the live server reference. Called from {@code ServerLifecycleEvents.SERVER_STARTED}.
   * Drains any tasks that were submitted before the server was available.
   */
  public void setServer(MinecraftServer server) {
    this.server = server;
    // Drain pre-start submissions onto the server thread now that it exists.
    Runnable r;
    while ((r = preStartQueue.poll()) != null) {
      try {
        server.execute(r);
      } catch (Throwable t) {
        RTP.log(java.util.logging.Level.WARNING,
            "[FabricScheduler] failed to drain pre-start task", t);
      }
    }
  }

  /** Clears the server reference. Called from {@code ServerLifecycleEvents.SERVER_STOPPING}. */
  public void clearServer() {
    this.server = null;
    for (var f : asyncTimers.values()) {
      try { f.cancel(false); } catch (Throwable ignored) {}
    }
    this.asyncTimers.clear();
    this.scheduled.clear();
    this.pendingDue.clear();
    this.preStartQueue.clear();
  }

  /**
   * Drains due scheduled entries within {@link TickBudget#syncNanos()}. Invoked
   * from {@code ServerTickEvents.END_SERVER_TICK}.
   *
   * @param server the live server (passed through for parity with the event signature)
   */
  public void tick(MinecraftServer server) {
    drainDueWithinTickBudget();
  }

  /** MC-free entry point, mirrored from NeoForgeScheduler whose test classpath lacks MC. */
  void drainDueWithinTickBudget() {
    if (scheduled.isEmpty()) return;
    drainDue(TickBudget.syncNanos());
  }

  /**
   * One tick of the sync loop. Deferred entries keep their place and are not
   * re-counted, so a due task is never dropped or pushed back a full period;
   * at least one callback runs per tick so the backlog always shrinks.
   */
  void drainDue(long budgetNanos) {
    long start = System.nanoTime();
    List<ScheduledEntry> newlyDue = new ArrayList<>();
    List<Integer> toRemove = new ArrayList<>();
    for (var e : scheduled.entrySet()) {
      ScheduledEntry entry = e.getValue();
      if (entry.cancelled) {
        toRemove.add(e.getKey());
        continue;
      }
      if (entry.pending) continue;
      if (--entry.remainingTicks <= 0) {
        entry.pending = true;
        newlyDue.add(entry);
      }
    }
    for (Integer id : toRemove) scheduled.remove(id);
    // Ids grow with submission order; CHM iteration order does not.
    newlyDue.sort(Comparator.comparingInt(en -> en.id));
    pendingDue.addAll(newlyDue);

    int ran = 0;
    ScheduledEntry entry;
    while ((entry = pendingDue.peekFirst()) != null) {
      if (ran > 0 && System.nanoTime() - start >= budgetNanos) break;
      pendingDue.pollFirst();
      if (entry.cancelled || scheduled.get(entry.id) != entry) {
        scheduled.remove(entry.id, entry);
        continue;
      }
      ran++;
      try {
        entry.task.run();
      } catch (Throwable t) {
        RTP.log(java.util.logging.Level.WARNING,
            "[FabricScheduler] scheduled task threw", t);
      }
      entry.pending = false;
      if (entry.periodTicks > 0 && !entry.cancelled) {
        entry.remainingTicks = entry.periodTicks;
      } else {
        scheduled.remove(entry.id, entry);
      }
    }
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
    int id = nextTaskId.getAndIncrement();
    long delayMs = Math.max(0L, delay * 50L);
    long periodMs = Math.max(50L, period * 50L);
    // Skip a firing while the previous one still runs, so a slow callback
    // cannot fan out across every pool thread.
    AtomicBoolean running = new AtomicBoolean(false);
    Runnable body = () -> {
      try {
        task.run();
      } catch (Throwable t) {
        RTP.log(java.util.logging.Level.WARNING,
            "[FabricScheduler] async timer task threw", t);
      } finally {
        running.set(false);
      }
    };
    java.util.concurrent.ScheduledFuture<?> future = ASYNC_TIMER_EXECUTOR.scheduleAtFixedRate(() -> {
      if (!running.compareAndSet(false, true)) return;
      try {
        ASYNC_EXECUTOR.execute(body);
      } catch (Throwable t) {
        running.set(false);
        RTP.log(java.util.logging.Level.WARNING,
            "[FabricScheduler] async timer task dispatch failed", t);
      }
    }, delayMs, periodMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    asyncTimers.put(id, future);
    return id;
  }

  // ----------------------------------------------------------------- sync ---

  @Override
  public void runTask(Runnable task) {
    if (task == null) return;
    MinecraftServer s = this.server;
    if (s != null && Thread.currentThread() == serverRunningThread(s)) {
      task.run();
      return;
    }
    if (s != null) {
      s.execute(task);
      return;
    }
    // No server yet - buffer the task and drain on SERVER_STARTED. This
    // matches Bukkit's runTask semantics (always-queue, never fail) and
    // prevents the early-dispatch warning cascade observed on Fabric where
    // region-thread evaluation fired before setServer had been called.
    preStartQueue.offer(task);
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
      java.util.concurrent.ScheduledFuture<?> f = asyncTimers.remove(id);
      if (f != null) f.cancel(false);
      ScheduledEntry e = scheduled.get(id);
      if (e != null) e.cancelled = true;
    }
    // Other token types: no-op (matches BukkitSchedulerImpl's lenient contract).
  }

  @Override
  public void runTaskForPlayer(RTPPlayer player, RTPRunnable task, long delayTicks) {
    String taskId = UUID.randomUUID().toString();
    TrackedRTPTask tracked = new TrackedRTPTask(task, taskId);
    if (RTPAPI.serverAccessor != null) {
      RTPAPI.serverAccessor.registerAction(tracked);
    }
    MinecraftServer s = this.server;
    if (delayTicks < 1 && s != null && Thread.currentThread() == serverRunningThread(s)) {
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
    e.id = id;
    e.task = task;
    e.remainingTicks = Math.max(1L, delay);
    e.periodTicks = Math.max(0L, period);
    scheduled.put(id, e);
    return id;
  }

  // Cached reflective accessor for MinecraftServer's main-thread getter.
  // The common module is compiled with Loom + officialMojangMappings (1.21.1
  // namespace), so a direct call to s.getRunningThread() ends up baked into
  // bytecode as the intermediary method_3777, which does not exist on the
  // deobfuscated MC 26.1.2 runtime. Resolve the method by name from the live
  // class instance to avoid pinning any intermediary descriptor in the
  // constant pool. On 1.20/1.21 runtimes the same call still resolves cleanly.
  private static volatile java.lang.reflect.Method GET_RUNNING_THREAD;

  private static Thread serverRunningThread(MinecraftServer s) {
    // Prefer the per-version adapter's typed override. Falls through to the
    // reflective resolver for adapters that don't override (1.20 / 1.21 family).
    try {
      io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
          io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
      if (adapter != null) {
        Thread t = adapter.getServerThread(s);
        if (t != null) return t;
      }
    } catch (Throwable ignored) {
      // adapter registry not yet bound (early bootstrap) - fall through
    }
    try {
      java.lang.reflect.Method m = GET_RUNNING_THREAD;
      if (m == null) {
        m = s.getClass().getMethod("getRunningThread");
        m.setAccessible(true);
        GET_RUNNING_THREAD = m;
      }
      return (Thread) m.invoke(s);
    } catch (ReflectiveOperationException e) {
      return null;
    }
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
    int id;
    Runnable task;
    long remainingTicks;
    long periodTicks;
    /** Due and queued in {@link #pendingDue}; countdown frozen until it runs. */
    boolean pending;
    volatile boolean cancelled;
  }
}
