package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.effectsapi.common.hologram.HologramHandle;
import io.github.dailystruggle.effectsapi.common.hologram.HologramProvider;
import io.github.dailystruggle.effectsapi.common.hologram.HologramRegistry;
import io.github.dailystruggle.effectsapi.common.hologram.VirtualHologramHandle;
import io.github.dailystruggle.effectsapi.common.volumetric.Vector3d;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestHologramCmdTest {
  private TestHologramCmd cmd;
  private MockRTPServerAccessor serverAccessor;

  @BeforeEach
  void setUp() {
    serverAccessor = new MockRTPServerAccessor(new File("."));
    RTP.serverAccessor = serverAccessor;
    RTP.scheduler = serverAccessor.getScheduler();
    cmd = new TestHologramCmd(null);
  }

  @Test
  @DisplayName("Command metadata conforms to rtp.test standards")
  void testCommandMetadata() {
    assertEquals("hologram", cmd.name());
    assertEquals("rtp.test", cmd.permission());
    assertNotNull(cmd.description());
  }

  @Test
  @DisplayName("Console execution without player dumps active provider status")
  void testConsoleExecutionStatusDump() {
    HologramRegistry.register(null); // virtual / none
    Map<String, List<String>> params = new HashMap<>();

    boolean result = cmd.onCommand(RTPAPI.serverId, params, null);
    assertTrue(result);
  }

  @Test
  @DisplayName("Custom provider handles lifecycle on player spawn")
  void testCustomProviderExecution() {
    AtomicBoolean spawned = new AtomicBoolean(false);
    AtomicBoolean closed = new AtomicBoolean(false);

    HologramProvider customProvider = new HologramProvider() {
      @Override
      public HologramHandle spawnHologram(String id, String worldName, Vector3d position, List<String> lines) {
        spawned.set(true);
        return new VirtualHologramHandle(id, worldName, position, lines) {
          @Override
          public void close() {
            super.close();
            closed.set(true);
          }
        };
      }
    };
    HologramRegistry.register(customProvider);

    // Create a mock player with location
    UUID playerUuid = UUID.randomUUID();
    MockRTPWorld world = new MockRTPWorld("world");
    MockRTPPlayer player = new MockRTPPlayer(playerUuid, "TestHoloPlayer", new RTPLocation(world, 0, 64, 0));
    serverAccessor.addPlayer(player);

    Map<String, List<String>> params = new HashMap<>();
    params.put("player", List.of("TestHoloPlayer"));
    params.put("seconds", List.of("1"));

    boolean result = cmd.onCommand(playerUuid, params, null);
    assertTrue(result);
    assertTrue(spawned.get());
  }

  @Test
  @DisplayName("Unknown player reports warning and fails safe")
  void testUnknownPlayerReportsError() {
    Map<String, List<String>> params = new HashMap<>();
    params.put("player", List.of("NonExistentPlayerXYZ"));

    boolean result = cmd.onCommand(UUID.randomUUID(), params, null);
    assertTrue(result);
  }
}
