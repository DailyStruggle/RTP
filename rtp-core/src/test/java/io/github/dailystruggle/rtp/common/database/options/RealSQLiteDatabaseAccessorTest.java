package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Drives the real {@link SQLiteDatabaseAccessor} against an on-disk SQLite database
 * created under a JUnit {@code @TempDir}, so coverage credits the shipped class rather
 * than a copied test subclass (ENTERPRISE_READINESS.md item 18).
 */
class RealSQLiteDatabaseAccessorTest {

    @TempDir
    Path tempDir;

    private SQLiteDatabaseAccessor db;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        // The url argument is ignored; the accessor derives its path from the plugin dir.
        db = new SQLiteDatabaseAccessor("unused");
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.close();
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
        io.github.dailystruggle.rtp.common.RTP.serverAccessor = null;
        io.github.dailystruggle.rtp.common.RTP.scheduler = null;
    }

    private static Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> cachedLocationRow(String uuid,
                                                                                               String region,
                                                                                               String playerUuid,
                                                                                               long timestamp) {
        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> row = new LinkedHashMap<>();
        row.put(new DatabaseAccessor.TableObj("UUID"), new DatabaseAccessor.TableObj(uuid));
        row.put(new DatabaseAccessor.TableObj("world"), new DatabaseAccessor.TableObj("world"));
        row.put(new DatabaseAccessor.TableObj("x"), new DatabaseAccessor.TableObj(10));
        row.put(new DatabaseAccessor.TableObj("y"), new DatabaseAccessor.TableObj(64));
        row.put(new DatabaseAccessor.TableObj("z"), new DatabaseAccessor.TableObj(-5));
        row.put(new DatabaseAccessor.TableObj("attempts"), new DatabaseAccessor.TableObj(2));
        row.put(new DatabaseAccessor.TableObj("region"), new DatabaseAccessor.TableObj(region));
        row.put(new DatabaseAccessor.TableObj("player_uuid"), new DatabaseAccessor.TableObj(playerUuid));
        row.put(new DatabaseAccessor.TableObj("timestamp"), new DatabaseAccessor.TableObj(timestamp));
        row.put(new DatabaseAccessor.TableObj("seed"), new DatabaseAccessor.TableObj(99L));
        return row;
    }

    // -------------------------------------------------------------------------
    // construction / identity
    // -------------------------------------------------------------------------

    @Test
    void name_isJdbcSqliteUrl() {
        assertTrue(db.name().startsWith("jdbc:sqlite:"), db.name());
    }

    @Test
    void getConnection_reusesLiveConnection() throws Exception {
        Connection a = db.getConnection();
        Connection b = db.getConnection();
        assertSame(a, b);
        assertFalse(a.isClosed());
    }

    @Test
    void asDataSource_delegatesToGetConnection() throws Exception {
        javax.sql.DataSource ds = db.asDataSource();
        assertNotNull(ds.getConnection());
        assertNotNull(ds.getConnection("u", "p"));
    }

    // -------------------------------------------------------------------------
    // write / read round-trip
    // -------------------------------------------------------------------------

    @Test
    void write_thenRead_returnsRow() throws Exception {
        Connection conn = db.getConnection();
        db.write(conn, "rtp_cached_locations", cachedLocationRow("loc-1", "default", "shared", 1L));

        Optional<Map<String, Object>> read = db.read(conn, "rtp_cached_locations",
                new AbstractMap.SimpleEntry<>("UUID", "loc-1"));
        assertTrue(read.isPresent());
        assertEquals("world", read.get().get("world"));
    }

    @Test
    void read_missingRow_returnsPresentButEmpty() throws Exception {
        // SQLite's read still returns a (possibly empty) map when the table exists.
        Optional<Map<String, Object>> read = db.read(db.getConnection(), "rtp_cached_locations",
                new AbstractMap.SimpleEntry<>("UUID", "nope"));
        assertTrue(read.isPresent());
        assertTrue(read.get().isEmpty());
    }

    @Test
    void write_nullPairs_throwsIllegalState() throws Exception {
        Connection conn = db.getConnection();
        assertThrows(IllegalStateException.class, () -> db.write(conn, "rtp_cached_locations", null));
    }

    @Test
    void write_emptyPairs_throwsIllegalState() throws Exception {
        Connection conn = db.getConnection();
        assertThrows(IllegalStateException.class,
                () -> db.write(conn, "rtp_cached_locations", new LinkedHashMap<>()));
    }

    @Test
    void write_missingTable_autoCreatesAndInserts() throws Exception {
        Connection conn = db.getConnection();
        // SQLite's write auto-creates the table (and a UNIQUE index for UUID) on demand.
        assertDoesNotThrow(() ->
                db.write(conn, "adhoc_table", cachedLocationRow("loc-new", "r", "shared", 1L)));

        Optional<Map<String, Object>> read = db.read(conn, "adhoc_table",
                new AbstractMap.SimpleEntry<>("UUID", "loc-new"));
        assertTrue(read.isPresent());
        assertFalse(read.get().isEmpty());
    }

    @Test
    void write_addsMissingColumnOnSecondSchema() throws Exception {
        Connection conn = db.getConnection();
        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> first = new LinkedHashMap<>();
        first.put(new DatabaseAccessor.TableObj("UUID"), new DatabaseAccessor.TableObj("k1"));
        first.put(new DatabaseAccessor.TableObj("a"), new DatabaseAccessor.TableObj("v1"));
        db.write(conn, "evolving", first);

        // A later write carrying a new column must ALTER TABLE ADD it.
        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> second = new LinkedHashMap<>();
        second.put(new DatabaseAccessor.TableObj("UUID"), new DatabaseAccessor.TableObj("k2"));
        second.put(new DatabaseAccessor.TableObj("a"), new DatabaseAccessor.TableObj("v2"));
        second.put(new DatabaseAccessor.TableObj("b"), new DatabaseAccessor.TableObj("v3"));
        assertDoesNotThrow(() -> db.write(conn, "evolving", second));

        Optional<Map<String, Object>> read = db.read(conn, "evolving",
                new AbstractMap.SimpleEntry<>("UUID", "k2"));
        assertTrue(read.isPresent());
        assertEquals("v3", read.get().get("b"));
    }

    // -------------------------------------------------------------------------
    // delete / load / purge / clear
    // -------------------------------------------------------------------------

    @Test
    void write_thenDelete_rowGone() throws Exception {
        Connection conn = db.getConnection();
        db.write(conn, "rtp_cached_locations", cachedLocationRow("loc-del", "default", "shared", 1L));
        db.delete(conn, "rtp_cached_locations", new AbstractMap.SimpleEntry<>("UUID", "loc-del"));

        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("default");
        assertTrue(locs.stream().noneMatch(l -> "loc-del".equals(l.getId())));
    }

    @Test
    void loadCachedLocations_sharedRow_hasNullPlayerId() throws Exception {
        Connection conn = db.getConnection();
        db.write(conn, "rtp_cached_locations", cachedLocationRow("loc-shared", "spawn", "shared", 1L));

        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("spawn");
        assertEquals(1, locs.size());
        assertEquals("loc-shared", locs.get(0).getId());
        assertNull(locs.get(0).getPlayerId());
    }

    @Test
    void loadCachedLocations_playerBoundRow_parsesPlayerId() throws Exception {
        Connection conn = db.getConnection();
        UUID player = UUID.randomUUID();
        db.write(conn, "rtp_cached_locations",
                cachedLocationRow("loc-p", "personal", player.toString(), System.currentTimeMillis()));

        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("personal");
        assertEquals(1, locs.size());
        assertEquals(player, locs.get(0).getPlayerId());
    }

    @Test
    void clearAllCachedLocations_removesEverything() throws Exception {
        Connection conn = db.getConnection();
        db.write(conn, "rtp_cached_locations", cachedLocationRow("loc-a", "r", "shared", 1L));
        db.clearAllCachedLocations();
        assertTrue(db.loadCachedLocations("r").isEmpty());
    }

    @Test
    void purgeStaleLocations_dropsExpiredPlayerBoundRows() throws Exception {
        Connection conn = db.getConnection();
        UUID player = UUID.randomUUID();
        db.write(conn, "rtp_cached_locations", cachedLocationRow("loc-stale", "r", player.toString(), 1L));
        db.write(conn, "rtp_cached_locations",
                cachedLocationRow("loc-keep", "r", "shared", System.currentTimeMillis()));

        db.purgeStaleLocations();

        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("r");
        assertEquals(1, locs.size());
        assertEquals("loc-keep", locs.get(0).getId());
    }

    // -------------------------------------------------------------------------
    // flush + startup
    // -------------------------------------------------------------------------

    private TeleportData teleportData() {
        TeleportData data = new TeleportData();
        data.completed = true;
        data.time = 123L;
        data.selectedCoords = new RTPCoords("world", 1, 2, 3);
        data.originalCoords = new RTPCoords("world", 4, 5, 6);
        data.cost = 0.0;
        Region region = mock(Region.class);
        region.name = "default";
        data.targetRegion = region;
        return data;
    }

    @Test
    void flush_emptyQueue_noOp() {
        assertDoesNotThrow(db::flush);
    }

    @Test
    void flush_withData_commitsBatch() {
        db.cacheValue(teleportData());
        assertDoesNotThrow(db::flush);
    }

    @Test
    void flush_missingTable_rollsBackWithoutThrowing() throws Exception {
        Connection conn = db.getConnection();
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE rtp_teleport_data");
        }
        db.cacheValue(teleportData());
        assertDoesNotThrow(db::flush);
    }

    @Test
    void startup_withoutReferenceData_returnsEarly() {
        // referenceData table is absent, so startup short-circuits without error.
        assertDoesNotThrow(db::startup);
    }

    // -------------------------------------------------------------------------
    // connect / disconnect / close
    // -------------------------------------------------------------------------

    @Test
    void connect_returnsUsableConnection() {
        assertNotNull(db.connect());
    }

    @Test
    void disconnect_isSharedNoOp() throws Exception {
        Connection conn = db.getConnection();
        db.disconnect(conn);
        assertFalse(conn.isClosed());
    }

    @Test
    void close_closesConnection() throws Exception {
        Connection conn = db.getConnection();
        db.close();
        assertTrue(conn.isClosed());
        assertDoesNotThrow(db::close);
        db = null;
    }
}
