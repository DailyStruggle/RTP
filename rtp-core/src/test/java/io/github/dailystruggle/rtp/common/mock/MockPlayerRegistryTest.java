package io.github.dailystruggle.rtp.common.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the runtime mock-player interception point used to drive synthetic
 * players through the queue / teleport pipeline on a live server.
 */
class MockPlayerRegistryTest {

  @BeforeEach
  @AfterEach
  void reset() {
    MockPlayerRegistry.clear();
  }

  private static RuntimeMockRTPPlayer newMock(String name) {
    RTPLocation loc = new RTPLocation(new MockRTPWorld("world"), 0, 64, 0);
    return new RuntimeMockRTPPlayer(UUID.randomUUID(), name, loc);
  }

  @Test
  @DisplayName("register/get/getByName/isMock round-trip")
  void registerAndResolve() {
    assertTrue(MockPlayerRegistry.isEmpty());
    RuntimeMockRTPPlayer mock = newMock("Tester");
    MockPlayerRegistry.register(mock);

    assertFalse(MockPlayerRegistry.isEmpty());
    assertTrue(MockPlayerRegistry.isMock(mock.uuid()));
    assertSame(mock, MockPlayerRegistry.get(mock.uuid()));
    assertSame(mock, MockPlayerRegistry.getByName("tester"));
    assertNull(MockPlayerRegistry.getByName("nobody"));
    assertEquals(1, MockPlayerRegistry.all().size());
  }

  @Test
  @DisplayName("unregister removes the mock")
  void unregisterRemoves() {
    RuntimeMockRTPPlayer mock = newMock("Gone");
    MockPlayerRegistry.register(mock);

    assertSame(mock, MockPlayerRegistry.unregister(mock.uuid()));
    assertFalse(MockPlayerRegistry.isMock(mock.uuid()));
    assertNull(MockPlayerRegistry.get(mock.uuid()));
    assertTrue(MockPlayerRegistry.isEmpty());
  }

  @Test
  @DisplayName("null-tolerant lookups never throw")
  void nullTolerant() {
    assertNull(MockPlayerRegistry.get(null));
    assertNull(MockPlayerRegistry.getByName(null));
    assertNull(MockPlayerRegistry.unregister(null));
    assertFalse(MockPlayerRegistry.isMock(null));
  }

  @Test
  @DisplayName("RuntimeMockRTPPlayer setLocation records destination and completes true")
  void mockTeleportNoOpCompletes() throws Exception {
    RuntimeMockRTPPlayer mock = newMock("Walker");
    RTPLocation dest = new RTPLocation(new MockRTPWorld("world"), 100, 70, -50);

    Boolean ok = mock.setLocation(dest).get();

    assertTrue(ok);
    RTPPlayer resolved = mock;
    assertSame(dest, resolved.getLocation());
    assertTrue(mock.isOnline());
  }
}
