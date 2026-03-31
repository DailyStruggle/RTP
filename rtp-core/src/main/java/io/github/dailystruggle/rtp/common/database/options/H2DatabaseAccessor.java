package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;

public class H2DatabaseAccessor extends AbstractSQLDatabaseAccessor {
  private final String url;
  private Connection connection;

  public H2DatabaseAccessor() {
    File pluginDir = RTP.serverAccessor.getPluginDirectory();
    File databaseDir = new File(pluginDir, "database");
    if (!databaseDir.exists()) {
      databaseDir.mkdirs();
    }
    this.url = "jdbc:h2:file:" + pluginDir.getAbsolutePath() + File.separator + "database" + File.separator + "rtp;MODE=MySQL;DB_CLOSE_DELAY=-1";
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
      }
    } catch (SQLException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    }
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
  public void startup() {
    try (Connection connection = getConnection()) {
      String tableName = "rtp_teleport_data";
      String sql = "SELECT * FROM " + tableName;
      try (Statement statement = connection.createStatement();
          ResultSet resultSet = statement.executeQuery(sql)) {

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

    // H2 in MySQL mode supports MERGE INTO or REPLACE INTO
    String sql = "REPLACE INTO " + tableName + " (" + columns + ") VALUES (" + values + ")";

    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.size(); i++) {
        statement.setObject(i + 1, parameters.get(i));
      }
      statement.executeUpdate();
    } catch (SQLException e) {
      RTP.log(Level.WARNING, "Failed to write to H2 table " + tableName, e);
    }
  }
}
