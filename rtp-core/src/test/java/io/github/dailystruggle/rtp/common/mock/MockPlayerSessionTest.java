package io.github.dailystruggle.rtp.common.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-121 section 11: a BIT mock player never outlives the probe that owns it. */
class MockPlayerSessionTest {

  private TeleportSessions previous;

  @BeforeEach
  void setUp() {
    MockPlayerRegistry.clear();
    previous = TeleportSessions.current();
    TeleportSessions.install(new TeleportSessions());
  }

  @AfterEach
  void tearDown() {
    MockPlayerRegistry.clear();
    TeleportSessions.install(previous);
  }

  private static RTPLocation origin() {
    return new RTPLocation(new MockRTPWorld("world"), 0, 64, 0);
  }

  @Test
  @DisplayName("ADR-121: open registers an rtp-bit-<8 hex> mock that cannot collide with a Minecraft name")
  void openRegistersPrefixedMock() {
    try (MockPlayerSession session = MockPlayerSession.open(origin())) {
      RuntimeMockRTPPlayer player = session.player();
      assertTrue(player.name().matches("rtp-bit-[0-9a-f]{8}"), player.name());
      assertEquals(16, player.name().length());
      assertTrue(player.name().startsWith(MockPlayerSession.NAME_PREFIX));
      assertSame(player, MockPlayerRegistry.get(player.uuid()));
      assertSame(player, MockPlayerRegistry.getByName(player.name()));
    }
    assertTrue(MockPlayerRegistry.isEmpty());
  }

  @Test
  @DisplayName("ADR-121: open rejects a null initial location")
  void openRejectsNull() {
    assertThrows(NullPointerException.class, () -> MockPlayerSession.open(null));
    assertTrue(MockPlayerRegistry.isEmpty());
  }

  @Test
  @DisplayName("ADR-121: with() unregisters the mock even when the body throws")
  void withUnregistersOnThrow() {
    AtomicReference<UUID> seen = new AtomicReference<>();
    assertThrows(IllegalStateException.class, () -> MockPlayerSession.with(origin(), p -> {
      seen.set(p.uuid());
      assertTrue(MockPlayerRegistry.isMock(p.uuid()));
      throw new IllegalStateException("probe body failed");
    }));
    assertFalse(MockPlayerRegistry.isMock(seen.get()));
    assertTrue(MockPlayerRegistry.isEmpty());
  }

  @Test
  @DisplayName("ADR-121: with() returns the body result and closes the session")
  void withReturnsResult() {
    String name = MockPlayerSession.with(origin(), RuntimeMockRTPPlayer::name);
    assertTrue(name.startsWith(MockPlayerSession.NAME_PREFIX));
    assertTrue(MockPlayerRegistry.isEmpty());
  }

  @Test
  @DisplayName("ADR-121: close purges TeleportSessions state for the mock and is idempotent")
  void closePurgesSessionStateIdempotently() {
    TeleportSessions sessions = TeleportSessions.current();
    UUID other = UUID.randomUUID();
    sessions.processingPlayers.add(other);

    MockPlayerSession session = MockPlayerSession.open(origin());
    UUID id = session.player().uuid();
    sessions.processingPlayers.add(id);
    sessions.queuedPlayers.add(id);
    sessions.invulnerablePlayers.put(id, 1L);
    sessions.regionTeleportTimes.put(id, new HashMap<>());
    sessions.priorTeleportData.put(id, new TeleportData());
    sessions.latestTeleportData.put(id, new TeleportData());
    sessions.usageCaps.recordSuccess(id, 5, 60_000L, System.currentTimeMillis());

    session.close();
    session.close();

    assertFalse(MockPlayerRegistry.isMock(id));
    assertFalse(sessions.processingPlayers.contains(id));
    assertFalse(sessions.queuedPlayers.contains(id));
    assertFalse(sessions.invulnerablePlayers.containsKey(id));
    assertFalse(sessions.regionTeleportTimes.containsKey(id));
    assertFalse(sessions.priorTeleportData.containsKey(id));
    assertFalse(sessions.latestTeleportData.containsKey(id));
    assertNull(sessions.usageCaps.snapshot(id));
    assertTrue(sessions.processingPlayers.contains(other), "real players are untouched");
  }

  @Test
  @DisplayName("ADR-121: close tolerates a missing TeleportSessions instance")
  void closeWithoutSessions() {
    TeleportSessions.install(null);
    MockPlayerSession session = MockPlayerSession.open(origin());
    session.close();
    assertTrue(MockPlayerRegistry.isEmpty());
  }
}
