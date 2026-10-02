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

    /**
     * Probes the server plugins/ directory for available foreign configurations.
     *
     * @param pluginsDir server's plugins/ directory
     * @return map of detected plugin directory name -> directory Path
     */
    public static Map<String, Path> detectAvailableSources(Path pluginsDir) {
        Map<String, Path> detected = new LinkedHashMap<>();
        if (pluginsDir == null || !Files.isDirectory(pluginsDir)) {
            return detected;
        }

        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir)) {
            for (Path dir : stream) {
                if (Files.isDirectory(dir)) {
                    String folderName = dir.getFileName().toString();
                    if (folderName.equalsIgnoreCase("RTP") || folderName.equalsIgnoreCase("LeafRTP")) {
                        continue; // skip our own directory
                    }
                    if (UNIVERSAL_IMPORTER.canImport(dir)) {
                        detected.put(folderName, dir);
                    }
                }
            }
        } catch (Exception ignored) {}

        return detected;
    }
}
