package io.github.dailystruggle.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TaskSchedulerTest {

  @BeforeEach
  void clearAccessors() {
    ServerAccessors.set(null);
    ServerAccessors.setScheduler(null);
  }

  @AfterEach
  void resetAccessors() {
    ServerAccessors.set(null);
    ServerAccessors.setScheduler(null);
  }

  @Test
  @DisplayName("ADR-116: TaskHandle.of invokes cancel callback once and tracks cancelled state")
  void taskHandleOfIsIdempotent() {
    AtomicInteger cancelCount = new AtomicInteger();
    TaskHandle handle = TaskHandle.of(cancelCount::incrementAndGet);

    assertFalse(handle.isCancelled());
    handle.cancel();
    assertTrue(handle.isCancelled());
    assertEquals(1, cancelCount.get());

    handle.cancel();
    assertTrue(handle.isCancelled());
    assertEquals(1, cancelCount.get());
  }

  @Test
  void taskHandleOfRejectsNullAction() {
    assertThrows(NullPointerException.class, () -> TaskHandle.of(null));
  }

  @Test
  @DisplayName("ADR-116: TaskScheduler default methods delegate to sync/async and primary thread")
  void defaultSchedulerMethodsDelegateCorrectly() {
    List<String> executed = new ArrayList<>();
    StubScheduler scheduler = new StubScheduler(executed, true);

    TaskHandle syncDelayed = scheduler.runSyncDelayed(() -> executed.add("syncDelayed"), 20L);
    TaskHandle syncTimer = scheduler.runSyncTimer(() -> executed.add("syncTimer"), 10L, 40L);
    TaskHandle asyncTimer = scheduler.runAsyncTimer(() -> executed.add("asyncTimer"), 5L, 20L);
    TaskHandle atLoc = scheduler.runAtLocation("world", 4, -2, () -> executed.add("atLocation"));

    assertNotNull(syncDelayed);
    assertNotNull(syncTimer);
    assertNotNull(asyncTimer);
    assertNotNull(atLoc);
    assertEquals(
        List.of("sync:syncDelayed", "sync:syncTimer", "async:asyncTimer", "sync:atLocation"),
        executed);
    assertTrue(scheduler.isPrimaryThread());
    assertTrue(scheduler.isOwnedByCurrentRegion("world", 4, -2));

    StubScheduler offThreadScheduler = new StubScheduler(new ArrayList<>(), false);
    assertFalse(offThreadScheduler.isPrimaryThread());
    assertFalse(offThreadScheduler.isOwnedByCurrentRegion("world", 0, 0));
  }

  @Test
  void defaultSchedulerMethodsRejectNullArguments() {
    StubScheduler scheduler = new StubScheduler(new ArrayList<>(), true);

    assertThrows(NullPointerException.class, () -> scheduler.runSyncDelayed(null, 1L));
    assertThrows(NullPointerException.class, () -> scheduler.runSyncTimer(null, 1L, 1L));
    assertThrows(NullPointerException.class, () -> scheduler.runAsyncTimer(null, 1L, 1L));
    assertThrows(NullPointerException.class, () -> scheduler.runAtLocation(null, 0, 0, () -> {}));
    assertThrows(NullPointerException.class, () -> scheduler.runAtLocation("world", 0, 0, null));
    assertThrows(NullPointerException.class, () -> scheduler.isOwnedByCurrentRegion(null, 0, 0));
  }

  @Test
  @DisplayName("ADR-116: ServerAccessor and ServerAccessors expose optional TaskScheduler")
  void serverAccessorAndHolderExposeScheduler() {
    assertTrue(ServerAccessors.scheduler().isEmpty());

    StubScheduler accessorScheduler = new StubScheduler(new ArrayList<>(), true);
    ServerAccessor accessorWithoutScheduler = new StubAccessor(Optional.empty());
    ServerAccessors.set(accessorWithoutScheduler);
    assertTrue(ServerAccessors.scheduler().isEmpty());

    ServerAccessor accessorWithScheduler = new StubAccessor(Optional.of(accessorScheduler));
    ServerAccessors.set(accessorWithScheduler);
    assertSame(accessorScheduler, ServerAccessors.scheduler().orElseThrow());

    StubScheduler explicitOverride = new StubScheduler(new ArrayList<>(), false);
    ServerAccessors.setScheduler(explicitOverride);
    assertSame(explicitOverride, ServerAccessors.scheduler().orElseThrow());

    ServerAccessors.setScheduler(null);
    assertSame(accessorScheduler, ServerAccessors.scheduler().orElseThrow());

    ServerAccessors.set(null);
    assertTrue(ServerAccessors.scheduler().isEmpty());
  }

  private static final class StubScheduler implements TaskScheduler {
    private final List<String> log;
    private final boolean primaryThread;

    private StubScheduler(List<String> log, boolean primaryThread) {
      this.log = log;
      this.primaryThread = primaryThread;
    }

    @Override
    public TaskHandle runAsync(Runnable task) {
      int before = log.size();
      task.run();
      if (log.size() > before) {
        int last = log.size() - 1;
        log.set(last, "async:" + log.get(last));
      }
      return TaskHandle.of(() -> {});
    }

    @Override
    public TaskHandle runSync(Runnable task) {
      int before = log.size();
      task.run();
      if (log.size() > before) {
        int last = log.size() - 1;
        log.set(last, "sync:" + log.get(last));
      }
      return TaskHandle.of(() -> {});
    }

    @Override
    public boolean isPrimaryThread() {
      return primaryThread;
    }
  }

  private static final class StubAccessor implements ServerAccessor {
    private final Optional<TaskScheduler> scheduler;

    private StubAccessor(Optional<TaskScheduler> scheduler) {
      this.scheduler = scheduler;
    }

    @Override
    public String getServerVersion() {
      return "1.21.4";
    }

    @Override
    public String getPlatform() {
      return "paper";
    }

    @Override
    public Integer getServerIntVersion() {
      return 21;
    }

    @Override
    public void log(Level level, String message) {}

    @Override
    public void log(Level level, String message, Throwable throwable) {}

    @Override
    public Optional<TaskScheduler> scheduler() {
      return scheduler;
    }
  }
}
