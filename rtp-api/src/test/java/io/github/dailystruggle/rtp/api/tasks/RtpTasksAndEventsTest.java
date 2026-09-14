package io.github.dailystruggle.rtp.api.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.event.PrefabChange;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RtpTasksAndEventsTest {

  @Test
  @DisplayName("RTPRunnable lifecycle, tracking hooks, and execution")
  void testRtpRunnable() {
    AtomicBoolean tracked = new AtomicBoolean(false);
    AtomicBoolean updated = new AtomicBoolean(false);
    AtomicBoolean untracked = new AtomicBoolean(false);

    UUID testUuid = UUID.randomUUID();
    RTPRunnable.trackHook = (obj, life) -> {
      tracked.set(true);
      return testUuid;
    };
    RTPRunnable.updateHook = id -> updated.set(true);
    RTPRunnable.untrackHook = obj -> untracked.set(true);

    AtomicBoolean ran = new AtomicBoolean(false);
    RTPRunnable task = new RTPRunnable(() -> ran.set(true), 20L);

    assertTrue(tracked.get());
    assertEquals(20L, task.getDelay());
    task.setDelay(5L);
    assertEquals(5L, task.getDelay());
    assertFalse(task.isCancelled());
    assertFalse(task.isRunning());
    assertNull(task.getTarget());
    assertNull(task.getLocation());

    // Run with tracking
    task.runWithTracking();
    assertTrue(ran.get());
    assertTrue(updated.get());

    // Cancel and untrack
    task.setCancelled(true);
    assertTrue(task.isCancelled());

    // Alternate constructors
    RTPRunnable defTask = new RTPRunnable();
    assertNotNull(defTask);

    RTPRunnable intDelayTask = new RTPRunnable(10);
    assertEquals(10L, intDelayTask.getDelay());

    RTPRunnable delegatingTask = new RTPRunnable(() -> {});
    assertNotNull(delegatingTask);

    // Reset hooks
    RTPRunnable.trackHook = (obj, life) -> null;
    RTPRunnable.updateHook = id -> {};
    RTPRunnable.untrackHook = obj -> {};

    // Test sparkFrameName tagged routing branches
    for (String tag : List.of(
        "rtp_pipeline_attempt",
        "rtp_cache_generator",
        "rtp_scan_crawler",
        "rtp_async_task_drain",
        "rtp_scan_drain",
        "rtp_force_queue",
        "custom_unknown_tag"
    )) {
      AtomicBoolean taggedRan = new AtomicBoolean(false);
      RTPRunnable taggedTask = new RTPRunnable(() -> taggedRan.set(true)) {
        @Override
        protected String sparkFrameName() {
          return tag;
        }
      };
      taggedTask.runWithTracking();
      assertTrue(taggedRan.get());
    }
  }

  @Test
  @DisplayName("TrackedRTPTask state transitions and aging")
  void testTrackedRTPTask() {
    AtomicBoolean executed = new AtomicBoolean(false);
    RTPRunnable runnable = new RTPRunnable(() -> executed.set(true));
    UUID trackId = UUID.randomUUID();

    TrackedRTPTask tracked = new TrackedRTPTask(runnable, trackId);
    assertEquals(runnable, tracked.getTask());
    assertEquals(trackId.toString(), tracked.getTrackingId());
    assertEquals(TrackedRTPTask.TaskState.PENDING, tracked.getState());
    assertEquals(0, tracked.getDuration());
    assertEquals(-1, tracked.getStartTime());
    assertEquals(-1, tracked.getEndTime());
    assertTrue(tracked.getQueuedTime() > 0L);

    // Delegation getters/setters
    assertEquals(runnable.getDelay(), tracked.getDelay());
    tracked.setDelay(12L);
    assertEquals(12L, runnable.getDelay());
    assertFalse(tracked.isCancelled());
    tracked.setCancelled(true);
    assertTrue(runnable.isCancelled());
    assertFalse(tracked.isRunning());

    tracked.run();
    assertTrue(executed.get());
    assertEquals(TrackedRTPTask.TaskState.COMPLETED, tracked.getState());
    assertTrue(tracked.getStartTime() > 0L);
    assertTrue(tracked.getEndTime() >= tracked.getStartTime());
  }

  @Test
  @DisplayName("PrefabChange model")
  void testPrefabEvents() {
    PrefabChange change = new PrefabChange("queue.maxSize", 100, 200);
    assertEquals("queue.maxSize", change.keyPath());
    assertEquals(100, change.oldValue());
    assertEquals(200, change.newValue());

    UUID caller = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.event.PrefabAppliedEvent event =
        new io.github.dailystruggle.rtp.api.event.PrefabAppliedEvent(
            "prefab1",
            caller,
            List.of("config.yml"),
            java.util.Map.of("config.yml", List.of(change)),
            true
        );
    assertEquals("prefab1", event.prefabId());
    assertEquals(caller, event.callerId());
    assertEquals(List.of("config.yml"), event.writtenFiles());
    assertEquals(1, event.changes().size());
    assertTrue(event.reloadSucceeded());

    // Defensive copy / unmodifiable
    assertThrows(UnsupportedOperationException.class, () -> event.writtenFiles().add("other"));
    assertThrows(UnsupportedOperationException.class, () -> event.changes().put("x", List.of()));

    // Null checks
    assertThrows(NullPointerException.class, () -> new io.github.dailystruggle.rtp.api.event.PrefabAppliedEvent(null, caller, List.of(), java.util.Map.of(), true));
    assertThrows(NullPointerException.class, () -> new io.github.dailystruggle.rtp.api.event.PrefabAppliedEvent("p", null, List.of(), java.util.Map.of(), true));
    assertThrows(NullPointerException.class, () -> new io.github.dailystruggle.rtp.api.event.PrefabAppliedEvent("p", caller, null, java.util.Map.of(), true));
    assertThrows(NullPointerException.class, () -> new io.github.dailystruggle.rtp.api.event.PrefabAppliedEvent("p", caller, List.of(), null, true));

    // PrefabEventDispatcher
    io.github.dailystruggle.rtp.api.event.PrefabEventDispatcher dispatcher =
        new io.github.dailystruggle.rtp.api.event.PrefabEventDispatcher();
    assertFalse(dispatcher.hasSubscribers());

    assertThrows(IllegalArgumentException.class, () -> dispatcher.subscribe(null));

    java.util.concurrent.atomic.AtomicInteger receivedCount = new java.util.concurrent.atomic.AtomicInteger(0);
    AutoCloseable sub1 = dispatcher.subscribe(e -> receivedCount.incrementAndGet());
    AutoCloseable sub2 = dispatcher.subscribe(e -> {
      throw new RuntimeException("faulty subscriber simulation");
    });
    assertTrue(dispatcher.hasSubscribers());

    // Fire null (ignored)
    dispatcher.fire(null);
    assertEquals(0, receivedCount.get());

    // Fire event (should not fail even if sub2 throws)
    dispatcher.fire(event);
    assertEquals(1, receivedCount.get());

    // Unsubscribe
    try {
      sub1.close();
      sub2.close();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    assertFalse(dispatcher.hasSubscribers());

    dispatcher.fire(event);
    assertEquals(1, receivedCount.get());
  }
}
