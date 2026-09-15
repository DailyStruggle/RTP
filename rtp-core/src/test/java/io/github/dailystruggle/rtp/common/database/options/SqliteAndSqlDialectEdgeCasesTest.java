package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("REQ-RTP-DB-OPT-004: SQLite and SQL Dialect Options Edge Cases")
class SqliteAndSqlDialectEdgeCasesTest {

    @TempDir
    Path tempDir;

    private SQLiteDatabaseAccessor sqliteAccessor;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        sqliteAccessor = new SQLiteDatabaseAccessor("ignored");
    }

    @AfterEach
    void tearDown() {
        if (sqliteAccessor != null) {
            try {
                sqliteAccessor.close();
            } catch (Throwable ignored) {}
        }
    }

    @Test
    void sqlite_getConnection_createsConnectionAndPragmas() throws Exception {
        Connection conn = sqliteAccessor.getConnection();
        assertNotNull(conn);
        assertFalse(conn.isClosed());
        assertSame(conn, sqliteAccessor.getConnection());

        // Test disconnect is no-op on shared connection
        sqliteAccessor.disconnect(conn);
        assertFalse(conn.isClosed());
    }

    @Test
    void sqlite_startup_and_purgeStaleLocations() {
        assertDoesNotThrow(() -> sqliteAccessor.startup());
    }

    @Test
    void sqlite_readAndWrite_operations() throws Exception {
        Connection conn = sqliteAccessor.getConnection();
        Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> pairs = new HashMap<>();
        pairs.put(new DatabaseAccessor.TableObj("UUID"), new DatabaseAccessor.TableObj("loc-sql-1"));
        pairs.put(new DatabaseAccessor.TableObj("world"), new DatabaseAccessor.TableObj("world"));
        pairs.put(new DatabaseAccessor.TableObj("x"), new DatabaseAccessor.TableObj(15));
        pairs.put(new DatabaseAccessor.TableObj("y"), new DatabaseAccessor.TableObj(64));
        pairs.put(new DatabaseAccessor.TableObj("z"), new DatabaseAccessor.TableObj(-20));
        pairs.put(new DatabaseAccessor.TableObj("attempts"), new DatabaseAccessor.TableObj(1));
        pairs.put(new DatabaseAccessor.TableObj("region"), new DatabaseAccessor.TableObj("default"));
        pairs.put(new DatabaseAccessor.TableObj("player_uuid"), new DatabaseAccessor.TableObj("shared"));
        pairs.put(new DatabaseAccessor.TableObj("timestamp"), new DatabaseAccessor.TableObj(System.currentTimeMillis()));
        pairs.put(new DatabaseAccessor.TableObj("seed"), new DatabaseAccessor.TableObj(12345L));

        sqliteAccessor.write(conn, "rtp_cached_locations", pairs);

        Optional<Map<String, Object>> read = sqliteAccessor.read(conn, "rtp_cached_locations",
                new AbstractMap.SimpleEntry<>("UUID", "loc-sql-1"));
        assertTrue(read.isPresent());
        assertEquals("world", read.get().get("world"));
    }

    @Test
    void sqlite_write_emptyOrNullPairs_throwsIllegalStateException() throws Exception {
        Connection conn = sqliteAccessor.getConnection();
        assertThrows(IllegalStateException.class, () -> sqliteAccessor.write(conn, "table", null));
        assertThrows(IllegalStateException.class, () -> sqliteAccessor.write(conn, "table", Collections.emptyMap()));
    }

    @Test
    void sqlite_getInsertStatement_returnsInsertOrIgnore() {
        String insert = sqliteAccessor.getInsertStatement();
        assertNotNull(insert);
        assertTrue(insert.startsWith("INSERT OR IGNORE INTO rtp_teleport_data"));
    }
}
