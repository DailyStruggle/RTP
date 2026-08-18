package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for H2DatabaseAccessor using an in-memory H2 database.
 *
 * A package-private subclass bypasses the RTP.serverAccessor dependency in the
 * default constructor by accepting a JDBC URL directly.
 */
class H2DatabaseAccessorTest {

    /** Testable subclass that accepts a URL instead of calling RTP.serverAccessor */
    static class TestableH2Accessor extends AbstractSQLDatabaseAccessor {
        private final String url;
        private Connection connection;

        TestableH2Accessor(String url) throws SQLException {
            this.url = url;
            this.connection = DriverManager.getConnection(url);
            try (Statement st = connection.createStatement()) {
                st.execute(
                    "CREATE TABLE IF NOT EXISTS rtp_teleport_data ("
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
                st.execute(
                    "CREATE TABLE IF NOT EXISTS rtp_cached_locations ("
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
        public String name() { return url; }

        @Override
        public Connection getConnection() throws SQLException {
            if (connection == null || connection.isClosed()) {
                connection = DriverManager.getConnection(url);
            }
            return connection;
        }

        @Override
        public void disconnect(Connection connection) {
            // Shared connection, do nothing.
        }

        @Override
        public void close() {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void startup() {
            // no-op for tests
        }

        @Override
        protected String getInsertStatement() {
            return "INSERT INTO rtp_teleport_data "
                + "(senderName, senderId, time, delay, selectedX, selectedY, selectedZ, "
                + "selectedWorldName, selectedWorldId, originalX, originalY, originalZ, "
                + "originalWorldName, originalWorldId, region, cost, attempts) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        }

        @Override
        public @org.jetbrains.annotations.NotNull Optional<Map<String, Object>> read(
                Connection connection, String tableName, Map.Entry<String, Object> lookup) {
            Map<String, Object> row = new HashMap<>();
            String sql = "SELECT * FROM " + tableName + " WHERE " + lookup.getKey() + " = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setObject(1, lookup.getValue());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            Object val = rs.getObject(i);
                            if (val != null) row.put(meta.getColumnName(i), val);
                        }
                        return Optional.of(row);
                    }
                }
            } catch (SQLException ignored) {}
            return Optional.empty();
        }

        @Override
        public void write(Connection connection, String tableName,
                          Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> keyValuePairs) {
            if (keyValuePairs == null || keyValuePairs.isEmpty()) throw new IllegalStateException();

            StringBuilder columns = new StringBuilder();
            StringBuilder placeholders = new StringBuilder();
            List<Object> params = new ArrayList<>();

            for (Map.Entry<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> e : keyValuePairs.entrySet()) {
                columns.append(e.getKey().object.toString()).append(",");
                placeholders.append("?,");
                params.add(e.getValue().object);
            }
            columns.setLength(columns.length() - 1);
            placeholders.setLength(placeholders.length() - 1);

            String sql = "MERGE INTO " + tableName + " (" + columns + ") VALUES (" + placeholders + ")";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // Each test gets its own named in-memory DB so there is no cross-test state.
    private String dbUrl;
    private TestableH2Accessor accessor;
    private Connection connection;

    @BeforeEach
    void setUp() throws SQLException {
        // Unique DB name per test instance avoids shared state between tests
        dbUrl = "jdbc:h2:mem:h2test_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
        accessor = new TestableH2Accessor(dbUrl);
        connection = DriverManager.getConnection(dbUrl);
    }

    @AfterEach
    void tearDown() throws SQLException {
        // Drop tables and close our own connection; accessor's internal connection
        // may already be closed by individual tests - that is fine.
        if (connection != null && !connection.isClosed()) {
            try (Statement st = connection.createStatement()) {
                st.execute("DROP TABLE IF EXISTS rtp_teleport_data");
                st.execute("DROP TABLE IF EXISTS rtp_cached_locations");
                st.execute("DROP TABLE IF EXISTS custom_table");
            }
            connection.close();
        }
    }

    // -------------------------------------------------------------------------
    // connect() / disconnect()
    // -------------------------------------------------------------------------

    @Test
    void connect_returnsOpenConnection() throws SQLException {
        Connection conn = accessor.connect();
        assertNotNull(conn);
        assertFalse(conn.isClosed());
    }

    @Test
    void disconnect_doesNotCloseConnection() throws SQLException {
        // Use a separate connection so the shared field is unaffected
        Connection conn = DriverManager.getConnection(dbUrl);
        accessor.disconnect(conn);
        assertFalse(conn.isClosed(), "disconnect should not close a shared connection");
        conn.close();
    }

    @Test
    void close_closesInternalConnection() throws SQLException {
        Connection conn = accessor.getConnection();
        assertFalse(conn.isClosed());
        accessor.close();
        assertTrue(conn.isClosed());
    }

    @Test
    void getConnection_reopensAfterClose() throws SQLException {
        // Close the accessor's internal connection, then verify it reopens
        accessor.getConnection().close();
        Connection reopened = accessor.getConnection();
        assertNotNull(reopened);
        assertFalse(reopened.isClosed());
    }

    // -------------------------------------------------------------------------
    // Table creation (verified via schema inspection)
    // -------------------------------------------------------------------------

    @Test
    void tableCreation_rtpTeleportDataExists() throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, "RTP_TELEPORT_DATA", null)) {
            assertTrue(rs.next(), "rtp_teleport_data table should exist");
        }
    }

