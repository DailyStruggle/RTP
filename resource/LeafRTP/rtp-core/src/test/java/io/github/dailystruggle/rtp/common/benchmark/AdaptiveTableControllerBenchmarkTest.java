package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Validates {@link AdaptiveTableController} against memory oscillations.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier.
 */
@Tag("simulation")
@DisplayName("Adaptive Table Controller Hysteresis Benchmark Test")
public class AdaptiveTableControllerBenchmarkTest {

  @Test
  @DisplayName("Controller resists rapid oscillation around thresholds")
  void testHysteresisNoThrashing() {
    long low = 64L * 1024 * 1024; // 64 MB
    long high = 160L * 1024 * 1024; // 160 MB
    int epochs = 5;

    AdaptiveTableController controller = new AdaptiveTableController(low, high, epochs);
    assertEquals(AdaptiveTableController.Mode.FLAT_COMPACT, controller.currentMode());

    // 1. Oscillate in the middle band (80 MB - 140 MB): should never change mode
    for (int i = 0; i < 50; i++) {
      long mem = (i % 2 == 0) ? 80L * 1024 * 1024 : 140L * 1024 * 1024;
      controller.evaluate(mem);
      assertEquals(AdaptiveTableController.Mode.FLAT_COMPACT, controller.currentMode());
    }
    assertEquals(0, controller.totalTransitions());

    // 2. High memory spike for 3 epochs (< 5 required): should NOT upgrade
    for (int i = 0; i < 3; i++) {
      controller.evaluate(200L * 1024 * 1024);
      assertEquals(AdaptiveTableController.Mode.FLAT_COMPACT, controller.currentMode());
    }
    // Dips below high watermark on 4th epoch: resets counter
    controller.evaluate(150L * 1024 * 1024);
    assertEquals(AdaptiveTableController.Mode.FLAT_COMPACT, controller.currentMode());

    // 3. Sustained high memory for 5 epochs: upgrades to SEGMENTED_ACCELERATED
    for (int i = 0; i < 5; i++) {
      controller.evaluate(200L * 1024 * 1024);
    }
    assertEquals(AdaptiveTableController.Mode.SEGMENTED_ACCELERATED, controller.currentMode());
    assertEquals(1, controller.totalTransitions());

    // 4. Oscillate between 100 MB and 200 MB (above low watermark 64 MB): should STAY segmented
    for (int i = 0; i < 50; i++) {
      long mem = (i % 2 == 0) ? 100L * 1024 * 1024 : 220L * 1024 * 1024;
      controller.evaluate(mem);
      assertEquals(AdaptiveTableController.Mode.SEGMENTED_ACCELERATED, controller.currentMode());
    }
    assertEquals(1, controller.totalTransitions()); // still only 1 transition!

    // 5. Memory drops below low watermark (< 64 MB): immediately drops to FLAT_COMPACT
    controller.evaluate(50L * 1024 * 1024);
    assertEquals(AdaptiveTableController.Mode.FLAT_COMPACT, controller.currentMode());
    assertEquals(2, controller.totalTransitions());
  }
}
