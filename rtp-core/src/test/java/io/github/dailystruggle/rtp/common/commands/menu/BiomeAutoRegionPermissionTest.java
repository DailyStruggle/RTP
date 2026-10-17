package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.BiomeMenuSource;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ADR-063 - permission behaviour of auto-region-by-biome ({@code rtp biome=<b>} without
 * {@code region=}). {@code RTPCmd.compute} passes the command sender's id, so these probe
 * {@link BiomeMenuSource#bestRegionForBiome} with player and console ids against a
 * {@code requirePermission} region.
 */
@DisplayName("ADR-063 - auto-region by biome honours rtp.regions.<r> of the sender")
class BiomeAutoRegionPermissionTest {

  @TempDir Path tempDir;

  private MockRTPServerAccessor accessor;
  private Square defaultShape;
  private Square vipShape;
  private MockRTPPlayer player;

  @BeforeEach
  void setUp() {
    accessor = RTPTestSetup.install(tempDir.toFile());
    RTP.selectionAPI = new SelectionAPI();
    defaultShape = newRegion("default", false);
    vipShape = newRegion("vip", true);
    player = new MockRTPPlayer(UUID.randomUUID(), "leaf26",
        new RTPLocation(new MockRTPWorld("default_world"), 0, 64, 0));
    accessor.addPlayer(player);
  }

  @AfterEach
  void tearDown() {
    if (RTP.selectionAPI != null) RTP.selectionAPI.permRegionLookup.clear();
    RTP.serverAccessor = null;
    RTP.scheduler = null;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
  }

  private Square newRegion(String name, boolean requirePermission) {
    MockRTPWorld world = new MockRTPWorld(name + "_world");
    Square shape = new Square();
    LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
    RegionSettings settings = new RegionSettings(
        name, world, shape, vert,
        false, requirePermission,
        10L, 1000L, 0L, 5, 0.0, 1L, "", false);
    RTP.selectionAPI.permRegionLookup.put(name, new Region(name, settings));
    return shape;
  }

  private static long nextSeedKey = 1_000_000L;

  private static void seedBiome(MemoryShape<?> shape, String biome, long count) throws Exception {
    shape.addBiomeLocation(nextSeedKey, count, biome);
    nextSeedKey += 1_000_000L;
    shape.flushAndRebuild(shape.spatialResolution());
  }

  @Test
  @DisplayName("player without rtp.regions.vip is never routed into gated region vip")
  void playerWithoutRegionPermission_notRoutedToGatedRegion() throws Exception {
    seedBiome(vipShape, "JUNGLE", 50L);
    player.setPermission("rtp.regions.vip", false);

    assertNull(BiomeMenuSource.bestRegionForBiome(player.uuid(), "JUNGLE", "default"));
  }

  @Test
  @DisplayName("player with rtp.regions.vip is routed into gated region vip")
  void playerWithRegionPermission_routedToGatedRegion() throws Exception {
    seedBiome(vipShape, "JUNGLE", 50L);
    player.setPermission("rtp.regions.vip", true);

    assertEquals("vip", BiomeMenuSource.bestRegionForBiome(player.uuid(), "JUNGLE", "default"));
  }

  @Test
  @DisplayName("rtp.regions.* alone does not open gated region vip to auto-region")
  void wildcardOnly_notRoutedToGatedRegion() throws Exception {
    seedBiome(vipShape, "JUNGLE", 50L);
    player.setPermission("rtp.regions.vip", false);
    player.setPermission("rtp.regions.*", true);

    assertNull(BiomeMenuSource.bestRegionForBiome(player.uuid(), "JUNGLE", "default"));
  }

  @Test
  @DisplayName("console sender skips gated region vip even when the target player holds rtp.regions.vip")
  void consoleSender_skipsGatedRegion_regardlessOfTarget() throws Exception {
    seedBiome(vipShape, "JUNGLE", 50L);
    player.setPermission("rtp.regions.vip", true);

    assertNull(BiomeMenuSource.bestRegionForBiome(RTP.serverId, "JUNGLE", "default"));
  }

  @Test
  @DisplayName("preferred region is kept without a permission check when it observes the biome")
  void preferredGatedRegion_keptWithoutPermissionCheck() throws Exception {
    seedBiome(vipShape, "JUNGLE", 50L);
    player.setPermission("rtp.regions.vip", false);

    assertEquals("vip", BiomeMenuSource.bestRegionForBiome(player.uuid(), "JUNGLE", "vip"));
  }

  @Test
  @DisplayName("only the first listed biome picks the region; fallback may leave the player's world")
  void firstBiomeOnly_fallbackCrossesWorlds() throws Exception {
    newRegion("wild", false);
    seedBiome(defaultShape, "PLAINS", 100L);
    seedBiome((Square) RTP.selectionAPI.getRegion("wild").shape, "MEADOW", 10L);

    // RTPCmd passes biomeList.get(0) only: MEADOW, not PLAINS.
    assertEquals("wild", BiomeMenuSource.bestRegionForBiome(player.uuid(), "MEADOW", "default"));
    assertEquals("wild_world", RTP.selectionAPI.getRegion("wild").getWorld().name());
  }
}
