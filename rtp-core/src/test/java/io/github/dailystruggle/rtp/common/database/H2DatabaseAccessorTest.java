package io.github.dailystruggle.rtp.common.database;

import io.github.dailystruggle.rtp.common.database.options.AbstractSQLDatabaseAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the H2-backed SQL database accessor.
 *
 * <p>Rather than using the production {@code H2DatabaseAccessor} constructor
 * (which calls {@code RTP.serverAccessor.getPluginDirectory()}), we create a
 * minimal anonymous subclass that opens an in-memory H2 database directly.
 * This keeps the test fast and free of server-coupling while still exercising
 * the real {@link AbstractSQLDatabaseAccessor} logic.
 */
class H2DatabaseAccessorTest extends AbstractDatabaseAccessorTest {

    @TempDir
    Path tempDir;

    private TestH2Accessor db;

    // -------------------------------------------------------------------------
    // Minimal testable subclass
    // -------------------------------------------------------------------------

    static class TestH2Accessor extends AbstractSQLDatabaseAccessor {
        private final String url;
        private Connection sharedConnection;

        TestH2Accessor(String url) throws SQLException {
            this.url = url;
            this.sharedConnection = DriverManager.getConnection(url);
            try (Statement st = sharedConnection.createStatement()) {
                st.execute(
                    "CREATE TABLE IF NOT EXISTS rtp_teleport_data ("
                    + "senderName TEXT, senderId VARCHAR(36), time BIGINT, delay BIGINT, "
                    + "selectedX INT, selectedY INT, selectedZ INT, selectedWorldName TEXT, "
                    + "selectedWorldId VARCHAR(36), originalX INT, originalY INT, originalZ INT, "
                    + "originalWorldName TEXT, originalWorldId VARCHAR(36), region TEXT, "
                    + "cost DOUBLE, attempts INT)");
                st.execute(
                    "CREATE TABLE IF NOT EXISTS rtp_cached_locations ("
                    + "UUID VARCHAR(255) PRIMARY KEY, world TEXT, x INT, y INT, z INT, "
                    + "attempts INT, region TEXT, player_uuid VARCHAR(36), "
                    + "timestamp BIGINT, seed BIGINT)");
                st.execute(
                    "CREATE TABLE IF NOT EXISTS test_table ("
                    + "id VARCHAR(255) PRIMARY KEY, val VARCHAR(255))");
            }
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (sharedConnection == null || sharedConnection.isClosed()) {
                sharedConnection = DriverManager.getConnection(url);
            }
            return sharedConnection;
        }

        @Override
        public String name() { return "TestH2"; }

        @Override
        public void startup() { /* no-op for tests */ }

