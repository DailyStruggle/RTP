package io.github.dailystruggle.rtp.common.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link RTPRunnable#schedule()} self-dispatch routing: entity thread when a
 * target player is set, region thread when only a location is set, and async/main when
 * neither is set. Also asserts the require-by-contract guard when the core scheduler has
 * not been installed.
 */
class RTPRunnableScheduleRoutingTest {

  /** Records which scheduler tier the last dispatch landed on. */
  private static final class RecordingScheduler implements RTPScheduler {
    String tier = null;
    long lastDelay = Long.MIN_VALUE;

    @Override
    public TrackedRTPTask runTaskAsynchronously(Runnable task) {
      tier = "async";
      return null;
    }

    @Override
    public void runTask(Runnable task) {
      tier = "main";
    }

    @Override
    public void runTaskLater(Runnable task, long delay) {
      tier = "main-later";
      lastDelay = delay;
    }

    @Override
    public Object runTaskTimer(Runnable task, long delay, long period) {
      tier = "main-timer";
      return null;
    }

    @Override
    public Object runTaskTimerAsynchronously(Runnable task, long delay, long period) {
      tier = "async-timer";
      return null;
    }

    @Override
    public void cancelTask(Object task) {}

    @Override
    public void runTaskForPlayer(RTPPlayer player, RTPRunnable task, long delayTicks) {
      tier = "entity";
      lastDelay = delayTicks;
    }

    @Override
    public void runTask(RTPLocation location, Runnable task) {
      tier = "region";
    }

    @Override
    public void runTask(RTPWorld<?> world, int cx, int cz, Runnable task) {
      tier = "region-chunk";
    }

    @Override
    public Object runTaskTimer(RTPWorld<?> world, int cx, int cz, Runnable task, long delay, long period) {
      tier = "region-timer";
      return null;
    }

    @Override
    public void runTaskLater(RTPWorld<?> world, int cx, int cz, Runnable task, long delay) {
      tier = "region-later";
      lastDelay = delay;
    }
  }

  @AfterEach
  void tearDown() {
    RTPRunnable.scheduler = null;
  }

  @Test
  @DisplayName("schedule() with a target player routes to the entity scheduler")
  void entityRouting() {
    RecordingScheduler s = new RecordingScheduler();
    RTPRunnable.scheduler = s;
    RTPPlayer player = mock(RTPPlayer.class);
    RTPLocation loc = new RTPLocation(mock(RTPWorld.class), 1, 64, 2);

    RTPRunnable task = new RTPRunnable(() -> {});
    task.setTarget(player).setLocation(loc); // player takes precedence over location
    task.schedule(5L);

    assertEquals("entity", s.tier);
    assertEquals(5L, s.lastDelay);
  }

  @Test
  @DisplayName("schedule() with only a location, no delay, routes to the region thread")
  void regionRoutingNoDelay() {
    RecordingScheduler s = new RecordingScheduler();
    RTPRunnable.scheduler = s;
    RTPLocation loc = new RTPLocation(mock(RTPWorld.class), 1, 64, 2);

    RTPRunnable task = new RTPRunnable(() -> {});
    task.setLocation(loc);
    task.schedule();

    assertEquals("region", s.tier);
  }

  @Test
  @DisplayName("schedule() with a location and a delay routes to the region chunk-later thread")
  void regionRoutingDelayed() {
    RecordingScheduler s = new RecordingScheduler();
    RTPRunnable.scheduler = s;
    RTPLocation loc = new RTPLocation(mock(RTPWorld.class), 33, 64, 49);

    RTPRunnable task = new RTPRunnable(() -> {});
    task.setLocation(loc);
    task.schedule(3L);

    assertEquals("region-later", s.tier);
    assertEquals(3L, s.lastDelay);
  }

  @Test
  @DisplayName("schedule() with no spatial context, no delay, routes async")
  void asyncRouting() {
    RecordingScheduler s = new RecordingScheduler();
    RTPRunnable.scheduler = s;

    RTPRunnable task = new RTPRunnable(() -> {});
    task.schedule();

    assertEquals("async", s.tier);
  }

  @Test
  @DisplayName("schedule() with no spatial context but a delay routes to a delayed main-thread task")
  void mainLaterRouting() {
    RecordingScheduler s = new RecordingScheduler();
    RTPRunnable.scheduler = s;

    RTPRunnable task = new RTPRunnable(() -> {});
    task.schedule(7L);

    assertEquals("main-later", s.tier);
    assertEquals(7L, s.lastDelay);
  }

  @Test
  @DisplayName("schedule() before the core scheduler is installed throws IllegalStateException")
  void requireByContract() {
    RTPRunnable.scheduler = null;
    RTPRunnable task = new RTPRunnable(() -> {});
    assertThrows(IllegalStateException.class, task::schedule);
  }
}
