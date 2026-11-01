package io.github.dailystruggle.rtp.proxy.common.transport.sql;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlNetworkStateSchemaUnitTest {

    @Test
    void bootstrap_success_executesStatements() throws SQLException {
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.execute(anyString())).thenReturn(true);

        assertDoesNotThrow(() -> SqlNetworkStateSchema.bootstrap(conn));
    }

    @Test
    void bootstrap_duplicateTableOrColumn_isIgnored() throws SQLException {
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);

        // Throw SQLState 42S01 (table exists) or 42S21 (duplicate column)
        when(stmt.execute(anyString()))
                .thenThrow(new SQLException("Table exists", "42S01"))
                .thenThrow(new SQLException("Duplicate column", "42S21"))
                .thenThrow(new SQLException("Generic 42xxx syntax / already exists", "42000"))
                .thenThrow(new SQLException("Derby exists", "X0Y32"))
                .thenReturn(true);

        assertDoesNotThrow(() -> SqlNetworkStateSchema.bootstrap(conn));
    }

    @Test
    void bootstrap_fatalSqlException_throws() throws SQLException {
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);

        // Fatal SQLState e.g. 08001 (unable to connect) or 28000 (invalid auth)
        when(stmt.execute(anyString())).thenThrow(new SQLException("Fatal connection error", "08001"));

        assertThrows(SQLException.class, () -> SqlNetworkStateSchema.bootstrap(conn));
    }
}
