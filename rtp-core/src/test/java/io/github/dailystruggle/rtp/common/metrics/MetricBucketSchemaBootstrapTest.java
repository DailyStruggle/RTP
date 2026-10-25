package io.github.dailystruggle.rtp.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.metrics.MetricBucketRow;
import io.github.dailystruggle.rtp.common.database.options.AbstractSQLDatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.H2DatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.RedisMetricBucketAccessor;
import io.github.dailystruggle.rtp.common.database.options.SQLiteDatabaseAccessor;
import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@code REQ-RTP-METTS-008}: Schema bootstrap is idempotent across PostgreSQL, MySQL,
 * H2, and SQLite drivers and the Redis layout matches the documented sorted-set shape
 * ({@code ZADD rtp:metric:<backend_id>:<metric_key> <window_start_epoch> <packed>} with
 * {@code EXPIRE} and {@code ZRANGEBYSCORE}).
 */
@DisplayName("REQ-RTP-METTS-008: MetricBucketSchemaBootstrapTest")
class MetricBucketSchemaBootstrapTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("REQ-RTP-METTS-008: SQLite, H2, MySQL-mode, and PostgreSQL-mode schema bootstrap are idempotent across repeated invocations")
  void sqlDialectsBootstrapSchemaIdempotently() throws SQLException {
    // 1. SQLite
    File sqliteFile = tempDir.resolve("bootstrap_sqlite.db").toFile();
    SQLiteDatabaseAccessor sqlite =
        new SQLiteDatabaseAccessor("jdbc:sqlite:" + sqliteFile.getAbsolutePath());
    try {
      sqlite.ensureMetricBucketSchema();
      sqlite.ensureMetricBucketSchema();
      assertTrue(sqlite.getMetricBucketInsertStatement().startsWith("INSERT OR IGNORE INTO rtp_metric_bucket"));
      MetricBucketRow row =
          new MetricBucketRow("surv-1", "mspt", 1_700_000_000L, 60, 60, 12.0, 9.0, 18.0, 16.0, 1L);
      assertEquals(1, sqlite.insertMetricBuckets(List.of(row)));
      assertEquals(1, sqlite.queryMetricBuckets("surv-1", "mspt", 0L, 2_000_000_000L).size());
    } finally {
      sqlite.close();
    }

    // 2. H2
    File h2File = tempDir.resolve("bootstrap_h2").toFile();
    H2DatabaseAccessor h2 =
        new H2DatabaseAccessor(
            "jdbc:h2:" + h2File.getAbsolutePath() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    try {
      h2.ensureMetricBucketSchema();
      h2.ensureMetricBucketSchema();
      assertTrue(h2.getMetricBucketInsertStatement().startsWith("INSERT IGNORE INTO rtp_metric_bucket"));
    } finally {
      h2.close();
    }

    // 3. MySQL dialect DDL + INSERT IGNORE (executed against H2 in MySQL compatibility mode)
    try (Connection mysqlConn =
        DriverManager.getConnection("jdbc:h2:mem:mysql_dialect_test;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
      AbstractSQLDatabaseAccessor mysqlDialect =
          dialectAccessor(
              "mysql",
              mysqlConn,
              "CREATE TABLE IF NOT EXISTS rtp_metric_bucket ("
                  + "backend_id VARCHAR(128) NOT NULL, "
                  + "metric_key VARCHAR(128) NOT NULL, "
                  + "window_start_epoch BIGINT NOT NULL, "
                  + "window_seconds INT NOT NULL, "
                  + "sample_count INT NOT NULL, "
                  + "mean DOUBLE NOT NULL, "
                  + "min DOUBLE NOT NULL, "
                  + "max DOUBLE NOT NULL, "
                  + "p95 DOUBLE, "
                  + "generation BIGINT NOT NULL, "
                  + "PRIMARY KEY (backend_id, metric_key, window_start_epoch), "
                  + "INDEX idx_rtp_metric_bucket_key_epoch (metric_key, window_start_epoch)"
                  + ");");
      mysqlDialect.ensureMetricBucketSchema();
      mysqlDialect.ensureMetricBucketSchema();
      assertTrue(
          mysqlDialect.getMetricBucketInsertStatement().contains("INSERT IGNORE INTO rtp_metric_bucket"));
      MetricBucketRow row =
          new MetricBucketRow("surv-mysql", "mspt", 1_700_000_060L, 60, 60, 14.0, 10.0, 20.0, 19.0, 2L);
      mysqlDialect.insertMetricBuckets(List.of(row));
      mysqlDialect.insertMetricBuckets(List.of(row));
      assertEquals(1, mysqlDialect.queryMetricBuckets("surv-mysql", "mspt", 0L, 2_000_000_000L).size());
    }

    // 4. PostgreSQL dialect DDL + ON CONFLICT DO NOTHING (executed against H2 in PostgreSQL compatibility mode)
    try (Connection pgConn =
        DriverManager.getConnection(
            "jdbc:h2:mem:pg_dialect_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")) {
      AbstractSQLDatabaseAccessor pgDialect = dialectAccessor("postgresql", pgConn, null);
      pgDialect.ensureMetricBucketSchema();
      pgDialect.ensureMetricBucketSchema();
      assertTrue(
          pgDialect
              .getMetricBucketInsertStatement()
              .contains("ON CONFLICT (backend_id, metric_key, window_start_epoch) DO NOTHING"));
      MetricBucketRow row =
          new MetricBucketRow("surv-pg", "mspt", 1_700_000_120L, 60, 60, 11.0, 7.0, 16.0, 15.0, 3L);
      pgDialect.insertMetricBuckets(List.of(row));
      pgDialect.insertMetricBuckets(List.of(row));
      assertEquals(1, pgDialect.queryMetricBuckets("surv-pg", "mspt", 0L, 2_000_000_000L).size());
    }
  }

  @Test
  @DisplayName("REQ-RTP-METTS-008: Redis sorted-set layout issues ZADD rtp:metric:<backend_id>:<metric_key> <window_start_epoch> <packed>, EXPIRE, and ZRANGEBYSCORE")
  void redisSortedSetLayoutMatchesSpecification() {
    List<List<String>> commands = new ArrayList<>();
    MetricBucketRow row =
        new MetricBucketRow("surv-eu-1", "mspt", 1_700_000_000L, 60, 60, 13.5, 9.0, 21.0, 19.5, 4L);

    RedisMetricBucketAccessor redis =
        new RedisMetricBucketAccessor(
            args -> {
              commands.add(List.of(args));
              if ("ZRANGEBYSCORE".equals(args[0])) {
                // Return empty on first check before ZADD, then return packed member on rangeScan
                boolean afterZadd =
                    commands.stream().anyMatch(c -> "ZADD".equals(c.get(0)));
                return afterZadd ? List.of(row.packRedisMember()) : List.of();
              }
              return 1L;
            });

    long retentionSec = 7L * 86_400L;
    redis.insertMetricBuckets(List.of(row), retentionSec);

    List<String> zaddCmd =
        commands.stream().filter(c -> "ZADD".equals(c.get(0))).findFirst().orElseThrow();
    assertEquals(
        List.of("ZADD", "rtp:metric:surv-eu-1:mspt", "1700000000", row.packRedisMember()),
        zaddCmd);

    List<String> expireCmd =
        commands.stream().filter(c -> "EXPIRE".equals(c.get(0))).findFirst().orElseThrow();
    assertEquals(
        List.of("EXPIRE", "rtp:metric:surv-eu-1:mspt", String.valueOf(retentionSec)),
        expireCmd);

    List<MetricBucketRow> scanned =
        redis.queryMetricBuckets("surv-eu-1", "mspt", 1_700_000_000L, 1_700_000_060L);
    assertEquals(1, scanned.size());
    assertEquals(row, scanned.get(0));
  }

  private static AbstractSQLDatabaseAccessor dialectAccessor(
      String dialectName, Connection sharedConnection, String customTableDdl) {
    return new AbstractSQLDatabaseAccessor() {
      @Override
      public String name() {
        return dialectName;
      }

      @Override
      public Connection getConnection() {
        return sharedConnection;
      }

      @Override
      public void disconnect(Connection c) {
        // Keep shared in-memory connection open for test assertions
      }

      @Override
      protected String getInsertStatement() {
        return "";
      }

      @Override
      public String getMetricBucketTableDdl() {
        return customTableDdl != null ? customTableDdl : super.getMetricBucketTableDdl();
      }

      @Override
      public void write(
          Connection d, String tableName, Map<TableObj, TableObj> keyValuePairs) {}
    };
  }
}
