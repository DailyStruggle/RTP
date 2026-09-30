package io.github.dailystruggle.rtp.common.importer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Registry and auto-detection manager for {@link ForeignConfigImporter} implementations.
 */
public final class ForeignConfigImporterRegistry {

    private static final Map<String, ForeignConfigImporter> IMPORTERS = new LinkedHashMap<>();

    static {
        register(new BetterRtpConfigImporter());
        register(new EzRtpConfigImporter());
        register(new JustRtpConfigImporter());
        register(new JakesRtpConfigImporter());
    }

    private ForeignConfigImporterRegistry() {}

    public static void register(ForeignConfigImporter importer) {
        if (importer != null) {
            IMPORTERS.put(importer.sourceName().toLowerCase(Locale.ROOT), importer);
        }
    }

    public static ForeignConfigImporter getImporter(String name) {
        if (name == null) return null;
        return IMPORTERS.get(name.trim().toLowerCase(Locale.ROOT));
    }

    public static Collection<ForeignConfigImporter> getAllImporters() {
        return Collections.unmodifiableCollection(IMPORTERS.values());
    }

    public static Set<String> getRegisteredSourceNames() {
        return Collections.unmodifiableSet(IMPORTERS.keySet());
    }

    /**
     * Resolves the candidate source directory for a given importer in the parent plugins/ directory.
     *
     * @param pluginsDir server's plugins/ directory
     * @param sourceName name of the importer source
     * @return Path to the foreign plugin directory if found, or null
     */
    public static Path resolveSourceDir(Path pluginsDir, String sourceName) {
        if (pluginsDir == null || !Files.isDirectory(pluginsDir)) return null;
        if (sourceName == null) return null;

        String norm = sourceName.trim().toLowerCase(Locale.ROOT);
        if ("betterrtp".equals(norm)) {
            Path[] candidates = new Path[]{
                    pluginsDir.resolve("BetterRTP"),
                    pluginsDir.resolve("betterrtp"),
                    pluginsDir.resolve("BETTERRTP")
            };
            for (Path c : candidates) {
                if (Files.isDirectory(c)) return c;
            }
        } else if ("ezrtp".equals(norm)) {
            // Check common casing variations
            Path[] candidates = new Path[]{
                    pluginsDir.resolve("EzRTP"),
                    pluginsDir.resolve("ezrtp"),
                    pluginsDir.resolve("EZRTP")
            };
            for (Path c : candidates) {
                if (Files.isDirectory(c)) return c;
            }
        } else if ("justrtp".equals(norm)) {
            Path[] candidates = new Path[]{
                    pluginsDir.resolve("justRTP"),
                    pluginsDir.resolve("JustRTP"),
                    pluginsDir.resolve("justrtp"),
                    pluginsDir.resolve("JUSTRTP")
            };
            for (Path c : candidates) {
                if (Files.isDirectory(c)) return c;
            }
        } else if ("jakesrtp".equals(norm)) {
            Path[] candidates = new Path[]{
                    pluginsDir.resolve("JakesRTP"),
                    pluginsDir.resolve("jakesrtp"),
                    pluginsDir.resolve("JAKESRTP")
            };
            for (Path c : candidates) {
                if (Files.isDirectory(c)) return c;
            }
        }
        Path direct = pluginsDir.resolve(sourceName);
        if (Files.isDirectory(direct)) return direct;
        return null;
    }

    /**
     * Probes the server plugins/ directory for available foreign configurations.
     *
     * @param pluginsDir server's plugins/ directory
     * @return map of source name -> detected directory
     */
    public static Map<String, Path> detectAvailableSources(Path pluginsDir) {
        Map<String, Path> detected = new LinkedHashMap<>();
        if (pluginsDir == null || !Files.isDirectory(pluginsDir)) {
            return detected;
        }

        for (ForeignConfigImporter importer : IMPORTERS.values()) {
            Path dir = resolveSourceDir(pluginsDir, importer.sourceName());
            if (dir != null && importer.canImport(dir)) {
                detected.put(importer.sourceName(), dir);
            }
        }
        return detected;
    }
}
