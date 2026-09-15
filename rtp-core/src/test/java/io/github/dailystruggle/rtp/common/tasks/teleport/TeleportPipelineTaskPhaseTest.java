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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TeleportPipelineTask} phase state machine, action hooks,
 * and cancellation behaviour - all executed synchronously (no real threads).
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
        // idempotent
        task.setCancelled(true);
        assertTrue(task.isCancelled());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void setCancelled_false_when_not_cancelled_is_noop() {
        TeleportPipelineTask task = buildMinimalTask();
        task.setCancelled(false);
        assertFalse(task.isCancelled());
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
    // run() when cancelled - must transition to CLEANUP without throwing
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
    // Static action hook lists - add / clear
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
    // Pipeline histogram wiring
    // -----------------------------------------------------------------------

    /** Regression guard for REQ-RTP-OBS-002 (Single-Sample Pipeline Recording). */
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
    // Constructors and Getters
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void constructors_and_getters_integrity() {
        io.github.dailystruggle.rtp.api.world.RTPWorld targetWorld = new io.github.dailystruggle.rtp.common.mock.MockRTPWorld("test_w");
        io.github.dailystruggle.rtp.api.world.RTPCoords coords = new io.github.dailystruggle.rtp.api.world.RTPCoords("test_w", 10, 64, 20);
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer mockPlayer = new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
                java.util.UUID.randomUUID(), "TestP", new io.github.dailystruggle.rtp.api.world.RTPLocation(targetWorld, 0, 64, 0));
        io.github.dailystruggle.rtp.api.selection.GenerationContext ctx =
                new io.github.dailystruggle.rtp.api.selection.GenerationContext(mockPlayer, mockPlayer, null);

        // 3-arg constructor
        TeleportPipelineTask task3 = new TeleportPipelineTask(ctx, null, coords);
        assertNotNull(task3.coords());
        assertEquals(coords, task3.coords());
        assertSame(mockPlayer, task3.player());
        assertNull(task3.region());

        // 4-arg constructor
        TeleportPipelineTask task4 = new TeleportPipelineTask(ctx, null, coords, null);
        assertNotNull(task4.coords());
        assertEquals(coords, task4.coords());
        assertSame(mockPlayer, task4.player());
        assertNull(task4.region());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void actionHooks_firing_check() {
        TeleportPipelineTask task = buildMinimalTask();
        AtomicInteger cleanupPre = new AtomicInteger();
        AtomicInteger cleanupPost = new AtomicInteger();
        TeleportPipelineTask.cleanupPreActions.add(t -> cleanupPre.incrementAndGet());
        TeleportPipelineTask.cleanupPostActions.add(t -> cleanupPost.incrementAndGet());

        task.setCancelled(true);
        task.run();

        assertEquals(1, cleanupPre.get());
        assertEquals(1, cleanupPost.get());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void full_teleport_stage_assertions() {
        io.github.dailystruggle.rtp.common.mock.TrackedMockWorld world = new io.github.dailystruggle.rtp.common.mock.TrackedMockWorld("full_pipe_w");
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                (io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) io.github.dailystruggle.rtp.common.RTP.serverAccessor;
        accessor.addWorld(world);

        io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle circle =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle();
        circle.setRng(new java.util.Random(42L));
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor vert =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(new ArrayList<>());
        io.github.dailystruggle.rtp.common.selection.region.RegionSettings settings =
                new io.github.dailystruggle.rtp.common.selection.region.RegionSettings(
                        "full_test_reg", world, circle, vert, false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        io.github.dailystruggle.rtp.common.selection.region.Region reg =
                new io.github.dailystruggle.rtp.common.selection.region.Region("full_test_reg", settings);
        io.github.dailystruggle.rtp.common.RTP.selectionAPI.permRegionLookup.put(reg.name, reg);

        java.util.UUID pid = java.util.UUID.randomUUID();
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer p = new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
                pid, "FullPipeP", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(p);

        io.github.dailystruggle.rtp.api.world.RTPCoords targetCoords = new io.github.dailystruggle.rtp.api.world.RTPCoords(world.name(), 50, 70, 50);
        java.util.List<java.util.concurrent.CompletableFuture<Long>> chunks = new java.util.ArrayList<>();
        chunks.add(java.util.concurrent.CompletableFuture.completedFuture(0L));
        io.github.dailystruggle.rtp.api.world.ChunkSet set = new io.github.dailystruggle.rtp.api.world.ChunkSet(world, 3, 3, chunks, java.util.concurrent.CompletableFuture.completedFuture(true));
        io.github.dailystruggle.rtp.api.world.ChunkReservation res = new io.github.dailystruggle.rtp.api.world.ChunkReservation(set, world);

        io.github.dailystruggle.rtp.api.selection.GenerationContext ctx = new io.github.dailystruggle.rtp.api.selection.GenerationContext(p, p, null);

        // Configure options to exercise all branches in TeleportPipelineTask
        TeleportPipelineTask.ConfigCache.setRespawnOnTeleport = true;
        TeleportPipelineTask.ConfigCache.lockAfterUses = 10;
        TeleportPipelineTask.ConfigCache.lockAfterResetMillis = 60000;
        TeleportPipelineTask.ConfigCache.teleportMessage = "Welcome arrived!";
        TeleportPipelineTask.ConfigCache.unsafe = "Destination unsafe!";

        AtomicInteger loadPre = new AtomicInteger();
        AtomicInteger loadPost = new AtomicInteger();
        AtomicInteger teleportPre = new AtomicInteger();
        AtomicInteger teleportPost = new AtomicInteger();

        TeleportPipelineTask.loadPreActions.add(t -> loadPre.incrementAndGet());
        TeleportPipelineTask.loadPostActions.add(t -> loadPost.incrementAndGet());
        TeleportPipelineTask.teleportPreActions.add(t -> teleportPre.incrementAndGet());
        TeleportPipelineTask.teleportPostActions.add(t -> teleportPost.incrementAndGet());

        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, targetCoords, res);

        io.github.dailystruggle.rtp.common.RTP.getInstance().databaseAccessor = org.mockito.Mockito.mock(io.github.dailystruggle.rtp.common.database.DatabaseAccessor.class);

        // Run through LOAD -> TELEPORT -> CLEANUP
        task.run();
        accessor.getMockScheduler().tick(1);
        accessor.getMockScheduler().tick(1);
        accessor.getMockScheduler().tick(1);

        assertEquals(1, loadPre.get(), "loadPre should execute");
        assertEquals(1, loadPost.get(), "loadPost should execute");
        assertEquals(1, teleportPre.get(), "teleportPre should execute");
        assertEquals(1, teleportPost.get(), "teleportPost should execute");
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void setup_phase_drives_location_generator_and_transitions_to_load() {
        io.github.dailystruggle.rtp.common.mock.TrackedMockWorld world = new io.github.dailystruggle.rtp.common.mock.TrackedMockWorld("setup_pipe_w");
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                (io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) io.github.dailystruggle.rtp.common.RTP.serverAccessor;
        accessor.addWorld(world);

        io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle circle =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle();
        circle.setRng(new java.util.Random(42L));
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor vert =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(new ArrayList<>());
        io.github.dailystruggle.rtp.common.selection.region.RegionSettings settings =
                new io.github.dailystruggle.rtp.common.selection.region.RegionSettings(
                        "setup_test_reg", world, circle, vert, false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        io.github.dailystruggle.rtp.common.selection.region.Region reg =
                new io.github.dailystruggle.rtp.common.selection.region.Region("setup_test_reg", settings);
        io.github.dailystruggle.rtp.common.RTP.selectionAPI.permRegionLookup.put(reg.name, reg);

        java.util.UUID pid = java.util.UUID.randomUUID();
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer p = new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
                pid, "SetupPipeP", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(p);

        io.github.dailystruggle.rtp.api.world.RTPCoords targetCoords = new io.github.dailystruggle.rtp.api.world.RTPCoords(world.name(), 10, 64, 10);
        accessor.setLocationGenerator(new io.github.dailystruggle.rtp.common.mock.MockLocationGenerator(world) {
            @Override
            public java.util.concurrent.CompletableFuture<io.github.dailystruggle.rtp.api.selection.GenerationResult> getLocation(Object r, io.github.dailystruggle.rtp.api.selection.GenerationContext c) {
                return java.util.concurrent.CompletableFuture.completedFuture(new io.github.dailystruggle.rtp.api.selection.GenerationResult(targetCoords, 1, null));
            }
        });

        io.github.dailystruggle.rtp.api.selection.GenerationContext ctx = new io.github.dailystruggle.rtp.api.selection.GenerationContext(p, p, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);

        assertEquals(TeleportPipelineTask.Phase.SETUP, task.getPhase());
        task.run(); // runs runSetup() -> processGenerationResult() -> transitions to LOAD and schedules
        accessor.getMockScheduler().tick(1);

        assertTrue(task.coords() != null);
        assertEquals(targetCoords, task.coords());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void setup_phase_null_coords_fails_gracefully_to_cleanup() {
        io.github.dailystruggle.rtp.common.mock.TrackedMockWorld world = new io.github.dailystruggle.rtp.common.mock.TrackedMockWorld("setup_fail_w");
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                (io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) io.github.dailystruggle.rtp.common.RTP.serverAccessor;
        accessor.addWorld(world);

        io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle circle =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle();
        circle.setRng(new java.util.Random(42L));
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor vert =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(new ArrayList<>());
        io.github.dailystruggle.rtp.common.selection.region.RegionSettings settings =
                new io.github.dailystruggle.rtp.common.selection.region.RegionSettings(
                        "setup_fail_reg", world, circle, vert, false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        io.github.dailystruggle.rtp.common.selection.region.Region reg =
                new io.github.dailystruggle.rtp.common.selection.region.Region("setup_fail_reg", settings);
        io.github.dailystruggle.rtp.common.RTP.selectionAPI.permRegionLookup.put(reg.name, reg);

        java.util.UUID pid = java.util.UUID.randomUUID();
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer p = new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
                pid, "SetupFailP", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(p);

        accessor.setLocationGenerator(new io.github.dailystruggle.rtp.common.mock.MockLocationGenerator(world) {
            @Override
            public java.util.concurrent.CompletableFuture<io.github.dailystruggle.rtp.api.selection.GenerationResult> getLocation(Object r, io.github.dailystruggle.rtp.api.selection.GenerationContext c) {
                return java.util.concurrent.CompletableFuture.completedFuture(new io.github.dailystruggle.rtp.api.selection.GenerationResult(null, 5, null));
            }
        });

        io.github.dailystruggle.rtp.api.selection.GenerationContext ctx = new io.github.dailystruggle.rtp.api.selection.GenerationContext(p, p, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg);

        task.run();
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    private record DummySchematic(int width, int height, int length, int offsetX, int offsetY, int offsetZ) implements io.github.dailystruggle.rtp.api.schematic.LoadedSchematic {
        @Override
        public io.github.dailystruggle.rtp.api.schematic.SchematicSource source() { return null; }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void schematicFootprintClear_checks() {
        io.github.dailystruggle.rtp.common.mock.TrackedMockWorld world = new io.github.dailystruggle.rtp.common.mock.TrackedMockWorld("footprint_w");
        io.github.dailystruggle.rtp.api.world.RTPLocation loc = new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 100, 64, 100);

        io.github.dailystruggle.rtp.api.schematic.PasteOptions optionsCenter =
                new io.github.dailystruggle.rtp.api.schematic.PasteOptions(io.github.dailystruggle.rtp.api.schematic.PasteAnchor.CENTER, false, true);
        io.github.dailystruggle.rtp.api.schematic.PasteOptions optionsOrigin =
                new io.github.dailystruggle.rtp.api.schematic.PasteOptions(io.github.dailystruggle.rtp.api.schematic.PasteAnchor.ORIGIN, false, true);

        io.github.dailystruggle.rtp.api.schematic.LoadedSchematic emptySchematic =
                new DummySchematic(0, 0, 0, 0, 0, 0);
        assertTrue(TeleportPipelineTask.schematicFootprintClear(emptySchematic, loc, optionsCenter, world.name()));

        io.github.dailystruggle.rtp.api.schematic.LoadedSchematic nonNullSchematic =
                new DummySchematic(2, 2, 2, 1, 0, 1);
        assertTrue(TeleportPipelineTask.schematicFootprintClear(nonNullSchematic, loc, optionsCenter, world.name()));
        assertTrue(TeleportPipelineTask.schematicFootprintClear(nonNullSchematic, loc, optionsOrigin, world.name()));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void schematicFootprintClear_with_verifiers() {
        io.github.dailystruggle.rtp.common.mock.TrackedMockWorld world = new io.github.dailystruggle.rtp.common.mock.TrackedMockWorld("footprint_claim_w");
        io.github.dailystruggle.rtp.api.world.RTPLocation loc = new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 100, 64, 100);

        io.github.dailystruggle.rtp.api.schematic.PasteOptions optionsCenter =
                new io.github.dailystruggle.rtp.api.schematic.PasteOptions(io.github.dailystruggle.rtp.api.schematic.PasteAnchor.CENTER, false, true);

        io.github.dailystruggle.rtp.api.schematic.LoadedSchematic schematic =
                new DummySchematic(2, 2, 2, 0, 0, 0);

        // Test with a verifier that rejects
        java.util.function.Predicate<io.github.dailystruggle.rtp.api.world.RTPCoords> rejectVerifier = c -> false;
        io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers.addGlobalRegionVerifier(rejectVerifier);
        try {
            assertFalse(TeleportPipelineTask.schematicFootprintClear(schematic, loc, optionsCenter, world.name()));
        } finally {
            io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers.clearGlobalRegionVerifiers();
        }

        // Test with a verifier that throws
        java.util.function.Predicate<io.github.dailystruggle.rtp.api.world.RTPCoords> throwingVerifier = c -> { throw new RuntimeException("fail"); };
        io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers.addGlobalRegionVerifier(throwingVerifier);
        try {
            assertFalse(TeleportPipelineTask.schematicFootprintClear(schematic, loc, optionsCenter, world.name()));
        } finally {
            io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers.clearGlobalRegionVerifiers();
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void platform_building_when_standing_on_air_or_unsafe() {
        AtomicBoolean platformBuilt = new AtomicBoolean(false);
        io.github.dailystruggle.rtp.common.mock.TrackedMockWorld world = new io.github.dailystruggle.rtp.common.mock.TrackedMockWorld("platform_pipe_w") {
            @Override
            public void platform(io.github.dailystruggle.rtp.api.world.RTPLocation loc) {
                platformBuilt.set(true);
            }
        };
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                (io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) io.github.dailystruggle.rtp.common.RTP.serverAccessor;
        accessor.addWorld(world);

        io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle circle =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle();
        circle.setRng(new java.util.Random(42L));
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor vert =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(new ArrayList<>());
        io.github.dailystruggle.rtp.common.selection.region.RegionSettings settings =
                new io.github.dailystruggle.rtp.common.selection.region.RegionSettings(
                        "plat_test_reg", world, circle, vert, false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        io.github.dailystruggle.rtp.common.selection.region.Region reg =
                new io.github.dailystruggle.rtp.common.selection.region.Region("plat_test_reg", settings);
        io.github.dailystruggle.rtp.common.RTP.selectionAPI.permRegionLookup.put(reg.name, reg);

        java.util.UUID pid = java.util.UUID.randomUUID();
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer p = new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
                pid, "PlatPipeP", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(p);

        io.github.dailystruggle.rtp.api.world.RTPCoords targetCoords = new io.github.dailystruggle.rtp.api.world.RTPCoords(world.name(), 50, 70, 50);
        java.util.List<java.util.concurrent.CompletableFuture<Long>> chunks = new java.util.ArrayList<>();
        chunks.add(java.util.concurrent.CompletableFuture.completedFuture(0L));
        io.github.dailystruggle.rtp.api.world.ChunkSet set = new io.github.dailystruggle.rtp.api.world.ChunkSet(world, 3, 3, chunks, java.util.concurrent.CompletableFuture.completedFuture(true));
        io.github.dailystruggle.rtp.api.world.ChunkReservation res = new io.github.dailystruggle.rtp.api.world.ChunkReservation(set, world);

        io.github.dailystruggle.rtp.api.selection.GenerationContext ctx = new io.github.dailystruggle.rtp.api.selection.GenerationContext(p, p, null);

        TeleportPipelineTask task = new TeleportPipelineTask(ctx, reg, targetCoords, res);
        task.run();
        accessor.getMockScheduler().tick(1);

        assertTrue(platformBuilt.get(), "Platform should be built because chunk directly below is air");
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void platform_creator_lifecycle_handling() {
        io.github.dailystruggle.rtp.common.mock.TrackedMockWorld world = new io.github.dailystruggle.rtp.common.mock.TrackedMockWorld("plat_creator_w");
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                (io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) io.github.dailystruggle.rtp.common.RTP.serverAccessor;
        accessor.addWorld(world);

        java.util.UUID pid = java.util.UUID.randomUUID();
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer p = new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
                pid, "PlatCreatorP", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(p);

        io.github.dailystruggle.rtp.api.world.RTPCoords targetCoords = new io.github.dailystruggle.rtp.api.world.RTPCoords(world.name(), 50, 70, 50);
        java.util.List<java.util.concurrent.CompletableFuture<Long>> chunks = new java.util.ArrayList<>();
        chunks.add(java.util.concurrent.CompletableFuture.completedFuture(0L));
        io.github.dailystruggle.rtp.api.world.ChunkSet set = new io.github.dailystruggle.rtp.api.world.ChunkSet(world, 3, 3, chunks, java.util.concurrent.CompletableFuture.completedFuture(true));
        io.github.dailystruggle.rtp.api.world.ChunkReservation res = new io.github.dailystruggle.rtp.api.world.ChunkReservation(set, world);

        io.github.dailystruggle.rtp.api.selection.GenerationContext ctx = new io.github.dailystruggle.rtp.api.selection.GenerationContext(p, p, null);

        io.github.dailystruggle.rtp.common.RTP.getInstance().databaseAccessor = org.mockito.Mockito.mock(io.github.dailystruggle.rtp.common.database.DatabaseAccessor.class);

        // Test with custom platform creator returning true
        AtomicBoolean customCreated = new AtomicBoolean(false);
        io.github.dailystruggle.rtp.api.platform.PlatformCreator customCreator = new io.github.dailystruggle.rtp.api.platform.PlatformCreator() {
            @Override
            public String creatorName() { return "CustomTestCreator"; }
            @Override
            public CompletableFuture<?> prepare(io.github.dailystruggle.rtp.api.world.RTPLocation arrivalLocation) {
                return CompletableFuture.completedFuture("prepared");
            }
            @Override
            public boolean createPlatform(io.github.dailystruggle.rtp.api.world.RTPLocation arrivalLocation, Object preparedHandle) {
                customCreated.set(true);
                return true;
            }
        };

        io.github.dailystruggle.rtp.api.RTPAPI.hooks().platformCreator().bind(customCreator);
        try {
            java.lang.reflect.Method m = TeleportPipelineTask.class.getDeclaredMethod("buildArrivalPlatform", io.github.dailystruggle.rtp.api.world.RTPLocation.class);
            m.setAccessible(true);
            TeleportPipelineTask task = new TeleportPipelineTask(ctx, null, targetCoords, res);
            java.lang.reflect.Field fCreator = TeleportPipelineTask.class.getDeclaredField("platformCreator");
            fCreator.setAccessible(true);
            fCreator.set(task, customCreator);
            java.lang.reflect.Field fPrepare = TeleportPipelineTask.class.getDeclaredField("platformPrepare");
            fPrepare.setAccessible(true);
            fPrepare.set(task, CompletableFuture.completedFuture("prepared"));
            m.invoke(task, new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 50, 70, 50));
            assertTrue(customCreated.get(), "Custom creator should have created platform");
        } catch (Exception e) {
            fail(e);
        } finally {
            io.github.dailystruggle.rtp.api.RTPAPI.hooks().platformCreator().clear();
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void config_cache_reload_reads_config_values() {
        assertDoesNotThrow(TeleportPipelineTask.ConfigCache::reload);
        assertNotNull(TeleportPipelineTask.ConfigCache.unsafe);
        assertNotNull(TeleportPipelineTask.ConfigCache.teleportMessage);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void run_when_cancelled_aborts_immediately() {
        TeleportPipelineTask task = buildMinimalTask();
        task.setCancelled(true);
        task.run();
        // Remains cancelled and doesn't progress
        assertTrue(task.isCancelled());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void processGenerationResult_null_result_when_player_in_processing_halts() throws Exception {
        io.github.dailystruggle.rtp.common.mock.TrackedMockWorld world = new io.github.dailystruggle.rtp.common.mock.TrackedMockWorld("pgr_null_proc_w");
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                (io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) io.github.dailystruggle.rtp.common.RTP.serverAccessor;
        accessor.addWorld(world);

        UUID pid = UUID.randomUUID();
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer p = new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
                pid, "ProcPlayer", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(p);

        io.github.dailystruggle.rtp.common.RTP.getInstance().processingPlayers.add(pid);
        try {
            io.github.dailystruggle.rtp.api.selection.GenerationContext ctx =
                    new io.github.dailystruggle.rtp.api.selection.GenerationContext(p, p, null);
            TeleportPipelineTask task = new TeleportPipelineTask(ctx);

            java.lang.reflect.Method m = TeleportPipelineTask.class.getDeclaredMethod("processGenerationResult",
                    io.github.dailystruggle.rtp.api.selection.GenerationResult.class);
            m.setAccessible(true);
            m.invoke(task, (Object) null);

            // Phase should not have progressed to LOAD or CLEANUP
            assertEquals(TeleportPipelineTask.Phase.SETUP, task.getPhase());
        } finally {
            io.github.dailystruggle.rtp.common.RTP.getInstance().processingPlayers.remove(pid);
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void processGenerationResult_null_coords_triggers_unsafe_and_cleanup() throws Exception {
        io.github.dailystruggle.rtp.common.mock.TrackedMockWorld world = new io.github.dailystruggle.rtp.common.mock.TrackedMockWorld("pgr_unsafe_w");
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                (io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) io.github.dailystruggle.rtp.common.RTP.serverAccessor;
        accessor.addWorld(world);

        UUID pid = UUID.randomUUID();
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer p = new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
                pid, "UnsafePlayer", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(p);

        io.github.dailystruggle.rtp.api.selection.GenerationContext ctx =
                new io.github.dailystruggle.rtp.api.selection.GenerationContext(p, p, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx);

        io.github.dailystruggle.rtp.api.selection.GenerationResult nullCoordsResult =
                new io.github.dailystruggle.rtp.api.selection.GenerationResult(null, 5L, null);

        java.lang.reflect.Method m = TeleportPipelineTask.class.getDeclaredMethod("processGenerationResult",
                io.github.dailystruggle.rtp.api.selection.GenerationResult.class);
        m.setAccessible(true);
        m.invoke(task, nullCoordsResult);

        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void constructors_and_tracking_lifecycle() {
        io.github.dailystruggle.rtp.common.mock.TrackedMockWorld world = new io.github.dailystruggle.rtp.common.mock.TrackedMockWorld("constructors_w");
        UUID pid = UUID.randomUUID();
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer p = new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
                pid, "TrackP", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
        io.github.dailystruggle.rtp.api.selection.GenerationContext ctx =
                new io.github.dailystruggle.rtp.api.selection.GenerationContext(p, p, null);

        TeleportPipelineTask t1 = new TeleportPipelineTask(ctx);
        assertNotNull(t1);
        t1.setCancelled(true);
        assertTrue(t1.isCancelled());

        TeleportPipelineTask t2 = new TeleportPipelineTask(ctx, null);
        assertNotNull(t2);
        t2.setPhase(TeleportPipelineTask.Phase.LOAD);
        assertEquals(TeleportPipelineTask.Phase.LOAD, t2.getPhase());

        io.github.dailystruggle.rtp.api.world.RTPCoords coords = new io.github.dailystruggle.rtp.api.world.RTPCoords(world.name(), 10, 64, 10);
        TeleportPipelineTask t3 = new TeleportPipelineTask(ctx, null, coords);
        assertEquals(TeleportPipelineTask.Phase.LOAD, t3.getPhase());

        TeleportPipelineTask t4 = new TeleportPipelineTask(ctx, null, coords, null);
        assertEquals(TeleportPipelineTask.Phase.LOAD, t4.getPhase());
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
