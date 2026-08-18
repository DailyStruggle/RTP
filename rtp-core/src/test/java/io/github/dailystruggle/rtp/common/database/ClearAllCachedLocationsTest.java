package io.github.dailystruggle.rtp.common.database;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for stale cached locations persisting across restarts.
 * Verifies {@link DatabaseAccessor#clearAllCachedLocations()} and
 * {@link DatabaseAccessor#rebuildCachedLocationsFromMemory()}.
 */
class ClearAllCachedLocationsTest {

    @TempDir
    Path tempDir;

    private H2DatabaseAccessorTest.TestH2Accessor db;

    @BeforeEach
    void setUp() throws Exception {
        RTPTestSetup.install(tempDir.toFile());
        String url = "jdbc:h2:mem:rtp_clearall_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
        db = new H2DatabaseAccessorTest.TestH2Accessor(url);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) db.close();
    }

    @Test
    @DisplayName("clearAllCachedLocations removes every persisted row (stale-row protection)")
    void clearAllCachedLocations_wipesTable() {
        db.saveCachedLocation("default", new RTPLocation(new RTPCoords("world", 10, 64, 10), 1L, null), null);
        db.saveCachedLocation("default", new RTPLocation(new RTPCoords("world", 20, 64, 20), 1L, null), null);
        db.saveCachedLocation("other",   new RTPLocation(new RTPCoords("world", 30, 64, 30), 1L, null), null);
        db.flushDirtyCache();
        db.processQueries(Long.MAX_VALUE);

        assertEquals(2, db.loadCachedLocations("default").size(), "pre-wipe sanity: default has two rows");
        assertEquals(1, db.loadCachedLocations("other").size(),   "pre-wipe sanity: other has one row");

        db.clearAllCachedLocations();

        assertTrue(db.loadCachedLocations("default").isEmpty(),
                "clearAllCachedLocations must erase every row (default region)");
        assertTrue(db.loadCachedLocations("other").isEmpty(),
                "clearAllCachedLocations must erase every row (other region)");
    }

    @Test
    @DisplayName("rebuildCachedLocationsFromMemory is a safe no-op when no regions are registered")
    void rebuildCachedLocations_noOp_whenNoRegions() {
        db.saveCachedLocation("default", new RTPLocation(new RTPCoords("world", 7, 64, 7), 1L, null), null);
        db.flushDirtyCache();
        db.processQueries(Long.MAX_VALUE);
        assertEquals(1, db.loadCachedLocations("default").size());

        // RTPTestSetup.install leaves permRegionLookup/tempRegions empty - the
        // rebuild must NOT wipe the table in that case, so direct unit-test
        // calls to flushDirtyCache retain their original behaviour.
        assertTrue(RTP.selectionAPI == null
                        || (RTP.selectionAPI.permRegionLookup.isEmpty()
                            && RTP.selectionAPI.tempRegions.isEmpty()),
                "precondition: no regions registered in test harness");

        db.rebuildCachedLocationsFromMemory();
        db.processQueries(Long.MAX_VALUE);

        assertEquals(1, db.loadCachedLocations("default").size(),
                "rebuild must leave the table untouched when no regions are registered");
    }
}
