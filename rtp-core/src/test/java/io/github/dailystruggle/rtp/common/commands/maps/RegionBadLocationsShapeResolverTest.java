package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.PaletteIndex;
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
 * REQ-RTP-MAP-006 - {@link RegionBadLocationsShapeResolver} tests.
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

  /** Reflectively seeds the MemoryShape's bad-keys cache + prefix sums so
   *  {@link MemoryShape#isKnownBad(long)} returns true for the seeded keys. */
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

  /** Reflectively seeds the per-run rejection-cause cache, aligned 1:1 with
   *  the bad-keys cache, so {@link MemoryShape#causeAt(long)} resolves each
   *  seeded key to its recorded {@link io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes}. */
  private static void seedBadCauses(MemoryShape<?> shape, byte[] causes) throws Exception {
    Field causesField = MemoryShape.class.getDeclaredField("badCauseCache");
    causesField.setAccessible(true);
    causesField.set(shape, causes);
  }

  private static int countPalette(byte[] palette, byte color) {
    int n = 0;
    for (byte b : palette) if (b == color) n++;
    return n;
  }

  @Test
  @DisplayName("Happy path: non-empty cache -> palette buffer with at least one RED pixel")
  void happyPath_returnsPopulatedPaletteBuffer() throws Exception {
    // The resolver classifies one block per canvas pixel (128x128), so an
    // isolated bad key has a sub-pixel chance of being struck by the
    // pixel grid against a Square with default range (~260k locations).
    // Seed a contiguous 64-block-wide block-patch so the RED region is
    // guaranteed to land on multiple canvas pixels regardless of stride.
    java.util.List<Long> raw = new java.util.ArrayList<>();
    for (int dx = -32; dx <= 32; dx++) {
      for (int dz = -32; dz <= 32; dz++) {
        long k = shape.xzToLocation((long) dx + 80L, (long) dz + 80L);
        raw.add(k);
      }
    }
    long[] keys = raw.stream().distinct().mapToLong(Long::longValue).toArray();
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
    assertEquals(128, rbl.width(), "resolver emits canvas-aligned 128-wide buffer");
    assertEquals(128, rbl.height(), "resolver emits canvas-aligned 128-tall buffer");
    byte[] palette = rbl.palette();
    assertTrue(countPalette(palette, PaletteIndex.GREEN) > 0,
        "in-region pixels shall be GREEN");
    assertTrue(countPalette(palette, PaletteIndex.RED) > 0,
        "seeded bad locations shall produce at least one RED pixel; "
            + "got palette histogram BLACK=" + countPalette(palette, PaletteIndex.BLACK)
            + " GREEN=" + countPalette(palette, PaletteIndex.GREEN)
            + " RED=" + countPalette(palette, PaletteIndex.RED));
  }

  @Test
  @DisplayName("Empty cache: still resolves to a populated GREEN+BLACK buffer (no failure)")
  void emptyCache_resolvesToGreenDisk() throws Exception {
    seedBadKeys(shape, new long[0]);
    ChartSpec spec = ChartSpec.of(ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE, "default");

    ChartSpecResolver.Resolution res = resolver.resolve(spec);

    assertNotNull(res, "empty bad-set is no longer a failure path");
    RegionBadLocations rbl = assertInstanceOf(RegionBadLocations.class, res.model());
    byte[] palette = rbl.palette();
    assertEquals(0, countPalette(palette, PaletteIndex.RED),
        "no bad keys -> no RED pixels");
    assertTrue(countPalette(palette, PaletteIndex.GREEN) > 0,
        "in-region pixels shall still be GREEN even when bad set is empty");
  }

  @Test
  @DisplayName("Cause colorization: per-run cause is rendered as its distinct palette shade, not generic RED")
  void causeColorization_rendersCauseSpecificShade() throws Exception {
    // Seed a contiguous block-patch (as in the happy path) so the bad run
    // is guaranteed to strike multiple canvas pixels, then tag every key in
    // the run with the same recorded cause (biome). The resolver must color
    // those pixels with causeColor(biome) rather than the generic RED.
    java.util.List<Long> raw = new java.util.ArrayList<>();
    for (int dx = -32; dx <= 32; dx++) {
      for (int dz = -32; dz <= 32; dz++) {
        raw.add(shape.xzToLocation((long) dx + 80L, (long) dz + 80L));
      }
    }
    long[] keys = raw.stream().distinct().mapToLong(Long::longValue).toArray();
    java.util.Arrays.sort(keys);
    seedBadKeys(shape, keys);

    byte biomeCause =
        (byte) io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes.biome.ordinal();
    byte[] causes = new byte[keys.length];
    java.util.Arrays.fill(causes, biomeCause);
    seedBadCauses(shape, causes);

    ChartSpecResolver.Resolution res = resolver.resolve(
        ChartSpec.of(ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE, "default"));

    RegionBadLocations rbl = assertInstanceOf(RegionBadLocations.class, res.model());
    byte[] palette = rbl.palette();

    byte expected = RegionBadLocationsShapeResolver.causeColor(
        io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes.biome.ordinal());
    assertTrue(expected != PaletteIndex.RED,
        "biome cause shall map to a non-RED shade so it is distinguishable");
    assertTrue(countPalette(palette, expected) > 0,
        "bad locations tagged 'biome' shall render as causeColor(biome)=" + expected
            + "; got histogram for that shade=" + countPalette(palette, expected)
            + " RED=" + countPalette(palette, PaletteIndex.RED));
    assertEquals(0, countPalette(palette, PaletteIndex.RED),
        "no key was tagged with a RED-mapped cause, so no generic RED pixels expected");
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
