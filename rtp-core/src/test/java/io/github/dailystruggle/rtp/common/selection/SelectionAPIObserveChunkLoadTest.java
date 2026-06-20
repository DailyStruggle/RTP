package io.github.dailystruggle.rtp.common.selection;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.Region;
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
import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opportunistic on-load biome harvest ({@code PerformanceKeys.checkOnChunkLoads}).
 * Verifies {@link SelectionAPI#observeChunkLoad} records the loaded chunk's biome
 * into the region's spatial memory when the flag is enabled, and is a no-op when
 * the flag is disabled or the chunk belongs to another world.
 */
@DisplayName("SelectionAPI.observeChunkLoad - on-load biome harvest")
class SelectionAPIObserveChunkLoadTest {

  @TempDir Path tempDir;

  private MockRTPServerAccessor accessor;
  private MockRTPWorld world;

  @BeforeEach
  void setUp() {
    accessor = RTPTestSetup.install(tempDir.toFile());
    RTP.selectionAPI = new SelectionAPI();
    world = new MockRTPWorld("default_world");
    accessor.addWorld(world);
    Square shape = new Square();
    LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
    RegionSettings settings = new RegionSettings(
        "default", world, shape, vert,
        false, false,
        10L, 1000L, 0L, 5, 0.0, 1L, "", false);
    RTP.selectionAPI.permRegionLookup.put("default", new Region("default", settings));
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

  @SuppressWarnings("unchecked")
  private void setCheckOnChunkLoads(boolean value) {
    ConfigParser<PerformanceKeys> perf =
        (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
    EnumMap<PerformanceKeys, Object> data = new EnumMap<>(PerformanceKeys.class);
    data.put(PerformanceKeys.checkOnChunkLoads, value);
    perf.setData(data);
  }

  private MemoryShape<?> shape() {
    return (MemoryShape<?>) RTP.selectionAPI.getRegion("default").getShape();
  }

  @Test
  @DisplayName("registers the loaded chunk's biome into the in-range region when enabled")
  void registersBiomeWhenEnabled() {
    setCheckOnChunkLoads(true);
    MemoryShape<?> shape = shape();
    shape.flushAndRebuild(shape.spatialResolution);
    assertEquals(0, shape.getEffectiveGoodCount(), "precondition: no biome recorded yet");

    RTP.selectionAPI.observeChunkLoad(world, 10, 10);

    shape.flushAndRebuild(shape.spatialResolution);
    assertTrue(shape.getEffectiveGoodCount() > 0,
        "the loaded chunk's biome must be recorded into the region's spatial memory");
  }

  @Test
  @DisplayName("is a no-op when checkOnChunkLoads is disabled")
  void noOpWhenDisabled() {
    setCheckOnChunkLoads(false);
    MemoryShape<?> shape = shape();

    RTP.selectionAPI.observeChunkLoad(world, 10, 10);

    shape.flushAndRebuild(shape.spatialResolution);
    assertEquals(0, shape.getEffectiveGoodCount(),
        "no biome should be recorded while the flag is off");
  }

  @Test
  @DisplayName("is a no-op for a chunk in a world no region targets")
  void noOpForUnrelatedWorld() {
    setCheckOnChunkLoads(true);
    MockRTPWorld other = new MockRTPWorld("other_world");
    accessor.addWorld(other);
    MemoryShape<?> shape = shape();

    RTP.selectionAPI.observeChunkLoad(other, 10, 10);

    shape.flushAndRebuild(shape.spatialResolution);
    assertEquals(0, shape.getEffectiveGoodCount(),
        "a chunk in an untargeted world must not touch the region's memory");
  }
}
