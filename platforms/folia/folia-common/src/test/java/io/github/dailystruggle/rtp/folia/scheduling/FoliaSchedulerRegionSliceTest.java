package io.github.dailystruggle.rtp.folia.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.rtp.folia.mock.MockAsyncScheduler;
import io.github.dailystruggle.rtp.folia.mock.MockGlobalRegionScheduler;
import io.github.dailystruggle.rtp.folia.world.FoliaRTPWorld;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * ADR-112: regional work is divided per region. Same-cell tasks coalesce into one budgeted slice,
 * leftovers queue behind other regions, and a slow region (ADR-087 circuit breaker) gets one task
 * per slice.
 */
class FoliaSchedulerRegionSliceTest {
  private static final long BUDGET = TimeUnit.MILLISECONDS.toNanos(5);

  private Server previousServer;
  private Server server;
  private Plugin plugin;
  private FoliaSchedulerLimiterTest.RecordingRegionScheduler regionScheduler;
  private FoliaRTPWorld world;
  private final AtomicLong clock = new AtomicLong(5_000_000_000L);
  private final AtomicBoolean unhealthy = new AtomicBoolean(false);

  @BeforeEach
  void setUp() throws Exception {
    java.lang.reflect.Field serverField = org.bukkit.Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    previousServer = (Server) serverField.get(null);
    server = Mockito.mock(Server.class);
    serverField.set(null, server);
    plugin = Mockito.mock(Plugin.class);
    when(plugin.isEnabled()).thenReturn(true);
    regionScheduler = new FoliaSchedulerLimiterTest.RecordingRegionScheduler();
    world = new FoliaRTPWorld(Mockito.mock(World.class));
  }

