package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPScheduler;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ViewDistanceRestoreTask mutation tests")
class ViewDistanceRestoreTaskTest {

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

    @Test
    void clampAndSchedule_null_player_noops() {
        assertDoesNotThrow(() -> ViewDistanceRestoreTask.clampAndSchedule(null, 2, 200L));
    }

    @Test
    void clampAndSchedule_non_positive_interval_noops() {
        MockRTPPlayer player = new MockRTPPlayer();
        player.setViewDistance(10);
        ViewDistanceRestoreTask.clampAndSchedule(player, 2, 0L);
        assertEquals(10, player.getViewDistance());

        ViewDistanceRestoreTask.clampAndSchedule(player, 2, -5L);
        assertEquals(10, player.getViewDistance());
    }

    @Test
    void clampAndSchedule_player_with_negative_view_distance_noops() {
        MockRTPPlayer player = new MockRTPPlayer();
        player.setViewDistance(-1);
        ViewDistanceRestoreTask.clampAndSchedule(player, 2, 100L);
        assertEquals(-1, player.getViewDistance());
    }

    @Test
    void clampAndSchedule_player_getViewDistance_throws_handled_safely() {
        MockRTPPlayer player = new MockRTPPlayer() {
            @Override
            public int getViewDistance() {
                throw new RuntimeException("Simulated platform error");
            }
        };
        assertDoesNotThrow(() -> ViewDistanceRestoreTask.clampAndSchedule(player, 2, 100L));
    }

    @Test
    void clampAndSchedule_floors_at_min_vd() {
        MockRTPPlayer player = new MockRTPPlayer();
        player.setViewDistance(10);
        // Preload radius 0 or 1 should be floored to MIN_VD (2)
        ViewDistanceRestoreTask.clampAndSchedule(player, 0, 100L);
        assertEquals(ViewDistanceRestoreTask.MIN_VD, player.getViewDistance());
        assertEquals(10, player.getSendViewDistance());
    }

    @Test
    void clampAndSchedule_player_setViewDistance_throws_releases_send_pin() {
        MockRTPPlayer player = new MockRTPPlayer() {
            private int vd = 10;
            @Override
            public int getViewDistance() {
                return vd;
            }
            @Override
            public void setViewDistance(int value) {
                if (value == 2) {
                    throw new RuntimeException("Simulated setViewDistance failure");
                }
                this.vd = value;
            }
        };

        ViewDistanceRestoreTask.clampAndSchedule(player, 2, 100L);
        // releaseSendPin sets sendViewDistance to -1
        assertEquals(-1, player.getSendViewDistance());
    }

    @Test
    void clampAndSchedule_player_setSendViewDistance_throws_continues_with_tracking_clamp() {
        MockRTPPlayer player = new MockRTPPlayer() {
            @Override
            public void setSendViewDistance(int value) {
                throw new RuntimeException("Simulated setSendViewDistance failure");
            }
        };
        player.setViewDistance(8);
        ViewDistanceRestoreTask.clampAndSchedule(player, 2, 100L);
        assertEquals(2, player.getViewDistance());
    }

    @Test
    void run_cancels_if_task_cancelled() {
        MockRTPPlayer player = new MockRTPPlayer();
        player.setViewDistance(10);
        ViewDistanceRestoreTask.clampAndSchedule(player, 2, 100L);

        // Cancel scheduled tasks via cancelTask
        for (Object t : scheduler.getScheduledTasks()) {
            scheduler.cancelTask(t);
        }
        scheduler.tick(150);
        // If cancelled before execution, player view distance stays at 2
        assertEquals(2, player.getViewDistance());
    }

    @Test
    void run_finishes_when_player_goes_offline() {
        MockRTPPlayer player = new MockRTPPlayer();
        player.setViewDistance(10);
        ViewDistanceRestoreTask.clampAndSchedule(player, 2, 100L);

        player.setOnline(false);
        scheduler.tick(200);
        // Offline finishes task without raising view distance further
        assertEquals(2, player.getViewDistance());
    }

    @Test
    void run_finishes_when_player_getViewDistance_negative() {
        MockRTPPlayer player = new MockRTPPlayer();
        player.setViewDistance(10);
        ViewDistanceRestoreTask.clampAndSchedule(player, 2, 100L);

        // Player now returns -1 (e.g. world change or platform quirk)
        player.setViewDistance(-1);
        scheduler.tick(50);
        assertEquals(-1, player.getSendViewDistance());
    }

    @Test
    void run_finishes_when_player_getViewDistance_throws_during_ramp() {
        AtomicInteger calls = new AtomicInteger(0);
        MockRTPPlayer player = new MockRTPPlayer() {
            @Override
            public int getViewDistance() {
                if (calls.incrementAndGet() > 1) {
                    throw new RuntimeException("Simulated error during run");
                }
                return 10;
            }
        };

        ViewDistanceRestoreTask.clampAndSchedule(player, 2, 100L);
        scheduler.tick(150);
        assertEquals(-1, player.getSendViewDistance());
    }

