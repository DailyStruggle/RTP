package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that bad location data written by {@link MemoryShape#save} during a
 * simulated shutdown sequence can be read back by {@link MemoryShape#load} on
 * the next startup, and that {@link MemoryShape#isKnownBad} correctly reflects
 * the persisted data.
 *
 * <p>This test exercises the ordering fix in {@code RTP.stop()} that moves
 * {@code databaseAccessor.stop.set(true)} to <em>after</em> the region
 * shutdown loops, ensuring that file-write requests enqueued by
 * {@code shape.save()} are not silently dropped when
 * {@code processQueries(Long.MAX_VALUE)} is called during {@code onDisable()}.
 */
class MemoryShapeShutdownTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
    }

    /**
     * Injects a real {@link YamlFileDatabase} (which handles binary file I/O
     * via the shared {@code fileWriteQueue} / {@code fileReadQueue}) into the
     * RTP singleton so that {@code save()} and {@code load()} can actually
     * write and read files.
     */
    private YamlFileDatabase wireDatabaseAccessor() throws Exception {
        YamlFileDatabase db = new YamlFileDatabase(tempDir.toFile());
        Field daField = RTP.class.getDeclaredField("databaseAccessor");
        daField.setAccessible(true);
        daField.set(RTP.getInstance(), db);
        return db;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Flush all pending file writes then all pending file reads. */
    private static void flushIO(YamlFileDatabase db) {
        db.processQueries(Long.MAX_VALUE);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void shutdown_savesAndLoads_badLocations() throws Exception {
        YamlFileDatabase db = wireDatabaseAccessor();

        // --- Phase 1: simulate a running server that marks locations as bad ---
        Circle shape = new Circle();
        long loc1 = 100L;
        long loc2 = 200L;
        long loc3 = 500L;

        shape.addBadLocation(loc1);
        shape.addBadLocation(loc2);
        shape.addBadLocation(loc3);

        // Flush pending bad locations into the prefix-sum caches (as ScanTask does)
        shape.flushAndRebuild(shape.spatialResolution);

        // Simulate shutdown: save() enqueues a binary file write
        shape.save("shutdown_test", "test_world");

        // Simulate the correct shutdown ordering: processQueries runs BEFORE stop is set
        // (this is the fix — stop must NOT be true yet when we flush writes)
        assertFalse(db.stop.get(), "stop must be false when flushing writes on shutdown");
        flushIO(db);

        // --- Phase 2: simulate a fresh server startup loading the saved data ---
        Circle freshShape = new Circle();

        // load() enqueues a binary file read; the future completes when processQueries runs
        java.util.concurrent.CompletableFuture<Void> loadFuture =
                freshShape.load("shutdown_test", "test_world");

        flushIO(db);

        // Wait for the load future to complete (it should already be done after flushIO)
        loadFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);

        // --- Verify: all three bad locations must be recognised after reload ---
        assertTrue(freshShape.isKnownBad(loc1),
                "loc1 should be known bad after reload");
        assertTrue(freshShape.isKnownBad(loc2),
                "loc2 should be known bad after reload");
        assertTrue(freshShape.isKnownBad(loc3),
                "loc3 should be known bad after reload");
    }

    @Test
    void shutdown_withStopSetBeforeFlush_stillSavesFileData() throws Exception {
        // After the fix: stop=true no longer blocks file writes in processQueries,
        // so data enqueued before stop is set must still be written to disk.
        YamlFileDatabase db = wireDatabaseAccessor();

        Circle shape = new Circle();
        long loc = 99L;
        shape.addBadLocation(loc);
        shape.flushAndRebuild(shape.spatialResolution);
        shape.save("shutdown_stop_before_test", "test_world");

        // Set stop=true BEFORE flushing (simulates the old broken ordering)
        db.stop.set(true);
        flushIO(db); // with the fix, file writes still drain even when stop=true

        db.stop.set(false); // reset so load can proceed

        Circle freshShape = new Circle();
        java.util.concurrent.CompletableFuture<Void> loadFuture =
                freshShape.load("shutdown_stop_before_test", "test_world");
        flushIO(db);
        loadFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertTrue(freshShape.isKnownBad(loc),
                "file writes must complete even when stop=true (fix: stop does not gate file I/O)");
    }

    @Test
    void shutdown_withStopSetAfterFlush_savesData() throws Exception {
        YamlFileDatabase db = wireDatabaseAccessor();

        Circle shape = new Circle();
        long loc = 42L;
        shape.addBadLocation(loc);
        shape.flushAndRebuild(shape.spatialResolution);
        shape.save("shutdown_race_test", "test_world");

        // Simulate the correct shutdown ordering: stop is set AFTER processQueries runs
        // (this is the fix in RTP.stop() — stop must NOT be true yet when we flush writes)
        flushIO(db); // write completes because stop is still false
        db.stop.set(true);

        // Reset stop so load can proceed
        db.stop.set(false);

        Circle freshShape = new Circle();
        java.util.concurrent.CompletableFuture<Void> loadFuture =
                freshShape.load("shutdown_race_test", "test_world");
        flushIO(db);
        loadFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);

        // The data was written before stop was set, so the location MUST be known bad
        assertTrue(freshShape.isKnownBad(loc),
                "data should be saved when stop is set after flush (verifies the fix)");
    }
}
