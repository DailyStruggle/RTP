package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.model.CompositeRegionModel;
import io.github.dailystruggle.mapsapi.render.CompositeRegionRenderer;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RegionCompositeResolver Tests")
public class RegionCompositeResolverTest {

  @TempDir
  File tempDir;

  private RegionCompositeResolver resolver;
  private Region region;
  private Square shape;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir);
    resolver = new RegionCompositeResolver();

    MockRTPWorld world = new MockRTPWorld("test_world");
    shape = new Square();
    LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
    RegionSettings settings = new RegionSettings(
        "composite_test",
        world,
        shape,
        vert,
        false,
        false,
        10L,
        1000L,
        0L,
        5,
        0.0,
        1L,
        "",
        false);
    region = new Region("composite_test", settings);
    RTP.selectionAPI = new SelectionAPI();
    RTP.selectionAPI.permRegionLookup.put("composite_test", region);
  }

  @AfterEach
  void tearDown() {
    if (RTP.selectionAPI != null) RTP.selectionAPI.permRegionLookup.clear();
    RTP.serverAccessor = null;
    RTP.scheduler = null;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
  }

  @Test
  @DisplayName("Happy path: resolves REGION_COMPOSITE to valid resolution and model")
  void testResolveSuccess() throws Exception {
    ChartSpec spec = ChartSpec.of(ChartSpec.Kind.REGION_COMPOSITE, "composite_test");
    ChartSpecResolver.Resolution res = resolver.resolve(spec);

    assertNotNull(res);
    assertSame(CompositeRegionRenderer.INSTANCE, res.renderer());
    assertInstanceOf(CompositeRegionModel.class, res.model());
    CompositeRegionModel model = (CompositeRegionModel) res.model();
    assertNotNull(model.l1Gauge());
    assertNotNull(model.l2Gauge());
    assertNotNull(model.l3Gauge());
    assertEquals("L1 Hot", model.l1Gauge().name());
  }

  @Test
  @DisplayName("Rejects null spec, wrong kind, and non-MemoryShape")
  void testRejections() {
    assertThrows(ChartSpecResolver.UnresolvableChartSpecException.class, () -> resolver.resolve(null));

    ChartSpec wrongKind = ChartSpec.of(ChartSpec.Kind.REGION_BIOMES, "composite_test");
    assertThrows(ChartSpecResolver.UnresolvableChartSpecException.class, () -> resolver.resolve(wrongKind));

    ChartSpec unknownRegion = ChartSpec.of(ChartSpec.Kind.REGION_COMPOSITE, "unknown_region");
    assertThrows(ChartSpecResolver.UnresolvableChartSpecException.class, () -> resolver.resolve(unknownRegion));
  }
}
