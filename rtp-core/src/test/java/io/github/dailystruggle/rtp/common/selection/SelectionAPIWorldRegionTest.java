package io.github.dailystruggle.rtp.common.selection;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.GenericVerticalAdjustorKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-065 - world-override regions. Verifies that {@link SelectionAPI#worldRegion}
 * rebinds a base region to a requested target world (so {@code /rtp world:nether}
 * lands in the nether rather than following the base region's configured world),
 * caches the synthetic region in {@code tempRegions} keyed by the target world's
 * UUID, returns the base region unchanged when it already targets the world, and
 * returns {@code null} for an unknown world.
 */
@DisplayName("ADR-065 - SelectionAPI.worldRegion override + caching")
class SelectionAPIWorldRegionTest {

  @TempDir Path tempDir;

  private MockRTPServerAccessor accessor;

  @BeforeEach
  void setUp() {
    accessor = RTPTestSetup.install(tempDir.toFile());
    RTP.selectionAPI = new SelectionAPI();
    // Base region "default" lives in world "default_world".
    MockRTPWorld defaultWorld = new MockRTPWorld("default_world");
    accessor.addWorld(defaultWorld);
    Square shape = new Square();
    LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
    RegionSettings settings = new RegionSettings(
        "default", defaultWorld, shape, vert,
        false, false,
        10L, 1000L, 0L, 5, 0.0, 1L, "", false);
    RTP.selectionAPI.permRegionLookup.put("default", new Region("default", settings));
    // A distinct target world (e.g. the nether) the player wants to RTP into.
    accessor.addWorld(new MockRTPWorld("nether_world"));
    // A dimension-suffixed world so the nether/end vert seeding kicks in.
    accessor.addWorld(new MockRTPWorld("world_nether"));
    accessor.addWorld(new MockRTPWorld("world_the_end"));
  }

  @AfterEach
  void tearDown() {
    if (RTP.selectionAPI != null) {
      RTP.selectionAPI.permRegionLookup.clear();
      RTP.selectionAPI.tempRegions.clear();
    }
    RTP.serverAccessor = null;
    RTP.scheduler = null;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
  }

  @Test
  @DisplayName("rebinds base region to the requested world and names it <base>_<world>")
  void worldRegion_rebindsToRequestedWorld() {
    Region region = RTP.selectionAPI.worldRegion("default", "nether_world");
    assertNotNull(region);
    assertEquals("default_nether_world", region.name);
    assertNotNull(region.getWorld());
    assertEquals("nether_world", region.getWorld().name());
  }

  @Test
  @DisplayName("caches the override in tempRegions keyed by world id and reuses it")
  void worldRegion_cachedByWorldId() {
    Region first = RTP.selectionAPI.worldRegion("default", "nether_world");
    Region second = RTP.selectionAPI.worldRegion("default", "nether_world");
    assertSame(first, second, "repeated calls must reuse the cached region");
    assertTrue(RTP.selectionAPI.tempRegions.containsValue(first));
    assertSame(first,
        RTP.selectionAPI.tempRegions.get(accessor.getRTPWorld("nether_world").id()));
  }

  @Test
  @DisplayName("returns the base region unchanged when it already targets the world")
  void worldRegion_baseAlreadyTargetsWorld() {
    Region base = RTP.selectionAPI.getRegion("default");
    Region region = RTP.selectionAPI.worldRegion("default", "default_world");
    assertSame(base, region, "no synthetic region when base already targets the world");
  }

  @Test
  @DisplayName("seeds a dimension-appropriate (LINEAR, downward, no-skylight) vert for _nether worlds")
  void worldRegion_seedsNetherVert() {
    Region region = RTP.selectionAPI.worldRegion("default", "world_nether");
    assertNotNull(region);
    var vert = (LinearAdjustor) region.getVert();
    assertNotNull(vert);
    assertEquals("linear", vert.name);
    assertEquals(2, vert.getNumber(GenericVerticalAdjustorKeys.direction, 0).intValue());
    assertTrue(!vert.requiresSkyLight(), "nether vert must not require sky light");
  }

  @Test
  @DisplayName("seeds a dimension-appropriate vert for _the_end worlds")
  void worldRegion_seedsEndVert() {
    Region region = RTP.selectionAPI.worldRegion("default", "world_the_end");
    assertNotNull(region);
    var vert = (LinearAdjustor) region.getVert();
    assertNotNull(vert);
    assertEquals("linear", vert.name);
    assertEquals(2, vert.getNumber(GenericVerticalAdjustorKeys.direction, 0).intValue());
    assertTrue(!vert.requiresSkyLight(), "end vert must not require sky light");
  }

  @Test
  @DisplayName("leaves the base vert unchanged for non-dimension worlds")
  void worldRegion_keepsBaseVertForOverworld() {
    Region region = RTP.selectionAPI.worldRegion("default", "nether_world");
    assertNotNull(region);
    var vert = (LinearAdjustor) region.getVert();
    assertNotNull(vert);
    // base LinearAdjustor default direction is 0 (not the seeded 2)
    assertEquals(0, vert.getNumber(GenericVerticalAdjustorKeys.direction, 0).intValue());
  }

  @Test
  @DisplayName("returns null for an unknown world or missing base region")
  void worldRegion_nullOnUnknownInputs() {
    assertNull(RTP.selectionAPI.worldRegion("default", "no_such_world"));
    assertNull(RTP.selectionAPI.worldRegion("default", null));
    assertNull(RTP.selectionAPI.worldRegion("default", ""));
  }
}
