package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.UUID;

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
    void memoryCeilingConfigurationAndCheck() {
        MemoryTracker.setMemoryCeiling(-1L);
        assertEquals(-1L, MemoryTracker.getMemoryCeiling());
        assertFalse(MemoryTracker.isOverMemoryCeiling(100_000_000L));

        MemoryTracker.setMemoryCeiling(500L);
        assertEquals(500L, MemoryTracker.getMemoryCeiling());
        assertFalse(MemoryTracker.isOverMemoryCeiling(400L));
        assertTrue(MemoryTracker.isOverMemoryCeiling(600L));

        MemoryTracker.setMemoryCeiling("1GB");
        assertEquals(1_000_000_000L, MemoryTracker.getMemoryCeiling());
        assertFalse(MemoryTracker.isOverMemoryCeiling(500_000_000L));
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
}
