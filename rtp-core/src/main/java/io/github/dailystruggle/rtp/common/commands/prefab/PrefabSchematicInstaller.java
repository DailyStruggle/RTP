package io.github.dailystruggle.rtp.common.commands.prefab;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Extracts prefab {@code .schem} files into {@code advanced/schematics/<region>.schem}
 * on disk so per-region arrival schematics (ADR-058) resolve at runtime.
 *
 * <p>Skips existing destination files and records missing jar resources per S-004.
 */
public final class PrefabSchematicInstaller {

    /** Region-overlay key naming the schematic file (sans extension). */
    static final String SCHEMATIC_KEY = "schematic";
    /**
     * On-disk subdirectory (under the plugin directory) holding {@code .schem} files.
     * ADR-076 relocates this under the {@code advanced/} door.
     */
    static final String SCHEMATICS_SUBDIR = "advanced/schematics";
    /** Bundled-resource subdirectory (classpath) holding {@code .schem} files. */
    static final String RESOURCE_DIR = "/schematics/";
    static final String SCHEM_EXT = ".schem";

    private PrefabSchematicInstaller() {
    }

    /**
     * Outcome of {@link #install}.
     *
     * @param installed       schematic names copied from jar
     * @param skippedExisting schematic names already present on disk
     * @param missingResource schematic names referenced by prefab but absent from jar
     */
    public record Result(
            List<String> installed,
            List<String> skippedExisting,
            List<String> missingResource
    ) {
    }

    /**
     * Collect every distinct, non-blank schematic name referenced under a
     * {@value #SCHEMATIC_KEY} key anywhere in the prefab's region overlays.
     * Scans recursively so a knob nested under a sub-map is still found.
     *
     * @param prefab the prefab to inspect; never {@code null}.
     * @return distinct schematic names in first-seen order; never {@code null}.
     */
    public static Set<String> schematicNames(Prefab prefab) {
        Objects.requireNonNull(prefab, "prefab");
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> overlay : prefab.regionOverlays().values()) {
            collect(overlay, names);
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Map<String, Object> node, Set<String> names) {
        if (node == null) {
            return;
        }
        for (Map.Entry<String, Object> e : node.entrySet()) {
            Object value = e.getValue();
            if (SCHEMATIC_KEY.equals(e.getKey()) && value instanceof String s) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty()) {
                    names.add(trimmed);
                }
            } else if (value instanceof Map) {
                collect((Map<String, Object>) value, names);
            }
        }
    }

    /**
     * Extracts schematics referenced by prefab overlays to {@code <pluginDir>/advanced/schematics/<regionId>.schem}.
     * Skips existing files and records missing jar resources in {@link Result}.
     *
     * @param pluginDir platform plugin directory
     * @param prefab prefab being applied
     * @return {@link Result} of copied, skipped, and missing schematics
     * @throws IOException if destination file cannot be written
     */
    public static Result install(File pluginDir, Prefab prefab) throws IOException {
        Objects.requireNonNull(pluginDir, "pluginDir");
        Objects.requireNonNull(prefab, "prefab");

        List<String> installed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        File schematicsDir = new File(pluginDir, SCHEMATICS_SUBDIR);
        for (Map.Entry<String, Map<String, Object>> reg : prefab.regionOverlays().entrySet()) {
            String regionId = reg.getKey();
            String resource = findSchematic(reg.getValue());
            if (resource == null) {
                continue;
            }
            File dest = new File(schematicsDir, regionId + SCHEM_EXT);
            if (dest.exists()) {
                skipped.add(resource);
                continue;
            }
            try (InputStream in = PrefabSchematicInstaller.class
                    .getResourceAsStream(RESOURCE_DIR + resource + SCHEM_EXT)) {
                if (in == null) {
                    missing.add(resource);
                    continue;
                }
                if (!schematicsDir.exists() && !schematicsDir.mkdirs()) {
                    throw new IOException("failed to create directory - " + schematicsDir.getPath());
                }
                try (OutputStream out = Files.newOutputStream(dest.toPath())) {
                    in.transferTo(out);
                }
                installed.add(resource);
            }
        }
        return new Result(installed, skipped, missing);
    }

    /**
     * Returns the first non-blank {@value #SCHEMATIC_KEY} string value found in
     * {@code node} (scanned recursively), or {@code null} if none is present.
     */
    @SuppressWarnings("unchecked")
    private static String findSchematic(Map<String, Object> node) {
        if (node == null) {
            return null;
        }
        for (Map.Entry<String, Object> e : node.entrySet()) {
            Object value = e.getValue();
            if (SCHEMATIC_KEY.equals(e.getKey()) && value instanceof String s) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            } else if (value instanceof Map) {
                String nested = findSchematic((Map<String, Object>) value);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}
