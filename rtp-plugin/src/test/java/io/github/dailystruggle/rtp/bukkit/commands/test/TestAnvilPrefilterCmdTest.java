package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.rtp.common.commands.test.TestAnvilPrefilterCmd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.commands.test.TestAnvilPrefilterCmd.Snapshot;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TestAnvilPrefilterCmd#snapshot()} and the derived
 * {@link Snapshot} arithmetic.
 *
 * <p>The three counters are process-global
 * {@code AtomicLong}s; this test captures the pre-test baseline in
 * {@link #reset()} and restores it in {@link #restore()} so repeated runs
 * (and interleaving with real pre-filter traffic during a live dev build)
 * stay deterministic.
 *
 * <p>Traces ADR-016 Phase&nbsp;4 telemetry exposure (see
 * {@code ADR-016 §10}).
 */
class TestAnvilPrefilterCmdTest {

  private long savedAccepts;
  private long savedRejects;
  private long savedUnknowns;

  private AtomicLong getMetric(String name) {
    try {
      return (AtomicLong)
          Class.forName("io.github.dailystruggle.rtp.anvil.AnvilPrefilterMetrics")
              .getField(name)
              .get(null);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @BeforeEach
  void reset() {
    savedAccepts = getMetric("accepts").getAndSet(0L);
    savedRejects = getMetric("rejects").getAndSet(0L);
    savedUnknowns = getMetric("unknowns").getAndSet(0L);
  }

  @AfterEach
  void restore() {
    getMetric("accepts").set(savedAccepts);
    getMetric("rejects").set(savedRejects);
    getMetric("unknowns").set(savedUnknowns);
  }

  @Test
  @DisplayName("empty counters → total=0, rates=0.0 (no NaN from div-by-zero)")
  void emptySnapshotIsZero() {
    Snapshot s = TestAnvilPrefilterCmd.snapshot();
    assertEquals(0L, s.accepts);
    assertEquals(0L, s.rejects);
    assertEquals(0L, s.unknowns);
    assertEquals(0L, s.total());
    assertEquals(0.0, s.hitRate(), 0.0, "hitRate must not NaN on empty counters");
    assertEquals(0.0, s.rejectRate(), 0.0, "rejectRate must not NaN on empty counters");
  }

  @Test
  @DisplayName("snapshot reflects live counter mutations (ACCEPT/REJECT/UNKNOWN)")
  void snapshotCapturesMutations() {
    getMetric("accepts").addAndGet(7L);
    getMetric("rejects").addAndGet(3L);
    getMetric("unknowns").addAndGet(10L);

    Snapshot s = TestAnvilPrefilterCmd.snapshot();
    assertEquals(7L, s.accepts);
    assertEquals(3L, s.rejects);
    assertEquals(10L, s.unknowns);
    assertEquals(20L, s.total());
    // 10 usable (ACCEPT + REJECT) out of 20 total = 0.5
    assertEquals(0.5, s.hitRate(), 1e-9);
    // 3 REJECT out of 20 = 0.15
    assertEquals(0.15, s.rejectRate(), 1e-9);
  }

  @Test
  @DisplayName("hit rate is 1.0 when no UNKNOWN verdicts have fired")
  void hitRateSaturatesWithoutUnknowns() {
    getMetric("accepts").addAndGet(4L);
    getMetric("rejects").addAndGet(1L);

    Snapshot s = TestAnvilPrefilterCmd.snapshot();
    assertEquals(1.0, s.hitRate(), 1e-9);
    assertEquals(0.2, s.rejectRate(), 1e-9);
  }

  @Test
  @DisplayName("toString includes all three buckets and the computed rates")
  void toStringIsDiagnostic() {
    getMetric("accepts").set(2L);
    getMetric("rejects").set(1L);
    getMetric("unknowns").set(1L);

    String out = TestAnvilPrefilterCmd.snapshot().toString();
    assertTrue(out.contains("accepts=2"), out);
    assertTrue(out.contains("rejects=1"), out);
    assertTrue(out.contains("unknowns=1"), out);
    assertTrue(out.contains("total=4"), out);
    assertTrue(out.contains("reject-rate="), out);
    assertTrue(out.contains("hit-rate="), out);
  }

  @Test
  @DisplayName("constructor is resilient to a null parent (mirrors siblings)")
  void constructsWithNullParent() {
    TestAnvilPrefilterCmd cmd = new TestAnvilPrefilterCmd(null);
    assertNotNull(cmd);
    assertEquals("anvil-prefilter", cmd.name());
    assertEquals("rtp.test", cmd.permission());
    assertSame(null, cmd.parent());
  }
}
