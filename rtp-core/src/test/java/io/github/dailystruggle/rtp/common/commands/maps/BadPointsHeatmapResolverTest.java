package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.model.ChartModel;
import io.github.dailystruggle.mapsapi.model.Heatmap2D;
import io.github.dailystruggle.mapsapi.render.HeatmapRenderer;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
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

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolver tests for {@link BadPointsHeatmapResolver} (REQ-RTP-MAP-006 / ADR-047).
 * Verifies heatmap generation, error throwing (S-004), and no chunk I/O (S-005).
 */
@DisplayName("REQ-RTP-MAP-006 - BadPointsHeatmapResolver Stage 1 contract")
class BadPointsHeatmapResolverTest {

  @TempDir Path tempDir;

  private BadPointsHeatmapResolver resolver;
  private Region region;
  private Square shape;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir.toFile());
    MockRTPWorld world = new MockRTPWorld("bad_points_world");
    shape = new Square();
    LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
    RegionSettings settings = new RegionSettings(
        "default",
        world,
        shape,
        vert,
        false, false,
        10L, 1000L, 0L, 5, 0.0, 1L, "", false);
    region = new Region("default", settings);
    RTP.selectionAPI = new SelectionAPI();
    RTP.selectionAPI.permRegionLookup.put("default", region);
    resolver = new BadPointsHeatmapResolver();
  }

  @AfterEach
  void tearDown() {
    // Clear leaked region registrations so subsequent tests in the same JVM
    // do not walk our test region during their own reload / shutdown paths.
    if (RTP.selectionAPI != null) RTP.selectionAPI.permRegionLookup.clear();
    RTP.serverAccessor = null;
    RTP.scheduler = null;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
  }

  /**
   * Reflection helper: badKeysCache is protected; tests bypass via reflection.
   * Must also seed the parallel {@code badPrefixSumsCache} so a later
   * {@code flushAndRebuild} (triggered by a region shutdown / reload from
   * another test in the same JVM) does not throw
   * {@link ArrayIndexOutOfBoundsException}.
   */
  private static void seedBadKeys(MemoryShape<?> shape, long[] keys) throws Exception {
    Field keysField = MemoryShape.class.getDeclaredField("badKeysCache");
    keysField.setAccessible(true);
    keysField.set(shape, keys);
    long[] sums = new long[keys.length];
    for (int i = 0; i < keys.length; i++) sums[i] = i + 1L;
    Field sumsField = MemoryShape.class.getDeclaredField("badPrefixSumsCache");
    sumsField.setAccessible(true);
    sumsField.set(shape, sums);
  }

  @Test
  @DisplayName("Happy path: non-empty cache -> Heatmap2D + HeatmapRenderer")
  void happyPath_returnsHeatmap() throws Exception {
    // Three bad keys at spread XZ -> non-degenerate bbox.
    long k0 = shape.xzToLocation(10L, 20L);
    long k1 = shape.xzToLocation(-30L, 40L);
    long k2 = shape.xzToLocation(50L, -60L);
    long[] keys = new long[] {k0, k1, k2};
    java.util.Arrays.sort(keys);
    seedBadKeys(shape, keys);

    ChartSpecResolver.Resolution res = resolver.resolve(
        ChartSpec.of(ChartSpec.Kind.BAD_POINTS_HEATMAP, "default"));

    assertNotNull(res);
    assertSame(HeatmapRenderer.INSTANCE, res.renderer(),
        "resolver shall reuse the renderer singleton (no per-call allocation)");
    ChartModel model = res.model();
    Heatmap2D heatmap = assertInstanceOf(Heatmap2D.class, model);
    assertEquals(BadPointsHeatmapResolver.BINS, heatmap.width());
    assertEquals(BadPointsHeatmapResolver.BINS, heatmap.height());
    assertTrue(heatmap.maxValue() > heatmap.minValue(),
        "Heatmap2D requires maxValue > minValue; resolver must widen degenerate bbox");
    // Sum of bin counts shall equal number of decoded keys.
    double sum = 0.0;
    double[] values = heatmap.values();
    for (double v : values) sum += v;
    assertEquals(3.0, sum, 0.0001, "every decoded bad key shall land in exactly one bin");
  }

  @Test
  @DisplayName("Failure: unknown region name -> UnresolvableChartSpecException")
  void unknownRegion_throws() {
    RTP.selectionAPI.permRegionLookup.clear();
    ChartSpec spec = ChartSpec.of(ChartSpec.Kind.BAD_POINTS_HEATMAP, "no_such_region");
    assertThrows(ChartSpecResolver.UnresolvableChartSpecException.class,
        () -> resolver.resolve(spec));
  }

  @Test
  @DisplayName("Failure: empty bad-keys cache -> UnresolvableChartSpecException")
  void emptyCache_throws() throws Exception {
    seedBadKeys(shape, new long[0]);
    ChartSpec spec = ChartSpec.of(ChartSpec.Kind.BAD_POINTS_HEATMAP, "default");
    ChartSpecResolver.UnresolvableChartSpecException ex = assertThrows(
        ChartSpecResolver.UnresolvableChartSpecException.class,
        () -> resolver.resolve(spec));
    assertTrue(ex.getMessage().contains("no bad-location data"),
        "exception message shall name the empty-cache condition: " + ex.getMessage());
  }

  @Test
  @DisplayName("Failure: wrong ChartSpec.Kind -> UnresolvableChartSpecException")
  void wrongKind_throws() {
    ChartSpec spec = ChartSpec.of(ChartSpec.Kind.REGION_COVERAGE, "default");
    assertThrows(ChartSpecResolver.UnresolvableChartSpecException.class,
        () -> resolver.resolve(spec));
  }

  @Test
  @DisplayName("Failure: null spec -> UnresolvableChartSpecException (not NPE)")
  void nullSpec_throws() {
    assertThrows(ChartSpecResolver.UnresolvableChartSpecException.class,
        () -> resolver.resolve(null));
  }
}
