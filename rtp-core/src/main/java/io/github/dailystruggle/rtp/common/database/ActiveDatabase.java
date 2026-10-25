package io.github.dailystruggle.rtp.common.database;

import io.github.dailystruggle.rtp.common.playerData.TeleportLimitStore;
import io.github.dailystruggle.rtp.common.selection.region.Region;

/**
 * Slots for the live database accessor and teleport-limit store (ADR-068; ADR-115 seam 4),
 * formerly the {@code RTP.databaseAccessor} / {@code RTP.teleportLimitStore} instance fields.
 *
 * <p>When a core instance becomes current it clears the accessor (a new instance started without
 * one) and installs its limit store. Platforms install the accessor once the database is open;
 * shutdown clears it after closing. Both slots are {@code volatile}; callers shall null-check.
 *
 * @governedBy ADR-115 (Package DAG Disentanglement and Finite Decision-Preservation Hypergraph)
 */
public final class ActiveDatabase {
  private static volatile DatabaseAccessor<?> accessor;
  private static volatile TeleportLimitStore teleportLimitStore;

  private ActiveDatabase() {
  }

  /** The live accessor, or {@code null} before the platform opens the database. */
  public static DatabaseAccessor<?> accessor() {
    return accessor;
  }

  public static void setAccessor(DatabaseAccessor<?> value) {
    accessor = value;
    Region.setLocationStore(value);
  }

  /** The teleport-limit guard, or {@code null} before the first core instance is constructed. */
  public static TeleportLimitStore teleportLimitStore() {
    return teleportLimitStore;
  }

  public static void setTeleportLimitStore(TeleportLimitStore value) {
    teleportLimitStore = value;
  }
}
