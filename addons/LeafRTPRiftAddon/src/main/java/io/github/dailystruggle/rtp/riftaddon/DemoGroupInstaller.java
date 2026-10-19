package io.github.dailystruggle.rtp.riftaddon;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Writes the demo effects group file. JDK-only so it is testable without a running RTP.
 *
 * <p>The file goes into the live parser's directory ({@code definitions/effects/}, ADR-076), never
 * the legacy root {@code effects/}: core migrates legacy files into {@code definitions/effects/} at
 * every start, so a copy left there brings a deleted group back on the next start.
 */
final class DemoGroupInstaller {

  /** What {@link #install} did. */
  record Outcome(boolean installed, boolean removedLegacyCopy) {}

  private DemoGroupInstaller() {}

  /**
   * Installs {@code fileName} into {@code groupsDir} when absent, and deletes an unedited copy this
   * addon previously wrote to {@code legacyDir} (the directory too, once empty). An edited legacy
   * copy is left for core's migration. An existing file in {@code groupsDir} is never overwritten.
   *
   * @throws IOException when the directory cannot be created or the file cannot be written
   */
  static Outcome install(File groupsDir, File legacyDir, String fileName, byte[] content)
      throws IOException {
    boolean removedLegacy = false;
    File legacy = new File(legacyDir, fileName);
    if (legacy.isFile() && sameIgnoringCr(Files.readAllBytes(legacy.toPath()), content)) {
      Files.delete(legacy.toPath());
      removedLegacy = true;
      String[] rest = legacyDir.list();
      if (rest != null && rest.length == 0) Files.delete(legacyDir.toPath());
    }

    File target = new File(groupsDir, fileName);
    if (target.exists()) return new Outcome(false, removedLegacy);
    if (!groupsDir.isDirectory() && !groupsDir.mkdirs()) {
      throw new IOException("could not create " + groupsDir);
    }
    Files.write(target.toPath(), content);
    return new Outcome(true, removedLegacy);
  }

  /** Line endings of the bundled resource depend on the build host (git autocrlf). */
  private static boolean sameIgnoringCr(byte[] a, byte[] b) {
    return Arrays.equals(stripCr(a), stripCr(b));
  }

  private static byte[] stripCr(byte[] in) {
    byte[] out = new byte[in.length];
    int n = 0;
    for (byte x : in) if (x != '\r') out[n++] = x;
    return Arrays.copyOf(out, n);
  }
}
