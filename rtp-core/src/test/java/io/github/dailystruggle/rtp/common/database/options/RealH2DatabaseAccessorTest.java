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
 * Drives the real {@link H2DatabaseAccessor} against an on-disk H2 database created
 * under a JUnit {@code @TempDir}. Unlike the mock-based tests that copy the read/write
 * logic into a throwaway subclass, these exercise the shipped bytecode directly so the
 * coverage credits the production class (ENTERPRISE_READINESS.md item 18).
 */
class RealH2DatabaseAccessorTest {

    @TempDir
    Path tempDir;

    private H2DatabaseAccessor db;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        db = new H2DatabaseAccessor();
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
    void name_isJdbcH2Url() {
        assertTrue(db.name().startsWith("jdbc:h2:file:"), db.name());
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
        assertEquals(0, ds.getLoginTimeout());
        assertNull(ds.unwrap(String.class));
        assertFalse(ds.isWrapperFor(String.class));
        assertNotNull(ds.getParentLogger());
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
        assertEquals("world", read.get().get("WORLD"));
    }

    @Test
    void read_missingRow_returnsEmpty() throws Exception {
        Optional<Map<String, Object>> read = db.read(db.getConnection(), "rtp_cached_locations",
                new AbstractMap.SimpleEntry<>("UUID", "nope"));
        assertFalse(read.isPresent());
    }

    @Test
    void read_missingTable_returnsEmpty() throws Exception {
        Optional<Map<String, Object>> read = db.read(db.getConnection(), "no_such_table",
                new AbstractMap.SimpleEntry<>("UUID", "x"));
        assertFalse(read.isPresent());
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
    void write_missingTable_isSwallowed() throws Exception {
        Connection conn = db.getConnection();
        // H2's write logs and swallows the SQLException rather than propagating.
        assertDoesNotThrow(() ->
                db.write(conn, "no_such_table", cachedLocationRow("loc-x", "default", "shared", 1L)));
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    @Test
    void write_thenDelete_rowGone() throws Exception {
        Connection conn = db.getConnection();
        db.write(conn, "rtp_cached_locations", cachedLocationRow("loc-del", "default", "shared", 1L));
        db.delete(conn, "rtp_cached_locations", new AbstractMap.SimpleEntry<>("UUID", "loc-del"));

        Optional<Map<String, Object>> read = db.read(conn, "rtp_cached_locations",
                new AbstractMap.SimpleEntry<>("UUID", "loc-del"));
        assertFalse(read.isPresent());
    }

    @Test
    void delete_missingTable_isSwallowed() throws Exception {
        Connection conn = db.getConnection();
        assertDoesNotThrow(() -> db.delete(conn, "no_such_table",
                new AbstractMap.SimpleEntry<>("UUID", "x")));
    }

    // -------------------------------------------------------------------------
    // loadCachedLocations
    // -------------------------------------------------------------------------

    @Test
    void loadCachedLocations_emptyTable_returnsEmpty() {
        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("default");
        assertNotNull(locs);
        assertTrue(locs.isEmpty());
    }

    @Test
    void loadCachedLocations_sharedRow_hasNullPlayerId() throws Exception {
        Connection conn = db.getConnection();
        db.write(conn, "rtp_cached_locations", cachedLocationRow("loc-shared", "spawn", "shared", 1L));

        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("spawn");
        assertEquals(1, locs.size());
        DatabaseAccessor.StoredLocation loc = locs.get(0);
        assertEquals("loc-shared", loc.getId());
        assertEquals("world", loc.getWorldName());
        assertNull(loc.getPlayerId());
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
    void loadCachedLocations_invalidPlayerUuid_leavesNullPlayerId() throws Exception {
        Connection conn = db.getConnection();
        db.write(conn, "rtp_cached_locations",
                cachedLocationRow("loc-bad", "badp", "not-a-uuid", System.currentTimeMillis()));

        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("badp");
        assertEquals(1, locs.size());
        assertNull(locs.get(0).getPlayerId());
    }

    // -------------------------------------------------------------------------
    // clear / purge
    // -------------------------------------------------------------------------

    @Test
    void clearAllCachedLocations_removesEverything() throws Exception {
        Connection conn = db.getConnection();
        db.write(conn, "rtp_cached_locations", cachedLocationRow("loc-a", "r", "shared", 1L));
        db.write(conn, "rtp_cached_locations", cachedLocationRow("loc-b", "r", "shared", 1L));

        db.clearAllCachedLocations();
        assertTrue(db.loadCachedLocations("r").isEmpty());
    }

    @Test
    void purgeStaleLocations_dropsExpiredPlayerBoundRows() throws Exception {
        Connection conn = db.getConnection();
        UUID player = UUID.randomUUID();
        // timestamp far in the past (older than 7 days) -> purged.
        db.write(conn, "rtp_cached_locations", cachedLocationRow("loc-stale", "r", player.toString(), 1L));
        // shared rows are never purged.
        db.write(conn, "rtp_cached_locations",
                cachedLocationRow("loc-keep", "r", "shared", System.currentTimeMillis()));

        db.purgeStaleLocations();

        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("r");
        assertEquals(1, locs.size());
        assertEquals("loc-keep", locs.get(0).getId());
    }

    // -------------------------------------------------------------------------
    // flush (batched insert) + startup
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
    void flush_withData_commitsBatch() throws Exception {
        db.cacheValue(teleportData());
        db.flush();

        // startup() reads rtp_teleport_data back into RTP.getInstance().latestTeleportData.
        assertDoesNotThrow(db::startup);
    }

    @Test
    void flush_missingTable_rollsBackWithoutThrowing() throws Exception {
        Connection conn = db.getConnection();
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE rtp_teleport_data");
        }
        db.cacheValue(teleportData());
        // prepareStatement against the dropped table fails; the accessor must roll back
        // and swallow rather than propagate.
        assertDoesNotThrow(db::flush);
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
    // connect / disconnect / close
    // -------------------------------------------------------------------------

    @Test
    void connect_returnsUsableConnection() {
        Connection conn = db.connect();
        assertNotNull(conn);
    }

    @Test
    void disconnect_isSharedNoOp() throws Exception {
        Connection conn = db.getConnection();
        db.disconnect(conn);
        // H2's disconnect deliberately keeps the shared connection open.
        assertFalse(conn.isClosed());
    }

    @Test
    void close_closesConnection() throws Exception {
        Connection conn = db.getConnection();
        db.close();
        assertTrue(conn.isClosed());
        // close() on an already-closed connection is a safe no-op.
        assertDoesNotThrow(db::close);
        db = null;
    }
}
