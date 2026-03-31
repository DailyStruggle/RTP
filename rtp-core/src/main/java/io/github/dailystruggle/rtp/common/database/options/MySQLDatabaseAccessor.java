package io.github.dailystruggle.rtp.common.database.options;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor.TableObj;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;

public class MySQLDatabaseAccessor extends AbstractSQLDatabaseAccessor {
  private final HikariDataSource dataSource;
  private final String name;

  public MySQLDatabaseAccessor(String host, int port, String database, String username, String password) {
    this.name = "jdbc:mysql://" + host + ":" + port + "/" + database;
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(name + "?useSSL=false&autoReconnect=true");
    config.setUsername(username);
    config.setPassword(password);
    config.addDataSourceProperty("cachePrepStmts", "true");
    config.addDataSourceProperty("prepStmtCacheSize", "250");
    config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

    this.dataSource = new HikariDataSource(config);

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
    } catch (SQLException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    }
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public Connection getConnection() throws SQLException {
    return dataSource.getConnection();
  }

  @Override
  public void startup() {
    try (Connection connection = getConnection()) {
      // get full table
      String tableName = "rtp_teleport_data";
      String sql = "SELECT * FROM " + tableName;
      try (Statement statement = connection.createStatement();
          ResultSet resultSet = statement.executeQuery(sql)) {

        // each data
        while (resultSet.next()) {
          String uuidStr = resultSet.getString("senderId");
          if (uuidStr == null) continue;

          UUID uuid = UUID.fromString(uuidStr);

          TeleportData teleportData = new TeleportData();
          teleportData.completed = true;
          teleportData.time = resultSet.getLong("time");
          teleportData.selectedCoords =
              new RTPCoords(
                  resultSet.getString("selectedWorldName"),
                  resultSet.getInt("selectedX"),
                  resultSet.getInt("selectedY"),
                  resultSet.getInt("selectedZ"));
          teleportData.originalCoords =
              new RTPCoords(
                  resultSet.getString("originalWorldName"),
                  resultSet.getInt("originalX"),
                  resultSet.getInt("originalY"),
                  resultSet.getInt("originalZ"));
          teleportData.cost = resultSet.getDouble("cost");

          RTP.getInstance().latestTeleportData.put(uuid, teleportData);
        }
      }
    } catch (SQLException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    } catch (IllegalArgumentException ignored) {
    }
  }

  @Override
  public @NotNull Optional<Map<String, Object>> read(
      Connection connection, String tableName, Map.Entry<String, Object> lookup) {
    Map<String, Object> row = new HashMap<>();

    String sql = "SELECT * FROM " + tableName + " WHERE " + lookup.getKey() + " = ?";

    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, lookup.getValue());
      try (ResultSet resultSet = statement.executeQuery()) {
          if (resultSet.next()) {
              ResultSetMetaData metaData = resultSet.getMetaData();
              int columnCount = metaData.getColumnCount();
              for (int i = 1; i <= columnCount; i++) {
                  String key = metaData.getColumnName(i);
                  Object object = resultSet.getObject(i);
                  if (object == null) continue;
                  row.put(key, object);
              }
              return Optional.of(row);
          }
      }
    } catch (SQLException ignored) {
    }

    return Optional.empty();
  }

  @Override
  protected String getInsertStatement() {
    return "INSERT IGNORE INTO rtp_teleport_data (senderName, senderId, time, delay, selectedX, selectedY, selectedZ, selectedWorldName, selectedWorldId, originalX, originalY, originalZ, originalWorldName, originalWorldId, region, cost, attempts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
  }

  @Override
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
