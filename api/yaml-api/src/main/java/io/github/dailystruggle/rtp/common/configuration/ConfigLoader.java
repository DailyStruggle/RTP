package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.server.api.ServerAccessors;
import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Interface for loading configuration files
 *
 * @governedBy ADR-020
 * @governedBy ADR-064
 * @governedBy ADR-115
 */
public interface ConfigLoader {
  /**
   * Get the main directory for configuration files
   *
   * @return the main directory
   */
  File getMainDirectory();

  /**
   * Get the class loader for loading resources
   *
   * @return the class loader
   */
  ClassLoader getClassLoader();

  /**
   * Get an input stream for a resource in the jar
   *
   * @param filename the path to the resource
   * @return the input stream, or null if not found
   */
  @Nullable
  @SuppressWarnings("NullableProblems")
  default InputStream getResourceFromJar(@NotNull String filename) {
    try {
      ClassLoader cl = getClassLoader();
      if (cl == null) cl = ConfigLoader.class.getClassLoader();
      URL url = cl != null ? cl.getResource(filename) : null;

      if (url == null) {
        return null;
      }

      URLConnection connection = url.openConnection();
      connection.setUseCaches(false);
      return connection.getInputStream();
    } catch (IOException ex) {
      return null;
    }
  }

  /**
   * Save a resource from the jar to the disk
   *
   * @param resourcePath the path to the resource in the jar
   * @param replace whether to replace existing files
   */
  default void saveResourceFromJar(@NotNull String resourcePath, boolean replace) {
    resourcePath = resourcePath.replace('\\', '/');

    // Short-circuit: if the file already exists and we are not replacing, nothing to do.
    File earlyCheck = new File(getMainDirectory(), resourcePath);
    if (earlyCheck.exists() && !replace) {
      log(
          Level.WARNING,
          "Could not save " + earlyCheck.getName() + " to " + earlyCheck
              + " because " + earlyCheck.getName() + " already exists.",
          null);
      return;
    }

    InputStream in = getResourceFromJar(resourcePath);
    if (in == null) {
      return;
    }

    File outFile = new File(getMainDirectory(), resourcePath);
    int lastIndex = resourcePath.lastIndexOf('/');
    File outDir = new File(getMainDirectory(), resourcePath.substring(0, Math.max(lastIndex, 0)));

    if (!outDir.exists()) {
      boolean mkdirs = outDir.mkdirs();
      if (!mkdirs)
        throw new IllegalStateException("failed to create directory - " + outDir.getPath());
    }

    try (InputStream is = in) {
      if (!outFile.exists() || replace) {
        try (OutputStream out = new FileOutputStream(outFile)) {
          byte[] buf = new byte[1024];
          int len;
          while ((len = is.read(buf)) > 0) {
            out.write(buf, 0, len);
          }
        } catch (IOException ex) {
          log(Level.SEVERE, "Could not save " + outFile.getName() + " to " + outFile, ex);
        }
      } else {
        log(
            Level.WARNING,
            "Could not save "
                + outFile.getName()
                + " to "
                + outFile
                + " because "
                + outFile.getName()
                + " already exists.",
            null);
      }
    } catch (IOException ex) {
      log(Level.SEVERE, "Could not save " + outFile.getName() + " to " + outFile, ex);
    }
  }

  private static void log(Level level, String message, @Nullable Throwable throwable) {
    if (throwable != null) {
      ServerAccessors.log(level, message, throwable);
    } else {
      ServerAccessors.log(level, message);
    }
  }
}
