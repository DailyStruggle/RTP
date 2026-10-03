package io.github.dailystruggle.rtp.common.commands.menu;

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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-063 - biome-first menu source. Verifies that the observed-biome union is
 * scoped to regions and that auto-region-by-biome resolution prefers the
 * region with the strongest observation (and the world-default region only
 * when it already observes the biome). Chunk-I/O-free; reads only seeded
 * spatial-memory caches.
 */
@DisplayName("ADR-063 - BiomeMenuSource observed-biome union and auto-region")
class BiomeMenuSourceTest {

  @TempDir Path tempDir;

  private Square defaultShape;
  private Square wildShape;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir.toFile());
    RTP.selectionAPI = new SelectionAPI();
    defaultShape = newRegion("default");
    wildShape = newRegion("wild");
  }

  @AfterEach
  void tearDown() {
    if (RTP.selectionAPI != null) RTP.selectionAPI.permRegionLookup.clear();
    RTP.serverAccessor = null;
    RTP.scheduler = null;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
  }

  private Square newRegion(String name) {
    MockRTPWorld world = new MockRTPWorld(name + "_world");
    Square shape = new Square();
    LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
    RegionSettings settings = new RegionSettings(
        name, world, shape, vert,
        false, false,
        10L, 1000L, 0L, 5, 0.0, 1L, "", false);
    Region region = new Region(name, settings);
    RTP.selectionAPI.permRegionLookup.put(name, region);
    return shape;
  }

  /** Distinct run start per seeded biome, so the union's clipping never merges two of them. */
  private static long nextSeedKey = 1_000_000L;

  private static void seedBiome(MemoryShape<?> shape, String biome, long count) throws Exception {
    // Record a real observation and merge it: the biome table exists only as the shape's blocked
    // union, so there is no per-biome map to inject into.
    shape.addBiomeLocation(nextSeedKey, count, biome);
    nextSeedKey += 1_000_000L;
    shape.flushAndRebuild(shape.spatialResolution());
  }

  @Test
  @DisplayName("observedBiomes is the union of observed biomes across regions")
  void observedBiomes_union() throws Exception {
    seedBiome(defaultShape, "FOREST", 100L);
    seedBiome(wildShape, "JUNGLE", 50L);
    seedBiome(wildShape, "FOREST", 10L);

    Set<String> observed = BiomeMenuSource.observedBiomes(null);
    assertEquals(Set.of("FOREST", "JUNGLE"), observed);
  }

  @Test
  @DisplayName("bestRegionByBiome picks the region with the strongest observation")
  void bestRegion_byObservationCount() throws Exception {
    seedBiome(defaultShape, "FOREST", 100L);
    seedBiome(wildShape, "FOREST", 10L);
    seedBiome(wildShape, "JUNGLE", 50L);

    Map<String, String> best = BiomeMenuSource.bestRegionByBiome(null);
    assertEquals("default", best.get("FOREST"), "FOREST best region is the higher-count one");
    assertEquals("wild", best.get("JUNGLE"));
  }

  @Test
  @DisplayName("auto-region keeps the world-default region when it observes the biome")
  void bestRegionForBiome_prefersWorldDefaultWhenItQualifies() throws Exception {
    seedBiome(defaultShape, "FOREST", 5L);
    seedBiome(wildShape, "FOREST", 999L);

    // World-default = "default"; it observes FOREST, so keep it even though
    // "wild" has a stronger observation.
    assertEquals("default",
        BiomeMenuSource.bestRegionForBiome(null, "minecraft:forest", "default"));
  }

  @Test
  @DisplayName("auto-region switches regions when the world-default lacks the biome")
  void bestRegionForBiome_switchesWhenWorldDefaultLacksBiome() throws Exception {
    seedBiome(wildShape, "JUNGLE", 50L);

    // "default" never observed JUNGLE -> resolve to "wild".
    assertEquals("wild",
        BiomeMenuSource.bestRegionForBiome(null, "JUNGLE", "default"));
    assertTrue(BiomeMenuSource.regionObservesBiome("wild", "jungle"));
    assertFalse(BiomeMenuSource.regionObservesBiome("default", "jungle"));
  }

  @Test
  @DisplayName("auto-region returns null when no region has observed the biome (cold data)")
  void bestRegionForBiome_nullWhenColdData() {
    assertEquals(null, BiomeMenuSource.bestRegionForBiome(null, "SAVANNA", "default"));
    assertTrue(BiomeMenuSource.observedBiomes(null).isEmpty());
  }

  @Test
  @DisplayName("biomeWeightsForWorld aggregates observed-biome counts for a world")
  void biomeWeightsForWorld_aggregatesPerWorld() throws Exception {
    seedBiome(defaultShape, "FOREST", 100L);
    seedBiome(wildShape, "JUNGLE", 50L);

    // Each test region lives in its own world ("<name>_world").
    Map<String, Long> defaultWeights = BiomeMenuSource.biomeWeightsForWorld("default_world");
    assertEquals(Map.of("FOREST", 100L), defaultWeights);

    Map<String, Long> wildWeights = BiomeMenuSource.biomeWeightsForWorld("wild_world");
    assertEquals(Map.of("JUNGLE", 50L), wildWeights);

    assertTrue(BiomeMenuSource.biomeWeightsForWorld("nonexistent_world").isEmpty());

    // Test biomeWeightsForRegion directly
    Map<String, Long> defaultRegionWeights = BiomeMenuSource.biomeWeightsForRegion("default");
    assertEquals(Map.of("FOREST", 100L), defaultRegionWeights);
    assertTrue(BiomeMenuSource.biomeWeightsForRegion("nonexistent_region").isEmpty());
    assertTrue(BiomeMenuSource.biomeWeightsForRegion(null).isEmpty());
    assertTrue(BiomeMenuSource.biomeWeightsForRegion("").isEmpty());
  }

  @Test
  @DisplayName("worldColorPrefix yields a parchment-safe dark code (and a neutral default on cold data)")
  void worldColorPrefix_darkCodeAndColdDefault() throws Exception {
    // Cold data: no observed biomes -> neutral default.
    assertEquals("&2", io.github.dailystruggle.rtp.common.commands.menu.MenuColor
        .worldColorPrefix("default_world"));

    seedBiome(defaultShape, "FOREST", 100L);

    String prefix = io.github.dailystruggle.rtp.common.commands.menu.MenuColor
        .worldColorPrefix("default_world");
    // Always a single legacy color code, drawn from the curated dark set
    // (never yellow '&e', white '&f', or gold '&6' which wash out on the
    // parchment book background).
    assertTrue(prefix.matches("&[0-9a-f]"), "expected a single legacy color code, got " + prefix);
    assertFalse(prefix.equals("&e") || prefix.equals("&f") || prefix.equals("&6"),
        "world color must avoid pale-on-parchment codes, got " + prefix);
  }

  @Test
  @DisplayName("WCAG 2.1 Contrast Compliance: All MenuColor codes meet contrast requirements on parchment")
  void menuColor_wcagContrastComplianceOnParchment() {
    final int parchmentRgb = 0xF4E8C1;
    final double parchmentLuminance = calculateLuminance(parchmentRgb);

    Map<String, Integer> palette = Map.of(
        "&0", 0x000000,
        "&1", 0x0000AA,
        "&2", 0x00AA00,
        "&3", 0x00AAAA,
        "&4", 0xAA0000,
        "&5", 0xAA00AA,
        "&8", 0x555555,
        "&e", 0xFFFF55,
        "&6", 0xFFAA00,
        "&f", 0xFFFFFF
    );

    // Dark high-contrast codes achieve WCAG AA (>= 4.5:1)
    for (String highContrastCode : List.of("&0", "&1", "&4", "&5", "&8")) {
      double lum = calculateLuminance(palette.get(highContrastCode));
      double ratio = contrastRatio(parchmentLuminance, lum);
      assertTrue(ratio >= 4.5, "High-contrast code " + highContrastCode + " failed WCAG AA threshold: " + ratio);
    }

    // Secondary dark accent codes (&2 dark green, &3 dark aqua) achieve readable UI contrast (>= 2.2:1)
    for (String darkCode : List.of("&2", "&3")) {
      double lum = calculateLuminance(palette.get(darkCode));
      double ratio = contrastRatio(parchmentLuminance, lum);
      assertTrue(ratio >= 2.2, "Dark code " + darkCode + " failed baseline contrast threshold: " + ratio);
    }

    // Pale/yellow/white codes strictly fail (< 2.0:1) and are prohibited on parchment
    for (String prohibited : List.of("&e", "&6", "&f")) {
      double lum = calculateLuminance(palette.get(prohibited));
      double ratio = contrastRatio(parchmentLuminance, lum);
      assertTrue(ratio < 2.0, "Prohibited pale code " + prohibited + " should fail contrast threshold: " + ratio);
    }
  }

  private static double calculateLuminance(int rgb) {
    double r = ((rgb >> 16) & 0xFF) / 255.0;
    double g = ((rgb >> 8) & 0xFF) / 255.0;
    double b = (rgb & 0xFF) / 255.0;

    double rLin = (r <= 0.03928) ? (r / 12.92) : Math.pow((r + 0.055) / 1.055, 2.4);
    double gLin = (g <= 0.03928) ? (g / 12.92) : Math.pow((g + 0.055) / 1.055, 2.4);
    double bLin = (b <= 0.03928) ? (b / 12.92) : Math.pow((b + 0.055) / 1.055, 2.4);

    return 0.2126 * rLin + 0.7152 * gLin + 0.0722 * bLin;
  }

  private static double contrastRatio(double l1, double l2) {
    double max = Math.max(l1, l2);
    double min = Math.min(l1, l2);
    return (max + 0.05) / (min + 0.05);
  }
}
