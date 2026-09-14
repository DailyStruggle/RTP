package io.github.dailystruggle.mapsapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BiomeColorSourceTest {

  @Test
  @DisplayName("fallback produces stable deterministic color from 16-entry palette")
  void testFallbackPalette() {
    int color1 = BiomeColorSource.fallback("PLAINS");
    int color2 = BiomeColorSource.fallback("PLAINS");
    assertEquals(color1, color2);

    int colorNull = BiomeColorSource.fallback("");
    assertTrue(colorNull > 0);

    // Verify all 16 entries in FALLBACK_PALETTE are non-zero
    assertEquals(16, BiomeColorSource.FALLBACK_PALETTE.length);
    for (int c : BiomeColorSource.FALLBACK_PALETTE) {
      assertNotEquals(0, c);
    }
  }

  @Test
  @DisplayName("install and resolve behavior with active source, exception handling, and null fallback")
  void testResolveAndInstall() {
    BiomeColorSource prior = BiomeColorSource.install(null);
    try {
      // With null active source, resolve delegates to fallback
      assertEquals(BiomeColorSource.fallback("FOREST"), BiomeColorSource.resolve("FOREST"));
      assertEquals(BiomeColorSource.fallback(""), BiomeColorSource.resolve(null));

      // Install a custom source
      BiomeColorSource custom = biome -> {
        if ("SPECIAL".equals(biome)) return 0x123456;
        if ("THROW".equals(biome)) throw new RuntimeException("fail");
        return 0; // return 0 triggers fallback
      };
      BiomeColorSource prev = BiomeColorSource.install(custom);
      assertNull(prev);

      // Custom resolution
      assertEquals(0x123456, BiomeColorSource.resolve("SPECIAL"));
      // 0 returns fallback
      assertEquals(BiomeColorSource.fallback("OTHER"), BiomeColorSource.resolve("OTHER"));
      // Exception triggers fallback
      assertEquals(BiomeColorSource.fallback("THROW"), BiomeColorSource.resolve("THROW"));
    } finally {
      BiomeColorSource.install(prior);
    }
  }

  @Test
  @DisplayName("default MapCanvas setPixelRgb maps luma correctly to palette slots")
  void testDefaultMapCanvasSetPixelRgb() {
    int[] writtenX = new int[1];
    int[] writtenY = new int[1];
    byte[] writtenByte = new byte[1];

    MapCanvas canvas = new MapCanvas() {
      @Override public int width() { return 128; }
      @Override public int height() { return 128; }
      @Override public void setPixel(int x, int y, byte paletteIndex) {
        writtenX[0] = x;
        writtenY[0] = y;
        writtenByte[0] = paletteIndex;
      }
      @Override public void fillRect(int x0, int y0, int x1, int y1, byte paletteIndex) {}
      @Override public void drawText(int x, int y, String text, byte paletteIndex) {}
      @Override public void clear() {}
      @Override public void commit() {}
    };

    // Black (0, 0, 0) -> luma = 0 -> slot = 1
    canvas.setPixelRgb(5, 10, 0x000000);
    assertEquals(5, writtenX[0]);
    assertEquals(10, writtenY[0]);
    assertEquals((byte) 1, writtenByte[0]);

    // White (255, 255, 255) -> luma = 255 -> slot = 27 (PaletteIndex.RAMP_MAX)
    canvas.setPixelRgb(20, 30, 0xFFFFFF);
    assertEquals(20, writtenX[0]);
    assertEquals(30, writtenY[0]);
    assertEquals((byte) 27, writtenByte[0]);
  }
}
