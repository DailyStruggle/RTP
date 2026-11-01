package io.github.dailystruggle.database.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Canonical SQL dialect descriptor providing driver availability probing, JDBC URL construction,
 * connection pragma initialization, and portable UPSERT statement generation across {@code SQLite},
 * {@code MySQL}, {@code MariaDB}, {@code PostgreSQL}, and {@code H2}.
 *
 * @governedBy ADR-113 (Java 17 Bytecode Baseline)
 * @governedBy ADR-115 (Package DAG Disentanglement and Finite Decision-Preservation Hypergraph)
 * @governedBy leafrtp-ADR-079 (In-House SQL Connection Pool and Retirement of HikariCP)
 * @trace S-004
 * @trace S-005
 */
public enum SqlDialect {
  SQLITE("org.sqlite.JDBC"),
  MYSQL("com.mysql.cj.jdbc.Driver"),
  MARIADB("org.mariadb.jdbc.Driver"),
  POSTGRESQL("org.postgresql.Driver"),
  H2("org.h2.Driver"),
  UNKNOWN(null);

  private final String driverClass;

  SqlDialect(String driverClass) {
    this.driverClass = driverClass;
  }

  /**
   * Returns the primary JDBC driver class name for this dialect, or {@code null} for {@link
   * #UNKNOWN}.
   *
   * @return driver class FQN or {@code null}
   */
  public String driverClass() {
    return driverClass;
  }

  /**
   * Probes whether the JDBC driver for this dialect is reachable on the classloader. For {@link
   * #MARIADB}, also accepts the MySQL Connector/J driver as a wire-compatible fallback.
   *
   * @return {@code true} if the driver class can be resolved
   */
  public boolean isDriverAvailable() {
    if (driverClass == null) {
      return true;
    }
    if (isDriverAvailable(driverClass)) {
      return true;
    }
    return this == MARIADB && isDriverAvailable(MYSQL.driverClass);
  }

  /**
   * Loads and initializes the JDBC driver class for this dialect so its static {@link
   * java.sql.DriverManager} registration block executes before opening connections.
   */
  public void ensureDriverRegistered() {
    if (driverClass == null) {
      return;
    }
    try {
      Class.forName(driverClass);
      return;
    } catch (Throwable ignored) {
    }
    if (this == MARIADB) {
      try {
        Class.forName(MYSQL.driverClass);
      } catch (Throwable ignored) {
      }
    }
  }