    @Test
    void run_finishes_when_player_setViewDistance_throws_during_ramp() {
        MockRTPPlayer player = new MockRTPPlayer() {
            private int vd = 10;
            @Override
            public int getViewDistance() {
                return vd;
            }
            @Override
            public void setViewDistance(int val) {
                if (val > 2) {
                    throw new RuntimeException("Simulated error in setViewDistance during restore");
                }
                this.vd = val;
            }
        };

        ViewDistanceRestoreTask.clampAndSchedule(player, 2, 100L);
        scheduler.tick(150);
        // Fails on step > 2 and ends ramp
        assertEquals(2, player.getViewDistance());
    }

    @Test
    void run_handles_concurrent_view_distance_elevation() {
        MockRTPPlayer player = new MockRTPPlayer();
        player.setViewDistance(10);
        ViewDistanceRestoreTask.clampAndSchedule(player, 2, 100L);

        // Concurrently elevated beyond target
        player.setViewDistance(12);
        scheduler.tick(10);
        assertEquals(12, player.getViewDistance());
        assertEquals(-1, player.getSendViewDistance());
    }

    @Test
    void run_handles_concurrent_elevation_exactly_at_target() {
        MockRTPPlayer player = new MockRTPPlayer();
        player.setViewDistance(10);
        ViewDistanceRestoreTask.clampAndSchedule(player, 2, 100L);

        // Concurrently elevated exactly to target (10)
        player.setViewDistance(10);
        scheduler.tick(10);
        assertEquals(10, player.getViewDistance());
        assertEquals(-1, player.getSendViewDistance());

        // Subsequent ticks should NOT do anything because task finished
        scheduler.tick(50);
        assertEquals(10, player.getViewDistance());
    }

    @Test
    void clampAndSchedule_steps_exactly_zero_noops() {
        MockRTPPlayer player = new MockRTPPlayer();
        player.setViewDistance(4);
        ViewDistanceRestoreTask.clampAndSchedule(player, 4, 100L);
        assertEquals(4, player.getViewDistance());
        assertEquals(-1, player.getSendViewDistance());
    }

    @Test
    void clampAndSchedule_steps_less_than_zero_noops() {
        MockRTPPlayer player = new MockRTPPlayer();
        player.setViewDistance(4);
        ViewDistanceRestoreTask.clampAndSchedule(player, 6, 100L);
        assertEquals(4, player.getViewDistance());
    }

    @Test
    void run_happy_path_ramps_view_distance_to_target() {
        MockRTPPlayer player = new MockRTPPlayer();
        player.setViewDistance(10);
        // Clamp to 2 with interval 100L (steps = 10 - 2 = 8, stepInterval = 100/8 = 12 ticks)
        ViewDistanceRestoreTask.clampAndSchedule(player, 2, 100L);
        assertEquals(2, player.getViewDistance());
        assertEquals(10, player.getSendViewDistance());

        // Step through ticks and verify progressive ramping
        for (int i = 0; i < 150; i++) {
            scheduler.tick(1);
        }

        // Must reach target view distance 10 and release send pin (-1)
        assertEquals(10, player.getViewDistance());
        assertEquals(-1, player.getSendViewDistance());
    }

    @Test
    void run_first_step_dwell_timing_kills_total_marginal_math_mutant() {
        MockRTPPlayer player = new MockRTPPlayer();
        player.setViewDistance(10);
        // Captured = 10, clamp = 2.
        // ringChunks(10) - ringChunks(2) = 21^2 - 5^2 = 441 - 25 = 416.
        // For r=2: marginal = 8*2 + 8 = 24.
        // dwell = round(100 * 24 / 416) = round(5.769) = 6 ticks.
        // If mutated to subtraction->addition (+): 441 + 25 = 466.
        // dwell would be round(100 * 24 / 466) = round(5.15) = 5 ticks!
        ViewDistanceRestoreTask.clampAndSchedule(player, 2, 100L);
        assertEquals(2, player.getViewDistance());

        // At tick 5, with correct formula dwell is 6, so viewDistance MUST still be 2.
        scheduler.tick(5);
        assertEquals(2, player.getViewDistance(), "At tick 5, viewDistance should still be clamped at 2");

        // At tick 6, the task should fire and advance to 3.
        scheduler.tick(1);
        assertEquals(3, player.getViewDistance(), "At tick 6, viewDistance should advance to 3");
    }

    @Test
    void run_reaches_target_when_current_equals_target() {
        MockRTPPlayer player = new MockRTPPlayer();
        player.setViewDistance(3);
        ViewDistanceRestoreTask.clampAndSchedule(player, 2, 50L);
        assertEquals(2, player.getViewDistance());

        for (int i = 0; i < 60; i++) {
            scheduler.tick(1);
        }

        assertEquals(3, player.getViewDistance());
        assertEquals(-1, player.getSendViewDistance());
    }

    @Test
    void run_finish_sets_cancelled_preventing_future_runs() {
        MockRTPPlayer player = new MockRTPPlayer();
        player.setViewDistance(3);
        ViewDistanceRestoreTask.clampAndSchedule(player, 2, 50L);

        // When offline, run calls finish() which sets cancelled
        player.setOnline(false);
        scheduler.tick(100);

        // Turn back online: task was cancelled so it should never run again
        player.setOnline(true);
        scheduler.tick(100);
        assertEquals(2, player.getViewDistance());
    }
}
