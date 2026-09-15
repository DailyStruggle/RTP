package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("REQ-RTP-DB-OPT-005: Remote SQL Dialects (MySQL / PostgreSQL) Configuration and Logic Tests")
class RemoteSqlDialectsComprehensiveTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = null;
    }

    @Test
    void testableMySQLAccessor_getInsertStatement_andWriteQuote() throws Exception {
        Connection mockConnection = mock(Connection.class);
        PreparedStatement mockPs = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPs);

        MySQLDatabaseAccessorTest.TestableMySQLAccessor accessor =
                new MySQLDatabaseAccessorTest.TestableMySQLAccessor(mockConnection);

        assertEquals("jdbc:mysql://test/testdb", accessor.name());
        String insertSql = accessor.getInsertStatement();
        assertTrue(insertSql.startsWith("INSERT IGNORE INTO rtp_teleport_data"));

        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> pairs = new LinkedHashMap<>();
        pairs.put(new DatabaseAccessor.TableObj("user_id"), new DatabaseAccessor.TableObj("123"));
        pairs.put(new DatabaseAccessor.TableObj("status"), new DatabaseAccessor.TableObj("active"));

        accessor.write(mockConnection, "my_table", pairs);
        verify(mockConnection).prepareStatement(contains("REPLACE INTO my_table (`user_id`,`status`) VALUES (?,?)"));
        verify(mockPs).setObject(1, "123");
        verify(mockPs).setObject(2, "active");
        verify(mockPs).executeUpdate();
    }

    @Test
    void testablePostgreSQLAccessor_getInsertStatement_andWriteQuote() throws Exception {
        Connection mockConnection = mock(Connection.class);
        PreparedStatement mockPs = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPs);

        PostgreSQLDatabaseAccessorTest.TestablePostgreSQLAccessor accessor =
                new PostgreSQLDatabaseAccessorTest.TestablePostgreSQLAccessor(mockConnection);

        assertEquals("jdbc:postgresql://test/testdb", accessor.name());
        String insertSql = accessor.getInsertStatement();
        assertTrue(insertSql.contains("ON CONFLICT (\"senderId\") DO NOTHING"));

        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> pairs = new LinkedHashMap<>();
        pairs.put(new DatabaseAccessor.TableObj("id"), new DatabaseAccessor.TableObj("456"));
        pairs.put(new DatabaseAccessor.TableObj("score"), new DatabaseAccessor.TableObj(99L));

        accessor.write(mockConnection, "rtp_teleport_data", pairs);
        verify(mockConnection).prepareStatement(contains("INSERT INTO rtp_teleport_data (\"id\",\"score\") VALUES (?,?) ON CONFLICT (\"senderId\") DO UPDATE SET"));
        verify(mockPs).setObject(1, "456");
        verify(mockPs).setObject(2, 99L);
        verify(mockPs).executeUpdate();
    }

    @Test
    void mysql_write_nullOrEmpty_throwsIllegalStateException() {
        Connection mockConnection = mock(Connection.class);
        MySQLDatabaseAccessorTest.TestableMySQLAccessor accessor =
                new MySQLDatabaseAccessorTest.TestableMySQLAccessor(mockConnection);

        assertThrows(IllegalStateException.class, () -> accessor.write(mockConnection, "tbl", null));
        assertThrows(IllegalStateException.class, () -> accessor.write(mockConnection, "tbl", Collections.emptyMap()));
    }

    @Test
    void postgresql_write_nullOrEmpty_throwsIllegalStateException() {
        Connection mockConnection = mock(Connection.class);
        PostgreSQLDatabaseAccessorTest.TestablePostgreSQLAccessor accessor =
                new PostgreSQLDatabaseAccessorTest.TestablePostgreSQLAccessor(mockConnection);

        assertThrows(IllegalStateException.class, () -> accessor.write(mockConnection, "tbl", null));
        assertThrows(IllegalStateException.class, () -> accessor.write(mockConnection, "tbl", Collections.emptyMap()));
    }

    @Test
    void mysql_read_handlesSQLExceptionGracefully() throws Exception {
        Connection mockConnection = mock(Connection.class);
        when(mockConnection.prepareStatement(anyString())).thenThrow(new SQLException("connection lost"));

        MySQLDatabaseAccessorTest.TestableMySQLAccessor accessor =
                new MySQLDatabaseAccessorTest.TestableMySQLAccessor(mockConnection);

        Optional<Map<String, Object>> result = accessor.read(mockConnection, "tbl",
                new AbstractMap.SimpleEntry<>("id", 1));
        assertFalse(result.isPresent());
    }

    @Test
    void postgresql_read_handlesSQLExceptionGracefully() throws Exception {
        Connection mockConnection = mock(Connection.class);
        when(mockConnection.prepareStatement(anyString())).thenThrow(new SQLException("connection lost"));

        PostgreSQLDatabaseAccessorTest.TestablePostgreSQLAccessor accessor =
                new PostgreSQLDatabaseAccessorTest.TestablePostgreSQLAccessor(mockConnection);

        Optional<Map<String, Object>> result = accessor.read(mockConnection, "tbl",
                new AbstractMap.SimpleEntry<>("id", 1));
        assertFalse(result.isPresent());
    }
}
