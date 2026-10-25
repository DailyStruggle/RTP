package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

public class H2DatabaseAccessor extends AbstractSQLDatabaseAccessor {
  static {
    // Explicitly load H2 JDBC driver if available on classpath.
    try {
      Class.forName("org.h2.Driver");
    } catch (ClassNotFoundException e) {
      RTPRuntime.log(Level.WARNING, "H2 JDBC driver not on classpath: " + e.getMessage(), e);
    }
  }

  private final String url;
  private Connection connection;

  public H2DatabaseAccessor() {
    this(defaultJdbcUrl());
  }

  public H2DatabaseAccessor(String url) {
    this.url = url;
    try {
      this.connection = DriverManager.getConnection(url);
      try (Statement statement = connection.createStatement()) {
        String schema =
            "CREATE TABLE IF NOT EXISTS rtp_teleport_data ("
                + "senderName TEXT, "
                + "senderId VARCHAR(36), "
                + "time BIGINT, "
                + "delay BIGINT, "
                + "selectedX INT, "
                + "selectedY INT, "
                + "selectedZ INT, "
                + "selectedWorldName TEXT, "
                + "selectedWorldId VARCHAR(36), "
                + "originalX INT, "
                + "originalY INT, "
                + "originalZ INT, "
                + "originalWorldName TEXT, "
                + "originalWorldId VARCHAR(36), "
                + "region TEXT, "
                + "cost DOUBLE, "
                + "attempts INT"
                + ");";
        statement.execute(schema);
        schema =
                "CREATE TABLE IF NOT EXISTS rtp_cached_locations ("
                        + "UUID VARCHAR(255) PRIMARY KEY, "
                        + "world TEXT, "
                        + "x INT, "
                        + "y INT, "
                        + "z INT, "
                        + "attempts INT, "
                        + "region TEXT, "
                        + "player_uuid VARCHAR(36), "
                        + "timestamp BIGINT, "
                        + "seed BIGINT"
                        + ");";
        statement.execute(schema);
        ensureMetricBucketSchema(this.connection);
      }
    } catch (SQLException e) {
      RTPRuntime.log(Level.WARNING, e.getMessage(), e);
    }
  }

  private static String defaultJdbcUrl() {
    File pluginDir = RTPRuntime.serverAccessor().getPluginDirectory();
    File databaseDir = new File(pluginDir, "database");
    if (!databaseDir.exists()) {
      databaseDir.mkdirs();
    }
    return "jdbc:h2:file:" + pluginDir.getAbsolutePath() + File.separator + "database" + File.separator + "rtp;MODE=MySQL;DB_CLOSE_DELAY=-1";
  }

  @Override
  public String name() {
    return url;
  }

  @Override
  public Connection getConnection() throws SQLException {
    if (connection == null || connection.isClosed()) {
      connection = DriverManager.getConnection(url);
    }
    return connection;
  }

  @Override
  public void disconnect(Connection connection) {
    // Shared connection, do nothing.
    // Hard disconnect happens in close()
  }

  @Override
  public void close() {
    try {
      if (connection != null && !connection.isClosed()) {
        connection.close();
      }
    } catch (SQLException e) {
      RTPRuntime.log(Level.WARNING, e.getMessage(), e);
    }
  }

  @Override
  protected String getInsertStatement() {
    return "INSERT IGNORE INTO rtp_teleport_data (senderName, senderId, time, delay, selectedX, selectedY, selectedZ, selectedWorldName, selectedWorldId, originalX, originalY, originalZ, originalWorldName, originalWorldId, region, cost, attempts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
  }

  @Override
  public String getMetricBucketInsertStatement() {
    return "INSERT IGNORE INTO rtp_metric_bucket "
        + "(backend_id, metric_key, window_start_epoch, window_seconds, sample_count, mean, min, max, p95, generation) "
        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
  }

  @Override
  @SuppressWarnings("java:S2077") // Dynamic table and column identifiers cannot be parameterized in JDBC; values use parameter binding
  public void write(
      Connection connection, String tableName, Map<TableObj, TableObj> keyValuePairs) {
    if (keyValuePairs == null || keyValuePairs.isEmpty()) throw new IllegalStateException();

    StringBuilder columns = new StringBuilder();
    StringBuilder values = new StringBuilder();
    List<Object> parameters = new ArrayList<>();

    for (Map.Entry<TableObj, TableObj> entry : keyValuePairs.entrySet()) {
      String colName = entry.getKey().object.toString();
      columns.append("`").append(colName).append("`,");
      values.append("?,");
      parameters.add(entry.getValue().object);
    }

    columns.setLength(columns.length() - 1);
    values.setLength(values.length() - 1);

    // H2 in MySQL mode supports MERGE INTO or REPLACE INTO
    String sql = "REPLACE INTO " + tableName + " (" + columns + ") VALUES (" + values + ")";

    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.size(); i++) {
        statement.setObject(i + 1, parameters.get(i));
      }
      statement.executeUpdate();
    } catch (SQLException e) {
      RTPRuntime.log(Level.WARNING, "Failed to write to H2 table " + tableName, e);
    }
  }
}
