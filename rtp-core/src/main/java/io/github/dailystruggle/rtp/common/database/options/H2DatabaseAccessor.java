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
  static {
    // Explicitly register the H2 JDBC driver. Required on Fabric, where the
    // RTP mod jar is loaded by Knot's classloader and the JVM's automatic
    // ServiceLoader-based driver discovery (which scans the system
    // classloader) cannot see drivers shipped inside the mod jar — leading
    // to "No suitable driver found for jdbc:h2:..." at DriverManager.getConnection.
    //
    // H2 is no longer shaded into the RTP jar (we cannot bundle every JDBC
    // driver — see DatabaseAccessorFactory). Admins who select database.type=h2
    // must drop `h2-*.jar` onto the server classpath; otherwise the factory's
    // Class.forName probe falls back to flat-file YAML before this accessor is
    // ever constructed, so reaching the catch below should be effectively
    // impossible at runtime.
    try {
      Class.forName("org.h2.Driver");
    } catch (ClassNotFoundException e) {
      RTP.log(Level.WARNING, "H2 JDBC driver not on classpath: " + e.getMessage(), e);
    }
  }

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
      RTP.log(Level.WARNING, e.getMessage(), e);
    }
  }

  @Override
  public void startup() {
    Connection connection = connect();
    if (connection == null) return;
    try {
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
    } finally {
      disconnect(connection);
    }

    purgeStaleLocations();
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
