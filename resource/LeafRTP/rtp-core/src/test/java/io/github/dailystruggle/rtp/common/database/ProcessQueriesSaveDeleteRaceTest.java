package io.github.dailystruggle.rtp.common.database;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for dirtyCache/deleteQueue race condition causing ghost rows in database.
 */
class ProcessQueriesSaveDeleteRaceTest {

    @TempDir
    Path tempDir;

    private H2DatabaseAccessorTest.TestH2Accessor db;

    @BeforeEach
    void setUp() throws Exception {
        RTPTestSetup.install(tempDir.toFile());
        String url = "jdbc:h2:mem:rtp_savedelete_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
        db = new H2DatabaseAccessorTest.TestH2Accessor(url);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) db.close();
    }

    @Test
    @DisplayName("save+poll within the same flush window must not leave a ghost row")
    void saveThenDelete_beforeDirtyCacheFlush_leavesNoGhostRow() {
        RTPLocation loc = new RTPLocation(new RTPCoords("world", 1, 64, 1), 1L, null);

        // Reproduce exactly what RegionQueueManager's save/delete callbacks do
        // when a location is offered and then polled within a single per-tick
        // flush window:
        //   1. save callback fires  -> saveCachedLocation -> dirtyCache.put
        //   2. delete callback fires -> deleteCachedLocation -> deleteQueue.add
        // No flushDirtyCache has run yet (it only fires every 6000 ticks).
        db.saveCachedLocation("default", loc, null);
        db.deleteCachedLocation("default", loc);

        // Now the per-tick flush task runs (AbstractSQLDatabaseAccessor.flush ->
        // processQueries). It drains the writeQueue (empty) and the deleteQueue
        // (one entry). The delete is a no-op because the row was never written.
        db.processQueries(Long.MAX_VALUE);

        // Some time later the 5-minute rebuild cycle finally promotes
        // dirtyCache onto the writeQueue and processQueries flushes it.
        db.flushDirtyCache();
        db.processQueries(Long.MAX_VALUE);

        List<DatabaseAccessor.StoredLocation> remaining = db.loadCachedLocations("default");
        assertTrue(remaining.isEmpty(),
                "A location that was saved and then immediately deleted must not survive in the DB. "
                        + "Found " + remaining.size() + " ghost row(s) — the dirtyCache/deleteQueue "
                        + "ordering inversion is what produces the impossible 'Successfully loaded N "
                        + "locations' banner on the next boot.");
    }

    @Test
    @DisplayName("many save+delete pairs across several flush windows must net to zero rows")
    void manyPairs_acrossFlushWindows_netToZero() {
        // Simulate steady-state traffic: ~50 cycles of (offer, poll) interleaved
        // with the per-tick processQueries drain. flushDirtyCache (the 5-minute
        // task) only fires at the end. A correct implementation must end with
        // zero rows because every save was paired 1:1 with a delete.
        int pairs = 50;
        for (int i = 0; i < pairs; i++) {
            RTPLocation loc = new RTPLocation(new RTPCoords("world", i, 64, i), 1L, null);
            db.saveCachedLocation("default", loc, null); // -> dirtyCache
            db.deleteCachedLocation("default", loc);     // -> deleteQueue (no-op vs DB)
            db.processQueries(Long.MAX_VALUE);           // per-tick flush
        }

        db.flushDirtyCache();              // 5-minute rebuild: dirtyCache -> writeQueue
        db.processQueries(Long.MAX_VALUE); // drain everything

        List<DatabaseAccessor.StoredLocation> remaining = db.loadCachedLocations("default");
        assertEquals(0, remaining.size(),
                "Every saveCachedLocation was paired 1:1 with deleteCachedLocation. "
                        + "The table must end empty regardless of which queue was drained first.");
    }
}
