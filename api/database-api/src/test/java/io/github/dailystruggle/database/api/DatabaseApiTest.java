package io.github.dailystruggle.database.api;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ADR-113 / leafrtp-ADR-079: database-api SqlDialect and SqlDataSourceFactory Tests")
class DatabaseApiTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("SqlDialect resolves names, JDBC URLs, and driver availability")
  void testDialectResolution() {
    assertEquals(SqlDialect.SQLITE, SqlDialect.fromName("sqlite"));
    assertEquals(SqlDialect.MYSQL, SqlDialect.fromName("MySQL"));
    assertEquals(SqlDialect.MARIADB, SqlDialect.fromName("MariaDB"));
    assertEquals(SqlDialect.POSTGRESQL, SqlDialect.fromName("PostgreSQL"));
    assertEquals(SqlDialect.POSTGRESQL, SqlDialect.fromName("postgres"));
    assertEquals(SqlDialect.H2, SqlDialect.fromName("h2"));
    assertEquals(SqlDialect.UNKNOWN, SqlDialect.fromName("unknown"));
    assertEquals(SqlDialect.UNKNOWN, SqlDialect.fromName(null));

    assertEquals(SqlDialect.SQLITE, SqlDialect.fromJdbcUrl("jdbc:sqlite:/tmp/test.db"));
    assertEquals(SqlDialect.MYSQL, SqlDialect.fromJdbcUrl("jdbc:mysql://localhost:3306/db"));
    assertEquals(SqlDialect.MARIADB, SqlDialect.fromJdbcUrl("jdbc:mariadb://localhost:3306/db"));
    assertEquals(
        SqlDialect.POSTGRESQL, SqlDialect.fromJdbcUrl("jdbc:postgresql://localhost:5432/db"));
    assertEquals(SqlDialect.H2, SqlDialect.fromJdbcUrl("jdbc:h2:mem:test"));
    assertEquals(SqlDialect.UNKNOWN, SqlDialect.fromJdbcUrl(""));

    assertTrue(SqlDialect.H2.isDriverAvailable());
    assertTrue(SqlDialect.SQLITE.isDriverAvailable());
    assertTrue(SqlDialect.UNKNOWN.isDriverAvailable());
    assertFalse(SqlDialect.isDriverAvailable("com.example.NonExistentDriver"));
  }

  @Test
  @DisplayName("SqlDialect builds portable UPSERT and monotonic UPSERT statements")
  void testUpsertSqlGeneration() {
    List<String> cols = List.of("id", "val", "updated_at");
    List<String> pk = List.of("id");
    List<String> updates = List.of("val", "updated_at");

    String mysqlUpsert = SqlDialect.MYSQL.buildUpsertSql("tbl", cols, pk, updates);
    assertTrue(mysqlUpsert.contains("ON DUPLICATE KEY UPDATE val = VALUES(val)"));

    String pgUpsert = SqlDialect.POSTGRESQL.buildUpsertSql("tbl", cols, pk, updates);
    assertTrue(pgUpsert.contains("ON CONFLICT(id) DO UPDATE SET val = excluded.val"));

    String h2Upsert = SqlDialect.H2.buildUpsertSql("tbl", cols, pk, updates);
    assertTrue(h2Upsert.startsWith("MERGE INTO tbl"));

    String pgMonotonic =
        SqlDialect.POSTGRESQL.buildMonotonicUpsertSql(
            "epochs", List.of("group_id", "epoch", "ts"), "group_id", "epoch", List.of("ts"));
    assertTrue(pgMonotonic.contains("GREATEST(epochs.epoch, excluded.epoch)"));

    String sqliteMonotonic =
        SqlDialect.SQLITE.buildMonotonicUpsertSql(
            "epochs", List.of("group_id", "epoch", "ts"), "group_id", "epoch", List.of("ts"));
    assertTrue(sqliteMonotonic.contains("MAX(epoch, excluded.epoch)"));

    String mysqlMonotonic =
        SqlDialect.MYSQL.buildMonotonicUpsertSql(
            "epochs", List.of("group_id", "epoch", "ts"), "group_id", "epoch", List.of("ts"));
    assertTrue(mysqlMonotonic.contains("GREATEST(epoch, VALUES(epoch))"));
  }

  @Test
  @DisplayName("SqlDataSourceFactory creates pooled SQLite WAL and H2 data sources and recycles autoCommit")
  void testSqliteAndH2DataSources() throws SQLException {
    Path dbFile = tempDir.resolve("nested/sleep.db");
    try (CloseableDataSource sqliteDs = SqlDataSourceFactory.createSqlite(dbFile)) {
      assertFalse(sqliteDs.isClosed());
      try (Connection c = sqliteDs.getConnection()) {
        assertEquals(SqlDialect.SQLITE, SqlDialect.fromConnection(c));
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
          st.execute("CREATE TABLE IF NOT EXISTS items (id TEXT PRIMARY KEY, qty INTEGER)");
        }
        c.commit();
      }
      assertEquals(0, sqliteDs.getActiveCount());
      assertEquals(1, sqliteDs.getIdleCount());

      String upsert =
          SqlDialect.SQLITE.buildUpsertSql(
              "items", List.of("id", "qty"), List.of("id"), List.of("qty"));
      try (Connection c2 = sqliteDs.getConnection();
          PreparedStatement ps = c2.prepareStatement(upsert)) {
        assertTrue(c2.getAutoCommit(), "Recycled connection must have autoCommit restored to true");
        ps.setString(1, "apple");
        ps.setInt(2, 5);
        ps.executeUpdate();
      }

      try (Connection c3 = sqliteDs.getConnection();
          PreparedStatement ps = c3.prepareStatement("SELECT qty FROM items WHERE id = ?")) {
        ps.setString(1, "apple");
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(5, rs.getInt(1));
        }
      }
    }
  }
}
