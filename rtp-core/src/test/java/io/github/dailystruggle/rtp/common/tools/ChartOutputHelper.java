package io.github.dailystruggle.rtp.common.tools;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Utility for writing visual diagnostic test PNG charts deterministically.
 * Avoids updating existing files on disk if the generated image's dimensions
 * and ARGB pixel values are identical to the file already on disk, preventing
 * build churn and unwanted git diffs when tests run on unmodified code.
 *
 * Ensures all visualizer charts route to canonical destinations (docs/assets/img/
 * and build/reports/) rather than scattering duplicate files into the repo root.
 */
public final class ChartOutputHelper {

  private static volatile File repoRootDir;

  private ChartOutputHelper() {}

  /**
   * Resolves the canonical repository root directory by looking upwards for
   * settings.gradle or .git starting from user.dir.
   */
  public static File getRepoRoot() {
    if (repoRootDir != null) {
      return repoRootDir;
    }
    File current = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
    while (current != null) {
      if (new File(current, "settings.gradle").exists() || new File(current, ".git").exists()) {
        repoRootDir = current;
        return repoRootDir;
      }
      current = current.getParentFile();
    }
    repoRootDir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
    return repoRootDir;
  }

  /**
   * Resolves the canonical docs image destination for a given chart filename:
   * &lt;repoRoot&gt;/docs/assets/img/&lt;chartName&gt;
   */
  public static File getDocsAssetFile(String chartName) {
    return new File(new File(getRepoRoot(), "docs/assets/img"), chartName);
  }

  /**
   * Resolves the canonical build report destination for a given chart filename:
   * &lt;repoRoot&gt;/build/reports/&lt;subDir&gt;/&lt;chartName&gt;
   */
  public static File getReportFile(String subDir, String chartName) {
    File reportsDir = new File(new File(getRepoRoot(), "build/reports"), subDir);
    return new File(reportsDir, chartName);
  }

  /**
   * Writes the rendered image to both the canonical documentation assets directory
   * ({@code docs/assets/img/<chartName>}) and the build reports directory
   * ({@code build/reports/<reportSubDir>/<chartName>}), skipping unchanged files.
   *
   * @param img the rendered chart image
   * @param reportSubDir the report subdirectory under build/reports/ (e.g. "player_distribution")
   * @param chartName the chart file name (e.g. "my_chart.png")
   * @return true if at least one file was newly written or modified
   */
  public static boolean writeChart(BufferedImage img, String reportSubDir, String chartName) throws Exception {
    if (img == null || chartName == null) return false;
    File docsFile = getDocsAssetFile(chartName);
    File reportFile = getReportFile(reportSubDir != null ? reportSubDir : "visualizers", chartName);

    boolean w1 = writeIfModified(img, docsFile);
    boolean w2 = writeIfModified(img, reportFile);
    return w1 || w2;
  }

  /**
   * Writes the rendered image to both docs/assets/img/<chartName> and
   * build/reports/visualizers/<chartName>.
   */
  public static boolean writeChart(BufferedImage img, String chartName) throws Exception {
    return writeChart(img, "visualizers", chartName);
  }

  /**
   * Writes an internal diagnostic image only to the build reports directory.
   */
  public static boolean writeReportOnly(BufferedImage img, String reportSubDir, String chartName) throws Exception {
    if (img == null || chartName == null) return false;
    File reportFile = getReportFile(reportSubDir != null ? reportSubDir : "visualizers", chartName);
    return writeIfModified(img, reportFile);
  }

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
