package io.github.dailystruggle.rtp.folia.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** ADR-004 count-bound pipe with {@code TimeBoundTaskPipe} delay / cancel / isolation parity. */
class CountBoundTaskPipeTest {

  private RTPServerAccessor previousAccessor;
  private final List<Runnable> regionalDispatches = new ArrayList<>();

  @BeforeEach
  void setUp() {
    previousAccessor = RTP.serverAccessor;
    RTPScheduler scheduler = Mockito.mock(RTPScheduler.class);
    Mockito.doAnswer(inv -> regionalDispatches.add(inv.getArgument(1)))
        .when(scheduler).runTask(any(RTPLocation.class), any(Runnable.class));
    RTPServerAccessor accessor = Mockito.mock(RTPServerAccessor.class);
    when(accessor.getScheduler()).thenReturn(scheduler);
    RTP.serverAccessor = accessor;
  }

  @AfterEach
  void tearDown() {
    RTP.serverAccessor = previousAccessor;
  }

  @Test
  @DisplayName("ADR-004: a delay-N task waits N executions before running")
  void delayedTaskWaitsItsDelay() {
    CountBoundTaskPipe pipe = new CountBoundTaskPipe(null, 10);
    AtomicInteger ran = new AtomicInteger();
    pipe.add(new RTPRunnable(ran::incrementAndGet, 2));

    assertFalse(pipe.execute());
    assertEquals(0, ran.get());
    assertFalse(pipe.execute());
    assertEquals(0, ran.get());
    assertTrue(pipe.execute());
    assertEquals(1, ran.get());
  }

  @Test
  @DisplayName("ADR-004: cancelled tasks are discarded without running or using budget")
  void cancelledTasksAreDiscarded() {
    CountBoundTaskPipe pipe = new CountBoundTaskPipe(null, 1);
    AtomicInteger ran = new AtomicInteger();
    RTPRunnable cancelled = new RTPRunnable(() -> ran.addAndGet(100));
    cancelled.setCancelled(true);
    pipe.add(cancelled);
    pipe.add(new RTPRunnable(ran::incrementAndGet));

    assertTrue(pipe.execute());
    assertEquals(1, ran.get());
    assertEquals(0, pipe.size());
  }

  @Test
  @DisplayName("S-004: a throwing task is logged and does not abort the rest of the drain")
  void throwingTaskIsIsolated() {
    CountBoundTaskPipe pipe = new CountBoundTaskPipe(null, 10);
    AtomicInteger ran = new AtomicInteger();
    pipe.add(new RTPRunnable(() -> {
      throw new IllegalStateException("boom");
    }));
    pipe.add(() -> {
      throw new IllegalArgumentException("plain runnable boom");
    });
    pipe.add(new RTPRunnable(ran::incrementAndGet));

    assertTrue(pipe.execute());
    assertEquals(1, ran.get());
  }

  @Test
  @DisplayName("ADR-004: region-bound tasks count toward the per-execute budget")
  void locationBoundTasksConsumeBudget() {
    CountBoundTaskPipe pipe = new CountBoundTaskPipe(null, 2);
    RTPWorld<?> world = Mockito.mock(RTPWorld.class);
    AtomicInteger ran = new AtomicInteger();
    for (int i = 0; i < 3; i++) {
      pipe.add(new RTPRunnable(ran::incrementAndGet).setLocation(new RTPLocation(world, i * 16, 64, 0)));
    }

    assertFalse(pipe.execute());
    assertEquals(2, regionalDispatches.size(), "fan-out per pulse is bounded by maxTasksPerTick");
    assertEquals(1, pipe.size());

    regionalDispatches.forEach(Runnable::run);
    assertEquals(2, ran.get());
  }

  @Test
  @DisplayName("ADR-004: a region-bound task cancelled while queued does not run on the region")
  void regionBoundTaskCancelledWhileQueuedDoesNotRun() {
    CountBoundTaskPipe pipe = new CountBoundTaskPipe(null, 2);
    RTPWorld<?> world = Mockito.mock(RTPWorld.class);
    AtomicInteger ran = new AtomicInteger();
    RTPRunnable task = new RTPRunnable(ran::incrementAndGet);
    task.setLocation(new RTPLocation(world, 0, 64, 0));
    pipe.add(task);

    pipe.execute();
    task.setCancelled(true);
    regionalDispatches.forEach(Runnable::run);
    assertEquals(0, ran.get());
  }

  @Test
  @DisplayName("ADR-004: a 1 ns budget still runs one task per execute, so a spent pulse never stalls the pipe")
  void minimalBudgetStillRunsOneTask() {
    CountBoundTaskPipe pipe = new CountBoundTaskPipe(null, 10);
    AtomicInteger ran = new AtomicInteger();
    for (int i = 0; i < 3; i++) pipe.add(new RTPRunnable(ran::incrementAndGet));

    pipe.execute(1L);
    // A coarse clock may read 0 ns elapsed and admit a second task; the floor is what matters.
    assertTrue(ran.get() >= 1, "progress is guaranteed at the smallest positive budget");
    for (int i = 0; i < 3; i++) pipe.execute(1L);
    assertEquals(3, ran.get());
  }

  @Test
  @DisplayName("ADR-004: a spent budget (zero or less) runs nothing")
  void exhaustedBudgetRunsNothing() {
    CountBoundTaskPipe pipe = new CountBoundTaskPipe(null, 10);
    AtomicInteger ran = new AtomicInteger();
    pipe.add(new RTPRunnable(ran::incrementAndGet));

    assertFalse(pipe.execute(0L));
    assertFalse(pipe.execute(-5L));
    assertEquals(0, ran.get());
    assertEquals(1, pipe.size());
  }

  @Test
  @DisplayName("ADR-004: inline work stops once the time budget is spent on the draining thread")
  void timeBudgetBoundsInlineWork() {
    CountBoundTaskPipe pipe = new CountBoundTaskPipe(null, 10);
    AtomicInteger ran = new AtomicInteger();
    for (int i = 0; i < 5; i++) {
      pipe.add(new RTPRunnable(() -> {
        ran.incrementAndGet();
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2);
        while (System.nanoTime() < until) Thread.onSpinWait();
      }));
    }

    pipe.execute(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(3));
    assertTrue(ran.get() >= 1 && ran.get() <= 2, "3 ms budget fits at most two 2 ms tasks, ran " + ran.get());
  }
}
