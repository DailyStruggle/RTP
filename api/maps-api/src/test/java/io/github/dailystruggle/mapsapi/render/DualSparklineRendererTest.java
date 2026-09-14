package io.github.dailystruggle.mapsapi.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dailystruggle.mapsapi.image.ImageMapCanvas;
import io.github.dailystruggle.mapsapi.model.DualSparkline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DualSparklineRendererTest {

  @Test
  @DisplayName("DualSparkline model validation and defensive copying")
  void testDualSparklineModel() {
    double[] sA = new double[] {10.0, 20.0, 30.0};
    double[] sB = new double[] {100.0, 200.0, 300.0};

    DualSparkline model = new DualSparkline("MSPT", sA, 0.0, 50.0, "Heap", sB, 0.0, 500.0);
    assertEquals("MSPT", model.labelA());
    assertEquals("Heap", model.labelB());
    assertEquals(3, model.sampleCount());
    assertEquals(0.0, model.aMin());
    assertEquals(50.0, model.aMax());
    assertEquals(0.0, model.bMin());
    assertEquals(500.0, model.bMax());

    // Defensive copy test
    sA[0] = 999.0;
    assertNotEquals(999.0, model.seriesA()[0]);

    double[] readA = model.seriesA();
    readA[0] = 888.0;
    assertNotEquals(888.0, model.seriesA()[0]);

    // Validation failures
    assertThrows(NullPointerException.class, () -> new DualSparkline(null, sA, 0, 1, "B", sB, 0, 1));
    assertThrows(NullPointerException.class, () -> new DualSparkline("A", null, 0, 1, "B", sB, 0, 1));
    assertThrows(NullPointerException.class, () -> new DualSparkline("A", sA, 0, 1, null, sB, 0, 1));
    assertThrows(NullPointerException.class, () -> new DualSparkline("A", sA, 0, 1, "B", null, 0, 1));

    assertThrows(IllegalArgumentException.class, () -> new DualSparkline("A", new double[0], 0, 1, "B", new double[0], 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new DualSparkline("A", new double[2], 0, 1, "B", new double[3], 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new DualSparkline("A", new double[2], 5, 2, "B", new double[2], 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new DualSparkline("A", new double[2], 0, 1, "B", new double[2], 5, 2));
  }

  @Test
  @DisplayName("DualSparklineRenderer renders onto ImageMapCanvas with NaN and scaling")
  void testDualSparklineRendering() {
    DualSparklineRenderer renderer = DualSparklineRenderer.INSTANCE;

    assertThrows(IllegalArgumentException.class, () -> renderer.render(null, new DualSparkline("A", new double[] {1}, 0, 10, "B", new double[] {1}, 0, 10)));
    assertThrows(IllegalArgumentException.class, () -> renderer.render(new ImageMapCanvas(128, 128), null));

    ImageMapCanvas canvas = new ImageMapCanvas(128, 128);
    double[] seriesA = new double[] {5.0, Double.NaN, 15.0, 20.0, 45.0, 10.0};
    double[] seriesB = new double[] {100.0, 250.0, Double.NaN, 300.0, 400.0, 150.0};
    DualSparkline model = new DualSparkline("MSPT (ms)", seriesA, 0.0, 50.0, "Heap (MB)", seriesB, 0.0, 500.0);

    renderer.render(canvas, model);
    assertNotNull(canvas.getImage());

    // Small canvas boundary check
    ImageMapCanvas smallCanvas = new ImageMapCanvas(128, 6);
    renderer.render(smallCanvas, model);

    // Single sample checks (NaN and valid)
    DualSparkline singleValid = new DualSparkline("", new double[] {25.0}, 0.0, 50.0, "", new double[] {Double.NaN}, 0.0, 50.0);
    renderer.render(canvas, singleValid);

    // Clamped sample values (above max, below min)
    DualSparkline clampedSamples = new DualSparkline("Low", new double[] {-10.0, 60.0}, 0.0, 50.0, "High", new double[] {-5.0, 100.0}, 0.0, 50.0);
    renderer.render(canvas, clampedSamples);
  }
}
