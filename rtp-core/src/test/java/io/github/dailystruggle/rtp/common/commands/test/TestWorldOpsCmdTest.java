package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests verifying {@link TestWorldOpsCmd} ({@code rtp test world-ops}).
 */
class TestWorldOpsCmdTest {

  @TempDir File tempDir;
  private MockRTPServerAccessor accessor;

  @BeforeEach
  void setUp() {
    accessor = RTPTestSetup.install(tempDir);
  }

  private static Map<String, List<String>> noArgs() {
    return new HashMap<>();
  }

  @Test
  void metadata_isStable() {
    TestWorldOpsCmd cmd = new TestWorldOpsCmd(null);
    assertEquals("world-ops", cmd.name());
    assertEquals("rtp.test.worldops", cmd.permission());
    assertNotNull(cmd.description());
    assertFalse(cmd.description().isEmpty());
  }

  @Test
  void runProbe_headlessDefaultStub_passesGracefully() {
    // Under default MockRTPServerAccessor, ALWAYS_INSIDE_BORDER stub is used
    TestWorldOpsCmd.Result r = TestWorldOpsCmd.runProbe();
    assertTrue(r.pass);
    assertTrue(r.heightValid);
    assertTrue(r.minHeight < r.maxHeight);
    assertTrue(r.stubOrNullBorder);
  }

  @Test
  void runProbe_withRealWorldBorder_evaluatesBoundaries() {
    MockRTPWorld world = (MockRTPWorld) accessor.getRTPWorld("world");
    assertNotNull(world);

    // Standard Minecraft world border: +/- 29,999,984 blocks (radius ~ 30,000,000)
    int maxRadius = 29_999_984;
    Square borderSquare = new Square();
    borderSquare.set(GenericMemoryShapeParams.radius, (long) (maxRadius / 16));
    borderSquare.set(GenericMemoryShapeParams.centerX, 0L);
    borderSquare.set(GenericMemoryShapeParams.centerZ, 0L);

    WorldBorder realBorder =
        new WorldBorder(
            () -> borderSquare,
            (RTPLocation loc) ->
                Math.abs(loc.x()) <= maxRadius && Math.abs(loc.z()) <= maxRadius);

    accessor.setWorldBorderFunction(w -> realBorder);

    TestWorldOpsCmd.Result r = TestWorldOpsCmd.runProbe();
    assertTrue(r.pass);
    assertTrue(r.heightValid);
    assertFalse(r.stubOrNullBorder);
    assertTrue(r.centerInside);
    assertFalse(r.farInside);
    assertEquals("ok", r.message);
  }

  @Test
  void runProbe_withNullBorder_handlesGracefully() {
    accessor.setWorldBorderFunction(w -> null);

    // When worldBorderFunction returns null and ALWAYS_INSIDE_BORDER is overridden
    MockRTPServerAccessor nullBorderAccessor =
        new MockRTPServerAccessor(tempDir) {
          @Override
          public Object getWorldBorder(String worldName) {
            return null;
          }
        };
    RTP.serverAccessor = nullBorderAccessor;
    RTPAPI.serverAccessor = nullBorderAccessor;

    MockRTPWorld world = new MockRTPWorld("world");
    nullBorderAccessor.addWorld(world);

    TestWorldOpsCmd.Result r = TestWorldOpsCmd.runProbe();
    assertTrue(r.pass);
    assertTrue(r.heightValid);
    assertTrue(r.stubOrNullBorder);
    assertTrue(r.message.contains("stub/null border"));
  }

  @Test
  void runProbe_invalidHeight_fails() {
    accessor.clearWorlds();
    MockRTPWorld invertedHeightWorld =
        new MockRTPWorld("world") {
          @Override
          public int getMinHeight() {
            return 256;
          }

          @Override
          public int getMaxHeight() {
            return 0;
          }
        };
    accessor.addWorld(invertedHeightWorld);

    TestWorldOpsCmd.Result r = TestWorldOpsCmd.runProbe();
    assertFalse(r.pass);
    assertFalse(r.heightValid);
    assertTrue(r.message.contains("minHeight"));
  }

  @Test
  void runProbe_borderInsideFailsAtCenter_fails() {
    // Border where center is outside
    WorldBorder badBorder =
        new WorldBorder(
            Square::new,
            loc -> false);
    accessor.setWorldBorderFunction(w -> badBorder);

    TestWorldOpsCmd.Result r = TestWorldOpsCmd.runProbe();
    assertFalse(r.pass);
    assertFalse(r.centerInside);
  }

  @Test
  void onCommand_emitsSummaryToPlayer() {
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "player1", null);
    accessor.addPlayer(player);

    boolean ret = new TestWorldOpsCmd(null).onCommand(player.uuid(), noArgs(), null);
    assertTrue(ret);
    assertTrue(
        player.sentMessages.stream().anyMatch(m -> m.contains("world-ops")),
        player.sentMessages.toString());
  }

  @Test
  void onCommand_console_logsSummary() {
    boolean ret = new TestWorldOpsCmd(null).onCommand(RTPAPI.serverId, noArgs(), null);
    assertTrue(ret);
    assertTrue(
        accessor.logMessages.stream().anyMatch(m -> m.contains("world-ops")),
        accessor.logMessages.toString());
  }

  @Test
  void nextCommandNonNull_shortCircuits() {
    assertTrue(
        new TestWorldOpsCmd(null)
            .onCommand(RTPAPI.serverId, noArgs(), new TestWorldOpsCmd(null)));
  }
}
