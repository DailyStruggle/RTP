package io.github.dailystruggle.rtp.bukkit.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the argument parsing and clamping helpers in
 * {@link TestStressCmd}. These helpers are safety-relevant: they enforce
 * the hard caps documented in {@code RUNTIME_TEST_SUITE_PLAN.md §3} that
 * prevent an operator typo (e.g. {@code iterations:1000000}) from
 * saturating the scheduler.
 *
 * <p>Traces REQ-RTP-S-004 (no silent failure modes) and the hard-cap
 * contract from the plan document. See {@code docs/dev/TRACEABILITY.md}.
 */
class TestStressCmdClampTest {

  @Test
  @DisplayName("clamp keeps values inside [lo, hi] (REQ-RTP-S-004 hard caps)")
  void clampKeepsValuesInRange() {
    assertEquals(1, TestStressCmd.clamp(0, 1, 1000));
    assertEquals(1, TestStressCmd.clamp(-5, 1, 1000));
    assertEquals(1000, TestStressCmd.clamp(99_999, 1, 1000));
    assertEquals(500, TestStressCmd.clamp(500, 1, 1000));
  }

  @Test
  @DisplayName("parseFirst returns fallback on null, empty, or non-numeric input")
  void parseFirstFallsBackGracefully() {
    assertEquals(10, TestStressCmd.parseFirst(null, 10));
    assertEquals(10, TestStressCmd.parseFirst(Collections.emptyList(), 10));
    assertEquals(10, TestStressCmd.parseFirst(Collections.singletonList("not-a-number"), 10));
    assertEquals(42, TestStressCmd.parseFirst(Arrays.asList("42", "99"), 10));
  }

  @Test
  @DisplayName("documented defaults match the plan's safe-runtime expectations")
  void defaultsMatchPlanDocument() {
    // These values are the contract the plan document promises; if they
    // change, RUNTIME_TEST_SUITE_PLAN.md §2 must change in the same commit.
    assertEquals(10, TestStressCmd.DEFAULT_ITERATIONS);
    assertEquals(40, TestStressCmd.DEFAULT_INTERVAL_TICKS);
    assertEquals(1, TestStressCmd.MIN_ITERATIONS);
    assertEquals(1000, TestStressCmd.MAX_ITERATIONS);
    assertEquals(10, TestStressCmd.MIN_INTERVAL_TICKS);
    assertEquals(6000, TestStressCmd.MAX_INTERVAL_TICKS);
  }
}
