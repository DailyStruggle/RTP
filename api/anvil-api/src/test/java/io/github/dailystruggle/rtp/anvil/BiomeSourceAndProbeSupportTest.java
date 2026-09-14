package io.github.dailystruggle.rtp.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BiomeSourceAndProbeSupportTest {

  @BeforeEach
  void setUp() {
    BiomeSourceMetrics.resetForTest();
  }

  @Test
  @DisplayName("BiomeSourceMetrics increments reason counters and maintains high-level hits")
  void testBiomeSourceMetrics() {
    assertEquals(0L, BiomeSourceMetrics.anvilHits.get());
    assertEquals(0L, BiomeSourceMetrics.liveHits.get());

    BiomeSourceMetrics.record(true);
    assertEquals(1L, BiomeSourceMetrics.anvilHits.get());
    assertEquals(0L, BiomeSourceMetrics.liveHits.get());

    BiomeSourceMetrics.record(false);
    assertEquals(1L, BiomeSourceMetrics.anvilHits.get());
    assertEquals(1L, BiomeSourceMetrics.liveHits.get());

    BiomeSourceMetrics.record(BiomeSourceMetrics.Reasons.VIEW_MISSING_BIOME);
    assertEquals(1L, BiomeSourceMetrics.anvilHits.get());
    assertEquals(2L, BiomeSourceMetrics.liveHits.get());

    BiomeSourceMetrics.record((String) null);
    assertEquals(1L, BiomeSourceMetrics.anvilHits.get());
    assertEquals(3L, BiomeSourceMetrics.liveHits.get());

    BiomeSourceMetrics.record("custom-reason");
    assertEquals(1L, BiomeSourceMetrics.anvilHits.get());
    assertEquals(4L, BiomeSourceMetrics.liveHits.get());

    Map<String, Long> counters = BiomeSourceMetrics.reasonCounters();
    assertEquals(1L, counters.get(BiomeSourceMetrics.Reasons.ANVIL_HIT));
    assertEquals(2L, counters.get(BiomeSourceMetrics.Reasons.NO_VIEW_CACHED));
    assertEquals(1L, counters.get(BiomeSourceMetrics.Reasons.VIEW_MISSING_BIOME));
    assertEquals(1L, counters.get("custom-reason"));

    assertThrows(UnsupportedOperationException.class, () -> counters.put("x", 1L));

    BiomeSourceMetrics.resetForTest();
    assertEquals(0L, BiomeSourceMetrics.anvilHits.get());
    assertEquals(0L, BiomeSourceMetrics.liveHits.get());
    assertEquals(0L, BiomeSourceMetrics.reasonCounters().get(BiomeSourceMetrics.Reasons.ANVIL_HIT));
  }

  @Test
  @DisplayName("AnvilProbeSupport constructor, publish, take, evict, and clear")
  void testAnvilProbeSupportLifecycle() {
    assertThrows(IllegalArgumentException.class, () -> new AnvilProbeSupport(0));
    assertThrows(IllegalArgumentException.class, () -> new AnvilProbeSupport(-5));

    AnvilProbeSupport support = new AnvilProbeSupport(2);
    assertEquals(0, support.size());

    // Null publish is no-op
    support.publish(1L, null);
    assertEquals(0, support.size());

    // Publish view1
    AnvilChunkView view1 = new AnvilChunkView(2975, java.util.List.of(), new long[37]);
    AnvilChunkView view2 = new AnvilChunkView(2975, java.util.List.of(), new long[37]);
    AnvilChunkView view3 = new AnvilChunkView(2975, java.util.List.of(), new long[37]);

    support.publish(100L, view1);
    assertEquals(1, support.size());
    assertEquals(view1, support.takeCached(100L));

    // Overwrite same key
    support.publish(100L, view2);
    assertEquals(1, support.size());
    assertEquals(view2, support.takeCached(100L));

    // Fill to capacity
    support.publish(200L, view1);
    assertEquals(2, support.size());

    // Exceed capacity -> FIFO evicts 100L
    support.publish(300L, view3);
    assertEquals(2, support.size());
    assertNull(support.takeCached(100L));
    assertEquals(view1, support.takeCached(200L));
    assertEquals(view3, support.takeCached(300L));

    // Evict explicit
    support.evict(200L);
    assertNull(support.takeCached(200L));
    assertEquals(1, support.size());

    // Evict nonexistent
    support.evict(999L);
    assertEquals(1, support.size());

    // Clear
    support.clear();
    assertEquals(0, support.size());
    assertNull(support.takeCached(300L));
  }

  @Test
  @DisplayName("AnvilPrefilterMetrics record and snapshot")
  void testAnvilPrefilterMetrics() {
    AnvilPrefilterMetrics.accepts.set(0);
    AnvilPrefilterMetrics.rejects.set(0);
    AnvilPrefilterMetrics.unknowns.set(0);

    AnvilPrefilterMetrics.record(Verdict.ACCEPT);
    AnvilPrefilterMetrics.record(Verdict.REJECT);
    AnvilPrefilterMetrics.record(Verdict.UNKNOWN);
    AnvilPrefilterMetrics.record(null);

    assertEquals(1L, AnvilPrefilterMetrics.accepts.get());
    assertEquals(1L, AnvilPrefilterMetrics.rejects.get());
    assertEquals(1L, AnvilPrefilterMetrics.unknowns.get());
  }

  @Test
  @DisplayName("AnvilRegionByteCache Stats coverage")
  void testAnvilRegionByteCacheStats() {
    AnvilRegionByteCache.Stats stats1 = new AnvilRegionByteCache.Stats(10, 5, 2, 1, 10_000_000L, 3);
    AnvilRegionByteCache.Stats stats2 = new AnvilRegionByteCache.Stats(10, 5, 2, 1, 10_000_000L, 3);

    assertEquals(10, stats1.hits());
    assertEquals(5, stats1.misses());
    assertEquals(2, stats1.stale());
    assertEquals(1, stats1.coalesced());
    assertEquals(10_000_000L, stats1.coldReadNanos());
    assertEquals(3, stats1.statSkips());
    assertEquals(16, stats1.total());
    assertEquals((double) (10 + 1) / 16.0, stats1.hitRate());
    assertEquals(2.0, stats1.avgColdMissMs());
    assertEquals(stats1, stats2);
    assertEquals(stats1.hashCode(), stats2.hashCode());
    assertTrue(stats1.toString().contains("hits=10"));

    AnvilRegionByteCache.Stats zeroStats = new AnvilRegionByteCache.Stats(0, 0, 0, 0, 0L, 0);
    assertEquals(0.0, zeroStats.hitRate());
    assertEquals(0.0, zeroStats.avgColdMissMs());
  }

  @Test
  @DisplayName("AnvilChunkView query methods: isAir, isSafe, getSurfaceHeight, and minHeight")
  void testAnvilChunkViewQueries() {
    PaletteSection sec0 = new PaletteSection(0, java.util.List.of("minecraft:stone", "minecraft:air", "minecraft:lava"), new long[4]);
    PaletteSection sec1 = new PaletteSection(1, java.util.List.of("minecraft:bedrock"), new long[4]);

    AnvilChunkView view = new AnvilChunkView(2975, java.util.List.of(sec0, sec1), new long[37]);
    assertEquals(0, view.minHeight());
    assertEquals(2975, view.dataVersion());
    assertEquals(2, view.sections().size());
    assertEquals(37, view.motionBlockingNoLeaves().length);
    assertTrue(view.biomeSections().isEmpty());

    // Out of section Y returns null blockId, treated as air and safe
    assertNull(view.blockIdAt(0, -32, 0));
    assertTrue(view.isAir(0, -32, 0));
    assertTrue(view.isAir(0, -32, 0, java.util.Set.of("TALL_GRASS")));
    assertTrue(view.isSafe(0, -32, 0, java.util.Set.of("LAVA")));

    // Safe with empty / null unsafe set
    assertTrue(view.isSafe(0, 0, 0, null));
    assertTrue(view.isSafe(0, 0, 0, java.util.Collections.emptySet()));

    // Air with empty / null air set
    assertFalse(view.isAir(0, 0, 0, null));
    assertFalse(view.isAir(0, 0, 0, java.util.Collections.emptySet()));

    // Air vanilla vs custom
    assertTrue(view.isAir(0, 0, 0, java.util.Set.of("STONE"))); // STONE counted as passable

    // View with empty sections
    AnvilChunkView emptyView = new AnvilChunkView(2975, java.util.Collections.emptyList(), null);
    assertEquals(0, emptyView.minHeight());
    assertEquals(0, emptyView.getSurfaceHeight(0, 0));
    assertTrue(emptyView.isAir(0, 0, 0));
    assertTrue(emptyView.isSafe(0, 0, 0, java.util.Set.of("LAVA")));
    assertNull(emptyView.getBiomeAt(0, 0, 0));
    assertTrue(emptyView.getBiomesPresent().isEmpty());
  }

  @Test
  @DisplayName("AnvilProbeSupport probeAndPublish exceptionally path")
  void testAnvilProbeSupportExceptionally() {
    AnvilProbeSupport support = new AnvilProbeSupport();
    // Passing an invalid or non-existent path
    java.util.concurrent.CompletableFuture<AnvilPrefilter.ProbeResult> fut = support.probeAndPublish(
        java.nio.file.Path.of("nonexistent-dir-for-rtp-test"),
        "",
        0, 0,
        12345L,
        java.util.Set.of(),
        s -> { throw new RuntimeException("boom"); }
    );
    AnvilPrefilter.ProbeResult res = fut.join();
    assertNotNull(res);
    assertEquals(Verdict.UNKNOWN, res.verdict());

    // Test publish null view no-op
    support.publish(999L, null);
    assertEquals(0, support.size());
  }
}
