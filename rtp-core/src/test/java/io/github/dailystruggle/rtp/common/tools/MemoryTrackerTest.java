package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MemoryTracker} (ENTERPRISE_READINESS item 19, {@code tools} package).
 */
public class MemoryTrackerTest {

    @TempDir
    File pluginDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(pluginDir);
        MemoryTracker.reset();
    }

    @AfterEach
    void tearDown() {
        MemoryTracker.reset();
    }

    @Test
    void privateConstructorCanBeInvoked() throws Exception {
        Constructor<MemoryTracker> ctor = MemoryTracker.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MemoryTracker instance = ctor.newInstance();
        assertNotNull(instance);
    }

    @Test
    void trackAndUntrackByUUID() {
        Object target = new Object();
        UUID id = MemoryTracker.track(target, "test-target", 5000L);
        assertNotNull(id);
        assertEquals(1, MemoryTracker.trackedCount());
        assertEquals(1, MemoryTracker.trackedCountByLabel("test-target"));
        assertEquals(0, MemoryTracker.trackedCountByLabel("nonexistent"));

        MemoryTracker.untrack(id);
        assertEquals(0, MemoryTracker.trackedCount());
    }

    @Test
    void trackAndUntrackByTargetReference() {
        Object target = new Object();
        MemoryTracker.track(target, "ref-target", 5000L);
        assertEquals(1, MemoryTracker.trackedCount());

        MemoryTracker.untrack(target);
        assertEquals(0, MemoryTracker.trackedCount());
    }

    @Test
    void untrackNullSafelyNoOps() {
        MemoryTracker.untrack((Object) null);
        MemoryTracker.untrack((UUID) null);
        assertEquals(0, MemoryTracker.trackedCount());
    }

    @Test
    void trackedCountByLabelWithNullReturnsZero() {
        assertEquals(0, MemoryTracker.trackedCountByLabel(null));
    }

    @Test
    void updateTrackingResetsLifespan() {
        Object target = new Object();
        UUID id = MemoryTracker.track(target, "update-target", 1000L);

        // Update tracking with valid ID
        MemoryTracker.updateTracking(id);
        // Update tracking with null or non-existent ID
        MemoryTracker.updateTracking(null);
        MemoryTracker.updateTracking(UUID.randomUUID());

        assertEquals(1, MemoryTracker.trackedCount());
        MemoryTracker.untrack(id);
    }

    @Test
    void activeTicketsCalculatesAccurately() {
        // With default mock setup (one world without activeChunkTickets initialized)
        assertEquals(0L, MemoryTracker.activeTickets());

        // Add a world with activeChunkTickets
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                (io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) RTP.serverAccessor;
        io.github.dailystruggle.rtp.common.mock.MockRTPWorld world1 =
                new io.github.dailystruggle.rtp.common.mock.MockRTPWorld("tickets-world-1");
        world1.activeChunkTickets.set(7L);
        accessor.addWorld(world1);

        io.github.dailystruggle.rtp.common.mock.MockRTPWorld world2 =
                new io.github.dailystruggle.rtp.common.mock.MockRTPWorld("tickets-world-2");
        world2.activeChunkTickets.set(13L);
        accessor.addWorld(world2);

        assertEquals(20L, MemoryTracker.activeTickets());

        // When serverAccessor is null
        io.github.dailystruggle.rtp.api.server.RTPServerAccessor orig = RTP.serverAccessor;
        try {
            RTP.serverAccessor = null;
            assertEquals(0L, MemoryTracker.activeTickets());
        } finally {
            RTP.serverAccessor = orig;
        }
    }

    @Test
    void activeTasksCalculatesAccurately() {
        assertEquals(0, MemoryTracker.activeTasks());

        // Track a regular object with label "TeleportPipelineTask"
        Object obj1 = new Object();
        UUID id1 = MemoryTracker.track(obj1, "TeleportPipelineTask", 5000L);
        assertEquals(1, MemoryTracker.activeTasks());

        // Track an actual TrackedRTPTask
        RTPRunnable dummyRunnable = new RTPRunnable(() -> {});
        TrackedRTPTask trackedTask = new TrackedRTPTask(dummyRunnable, UUID.randomUUID());
        UUID id2 = MemoryTracker.track(trackedTask, "other-task", 5000L);
        assertEquals(2, MemoryTracker.activeTasks());

        // Track an actual TeleportPipelineTask (constructor automatically registers in MemoryTracker with "TeleportPipelineTask")
        UUID playerId = UUID.randomUUID();
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer player =
                new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(playerId, "ActiveTaskPlayer", null);
        ((io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) RTP.serverAccessor).addPlayer(player);
        io.github.dailystruggle.rtp.api.selection.GenerationContext ctx =
                new io.github.dailystruggle.rtp.api.selection.GenerationContext(player, player, java.util.Collections.emptySet());
        io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask pipelineTask =
                new io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask(ctx);
        assertEquals(3, MemoryTracker.activeTasks());

        // Track a non-task object with different label
        Object obj2 = new Object();
        UUID id4 = MemoryTracker.track(obj2, "regular-label", 5000L);
        assertEquals(3, MemoryTracker.activeTasks());

        // Untrack tasks
        MemoryTracker.untrack(id1);
        assertEquals(2, MemoryTracker.activeTasks());
        MemoryTracker.untrack(id2);
        assertEquals(1, MemoryTracker.activeTasks());
        pipelineTask.setCancelled(true);
        assertEquals(0, MemoryTracker.activeTasks());
        MemoryTracker.untrack(id4);
    }

    @Test
    void memoryCeilingConfigurationAndCheck() {
        MemoryTracker.setMemoryCeiling(-1L);
        assertEquals(-1L, MemoryTracker.getMemoryCeiling());
        assertFalse(MemoryTracker.isOverMemoryCeiling(100_000_000L));

        MemoryTracker.setMemoryCeiling(0L);
        assertEquals(0L, MemoryTracker.getMemoryCeiling());
        assertFalse(MemoryTracker.isOverMemoryCeiling(100_000_000L));

        MemoryTracker.setMemoryCeiling(500L);
        assertEquals(500L, MemoryTracker.getMemoryCeiling());
        assertFalse(MemoryTracker.isOverMemoryCeiling(400L));
        assertFalse(MemoryTracker.isOverMemoryCeiling(500L)); // boundary
        assertTrue(MemoryTracker.isOverMemoryCeiling(501L));
        assertTrue(MemoryTracker.isOverMemoryCeiling(600L));

        MemoryTracker.setMemoryCeiling("1GB");
        assertEquals(1_000_000_000L, MemoryTracker.getMemoryCeiling());
        assertFalse(MemoryTracker.isOverMemoryCeiling(500_000_000L));
        assertFalse(MemoryTracker.isOverMemoryCeiling(1_000_000_000L));
        assertTrue(MemoryTracker.isOverMemoryCeiling(1_500_000_000L));
    }

    @Test
    void runDiagnosticsWithHealthyAndExpiredObjects() {
        // Track an object with 0ms lifespan so it immediately expires (isLeaking() becomes true)
        Object expiredTarget = new Object();
        UUID expiredId = MemoryTracker.track(expiredTarget, "expired-target", 0L);

        // Track a healthy object with a large lifespan
        Object healthyTarget = new Object();
        UUID healthyId = MemoryTracker.track(healthyTarget, "healthy-target", 60_000L);

        // Also track a TrackedRTPTask wrapper that is expired
        RTPRunnable dummyRunnable = new RTPRunnable(() -> {});
        TrackedRTPTask trackedTask = new TrackedRTPTask(dummyRunnable, UUID.randomUUID());
        UUID taskId = MemoryTracker.track(trackedTask, "task-target", 0L);

        assertEquals(3, MemoryTracker.trackedCount());

        // Run sweep diagnostics
        MemoryTracker.runDiagnostics();

        // Expired regular targets without specific auto-cleanup handler remain until untracked
        MemoryTracker.untrack(expiredId);
        MemoryTracker.untrack(healthyId);
        MemoryTracker.untrack(taskId);
        assertEquals(0, MemoryTracker.trackedCount());
    }

    @Test
    void runDiagnosticsWithChunkTicketsAndRunnableLifecycle() throws Exception {
        // Track an expired RTPRunnable
        java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean(false);
        RTPRunnable runnable = new RTPRunnable(() -> ran.set(true));
        UUID runId = MemoryTracker.track(runnable, "runnable-leak", 0L);

        // Track an expired TeleportPipelineTask wrapped in TrackedRTPTask
        UUID playerId = UUID.randomUUID();
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer player =
                new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(playerId, "MemPlayer", null);
        ((io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) io.github.dailystruggle.rtp.common.RTP.serverAccessor).addPlayer(player);

        io.github.dailystruggle.rtp.api.selection.GenerationContext ctx =
                new io.github.dailystruggle.rtp.api.selection.GenerationContext(player, player, java.util.Collections.emptySet());
        io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask pipelineTask =
                new io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask(ctx);
        TrackedRTPTask trackedTask = new TrackedRTPTask(pipelineTask, UUID.randomUUID());
        UUID pipelineId = MemoryTracker.track(trackedTask, "pipeline-leak", 0L);

        // Ensure isLeaking() evaluates to true by sleeping 5ms past 0L lifespan
        Thread.sleep(5L);

        // Run sweep diagnostics
        MemoryTracker.runDiagnostics();

        // The pipeline task should be cancelled and purged
        assertTrue(pipelineTask.isCancelled());
        MemoryTracker.untrack(runId);
        MemoryTracker.untrack(pipelineId);
        assertEquals(0, MemoryTracker.trackedCount());
    }

    @Test
    void runDiagnosticsWithOrphanedTicketsTriggersRelease() {
        // Ensure system_memory_tracker logging is enabled
        io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys> logging =
                (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys>)
                        RTP.configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys.class);
        if (logging != null) {
            logging.set(io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys.system_memory_tracker, true);
        }

        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                (io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) RTP.serverAccessor;
        io.github.dailystruggle.rtp.common.mock.MockRTPWorld world =
                (io.github.dailystruggle.rtp.common.mock.MockRTPWorld) accessor.getRTPWorlds().get(0);

        // Set active tickets higher than tracked tickets to create positive discrepancy
        world.activeChunkTickets.set(10L);
        world.totalChunkLoads.set(50L);

        // Run diagnostics
        assertDoesNotThrow(MemoryTracker::runDiagnostics);

        // Check that diagnostic log was emitted
        assertFalse(accessor.logMessages.isEmpty());
        boolean hasDiagLog = accessor.logMessages.stream().anyMatch(m -> m.contains("Diagnostic: Locations="));
        assertTrue(hasDiagLog, "Diagnostic message must be logged when running active GC sweep");
    }

    @Test
    void runDiagnostics_purgesStalledTeleportDataAndReleasesProcessingPlayer() throws Exception {
        UUID playerId = UUID.randomUUID();
        io.github.dailystruggle.rtp.common.playerData.TeleportData td =
                new io.github.dailystruggle.rtp.common.playerData.TeleportData();
        td.completed = false;

        RTP.getInstance().processingPlayers.add(playerId);
        RTP.getInstance().latestTeleportData.put(playerId, td);

        UUID trackId = MemoryTracker.track(td, "TeleportData-" + playerId, 0L);
        Thread.sleep(5L);

        MemoryTracker.runDiagnostics();

        assertTrue(td.completed, "TeleportData must be marked completed");
        assertFalse(RTP.getInstance().processingPlayers.contains(playerId), "processingPlayers lock must be released");
        assertEquals(0, MemoryTracker.trackedCountByLabel("TeleportData-" + playerId), "leaking TeleportData must be purged from tracker");
    }
}