  /**
   * Probes whether a specific JDBC driver class name is reachable on the classloader.
   *
   * @param driverClassName the driver class FQN, or {@code null}
   * @return {@code true} if reachable or {@code driverClassName} is {@code null}
   */
  public static boolean isDriverAvailable(String driverClassName) {
    if (driverClassName == null) {
      return true;
    }
    if (driverClassName.isBlank()) {
      return false;
    }
    try {
      Class.forName(driverClassName, false, SqlDialect.class.getClassLoader());
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }

  /**
   * Resolves a {@link SqlDialect} from a configuration name such as {@code "sqlite"}, {@code
   * "mysql"}, {@code "mariadb"}, {@code "postgres"}, {@code "postgresql"}, or {@code "h2"}.
   *
   * @param raw configuration type name
   * @return resolved dialect, or {@link #UNKNOWN}
   */
  public static SqlDialect fromName(String raw) {
    if (raw == null || raw.isBlank()) {
      return UNKNOWN;
    }
    String lower = raw.trim().toLowerCase(Locale.ROOT);
    return switch (lower) {
      case "sqlite", "sqlite3" -> SQLITE;
      case "mysql" -> MYSQL;
      case "mariadb" -> MARIADB;
      case "postgres", "postgresql", "pg" -> POSTGRESQL;
      case "h2" -> H2;
      default -> UNKNOWN;
    };
  }

  /**
   * Resolves a {@link SqlDialect} from a JDBC URL prefix.
   *
   * @param jdbcUrl JDBC connection string
   * @return resolved dialect, or {@link #UNKNOWN}
   */
  public static SqlDialect fromJdbcUrl(String jdbcUrl) {
    if (jdbcUrl == null || jdbcUrl.isBlank()) {
      return UNKNOWN;
    }
    String lower = jdbcUrl.trim().toLowerCase(Locale.ROOT);
    if (lower.startsWith("jdbc:sqlite:")) return SQLITE;
    if (lower.startsWith("jdbc:mariadb:")) return MARIADB;
    if (lower.startsWith("jdbc:mysql:")) return MYSQL;
    if (lower.startsWith("jdbc:postgresql:")) return POSTGRESQL;
    if (lower.startsWith("jdbc:h2:")) return H2;
    return UNKNOWN;
  }

  /**
   * Detects the {@link SqlDialect} from an open {@link Connection}'s {@link DatabaseMetaData}.
   *
   * @param connection open JDBC connection
   * @return detected dialect, or {@link #UNKNOWN}
   * @throws SQLException if metadata inspection fails
   */
  public static SqlDialect fromConnection(Connection connection) throws SQLException {
    if (connection == null) {
      return UNKNOWN;
    }
    DatabaseMetaData meta = connection.getMetaData();
    if (meta == null) {
      return UNKNOWN;
    }
    String product = meta.getDatabaseProductName();
    if (product == null) {
      return UNKNOWN;
    }
    String lower = product.toLowerCase(Locale.ROOT);
    if (lower.contains("h2")) return H2;
    if (lower.contains("mariadb")) return MARIADB;
    if (lower.contains("mysql")) return MYSQL;
    if (lower.contains("postgres")) return POSTGRESQL;
    if (lower.contains("sqlite")) return SQLITE;
    return UNKNOWN;
  }

  /**
   * Builds a tuned JDBC URL for network-backed dialects ({@link #MYSQL}, {@link #MARIADB}, {@link
   * #POSTGRESQL}) or embedded dialects ({@link #H2}, {@link #SQLITE}).
   *
   * @param host database host
   * @param port database port
   * @param database database name or file path
   * @return formatted JDBC URL
   */
  public String buildJdbcUrl(String host, int port, String database) {
    String safeHost = (host == null || host.isBlank()) ? "127.0.0.1" : host.trim();
    String safeDb = (database == null || database.isBlank()) ? "minecraft" : database.trim();
    return switch (this) {
      case MYSQL ->
          "jdbc:mysql://"
              + safeHost
              + ":"
              + (port > 0 ? port : 3306)
              + "/"
              + safeDb
              + "?useSSL=false&autoReconnect=true&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048";
      case MARIADB -> {
        String subprotocol =
            !isDriverAvailable(MARIADB.driverClass) && isDriverAvailable(MYSQL.driverClass)
                ? "mysql"
                : "mariadb";
        yield "jdbc:"
            + subprotocol
            + "://"
            + safeHost
            + ":"
            + (port > 0 ? port : 3306)
            + "/"
            + safeDb
            + "?useSSL=false&autoReconnect=true&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048";
      }
      case POSTGRESQL ->
          "jdbc:postgresql://"
              + safeHost
              + ":"
              + (port > 0 ? port : 5432)
              + "/"
              + safeDb
              + "?cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048";
      case H2 -> "jdbc:h2:mem:" + safeDb + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
      case SQLITE, UNKNOWN -> "jdbc:sqlite:" + safeDb;
    };
  }

  /**
   * Builds a {@code jdbc:sqlite:} URL for the target file path, creating parent directories if
   * necessary.
   *
   * @param sqliteFile path to the SQLite database file
   * @return {@code jdbc:sqlite:<absolutePath>}
   */
  public static String buildSqliteUrl(Path sqliteFile) {
    Objects.requireNonNull(sqliteFile, "sqliteFile");
    Path abs = sqliteFile.toAbsolutePath();
    Path parent = abs.getParent();
    if (parent != null && !Files.exists(parent)) {
      try {
        Files.createDirectories(parent);
      } catch (IOException ignored) {
        // Defer directory failure to JDBC open so caller handles SQLException uniformly.
      }
    }
    return "jdbc:sqlite:" + abs;
  }

  /**
   * Applies dialect-specific session pragmas on a newly opened physical {@link Connection}. For
   * {@link #SQLITE}, enables {@code WAL} journal mode, {@code synchronous=NORMAL}, and a {@code
   * 5000ms} busy timeout to prevent {@code SQLITE_BUSY} lock failures under concurrent access.
   *
   * @param connection newly opened physical connection
   * @throws SQLException if pragma execution fails
   */
  public void configureConnection(Connection connection) throws SQLException {
    if (connection == null) {
      return;
    }
    if (this == SQLITE) {
      try (Statement stmt = connection.createStatement()) {
        stmt.execute("PRAGMA journal_mode=WAL;");
        stmt.execute("PRAGMA synchronous=NORMAL;");
        stmt.execute("PRAGMA busy_timeout=5000;");
      }
    }
  }

  /**
   * Generates a parameterized UPSERT statement for the given table, column list, primary/conflict
   * key columns, and mutable update columns.
   *
   * @param table sanitized table name
   * @param columns all inserted columns in parameter order
   * @param conflictColumns primary-key or unique conflict columns
   * @param updateColumns columns to overwrite on conflict
   * @return dialect-specific parameterized SQL string
   */
  public String buildUpsertSql(
      String table,
      List<String> columns,
      List<String> conflictColumns,
      List<String> updateColumns) {
    Objects.requireNonNull(table, "table");
    Objects.requireNonNull(columns, "columns");
    Objects.requireNonNull(conflictColumns, "conflictColumns");
    Objects.requireNonNull(updateColumns, "updateColumns");
    if (columns.isEmpty() || conflictColumns.isEmpty() || updateColumns.isEmpty()) {
      throw new IllegalArgumentException("Upsert columns and conflict keys must not be empty");
    }

    String colList = String.join(", ", columns);
    StringJoiner placeholders = new StringJoiner(", ");
    for (int i = 0; i < columns.size(); i++) {
      placeholders.add("?");
    }

    return switch (this) {
      case MYSQL, MARIADB -> {
        StringJoiner updates = new StringJoiner(", ");
        for (String col : updateColumns) {
          updates.add(col + " = VALUES(" + col + ")");
        }
        yield "INSERT INTO "
            + table
            + " ("
            + colList
            + ") VALUES ("
            + placeholders
            + ") ON DUPLICATE KEY UPDATE "
            + updates;
      }
      case H2 ->
          "MERGE INTO "
              + table
              + " ("
              + colList
              + ") KEY("
              + String.join(", ", conflictColumns)
              + ") VALUES ("
              + placeholders
              + ")";
      case SQLITE, POSTGRESQL, UNKNOWN -> {
        StringJoiner updates = new StringJoiner(", ");
        for (String col : updateColumns) {
          updates.add(col + " = excluded." + col);
        }
        yield "INSERT INTO "
            + table
            + " ("
            + colList
            + ") VALUES ("
            + placeholders
            + ") ON CONFLICT("
            + String.join(", ", conflictColumns)
            + ") DO UPDATE SET "
            + updates;
      }
    };
  }

  /**
   * Generates a parameterized monotonic high-water-mark UPSERT statement where {@code
   * monotonicColumn} advances only to {@code GREATEST(existing, incoming)} (or {@code MAX} on
   * SQLite).
   *
   * @param table sanitized table name
   * @param columns all inserted columns in parameter order
   * @param conflictColumn primary-key column name
   * @param monotonicColumn numeric high-water-mark column that must never decrease
   * @param overwriteColumns additional columns updated on conflict
   * @return dialect-specific parameterized SQL string
   */
  public String buildMonotonicUpsertSql(
      String table,
      List<String> columns,
      String conflictColumn,
      String monotonicColumn,
      List<String> overwriteColumns) {
    Objects.requireNonNull(table, "table");
    Objects.requireNonNull(columns, "columns");
    Objects.requireNonNull(conflictColumn, "conflictColumn");
    Objects.requireNonNull(monotonicColumn, "monotonicColumn");
    Objects.requireNonNull(overwriteColumns, "overwriteColumns");

    String colList = String.join(", ", columns);
    StringJoiner placeholders = new StringJoiner(", ");
    for (int i = 0; i < columns.size(); i++) {
      placeholders.add("?");
    }

    return switch (this) {
      case MYSQL, MARIADB -> {
        StringJoiner updates = new StringJoiner(", ");
        updates.add(
            monotonicColumn + " = GREATEST(" + monotonicColumn + ", VALUES(" + monotonicColumn + "))");
        for (String col : overwriteColumns) {
          updates.add(col + " = VALUES(" + col + ")");
        }
        yield "INSERT INTO "
            + table
            + " ("
            + colList
            + ") VALUES ("
            + placeholders
            + ") ON DUPLICATE KEY UPDATE "
            + updates;
      }
      case POSTGRESQL -> {
        StringJoiner updates = new StringJoiner(", ");
        updates.add(
            monotonicColumn
                + " = GREATEST("
                + table
                + "."
                + monotonicColumn
                + ", excluded."
                + monotonicColumn
                + ")");
        for (String col : overwriteColumns) {
          updates.add(col + " = excluded." + col);
        }
        yield "INSERT INTO "
            + table
            + " ("
            + colList
            + ") VALUES ("
            + placeholders
            + ") ON CONFLICT("
            + conflictColumn
            + ") DO UPDATE SET "
            + updates;
      }
      case H2 ->
          "MERGE INTO "
              + table
              + " ("
              + colList
              + ") KEY("
              + conflictColumn
              + ") VALUES ("
              + placeholders
              + ")";
      case SQLITE, UNKNOWN -> {
        StringJoiner updates = new StringJoiner(", ");
        updates.add(
            monotonicColumn + " = MAX(" + monotonicColumn + ", excluded." + monotonicColumn + ")");
        for (String col : overwriteColumns) {
          updates.add(col + " = excluded." + col);
        }
        yield "INSERT INTO "
            + table
            + " ("
            + colList
            + ") VALUES ("
            + placeholders
            + ") ON CONFLICT("
            + conflictColumn
            + ") DO UPDATE SET "
            + updates;
      }
    };
  }
}
