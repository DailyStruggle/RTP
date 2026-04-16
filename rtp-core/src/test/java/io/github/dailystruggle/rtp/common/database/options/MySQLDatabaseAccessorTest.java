package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for MySQL-specific read/write logic using Mockito mocks.
 *
 * A testable subclass of {@link AbstractSQLDatabaseAccessor} is used to avoid
 * any real HikariCP / MySQL driver initialisation.  The MySQL-specific
 * {@code read} and {@code write} implementations are copied verbatim from
 * {@link MySQLDatabaseAccessor} so that the same code paths (including every
 * {@code catch (SQLException)} block) are exercised.
 */
class MySQLDatabaseAccessorTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // Testable subclass — mirrors MySQLDatabaseAccessor read/write exactly
    // -------------------------------------------------------------------------

    static class TestableMySQLAccessor extends AbstractSQLDatabaseAccessor {
        private Connection mockConnection;

        TestableMySQLAccessor(Connection mockConnection) {
            this.mockConnection = mockConnection;
        }

        void setMockConnection(Connection c) { this.mockConnection = c; }

        @Override
        public String name() { return "jdbc:mysql://test/testdb"; }

        @Override
        public Connection getConnection() throws SQLException { return mockConnection; }

        @Override
        public void startup() { /* no-op */ }

        @Override
        protected String getInsertStatement() {
            return "INSERT IGNORE INTO rtp_teleport_data "
                + "(senderName, senderId, time, delay, selectedX, selectedY, selectedZ, "
                + "selectedWorldName, selectedWorldId, originalX, originalY, originalZ, "
                + "originalWorldName, originalWorldId, region, cost, attempts) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        }

        /** Exact copy of MySQLDatabaseAccessor.read */
        @Override
        public @org.jetbrains.annotations.NotNull Optional<Map<String, Object>> read(
                Connection connection, String tableName, Map.Entry<String, Object> lookup) {
            Map<String, Object> row = new HashMap<>();
            String sql = "SELECT * FROM " + tableName + " WHERE " + lookup.getKey() + " = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, lookup.getValue());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        ResultSetMetaData metaData = resultSet.getMetaData();
                        int columnCount = metaData.getColumnCount();
                        for (int i = 1; i <= columnCount; i++) {
                            String key = metaData.getColumnName(i);
                            Object object = resultSet.getObject(i);
                            if (object == null) continue;
                            row.put(key, object);
                        }
                        return Optional.of(row);
                    }
                }
            } catch (SQLException ignored) {
            }
            return Optional.empty();
        }

        /** Exact copy of MySQLDatabaseAccessor.write */
        @Override
        public void write(Connection connection, String tableName,
                          Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> keyValuePairs) {
            if (keyValuePairs == null || keyValuePairs.isEmpty()) throw new IllegalStateException();

            StringBuilder columns = new StringBuilder();
            StringBuilder values = new StringBuilder();
            List<Object> parameters = new ArrayList<>();

            for (Map.Entry<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> entry : keyValuePairs.entrySet()) {
                String colName = entry.getKey().object.toString();
                columns.append("`").append(colName).append("`,");
                values.append("?,");
                parameters.add(entry.getValue().object);
            }

            columns.setLength(columns.length() - 1);
            values.setLength(values.length() - 1);

            String sql = "REPLACE INTO " + tableName + " (" + columns + ") VALUES (" + values + ")";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.size(); i++) {
                    statement.setObject(i + 1, parameters.get(i));
                }
                statement.executeUpdate();
            } catch (SQLException e) {
                // logged — swallowed
            }
        }
    }

    private Connection mockConn;
    private TestableMySQLAccessor accessor;

    @BeforeEach
    void setUp() throws SQLException {
        RTPTestSetup.install(tempDir.toFile());
        mockConn = mock(Connection.class);
        accessor = new TestableMySQLAccessor(mockConn);
    }

    @AfterEach
    void tearDown() {
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
        io.github.dailystruggle.rtp.common.RTP.serverAccessor = null;
        io.github.dailystruggle.rtp.common.RTP.scheduler = null;
    }

    // -------------------------------------------------------------------------
    // name()
    // -------------------------------------------------------------------------

    @Test
    void name_returnsExpectedUrl() {
        assertEquals("jdbc:mysql://test/testdb", accessor.name());
    }

    // -------------------------------------------------------------------------
    // read() — happy path
    // -------------------------------------------------------------------------

    @Test
    void read_rowFound_returnsPopulatedMap() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);

        when(mockConn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnName(1)).thenReturn("senderId");
        when(meta.getColumnName(2)).thenReturn("time");
        when(rs.getObject(1)).thenReturn("uuid-1");
        when(rs.getObject(2)).thenReturn(1000L);

        Optional<Map<String, Object>> result =
                accessor.read(mockConn, "rtp_teleport_data",
                        new AbstractMap.SimpleEntry<>("senderId", "uuid-1"));

        assertTrue(result.isPresent());
        assertEquals("uuid-1", result.get().get("senderId"));
        assertEquals(1000L, result.get().get("time"));
    }

    @Test
    void read_noRow_returnsEmpty() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);

        when(mockConn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        Optional<Map<String, Object>> result =
                accessor.read(mockConn, "rtp_teleport_data",
                        new AbstractMap.SimpleEntry<>("senderId", "no-such"));

        assertFalse(result.isPresent());
    }

    @Test
    void read_nullColumnValue_skipsEntry() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);

        when(mockConn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("region");
        when(rs.getObject(1)).thenReturn(null);

        Optional<Map<String, Object>> result =
                accessor.read(mockConn, "rtp_teleport_data",
                        new AbstractMap.SimpleEntry<>("senderId", "uuid-1"));

        // Row was found but the only column was null — map is empty, Optional still present
        assertTrue(result.isPresent());
        assertTrue(result.get().isEmpty());
    }

    // -------------------------------------------------------------------------
    // read() — SQLException coverage
    // -------------------------------------------------------------------------

    @Test
    void read_prepareStatementThrows_returnsEmpty() throws SQLException {
        when(mockConn.prepareStatement(anyString())).thenThrow(new SQLException("prepare failed"));

        Optional<Map<String, Object>> result =
                accessor.read(mockConn, "rtp_teleport_data",
                        new AbstractMap.SimpleEntry<>("senderId", "uuid-1"));

        assertFalse(result.isPresent());
    }

    @Test
    void read_executeQueryThrows_returnsEmpty() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(mockConn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenThrow(new SQLException("query failed"));

        Optional<Map<String, Object>> result =
                accessor.read(mockConn, "rtp_teleport_data",
                        new AbstractMap.SimpleEntry<>("senderId", "uuid-1"));

        assertFalse(result.isPresent());
    }

    // -------------------------------------------------------------------------
    // write() — happy path
    // -------------------------------------------------------------------------

    @Test
    void write_executesUpdate() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(mockConn.prepareStatement(anyString())).thenReturn(ps);

        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> pairs = new LinkedHashMap<>();
        pairs.put(new DatabaseAccessor.TableObj("senderId"), new DatabaseAccessor.TableObj("uuid-1"));
        pairs.put(new DatabaseAccessor.TableObj("time"), new DatabaseAccessor.TableObj(999L));

        accessor.write(mockConn, "rtp_teleport_data", pairs);

        verify(ps).executeUpdate();
    }

    @Test
    void write_nullPairs_throwsIllegalState() {
        assertThrows(IllegalStateException.class,
                () -> accessor.write(mockConn, "rtp_teleport_data", null));
    }

    @Test
    void write_emptyPairs_throwsIllegalState() {
        assertThrows(IllegalStateException.class,
                () -> accessor.write(mockConn, "rtp_teleport_data", new LinkedHashMap<>()));
    }

    // -------------------------------------------------------------------------
    // write() — SQLException coverage
    // -------------------------------------------------------------------------

    @Test
    void write_prepareStatementThrows_doesNotPropagate() throws SQLException {
        when(mockConn.prepareStatement(anyString())).thenThrow(new SQLException("write failed"));

        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> pairs = new LinkedHashMap<>();
        pairs.put(new DatabaseAccessor.TableObj("senderId"), new DatabaseAccessor.TableObj("uuid-1"));

        assertDoesNotThrow(() -> accessor.write(mockConn, "rtp_teleport_data", pairs));
    }

    @Test
    void write_executeUpdateThrows_doesNotPropagate() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(mockConn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenThrow(new SQLException("update failed"));

        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> pairs = new LinkedHashMap<>();
        pairs.put(new DatabaseAccessor.TableObj("senderId"), new DatabaseAccessor.TableObj("uuid-1"));

        assertDoesNotThrow(() -> accessor.write(mockConn, "rtp_teleport_data", pairs));
    }

    // -------------------------------------------------------------------------
    // delete() — inherited from AbstractSQLDatabaseAccessor
    // -------------------------------------------------------------------------

    @Test
    void delete_executesDeleteStatement() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(mockConn.prepareStatement(anyString())).thenReturn(ps);

        accessor.delete(mockConn, "rtp_teleport_data",
                new AbstractMap.SimpleEntry<>("senderId", "uuid-1"));

        verify(ps).executeUpdate();
    }

    @Test
    void delete_sqlException_doesNotPropagate() throws SQLException {
        when(mockConn.prepareStatement(anyString())).thenThrow(new SQLException("delete failed"));

        assertDoesNotThrow(() ->
                accessor.delete(mockConn, "rtp_teleport_data",
                        new AbstractMap.SimpleEntry<>("senderId", "uuid-1")));
    }

    // -------------------------------------------------------------------------
    // connect() / disconnect() — inherited
    // -------------------------------------------------------------------------

    @Test
    void connect_returnsConnectionFromGetConnection() throws SQLException {
        Connection result = accessor.connect();
        assertSame(mockConn, result);
    }

    @Test
    void connect_getConnectionThrows_returnsNull() throws SQLException {
        Connection throwingConn = mock(Connection.class);
        TestableMySQLAccessor bad = new TestableMySQLAccessor(throwingConn) {
            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException("no connection");
            }
        };
        assertNull(bad.connect());
    }

    @Test
    void disconnect_closesOpenConnection() throws SQLException {
        Connection conn = mock(Connection.class);
        when(conn.isClosed()).thenReturn(false);

        accessor.disconnect(conn);

        verify(conn).close();
    }

    @Test
    void disconnect_alreadyClosed_doesNotCallClose() throws SQLException {
        Connection conn = mock(Connection.class);
        when(conn.isClosed()).thenReturn(true);

        accessor.disconnect(conn);

        verify(conn, never()).close();
    }

    @Test
    void disconnect_nullConnection_doesNotThrow() {
        assertDoesNotThrow(() -> accessor.disconnect(null));
    }

    @Test
    void disconnect_closeThrows_doesNotPropagate() throws SQLException {
        Connection conn = mock(Connection.class);
        when(conn.isClosed()).thenReturn(false);
        doThrow(new SQLException("close failed")).when(conn).close();

        assertDoesNotThrow(() -> accessor.disconnect(conn));
    }

    // -------------------------------------------------------------------------
    // flush() — inherited, uses getConnection()
    // -------------------------------------------------------------------------

    @Test
    void flush_emptyQueue_doesNothing() throws SQLException {
        // No items queued — getConnection should never be called
        accessor.flush();
        verify(mockConn, never()).prepareStatement(anyString());
    }

    @Test
    void flush_withData_executesBatch() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(mockConn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeBatch()).thenReturn(new int[]{1});

        TeleportData data = new TeleportData();
        data.completed = true;
        Region region = mock(Region.class);
        region.name = "default";
        data.targetRegion = region;
        accessor.cacheValue(data);

        accessor.flush();

        verify(ps).executeBatch();
        verify(mockConn).commit();
    }

    @Test
    void flush_getConnectionThrows_doesNotPropagate() throws SQLException {
        TestableMySQLAccessor bad = new TestableMySQLAccessor(mockConn) {
            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException("no conn");
            }
        };
        TeleportData data = new TeleportData();
        data.completed = true;
        Region region = mock(Region.class);
        region.name = "default";
        data.targetRegion = region;
        bad.cacheValue(data);

        assertDoesNotThrow(bad::flush);
    }

    @Test
    void flush_executeBatchThrows_rollsBack() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(mockConn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeBatch()).thenThrow(new SQLException("batch failed"));

        TeleportData data = new TeleportData();
        data.completed = true;
        Region region = mock(Region.class);
        region.name = "default";
        data.targetRegion = region;
        accessor.cacheValue(data);

        assertDoesNotThrow(accessor::flush);
        verify(mockConn).rollback();
    }

    // -------------------------------------------------------------------------
    // loadCachedLocations() — inherited, uses getConnection()
    // -------------------------------------------------------------------------

    @Test
    void loadCachedLocations_sqlException_returnsEmptyList() throws SQLException {
        when(mockConn.prepareStatement(anyString())).thenThrow(new SQLException("table missing"));

        List<?> result = accessor.loadCachedLocations("someRegion");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void loadCachedLocations_getConnectionThrows_returnsEmptyList() throws SQLException {
        TestableMySQLAccessor bad = new TestableMySQLAccessor(mockConn) {
            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException("no conn");
            }
        };
        List<?> result = bad.loadCachedLocations("someRegion");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // -------------------------------------------------------------------------
    // cacheValue(TeleportData)
    // -------------------------------------------------------------------------

    @Test
    void cacheValue_teleportData_addsToWriteQueue() {
        TeleportData data = new TeleportData();
        data.completed = true;
        accessor.cacheValue(data);
        assertFalse(accessor.writeQueue.isEmpty());
    }

    // -------------------------------------------------------------------------
    // getInsertStatement()
    // -------------------------------------------------------------------------

    @Test
    void getInsertStatement_containsInsertIgnore() {
        assertTrue(accessor.getInsertStatement().toUpperCase().contains("INSERT IGNORE"));
    }
}
