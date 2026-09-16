package io.github.dailystruggle.rtp.proxy.common.transport.sql;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlNetworkStateBindingDialectTest {

    @Test
    void dialectOf_detectsAllDialects() throws SQLException {
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(meta);

        when(meta.getDatabaseProductName()).thenReturn("H2 Database");
        assertEquals(SqlNetworkStateBinding.Dialect.H2, SqlNetworkStateBinding.dialectOf(conn));

        when(meta.getDatabaseProductName()).thenReturn("MySQL");
        assertEquals(SqlNetworkStateBinding.Dialect.MYSQL, SqlNetworkStateBinding.dialectOf(conn));

        when(meta.getDatabaseProductName()).thenReturn("MariaDB Server");
        assertEquals(SqlNetworkStateBinding.Dialect.MYSQL, SqlNetworkStateBinding.dialectOf(conn));

        when(meta.getDatabaseProductName()).thenReturn("PostgreSQL");
        assertEquals(SqlNetworkStateBinding.Dialect.POSTGRES, SqlNetworkStateBinding.dialectOf(conn));

        when(meta.getDatabaseProductName()).thenReturn("SQLite 3");
        assertEquals(SqlNetworkStateBinding.Dialect.SQLITE, SqlNetworkStateBinding.dialectOf(conn));

        when(meta.getDatabaseProductName()).thenReturn("CustomDB");
        assertEquals(SqlNetworkStateBinding.Dialect.UNKNOWN, SqlNetworkStateBinding.dialectOf(conn));

        when(meta.getDatabaseProductName()).thenReturn(null);
        assertEquals(SqlNetworkStateBinding.Dialect.UNKNOWN, SqlNetworkStateBinding.dialectOf(conn));
    }
}