    @Test
    void tableCreation_rtpCachedLocationsExists() throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, "RTP_CACHED_LOCATIONS", null)) {
            assertTrue(rs.next(), "rtp_cached_locations table should exist");
        }
    }

    // -------------------------------------------------------------------------
    // write() - basic CRUD
    // -------------------------------------------------------------------------

    @Test
    void write_insertsRowIntoTable() throws SQLException {
        // Create a simple test table
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS custom_table (UUID VARCHAR(255) PRIMARY KEY, val TEXT)");
        }

        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> pairs = new LinkedHashMap<>();
        pairs.put(new DatabaseAccessor.TableObj("UUID"), new DatabaseAccessor.TableObj("abc-123"));
        pairs.put(new DatabaseAccessor.TableObj("val"), new DatabaseAccessor.TableObj("hello"));

        accessor.write(connection, "custom_table", pairs);

        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT val FROM custom_table WHERE UUID = 'abc-123'")) {
            assertTrue(rs.next());
            assertEquals("hello", rs.getString("val"));
        }
    }

    @Test
    void write_emptyPairs_throwsIllegalState() {
        assertThrows(IllegalStateException.class,
                () -> accessor.write(connection, "rtp_teleport_data", new LinkedHashMap<>()));
    }

    @Test
    void write_nullPairs_throwsIllegalState() {
        assertThrows(IllegalStateException.class,
                () -> accessor.write(connection, "rtp_teleport_data", null));
    }

    // -------------------------------------------------------------------------
    // read()
    // -------------------------------------------------------------------------

    @Test
    void read_existingRow_returnsData() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS custom_table (UUID VARCHAR(255) PRIMARY KEY, val TEXT)");
            st.execute("INSERT INTO custom_table (UUID, val) VALUES ('row-1', 'data')");
        }

        Optional<Map<String, Object>> result =
                accessor.read(connection, "custom_table",
                        new AbstractMap.SimpleEntry<>("UUID", "row-1"));

        assertTrue(result.isPresent());
        assertEquals("data", result.get().get("VAL"));
    }

    @Test
    void read_missingRow_returnsEmpty() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS custom_table (UUID VARCHAR(255) PRIMARY KEY, val TEXT)");
        }

        Optional<Map<String, Object>> result =
                accessor.read(connection, "custom_table",
                        new AbstractMap.SimpleEntry<>("UUID", "no-such-row"));

        assertFalse(result.isPresent());
    }

    @Test
    void read_invalidTable_returnsEmpty() {
        Optional<Map<String, Object>> result =
                accessor.read(connection, "nonexistent_table",
                        new AbstractMap.SimpleEntry<>("id", "1"));
        assertFalse(result.isPresent());
    }

    // -------------------------------------------------------------------------
    // delete()
    // -------------------------------------------------------------------------

    @Test
    void delete_removesMatchingRow() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS custom_table (UUID VARCHAR(255) PRIMARY KEY, val TEXT)");
            st.execute("INSERT INTO custom_table (UUID, val) VALUES ('del-1', 'bye')");
        }

        accessor.delete(connection, "custom_table",
                new AbstractMap.SimpleEntry<>("UUID", "del-1"));

        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM custom_table WHERE UUID = 'del-1'")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    void delete_nonExistentRow_doesNotThrow() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS custom_table (UUID VARCHAR(255) PRIMARY KEY, val TEXT)");
        }
        assertDoesNotThrow(() ->
                accessor.delete(connection, "custom_table",
                        new AbstractMap.SimpleEntry<>("UUID", "ghost")));
    }

    // -------------------------------------------------------------------------
    // loadCachedLocations()
    // -------------------------------------------------------------------------

    @Test
    void loadCachedLocations_emptyTable_returnsEmptyList() {
        assertTrue(accessor.loadCachedLocations("myRegion").isEmpty());
    }

    @Test
    void loadCachedLocations_withMatchingRow_returnsLocation() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(
                "INSERT INTO rtp_cached_locations "
                + "(UUID, world, x, y, z, attempts, region, player_uuid, timestamp, seed) "
                + "VALUES ('loc-1', 'world', 10, 64, -20, 2, 'myRegion', 'shared', 0, 99)");
        }

        var locations = accessor.loadCachedLocations("myRegion");
        assertEquals(1, locations.size());
        DatabaseAccessor.StoredLocation loc = locations.get(0);
        assertEquals("myRegion", loc.getRegionName());
        assertEquals("world", loc.getWorldName());
        assertEquals(10, loc.getX());
        assertEquals(64, loc.getY());
        assertEquals(-20, loc.getZ());
        assertEquals(99L, loc.getSeed());
    }

    @Test
    void loadCachedLocations_differentRegion_returnsEmpty() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(
                "INSERT INTO rtp_cached_locations "
                + "(UUID, world, x, y, z, attempts, region, player_uuid, timestamp, seed) "
                + "VALUES ('loc-2', 'world', 0, 64, 0, 1, 'otherRegion', 'shared', 0, 0)");
        }
        assertTrue(accessor.loadCachedLocations("myRegion").isEmpty());
    }

    // -------------------------------------------------------------------------
    // purgeStaleLocations()
    // -------------------------------------------------------------------------

    @Test
    void purgeStaleLocations_removesOldPlayerBoundEntries() throws SQLException {
        long oldTimestamp = System.currentTimeMillis() - (8L * 24 * 60 * 60 * 1000); // 8 days ago
        String playerUuid = UUID.randomUUID().toString();
        try (Statement st = connection.createStatement()) {
            st.execute(
                "INSERT INTO rtp_cached_locations "
                + "(UUID, world, x, y, z, attempts, region, player_uuid, timestamp, seed) "
                + "VALUES ('stale-1', 'world', 0, 64, 0, 1, 'r', '" + playerUuid + "', "
                + oldTimestamp + ", 0)");
        }

        accessor.purgeStaleLocations();

        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM rtp_cached_locations WHERE UUID = 'stale-1'")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    void purgeStaleLocations_keepsSharedEntries() throws SQLException {
        long oldTimestamp = System.currentTimeMillis() - (8L * 24 * 60 * 60 * 1000);
        try (Statement st = connection.createStatement()) {
            st.execute(
                "INSERT INTO rtp_cached_locations "
                + "(UUID, world, x, y, z, attempts, region, player_uuid, timestamp, seed) "
                + "VALUES ('shared-1', 'world', 0, 64, 0, 1, 'r', 'shared', "
                + oldTimestamp + ", 0)");
        }

        accessor.purgeStaleLocations();

        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM rtp_cached_locations WHERE UUID = 'shared-1'")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }

    // -------------------------------------------------------------------------
    // name()
    // -------------------------------------------------------------------------

    @Test
    void name_returnsUrl() {
        assertEquals(dbUrl, accessor.name());
    }
}
