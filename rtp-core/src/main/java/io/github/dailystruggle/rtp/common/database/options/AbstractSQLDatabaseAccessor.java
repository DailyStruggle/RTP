package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.network.NetworkStateBinding;
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

  /** Default constructor. Subclasses supply the JDBC connection. */
  protected AbstractSQLDatabaseAccessor() {
  }

  /** Queue for teleport data write operations */
  protected final ConcurrentLinkedQueue<TeleportData> writeQueue = new ConcurrentLinkedQueue<>();

  /**
   * Network-state member per decision D3 of {@code MULTI_SERVER_PLAN.md}.
   *
   * <p>{@code null} (the default) means network mode is disabled. When non-{@code
   * null}, a proxy-side binding (in-memory, Redis, Postgres, generic SQL) is
   * attached and the accessor may be consulted as the durable home for
   * network-state rows. The accessor itself is intentionally agnostic about
   * what the binding does; this is a plumbing slot only.
   *
   * <p>Read with {@link #getNetworkStateBinding()}; install with {@link
   * #setNetworkStateBinding(NetworkStateBinding)}. The byte-identical no-op
   * contract (REQ-RTP-NET-005) is exercised by {@code
   * ReqRtpNet005NetworkDisabledNoOpTest}.
   */
  private volatile NetworkStateBinding networkStateBinding;

  /**
   * Returns the installed network-state binding.
   *
   * @return the installed network-state binding, or {@code null} if network
   *     mode is disabled (the shipping default).
   */
  public NetworkStateBinding getNetworkStateBinding() {
    return networkStateBinding;
  }

  /**
   * Install (or clear, with {@code null}) the network-state binding.
   *
   * <p>This is a plumbing setter only: changing the binding does not retroactively
   * publish anything or alter previously-flushed rows. Intended to be called
   * once during boot, after the proxy-side wiring has chosen a binding
   * implementation per the configured {@code network.transport.type}.
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
   * <p>rtp-proxy-ADR-011 §Backend Wiring: the
   * {@code SqlNetworkStateBinding} consumes a {@code DataSource}, but
   * {@link #getConnection()} is the only contract this accessor publishes.
   * Wrap each call into a fresh {@code DataSource.getConnection()} so callers
   * (heartbeat publisher, network-state binding) participate in the existing
   * connection pool (HikariCP for MySQL/Postgres; single-connection for
   * SQLite/H2) without having to learn pool-specific APIs.</p>
   *
   * <p>The returned {@code DataSource} is a thin facade - it owns no state
   * and delegates every {@code getConnection()} call to this accessor's
   * abstract {@link #getConnection()}. Callers that need to share the pool
   * (proxy + binding + addons) should all use this single facade.</p>
   *
   * @return a {@link javax.sql.DataSource} facade backed by this accessor's connection pool
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
  public synchronized void flush() {
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
      // Table may not exist yet on first save — that's a successful no-op.
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
