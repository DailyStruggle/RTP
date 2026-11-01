package io.github.dailystruggle.rtp.folia.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.folia.mock.MockAsyncScheduler;
import io.github.dailystruggle.rtp.folia.mock.MockGlobalRegionScheduler;
import io.github.dailystruggle.rtp.folia.mock.MockScheduledTask;
import io.github.dailystruggle.rtp.folia.world.FoliaRTPWorld;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** ADR-087 regional permit pool: atomic cap, FIFO deferral, every one-shot overload limited. */
class FoliaSchedulerLimiterTest {

  /** Thread-safe region scheduler that queues one-shot tasks and records their handles. */
  static final class RecordingRegionScheduler implements RegionScheduler {
    final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    final List<MockScheduledTask> handles = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger fixedRateCalls = new AtomicInteger();
    volatile Runnable onSubmit = () -> {};

    @Override
    public void execute(Plugin plugin, World world, int chunkX, int chunkZ, Runnable run) {
      onSubmit.run();
      queue.add(run);
    }

    @Override
    public ScheduledTask run(Plugin plugin, World world, int chunkX, int chunkZ, Consumer<ScheduledTask> task) {
      onSubmit.run();
      MockScheduledTask handle = new MockScheduledTask(plugin, false);
      handles.add(handle);
      queue.add(() -> task.accept(handle));
      return handle;
    }

    @Override
    public ScheduledTask runDelayed(Plugin plugin, World world, int chunkX, int chunkZ,
                                    Consumer<ScheduledTask> task, long delayTicks) {
      throw new AssertionError("delayed regional work must wait on the global scheduler");
    }

    @Override
    public ScheduledTask runAtFixedRate(Plugin plugin, World world, int chunkX, int chunkZ,
                                        Consumer<ScheduledTask> task, long initialDelayTicks, long periodTicks) {
      fixedRateCalls.incrementAndGet();
      return new MockScheduledTask(plugin, true);
    }

    /** Runs exactly one queued task; returns false when empty. */
    boolean runOne() {
      Runnable r = queue.poll();
      if (r == null) return false;
      r.run();
      return true;
    }

    void runAll() {
      while (runOne()) {
        // drain, including tasks dispatched by releases during the drain
      }
    }
  }

  private Server previousServer;
  private Server server;
  private Plugin plugin;
  private RecordingRegionScheduler regionScheduler;
  private MockAsyncScheduler asyncScheduler;
  private MockGlobalRegionScheduler globalScheduler;
  private FoliaRTPWorld world;

  @BeforeEach
  void setUp() throws Exception {
    java.lang.reflect.Field serverField = org.bukkit.Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    previousServer = (Server) serverField.get(null);
    server = Mockito.mock(Server.class);
    serverField.set(null, server);

    plugin = Mockito.mock(Plugin.class);
    when(plugin.isEnabled()).thenReturn(true);
    regionScheduler = new RecordingRegionScheduler();
    asyncScheduler = new MockAsyncScheduler();
    globalScheduler = new MockGlobalRegionScheduler();
    world = new FoliaRTPWorld(Mockito.mock(World.class));
  }

