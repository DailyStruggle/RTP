package io.github.dailystruggle.mapsapi.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dailystruggle.mapsapi.image.ImageMapCanvas;
import io.github.dailystruggle.mapsapi.model.RegionBiomesRgb;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RegionBiomesRgbRendererTest {

  @Test
  @DisplayName("RegionBiomesRgb model validation and defensive copying")
  void testRegionBiomesRgbModel() {
    int[] rgb = new int[] {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00};
    byte[] mask = new byte[] {
        RegionBiomesRgb.MASK_OUTSIDE,
        RegionBiomesRgb.MASK_UNSAMPLED,
        RegionBiomesRgb.MASK_RGB,
        RegionBiomesRgb.MASK_RGB
    };

    RegionBiomesRgb model = new RegionBiomesRgb("test-region", 2, 2, rgb, mask);
    assertEquals("test-region", model.regionName());
    assertEquals(2, model.width());
    assertEquals(2, model.height());

    // Defensive copy test
    rgb[0] = 0x123456;
    assertNotEquals(0x123456, model.rgb()[0]);

    mask[0] = RegionBiomesRgb.MASK_RGB;
    assertNotEquals(RegionBiomesRgb.MASK_RGB, model.mask()[0]);

    // Validation
    assertThrows(NullPointerException.class, () -> new RegionBiomesRgb(null, 2, 2, rgb, mask));
    assertThrows(IllegalArgumentException.class, () -> new RegionBiomesRgb("r", 0, 2, rgb, mask));
    assertThrows(IllegalArgumentException.class, () -> new RegionBiomesRgb("r", 2, -1, rgb, mask));
    assertThrows(NullPointerException.class, () -> new RegionBiomesRgb("r", 2, 2, null, mask));
    assertThrows(NullPointerException.class, () -> new RegionBiomesRgb("r", 2, 2, rgb, null));
    assertThrows(IllegalArgumentException.class, () -> new RegionBiomesRgb("r", 2, 2, new int[3], mask));
    assertThrows(IllegalArgumentException.class, () -> new RegionBiomesRgb("r", 2, 2, rgb, new byte[3]));
  }

  @Test
  @DisplayName("RegionBiomesRgbRenderer blits exact resolution and scales nearest-neighbour")
  void testRegionBiomesRgbRendering() {
    RegionBiomesRgbRenderer renderer = RegionBiomesRgbRenderer.INSTANCE;

    assertThrows(IllegalArgumentException.class, () -> renderer.render(null, new RegionBiomesRgb("r", 1, 1, new int[1], new byte[1])));
    assertThrows(IllegalArgumentException.class, () -> renderer.render(new ImageMapCanvas(10, 10), null));

    int[] rgb = new int[] {
        0xFF0000, 0x00FF00,
        0x0000FF, 0xFFFF00
    };
    byte[] mask = new byte[] {
        RegionBiomesRgb.MASK_OUTSIDE, RegionBiomesRgb.MASK_UNSAMPLED,
        RegionBiomesRgb.MASK_RGB, RegionBiomesRgb.MASK_RGB
    };
    RegionBiomesRgb model = new RegionBiomesRgb("region", 2, 2, rgb, mask);

    // Exact resolution fast path
    ImageMapCanvas exactCanvas = new ImageMapCanvas(2, 2);
    renderer.render(exactCanvas, model);
    assertNotNull(exactCanvas.getImage());

    // Nearest neighbour scaled path
    ImageMapCanvas scaledCanvas = new ImageMapCanvas(128, 128);
    renderer.render(scaledCanvas, model);
    assertNotNull(scaledCanvas.getImage());
  }
}
