package io.github.dailystruggle.mapsapi.image;

import io.github.dailystruggle.mapsapi.MapCanvas;
import io.github.dailystruggle.mapsapi.PaletteIndex;
import io.github.dailystruggle.mapsapi.render.PixelFont;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import javax.imageio.ImageIO;

/**
 * Platform-neutral, standalone {@link MapCanvas} implementation backed directly
 * by an in-memory ARGB {@link BufferedImage}.
 *
 * <p>Fulfills ADR-089: provides a universal bridge that allows any
 * {@link io.github.dailystruggle.mapsapi.render.ChartRenderer} written against
 * {@link MapCanvas} to be rendered and exported to high-resolution PNG or BMP image files
 * without requiring a running Bukkit/Folia Minecraft server or main-thread chunk I/O (S-005).
 */
public final class ImageMapCanvas implements MapCanvas {

  /** Standard 32-slot ARGB lookup table for PaletteIndex values. */
  private static final int[] LOGICAL_PALETTE_ARGB = new int[32];

  static {
    // 0: Transparent
    LOGICAL_PALETTE_ARGB[PaletteIndex.TRANSPARENT] = 0x00000000;

    // 1..27: Heat ramp (Dark Blue -> Cyan -> Green -> Yellow -> Orange -> Red -> White)
    for (int i = PaletteIndex.RAMP_MIN; i <= PaletteIndex.RAMP_MAX; i++) {
      float frac = (float) (i - PaletteIndex.RAMP_MIN) / (PaletteIndex.RAMP_MAX - PaletteIndex.RAMP_MIN);
      int r, g, b;
      if (frac < 0.25f) { // Dark Blue -> Cyan
        float f = frac / 0.25f;
        r = 0;
        g = (int) (f * 255);
        b = (int) (128 + f * 127);
      } else if (frac < 0.5f) { // Cyan -> Green
        float f = (frac - 0.25f) / 0.25f;
        r = 0;
        g = 255;
        b = (int) ((1.0f - f) * 255);
      } else if (frac < 0.75f) { // Green -> Yellow
        float f = (frac - 0.5f) / 0.25f;
        r = (int) (f * 255);
        g = 255;
        b = 0;
      } else { // Yellow -> Red -> White
        float f = (frac - 0.75f) / 0.25f;
        r = 255;
        g = (int) ((1.0f - f * 0.7f) * 255);
        b = (int) (f * 180);
      }
      LOGICAL_PALETTE_ARGB[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // Named categorical slots
    LOGICAL_PALETTE_ARGB[PaletteIndex.BLACK] = 0xFF141414;
    LOGICAL_PALETTE_ARGB[PaletteIndex.RED]   = 0xFFE74C3C;
    LOGICAL_PALETTE_ARGB[PaletteIndex.GREEN] = 0xFF2ECC71;
    LOGICAL_PALETTE_ARGB[PaletteIndex.WHITE] = 0xFFECF0F1;
  }

  private final int width;
  private final int height;
  private final BufferedImage image;
  private int commitCount = 0;

  /**
   * Constructs an {@code ImageMapCanvas} with standard vanilla map dimensions (128x128).
   */
  public ImageMapCanvas() {
    this(MapCanvas.VANILLA_WIDTH, MapCanvas.VANILLA_HEIGHT);
  }

  /**
   * Constructs an {@code ImageMapCanvas} with arbitrary custom dimensions.
   *
   * @param width  canvas width in pixels (>= 1)
   * @param height canvas height in pixels (>= 1)
   */
  public ImageMapCanvas(int width, int height) {
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("Width and height must be positive, got " + width + "x" + height);
    }
    this.width = width;
    this.height = height;
    this.image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    clear();
  }

  @Override
  public int width() {
    return width;
  }

  @Override
  public int height() {
    return height;
  }

  @Override
  public void setPixel(int x, int y, byte paletteIndex) {
    if (x < 0 || x >= width || y < 0 || y >= height) return;
    int idx = paletteIndex & 0x1F; // clamp to 0..31
    image.setRGB(x, y, LOGICAL_PALETTE_ARGB[idx]);
  }

  @Override
  public void setPixelRgb(int x, int y, int rgb) {
    if (x < 0 || x >= width || y < 0 || y >= height) return;
    // Ensure full alpha if not provided
    int argb = (rgb & 0xFF000000) == 0 ? (0xFF000000 | (rgb & 0x00FFFFFF)) : rgb;
    image.setRGB(x, y, argb);
  }

  @Override
  public void fillRect(int x0, int y0, int x1, int y1, byte paletteIndex) {
    int minX = Math.max(0, Math.min(x0, x1));
    int maxX = Math.min(width - 1, Math.max(x0, x1));
    int minY = Math.max(0, Math.min(y0, y1));
    int maxY = Math.min(height - 1, Math.max(y0, y1));

    int argb = LOGICAL_PALETTE_ARGB[paletteIndex & 0x1F];
    for (int y = minY; y <= maxY; y++) {
      for (int x = minX; x <= maxX; x++) {
        image.setRGB(x, y, argb);
      }
    }
  }

  @Override
  public void drawText(int x, int y, String text, byte paletteIndex) {
    // PixelFont renders text glyphs using MapCanvas.setPixel
    PixelFont.draw(this, x, y, text, paletteIndex);
  }

  @Override
  public void clear() {
    int bg = 0xFF000000; // Default black background
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        image.setRGB(x, y, bg);
      }
    }
  }

  @Override
  public void commit() {
    commitCount++;
  }

  /**
   * Returns the count of times {@link #commit()} has been called on this canvas.
   */
  public int getCommitCount() {
    return commitCount;
  }

  /**
   * Returns the underlying {@link BufferedImage}.
   */
  public BufferedImage getImage() {
    return image;
  }

  /**
   * Writes the canvas image to a file in the specified format ("png", "bmp", etc.).
   *
   * @param destination target output file
   * @param formatName  image format name ("png" or "bmp")
   * @throws IOException if disk writing fails
   */
  public void writeToFile(File destination, String formatName) throws IOException {
    File parent = destination.getParentFile();
    if (parent != null && !parent.exists()) {
      parent.mkdirs();
    }
    BufferedImage exportImg = prepareForFormat(image, formatName);
    boolean ok = ImageIO.write(exportImg, formatName, destination);
    if (!ok) {
      throw new IOException("No ImageIO writer found for format: " + formatName);
    }
  }

  /**
   * Encodes the canvas image into a byte array in the specified format.
   *
   * @param formatName image format name ("png" or "bmp")
   * @return encoded image byte array
   * @throws IOException if encoding fails
   */
  public byte[] toByteArray(String formatName) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    BufferedImage exportImg = prepareForFormat(image, formatName);
    boolean ok = ImageIO.write(exportImg, formatName, baos);
    if (!ok) {
      throw new IOException("No ImageIO writer found for format: " + formatName);
    }
    return baos.toByteArray();
  }

  private static BufferedImage prepareForFormat(BufferedImage src, String formatName) {
    if ("bmp".equalsIgnoreCase(formatName) && src.getType() != BufferedImage.TYPE_INT_RGB) {
      // Standard BMP writer requires TYPE_INT_RGB (no alpha channel)
      BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
      for (int y = 0; y < src.getHeight(); y++) {
        for (int x = 0; x < src.getWidth(); x++) {
          rgb.setRGB(x, y, src.getRGB(x, y));
        }
      }
      return rgb;
    }
    return src;
  }
}
