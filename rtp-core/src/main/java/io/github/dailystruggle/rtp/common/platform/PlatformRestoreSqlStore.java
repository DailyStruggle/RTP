package io.github.dailystruggle.rtp.common.platform;

import io.github.dailystruggle.rtp.api.platform.BlockDelta;
import io.github.dailystruggle.rtp.api.platform.PendingPlatformRestore;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.options.AbstractSQLDatabaseAccessor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Self-contained ANSI-SQL persistence for ADR-060 emergency-platform restore jobs.
 *
 * <p>Owns a single {@code rtp_platform_restores} table created on demand via the shared
 * {@link AbstractSQLDatabaseAccessor} HikariCP-backed connection. The DDL uses only
 * portable types (TEXT/VARCHAR + INTEGER) so it works across the H2/SQLite/MySQL/PostgreSQL
 * accessors without per-dialect tweaks. Rows are written on enrollment, their
 * {@code remaining_seconds} updated as the countdown advances, and deleted on completion.
 *
 * <p>The serialized {@code blocks} payload is newline-delimited records of
 * {@code x\ty\tz\ttoken} (tab-separated) so a Bukkit {@code BlockData} string - which
 * contains commas and brackets - round-trips intact.
 */
public final class PlatformRestoreSqlStore {
  static final String TABLE = "rtp_platform_restores";

  private final AbstractSQLDatabaseAccessor accessor;
  private volatile boolean ensured = false;

  public PlatformRestoreSqlStore(AbstractSQLDatabaseAccessor accessor) {
    this.accessor = accessor;
  }

  private void ensureTable(Connection c) throws SQLException {
    if (ensured) return;
    try (PreparedStatement ps =
        c.prepareStatement(
            "CREATE TABLE IF NOT EXISTS "
                + TABLE
                + " ("
                + "id VARCHAR(36) PRIMARY KEY, "
                + "world VARCHAR(255), "
                + "cx INTEGER, "
                + "cz INTEGER, "
                + "remaining_seconds INTEGER, "
                + "blocks TEXT)")) {
      ps.execute();
    }
    ensured = true;
  }

  static String serialize(List<BlockDelta> blocks) {
    StringBuilder sb = new StringBuilder();
    for (BlockDelta b : blocks) {
      if (sb.length() > 0) sb.append('\n');
      sb.append(b.x()).append('\t').append(b.y()).append('\t').append(b.z())
          .append('\t').append(b.token());
    }
    return sb.toString();
  }

  static List<BlockDelta> deserialize(String payload) {
    List<BlockDelta> out = new ArrayList<>();
    if (payload == null || payload.isEmpty()) return out;
    for (String line : payload.split("\n", -1)) {
      if (line.isEmpty()) continue;
      String[] parts = line.split("\t", 4);
      if (parts.length < 4) continue;
      try {
        out.add(
            new BlockDelta(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]),
                parts[3]));
      } catch (NumberFormatException ignored) {
        // Skip a corrupt record rather than wedging the whole load.
      }
    }
    return out;
  }

  /** Inserts a newly enrolled restore job. */
  public void insert(PendingPlatformRestore restore) {
    Connection c = null;
    try {
      c = accessor.getConnection();
      ensureTable(c);
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO " + TABLE
                  + " (id, world, cx, cz, remaining_seconds, blocks) VALUES (?,?,?,?,?,?)")) {
        ps.setString(1, restore.id().toString());
        ps.setString(2, restore.worldName());
        ps.setInt(3, restore.cx());
        ps.setInt(4, restore.cz());
        ps.setInt(5, restore.remainingSeconds());
        ps.setString(6, serialize(restore.blocks()));
        ps.executeUpdate();
      }
    } catch (SQLException e) {
      RTP.log(Level.WARNING, "[RTP] failed to persist platform-restore row", e);
    } finally {
      if (c != null) accessor.disconnect(c);
    }
  }

  /** Updates the remaining countdown for an existing row (resume-accuracy across restarts). */
  public void updateRemaining(UUID id, int remainingSeconds) {
    Connection c = null;
    try {
      c = accessor.getConnection();
      ensureTable(c);
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE " + TABLE + " SET remaining_seconds=? WHERE id=?")) {
        ps.setInt(1, remainingSeconds);
        ps.setString(2, id.toString());
        ps.executeUpdate();
      }
    } catch (SQLException e) {
      RTP.log(Level.WARNING, "[RTP] failed to update platform-restore countdown", e);
    } finally {
      if (c != null) accessor.disconnect(c);
    }
  }

  /** Deletes a completed or abandoned restore row. */
  public void delete(UUID id) {
    Connection c = null;
    try {
      c = accessor.getConnection();
      ensureTable(c);
      try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + TABLE + " WHERE id=?")) {
        ps.setString(1, id.toString());
        ps.executeUpdate();
      }
    } catch (SQLException e) {
      RTP.log(Level.WARNING, "[RTP] failed to delete platform-restore row", e);
    } finally {
      if (c != null) accessor.disconnect(c);
    }
  }

  /** Loads all persisted restore jobs for resume on startup. */
  public List<PendingPlatformRestore> loadAll() {
    List<PendingPlatformRestore> out = new ArrayList<>();
    Connection c = null;
    try {
      c = accessor.getConnection();
      ensureTable(c);
      try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT id, world, cx, cz, remaining_seconds, blocks FROM " + TABLE);
          ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          try {
            out.add(
                new PendingPlatformRestore(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("world"),
                    rs.getInt("cx"),
                    rs.getInt("cz"),
                    deserialize(rs.getString("blocks")),
                    rs.getInt("remaining_seconds")));
          } catch (IllegalArgumentException ignored) {
            // Skip a corrupt row rather than aborting the entire resume.
          }
        }
      }
    } catch (SQLException e) {
      RTP.log(Level.WARNING, "[RTP] failed to load platform-restore rows", e);
    } finally {
      if (c != null) accessor.disconnect(c);
    }
    return out;
  }
}
