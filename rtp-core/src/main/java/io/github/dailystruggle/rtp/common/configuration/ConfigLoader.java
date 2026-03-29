package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.RTP;
import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Interface for loading configuration files */
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
  default InputStream getResourceFromJar(@NotNull String filename) {
    try {
      URL url = getClassLoader().getResource(filename);

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
    InputStream in = getResourceFromJar(resourcePath);
    if (in == null) {
      throw new IllegalArgumentException(
          "The embedded resource '" + resourcePath + "' cannot be found in RTP");
    }

    File outFile = new File(getMainDirectory(), resourcePath);
    int lastIndex = resourcePath.lastIndexOf('/');
    File outDir = new File(getMainDirectory(), resourcePath.substring(0, Math.max(lastIndex, 0)));

    if (!outDir.exists()) {
      boolean mkdirs = outDir.mkdirs();
      if (!mkdirs)
        throw new IllegalStateException("failed to create directory - " + outDir.getPath());
    }

    try {
      if (!outFile.exists() || replace) {
        try {
          OutputStream out = new FileOutputStream(outFile);
          byte[] buf = new byte[1024];
          int len;
          while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
          }
          out.close();
        } catch (IOException ex) {
          RTP.log(Level.SEVERE, "Could not save " + outFile.getName() + " to " + outFile, ex);
        }
      } else {
        RTP.log(
            Level.WARNING,
            "Could not save "
                + outFile.getName()
                + " to "
                + outFile
                + " because "
                + outFile.getName()
                + " already exists.");
      }
      in.close();
    } catch (IOException ex) {
      RTP.log(Level.SEVERE, "Could not save " + outFile.getName() + " to " + outFile, ex);
    } finally {

    }
  }
}
