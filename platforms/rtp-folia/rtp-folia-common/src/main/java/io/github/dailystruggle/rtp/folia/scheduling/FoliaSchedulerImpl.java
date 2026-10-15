package io.github.dailystruggle.rtp.folia.scheduling;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.tick.TickBudget;
import io.github.dailystruggle.rtp.common.tools.ServerHealthGate;
import io.github.dailystruggle.rtp.folia.world.FoliaRTPWorld;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Folia {@link RTPScheduler} (ADR-087, ADR-112). Regional work is divided per region, never
 * concatenated onto one tick thread.
 *
 * <p>Each one-shot regional task joins the FIFO of its cell ({@code 2^CELL_SHIFT} chunks square,
 * no larger than a Folia region section, so one cell is owned by one region). One slice per
 * cell runs on the owning region thread: it runs tasks until that thread's
 * {@link RegionThreadBudget} ({@code syncAllottedTime} per region thread) is spent, always at
 * least one, then re-queues behind every other waiting cell. A hot region therefore gets its
 * share each tick and no more, and other regions keep progressing. A region whose slice was
 * dispatched late or whose tick is unhealthy (ADR-087 regional circuit breaker) runs one task
 * per slice and reports {@link #isRegionSaturated} so callers aim new work elsewhere.
 *
 * <p>Limiter contract:
 * <ul>
 *   <li>A permit pool capped at {@code max(1, cores / 2)} bounds slices in flight, so RTP never
 *       instantiates disjoint regions faster than the worker pool ticks them. Permits are taken
 *       by CAS and released exactly once per lease (success, exception, rejected submit,
 *       cancelled slice reclaimed by the safety pump).</li>
 *   <li>Every one-shot regional overload is limited; delayed variants wait on the global
 *       scheduler and join the cell queue when they fire, so a pending delay never pins a region.</li>
 *   <li>A caller that already owns the region runs inline only while its thread has budget and
 *       the cell has nothing queued (FIFO); otherwise the task waits for the next slice. Inline
 *       runs take no permit: they create no region, and blocking for one could deadlock.</li>
 *   <li>Interactive requests keep their own priority queue and deadline; their cost is charged to
 *       the budget but they are never deferred by it.</li>
 *   <li>Repeating regional timers are exempt: few and long-lived; a lifetime permit would starve
 *       one-shot work.</li>
 *   <li>Waiting slices form one FIFO drained on every permit release; interactive requests go
 *       first unless the oldest slice has aged past {@link #DEFERRED_STARVATION_NANOS}. A single
 *       {@link #SAFETY_PUMP_PERIOD_MS} async timer reclaims cancelled leases and re-arms any
 *       stranded cell.</li>
 * </ul>
 */
public class FoliaSchedulerImpl implements RTPScheduler {
  /** Safety-pump cadence; only reclaims leaked permits and re-drains, never polls work. */
  static final long SAFETY_PUMP_PERIOD_MS = 1000L;
  /** Background entries waiting this long are served ahead of interactive ones (anti-starvation). */
  static final long DEFERRED_STARVATION_NANOS = TimeUnit.SECONDS.toNanos(2);
  /** Cell edge as a chunk shift; below Folia's default 16-chunk section so a cell never spans regions. */
  static final int CELL_SHIFT = 3;
  /** Slice dispatch-to-run latency above this marks the region backlogged (ADR-087 canary). */
  static final long DISPATCH_LAG_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
  /** Queued tasks at which a cell reports saturated, so callers stop adding to it. */
  static final int SATURATED_DEPTH = 64;
  /** Count cap per slice (REQ-FOLIA-ARCH-005): bounds a slice even when every task reads as free. */
  static final int MAX_TASKS_PER_SLICE = 32;

  /** Per-region tick health; production reads {@link ServerHealthGate#regionUnhealthy}. */
  @FunctionalInterface
  interface RegionHealth {
    boolean unhealthy(World world, int cx, int cz);
  }

  private final Plugin plugin;
  private final RegionScheduler regionScheduler;
  private final AsyncScheduler asyncScheduler;
  private final GlobalRegionScheduler globalScheduler;

  private final AtomicInteger inFlightRegionalTasks = new AtomicInteger(0);
  private final ConcurrentLinkedQueue<InteractiveWaitEntry> interactiveQueue = new ConcurrentLinkedQueue<>();
  private final ConcurrentLinkedQueue<DeferredDispatch> deferredQueue = new ConcurrentLinkedQueue<>();
  private final Set<Lease> leases = ConcurrentHashMap.newKeySet();
  /** Work-in-progress counter: one drainer at a time, no recursion, no lost wakeups. */
  private final AtomicInteger pumpWip = new AtomicInteger(0);
  private final AtomicBoolean safetyPumpStarted = new AtomicBoolean(false);
  private final ConcurrentHashMap<CellKey, CellQueue> cells = new ConcurrentHashMap<>();
  private final int maxRegionalInflight;
  private final long defaultInteractiveTimeoutMs;
  private final RegionThreadBudget budget;
  private final RegionHealth health;

  private record InteractiveWaitEntry(
      RTPWorld<?> world,
      int cx,
      int cz,
      Runnable task,
      Runnable onTimeout,
      long deadlineNanos,
      AtomicBoolean completed) {}

  private record DeferredDispatch(CellQueue cell, long enqueuedNanos) {}

  /** World identity (CraftWorld is one instance per world) plus cell coordinates. */
  private record CellKey(World world, int sx, int sz) {}

  private record CellTask(int cx, int cz, Runnable task) {}

  /** FIFO of one cell's background work; at most one slice is queued or running at a time. */
  private static final class CellQueue {
    final CellKey key;
    final ConcurrentLinkedQueue<CellTask> tasks = new ConcurrentLinkedQueue<>();
    final AtomicInteger depth = new AtomicInteger();
    final AtomicBoolean scheduled = new AtomicBoolean(false);
    /** Last slice ran late or on an unhealthy region. */
    volatile boolean yielding;

    CellQueue(CellKey key) {
      this.key = key;
    }
  }

  /** One acquired permit. {@link #release()} is idempotent so every exit path may call it. */
  private final class Lease {
    private final AtomicBoolean held = new AtomicBoolean(true);
    private volatile ScheduledTask handle;
    /** Cell whose slice holds this lease; lets the safety pump re-arm a cancelled slice. */
    private volatile CellQueue cell;

    void release() {
      if (held.compareAndSet(true, false)) {
        leases.remove(this);
        inFlightRegionalTasks.decrementAndGet();
        pumpQueue();
      }
    }
  }

  public FoliaSchedulerImpl(Plugin plugin) {
    this(plugin, Bukkit.getRegionScheduler(), Bukkit.getAsyncScheduler(), Bukkit.getGlobalRegionScheduler());
  }

  public FoliaSchedulerImpl(Plugin plugin, RegionScheduler rs, AsyncScheduler as, GlobalRegionScheduler gs) {
    this(plugin, rs, as, gs, Math.max(1, Runtime.getRuntime().availableProcessors() / 2), 2500L);
  }

  public FoliaSchedulerImpl(Plugin plugin, RegionScheduler rs, AsyncScheduler as, GlobalRegionScheduler gs,
                            int maxRegionalInflight, long defaultInteractiveTimeoutMs) {
    this(plugin, rs, as, gs, maxRegionalInflight, defaultInteractiveTimeoutMs,
        new RegionThreadBudget(System::nanoTime, TickBudget::syncNanos), FoliaSchedulerImpl::gateUnhealthy);
  }

  FoliaSchedulerImpl(Plugin plugin, RegionScheduler rs, AsyncScheduler as, GlobalRegionScheduler gs,
                     int maxRegionalInflight, long defaultInteractiveTimeoutMs,
                     RegionThreadBudget budget, RegionHealth health) {
    this.plugin = plugin;
    this.regionScheduler = rs;
    this.asyncScheduler = as;
    this.globalScheduler = gs;
    this.maxRegionalInflight = Math.max(1, maxRegionalInflight);
    this.defaultInteractiveTimeoutMs = Math.max(100L, defaultInteractiveTimeoutMs);
    this.budget = budget;
    this.health = health;
  }

  /** Fails open: a missing or throwing metrics source never stalls regional work. */
  private static boolean gateUnhealthy(World world, int cx, int cz) {
    try {
      UUID id = world.getUID();
      return id != null && ServerHealthGate.regionUnhealthy(id, cx, cz);
    } catch (Throwable t) {
      return false;
    }
  }

  public int getInFlightRegionalTasks() {
    return inFlightRegionalTasks.get();
  }

  public int getMaxRegionalInflight() {
    return maxRegionalInflight;
  }

  public int getInteractiveQueueSize() {
    return interactiveQueue.size();
  }

  /** Region slices waiting for a permit. */
  public int getDeferredQueueSize() {
    return deferredQueue.size();
  }

  /** Background regional tasks queued across every cell, including those of running slices. */
  public int getQueuedRegionalTasks() {
    int n = 0;
    for (CellQueue q : cells.values()) n += Math.max(0, q.depth.get());
    return n;
  }

  /** Cells with queued or running background work. */
  public int getActiveCellCount() {
    return cells.size();
  }

  /**
   * Dispatches an interactive regional task with priority queueing and a strict deadline (ADR-087).
   *
   * @param world     target RTP world
   * @param cx        chunk X
   * @param cz        chunk Z
   * @param task      task to execute upon regional dispatch
   * @param onTimeout runnable invoked if timeout expires before worker permit becomes available
   */
  public void runInteractiveTask(RTPWorld<?> world, int cx, int cz, Runnable task, Runnable onTimeout) {
    runInteractiveTask(world, cx, cz, task, onTimeout, defaultInteractiveTimeoutMs);
  }

  /**
   * Dispatches an interactive regional task with priority queueing and an explicit deadline.
   */
  public void runInteractiveTask(RTPWorld<?> world, int cx, int cz, Runnable task, Runnable onTimeout, long timeoutMs) {
    if (!plugin.isEnabled()) {
      if (onTimeout != null) onTimeout.run();
      return;
    }
    if (world != null && world.world() != null && !(world instanceof FoliaRTPWorld)) {
      throw new IllegalArgumentException("World [" + world.name() + "] is not a Folia world");
    }

    if (world instanceof FoliaRTPWorld foliaRTPWorld && foliaRTPWorld.world() != null) {
      if (Bukkit.isOwnedByCurrentRegion(foliaRTPWorld.world(), cx, cz)) {
        task.run();
        return;
      }
      observeRegionForMetrics(world, cx, cz);
    }

    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    InteractiveWaitEntry entry = new InteractiveWaitEntry(world, cx, cz, task, onTimeout, deadline, new AtomicBoolean(false));

    interactiveQueue.offer(entry);

    // Per-request deadline watchdog: fail fast if no permit frees up in time.
    asyncScheduler.runDelayed(plugin, st -> {
      if (System.nanoTime() >= entry.deadlineNanos && entry.completed.compareAndSet(false, true)) {
        interactiveQueue.remove(entry);
        runTimeout(entry);
      }
    }, timeoutMs, TimeUnit.MILLISECONDS);

    ensureSafetyPump();
    pumpQueue();
  }

  private Lease tryAcquireLease() {
    for (;;) {
      int current = inFlightRegionalTasks.get();
      if (current >= maxRegionalInflight) return null;
      if (inFlightRegionalTasks.compareAndSet(current, current + 1)) {
        Lease lease = new Lease();
        leases.add(lease);
        return lease;
      }
    }
  }

  /** Drains queued dispatches while permits are free. Safe from any thread; never recurses. */
  private void pumpQueue() {
    if (pumpWip.getAndIncrement() != 0) return; // the active drainer re-loops for this request
    int missed = 1;
    do {
      try {
        drainQueues();
      } catch (Throwable t) {
        RTP.log(Level.WARNING, "[RTP] Folia regional dispatch queue drain failed", t);
      }
      missed = pumpWip.addAndGet(-missed);
    } while (missed != 0);
  }

  private void drainQueues() {
    for (;;) {
      InteractiveWaitEntry interactive = peekLiveInteractive();
      DeferredDispatch deferred = deferredQueue.peek();
      if (interactive == null && deferred == null) return;

      Lease lease = tryAcquireLease();
      if (lease == null) return; // saturated; the next release re-pumps

      boolean takeDeferred = deferred != null
          && (interactive == null || System.nanoTime() - deferred.enqueuedNanos() >= DEFERRED_STARVATION_NANOS);
      if (takeDeferred) {
        DeferredDispatch next = deferredQueue.poll(); // sole consumer: same entry as peeked
        if (next == null) {
          lease.release();
          continue;
        }
        if (!plugin.isEnabled()) {
          // Shutdown: Folia would reject the submit; pending background work is moot.
          int dropped = dropCell(next.cell());
          lease.release();
          RTP.log(Level.FINE, "[RTP] Plugin disabled; dropped " + dropped + " queued regional task(s)");
          continue;
        }
        dispatchSlice(next.cell(), lease);
      } else {
        if (!interactiveQueue.remove(interactive) || !interactive.completed.compareAndSet(false, true)) {
          lease.release(); // timed out or removed concurrently
          continue;
        }
        dispatchInteractive(interactive, lease);
      }
    }
  }

  /** Head of the interactive queue that is still runnable; expired heads fire their timeout. */
  private InteractiveWaitEntry peekLiveInteractive() {
    for (;;) {
      InteractiveWaitEntry head = interactiveQueue.peek();
      if (head == null) return null;
      if (head.completed.get()) {
        interactiveQueue.remove(head);
        continue;
      }
      if (System.nanoTime() >= head.deadlineNanos) {
        interactiveQueue.remove(head);
        if (head.completed.compareAndSet(false, true)) runTimeout(head);
        continue;
      }
      return head;
    }
  }

  /** A throwing timeout callback must not abort the drain loop (S-004: logged, not swallowed). */
  private static void runTimeout(InteractiveWaitEntry entry) {
    if (entry.onTimeout == null) return;
    try {
      entry.onTimeout.run();
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[RTP] Interactive regional timeout callback failed", t);
    }
  }

  private void dispatchInteractive(InteractiveWaitEntry entry, Lease lease) {
    if (!plugin.isEnabled()) {
      lease.release();
      runTimeout(entry);
      return;
    }
    if (entry.world instanceof FoliaRTPWorld foliaRTPWorld && foliaRTPWorld.world() != null) {
      dispatchLeased(foliaRTPWorld.world(), entry.cx, entry.cz, entry.task, lease);
      return;
    }
    // Null-world fallback (validated at submit): no region to bound, but keep the lease paired.
    try {
      runTaskAsynchronously(() -> {
        try {
          entry.task.run();
        } finally {
          lease.release();
        }
      });
    } catch (Throwable t) {
      lease.release();
      RTP.log(Level.WARNING, "[RTP] Interactive fallback dispatch failed", t);
    }
  }

  /** Submits an interactive {@code task} to the owning region holding {@code lease}; released on every path. */
  private void dispatchLeased(World world, int cx, int cz, Runnable task, Lease lease) {
    try {
      lease.handle = regionScheduler.run(plugin, world, cx, cz, st -> {
        try {
          runMeasured(task, cx, cz);
        } finally {
          lease.release();
        }
      });
    } catch (Throwable t) {
      lease.release();
      RTP.log(Level.WARNING, "[RTP] Regional dispatch rejected at chunk (" + cx + "," + cz + ")", t);
    }
  }

  /** Runs a task charged to this thread's budget; failures are logged, never thrown (S-004). */
  private void runMeasured(Runnable task, int cx, int cz) {
    long span = budget.begin();
    try {
      task.run();
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[RTP] Regional task failed at chunk (" + cx + "," + cz + ")", t);
    } finally {
      budget.end(span);
    }
  }

  private static CellKey cellKey(World world, int cx, int cz) {
    return new CellKey(world, cx >> CELL_SHIFT, cz >> CELL_SHIFT);
  }

  /** Limited one-shot regional submit: joins its cell's FIFO and arms that cell's slice. */
  private void submitRegional(World world, int cx, int cz, Runnable task) {
    CellQueue q = cells.computeIfAbsent(cellKey(world, cx, cz), CellQueue::new);
    q.depth.incrementAndGet();
    q.tasks.offer(new CellTask(cx, cz, task));
    requestSlice(q);
  }

  /** Arms one slice for {@code q}: dispatch now if a permit is free and nobody waits, else queue FIFO. */
  private void requestSlice(CellQueue q) {
    if (!q.scheduled.compareAndSet(false, true)) return; // a slice is queued or running
    if (deferredQueue.isEmpty() && interactiveQueue.isEmpty()) {
      Lease lease = tryAcquireLease();
      if (lease != null) {
        dispatchSlice(q, lease);
        return;
      }
    }
    deferredQueue.offer(new DeferredDispatch(q, System.nanoTime()));
    ensureSafetyPump();
    pumpQueue(); // closes the race with a release that drained just before the offer
  }

  /** Submits {@code q}'s slice to the region owning its head task; the lease is released on every path. */
  private void dispatchSlice(CellQueue q, Lease lease) {
    CellTask head = q.tasks.peek();
    if (head == null) {
      lease.release();
      finishSlice(q);
      return;
    }
    lease.cell = q;
    final long submitted = budget.now();
    try {
      lease.handle = regionScheduler.run(plugin, q.key.world(), head.cx(), head.cz(),
          st -> runSlice(q, lease, submitted, head));
    } catch (Throwable t) {
      lease.cell = null;
      int dropped = dropCell(q);
      lease.release();
      RTP.log(Level.WARNING, "[RTP] Regional dispatch rejected at chunk (" + head.cx() + "," + head.cz()
          + "); dropped " + dropped + " queued task(s)", t);
    }
  }

  /**
   * Region-thread body of one slice: FIFO tasks until this thread's budget is spent (at least
   * one), or exactly one when the region is backlogged or unhealthy. Leftovers re-queue behind
   * every other waiting cell.
   */
  private void runSlice(CellQueue q, Lease lease, long submittedNanos, CellTask anchor) {
    World world = q.key.world();
    try {
      boolean late = budget.now() - submittedNanos > DISPATCH_LAG_NANOS;
      boolean yielding = late || health.unhealthy(world, anchor.cx(), anchor.cz());
      q.yielding = yielding;
      // Off a region thread (tests) ownership is unknowable; on one, strays are re-routed.
      boolean onAnchor = Bukkit.isOwnedByCurrentRegion(world, anchor.cx(), anchor.cz());
      int ran = 0;
      for (;;) {
        CellTask t = q.tasks.peek();
        if (t == null) break;
        if (ran > 0 && (yielding || ran >= MAX_TASKS_PER_SLICE || !budget.hasBudget())) break;
        q.tasks.poll(); // sole consumer while scheduled: same entry as peeked
        q.depth.decrementAndGet();
        if (onAnchor && (t.cx() != anchor.cx() || t.cz() != anchor.cz())
            && !Bukkit.isOwnedByCurrentRegion(world, t.cx(), t.cz())) {
          dispatchStray(world, t); // grid exponent below CELL_SHIFT split this cell
          continue;
        }
        runMeasured(t.task(), t.cx(), t.cz());
        ran++;
      }
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[RTP] Regional slice failed at chunk (" + anchor.cx() + "," + anchor.cz() + ")", t);
    } finally {
      lease.cell = null;
      lease.release();
      finishSlice(q);
    }
  }

  /** Task whose chunk belongs to another region than its cell's anchor: run it there, unlimited. */
  private void dispatchStray(World world, CellTask t) {
    try {
      regionScheduler.run(plugin, world, t.cx(), t.cz(), st -> runMeasured(t.task(), t.cx(), t.cz()));
    } catch (Throwable ex) {
      RTP.log(Level.WARNING, "[RTP] Regional dispatch rejected at chunk (" + t.cx() + "," + t.cz() + ")", ex);
    }
  }

  /** Ends a slice: re-arm behind other cells while work remains, else retire the cell. */
  private void finishSlice(CellQueue q) {
    q.scheduled.set(false);
    if (!q.tasks.isEmpty()) {
      requestSlice(q);
      return;
    }
    cells.remove(q.key, q);
    if (!q.tasks.isEmpty()) requestSlice(q); // a producer raced the retire; q still drains itself
  }

  /** Discards a cell's queued work (shutdown or rejected submit); returns how many were dropped. */
  private int dropCell(CellQueue q) {
    int dropped = 0;
    while (q.tasks.poll() != null) {
      q.depth.decrementAndGet();
      dropped++;
    }
    q.scheduled.set(false);
    cells.remove(q.key, q);
    return dropped;
  }

  private void ensureSafetyPump() {
    if (!safetyPumpStarted.compareAndSet(false, true)) return;
    try {
      asyncScheduler.runAtFixedRate(plugin, st -> safetyPump(),
          SAFETY_PUMP_PERIOD_MS, SAFETY_PUMP_PERIOD_MS, TimeUnit.MILLISECONDS);
    } catch (Throwable t) {
      safetyPumpStarted.set(false);
      RTP.log(Level.WARNING, "[RTP] Folia regional safety pump could not be scheduled", t);
    }
  }

  /** Reclaims permits whose region task was cancelled before running, re-arms stranded cells, re-drains. */
  void safetyPump() {
    for (Lease lease : leases) {
      ScheduledTask handle = lease.handle;
      if (handle != null && lease.held.get()
          && handle.getExecutionState() == ScheduledTask.ExecutionState.CANCELLED) {
        RTP.log(Level.WARNING, "[RTP] Regional task was cancelled before running; reclaiming its permit");
        CellQueue cell = lease.cell;
        lease.cell = null;
        lease.release();
        if (cell != null) finishSlice(cell); // its tasks were never polled; re-arm them
      }
    }
    for (CellQueue q : cells.values()) {
      if (!q.scheduled.get() && !q.tasks.isEmpty()) requestSlice(q);
    }
    pumpQueue();
  }

  private static void observeRegionForMetrics(RTPWorld<?> world, int cx, int cz) {
    try {
      RTP.metrics.getBinding().observeRegion(world, cx, cz);
    } catch (Throwable t) {
      RTP.log(Level.FINER, "[RTP] Folia region metrics observe failed: " + t);
    }
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
      // Drop the task silently - shutdown is an expected, non-teleport code path.
      return trackedTask;
    }
    asyncScheduler.runNow(plugin, scheduledTask -> trackedTask.run());
    return trackedTask;
  }

  @Override
  public void runTask(Runnable task) {
    if (Bukkit.isGlobalTickThread()) {
      task.run();
    } else {
      if (!plugin.isEnabled()) return;
      globalScheduler.run(plugin, scheduledTask -> task.run());
    }
  }

  @Override
  public void runTask(io.github.dailystruggle.rtp.api.world.RTPLocation location, Runnable task) {
    runTask(location.world(), location.x() >> 4, location.z() >> 4, task);
  }

  @Override
  public void runTask(RTPWorld<?> world, int cx, int cz, Runnable task) {
    if (world instanceof FoliaRTPWorld foliaRTPWorld && world.world() != null) {
      World bukkitWorld = foliaRTPWorld.world();
      if (Bukkit.isOwnedByCurrentRegion(bukkitWorld, cx, cz)) {
        CellQueue q = cells.get(cellKey(bukkitWorld, cx, cz));
        if ((q == null || q.depth.get() <= 0) && budget.hasBudget()) {
          // Inline: no new region, no permit (see class doc). Exceptions reach the caller as before.
          long span = budget.begin();
          try {
            task.run();
          } finally {
            budget.end(span);
          }
          return;
        }
        // Over budget or work already queued here: wait for the next slice instead of piling on.
      }
      if (!plugin.isEnabled()) return;
      observeRegionForMetrics(world, cx, cz);
      submitRegional(bukkitWorld, cx, cz, task);
    } else if (world == null || world.world() == null) {
      // TODO: THREAD-VIOLATION - Requires async bridge; null-world fallback crosses to @AsyncThread
      runTaskAsynchronously(task);
    } else {
      throw new IllegalArgumentException("World [" + world.name() + "] is not a Folia world");
    }
  }

  @Override
  public Object runTaskTimer(RTPWorld<?> world, int cx, int cz, Runnable task, long delay, long period) {
    // Exempt from the permit pool: long-lived and few (see class doc).
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
  public void runTaskLater(RTPWorld<?> world, int cx, int cz, Runnable task, long delay) {
    if (world instanceof FoliaRTPWorld && world.world() != null) {
      if (!plugin.isEnabled()) return;
      // Delay on the global region, take the permit on fire: a pending delay never pins a region.
      globalScheduler.runDelayed(plugin, scheduledTask -> runTask(world, cx, cz, task), Math.max(1, delay));
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
            TimeUnit.MILLISECONDS
    );
  }

  /** Backlogged (deep queue or late slice) or unhealthy region: background work should go elsewhere. */
  @Override
  public boolean isRegionSaturated(RTPWorld<?> world, int cx, int cz) {
    if (!(world instanceof FoliaRTPWorld foliaRTPWorld) || foliaRTPWorld.world() == null) return false;
    World bukkitWorld = foliaRTPWorld.world();
    CellQueue q = cells.get(cellKey(bukkitWorld, cx, cz));
    if (q != null && (q.yielding || q.depth.get() >= SATURATED_DEPTH)) return true;
    try {
      return health.unhealthy(bukkitWorld, cx, cz);
    } catch (Throwable t) {
      return false;
    }
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
