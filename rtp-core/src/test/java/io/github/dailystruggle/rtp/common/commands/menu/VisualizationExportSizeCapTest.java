package io.github.dailystruggle.rtp.common.commands.menu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.commands.menu.MenuConcreteCommandLeaves.VisualizationExportCmd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Export canvases share the server heap; explicit sizes must never exceed the side or area cap. */
class VisualizationExportSizeCapTest {

  private static final int MAX_SIDE = 8192;

  private static int[] resolve(Integer w, Integer h, boolean spatial) {
    return VisualizationExportCmd.resolveCanvasDimensions(null, w, h, spatial);
  }

  @Test
  @DisplayName("Explicit sizes within the cap pass through unchanged")
  void withinCapUnchanged() {
    assertArrayEquals(new int[]{1920, 1080}, resolve(1920, 1080, true));
    assertArrayEquals(new int[]{512, 256}, resolve(null, null, false));
  }

  @Test
  @DisplayName("Oversized explicit sides are capped and the area stays bounded")
  void oversizedSidesCapped() {
    int[] dims = resolve(100_000, 100_000, true);
    assertTrue(dims[0] <= MAX_SIDE && dims[1] <= MAX_SIDE);
    assertTrue((long) dims[0] * dims[1] <= (long) MAX_SIDE * MAX_SIDE);
  }

  @Test
  @DisplayName("Integer.MAX_VALUE sides (overflowed suffix input) still yield a bounded canvas")
  void hugeValuesBounded() {
    int[] dims = resolve(Integer.MAX_VALUE, Integer.MAX_VALUE, true);
    assertArrayEquals(new int[]{MAX_SIDE, MAX_SIDE}, dims);
  }

  @Test
  @DisplayName("Aspect ratio is preserved when scaling down a long thin canvas")
  void aspectPreserved() {
    int[] dims = VisualizationExportCmd.clampCanvas(32_768L, 4_096L);
    assertEquals(MAX_SIDE, dims[0]);
    assertEquals(1024, dims[1]);
  }

  @Test
  @DisplayName("Undersized and negative sides are raised to the minimum")
  void minimumEnforced() {
    assertArrayEquals(new int[]{16, 16}, VisualizationExportCmd.clampCanvas(-5L, 0L));
  }
}
