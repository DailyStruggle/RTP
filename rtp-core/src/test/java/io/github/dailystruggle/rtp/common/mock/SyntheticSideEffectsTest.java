package io.github.dailystruggle.rtp.common.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.database.ActiveDatabase;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.metrics.LandingHeatmap;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.playerData.TeleportLimitStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-121 section 11 / ADR-104: a BIT mock player leaves no persistent trace. One case per sink,
 * mock versus real, so a gate that forgets a sink or blocks real players fails here.
 */
class SyntheticSideEffectsTest {

  private final List<TeleportData> cached = new ArrayList<>();
  private final List<UUID> limitUses = new ArrayList<>();
  private final List<UUID> sharedWrites = new ArrayList<>();
  private DatabaseAccessor<?> previousDb;
  private TeleportLimitStore previousStore;
  private BiConsumer<UUID, Long> previousShared;
  private RuntimeMockRTPPlayer mock;
  private final UUID real = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    MockPlayerRegistry.clear();
    BitTeleportLog.clear();
    previousDb = ActiveDatabase.accessor();
    previousStore = ActiveDatabase.teleportLimitStore();
    previousShared = SyntheticSideEffects.sharedTimeSink;
    ActiveDatabase.setAccessor(new RecordingDb(cached));
    ActiveDatabase.setTeleportLimitStore(new RecordingLimitStore(limitUses));
    SyntheticSideEffects.sharedTimeSink = (id, t) -> sharedWrites.add(id);
    mock = new RuntimeMockRTPPlayer(UUID.randomUUID(), "rtp-bit-0000abcd",
        new RTPLocation(new MockRTPWorld("world"), 0, 64, 0));
    MockPlayerRegistry.register(mock);
  }

  @AfterEach
  void tearDown() {
    MockPlayerRegistry.clear();
    BitTeleportLog.clear();
    ActiveDatabase.setAccessor(previousDb);
    ActiveDatabase.setTeleportLimitStore(previousStore);
    SyntheticSideEffects.sharedTimeSink = previousShared;
  }

  @Test
  @DisplayName("ADR-121: isSynthetic and publishesEvents follow the mock registry")
  void classification() {
    assertTrue(SyntheticSideEffects.isSynthetic(mock.uuid()));
    assertFalse(SyntheticSideEffects.isSynthetic(real));
    assertFalse(SyntheticSideEffects.isSynthetic(null));
    assertFalse(SyntheticSideEffects.publishesEvents(mock));
    assertTrue(SyntheticSideEffects.publishesEvents(null));
  }

  @Test
  @DisplayName("ADR-121: teleport history is not cached for a mock and is cached once for a real player")
  void persistTeleportData() {
    TeleportData d = new TeleportData();
    SyntheticSideEffects.persistTeleportData(mock.uuid(), d);
    assertTrue(cached.isEmpty());

    SyntheticSideEffects.persistTeleportData(real, d);
    assertEquals(1, cached.size());
    assertSame(d, cached.get(0));
  }

  @Test
  @DisplayName("ADR-121: persistTeleportData tolerates a closed database")
  void persistWithoutDatabase() {
    ActiveDatabase.setAccessor(null);
    SyntheticSideEffects.persistTeleportData(real, new TeleportData());
    assertTrue(cached.isEmpty());
  }

  @Test
  @DisplayName("ADR-121: usage-cap success is not recorded for a mock and is recorded once for a real player")
  void recordLimitUse() {
    SyntheticSideEffects.recordLimitUse(mock.uuid(), 3, 60_000L, 1L);
    assertTrue(limitUses.isEmpty());

    SyntheticSideEffects.recordLimitUse(real, 3, 60_000L, 1L);
    assertEquals(List.of(real), limitUses);
  }

  @Test
  @DisplayName("ADR-121: shared last-teleport time is not written for a mock and is written once for a real player")
  void recordSharedLastTeleport() {
    SyntheticSideEffects.recordSharedLastTeleport(mock.uuid(), 5L);
    assertTrue(sharedWrites.isEmpty());

    SyntheticSideEffects.recordSharedLastTeleport(real, 5L);
    assertEquals(List.of(real), sharedWrites);
  }

  @Test
  @DisplayName("ADR-104: a mock landing goes to BitTeleportLog, never to LandingHeatmap")
  void mockLandingDiverted() {
    String region = "bit-test-" + UUID.randomUUID();
    long before = LandingHeatmap.total(region);
    TeleportData d = new TeleportData();
    d.attempts = 7;
    d.processingTime = 1234;
    RTPLocation at = new RTPLocation(new MockRTPWorld("world"), 100, 70, -50);

    SyntheticSideEffects.recordLanding(mock.uuid(), region, at, d);

    assertEquals(before, LandingHeatmap.total(region));
    List<MockTeleportRecord> log = BitTeleportLog.snapshot();
    assertEquals(1, log.size());
    MockTeleportRecord r = log.get(0);
    assertEquals(mock.uuid(), r.mockId());
    assertEquals("world", r.world());
    assertEquals(100, r.x());
    assertEquals(70, r.y());
    assertEquals(-50, r.z());
    assertEquals(region, r.region());
    assertEquals(7, r.attempts());
    assertEquals(1234, r.durationMillis());
    assertTrue(r.completedAtMillis() > 0);
    assertEquals(1, BitTeleportLog.forRegion(region).size());
  }

  @Test
  @DisplayName("ADR-104: a real landing goes to LandingHeatmap once and never to BitTeleportLog")
  void realLandingRecorded() {
    String region = "bit-test-" + UUID.randomUUID();
    RTPLocation at = new RTPLocation(new MockRTPWorld("world"), 100, 70, -50);

    SyntheticSideEffects.recordLanding(real, region, at, new TeleportData());

    assertEquals(1, LandingHeatmap.total(region));
    assertTrue(BitTeleportLog.snapshot().isEmpty());
  }

  @Test
  @DisplayName("ADR-121: a mock landing without TeleportData still records zero attempts and duration")
  void mockLandingWithoutData() {
    RTPLocation at = new RTPLocation(new MockRTPWorld("world"), 1, 2, 3);
    SyntheticSideEffects.recordLanding(mock.uuid(), null, at, null);
    MockTeleportRecord r = BitTeleportLog.snapshot().get(0);
    assertEquals(0, r.attempts());
    assertEquals(0, r.durationMillis());
  }

  @Test
  @DisplayName("ADR-121: BitTeleportLog is bounded, newest wins, snapshot is immutable")
  void logBounded() {
    for (int i = 0; i < BitTeleportLog.CAPACITY + 5; i++) {
      BitTeleportLog.append(new MockTeleportRecord(mock.uuid(), "world", i, 64, 0, "r", 0, 0, i));
    }
    List<MockTeleportRecord> snap = BitTeleportLog.snapshot();
    assertEquals(BitTeleportLog.CAPACITY, snap.size());
    assertEquals(5, snap.get(0).x());
    assertEquals(BitTeleportLog.CAPACITY + 4, snap.get(snap.size() - 1).x());
    org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, snap::clear);
  }

  private static final class RecordingLimitStore implements TeleportLimitStore {
    private final List<UUID> uses;

    RecordingLimitStore(List<UUID> uses) {
      this.uses = uses;
    }

    @Override
    public boolean isLocked(UUID id, long cap, long resetMillis, long now) {
      return false;
    }

    @Override
    public void recordSuccess(UUID id, long cap, long resetMillis, long now) {
      uses.add(id);
    }

    @Override
    public long millisUntilReset(UUID id, long cap, long resetMillis, long now) {
      return 0;
    }

    @Override
    public void reset(UUID id) {}
  }

  private static final class RecordingDb extends DatabaseAccessor<Object> {
    private final List<TeleportData> cached;

    RecordingDb(List<TeleportData> cached) {
      this.cached = cached;
    }

    @Override
    public void cacheValue(TeleportData data) {
      cached.add(data);
    }

    @Override
    public String name() {
      return "recording";
    }

    @Override
    public List<StoredLocation> loadCachedLocations(String regionName) {
      return Collections.emptyList();
    }

    @Override
    public void startup() {}

    @Override
    public Object connect() {
      return new Object();
    }

    @Override
    public @NotNull Optional<Map<String, Object>> read(
        Object o, String tableName, Map.Entry<String, Object> lookup) {
      return Optional.empty();
    }

    @Override
    public void write(Object o, String tableName, Map<TableObj, TableObj> keyValuePairs) {}

    @Override
    public void delete(Object o, String tableName, Map.Entry<String, Object> lookup) {}

    @Override
    public void disconnect(Object o) {}
  }
}
