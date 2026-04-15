package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * Abstract SQL database accessor with a batched async write queue.
 */
public abstract class AbstractSQLDatabaseAccessor extends DatabaseAccessor<Connection> {
  /** Queue for teleport data write operations */
  protected final ConcurrentLinkedQueue<TeleportData> writeQueue = new ConcurrentLinkedQueue<>();

  /**
   * Get a connection to the SQL database
   *
   * @return the connection
   * @throws SQLException if a database access error occurs
   */
  public abstract Connection getConnection() throws SQLException;

  @Override
  public void cacheValue(String tableName, Map<String, Object> data) {
    if (tableName.equalsIgnoreCase("rtp_teleport_data") || tableName.equalsIgnoreCase("teleportData")) {
      TeleportData teleportData = new TeleportData();
      try {
        // Try to get senderId or UUID
        String senderIdStr = null;
        if (data.containsKey("senderId")) senderIdStr = data.get("senderId").toString();
        else if (data.containsKey("UUID")) senderIdStr = data.get("UUID").toString();

        if (senderIdStr != null) {
          teleportData.sender = RTP.serverAccessor.getSender(UUID.fromString(senderIdStr));
        }

        if (data.containsKey("time")) {
          teleportData.time = ((Number) data.get("time")).longValue();
        }
        if (data.containsKey("delay")) {
          teleportData.delay = ((Number) data.get("delay")).longValue();
        }
        if (data.containsKey("cost")) {
          teleportData.cost = ((Number) data.get("cost")).doubleValue();
        }
        if (data.containsKey("attempts")) {
          teleportData.attempts = ((Number) data.get("attempts")).longValue();
        }
        if (data.containsKey("region")) {
          teleportData.targetRegion = RTP.selectionAPI.getRegion(data.get("region").toString());
        }

        Object selectedX = data.get("selectedX");
        Object selectedY = data.get("selectedY");
        Object selectedZ = data.get("selectedZ");
        Object selectedWorldName = data.get("selectedWorldName");
        if (selectedX != null && selectedY != null && selectedZ != null && selectedWorldName != null) {
          teleportData.selectedCoords =
              new RTPCoords(
                  selectedWorldName.toString(),
                  ((Number) selectedX).intValue(),
                  ((Number) selectedY).intValue(),
                  ((Number) selectedZ).intValue());
        }

        Object originalX = data.get("originalX");
        Object originalY = data.get("originalY");
        Object originalZ = data.get("originalZ");
        Object originalWorldName = data.get("originalWorldName");
        if (originalX != null && originalY != null && originalZ != null && originalWorldName != null) {
          teleportData.originalCoords =
              new RTPCoords(
                  originalWorldName.toString(),
                  ((Number) originalX).intValue(),
                  ((Number) originalY).intValue(),
                  ((Number) originalZ).intValue());
        }
        teleportData.completed = true;
        writeQueue.add(teleportData);
      } catch (Exception e) {
        // Fallback to default behavior if mapping fails
        super.cacheValue(tableName, data);
      }
    } else {
      super.cacheValue(tableName, data);
    }
  }

  @Override
  public void cacheValue(TeleportData data) {
    writeQueue.add(data);
  }

