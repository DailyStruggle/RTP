package io.github.dailystruggle.rtp.common.configuration;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

public class YAMLLoader {
    private final ClassLoader classLoader;

    public YAMLLoader() {
        classLoader = this.getClass().getClassLoader();
    }

    @Nullable
    public InputStream getResource(@NotNull String filename) {
        try {
            URL url = classLoader.getResource(filename);

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
}
