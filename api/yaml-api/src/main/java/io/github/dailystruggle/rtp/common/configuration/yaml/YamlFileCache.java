package io.github.dailystruggle.rtp.common.configuration.yaml;

import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.server.api.ServerAccessors;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Modification-time-cached directory map of {@link RtpYamlConfig} documents (ADR-025, ADR-115).
 *
 * <p>Decouples {@code ConfigParser} and {@code MultiConfigParser} from database-layer accessors so
 * YAML configuration loading remains self-contained in {@code yaml-api}.
 */
public class YamlFileCache implements ConfigParser.FileCacheProvider {

    /** Container for map reference access so {@code set()} propagates changes across holders. */
    public final AtomicReference<Map<String, RtpYamlConfig>> cachedLookup = new AtomicReference<>();

    /** Last-observed file modification timestamps keyed by file name. */
    public final AtomicReference<Map<String, Long>> cachedLookupLastModified = new AtomicReference<>();

    /** Last parse error message recorded per file name when {@link #connect()} fails to load a file. */
    public final Map<String, String> lastParseErrors = new ConcurrentHashMap<>();

    private final File directory;

    {
        cachedLookup.set(new ConcurrentHashMap<>());
        cachedLookupLastModified.set(new ConcurrentHashMap<>());
    }

    /**
     * Constructs a file cache rooted at {@code directory}.
     *
     * @param directory directory containing YAML files
     */
    public YamlFileCache(File directory) {
        this.directory = directory;
    }

    @Override
    public YamlFileCache yamlFileCache() {
        return this;
    }

    /**
     * Returns the directory managed by this cache.
     *
     * @return the root directory
     */
    public File directory() {
        return directory;
    }

    /**
     * Scans {@link #directory()} and loads or returns cached {@link RtpYamlConfig} instances for all
     * non-bootstrap YAML files whose modification time has changed.
     *
     * @return map of file name to loaded {@link RtpYamlConfig}
     */
    @NotNull
    public Map<String, RtpYamlConfig> connect() {
        return connect(null);
    }

    /**
     * Scans {@link #directory()}, invoking {@code onFreshLoad} whenever a file is parsed from disk
     * rather than served from the mtime cache.
     *
     * @param onFreshLoad optional callback invoked for freshly parsed files
     * @return map of file name to loaded {@link RtpYamlConfig}
     */
    @NotNull
    public Map<String, RtpYamlConfig> connect(
            @Nullable BiConsumer<String, RtpYamlConfig> onFreshLoad) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException(
                    "unable to create directory " + directory.getAbsolutePath());
        }

        File[] files = directory.listFiles();
        Map<String, RtpYamlConfig> res = new HashMap<>();
        if (files == null) return res;
        for (File file : files) {
            if (!file.isFile()) continue;
            // Skip the locale bootstrap file (LanguageBootstrap.FILE_NAME) - plugin configuration, not a database table.
            if ("language.yml".equalsIgnoreCase(file.getName())) continue;

            long lastModified = file.lastModified();
            long dt = lastModified - cachedLookupLastModified.get().getOrDefault(file.getName(), -1L);
            if (dt == 0) {
                res.put(file.getName(), cachedLookup.get().get(file.getName()));
                continue;
            }

            RtpYamlConfig yamlFile;
            try {
                yamlFile = new RtpYamlConfig(file);
                yamlFile.loadWithComments();
                cachedLookupLastModified.get().put(file.getName(), lastModified);
                cachedLookup.get().put(file.getName(), yamlFile);
                lastParseErrors.remove(file.getName());
            } catch (Exception exception) { // not a yaml file
                String msg = exception.getMessage();
                lastParseErrors.put(
                        file.getName(),
                        (msg != null && !msg.isBlank()) ? msg : exception.getClass().getSimpleName());
                continue;
            }
            res.put(file.getName(), yamlFile);
            if (onFreshLoad != null) {
                onFreshLoad.accept(file.getName(), yamlFile);
            }
        }

        return res;
    }

    /**
     * Persists all modified {@link RtpYamlConfig} documents in {@code database} to disk.
     *
     * @param database map of file name to {@link RtpYamlConfig}
     */
    public void disconnect(Map<String, RtpYamlConfig> database) {
        if (database == null) return;
        for (RtpYamlConfig file : database.values()) {
            try {
                if (!file.exists()) file.createNewFile(true);
                file.save();
            } catch (IOException e) {
                ServerAccessors.log(Level.WARNING, e.getMessage(), e);
            }
        }
    }

    /**
     * Compatibility hook for callers that flush pending queries before reloading.
     *
     * @param availableTime time budget in nanoseconds (unused for direct file cache)
     */
    public void processQueries(long availableTime) {
        // Direct YAML file cache has no queued async database writes.
    }

    /**
     * Compatibility lifecycle hook for test teardown.
     */
    public void close() {
        // Direct YAML file cache holds no persistent open descriptors.
    }
}
