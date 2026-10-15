package io.github.dailystruggle.rtp.neoforge.scheduling;

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
 * NeoForge {@link RTPScheduler}.
 *
 * <p>Structurally identical to {@code FabricScheduler}: NeoForge servers are
 * single-main-thread (no Folia regions), so the threading model is the same as
 * vanilla / Fabric (NEOFORGE_NOTES.md section 2 "Threading"). Only the wiring point
 * differs - the {@code @Mod} entry point ({@code RTPNeoForgeMod}) drives
 * {@link #tick(MinecraftServer)} from the NeoForge {@code ServerTickEvent.Post}
 * game-bus event and sets/clears the server reference from
 * {@code ServerStartedEvent} / {@code ServerStoppingEvent}.</p>
 *
 * <ul>
 *   <li><b>Async:</b> a private daemon bounded thread pool ({@link #ASYNC_EXECUTOR}).</li>
 *   <li><b>Sync:</b> {@link MinecraftServer#execute} (inline if already on the
 *       server thread). Submissions made before {@link #setServer} are buffered
 *       and drained on {@code ServerStartedEvent}, matching Bukkit's always-queue
 *       {@code runTask} semantics.</li>
 *   <li><b>Delayed / repeating:</b> tick-counted entries drained by {@link #tick}
 *       under the {@link TickBudget#syncNanos()} budget; due callbacks past the
 *       budget run first on the next tick.</li>
 *   <li>Region-aware overloads delegate (no regions on NeoForge).</li>
 * </ul>
 */
public class NeoForgeScheduler implements RTPScheduler {

  /** Async worker cap: half the cores (min 2) so RTP never starves the server or other mods. */
  static final int ASYNC_MAX_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);

  /**
   * Private bounded daemon thread pool used for all async work. Unbounded
   * queue: excess work waits instead of spawning threads (with a
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
            Thread t = new Thread(r, "RTP-NeoForge-Async-" + n.getAndIncrement());
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
          Thread t = new Thread(r, "RTP-NeoForge-AsyncTimer-" + n.getAndIncrement());
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
   * Pre-start buffer. Tasks submitted before {@link #setServer(MinecraftServer)}
   * (i.e. before {@code ServerStartedEvent}) are queued here and drained once the
   * server is available, mirroring Bukkit's always-queue {@code runTask}.
   */
  private final ConcurrentLinkedQueue<Runnable> preStartQueue = new ConcurrentLinkedQueue<>();

  /** Sets the live server reference; drains pre-start submissions. Called from {@code ServerStartedEvent}. */
  public void setServer(MinecraftServer server) {
    this.server = server;
    Runnable r;
    while ((r = preStartQueue.poll()) != null) {
      try {
        server.execute(r);
      } catch (Throwable t) {
        RTP.log(java.util.logging.Level.WARNING,
            "[NeoForgeScheduler] failed to drain pre-start task", t);
      }
    }
  }

  /** Clears the server reference. Called from {@code ServerStoppingEvent}. */
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
   * from {@code ServerTickEvent.Post}.
   *
   * @param server the live server (passed through for parity with the event signature)
   */
  public void tick(MinecraftServer server) {
    drainDueWithinTickBudget();
  }

  /** MC-free entry point; the test classpath has no {@code MinecraftServer}. */
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
            "[NeoForgeScheduler] scheduled task threw", t);
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
            "[NeoForgeScheduler] async timer task threw", t);
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
            "[NeoForgeScheduler] async timer task dispatch failed", t);
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
    // Other token types: no-op (lenient contract, matches Fabric/Bukkit).
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

  // NeoForge is Mojmap-at-runtime, so the deobf method name getRunningThread()
  // resolves cleanly on 1.20.4+. We still resolve reflectively to stay robust
  // against Mojmap method-name drift across MC revs (NEOFORGE_NOTES.md section 9), the
  // same defensive posture as FabricScheduler.
  private static volatile java.lang.reflect.Method GET_RUNNING_THREAD;

  private static Thread serverRunningThread(MinecraftServer s) {
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
