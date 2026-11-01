package io.github.dailystruggle.rtp.common.database;

import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for kept/unkept location cache persistence through public API.
 * Exercises: {@code saveCachedLocation -> cacheValue -> flushDirtyCache -> processQueries -> write}.
 * Verifies recovery via {@code loadCachedLocations}.
 */
class CachedLocationRoundTripTest {

    @TempDir
    Path tempDir;

    private H2DatabaseAccessorTest.TestH2Accessor db;

    @BeforeEach
    void setUp() throws Exception {
        RTPTestSetup.install(tempDir.toFile());
        String url = "jdbc:h2:mem:rtp_roundtrip_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
        db = new H2DatabaseAccessorTest.TestH2Accessor(url);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) db.close();
    }

    @Test
    @DisplayName("saveCachedLocation persists through cacheValue/flush/processQueries and is recovered by loadCachedLocations")
    void saveCachedLocation_roundTripsThroughPublicApi() {
        RTPLocation loc = new RTPLocation(new RTPCoords("world", 100, 64, -200), 3L, null);

        db.saveCachedLocation("default", loc, null);

        // Drain dirtyCache -> writeQueue (runs synchronously in tests because
        // getTable returns a completed future).
        db.flushDirtyCache();

        // Drain writeQueue -> real SQL INSERT. Give it effectively unbounded time.
        db.processQueries(Long.MAX_VALUE);

        List<DatabaseAccessor.StoredLocation> loaded = db.loadCachedLocations("default");
        assertNotNull(loaded, "loadCachedLocations must not return null");
        assertEquals(1, loaded.size(),
                "exactly one row should be recovered — if zero, the save chain is broken "
                        + "(likely the saveCachedLocation -> cacheValue primary-key inference regression)");

        DatabaseAccessor.StoredLocation s = loaded.get(0);
        assertEquals("world", s.getWorldName());
        assertEquals(100, s.getX());
        assertEquals(64, s.getY());
        assertEquals(-200, s.getZ());
        assertEquals(3, s.getAttempts());
        assertEquals("default", s.getRegionName());
    }

    @Test
    @DisplayName("multiple saveCachedLocation calls all round-trip (unkept+kept unified persistence)")
    void multipleSaves_allRoundTrip() {
        // Simulates the post-fix behaviour where BOTH the kept and unkept queues
        // persist via the same save callback. Order does not matter - hydrate shuffles.
        db.saveCachedLocation("default", new RTPLocation(new RTPCoords("world", 10, 64, 10), 1L, null), null);
        db.saveCachedLocation("default", new RTPLocation(new RTPCoords("world", 20, 64, 20), 1L, null), null);
        db.saveCachedLocation("default", new RTPLocation(new RTPCoords("world", 30, 64, 30), 1L, null), null);

        db.flushDirtyCache();
        db.processQueries(Long.MAX_VALUE);

        List<DatabaseAccessor.StoredLocation> loaded = db.loadCachedLocations("default");
        assertEquals(3, loaded.size(), "all three rows should round-trip");
        assertTrue(loaded.stream().anyMatch(s -> s.getX() == 10 && s.getZ() == 10));
        assertTrue(loaded.stream().anyMatch(s -> s.getX() == 20 && s.getZ() == 20));
        assertTrue(loaded.stream().anyMatch(s -> s.getX() == 30 && s.getZ() == 30));
    }

    @Test
    @DisplayName("deleteCachedLocation removes the row after round-trip")
    void deleteCachedLocation_removesRow() {
        RTPLocation loc = new RTPLocation(new RTPCoords("world", 7, 64, 7), 1L, null);
        db.saveCachedLocation("default", loc, null);
        db.flushDirtyCache();
        db.processQueries(Long.MAX_VALUE);

        assertEquals(1, db.loadCachedLocations("default").size());

        db.deleteCachedLocation("default", loc);
        db.processQueries(Long.MAX_VALUE);

        assertTrue(db.loadCachedLocations("default").isEmpty(),
                "row should be gone after deleteCachedLocation + processQueries");
    }
}
