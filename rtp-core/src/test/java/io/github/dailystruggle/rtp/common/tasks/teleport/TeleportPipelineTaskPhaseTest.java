package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TeleportPipelineTask} phase state machine, action hooks,
 * and cancellation behaviour — all executed synchronously (no real threads).
 */
class TeleportPipelineTaskPhaseTest {

    @TempDir
    File tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        clearAllHooks();
    }

    @AfterEach
    void tearDown() {
        clearAllHooks();
        io.github.dailystruggle.rtp.common.RTP.getInstance().latestTeleportData.clear();
        io.github.dailystruggle.rtp.common.RTP.getInstance().processingPlayers.clear();
    }

    // -----------------------------------------------------------------------
    // Phase enum ordering
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void phase_enum_values_are_defined_in_expected_order() {
        TeleportPipelineTask.Phase[] phases = TeleportPipelineTask.Phase.values();
        assertEquals(4, phases.length);
        assertEquals(TeleportPipelineTask.Phase.SETUP,   phases[0]);
        assertEquals(TeleportPipelineTask.Phase.LOAD,    phases[1]);
        assertEquals(TeleportPipelineTask.Phase.TELEPORT, phases[2]);
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, phases[3]);
    }

    // -----------------------------------------------------------------------
    // setPhase / getPhase round-trip
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void setPhase_and_getPhase_roundtrip_all_phases() {
        TeleportPipelineTask task = buildMinimalTask();

        for (TeleportPipelineTask.Phase phase : TeleportPipelineTask.Phase.values()) {
            task.setPhase(phase);
            assertEquals(phase, task.getPhase(),
                    "getPhase() must return the phase set by setPhase()");
        }
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void default_phase_is_SETUP_for_context_only_constructor() {
        TeleportPipelineTask task = buildMinimalTask();
        assertEquals(TeleportPipelineTask.Phase.SETUP, task.getPhase());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void constructor_with_coords_starts_at_LOAD_phase() {
        io.github.dailystruggle.rtp.api.selection.GenerationContext ctx = buildContext();
        io.github.dailystruggle.rtp.api.world.RTPCoords coords =
                new io.github.dailystruggle.rtp.api.world.RTPCoords("world", 0, 64, 0);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, null, coords);
        assertEquals(TeleportPipelineTask.Phase.LOAD, task.getPhase());
    }

    // -----------------------------------------------------------------------
    // Cancellation flag
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void task_is_not_cancelled_by_default() {
        TeleportPipelineTask task = buildMinimalTask();
        assertFalse(task.isCancelled());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void setCancelled_true_marks_task_as_cancelled() {
        TeleportPipelineTask task = buildMinimalTask();
        task.setCancelled(true);
        assertTrue(task.isCancelled());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void setCancelled_false_clears_cancellation() {
        TeleportPipelineTask task = buildMinimalTask();
        task.setCancelled(true);
        task.setCancelled(false);
        assertFalse(task.isCancelled());
    }

    // -----------------------------------------------------------------------
    // run() when cancelled — must transition to CLEANUP without throwing
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void run_when_cancelled_transitions_to_CLEANUP_and_does_not_throw() {
        TeleportPipelineTask task = buildMinimalTask();
        task.setCancelled(true);
        // run() must not throw even when the player is null (context has no player)
        assertDoesNotThrow(task::run);
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    // -----------------------------------------------------------------------
    // Static action hook lists — add / clear
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void setupPreActions_can_be_added_and_cleared() {
        AtomicInteger counter = new AtomicInteger(0);
        TeleportPipelineTask.setupPreActions.add(t -> counter.incrementAndGet());
        assertFalse(TeleportPipelineTask.setupPreActions.isEmpty());
        TeleportPipelineTask.setupPreActions.clear();
        assertTrue(TeleportPipelineTask.setupPreActions.isEmpty());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void loadPreActions_can_be_added_and_cleared() {
        TeleportPipelineTask.loadPreActions.add(t -> {});
        assertFalse(TeleportPipelineTask.loadPreActions.isEmpty());
        TeleportPipelineTask.loadPreActions.clear();
        assertTrue(TeleportPipelineTask.loadPreActions.isEmpty());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void teleportPreActions_can_be_added_and_cleared() {
        TeleportPipelineTask.teleportPreActions.add(t -> {});
        assertFalse(TeleportPipelineTask.teleportPreActions.isEmpty());
        TeleportPipelineTask.teleportPreActions.clear();
        assertTrue(TeleportPipelineTask.teleportPreActions.isEmpty());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void cleanupPreActions_can_be_added_and_cleared() {
        TeleportPipelineTask.cleanupPreActions.add(t -> {});
        assertFalse(TeleportPipelineTask.cleanupPreActions.isEmpty());
        TeleportPipelineTask.cleanupPreActions.clear();
        assertTrue(TeleportPipelineTask.cleanupPreActions.isEmpty());
    }

    // -----------------------------------------------------------------------
    // setupPreActions invoked during run() in SETUP phase
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void setupPreActions_are_invoked_when_run_in_SETUP_phase() {
        List<String> events = new ArrayList<>();
        TeleportPipelineTask.setupPreActions.add(t -> events.add("setupPre"));

        TeleportPipelineTask task = buildMinimalTask();
        // player() is null → runSetup will call runCleanup after pre-actions
        assertDoesNotThrow(task::run);
        assertTrue(events.contains("setupPre"),
                "setupPreActions must fire during SETUP phase run()");
    }

    // -----------------------------------------------------------------------
    // player() / sender() accessors
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void player_returns_null_when_context_has_no_player() {
        TeleportPipelineTask task = buildMinimalTask();
        assertNull(task.player());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void coords_returns_null_before_any_location_is_selected() {
        TeleportPipelineTask task = buildMinimalTask();
        assertNull(task.coords());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void region_returns_null_when_not_provided() {
        TeleportPipelineTask task = buildMinimalTask();
        assertNull(task.region());
    }

    // -----------------------------------------------------------------------
    // RTPRunnable base behaviour
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void task_is_an_RTPRunnable() {
        TeleportPipelineTask task = buildMinimalTask();
        assertInstanceOf(RTPRunnable.class, task);
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void isRunning_is_false_before_any_run() {
        TeleportPipelineTask task = buildMinimalTask();
        assertFalse(task.isRunning());
    }

    // -----------------------------------------------------------------------
    // Delay calculation helpers (via RTPRunnable base)
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void default_delay_is_zero() {
        TeleportPipelineTask task = buildMinimalTask();
        assertEquals(0L, task.getDelay());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void setDelay_and_getDelay_roundtrip() {
        TeleportPipelineTask task = buildMinimalTask();
        task.setDelay(42L);
        assertEquals(42L, task.getDelay());
    }

    // -----------------------------------------------------------------------
    // Phase M1 — pipeline histogram wiring
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void runCleanup_records_one_sample_into_pipeline_histogram() {
        long before = io.github.dailystruggle.rtp.common.RTP.metrics
                .pipelineHistogram().totalRecorded();
        TeleportPipelineTask task = buildMinimalTask();
        task.setCancelled(true);
        // Drives runCleanup via the cancelled-path in run().
        assertDoesNotThrow(task::run);
        long after = io.github.dailystruggle.rtp.common.RTP.metrics
                .pipelineHistogram().totalRecorded();
        assertEquals(before + 1, after,
                "runCleanup must record exactly one pipeline sample");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void runCleanup_is_idempotent_for_pipeline_histogram() {
        TeleportPipelineTask task = buildMinimalTask();
        task.setCancelled(true);
        long before = io.github.dailystruggle.rtp.common.RTP.metrics
                .pipelineHistogram().totalRecorded();
        assertDoesNotThrow(task::run);
        assertDoesNotThrow(task::run); // a second invocation must not double-record
        long after = io.github.dailystruggle.rtp.common.RTP.metrics
                .pipelineHistogram().totalRecorded();
        assertEquals(before + 1, after,
                "histogram must record exactly once per task lifecycle");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void clearAllHooks() {
        TeleportPipelineTask.setupPreActions.clear();
        TeleportPipelineTask.setupPostActions.clear();
        TeleportPipelineTask.loadPreActions.clear();
        TeleportPipelineTask.loadPostActions.clear();
        TeleportPipelineTask.teleportPreActions.clear();
        TeleportPipelineTask.teleportPostActions.clear();
        TeleportPipelineTask.cleanupPreActions.clear();
        TeleportPipelineTask.cleanupPostActions.clear();

        // Re-install no-op placeholders so the lists are non-empty but don't
        // reference protected fields from outside the class hierarchy.
    }

    /** Builds a {@link TeleportPipelineTask} whose context has no player (null). */
    private static TeleportPipelineTask buildMinimalTask() {
        return new TeleportPipelineTask(buildContext());
    }

    private static io.github.dailystruggle.rtp.api.selection.GenerationContext buildContext() {
        return new io.github.dailystruggle.rtp.api.selection.GenerationContext(null, null, null);
    }
}
