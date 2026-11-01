package io.github.dailystruggle.database.api;

import io.github.dailystruggle.rtp.common.database.pool.MiniConnectionPool;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/**
 * Factory for creating pooled and single-writer {@link CloseableDataSource} instances across
 * supported {@link SqlDialect}s.
 *
 * <p>For {@link SqlDialect#SQLITE}, the returned pool caps concurrency at {@code 1} physical
 * connection initialized with {@code PRAGMA journal_mode=WAL}, {@code synchronous=NORMAL}, and
 * {@code busy_timeout=5000}, eliminating file-handle churn and {@code SQLITE_BUSY} contention while
 * allowing callers to use standard {@code try (Connection c = dataSource.getConnection())} blocks.
 *
 * @governedBy ADR-113 (Java 17 Bytecode Baseline)
 * @governedBy ADR-115 (Package DAG Disentanglement and Finite Decision-Preservation Hypergraph)
 * @governedBy leafrtp-ADR-079 (In-House SQL Connection Pool and Retirement of HikariCP)
 * @trace S-004
 * @trace S-005
 */
public final class SqlDataSourceFactory {

  private static final int DEFAULT_NETWORK_POOL_SIZE = 8;

  private SqlDataSourceFactory() {}

  /**
   * Creates a managed {@link CloseableDataSource} for a local SQLite database file with {@code WAL}
   * mode enabled and a single reusable connection.
   *
   * @param sqliteFile path to the SQLite file
   * @return managed closeable data source
   */
  public static CloseableDataSource createSqlite(Path sqliteFile) {
    String url = SqlDialect.buildSqliteUrl(sqliteFile);
    return create(SqlDialect.SQLITE, url, null, null, 1);
  }

  /**
   * Creates a managed {@link CloseableDataSource} for the given dialect, host, port, database, and
   * credentials using the default pool size.
   *
   * @param dialect SQL dialect
   * @param host server hostname
   * @param port server port
   * @param database database name
   * @param username username (optional)
   * @param password password (optional)
   * @return managed closeable data source
   */
  public static CloseableDataSource create(
      SqlDialect dialect,
      String host,
      int port,
      String database,
      String username,
      String password) {
    Objects.requireNonNull(dialect, "dialect");
    String url = dialect.buildJdbcUrl(host, port, database);
    int poolSize = dialect == SqlDialect.SQLITE ? 1 : DEFAULT_NETWORK_POOL_SIZE;
    return create(dialect, url, username, password, poolSize);
  }

  /**
   * Creates a managed {@link CloseableDataSource} for an explicit JDBC URL and pool size.
   *
   * @param dialect SQL dialect (auto-detected from {@code jdbcUrl} if {@code null} or {@link
   *     SqlDialect#UNKNOWN})
   * @param jdbcUrl JDBC connection URL
   * @param username username (nullable)
   * @param password password (nullable)
   * @param maxPoolSize maximum pool size (forced to {@code 1} for {@link SqlDialect#SQLITE})
   * @return managed closeable data source
   */
  public static CloseableDataSource create(
      SqlDialect dialect, String jdbcUrl, String username, String password, int maxPoolSize) {
    Objects.requireNonNull(jdbcUrl, "jdbcUrl");
    SqlDialect resolved =
        (dialect == null || dialect == SqlDialect.UNKNOWN)
            ? SqlDialect.fromJdbcUrl(jdbcUrl)
            : dialect;
    resolved.ensureDriverRegistered();
    int effectivePoolSize =
        resolved == SqlDialect.SQLITE ? 1 : Math.max(1, maxPoolSize);
    Properties props = new Properties();
    if (username != null && !username.isEmpty()) {
      props.setProperty("user", username);
    }
    if (password != null && !password.isEmpty()) {
      props.setProperty("password", password);
    }
    return new MiniConnectionPool(
        jdbcUrl, props, effectivePoolSize, resolved::configureConnection);
  }
}
