package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.RTP;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.util.logging.Level;

public interface ConfigLoader {
    File getMainDirectory();
    ClassLoader getClassLoader();

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

    default void saveResourceFromJar(@NotNull String resourcePath, boolean replace) {
        resourcePath = resourcePath.replace('\\', '/');
        InputStream in = getResourceFromJar(resourcePath);
        if (in == null) {
            throw new IllegalArgumentException("The embedded resource '" + resourcePath + "' cannot be found in RTP");
        }

        File outFile = new File(getMainDirectory(), resourcePath);
        int lastIndex = resourcePath.lastIndexOf('/');
        File outDir = new File(getMainDirectory(), resourcePath.substring(0, Math.max(lastIndex, 0)));

        if (!outDir.exists()) {
            boolean mkdirs = outDir.mkdirs();
            if(!mkdirs) throw new IllegalStateException("failed to create directory - " + outDir.getPath());
        }

        try {
            if (!outFile.exists() || replace) {
                OutputStream out = new FileOutputStream(outFile);
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                out.close();
                in.close();
            } else {
                RTP.log(Level.WARNING, "Could not save " + outFile.getName() + " to " + outFile + " because " + outFile.getName() + " already exists.");
            }
        } catch (IOException ex) {
            RTP.log(Level.SEVERE, "Could not save " + outFile.getName() + " to " + outFile, ex);
        }
    }
}
