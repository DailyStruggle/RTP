package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.model.ChartModel;
import io.github.dailystruggle.mapsapi.model.RegionBadLocations;
import io.github.dailystruggle.mapsapi.render.RegionBadLocationsRenderer;
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
 * REQ-RTP-MAP-006 / ADR-047 - Stage 2 (PR2a) resolver coverage for the
 * region-shape two-tone map used by the admin "Visualizations -> Region
 * shape" entry. Mirrors the structure of {@code BadPointsHeatmapResolverTest}:
 *
 * <ul>
 *   <li>Happy path: known region + non-empty badKeysCache -> a
 *       {@link RegionBadLocations} carrying the same bad keys re-encoded
 *       to MemoryShape's packed long form, paired with
 *       {@link RegionBadLocationsRenderer#INSTANCE}.</li>
 *   <li>Failure: unknown region -> {@code UnresolvableChartSpecException}.</li>
 *   <li>Failure: empty badKeysCache -> {@code UnresolvableChartSpecException}.</li>
 *   <li>Failure: wrong {@code ChartSpec.Kind} -> {@code UnresolvableChartSpecException}.</li>
 *   <li>Failure: null spec -> {@code UnresolvableChartSpecException}, not NPE.</li>
 * </ul>
 *
 * <p>S-005: no chunk I/O on any path. S-004: every failure exits via a
 * thrown exception, never a silent {@code null}.
 */
@DisplayName("REQ-RTP-MAP-006 - RegionBadLocationsShapeResolver contract")
class RegionBadLocationsShapeResolverTest {

  @TempDir Path tempDir;

  private RegionBadLocationsShapeResolver resolver;
  private Region region;
  private Square shape;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir.toFile());
    MockRTPWorld world = new MockRTPWorld("region_shape_world");
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
    resolver = new RegionBadLocationsShapeResolver();
  }

  @AfterEach
  void tearDown() {
    if (RTP.selectionAPI != null) RTP.selectionAPI.permRegionLookup.clear();
    RTP.serverAccessor = null;
    RTP.scheduler = null;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
  }

  /** Same seeding helper as {@code BadPointsHeatmapResolverTest}. */
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
  @DisplayName("Happy path: non-empty cache -> RegionBadLocations + RegionBadLocationsRenderer")
  void happyPath_returnsRegionBadLocations() throws Exception {
    long k0 = shape.xzToLocation(10L, 20L);
    long k1 = shape.xzToLocation(-30L, 40L);
    long k2 = shape.xzToLocation(50L, -60L);
    long[] keys = new long[] {k0, k1, k2};
    java.util.Arrays.sort(keys);
    seedBadKeys(shape, keys);

    ChartSpecResolver.Resolution res = resolver.resolve(
        ChartSpec.of(ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE, "default"));

    assertNotNull(res);
    assertSame(RegionBadLocationsRenderer.INSTANCE, res.renderer(),
        "resolver shall reuse the renderer singleton (no per-call allocation)");
    ChartModel model = res.model();
    RegionBadLocations rbl = assertInstanceOf(RegionBadLocations.class, model);
    assertEquals("default", rbl.regionName());
    assertEquals(3, rbl.badCount(),
        "every decoded bad key shall round-trip into the snapshot");
    assertTrue(rbl.radius() > 0,
        "radius shall be strictly positive (RegionBadLocations canonical constructor invariant)");
    // Centre derived from bbox midpoint of decoded XZ. Square.xzToLocation
    // may round-trip through its internal index encoding, so we assert the
    // centre is *inside* the observed XZ extent rather than hardcoding the
    // pre-encoding midpoint.
    long[] decodedKeys = rbl.badKeys();
    int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
    int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
    for (long k : decodedKeys) {
      int bx = (int) (k >> 32);
      int bz = (int) k;
      if (bx < minX) minX = bx;
      if (bx > maxX) maxX = bx;
      if (bz < minZ) minZ = bz;
      if (bz > maxZ) maxZ = bz;
    }
    assertTrue(rbl.centerX() >= minX && rbl.centerX() <= maxX,
        "centerX shall lie inside the decoded XZ bbox [" + minX + "," + maxX + "], got " + rbl.centerX());
    assertTrue(rbl.centerZ() >= minZ && rbl.centerZ() <= maxZ,
        "centerZ shall lie inside the decoded XZ bbox [" + minZ + "," + maxZ + "], got " + rbl.centerZ());
  }

  @Test
  @DisplayName("Failure: unknown region name -> UnresolvableChartSpecException")
  void unknownRegion_throws() {
    RTP.selectionAPI.permRegionLookup.clear();
    ChartSpec spec = ChartSpec.of(ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE, "no_such_region");
    assertThrows(ChartSpecResolver.UnresolvableChartSpecException.class,
        () -> resolver.resolve(spec));
  }

  @Test
  @DisplayName("Failure: empty bad-keys cache -> UnresolvableChartSpecException")
  void emptyCache_throws() throws Exception {
    seedBadKeys(shape, new long[0]);
    ChartSpec spec = ChartSpec.of(ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE, "default");
    ChartSpecResolver.UnresolvableChartSpecException ex = assertThrows(
        ChartSpecResolver.UnresolvableChartSpecException.class,
        () -> resolver.resolve(spec));
    assertTrue(ex.getMessage().contains("no bad-location data"),
        "exception message shall name the empty-cache condition: " + ex.getMessage());
  }

  @Test
  @DisplayName("Failure: wrong ChartSpec.Kind -> UnresolvableChartSpecException")
  void wrongKind_throws() {
    ChartSpec spec = ChartSpec.of(ChartSpec.Kind.BAD_POINTS_HEATMAP, "default");
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
