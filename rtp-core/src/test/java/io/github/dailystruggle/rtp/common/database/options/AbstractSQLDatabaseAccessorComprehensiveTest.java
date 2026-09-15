package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.network.NetworkStateBinding;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("REQ-RTP-DB-OPT-002: AbstractSQLDatabaseAccessor Comprehensive Tests")
class AbstractSQLDatabaseAccessorComprehensiveTest {

    @TempDir
    Path tempDir;

    private static class ConcreteSQLAccessor extends AbstractSQLDatabaseAccessor {
        private final String url;
        private Connection connection;
        private boolean autoCommitBehavior = true;

        ConcreteSQLAccessor(String url) throws SQLException {
            this.url = url;
            this.connection = DriverManager.getConnection(url);
            try (Statement st = connection.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS rtp_teleport_data ("
                        + "senderName TEXT, "
                        + "senderId VARCHAR(36), "
                        + "time BIGINT, "
                        + "delay BIGINT, "
                        + "selectedX INT, "
                        + "selectedY INT, "
                        + "selectedZ INT, "
                        + "selectedWorldName TEXT, "
                        + "selectedWorldId VARCHAR(36), "
                        + "originalX INT, "
                        + "originalY INT, "
                        + "originalZ INT, "
                        + "originalWorldName TEXT, "
                        + "originalWorldId VARCHAR(36), "
                        + "region TEXT, "
                        + "cost DOUBLE, "
                        + "attempts INT"
                        + ")");
                st.execute("CREATE TABLE IF NOT EXISTS rtp_cached_locations ("
                        + "UUID VARCHAR(255) PRIMARY KEY, "
                        + "world TEXT, "
                        + "x INT, "
                        + "y INT, "
                        + "z INT, "
                        + "attempts INT, "
                        + "region TEXT, "
                        + "player_uuid VARCHAR(36), "
                        + "timestamp BIGINT, "
                        + "seed BIGINT"
                        + ")");
            }
        }

        @Override
        public String name() {
            return url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (connection == null || connection.isClosed()) {
                connection = DriverManager.getConnection(url);
                connection.setAutoCommit(autoCommitBehavior);
            }
            return connection;
        }

        @Override
        public void startup() {}

        @Override
        protected String getInsertStatement() {
            return "INSERT INTO rtp_teleport_data "
                    + "(senderName, senderId, time, delay, selectedX, selectedY, selectedZ, "
                    + "selectedWorldName, selectedWorldId, originalX, originalY, originalZ, "
                    + "originalWorldName, originalWorldId, region, cost, attempts) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        }

        @Override
        public Optional<Map<String, Object>> read(Connection connection, String tableName, Map.Entry<String, Object> lookup) {
            return Optional.empty();
        }

        @Override
        public void write(Connection connection, String tableName, Map<TableObj, TableObj> keyValuePairs) {}
    }

    private ConcreteSQLAccessor accessor;

    @BeforeEach
    void setUp() throws SQLException {
        RTPTestSetup.install(tempDir.toFile());
        String dbUrl = "jdbc:h2:mem:abstractsqltest_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        accessor = new ConcreteSQLAccessor(dbUrl);
    }

    @AfterEach
    void tearDown() {
        if (accessor != null) {
            try {
                accessor.getConnection().createStatement().execute("SHUTDOWN");
                accessor.disconnect(accessor.getConnection());
            } catch (Throwable ignored) {}
        }
        RTP.serverAccessor = null;
    }

    @Test
    void networkStateBinding_roundTrip() {
        assertNull(accessor.getNetworkStateBinding());
        NetworkStateBinding binding = mock(NetworkStateBinding.class);
        accessor.setNetworkStateBinding(binding);
        assertSame(binding, accessor.getNetworkStateBinding());
        accessor.setNetworkStateBinding(null);
        assertNull(accessor.getNetworkStateBinding());
    }

