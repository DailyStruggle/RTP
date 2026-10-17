package io.github.dailystruggle.rtp.common.importer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Registry and auto-detection manager for {@link ForeignConfigImporter} implementations.
 */
public final class ForeignConfigImporterRegistry {

    private static final Map<String, ForeignConfigImporter> IMPORTERS = new LinkedHashMap<>();
    private static final UniversalConfigImporter UNIVERSAL_IMPORTER = new UniversalConfigImporter();

    static {
        register(UNIVERSAL_IMPORTER);
    }

    private ForeignConfigImporterRegistry() {}

    public static void register(ForeignConfigImporter importer) {
        if (importer != null) {
            IMPORTERS.put(importer.sourceName().toLowerCase(Locale.ROOT), importer);
        }
    }

    public static ForeignConfigImporter getImporter(String name) {
        if (name == null) return null;
        String norm = name.trim().toLowerCase(Locale.ROOT);
        ForeignConfigImporter importer = IMPORTERS.get(norm);
        if (importer != null) return importer;
        // Generic fallback: any request maps to the universal importer
        return UNIVERSAL_IMPORTER;
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
     * @param sourceName name of the importer source or candidate folder
     * @return Path to the foreign plugin directory if found, or null
     */
    public static Path resolveSourceDir(Path pluginsDir, String sourceName) {
        if (pluginsDir == null || !Files.isDirectory(pluginsDir)) return null;
        if (sourceName == null) return null;

        String norm = sourceName.trim().toLowerCase(Locale.ROOT);

        // Direct directory checks across casing variations
        Path[] commonVariations = new Path[]{
                pluginsDir.resolve(sourceName),
                pluginsDir.resolve(norm),
                pluginsDir.resolve(sourceName.toUpperCase(Locale.ROOT)),
                pluginsDir.resolve(Character.toUpperCase(norm.charAt(0)) + norm.substring(1))
        };
        for (Path c : commonVariations) {
            if (Files.isDirectory(c)) return c;
        }

        // Search directory contents case-insensitively
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir)) {
            for (Path p : stream) {
                if (Files.isDirectory(p) && p.getFileName().toString().equalsIgnoreCase(sourceName)) {
                    return p;
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private static final Set<String> IGNORED_DIR_NAMES = Set.of(
            "rtp", "leafrtp", ".git", ".gradle", "build", "target", "out",
            "node_modules", ".idea", ".vscode", "temp", "tmp", "logs", "cache"
    );
    private static final int MAX_PROBED_DIRECTORIES = 64;

    /**
     * Probes the server plugins/ directory for available foreign configurations.
     * Bounds the directory inspection to prevent runaway filesystem walks in
     * non-server environments (e.g. workspace or OS temp roots).
     *
     * @param pluginsDir server's plugins/ directory
     * @return map of detected plugin directory name -> directory Path
     */
    public static Map<String, Path> detectAvailableSources(Path pluginsDir) {
        Map<String, Path> detected = new LinkedHashMap<>();
        if (pluginsDir == null || !Files.isDirectory(pluginsDir)) {
            return detected;
        }

        // 1. First probe known registered importer names directly (microsecond lookup)
        for (ForeignConfigImporter importer : IMPORTERS.values()) {
            if (importer == UNIVERSAL_IMPORTER) continue;
            Path resolved = resolveSourceDir(pluginsDir, importer.sourceName());
            if (resolved != null && importer.canImport(resolved)) {
                detected.put(resolved.getFileName().toString(), resolved);
            }
        }

        // 2. Bound adjacent directory scan for generic/custom sources
        int inspected = 0;
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir)) {
            for (Path dir : stream) {
                if (++inspected > MAX_PROBED_DIRECTORIES) {
                    break;
                }
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                String folderName = dir.getFileName().toString();
                if (folderName.startsWith(".") || IGNORED_DIR_NAMES.contains(folderName.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (detected.containsKey(folderName)) {
                    continue;
                }
                if (UNIVERSAL_IMPORTER.canImport(dir)) {
                    detected.put(folderName, dir);
                }
            }
        } catch (Exception ignored) {}

        return detected;
    }
}
