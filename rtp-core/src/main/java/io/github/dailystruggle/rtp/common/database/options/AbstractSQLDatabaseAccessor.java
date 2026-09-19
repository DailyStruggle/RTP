package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.network.NetworkStateBinding;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Abstract SQL database accessor with a batched async write queue.
 */
public abstract class AbstractSQLDatabaseAccessor extends DatabaseAccessor<Connection> {

  private static final String COL_SELECTED_X = "selectedX";
  private static final String COL_SELECTED_Y = "selectedY";
  private static final String COL_SELECTED_Z = "selectedZ";
  private static final String COL_SELECTED_WORLD_NAME = "selectedWorldName";
  private static final String COL_ORIGINAL_X = "originalX";
  private static final String COL_ORIGINAL_Y = "originalY";
  private static final String COL_ORIGINAL_Z = "originalZ";
  private static final String COL_ORIGINAL_WORLD_NAME = "originalWorldName";

  /** Default constructor. Subclasses supply the JDBC connection. */
  protected AbstractSQLDatabaseAccessor() {
  }

  /** Queue for teleport data write operations */
  protected final ConcurrentLinkedQueue<TeleportData> writeQueue = new ConcurrentLinkedQueue<>();

  /**
   * Network-state member for multi-server transport (REQ-RTP-NET-005).
   * {@code null} means network mode is disabled.
   */
  private volatile NetworkStateBinding networkStateBinding;

  /**
   * Returns the installed network-state binding.
   *
   * @return the installed network-state binding, or {@code null} if network mode is disabled.
   */
  public NetworkStateBinding getNetworkStateBinding() {
    return networkStateBinding;
  }

  /**
   * Install (or clear, with {@code null}) the network-state binding.
   *
   * @param binding the binding to install, or {@code null} to disable network mode
   */
  public void setNetworkStateBinding(NetworkStateBinding binding) {
    this.networkStateBinding = binding;
  }

  /**
   * Get a connection to the SQL database
   *
   * @return the connection
   * @throws SQLException if a database access error occurs
   */
  public abstract Connection getConnection() throws SQLException;

  /**
   * Adapter view of this accessor as a JDBC {@link javax.sql.DataSource}.
   *
   * @return a {@link javax.sql.DataSource} facade delegating to {@link #getConnection()}
   */
  public javax.sql.DataSource asDataSource() {
    return new AccessorDataSource(this);
  }

  /** Internal facade: see {@link #asDataSource()}. */
  private static final class AccessorDataSource implements javax.sql.DataSource {
    private final AbstractSQLDatabaseAccessor accessor;
    AccessorDataSource(AbstractSQLDatabaseAccessor accessor) { this.accessor = accessor; }
    @Override public Connection getConnection() throws SQLException {
      return accessor.getConnection();
    }
    @Override public Connection getConnection(String username, String password) throws SQLException {
      return accessor.getConnection();
    }
    @Override public java.io.PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(java.io.PrintWriter out) { /* unused */ }
    @Override public void setLoginTimeout(int seconds) { /* unused */ }
    @Override public int getLoginTimeout() { return 0; }
    @Override public java.util.logging.Logger getParentLogger() {
      return java.util.logging.Logger.getLogger("rtp-sql-accessor-datasource");
    }
    @Override public <T> T unwrap(Class<T> iface) { return null; }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
  }

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

        Object selectedX = data.get(COL_SELECTED_X);
        Object selectedY = data.get(COL_SELECTED_Y);
        Object selectedZ = data.get(COL_SELECTED_Z);
        Object selectedWorldName = data.get(COL_SELECTED_WORLD_NAME);
        if (selectedX != null && selectedY != null && selectedZ != null && selectedWorldName != null) {
          teleportData.selectedCoords =
              new RTPCoords(
                  selectedWorldName.toString(),
                  ((Number) selectedX).intValue(),
                  ((Number) selectedY).intValue(),
                  ((Number) selectedZ).intValue());
        }