    @Test
    void asDataSource_coversAllDataSourceMethods() throws Exception {
        DataSource ds = accessor.asDataSource();
        assertNotNull(ds);
        Connection c1 = ds.getConnection();
        assertNotNull(c1);
        Connection c2 = ds.getConnection("user", "pass");
        assertNotNull(c2);

        assertNull(ds.getLogWriter());
        assertDoesNotThrow(() -> ds.setLogWriter(null));
        assertDoesNotThrow(() -> ds.setLoginTimeout(10));
        assertEquals(0, ds.getLoginTimeout());
        assertNotNull(ds.getParentLogger());
        assertNull(ds.unwrap(String.class));
        assertFalse(ds.isWrapperFor(String.class));
    }

    @Test
    void cacheValue_teleportData_andFlush() throws Exception {
        TeleportData data = new TeleportData();
        data.time = 123456L;
        data.delay = 10L;
        data.cost = 5.5;
        data.attempts = 3;
        data.selectedCoords = new RTPCoords("world", 100, 70, 200);
        data.originalCoords = new RTPCoords("world", 10, 60, 20);
        io.github.dailystruggle.rtp.common.selection.region.Region region = mock(io.github.dailystruggle.rtp.common.selection.region.Region.class);
        region.name = "default";
        data.targetRegion = region;

        accessor.cacheValue(data);
        assertEquals(1, accessor.writeQueue.size());

        accessor.flush();
        assertTrue(accessor.writeQueue.isEmpty());

        // Verify inserted row in rtp_teleport_data
        try (Statement st = accessor.getConnection().createStatement()) {
            var rs = st.executeQuery("SELECT count(*) FROM rtp_teleport_data");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void cacheValue_mapData_teleportDataMapping() {
        Map<String, Object> data = new HashMap<>();
        UUID senderUuid = UUID.randomUUID();
        data.put("senderId", senderUuid.toString());
        data.put("time", 5000L);
        data.put("delay", 20L);
        data.put("cost", 100.0);
        data.put("attempts", 2L);
        data.put("selectedX", 50);
        data.put("selectedY", 64);
        data.put("selectedZ", -50);
        data.put("selectedWorldName", "world");
        data.put("originalX", 0);
        data.put("originalY", 64);
        data.put("originalZ", 0);
        data.put("originalWorldName", "world");

        accessor.cacheValue("rtp_teleport_data", data);
        assertEquals(1, accessor.writeQueue.size());
        TeleportData queued = accessor.writeQueue.peek();
        assertNotNull(queued);
        assertEquals(5000L, queued.time);
        assertEquals(20L, queued.delay);
        assertEquals(100.0, queued.cost);
        assertEquals(2L, queued.attempts);
        assertNotNull(queued.selectedCoords);
        assertEquals(50, queued.selectedCoords.x());
        assertEquals("world", queued.selectedCoords.worldName());
        assertNotNull(queued.originalCoords);
        assertEquals(0, queued.originalCoords.x());
    }

    @Test
    void cacheValue_mapData_withUUIDKeyInsteadOfSenderId() {
        Map<String, Object> data = new HashMap<>();
        UUID senderUuid = UUID.randomUUID();
        data.put("UUID", senderUuid.toString());
        data.put("time", 1000L);

        accessor.cacheValue("teleportData", data);
        assertEquals(1, accessor.writeQueue.size());
    }

    @Test
    void cacheValue_mapData_withInvalidData_fallsBackToSuper() {
        // Putting non-numeric or causing exception in mapping
        Map<String, Object> data = new HashMap<>();
        data.put("senderId", "invalid-uuid-string-that-causes-exception");
        // UUID.fromString will fail, catching exception and falling back to super.cacheValue
        assertDoesNotThrow(() -> accessor.cacheValue("rtp_teleport_data", data));
    }

    @Test
    void cacheValue_arbitraryTable_fallsBackToSuper() {
        Map<String, Object> data = new HashMap<>();
        data.put("foo", "bar");
        assertDoesNotThrow(() -> accessor.cacheValue("other_table", data));
    }

    @Test
    void flush_emptyQueue_isNoOp() {
        assertDoesNotThrow(() -> accessor.flush());
    }

    @Test
    void flush_handlesRollbackOnSQLException() throws Exception {
        TeleportData data = new TeleportData();
        accessor.cacheValue(data);

        // Subclass with bad SQL to trigger SQLException in statement execution
        ConcreteSQLAccessor badAccessor = new ConcreteSQLAccessor("jdbc:h2:mem:badsql_" + UUID.randomUUID().toString().replace("-", "")) {
            @Override
            protected String getInsertStatement() {
                return "INSERT INTO non_existent_table VALUES (?)";
            }
        };
        badAccessor.cacheValue(data);
        assertDoesNotThrow(() -> badAccessor.flush());
    }

    @Test
    void delete_removesMatchingRow() throws Exception {
        try (Statement st = accessor.getConnection().createStatement()) {
            st.execute("INSERT INTO rtp_cached_locations (UUID, world, x, y, z, attempts, region, player_uuid, timestamp, seed) "
                    + "VALUES ('loc-del', 'world', 1, 2, 3, 0, 'default', 'shared', 1000, 0)");
        }

        accessor.delete(accessor.getConnection(), "rtp_cached_locations", new AbstractMap.SimpleEntry<>("UUID", "loc-del"));

        try (Statement st = accessor.getConnection().createStatement()) {
            var rs = st.executeQuery("SELECT count(*) FROM rtp_cached_locations WHERE UUID = 'loc-del'");
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    void delete_handlesSQLExceptionGracefully() throws Exception {
        // Deleting from non-existent table should not throw unhandled exception
        assertDoesNotThrow(() -> accessor.delete(accessor.getConnection(), "table_does_not_exist", new AbstractMap.SimpleEntry<>("id", 1)));
    }

    @Test
    void loadCachedLocations_parsesSharedAndPlayerBoundRows() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        try (Statement st = accessor.getConnection().createStatement()) {
            st.execute("INSERT INTO rtp_cached_locations (UUID, world, x, y, z, attempts, region, player_uuid, timestamp, seed) "
                    + "VALUES ('loc-shared', 'world', 10, 64, 20, 1, 'my_region', 'shared', 1000, 42)");
            st.execute("INSERT INTO rtp_cached_locations (UUID, world, x, y, z, attempts, region, player_uuid, timestamp, seed) "
                    + "VALUES ('loc-player', 'world', 30, 64, 40, 2, 'my_region', '" + playerUuid + "', 2000, 84)");
            st.execute("INSERT INTO rtp_cached_locations (UUID, world, x, y, z, attempts, region, player_uuid, timestamp, seed) "
                    + "VALUES ('loc-other', 'world', 50, 64, 60, 3, 'other_region', 'shared', 3000, 126)");
        }

        List<DatabaseAccessor.StoredLocation> locs = accessor.loadCachedLocations("my_region");
        assertEquals(2, locs.size());

        DatabaseAccessor.StoredLocation loc1 = locs.stream().filter(l -> l.getId().equals("loc-shared")).findFirst().orElseThrow();
        assertEquals("world", loc1.getWorldName());
        assertEquals(10, loc1.getX());
        assertEquals(64, loc1.getY());
        assertEquals(20, loc1.getZ());
        assertEquals(1, loc1.getAttempts());
        assertEquals(42L, loc1.getSeed());
        assertNull(loc1.getPlayerId());

        DatabaseAccessor.StoredLocation loc2 = locs.stream().filter(l -> l.getId().equals("loc-player")).findFirst().orElseThrow();
        assertEquals(playerUuid, loc2.getPlayerId());
        assertEquals(84L, loc2.getSeed());
    }

    @Test
    void loadCachedLocations_tableMissing_returnsEmptyList() throws Exception {
        try (Statement st = accessor.getConnection().createStatement()) {
            st.execute("DROP TABLE rtp_cached_locations");
        }
        List<DatabaseAccessor.StoredLocation> locs = accessor.loadCachedLocations("my_region");
        assertNotNull(locs);
        assertTrue(locs.isEmpty());
    }

    @Test
    void clearAllCachedLocations_deletesAllRows() throws Exception {
        try (Statement st = accessor.getConnection().createStatement()) {
            st.execute("INSERT INTO rtp_cached_locations (UUID, world, x, y, z, attempts, region, player_uuid, timestamp, seed) "
                    + "VALUES ('loc-1', 'world', 1, 2, 3, 0, 'r', 'shared', 100, 0)");
            st.execute("INSERT INTO rtp_cached_locations (UUID, world, x, y, z, attempts, region, player_uuid, timestamp, seed) "
                    + "VALUES ('loc-2', 'world', 4, 5, 6, 0, 'r', 'shared', 200, 0)");
        }

        accessor.clearAllCachedLocations();

        try (Statement st = accessor.getConnection().createStatement()) {
            var rs = st.executeQuery("SELECT count(*) FROM rtp_cached_locations");
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    void clearAllCachedLocations_tableMissing_isNoOp() throws Exception {
        try (Statement st = accessor.getConnection().createStatement()) {
            st.execute("DROP TABLE rtp_cached_locations");
        }
        assertDoesNotThrow(() -> accessor.clearAllCachedLocations());
    }

    @Test
    void purgeStaleLocations_onlyPurgesPlayerBoundLocationsOlderThan7Days() throws Exception {
        long now = System.currentTimeMillis();
        long eightDaysAgo = now - (8 * 24 * 60 * 60 * 1000L);
        long oneDayAgo = now - (1 * 24 * 60 * 60 * 1000L);
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        try (Statement st = accessor.getConnection().createStatement()) {
            // Stale player location (8 days old) -> should be purged
            st.execute("INSERT INTO rtp_cached_locations (UUID, world, x, y, z, attempts, region, player_uuid, timestamp, seed) "
                    + "VALUES ('stale-player', 'world', 1, 2, 3, 0, 'r', '" + p1 + "', " + eightDaysAgo + ", 0)");
            // Stale with NULL timestamp -> should be purged
            st.execute("INSERT INTO rtp_cached_locations (UUID, world, x, y, z, attempts, region, player_uuid, timestamp, seed) "
                    + "VALUES ('stale-null-time', 'world', 1, 2, 3, 0, 'r', '" + p1 + "', NULL, 0)");
            // Fresh player location (1 day old) -> should remain
            st.execute("INSERT INTO rtp_cached_locations (UUID, world, x, y, z, attempts, region, player_uuid, timestamp, seed) "
                    + "VALUES ('fresh-player', 'world', 1, 2, 3, 0, 'r', '" + p2 + "', " + oneDayAgo + ", 0)");
            // Shared location (8 days old) -> should remain because player_uuid == 'shared'
            st.execute("INSERT INTO rtp_cached_locations (UUID, world, x, y, z, attempts, region, player_uuid, timestamp, seed) "
                    + "VALUES ('stale-shared', 'world', 1, 2, 3, 0, 'r', 'shared', " + eightDaysAgo + ", 0)");
        }

        accessor.purgeStaleLocations();

        try (Statement st = accessor.getConnection().createStatement()) {
            var rs = st.executeQuery("SELECT UUID FROM rtp_cached_locations ORDER BY UUID");
            List<String> remaining = new ArrayList<>();
            while (rs.next()) {
                remaining.add(rs.getString("UUID"));
            }
            assertEquals(2, remaining.size());
            assertTrue(remaining.contains("fresh-player"));
            assertTrue(remaining.contains("stale-shared"));
            assertFalse(remaining.contains("stale-player"));
            assertFalse(remaining.contains("stale-null-time"));
        }
    }

    @Test
    void purgeStaleLocations_tableMissing_isNoOp() throws Exception {
        try (Statement st = accessor.getConnection().createStatement()) {
            st.execute("DROP TABLE rtp_cached_locations");
        }
        assertDoesNotThrow(() -> accessor.purgeStaleLocations());
    }

    @Test
    void connect_and_disconnect() throws Exception {
        Connection conn = accessor.connect();
        assertNotNull(conn);
        assertFalse(conn.isClosed());
        accessor.disconnect(conn);
        assertTrue(conn.isClosed());

        // Calling disconnect on null or closed connection should not throw
        assertDoesNotThrow(() -> accessor.disconnect(null));
        assertDoesNotThrow(() -> accessor.disconnect(conn));
    }

    @Test
    void connect_handlesSQLException() throws Exception {
        ConcreteSQLAccessor brokenAccessor = new ConcreteSQLAccessor("jdbc:h2:mem:broken_" + UUID.randomUUID().toString().replace("-", "")) {
            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException("Simulated connection failure");
            }
        };
        Connection conn = brokenAccessor.connect();
        assertNull(conn);
    }
}
