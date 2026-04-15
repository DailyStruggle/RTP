package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;

public class SQLiteDatabaseAccessor extends AbstractSQLDatabaseAccessor {
  private final String sqliteUrl;
  private Connection connection;

  public SQLiteDatabaseAccessor(String url) {
    File pluginDir = RTP.serverAccessor.getPluginDirectory();
    File databaseFile = new File(pluginDir, "database" + File.separator + "rtp.db");
    if (!databaseFile.getParentFile().exists()) {
      databaseFile.getParentFile().mkdirs();
    }
    this.sqliteUrl = "jdbc:sqlite:" + databaseFile.getAbsolutePath();
    try {
      this.connection = DriverManager.getConnection(sqliteUrl);
      try (Statement statement = connection.createStatement()) {
        statement.execute("PRAGMA journal_mode=WAL;");
        statement.execute("PRAGMA synchronous=NORMAL;");
        String schema =
            "CREATE TABLE IF NOT EXISTS rtp_teleport_data ("
                + "senderName TEXT, "
                + "senderId TEXT, "
                + "time INTEGER, "
                + "delay INTEGER, "
                + "selectedX INTEGER, "
                + "selectedY INTEGER, "
                + "selectedZ INTEGER, "
                + "selectedWorldName TEXT, "
                + "selectedWorldId TEXT, "
                + "originalX INTEGER, "
                + "originalY INTEGER, "
                + "originalZ INTEGER, "
                + "originalWorldName TEXT, "
                + "originalWorldId TEXT, "
                + "region TEXT, "
                + "cost REAL, "
                + "attempts INTEGER"
                + ");";
        statement.execute(schema);
        schema =
                "CREATE TABLE IF NOT EXISTS rtp_cached_locations ("
                        + "UUID TEXT PRIMARY KEY, "
                        + "world TEXT, "
                        + "x INTEGER, "
                        + "y INTEGER, "
                        + "z INTEGER, "
                        + "attempts INTEGER, "
                        + "region TEXT, "
                        + "player_uuid TEXT, "
                        + "timestamp INTEGER, "
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
    return sqliteUrl;
  }

  @Override
  public Connection getConnection() throws SQLException {
    if (connection == null || connection.isClosed()) {
      connection = DriverManager.getConnection(sqliteUrl);
      try (Statement statement = connection.createStatement()) {
        statement.execute("PRAGMA journal_mode=WAL;");
        statement.execute("PRAGMA synchronous=NORMAL;");
      }
    }
    return connection;
  }


  @Override
  public void startup() {
    DriverManager.setLoginTimeout(30);
    Connection connection;
    try {
      connection = getConnection();
    } catch (SQLException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
      return;
    }

    @NotNull
    Optional<Map<String, Object>> read =
        read(connection, "referenceData", new AbstractMap.SimpleEntry<>("UUID", new UUID(0, 0)));
    if (!read.isPresent()) {
      return;
    }

    // get full table
    String tableName = "rtp_teleport_data";
    String sql = "SELECT * FROM " + tableName;
    Statement statement;

    try {
      statement = connection.createStatement();

      try {
        statement.execute(sql);
      } catch (SQLException e) {
        return;
      }

      ResultSet resultSet;

      try {
        resultSet = statement.getResultSet();
      } catch (SQLException e) {
        return;
      }

      if (resultSet == null) {
        return;
      }

      // each data
      while (resultSet.next()) {
        String uuidStr = resultSet.getString("senderId");
        if (uuidStr == null) continue;

        UUID uuid = UUID.fromString(uuidStr);

        TeleportData teleportData = new TeleportData();
        teleportData.completed = true;
        teleportData.time = Long.parseLong(resultSet.getString("time"));
        teleportData.selectedCoords =
            new RTPCoords(
                resultSet.getString("selectedWorldName"),
                Integer.parseInt(resultSet.getString("selectedX")),
                Integer.parseInt(resultSet.getString("selectedY")),
                Integer.parseInt(resultSet.getString("selectedZ")));
        teleportData.originalCoords =
            new RTPCoords(
                resultSet.getString("originalWorldName"),
                Integer.parseInt(resultSet.getString("originalX")),
                Integer.parseInt(resultSet.getString("originalY")),
                Integer.parseInt(resultSet.getString("originalZ")));
        teleportData.cost = Double.parseDouble(resultSet.getString("cost"));

        RTP.getInstance().latestTeleportData.put(uuid, teleportData);
      }
    } catch (SQLException throwables) {
      throwables.printStackTrace();
    } catch (IllegalArgumentException ignored) {

    }

    purgeStaleLocations();
  }

  @Override
  public @NotNull Optional<Map<String, Object>> read(
      Connection connection, String tableName, Map.Entry<String, Object> lookup) {
    String sql = "PRAGMA table_info( " + tableName + " );";
    Statement statement;
    Map<String, String> column_info = new HashMap<>();

    try {
      statement = connection.createStatement();
      statement.execute(sql);
      ResultSet resultSet = statement.getResultSet();

      if (resultSet == null) return Optional.empty();

      while (resultSet.next()) {
        column_info.put(resultSet.getString("name"), resultSet.getString("type"));
      }
    } catch (SQLException e) {
      return Optional.empty();
    }

    // validate and add necessary column_info
    Map<String, Object> row = new HashMap<>();

    sql =
        "SELECT * FROM "
            + tableName
            + " WHERE "
            + lookup.getKey()
            + " = "
            + "\""
            + lookup.getValue().toString()
            + "\"";

    try {
      statement.execute(sql);
      ResultSet resultSet = statement.getResultSet();
      for (String key : column_info.keySet()) {
        Object object = resultSet.getObject(key);
        if (object == null || object.equals("NULL")) continue;
        row.put(key, object);
      }
      return Optional.of(row);
    } catch (SQLException ignored) {

    }

    return Optional.empty();
  }

  @Override
  protected String getInsertStatement() {
    return "INSERT OR IGNORE INTO rtp_teleport_data (senderName, senderId, time, delay, selectedX, selectedY, selectedZ, selectedWorldName, selectedWorldId, originalX, originalY, originalZ, originalWorldName, originalWorldId, region, cost, attempts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
  }

  @Override
  public void write(
      Connection connection, String tableName, Map<TableObj, TableObj> keyValuePairs) {
    if (keyValuePairs == null) throw new IllegalStateException();
    if (keyValuePairs.isEmpty()) throw new IllegalStateException();

    // get table info for validation
    String sql = "PRAGMA table_info( " + tableName + " );";
    Statement statement;
    Map<String, String> columns = new HashMap<>();
    try {
      statement = connection.createStatement();

      statement.execute(sql);
      ResultSet resultSet;
      try {
        resultSet = statement.getResultSet();
      } catch (SQLException e) {
        StringBuilder create = new StringBuilder("CREATE TABLE IF NOT EXISTS " + tableName + " ( ");
        for (Map.Entry<TableObj, TableObj> entry : keyValuePairs.entrySet()) {
          create =
              create
                  .append("\"")
                  .append(entry.getKey().object.toString())
                  .append("\" ")
                  .append("TEXT")
                  .append(", ");
        }
        create = create.replace(create.lastIndexOf(","), create.length(), "");
        create = create.append(" );");
        statement.execute(create.toString());

        if (keyValuePairs.containsKey(new TableObj("UUID"))) {
          String unique =
              "CREATE UNIQUE INDEX IF NOT EXISTS "
                  + "UID"
                  + " ON "
                  + tableName
                  + " ( "
                  + "UUID"
                  + " );";
          statement.execute(unique);
        }

        statement.execute(sql);
        resultSet = statement.getResultSet();
      }

      if (resultSet == null) {
        StringBuilder create = new StringBuilder("CREATE TABLE IF NOT EXISTS " + tableName + " ( ");
        for (Map.Entry<TableObj, TableObj> entry : keyValuePairs.entrySet()) {
          create =
              create
                  .append(entry.getKey().object.toString())
                  .append(" ")
                  .append("TEXT")
                  .append(", ");
        }
        create = create.replace(create.lastIndexOf(","), create.length(), "");
        create = create.append(" );");
        statement.execute(create.toString());

        if (keyValuePairs.containsKey(new TableObj("UUID"))) {
          String unique =
              "CREATE UNIQUE INDEX IF NOT EXISTS "
                  + "UID"
                  + " ON "
                  + tableName
                  + " ( "
                  + "UUID"
                  + " );";
          statement.execute(unique);
        }

        statement.execute(sql);
        resultSet = statement.getResultSet();
      }

      Objects.requireNonNull(resultSet);
      int i = 0;
      while (resultSet.next()) {
        i++;
        columns.put(resultSet.getString("name"), resultSet.getString("type"));
      }

      if (i == 0) {
        StringBuilder create = new StringBuilder("CREATE TABLE IF NOT EXISTS " + tableName + " ( ");
        for (Map.Entry<TableObj, TableObj> entry : keyValuePairs.entrySet()) {
          create =
              create
                  .append(entry.getKey().object.toString())
                  .append(" ")
                  .append("TEXT")
                  .append(", ");
        }
        create = create.replace(create.lastIndexOf(","), create.length(), "");
        create = create.append(" );");
        statement.execute(create.toString());

        if (keyValuePairs.containsKey(new TableObj("UUID"))) {
          String unique =
              "CREATE UNIQUE INDEX IF NOT EXISTS "
                  + "UID"
                  + " ON "
                  + tableName
                  + " ( "
                  + "UUID"
                  + " );";
          statement.execute(unique);
        }

        statement.execute(sql);
        resultSet = statement.getResultSet();

        while (resultSet.next()) {
          columns.put(resultSet.getString("name"), resultSet.getString("type"));
        }
      }

    } catch (SQLException e) {
      throw new IllegalStateException();
    }

    // validate and add necessary columns
    List<String> keys = new ArrayList<>();
    List<String> values = new ArrayList<>();
    for (Map.Entry<TableObj, TableObj> entry : keyValuePairs.entrySet()) {
      String key = entry.getKey().object.toString();

      keys.add(key);
      values.add("\"" + entry.getValue().object.toString() + "\"");

      if (columns.containsKey(key)) continue;

      String typeStr;
      switch (entry.getValue().expectedType) {
        case INT:
          typeStr = "INTEGER";
          break;
        case REAL:
          typeStr = "REAL";
          break;
        case TEXT:
          typeStr = "TEXT";
          break;
        case BLOB:
          typeStr = "BLOB";
          break;
        default:
          throw new IllegalStateException("Unexpected value: " + entry.getKey().expectedType);
      }

      sql = "ALTER TABLE " + tableName + " ADD " + key + " " + typeStr + ";";
      try {
        statement.execute(sql);
      } catch (SQLException e) {
        RTP.log(Level.WARNING, e.getMessage(), e);
      }
    }

    StringBuilder builder =
        new StringBuilder("INSERT OR REPLACE INTO ").append(tableName).append(" ( ");
    for (int i = 0; i < keys.size(); i++) {
      String key = keys.get(i);
      builder = builder.append("\"").append(key).append("\"");
      if (i < keys.size() - 1) builder = builder.append(',');
    }
    builder = builder.append(" ) VALUES( ");
    for (int i = 0; i < values.size(); i++) {
      String value = values.get(i);
      builder = builder.append(value);
      if (i < values.size() - 1) builder = builder.append(',');
    }
    builder = builder.append(" );");

    try {
      statement.execute(builder.toString());
    } catch (SQLException e) {
      throw new IllegalStateException();
    }
  }

}
