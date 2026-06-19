package io.github.dailystruggle.rtp.common.playerData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Covers the ADR-068 local teleport-limit store: in-memory usage-cap
 * enforcement delegation and write-through/reload persistence of the rolling
 * window (the BetterRTP {@code LockAfter} restart-persistence gap).
 */
class TeleportLimitStoreTest {

  /** Minimal in-memory {@link DatabaseAccessor} - relies on the base-class
   * {@code localTables} for synchronous in-session round-tripping. */
  private static final class InMemoryAccessor extends DatabaseAccessor<Object> {
    @Override public String name() { return "in-memory-test"; }
    @Override public void startup() {}
    @Override public Object connect() { return new Object(); }
    @Override public Optional<Map<String, Object>> read(
        Object o, String tableName, Map.Entry<String, Object> lookup) { return Optional.empty(); }
    @Override public void write(Object o, String t, Map<TableObj, TableObj> kv) {}
    @Override public void delete(Object o, String t, Map.Entry<String, Object> lookup) {}
    @Override public void disconnect(Object o) {}
    @Override public java.util.List<StoredLocation> loadCachedLocations(String regionName) {
      return new java.util.ArrayList<>();
    }
  }

  @Test
  void snapshotRestore_roundTrips() {
    UsageCapTracker tracker = new UsageCapTracker();
    UUID id = UUID.randomUUID();
    long now = 1_000L;
    tracker.recordSuccess(id, 5, 0L, now);
    tracker.recordSuccess(id, 5, 0L, now);

    long[] snap = tracker.snapshot(id);
    assertEquals(2L, snap[0]);

    UsageCapTracker restored = new UsageCapTracker();
    restored.restore(id, snap[0], snap[1]);
    assertEquals(2L, restored.snapshot(id)[0]);

    // A non-positive count is treated as "no window".
    restored.restore(id, 0, 0);
    assertNull(restored.snapshot(id));
  }

  @Test
  void enforcement_locksAfterCap_withoutPersistence() {
    LocalTeleportLimitStore store =
        new LocalTeleportLimitStore(new UsageCapTracker(), false);
    UUID id = UUID.randomUUID();
    long now = 10_000L;

    assertFalse(store.isLocked(id, 2, 0L, now));
    store.recordSuccess(id, 2, 0L, now);
    assertFalse(store.isLocked(id, 2, 0L, now));
    store.recordSuccess(id, 2, 0L, now);
    assertTrue(store.isLocked(id, 2, 0L, now), "player should be locked once the cap is reached");

    store.reset(id);
    assertFalse(store.isLocked(id, 2, 0L, now), "reset must clear the player's window");
  }

  @Test
  void persistence_survivesAcrossFreshStore() {
    InMemoryAccessor db = new InMemoryAccessor();
    UUID id = UUID.randomUUID();
    long now = 50_000L;

    LocalTeleportLimitStore first =
        new LocalTeleportLimitStore(new UsageCapTracker(), true, () -> db);
    first.recordSuccess(id, 3, 0L, now);
    first.recordSuccess(id, 3, 0L, now);
    first.recordSuccess(id, 3, 0L, now);
    assertTrue(first.isLocked(id, 3, 0L, now));

    // A fresh store (simulating a restart) backed by the same persisted table
    // must hydrate the player's window and report the same locked verdict.
    LocalTeleportLimitStore second =
        new LocalTeleportLimitStore(new UsageCapTracker(), true, () -> db);
    assertTrue(second.isLocked(id, 3, 0L, now),
        "usage cap must survive a restart via the persisted window");
  }

  @Test
  void disabledCap_isNeverLocked() {
    LocalTeleportLimitStore store =
        new LocalTeleportLimitStore(new UsageCapTracker(), true, () -> null);
    UUID id = UUID.randomUUID();
    assertFalse(store.isLocked(id, 0, 0L, 1L));
    store.recordSuccess(id, 0, 0L, 1L); // no-op
    assertFalse(store.isLocked(id, 0, 0L, 1L));
  }
}
