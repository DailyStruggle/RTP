package io.github.dailystruggle.rtp.common.tools;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Utility for writing visual diagnostic test PNG charts deterministically.
 * Avoids updating existing files on disk if the generated image's dimensions
 * and ARGB pixel values are identical to the file already on disk, preventing
 * build churn and unwanted git diffs when tests run on unmodified code.
 */
public final class ChartOutputHelper {

  private ChartOutputHelper() {}

  /**
   * Writes {@code img} to {@code targetFile} in PNG format only if {@code targetFile}
   * does not exist or differs in dimensions or pixel values.
   *
   * @param img the rendered image
   * @param targetFile the destination file
   * @return true if the file was written, false if skipped because identical
   */
  public static boolean writeIfModified(BufferedImage img, File targetFile) throws Exception {
    if (img == null || targetFile == null) return false;

    if (targetFile.exists() && targetFile.length() > 0) {
      try {
        BufferedImage existing = ImageIO.read(targetFile);
        if (existing != null
            && existing.getWidth() == img.getWidth()
            && existing.getHeight() == img.getHeight()) {
          boolean match = true;
          int w = img.getWidth();
          int h = img.getHeight();
          for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
              if (existing.getRGB(x, y) != img.getRGB(x, y)) {
                match = false;
                break;
              }
            }
            if (!match) break;
          }
          if (match) {
            return false;
          }
        }
      } catch (Throwable ignored) {
        // If reading existing fails (e.g. corrupt or format issue), fall through to rewrite
      }
    }

    if (targetFile.getParentFile() != null && !targetFile.getParentFile().exists()) {
      targetFile.getParentFile().mkdirs();
    }
    ImageIO.write(img, "PNG", targetFile);
    return true;
  }
}
