package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MySQLContainer;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Drives the real {@link MySQLDatabaseAccessor} against a Testcontainers MySQL server
 * so coverage credits the shipped class rather than a copied {@code Testable*}
 * subclass (ENTERPRISE_READINESS.md item 18). Docker-gated via
 * {@link SqlTestContainers#dockerAvailable()}: a build without a Docker daemon skips
 * the whole class cleanly.
 *
 * <p>The container is shared across every test in the package, so each test uses a
 * distinct region / UUID namespace to stay isolated from its neighbours.</p>
 */
@EnabledIf("io.github.dailystruggle.rtp.common.database.options.SqlTestContainers#dockerAvailable")
class RealMySQLDatabaseAccessorTest {

    @TempDir
    Path tempDir;

    private MySQLDatabaseAccessor db;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        MySQLContainer<?> c = SqlTestContainers.mysql();
        db = new MySQLDatabaseAccessor(c.getHost(), c.getFirstMappedPort(),
                c.getDatabaseName(), c.getUsername(), c.getPassword());
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

    // -------------------------------------------------------------------------
    // construction / identity
    // -------------------------------------------------------------------------

    @Test
    void name_isJdbcMysqlUrl() {
        assertTrue(db.name().startsWith("jdbc:mysql://"), db.name());
    }

    @Test
    void getConnection_returnsUsableConnection() throws Exception {
        Connection conn = db.getConnection();
        assertNotNull(conn);
        assertFalse(conn.isClosed());
        conn.close();
    }

    @Test
    void asDataSource_delegatesToGetConnection() throws Exception {
        javax.sql.DataSource ds = db.asDataSource();
        try (Connection a = ds.getConnection(); Connection b = ds.getConnection("u", "p")) {
            assertNotNull(a);
            assertNotNull(b);
        }
    }

    // -------------------------------------------------------------------------
    // write / read round-trip
    // -------------------------------------------------------------------------

    @Test
    void write_thenRead_returnsRow() throws Exception {
        Connection conn = db.getConnection();
        db.write(conn, "rtp_cached_locations", cachedLocationRow("mysql-1", "mysqlrt", "shared", 1L));

        Optional<Map<String, Object>> read = db.read(conn, "rtp_cached_locations",
                new AbstractMap.SimpleEntry<>("UUID", "mysql-1"));
        assertTrue(read.isPresent());
        assertEquals("world", read.get().get("world"));
        conn.close();
    }

    @Test
    void read_missingRow_returnsEmpty() throws Exception {
        try (Connection conn = db.getConnection()) {
            Optional<Map<String, Object>> read = db.read(conn, "rtp_cached_locations",
                    new AbstractMap.SimpleEntry<>("UUID", "mysql-nope"));
            assertFalse(read.isPresent());
        }
    }

    @Test
    void write_nullPairs_throwsIllegalState() throws Exception {
        try (Connection conn = db.getConnection()) {
            assertThrows(IllegalStateException.class, () -> db.write(conn, "rtp_cached_locations", null));
        }
    }

    @Test
    void write_emptyPairs_throwsIllegalState() throws Exception {
        try (Connection conn = db.getConnection()) {
            assertThrows(IllegalStateException.class,
                    () -> db.write(conn, "rtp_cached_locations", new LinkedHashMap<>()));
        }
    }

    @Test
    void write_missingTable_isSwallowed() throws Exception {
        try (Connection conn = db.getConnection()) {
            assertDoesNotThrow(() ->
                    db.write(conn, "no_such_table", cachedLocationRow("mysql-x", "r", "shared", 1L)));
        }
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    @Test
    void write_thenDelete_rowGone() throws Exception {
        try (Connection conn = db.getConnection()) {
            db.write(conn, "rtp_cached_locations", cachedLocationRow("mysql-del", "mysqldel", "shared", 1L));
            db.delete(conn, "rtp_cached_locations", new AbstractMap.SimpleEntry<>("UUID", "mysql-del"));

            Optional<Map<String, Object>> read = db.read(conn, "rtp_cached_locations",
                    new AbstractMap.SimpleEntry<>("UUID", "mysql-del"));
            assertFalse(read.isPresent());
        }
    }

    // -------------------------------------------------------------------------
    // loadCachedLocations
    // -------------------------------------------------------------------------

    @Test
    void loadCachedLocations_emptyRegion_returnsEmpty() {
        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("mysql-empty-region");
        assertNotNull(locs);
        assertTrue(locs.isEmpty());
    }

    @Test
    void loadCachedLocations_sharedRow_hasNullPlayerId() throws Exception {
        try (Connection conn = db.getConnection()) {
            db.write(conn, "rtp_cached_locations", cachedLocationRow("mysql-shared", "mysqlshared", "shared", 1L));
        }
        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("mysqlshared");
        assertEquals(1, locs.size());
        DatabaseAccessor.StoredLocation loc = locs.get(0);
        assertEquals("mysql-shared", loc.getId());
        assertEquals("world", loc.getWorldName());
        assertNull(loc.getPlayerId());
    }

    @Test
    void loadCachedLocations_playerBoundRow_parsesPlayerId() throws Exception {
        UUID player = UUID.randomUUID();
        try (Connection conn = db.getConnection()) {
            db.write(conn, "rtp_cached_locations",
                    cachedLocationRow("mysql-p", "mysqlp", player.toString(), System.currentTimeMillis()));
        }
        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("mysqlp");
        assertEquals(1, locs.size());
        assertEquals(player, locs.get(0).getPlayerId());
    }

    @Test
    void loadCachedLocations_invalidPlayerUuid_leavesNullPlayerId() throws Exception {
        try (Connection conn = db.getConnection()) {
            db.write(conn, "rtp_cached_locations",
                    cachedLocationRow("mysql-bad", "mysqlbad", "not-a-uuid", System.currentTimeMillis()));
        }
        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("mysqlbad");
        assertEquals(1, locs.size());
        assertNull(locs.get(0).getPlayerId());
    }

    // -------------------------------------------------------------------------
    // clear / purge
    // -------------------------------------------------------------------------

    @Test
    void clearAllCachedLocations_removesEverything() throws Exception {
        try (Connection conn = db.getConnection()) {
            db.write(conn, "rtp_cached_locations", cachedLocationRow("mysql-c1", "mysqlclear", "shared", 1L));
            db.write(conn, "rtp_cached_locations", cachedLocationRow("mysql-c2", "mysqlclear", "shared", 1L));
        }
        db.clearAllCachedLocations();
        assertTrue(db.loadCachedLocations("mysqlclear").isEmpty());
    }

    @Test
    void purgeStaleLocations_dropsExpiredPlayerBoundRows() throws Exception {
        UUID player = UUID.randomUUID();
        try (Connection conn = db.getConnection()) {
            db.write(conn, "rtp_cached_locations", cachedLocationRow("mysql-stale", "mysqlpurge", player.toString(), 1L));
            db.write(conn, "rtp_cached_locations",
                    cachedLocationRow("mysql-keep", "mysqlpurge", "shared", System.currentTimeMillis()));
        }
        db.purgeStaleLocations();
        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("mysqlpurge");
        assertEquals(1, locs.size());
        assertEquals("mysql-keep", locs.get(0).getId());
    }

    // -------------------------------------------------------------------------
    // flush (batched insert) + startup
    // -------------------------------------------------------------------------

    @Test
    void flush_emptyQueue_noOp() {
        assertDoesNotThrow(db::flush);
    }

    @Test
    void flush_withData_commitsBatch_andStartupReadsBack() {
        db.cacheValue(teleportData());
        assertDoesNotThrow(db::flush);
        assertDoesNotThrow(db::startup);
    }

    @Test
    void getInsertStatement_isInsertIgnore() {
        assertTrue(db.getInsertStatement().toUpperCase().contains("INSERT IGNORE"));
    }

    // -------------------------------------------------------------------------
    // network-state binding
    // -------------------------------------------------------------------------

    @Test
    void networkStateBinding_defaultsNull_andRoundTrips() {
        assertNull(db.getNetworkStateBinding());
        io.github.dailystruggle.rtp.common.network.NetworkStateBinding binding =
                mock(io.github.dailystruggle.rtp.common.network.NetworkStateBinding.class);
        db.setNetworkStateBinding(binding);
        assertSame(binding, db.getNetworkStateBinding());
        db.setNetworkStateBinding(null);
        assertNull(db.getNetworkStateBinding());
    }

    // -------------------------------------------------------------------------
    // connect / disconnect
    // -------------------------------------------------------------------------

    @Test
    void connect_returnsUsableConnection_disconnectCloses() throws Exception {
        Connection conn = db.connect();
        assertNotNull(conn);
        db.disconnect(conn);
        assertTrue(conn.isClosed());
    }
}