        Object originalX = data.get(COL_ORIGINAL_X);
        Object originalY = data.get(COL_ORIGINAL_Y);
        Object originalZ = data.get(COL_ORIGINAL_Z);
        Object originalWorldName = data.get(COL_ORIGINAL_WORLD_NAME);
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
    synchronized (this) {
      if (writeQueue.isEmpty()) return;

      Connection connection = null;
      boolean autoCommitToggled = false;
      boolean batchAdded = false;
      try {
        connection = getConnection();
        // Guard against a shared connection whose auto-commit state
        // may have been flipped by another operation on the same
        // SQLite/H2 connection. Only toggle if we actually own the
        // transition so we can restore it in the finally block.
        if (connection.getAutoCommit()) {
          connection.setAutoCommit(false);
          autoCommitToggled = true;
        }
        String sql = getInsertStatement();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
          TeleportData data;
          while ((data = writeQueue.poll()) != null) {
            Map<String, Object> columns = toColumns(data);
            statement.setString(1, String.valueOf(columns.get("senderName")));
            statement.setString(2, String.valueOf(columns.get("senderId")));
            statement.setLong(3, ((Number) columns.getOrDefault("time", 0L)).longValue());
            statement.setLong(4, ((Number) columns.getOrDefault("delay", 0L)).longValue());
            statement.setInt(5, ((Number) columns.getOrDefault(COL_SELECTED_X, 0)).intValue());
            statement.setInt(6, ((Number) columns.getOrDefault(COL_SELECTED_Y, 0)).intValue());
            statement.setInt(7, ((Number) columns.getOrDefault(COL_SELECTED_Z, 0)).intValue());
            statement.setString(8, String.valueOf(columns.get(COL_SELECTED_WORLD_NAME)));
            statement.setString(9, String.valueOf(columns.get("selectedWorldId")));
            statement.setInt(10, ((Number) columns.getOrDefault(COL_ORIGINAL_X, 0)).intValue());
            statement.setInt(11, ((Number) columns.getOrDefault(COL_ORIGINAL_Y, 0)).intValue());
            statement.setInt(12, ((Number) columns.getOrDefault(COL_ORIGINAL_Z, 0)).intValue());
            statement.setString(13, String.valueOf(columns.get(COL_ORIGINAL_WORLD_NAME)));
            statement.setString(14, String.valueOf(columns.get("originalWorldId")));
            statement.setString(15, String.valueOf(columns.get("region")));
            statement.setDouble(16, ((Number) columns.getOrDefault("cost", 0.0)).doubleValue());
            statement.setLong(17, ((Number) columns.getOrDefault("attempts", 0L)).longValue());
            statement.addBatch();
            batchAdded = true;
          }
          if (batchAdded) {
            statement.executeBatch();
            if (!connection.getAutoCommit()) {
              connection.commit();
            }
          }
        } catch (SQLException e) {
          // Only attempt rollback if we are actually in a transaction.
          // On a shared SQLite/H2 connection another caller may have
          // restored auto-commit, in which case rollback would throw
          // "database in auto-commit mode" and mask the real error.
          try {
            if (!connection.getAutoCommit()) {
              connection.rollback();
            }
          } catch (SQLException rollbackEx) {
            RTP.log(Level.WARNING, "Failed to rollback after flush error", rollbackEx);
          }
          RTP.log(Level.WARNING, "Failed to flush teleport data batch", e);
        } finally {
          if (autoCommitToggled) {
            try {
              connection.setAutoCommit(true);
            } catch (SQLException ignored) {}
          }
        }
      } catch (SQLException e) {
        RTP.log(Level.WARNING, "Database connection error during flush", e);
      }
    }
  }

  /**
   * Get the SQL statement to insert teleport data.
   *
   * @return the SQL statement
   */
  protected abstract String getInsertStatement();

  @Override
  @SuppressWarnings("java:S2077") // Dynamic table and column identifiers cannot be parameterized in JDBC; values use parameter binding
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
    Connection connection = connect();
    if (connection == null) return res;
    try {
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
    } finally {
      disconnect(connection);
    }
    return res;
  }

  /**
   * Synchronously delete every row from {@code rtp_cached_locations}.
   *
   * <p>Called by {@link #rebuildCachedLocationsFromMemory()} immediately before
   * the in-memory state is re-enqueued for writing, guaranteeing that stale rows
   * from consumed-but-not-deleted locations cannot survive a restart.
   */
  @Override
  public void clearAllCachedLocations() {
    Connection connection = connect();
    if (connection == null) return;
    try {
      try (PreparedStatement statement =
          connection.prepareStatement("DELETE FROM rtp_cached_locations")) {
        statement.executeUpdate();
      }
    } catch (SQLException e) {
      // Table may not exist yet on first save - that's a successful no-op.
    } finally {
      disconnect(connection);
    }
  }

  /**
   * Purge stale cached locations from the database.
   * Stale locations are those bound to a specific player and older than 7 days.
   */
  public void purgeStaleLocations() {
    Connection connection = connect();
    if (connection == null) return;
    try {
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
    } finally {
      disconnect(connection);
    }
  }

  @Nullable
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

  @Override
  public void startup() {
    Connection connection = connect();
    if (connection == null) return;
    try {
      String sql = "SELECT senderId, time, selectedWorldName, selectedX, selectedY, selectedZ, "
          + "originalWorldName, originalX, originalY, originalZ, cost FROM rtp_teleport_data";
      try (PreparedStatement statement = connection.prepareStatement(sql);
          ResultSet resultSet = statement.executeQuery()) {

        while (resultSet.next()) {
          String uuidStr = resultSet.getString("senderId");
          if (uuidStr == null) continue;

          UUID uuid = UUID.fromString(uuidStr);

          TeleportData teleportData = new TeleportData();
          teleportData.completed = true;
          teleportData.time = resultSet.getLong("time");
          teleportData.selectedCoords =
              new RTPCoords(
                  resultSet.getString(COL_SELECTED_WORLD_NAME),
                  resultSet.getInt(COL_SELECTED_X),
                  resultSet.getInt(COL_SELECTED_Y),
                  resultSet.getInt(COL_SELECTED_Z));
          teleportData.originalCoords =
              new RTPCoords(
                  resultSet.getString(COL_ORIGINAL_WORLD_NAME),
                  resultSet.getInt(COL_ORIGINAL_X),
                  resultSet.getInt(COL_ORIGINAL_Y),
                  resultSet.getInt(COL_ORIGINAL_Z));
          teleportData.cost = resultSet.getDouble("cost");

          RTP.getInstance().latestTeleportData.put(uuid, teleportData);
        }
      }
    } catch (SQLException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    } catch (IllegalArgumentException ignored) {
      // Ignore corrupted or malformed UUID strings in database rows
    } finally {
      disconnect(connection);
    }

    purgeStaleLocations();
  }

  @Override
  @SuppressWarnings("java:S2077") // Dynamic table and column identifiers cannot be parameterized in JDBC; values use parameter binding
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
      // Table or column may not exist yet; return empty
    }
    return Optional.empty();
  }
}
