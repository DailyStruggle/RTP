package io.github.dailystruggle.rtp.bukkit.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.bukkit.commands.test.TestBiomeSourceCmd.Snapshot;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TestBiomeSourceCmd#snapshot()} and the derived
 * {@link Snapshot} arithmetic.
 *
 * <p>The two counters are process-global {@code AtomicLong}s; this test
 * captures the pre-test baseline in {@link #reset()} and restores it in
 * {@link #restore()} so repeated runs (and interleaving with real biome
 * traffic during a live dev build) stay deterministic.
 *
 * <p>Traces ADR-016 §13.1 biome-source attribution — the runtime evidence
 * that post-load biome reads on Spigot / Paper / Folia actually consult
 * the Anvil cache before the live getter.
 */
class TestBiomeSourceCmdTest {

  private long savedAnvilHits;
  private long savedLiveHits;

  private AtomicLong getMetric(String name) {
    try {
      return (AtomicLong)
          Class.forName("io.github.dailystruggle.rtp.anvil.BiomeSourceMetrics")
              .getField(name)
              .get(null);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @BeforeEach
  void reset() {
    savedAnvilHits = getMetric("anvilHits").getAndSet(0L);
    savedLiveHits = getMetric("liveHits").getAndSet(0L);
  }

  @AfterEach
  void restore() {
    getMetric("anvilHits").set(savedAnvilHits);
    getMetric("liveHits").set(savedLiveHits);
  }

  @Test
  @DisplayName("empty counters → total=0, anvilHitRate=0.0 (no NaN from div-by-zero)")
  void emptySnapshotIsZero() {
    Snapshot s = TestBiomeSourceCmd.snapshot();
    assertEquals(0L, s.anvilHits);
    assertEquals(0L, s.liveHits);
    assertEquals(0L, s.total());
    assertEquals(0.0, s.anvilHitRate(), 0.0, "anvilHitRate must not NaN on empty counters");
  }

  @Test
  @DisplayName("snapshot reflects live counter mutations (anvil + live)")
  void snapshotCapturesMutations() {
    getMetric("anvilHits").addAndGet(6L);
    getMetric("liveHits").addAndGet(4L);

    Snapshot s = TestBiomeSourceCmd.snapshot();
    assertEquals(6L, s.anvilHits);
    assertEquals(4L, s.liveHits);
    assertEquals(10L, s.total());
    assertEquals(0.6, s.anvilHitRate(), 1e-9);
  }

  @Test
  @DisplayName("BiomeSourceMetrics.record dispatches to the correct counter")
  void recordDispatchesByBranch() {
    io.github.dailystruggle.rtp.anvil.BiomeSourceMetrics.record(true);
    io.github.dailystruggle.rtp.anvil.BiomeSourceMetrics.record(true);
    io.github.dailystruggle.rtp.anvil.BiomeSourceMetrics.record(false);

    Snapshot s = TestBiomeSourceCmd.snapshot();
    assertEquals(2L, s.anvilHits);
    assertEquals(1L, s.liveHits);
    assertEquals(3L, s.total());
  }

  @Test
  @DisplayName("anvil-hit rate is 1.0 when no live fallthroughs have fired")
  void hitRateSaturatesWithoutLiveHits() {
    getMetric("anvilHits").addAndGet(5L);

    Snapshot s = TestBiomeSourceCmd.snapshot();
    assertEquals(1.0, s.anvilHitRate(), 1e-9);
  }

  @Test
  @DisplayName("toString includes both buckets and the computed rate")
  void toStringIsDiagnostic() {
    getMetric("anvilHits").set(3L);
    getMetric("liveHits").set(1L);

    String out = TestBiomeSourceCmd.snapshot().toString();
    assertTrue(out.contains("anvil-hits=3"), out);
    assertTrue(out.contains("live-hits=1"), out);
    assertTrue(out.contains("total=4"), out);
    assertTrue(out.contains("anvil-hit-rate="), out);
  }

  @Test
  @DisplayName("constructor is resilient to a null parent (mirrors siblings)")
  void constructsWithNullParent() {
    TestBiomeSourceCmd cmd = new TestBiomeSourceCmd(null);
    assertNotNull(cmd);
    assertEquals("biome-source", cmd.name());
    assertEquals("rtp.test", cmd.permission());
    assertSame(null, cmd.parent());
  }
}