  /** Drain the write queue and execute a batched insert. */
  public void flush() {
    if (writeQueue.isEmpty()) return;

    try (Connection connection = getConnection()) {
      connection.setAutoCommit(false);
      String sql = getInsertStatement();
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        TeleportData data;
        while ((data = writeQueue.poll()) != null) {
          Map<String, Object> columns = toColumns(data);
          statement.setString(1, String.valueOf(columns.get("senderName")));
          statement.setString(2, String.valueOf(columns.get("senderId")));
          statement.setLong(3, ((Number) columns.getOrDefault("time", 0L)).longValue());
          statement.setLong(4, ((Number) columns.getOrDefault("delay", 0L)).longValue());
          statement.setInt(5, ((Number) columns.getOrDefault("selectedX", 0)).intValue());
          statement.setInt(6, ((Number) columns.getOrDefault("selectedY", 0)).intValue());
          statement.setInt(7, ((Number) columns.getOrDefault("selectedZ", 0)).intValue());
          statement.setString(8, String.valueOf(columns.get("selectedWorldName")));
          statement.setString(9, String.valueOf(columns.get("selectedWorldId")));
          statement.setInt(10, ((Number) columns.getOrDefault("originalX", 0)).intValue());
          statement.setInt(11, ((Number) columns.getOrDefault("originalY", 0)).intValue());
          statement.setInt(12, ((Number) columns.getOrDefault("originalZ", 0)).intValue());
          statement.setString(13, String.valueOf(columns.get("originalWorldName")));
          statement.setString(14, String.valueOf(columns.get("originalWorldId")));
          statement.setString(15, String.valueOf(columns.get("region")));
          statement.setDouble(16, ((Number) columns.getOrDefault("cost", 0.0)).doubleValue());
          statement.setLong(17, ((Number) columns.getOrDefault("attempts", 0L)).longValue());
          statement.addBatch();
        }
        statement.executeBatch();
        connection.commit();
      } catch (SQLException e) {
        connection.rollback();
        RTP.log(Level.WARNING, "Failed to flush teleport data batch", e);
      }
    } catch (SQLException e) {
      RTP.log(Level.WARNING, "Database connection error during flush", e);
    }
  }

  /**
   * Get the SQL statement to insert teleport data.
   *
   * @return the SQL statement
   */
  protected abstract String getInsertStatement();

  @Override
  public void delete(Connection connection, String tableName, Map.Entry<String, Object> lookup) {
    String sql = "DELETE FROM " + tableName + " WHERE " + lookup.getKey() + " = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, lookup.getValue());
      statement.executeUpdate();
    } catch (SQLException e) {
      RTP.log(Level.WARNING, "Failed to execute delete query: " + sql, e);
    }
  }

  @Override
  public List<StoredLocation> loadCachedLocations(String regionName) {
    List<StoredLocation> res = new ArrayList<>();
    try (Connection connection = getConnection()) {
      String sql = "SELECT * FROM rtp_cached_locations WHERE region = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, regionName);
        try (ResultSet resultSet = statement.executeQuery()) {
          boolean hasPlayerUuid = false;
          boolean hasSeed = false;
          try {
            resultSet.findColumn("player_uuid");
            hasPlayerUuid = true;
          } catch (SQLException ignored) {}
          try {
            resultSet.findColumn("seed");
            hasSeed = true;
          } catch (SQLException ignored) {}

          while (resultSet.next()) {
            String id = resultSet.getString("UUID");
            String worldName = resultSet.getString("world");
            int x = resultSet.getInt("x");
            int y = resultSet.getInt("y");
            int z = resultSet.getInt("z");
            int attempts = resultSet.getInt("attempts");
            long seed = hasSeed ? resultSet.getLong("seed") : 0L;
            String playerUuidStr = hasPlayerUuid ? resultSet.getString("player_uuid") : "shared";

            UUID playerUuid = null;
            if (playerUuidStr != null && !playerUuidStr.equalsIgnoreCase("shared")) {
              try {
                playerUuid = UUID.fromString(playerUuidStr);
              } catch (IllegalArgumentException ignored) {}
            }
            res.add(new StoredLocation(id, regionName, worldName, x, y, z, attempts, seed, playerUuid));
          }
        }
      }
    } catch (SQLException e) {
      // If table doesn't exist, it's fine, just return empty list
    }
    return res;
  }

  /**
   * Purge stale cached locations from the database.
   * Stale locations are those bound to a specific player and older than 7 days.
   */
  public void purgeStaleLocations() {
    try (Connection connection = getConnection()) {
      // 7 days in milliseconds
      long threshold = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L);
      String sql = "DELETE FROM rtp_cached_locations WHERE player_uuid <> 'shared' AND (timestamp < ? OR timestamp IS NULL)";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, threshold);
        int deleted = statement.executeUpdate();
        if (deleted > 0 && isSystemDatabaseLoggingEnabled()) {
          RTP.log(Level.INFO, "Purged " + deleted + " stale cached locations from the database.");
        }
      }
    } catch (SQLException e) {
      // Table might not exist yet, or timestamp column might be missing
    }
  }

  @Override
  public Connection connect() {
    try {
      return getConnection();
    } catch (SQLException e) {
      RTP.log(Level.WARNING, "Failed to connect to database", e);
      return null;
    }
  }

  @Override
  public void disconnect(Connection connection) {
    try {
      if (connection != null && !connection.isClosed()) {
        connection.close();
      }
    } catch (SQLException e) {
      RTP.log(Level.WARNING, "Failed to close database connection", e);
    }
  }
}
