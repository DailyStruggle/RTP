package io.github.dailystruggle.rtp.common.tools.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the {@link BiomeActivityTracker} accumulator backing
 * {@code /rtp info biomes}. Exercises only the in-memory record/snapshot/reset
 * surface (no scheduler), so it runs without a platform.
 */
class BiomeActivityTrackerTest {

  @Test
  @DisplayName("snapshot orders biomes by descending sample count, ties by name")
  void snapshotOrdering() {
    BiomeActivityTracker t = new BiomeActivityTracker();
    for (int i = 0; i < 5; i++) t.record("PLAINS");
    for (int i = 0; i < 3; i++) t.record("FOREST");
    t.record("DESERT");
    t.record("BADLANDS"); // ties DESERT at 1 -> alphabetical first

    assertEquals(10L, t.totalSamples());
    assertEquals(4, t.distinctBiomes());

    List<String> order = new ArrayList<>(t.snapshot().keySet());
    assertEquals(List.of("PLAINS", "FOREST", "BADLANDS", "DESERT"), order);

    Map<String, Long> snap = t.snapshot();
    assertEquals(5L, snap.get("PLAINS"));
    assertEquals(3L, snap.get("FOREST"));
    assertEquals(1L, snap.get("DESERT"));
  }

  @Test
  @DisplayName("null/blank biome names are ignored and casing is normalised")
  void ignoresBlankAndNormalisesCase() {
    BiomeActivityTracker t = new BiomeActivityTracker();
    t.record(null);
    t.record("   ");
    t.record("");
    t.record("plains");
    t.record("PLAINS");
    t.record(" Plains ");

    assertEquals(3L, t.totalSamples());
    assertEquals(1, t.distinctBiomes());
    assertEquals(3L, t.snapshot().get("PLAINS"));
  }

  @Test
  @DisplayName("reset clears all accumulated occupancy")
  void resetClears() {
    BiomeActivityTracker t = new BiomeActivityTracker();
    t.record("PLAINS");
    t.record("FOREST");
    assertTrue(t.totalSamples() > 0);

    t.reset();

    assertEquals(0L, t.totalSamples());
    assertEquals(0, t.distinctBiomes());
    assertTrue(t.snapshot().isEmpty());
  }
}