  @AfterEach
  void tearDown() throws Exception {
    java.lang.reflect.Field serverField = org.bukkit.Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, previousServer);
  }

  private FoliaSchedulerImpl scheduler(int cap) {
    return new FoliaSchedulerImpl(plugin, regionScheduler, new MockAsyncScheduler(), new MockGlobalRegionScheduler(),
        cap, 5_000L, new RegionThreadBudget(clock::get, () -> BUDGET), (w, cx, cz) -> unhealthy.get());
  }

  /** Task that costs {@code ms} of (simulated) region-thread time. */
  private Runnable costing(List<String> order, String id, long ms) {
    return () -> {
      order.add(id);
      clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms));
    };
  }

  private static int otherCell(int i) {
    return i << (FoliaSchedulerImpl.CELL_SHIFT + 3);
  }

  @Test
  @DisplayName("ADR-112: tasks for one region coalesce into one slice and run in FIFO order")
  void sameCellCoalescesFifo() {
    FoliaSchedulerImpl scheduler = scheduler(4);
    List<String> order = Collections.synchronizedList(new ArrayList<>());
    int[][] chunks = {{0, 0}, {1, 0}, {2, 1}, {3, 3}, {7, 7}};
    for (int i = 0; i < chunks.length; i++) {
      scheduler.runTask(world, chunks[i][0], chunks[i][1], costing(order, "t" + i, 0));
    }
    assertEquals(1, regionScheduler.queue.size(), "one slice per region, not one dispatch per task");
    assertEquals(1, scheduler.getInFlightRegionalTasks(), "one region holds one permit");
    assertEquals(5, scheduler.getQueuedRegionalTasks());

    assertTrue(regionScheduler.runOne());
    assertEquals(List.of("t0", "t1", "t2", "t3", "t4"), order);
    assertEquals(0, scheduler.getInFlightRegionalTasks());
    assertEquals(0, scheduler.getActiveCellCount(), "an empty cell is retired");
  }

  @Test
  @DisplayName("ADR-112: a spent region-thread budget stops the slice; the rest waits behind other regions")
  void spentBudgetYieldsToOtherRegions() {
    FoliaSchedulerImpl scheduler = scheduler(1);
    List<String> order = Collections.synchronizedList(new ArrayList<>());
    scheduler.runTask(world, 0, 0, costing(order, "A1", 3));
    scheduler.runTask(world, 1, 1, costing(order, "A2", 3));
    scheduler.runTask(world, 2, 2, costing(order, "A3", 3));
    scheduler.runTask(world, otherCell(1), 0, costing(order, "B1", 1));

    assertTrue(regionScheduler.runOne()); // hot region: 6 ms spent of 5, A3 left over
    assertEquals(List.of("A1", "A2"), order);
    assertTrue(regionScheduler.runOne()); // the other region goes before the leftover
    assertEquals(List.of("A1", "A2", "B1"), order);

    clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100)); // next ticks refill the budget
    assertTrue(regionScheduler.runOne());
    assertEquals(List.of("A1", "A2", "B1", "A3"), order);
    assertEquals(0, scheduler.getQueuedRegionalTasks());
    assertEquals(0, scheduler.getInFlightRegionalTasks());
  }

  @Test
  @DisplayName("REQ-FOLIA-ARCH-005: a slice also stops at its task-count cap when every task reads as free")
  void sliceIsCountCapped() {
    FoliaSchedulerImpl scheduler = scheduler(2);
    AtomicInteger ran = new AtomicInteger();
    int total = FoliaSchedulerImpl.MAX_TASKS_PER_SLICE + 8;
    for (int i = 0; i < total; i++) scheduler.runTask(world, 0, 0, ran::incrementAndGet);

    assertTrue(regionScheduler.runOne());
    assertEquals(FoliaSchedulerImpl.MAX_TASKS_PER_SLICE, ran.get());
    regionScheduler.runAll();
    assertEquals(total, ran.get());
  }

  @Test
  @DisplayName("ADR-087 regional circuit breaker: an unhealthy region runs one task per slice and reports saturated")
  void unhealthyRegionRunsOnePerSlice() {
    FoliaSchedulerImpl scheduler = scheduler(2);
    unhealthy.set(true);
    List<String> order = Collections.synchronizedList(new ArrayList<>());
    for (int i = 0; i < 3; i++) scheduler.runTask(world, i, 0, costing(order, "t" + i, 0));

    assertTrue(regionScheduler.runOne());
    assertEquals(1, order.size(), "a slow region gets one task per slice");
    assertTrue(scheduler.isRegionSaturated(world, 0, 0), "callers are told to aim elsewhere");

    regionScheduler.runAll();
    assertEquals(List.of("t0", "t1", "t2"), order, "nothing is dropped, only paced");
    unhealthy.set(false);
    assertFalse(scheduler.isRegionSaturated(world, otherCell(3), otherCell(3)));
  }

  @Test
  @DisplayName("ADR-087 dispatch canary: a slice that ran late paces its region to one task")
  void lateSliceYields() {
    FoliaSchedulerImpl scheduler = scheduler(2);
    List<String> order = Collections.synchronizedList(new ArrayList<>());
    for (int i = 0; i < 3; i++) scheduler.runTask(world, i, 0, costing(order, "t" + i, 0));
    clock.addAndGet(FoliaSchedulerImpl.DISPATCH_LAG_NANOS + 1);

    assertTrue(regionScheduler.runOne());
    assertEquals(1, order.size());
    assertTrue(scheduler.isRegionSaturated(world, 0, 0));
  }

  @Test
  @DisplayName("ADR-112: a deep regional backlog reports saturated")
  void deepBacklogReportsSaturated() {
    FoliaSchedulerImpl scheduler = scheduler(1);
    for (int i = 0; i < FoliaSchedulerImpl.SATURATED_DEPTH; i++) scheduler.runTask(world, 0, 0, () -> {});
    assertTrue(scheduler.isRegionSaturated(world, 0, 0));
    assertFalse(scheduler.isRegionSaturated(world, otherCell(2), otherCell(2)));
  }

  @Test
  @DisplayName("ADR-112: an owning-region caller runs inline only with budget left and nothing queued")
  void inlineRequiresBudgetAndEmptyQueue() {
    FoliaSchedulerImpl scheduler = scheduler(4);
    when(server.isOwnedByCurrentRegion(any(World.class), anyInt(), anyInt())).thenReturn(true);
    AtomicInteger ran = new AtomicInteger();

    scheduler.runTask(world, 0, 0, () -> {
      ran.incrementAndGet();
      clock.addAndGet(BUDGET * 2); // this inline task spends the thread's budget
    });
    assertEquals(1, ran.get(), "budget available: inline");

    scheduler.runTask(world, 0, 0, ran::incrementAndGet);
    assertEquals(1, ran.get(), "budget spent: queued instead of piling onto this tick");
    assertEquals(1, regionScheduler.queue.size());

    clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(200));
    scheduler.runTask(world, 0, 0, ran::incrementAndGet);
    assertEquals(1, ran.get(), "work already queued for this region keeps FIFO order");

    regionScheduler.runAll();
    assertEquals(3, ran.get());
  }

  @Test
  @DisplayName("ADR-112: interactive work is charged but never deferred by a spent budget")
  void interactiveIgnoresBudget() {
    FoliaSchedulerImpl scheduler = scheduler(2);
    when(server.isOwnedByCurrentRegion(any(World.class), anyInt(), anyInt())).thenReturn(true);
    scheduler.runTask(world, 0, 0, () -> clock.addAndGet(BUDGET * 2));
    when(server.isOwnedByCurrentRegion(any(World.class), anyInt(), anyInt())).thenReturn(false);

    AtomicInteger ran = new AtomicInteger();
    scheduler.runInteractiveTask(world, otherCell(1), 0, ran::incrementAndGet, () -> {}, 5_000L);
    assertTrue(regionScheduler.runOne());
    assertEquals(1, ran.get());
  }
}
