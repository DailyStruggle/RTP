package io.github.dailystruggle.rtp.common.tasks.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPScheduler;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-072: teleport view-distance clamp and chunk-cost-weighted steady restore.
 *
 * <p>Drives {@link ViewDistanceRestoreTask#clampAndSchedule} through a {@link MockRTPScheduler}
 * and asserts: the clamp is applied immediately, the restore is monotonic (never shrinks) and
 * reaches the captured value, the per-step dwell is cost-weighted (later increments take longer),
 * the feature defers to a larger concurrent view distance, and the ramp tears down on disconnect.
 */
class ReqRtpAdr072ViewDistanceClampTest {

  private MockRTPScheduler scheduler;

  @BeforeEach
  void setUp() {
    scheduler = new MockRTPScheduler();
    RTPRunnable.scheduler = scheduler;
  }

  @AfterEach
  void tearDown() {
    RTPRunnable.scheduler = null;
  }

  private static MockRTPPlayer playerWithViewDistance(int vd) {
    MockRTPPlayer player = new MockRTPPlayer();
    player.setViewDistance(vd);
    return player;
  }

  @Test
  @DisplayName("clamp is applied immediately and the view distance is restored to the captured value")
  void clampThenFullRestore() {
    MockRTPPlayer player = playerWithViewDistance(12);

    ViewDistanceRestoreTask.clampAndSchedule(player, 2, 200L);

    // Clamp applied synchronously, before any tick.
    assertEquals(2, player.getViewDistance(), "view distance should be clamped immediately");

    int previous = player.getViewDistance();
    for (int t = 0; t < 260; t++) {
      scheduler.tick(1);
      int now = player.getViewDistance();
      assertTrue(now >= previous, "restore must be monotonic (never shrink)");
      previous = now;
    }
    assertEquals(12, player.getViewDistance(), "view distance should be fully restored");
  }

  @Test
  @DisplayName("per-step dwell is cost-weighted: later increments take longer than earlier ones")
  void costWeightedCadence() {
    MockRTPPlayer player = playerWithViewDistance(12);
    ViewDistanceRestoreTask.clampAndSchedule(player, 2, 200L);

    // Record the first tick at which each view-distance value is observed.
    int[] reachedAt = new int[13];
    java.util.Arrays.fill(reachedAt, -1);
    reachedAt[player.getViewDistance()] = 0;
    for (int t = 1; t <= 260; t++) {
      scheduler.tick(1);
      int vd = player.getViewDistance();
      if (vd >= 0 && vd < reachedAt.length && reachedAt[vd] == -1) {
        reachedAt[vd] = t;
      }
    }

    int firstStep = reachedAt[3] - reachedAt[2];   // 2 -> 3 (cheap)
    int lastStep = reachedAt[12] - reachedAt[11];  // 11 -> 12 (expensive)
    assertTrue(firstStep > 0 && lastStep > 0, "both steps must be observed");
    assertTrue(lastStep > firstStep,
        "the final (most expensive) increment must be granted more dwell than the first; "
            + "firstStep=" + firstStep + " lastStep=" + lastStep);
  }

  @Test
  @DisplayName("skips entirely when the clamp is not smaller than the player's view distance")
  void skipWhenNothingToGain() {
    MockRTPPlayer player = playerWithViewDistance(8);

    ViewDistanceRestoreTask.clampAndSchedule(player, 8, 200L);
    assertEquals(8, player.getViewDistance(), "no clamp when preload radius >= view distance");

    ViewDistanceRestoreTask.clampAndSchedule(player, 16, 200L);
    assertEquals(8, player.getViewDistance(), "no clamp when preload radius exceeds view distance");

    scheduler.tick(300);
    assertEquals(8, player.getViewDistance(), "view distance unchanged");
  }

  @Test
  @DisplayName("disabled (interval 0) applies no clamp")
  void disabledByZeroInterval() {
    MockRTPPlayer player = playerWithViewDistance(12);
    ViewDistanceRestoreTask.clampAndSchedule(player, 2, 0L);
    assertEquals(12, player.getViewDistance(), "interval 0 disables the clamp");
  }

  @Test
  @DisplayName("platform without a per-player view-distance API no-ops")
  void unsupportedPlatformNoOps() {
    MockRTPPlayer player = playerWithViewDistance(-1);
    ViewDistanceRestoreTask.clampAndSchedule(player, 2, 200L);
    assertEquals(-1, player.getViewDistance(), "unsupported platform must not be clamped");
  }

  @Test
  @DisplayName("restore defers to a larger view distance set by another tool and finishes early")
  void deferToLargerCurrentViewDistance() {
    MockRTPPlayer player = playerWithViewDistance(12);
    ViewDistanceRestoreTask.clampAndSchedule(player, 2, 200L);
    assertEquals(2, player.getViewDistance());

    // Another tool raises the view distance above the restore target mid-ramp.
    scheduler.tick(10);
    int beforeOverride = player.getViewDistance();
    assertTrue(beforeOverride > 2 && beforeOverride < 12, "ramp should be mid-flight");
    player.setViewDistance(20);

    // The ramp must never shrink it back down and must complete.
    for (int t = 0; t < 260; t++) {
      scheduler.tick(1);
      assertTrue(player.getViewDistance() >= 20, "ramp must not shrink a larger concurrent VD");
    }
    assertEquals(20, player.getViewDistance(), "view distance left at the larger external value");
  }

  @Test
  @DisplayName("send view distance is pinned to the captured value during the clamp and released when restored")
  void sendViewDistancePinnedThenReleased() {
    MockRTPPlayer player = playerWithViewDistance(12);

    ViewDistanceRestoreTask.clampAndSchedule(player, 2, 200L);

    // The client send distance is pinned to the captured value before the tracking clamp, so the
    // client never sees a view-distance change (no flash) even though tracking drops to 2.
    assertEquals(2, player.getViewDistance(), "tracking view distance should be clamped");
    assertEquals(12, player.getSendViewDistance(), "send view distance should be pinned to captured");

    // The pin holds for the whole ramp...
    for (int t = 0; t < 200; t++) {
      scheduler.tick(1);
      if (player.getViewDistance() < 12) {
        assertEquals(12, player.getSendViewDistance(),
            "send view distance must stay pinned while the tracking ramp is in flight");
      }
    }
    // run a few more ticks to guarantee completion
    scheduler.tick(60);

    assertEquals(12, player.getViewDistance(), "tracking view distance should be fully restored");
    assertEquals(-1, player.getSendViewDistance(),
        "send view distance pin should be released (follow default) once the ramp completes");
  }

  @Test
  @DisplayName("ramp tears down on disconnect and never mutates an offline player")
  void teardownOnDisconnect() {
    MockRTPPlayer player = playerWithViewDistance(12);
    ViewDistanceRestoreTask.clampAndSchedule(player, 2, 200L);

    scheduler.tick(10);
    int atDisconnect = player.getViewDistance();
    player.setOnline(false);

    scheduler.tick(300);
    assertEquals(atDisconnect, player.getViewDistance(),
        "an offline player's view distance must not be mutated further");
  }
}
