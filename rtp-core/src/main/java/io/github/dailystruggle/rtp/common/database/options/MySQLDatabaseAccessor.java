package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor.TableObj;
import io.github.dailystruggle.rtp.common.database.pool.MiniConnectionPool;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

public class MySQLDatabaseAccessor extends AbstractSQLDatabaseAccessor {
  private final MiniConnectionPool pool;
  private final String name;

  public MySQLDatabaseAccessor(String host, int port, String database, String username, String password) {
    this.name = "jdbc:mysql://" + host + ":" + port + "/" + database;
    String url = name + "?useSSL=false&autoReconnect=true&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048";
    this.pool = new MiniConnectionPool(url, username, password);

    try (Connection connection = getConnection();
         Statement statement = connection.createStatement()) {
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
    } catch (SQLException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
      close();
      throw new IllegalStateException("Failed to connect to MySQL database: " + e.getMessage(), e);
    }
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public Connection getConnection() throws SQLException {
    return pool.getConnection();
  }

  @Override
  public void close() {
    if (pool != null) {
      pool.close();
    }
  }

  @Override
  protected String getInsertStatement() {
    return "INSERT IGNORE INTO rtp_teleport_data (senderName, senderId, time, delay, selectedX, selectedY, selectedZ, selectedWorldName, selectedWorldId, originalX, originalY, originalZ, originalWorldName, originalWorldId, region, cost, attempts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
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

    String sql = "REPLACE INTO " + tableName + " (" + columns + ") VALUES (" + values + ")";

    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.size(); i++) {
        statement.setObject(i + 1, parameters.get(i));
      }
      statement.executeUpdate();
    } catch (SQLException e) {
      RTP.log(Level.WARNING, "Failed to write to MySQL table " + tableName, e);
    }
  }
}