        @Override
        protected String getInsertStatement() {
            return "INSERT INTO rtp_teleport_data "
                + "(senderName, senderId, time, delay, selectedX, selectedY, selectedZ, "
                + "selectedWorldName, selectedWorldId, originalX, originalY, originalZ, "
                + "originalWorldName, originalWorldId, region, cost, attempts) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        }

        @Override
        public void write(Connection connection, String tableName, Map<TableObj, TableObj> keyValuePairs) {
            if (keyValuePairs == null || keyValuePairs.isEmpty()) throw new IllegalStateException();
            StringBuilder columns = new StringBuilder();
            StringBuilder values = new StringBuilder();
            List<Object> parameters = new java.util.ArrayList<>();
            for (Map.Entry<TableObj, TableObj> entry : keyValuePairs.entrySet()) {
                columns.append("`").append(entry.getKey().object).append("`,");
                values.append("?,");
                parameters.add(entry.getValue().object);
            }
            columns.setLength(columns.length() - 1);
            values.setLength(values.length() - 1);
            String sql = "MERGE INTO " + tableName + " (" + columns + ") VALUES (" + values + ")";
            try (java.sql.PreparedStatement st = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.size(); i++) st.setObject(i + 1, parameters.get(i));
                st.executeUpdate();
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Optional<Map<String, Object>> read(Connection connection, String tableName, Map.Entry<String, Object> lookup) {
            Map<String, Object> row = new HashMap<>();
            String sql = "SELECT * FROM " + tableName + " WHERE " + lookup.getKey() + " = ?";
            try (java.sql.PreparedStatement st = connection.prepareStatement(sql)) {
                st.setObject(1, lookup.getValue());
                try (java.sql.ResultSet rs = st.executeQuery()) {
                    if (rs.next()) {
                        java.sql.ResultSetMetaData meta = rs.getMetaData();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            Object val = rs.getObject(i);
                            if (val != null) row.put(meta.getColumnName(i), val);
                        }
                        return Optional.of(row);
                    }
                }
            } catch (java.sql.SQLException ignored) {}
            return Optional.empty();
        }

        @Override
        public Connection connect() {
            try {
                return getConnection();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void disconnect(Connection connection) {
            try {
                if (connection != null && !connection.isClosed()) connection.close();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() {
            try {
                if (sharedConnection != null && !sharedConnection.isClosed()) {
                    sharedConnection.close();
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp() throws Exception {
        RTPTestSetup.install(tempDir.toFile());
        // Unique in-memory DB per test to avoid cross-test pollution
        String url = "jdbc:h2:mem:rtp_test_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
        db = new TestH2Accessor(url);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) db.close();
    }

    @Override
    protected DatabaseAccessor<?> accessor() {
        return db;
    }

    // -------------------------------------------------------------------------
    // H2-specific: direct JDBC read / write / delete
    // -------------------------------------------------------------------------

    @Test
    void write_thenRead_returnsRow() throws Exception {
        Connection conn = db.getConnection();

        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> row = new LinkedHashMap<>();
        row.put(new DatabaseAccessor.TableObj("UUID"), new DatabaseAccessor.TableObj("loc-001"));
        row.put(new DatabaseAccessor.TableObj("world"), new DatabaseAccessor.TableObj("world"));
        row.put(new DatabaseAccessor.TableObj("x"), new DatabaseAccessor.TableObj(10));
        row.put(new DatabaseAccessor.TableObj("y"), new DatabaseAccessor.TableObj(64));
        row.put(new DatabaseAccessor.TableObj("z"), new DatabaseAccessor.TableObj(-5));
        row.put(new DatabaseAccessor.TableObj("attempts"), new DatabaseAccessor.TableObj(1));
        row.put(new DatabaseAccessor.TableObj("region"), new DatabaseAccessor.TableObj("default"));
        row.put(new DatabaseAccessor.TableObj("player_uuid"), new DatabaseAccessor.TableObj("shared"));
        row.put(new DatabaseAccessor.TableObj("timestamp"), new DatabaseAccessor.TableObj(System.currentTimeMillis()));
        row.put(new DatabaseAccessor.TableObj("seed"), new DatabaseAccessor.TableObj(12345L));

        db.write(conn, "rtp_cached_locations", row);

        Optional<Map<String, Object>> result = db.read(conn, "rtp_cached_locations",
                new AbstractMap.SimpleEntry<>("UUID", "loc-001"));

        assertTrue(result.isPresent(), "read should return the inserted row");
        assertEquals("world", result.get().get("WORLD"));
    }

    @Test
    void write_thenDelete_rowIsGone() throws Exception {
        Connection conn = db.getConnection();

        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> row = new LinkedHashMap<>();
        row.put(new DatabaseAccessor.TableObj("UUID"), new DatabaseAccessor.TableObj("loc-del"));
        row.put(new DatabaseAccessor.TableObj("world"), new DatabaseAccessor.TableObj("world"));
        row.put(new DatabaseAccessor.TableObj("x"), new DatabaseAccessor.TableObj(0));
        row.put(new DatabaseAccessor.TableObj("y"), new DatabaseAccessor.TableObj(0));
        row.put(new DatabaseAccessor.TableObj("z"), new DatabaseAccessor.TableObj(0));
        row.put(new DatabaseAccessor.TableObj("attempts"), new DatabaseAccessor.TableObj(0));
        row.put(new DatabaseAccessor.TableObj("region"), new DatabaseAccessor.TableObj("default"));
        row.put(new DatabaseAccessor.TableObj("player_uuid"), new DatabaseAccessor.TableObj("shared"));
        row.put(new DatabaseAccessor.TableObj("timestamp"), new DatabaseAccessor.TableObj(0L));
        row.put(new DatabaseAccessor.TableObj("seed"), new DatabaseAccessor.TableObj(0L));

        db.write(conn, "rtp_cached_locations", row);
        db.delete(conn, "rtp_cached_locations", new AbstractMap.SimpleEntry<>("UUID", "loc-del"));

        Optional<Map<String, Object>> result = db.read(conn, "rtp_cached_locations",
                new AbstractMap.SimpleEntry<>("UUID", "loc-del"));
        assertFalse(result.isPresent(), "row should be absent after delete");
    }

    @Test
    void read_nonExistentRow_returnsEmpty() throws Exception {
        Connection conn = db.getConnection();
        Optional<Map<String, Object>> result = db.read(conn, "rtp_cached_locations",
                new AbstractMap.SimpleEntry<>("UUID", "does-not-exist"));
        assertFalse(result.isPresent());
    }

    @Test
    void write_emptyMap_throwsIllegalState() throws Exception {
        Connection conn = db.getConnection();
        assertThrows(IllegalStateException.class,
                () -> db.write(conn, "rtp_cached_locations", new HashMap<>()));
    }

    @Test
    void loadCachedLocations_emptyTable_returnsEmptyList() {
        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("default");
        assertNotNull(locs);
        assertTrue(locs.isEmpty());
    }

    @Test
    void loadCachedLocations_afterInsert_returnsMatchingRows() throws Exception {
        Connection conn = db.getConnection();

        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> row = new LinkedHashMap<>();
        row.put(new DatabaseAccessor.TableObj("UUID"), new DatabaseAccessor.TableObj("loc-load"));
        row.put(new DatabaseAccessor.TableObj("world"), new DatabaseAccessor.TableObj("overworld"));
        row.put(new DatabaseAccessor.TableObj("x"), new DatabaseAccessor.TableObj(100));
        row.put(new DatabaseAccessor.TableObj("y"), new DatabaseAccessor.TableObj(70));
        row.put(new DatabaseAccessor.TableObj("z"), new DatabaseAccessor.TableObj(200));
        row.put(new DatabaseAccessor.TableObj("attempts"), new DatabaseAccessor.TableObj(3));
        row.put(new DatabaseAccessor.TableObj("region"), new DatabaseAccessor.TableObj("spawn"));
        row.put(new DatabaseAccessor.TableObj("player_uuid"), new DatabaseAccessor.TableObj("shared"));
        row.put(new DatabaseAccessor.TableObj("timestamp"), new DatabaseAccessor.TableObj(System.currentTimeMillis()));
        row.put(new DatabaseAccessor.TableObj("seed"), new DatabaseAccessor.TableObj(99L));

        db.write(conn, "rtp_cached_locations", row);

        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("spawn");
        assertEquals(1, locs.size());
        DatabaseAccessor.StoredLocation loc = locs.get(0);
        assertEquals("loc-load", loc.getId());
        assertEquals("overworld", loc.getWorldName());
        assertEquals(100, loc.getX());
        assertEquals(70, loc.getY());
        assertEquals(200, loc.getZ());
        assertEquals(3, loc.getAttempts());
        assertNull(loc.getPlayerId());
    }

    @Test
    void loadCachedLocations_differentRegion_notReturned() throws Exception {
        Connection conn = db.getConnection();

        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> row = new LinkedHashMap<>();
        row.put(new DatabaseAccessor.TableObj("UUID"), new DatabaseAccessor.TableObj("loc-other"));
        row.put(new DatabaseAccessor.TableObj("world"), new DatabaseAccessor.TableObj("nether"));
        row.put(new DatabaseAccessor.TableObj("x"), new DatabaseAccessor.TableObj(0));
        row.put(new DatabaseAccessor.TableObj("y"), new DatabaseAccessor.TableObj(0));
        row.put(new DatabaseAccessor.TableObj("z"), new DatabaseAccessor.TableObj(0));
        row.put(new DatabaseAccessor.TableObj("attempts"), new DatabaseAccessor.TableObj(0));
        row.put(new DatabaseAccessor.TableObj("region"), new DatabaseAccessor.TableObj("nether_region"));
        row.put(new DatabaseAccessor.TableObj("player_uuid"), new DatabaseAccessor.TableObj("shared"));
        row.put(new DatabaseAccessor.TableObj("timestamp"), new DatabaseAccessor.TableObj(0L));
        row.put(new DatabaseAccessor.TableObj("seed"), new DatabaseAccessor.TableObj(0L));

        db.write(conn, "rtp_cached_locations", row);

        List<DatabaseAccessor.StoredLocation> locs = db.loadCachedLocations("overworld_region");
        assertTrue(locs.isEmpty(), "should not return rows from a different region");
    }

    @Test
    void connect_returnsNonNullConnection() {
        Connection conn = db.connect();
        assertNotNull(conn);
    }

    @Test
    void disconnect_closesConnection() throws Exception {
        Connection conn = db.getConnection();
        assertFalse(conn.isClosed());
        db.disconnect(conn);
        assertTrue(conn.isClosed());
    }

    @Test
    void name_returnsTestH2() {
        assertEquals("TestH2", db.name());
    }
}
