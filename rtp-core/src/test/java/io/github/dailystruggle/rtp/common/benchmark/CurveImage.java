package io.github.dailystruggle.rtp.common.benchmark;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Writes benchmark rasters to {@code build/reports/rtp-simulation/img} as PNG.
 *
 * <p>Exists because two of the findings in this line of work are geometric and were repeatedly
 * mis-stated in prose: which cells a 1D key gap bridges, and which usable ground a coalesced table
 * discards. A number can be quoted out of context; a picture of the same domain under two curves
 * cannot. The images are the shareable artifact of the comparison, so they are drawn from the same
 * run that emits the rows rather than reconstructed afterwards.
 *
 * <p>Never throws: an image-write failure must not fail a benchmark, on the same principle as
 * {@link SimulationReport}.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier.
 */
final class CurveImage {

  /** One cell per pixel is unreadable at a glance, so cells are drawn as blocks. */
  private final int scale;

  private final String stem;

  CurveImage(String stem, int scale) {
    this.stem = stem;
    this.scale = Math.max(1, scale);
  }

  /** Supplies a packed ARGB value for a cell, indexed from the domain's lower corner. */
  interface CellColour {
    int argbAt(int cellX, int cellZ);
  }

  /** One legend entry: a colour and what that colour means in the raster. */
  record Swatch(int argb, String label) {}

  /**
   * Everything a reader needs to interpret a raster without the surrounding prose.
   *
   * <p>Added because the first set of images shipped bare: correct pixels, no way to tell which
   * curve was drawn, what a colour meant, or what one pixel block was. An unlabelled raster is not
   * evidence, it is a decoration.
   */
  static final class Caption {
    private final String title;
    private final List<String> lines = new ArrayList<>();
    private final List<Swatch> swatches = new ArrayList<>();

    Caption(String title) {
      this.title = title;
    }

    Caption line(String text) {
      lines.add(text);
      return this;
    }

    Caption swatch(int argb, String label) {
      swatches.add(new Swatch(argb, label));
      return this;
    }
  }

  /**
   * Draws a {@code cells x cells} domain and writes it as {@code <stem>-<name>.png}.
   *
   * <p>The z axis is flipped so the image reads like a map: {@code +z} is south in Minecraft, and
   * an image whose top row is {@code +z} would mirror every screenshot an operator compares it to.
   *
   * @return the written path, or {@code null} if no report directory is configured
   */
  Path draw(String name, int cells, CellColour colour) {
    return draw(name, cells, colour, null);
  }

  /** As {@link #draw(String, int, CellColour)}, with a title, explanatory lines and a legend. */
  Path draw(String name, int cells, CellColour colour, Caption caption) {
    String dir = System.getProperty("rtp.simulation.reportDir");
    if (dir == null || dir.isBlank()) return null;
    try {
      int side = cells * scale;
      int pad = caption == null ? 0 : 12;
      int lineH = 15;
      int headH =
          caption == null ? 0 : pad + 22 + (caption.lines.size() * lineH) + pad / 2;
      int footH = caption == null || caption.swatches.isEmpty() ? 0 : pad + 18 * caption.swatches.size();
      int width = Math.max(side + 2 * pad, caption == null ? side : 560);
      BufferedImage img =
          new BufferedImage(width, headH + side + footH + pad, BufferedImage.TYPE_INT_RGB);
      Graphics2D g = img.createGraphics();
      g.setColor(new Color(0x101821));
      g.fillRect(0, 0, img.getWidth(), img.getHeight());

      int originX = pad;
      int originY = headH;
      for (int cz = 0; cz < cells; cz++) {
        for (int cx = 0; cx < cells; cx++) {
          int argb = colour.argbAt(cx, cz);
          int px = originX + cx * scale;
          int pz = originY + (cells - 1 - cz) * scale;
          for (int dz = 0; dz < scale; dz++) {
            for (int dx = 0; dx < scale; dx++) {
              img.setRGB(px + dx, pz + dz, argb);
            }
          }
        }
      }

      if (caption != null) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        int y = pad + 16;
        g.drawString(caption.title, pad, y);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        g.setColor(new Color(0xC7D0DA));
        for (String line : caption.lines) {
          y += lineH;
          g.drawString(line, pad, y);
        }
        int ly = originY + side + pad;
        for (Swatch s : caption.swatches) {
          g.setColor(new Color(s.argb()));
          g.fillRect(pad, ly - 9, 12, 12);
          g.setColor(new Color(0x33404D));
          g.drawRect(pad, ly - 9, 12, 12);
          g.setColor(new Color(0xC7D0DA));
          g.drawString(s.label(), pad + 20, ly + 2);
          ly += 18;
        }
        // North marker, so the map orientation is not something the reader has to take on trust.
        g.setColor(new Color(0x8894A0));
        g.drawString("+z (south) down, +x (east) right", pad, originY - 4);
      }
      g.dispose();

      Path out = Paths.get(dir).resolve("img");
      Files.createDirectories(out);
      Path file = out.resolve(stem + "-" + name + ".png");
      ImageIO.write(img, "png", file.toFile());
      System.out.println("[DEBUG_LOG] image written to " + file);
      return file;
    } catch (IOException | RuntimeException e) {
      System.out.println("[DEBUG_LOG] image write failed: " + e);
      return null;
    }
  }

  /**
   * Cyclic hue ramp over a normalised position along a curve.
   *
   * <p>Cyclic rather than monotone on purpose: a single dark-to-light ramp over a million keys is
   * visually flat, whereas repeating the ramp makes each band one contiguous stretch of key space,
   * so a curve's locality shows up directly as whether its bands are blobs or rings.
   */
  static int rampArgb(double position, int cycles) {
    double h = (position * cycles) % 1.0d;
    return java.awt.Color.HSBtoRGB((float) h, 0.72f, 0.96f);
  }
}