  @AfterEach
  void tearDown() throws Exception {
    java.lang.reflect.Field serverField = org.bukkit.Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, previousServer);
  }

  private FoliaSchedulerImpl scheduler(int cap) {
    return new FoliaSchedulerImpl(plugin, regionScheduler, asyncScheduler, globalScheduler, cap, 5_000L);
  }

  @Test
  @DisplayName("ADR-087: concurrent submits never exceed the regional permit cap and every task runs once")
  void concurrentSubmitsNeverExceedCap() throws Exception {
    int cap = 3;
    int submitters = 8;
    int perSubmitter = 250;
    int total = submitters * perSubmitter;
    FoliaSchedulerImpl scheduler = scheduler(cap);
    AtomicInteger maxInFlightAtSubmit = new AtomicInteger();
    AtomicInteger running = new AtomicInteger();
    AtomicInteger maxRunning = new AtomicInteger();
    AtomicInteger executed = new AtomicInteger();
    regionScheduler.onSubmit = () -> maxInFlightAtSubmit.accumulateAndGet(scheduler.getInFlightRegionalTasks(), Math::max);

    ExecutorService pool = Executors.newFixedThreadPool(submitters + 4);
    CountDownLatch start = new CountDownLatch(1);
    try {
      for (int s = 0; s < submitters; s++) {
        final int base = s * perSubmitter;
        pool.submit(() -> {
          start.await();
          for (int i = 0; i < perSubmitter; i++) {
            int c = base + i;
            scheduler.runTask(world, c, c, () -> {
              maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
              running.decrementAndGet();
              executed.incrementAndGet();
            });
          }
          return null;
        });
      }
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      for (int w = 0; w < 4; w++) {
        pool.submit(() -> {
          start.await();
          while (executed.get() < total && System.nanoTime() < deadline) {
            if (!regionScheduler.runOne()) Thread.onSpinWait();
          }
          return null;
        });
      }
      start.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "workers did not finish");
    } finally {
      pool.shutdownNow();
    }

    assertEquals(total, executed.get(), "every submitted task must run exactly once (no stranding)");
    assertTrue(maxInFlightAtSubmit.get() <= cap, "in-flight exceeded cap: " + maxInFlightAtSubmit.get());
    assertTrue(maxRunning.get() <= cap, "concurrently running exceeded cap: " + maxRunning.get());
    assertEquals(0, scheduler.getInFlightRegionalTasks());
    assertEquals(0, scheduler.getDeferredQueueSize());
  }

  /** First chunk X of the i-th distinct cell; tasks in one cell coalesce into one slice. */
  private static int cellX(int i) {
    return i << (FoliaSchedulerImpl.CELL_SHIFT + 3);
  }

  @Test
  @DisplayName("ADR-087: saturated dispatches drain in FIFO order, one per permit release")
  void deferredQueueDrainsFifoOnRelease() {
    FoliaSchedulerImpl scheduler = scheduler(1);
    List<String> order = Collections.synchronizedList(new ArrayList<>());
    List<String> ids = List.of("a", "b", "c", "d");
    for (int i = 0; i < ids.size(); i++) {
      String id = ids.get(i);
      scheduler.runTask(world, cellX(i), 0, () -> order.add(id));
    }
    assertEquals(1, scheduler.getInFlightRegionalTasks());
    assertEquals(3, scheduler.getDeferredQueueSize());
    assertEquals(1, regionScheduler.queue.size(), "only the permit holder reaches the region scheduler");

    for (int expected = 1; expected <= 4; expected++) {
      assertTrue(regionScheduler.runOne());
      assertEquals(expected, order.size());
      assertTrue(scheduler.getInFlightRegionalTasks() <= 1);
    }
    assertEquals(List.of("a", "b", "c", "d"), order);
    assertEquals(0, scheduler.getInFlightRegionalTasks());
    assertEquals(0, scheduler.getDeferredQueueSize());
  }

  @Test
  @DisplayName("ADR-087: location, chunk, delayed and interactive one-shot overloads all wait for a permit")
  void everyOneShotOverloadIsLimited() {
    FoliaSchedulerImpl scheduler = scheduler(1);
    scheduler.runTask(world, 0, 0, () -> {});
    assertEquals(1, regionScheduler.queue.size());

    scheduler.runTask(new RTPLocation(world, 160, 64, 160), () -> {});
    assertEquals(1, scheduler.getDeferredQueueSize(), "runTask(RTPLocation) must be limited");

    scheduler.runTaskLater(world, 20, 20, () -> {}, 5L);
    assertEquals(1, globalScheduler.pendingCount(), "delay waits on the global scheduler");
    assertEquals(1, scheduler.getDeferredQueueSize(), "permit is not taken at submit");
    globalScheduler.executeAll();
    assertEquals(2, scheduler.getDeferredQueueSize(), "delayed task takes the limited path when it fires");

    scheduler.runInteractiveTask(world, 30, 30, () -> {}, () -> {}, 5_000L);
    assertEquals(1, scheduler.getInteractiveQueueSize(), "interactive dispatch must be limited");

    assertEquals(1, regionScheduler.queue.size(), "no overload bypassed the cap");
    regionScheduler.runAll();
    assertEquals(0, scheduler.getInFlightRegionalTasks());
    assertEquals(0, scheduler.getDeferredQueueSize());
    assertEquals(0, scheduler.getInteractiveQueueSize());
  }

  @Test
  @DisplayName("ADR-087: inline run on the owning region bypasses a saturated pool without blocking")
  void inlineRunBypassesSaturatedPool() {
    FoliaSchedulerImpl scheduler = scheduler(1);
    scheduler.runTask(world, 0, 0, () -> {});
    when(server.isOwnedByCurrentRegion(any(World.class), anyInt(), anyInt())).thenReturn(true);
    AtomicInteger ran = new AtomicInteger();
    scheduler.runTask(world, cellX(1), cellX(1), ran::incrementAndGet);
    assertEquals(1, ran.get(), "owning-region caller runs inline");
    assertEquals(1, scheduler.getInFlightRegionalTasks(), "inline run takes no permit");
    assertEquals(0, scheduler.getDeferredQueueSize());
  }

  @Test
  @DisplayName("ADR-087: repeating regional timers do not consume permits")
  void repeatingTimersAreExempt() {
    FoliaSchedulerImpl scheduler = scheduler(1);
    scheduler.runTaskTimer(world, 0, 0, () -> {}, 1L, 1L);
    scheduler.runTaskTimer(world, 1, 1, () -> {}, 1L, 1L);
    assertEquals(2, regionScheduler.fixedRateCalls.get());
    assertEquals(0, scheduler.getInFlightRegionalTasks());
  }

  @Test
  @DisplayName("S-004: a throwing regional task releases its permit and the queue keeps draining")
  void throwingTaskReleasesPermit() {
    FoliaSchedulerImpl scheduler = scheduler(1);
    AtomicInteger ran = new AtomicInteger();
    scheduler.runTask(world, 0, 0, () -> {
      throw new IllegalStateException("boom");
    });
    scheduler.runTask(world, cellX(1), cellX(1), ran::incrementAndGet);
    assertEquals(1, scheduler.getDeferredQueueSize());

    regionScheduler.runOne();
    assertEquals(1, scheduler.getInFlightRegionalTasks(), "released permit went to the deferred task");
    assertEquals(0, scheduler.getDeferredQueueSize());
    regionScheduler.runOne();
    assertEquals(1, ran.get());
    assertEquals(0, scheduler.getInFlightRegionalTasks());
  }

  @Test
  @DisplayName("S-004: a submit rejected by the region scheduler releases its permit")
  void rejectedSubmitReleasesPermit() {
    RegionScheduler rejecting = Mockito.mock(RegionScheduler.class);
    when(rejecting.run(any(), any(), anyInt(), anyInt(), any())).thenThrow(new IllegalStateException("rejected"));
    FoliaSchedulerImpl scheduler =
        new FoliaSchedulerImpl(plugin, rejecting, asyncScheduler, globalScheduler, 1, 5_000L);
    scheduler.runTask(world, 0, 0, () -> {});
    scheduler.runTask(world, 1, 1, () -> {});
    assertEquals(0, scheduler.getInFlightRegionalTasks());
    assertEquals(0, scheduler.getDeferredQueueSize());
  }

  @Test
  @DisplayName("ADR-087: safety pump reclaims the permit of a slice cancelled before it ran and re-arms its tasks")
  void safetyPumpReclaimsCancelledLease() {
    FoliaSchedulerImpl scheduler = scheduler(1);
    AtomicInteger first = new AtomicInteger();
    AtomicInteger ran = new AtomicInteger();
    scheduler.runTask(world, 0, 0, first::incrementAndGet);
    scheduler.runTask(world, cellX(1), cellX(1), ran::incrementAndGet);
    assertEquals(1, scheduler.getDeferredQueueSize());

    regionScheduler.queue.poll(); // Folia dropped the first slice
    regionScheduler.handles.get(0).cancel();
    scheduler.safetyPump();

    assertEquals(1, scheduler.getInFlightRegionalTasks(), "the freed permit went to the waiting slice");
    assertEquals(1, scheduler.getDeferredQueueSize(), "the cancelled slice's task is re-armed, not lost");
    regionScheduler.runAll();
    assertEquals(1, ran.get());
    assertEquals(1, first.get(), "a task never polled by a cancelled slice still runs once");
    assertEquals(0, scheduler.getInFlightRegionalTasks());
    assertEquals(0, scheduler.getQueuedRegionalTasks());
  }

  @Test
  @DisplayName("ADR-087: background entries starved past the aging threshold beat interactive ones")
  void agedBackgroundEntryIsNotStarved() throws Exception {
    FoliaSchedulerImpl scheduler = scheduler(1);
    List<String> order = Collections.synchronizedList(new ArrayList<>());
    scheduler.runTask(world, 0, 0, () -> order.add("holder"));
    scheduler.runTask(world, cellX(1), cellX(1), () -> order.add("background"));
    Thread.sleep(TimeUnit.NANOSECONDS.toMillis(FoliaSchedulerImpl.DEFERRED_STARVATION_NANOS) + 50);
    scheduler.runInteractiveTask(world, 2, 2, () -> order.add("interactive"), () -> order.add("timeout"), 10_000L);

    regionScheduler.runAll();
    assertEquals(List.of("holder", "background", "interactive"), order);
  }
}
