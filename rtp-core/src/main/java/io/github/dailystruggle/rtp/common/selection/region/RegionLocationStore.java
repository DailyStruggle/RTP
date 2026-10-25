package io.github.dailystruggle.rtp.common.selection.region;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Persistence port for region location caches.
 *
 * <p>Implemented by {@code database.DatabaseAccessor} and wired by {@code database.ActiveDatabase}
 * so {@code selection.region} does not depend on {@code common.database}.
 *
 * @governedBy ADR-115 (Package DAG Disentanglement and Finite Decision-Preservation Hypergraph)
 */
public interface RegionLocationStore {

  /** Stored cached location row projected for region cache hydration. */
  interface StoredEntry {
    String getId();

    String getRegionName();

    String getWorldName();

    int getX();

    int getY();

    int getZ();

    int getAttempts();

    long getSeed();

    @Nullable
    UUID getPlayerId();
  }

  List<? extends StoredEntry> loadCachedLocations(String regionName);

  void saveCachedLocation(String regionName, RTPLocation location, @Nullable UUID playerUuid);

  void deleteCachedLocation(String regionName, RTPLocation location);

  void removeCachedLocation(String id);
}
