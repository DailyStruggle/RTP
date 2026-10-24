package io.github.dailystruggle.rtp.common.tasks.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dailystruggle.rtp.api.economy.RTPEconomy;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.mock.MockPlayerRegistry;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.mock.RuntimeMockRTPPlayer;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ADR-121 section 11: cancel refund never credits a BIT mock")
class RTPTeleportCancelRefundMockTest {

  @TempDir File tempDir;

  private MockRTPWorld world;
  private RTPEconomy savedEconomy;
  private final AtomicInteger giveCalls = new AtomicInteger();

  @BeforeEach
  void setUp() {
    MockPlayerRegistry.clear();
    MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir);
    world = new MockRTPWorld("world");
    accessor.addWorld(world);
    savedEconomy = RTPRuntime.economy();
    RTPRuntime.setEconomy(new RTPEconomy() {
      @Override public void give(UUID playerId, double money) { giveCalls.incrementAndGet(); }
      @Override public boolean take(UUID playerId, double money) { return true; }
      @Override public double bal(UUID playerId) { return 0.0; }
    });
  }

  @AfterEach
  void tearDown() {
    MockPlayerRegistry.clear();
    RTPRuntime.setEconomy(savedEconomy);
    TeleportSessions.current().latestTeleportData.clear();
    TeleportSessions.current().priorTeleportData.clear();
    TeleportSessions.current().processingPlayers.clear();
  }

  private static TeleportData pending(io.github.dailystruggle.rtp.api.entity.RTPPlayer sender) {
    TeleportData data = new TeleportData();
    data.completed = false;
    data.cost = 25.0;
    data.sender = sender;
    return data;
  }

  @Test
  @DisplayName("ADR-121 section 11: refund for a mock skips give but still clears session state")
  void mockTarget_isNotRefunded() {
    UUID id = UUID.randomUUID();
    RuntimeMockRTPPlayer mock = new RuntimeMockRTPPlayer(id, "BitMock", new RTPLocation(world, 0, 64, 0));
    MockPlayerRegistry.register(mock);
    TeleportSessions.current().latestTeleportData.put(id, pending(mock));
    TeleportSessions.current().processingPlayers.add(id);

    RTPTeleportCancel.refund(id);

    assertEquals(0, giveCalls.get(), "a mock must never be credited by the real economy");
    assertNull(TeleportSessions.current().latestTeleportData.get(id));
    assertFalse(TeleportSessions.current().processingPlayers.contains(id));
  }

  @Test
  @DisplayName("ADR-121 section 11: refund for a real player still credits the economy")
  void realTarget_isRefunded() {
    UUID id = UUID.randomUUID();
    MockRTPPlayer real = new MockRTPPlayer(id, "Real", new RTPLocation(world, 0, 64, 0));
    TeleportSessions.current().latestTeleportData.put(id, pending(real));

    RTPTeleportCancel.refund(id);

    assertEquals(1, giveCalls.get());
  }
}
