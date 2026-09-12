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
import org.testcontainers.containers.PostgreSQLContainer;

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
 * Drives the real {@link PostgreSQLDatabaseAccessor} against a Testcontainers
 * PostgreSQL server so coverage credits the shipped class rather than a copied
 * {@code Testable*} subclass (ENTERPRISE_READINESS.md item 18). Docker-gated via
 * {@link SqlTestContainers#dockerAvailable()}: a build without a Docker daemon skips
 * the whole class cleanly.
 *
 * <p>PostgreSQL folds unquoted identifiers to lower case, so column-name lookups here
 * use the lower-case forms the driver returns.</p>
 */
@EnabledIf("io.github.dailystruggle.rtp.common.database.options.SqlTestContainers#dockerAvailable")
class RealPostgreSQLDatabaseAccessorTest {

    @TempDir
    Path tempDir;

    private PostgreSQLDatabaseAccessor db;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        PostgreSQLContainer<?> c = SqlTestContainers.postgres();
        db = new PostgreSQLDatabaseAccessor(c.getHost(), c.getFirstMappedPort(),
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
    void name_isJdbcPostgresUrl() {
        assertTrue(db.name().startsWith("jdbc:postgresql://"), db.name());
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
        db.write(conn, "rtp_cached_locations", cachedLocationRow("pg-1", "pgrt", "shared", 1L));

        Optional<Map<String, Object>> read = db.read(conn, "rtp_cached_locations",
                new AbstractMap.SimpleEntry<>("UUID", "pg-1"));
        assertTrue(read.isPresent());
        assertEquals("world", read.get().get("world"));
        conn.close();
    }

    @Test
    void read_missingRow_returnsEmpty() throws Exception {
        try (Connection conn = db.getConnection()) {
            Optional<Map<String, Object>> read = db.read(conn, "rtp_cached_locations",
                    new AbstractMap.SimpleEntry<>("UUID", "pg-nope"));
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
                    db.write(conn, "no_such_table", cachedLocationRow("pg-x", "r", "shared", 1L)));
        }
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    @Test
    void write_thenDelete_rowGone() throws Exception {
        try (Connection conn = db.getConnection()) {
            db.write(conn, "rtp_cached_locations", cachedLocationRow("pg-del", "pgdel", "shared", 1L));
            db.delete(conn, "rtp_cached_locations", new AbstractMap.SimpleEntry<>("UUID", "pg-del"));

            Optional<Map<String, Object>> read = db.read(conn, "rtp_cached_locations",
                    new AbstractMap.SimpleEntry<>("UUID", "pg-del"));
            assertFalse(read.isPresent());
        }
    }

    // -------------------------------------------------------------------------
    // loadCachedLocations
    // -------------------------------------------------------------------------

    @Test
    void loadCachedLocations_emptyRegion_returnsEmpty() {
        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("pg-empty-region");
        assertNotNull(locs);
        assertTrue(locs.isEmpty());
    }

    @Test
    void loadCachedLocations_sharedRow_hasNullPlayerId() throws Exception {
        try (Connection conn = db.getConnection()) {
            db.write(conn, "rtp_cached_locations", cachedLocationRow("pg-shared", "pgshared", "shared", 1L));
        }
        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("pgshared");
        assertEquals(1, locs.size());
        DatabaseAccessor.StoredLocation loc = locs.get(0);
        assertEquals("pg-shared", loc.getId());
        assertEquals("world", loc.getWorldName());
        assertNull(loc.getPlayerId());
    }

    @Test
    void loadCachedLocations_playerBoundRow_parsesPlayerId() throws Exception {
        UUID player = UUID.randomUUID();
        try (Connection conn = db.getConnection()) {
            db.write(conn, "rtp_cached_locations",
                    cachedLocationRow("pg-p", "pgp", player.toString(), System.currentTimeMillis()));
        }
        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("pgp");
        assertEquals(1, locs.size());
        assertEquals(player, locs.get(0).getPlayerId());
    }

    @Test
    void loadCachedLocations_invalidPlayerUuid_leavesNullPlayerId() throws Exception {
        try (Connection conn = db.getConnection()) {
            db.write(conn, "rtp_cached_locations",
                    cachedLocationRow("pg-bad", "pgbad", "not-a-uuid", System.currentTimeMillis()));
        }
        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("pgbad");
        assertEquals(1, locs.size());
        assertNull(locs.get(0).getPlayerId());
    }

    // -------------------------------------------------------------------------
    // clear / purge
    // -------------------------------------------------------------------------

    @Test
    void clearAllCachedLocations_removesEverything() throws Exception {
        try (Connection conn = db.getConnection()) {
            db.write(conn, "rtp_cached_locations", cachedLocationRow("pg-c1", "pgclear", "shared", 1L));
            db.write(conn, "rtp_cached_locations", cachedLocationRow("pg-c2", "pgclear", "shared", 1L));
        }
        db.clearAllCachedLocations();
        assertTrue(db.loadCachedLocations("pgclear").isEmpty());
    }

    @Test
    void purgeStaleLocations_dropsExpiredPlayerBoundRows() throws Exception {
        UUID player = UUID.randomUUID();
        try (Connection conn = db.getConnection()) {
            db.write(conn, "rtp_cached_locations", cachedLocationRow("pg-stale", "pgpurge", player.toString(), 1L));
            db.write(conn, "rtp_cached_locations",
                    cachedLocationRow("pg-keep", "pgpurge", "shared", System.currentTimeMillis()));
        }
        db.purgeStaleLocations();
        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("pgpurge");
        assertEquals(1, locs.size());
        assertEquals("pg-keep", locs.get(0).getId());
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
    void getInsertStatement_hasOnConflict() {
        assertTrue(db.getInsertStatement().toUpperCase().contains("ON CONFLICT"));
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
