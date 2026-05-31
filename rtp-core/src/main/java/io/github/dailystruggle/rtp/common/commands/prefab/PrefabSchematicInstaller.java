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
 * Extracts the {@code .schem} file(s) a prefab references into the platform's
 * on-disk {@code schematics/} directory so the per-region {@code schematic}
 * knob (ADR-058) resolves to a real file after the prefab is applied.
 *
 * <p>A prefab such as {@link io.github.dailystruggle.rtp.common.commands.prefab.builtin.Skyblock}
 * writes {@code schematic: "skyblock"} into a region overlay. That name is
 * resolved by core to {@code <pluginDir>/schematics/<name>.schem}; the file
 * has to exist there or the paste is a no-op {@code MISSING_SOURCE}. The
 * bundled schematic ships in the jar at {@code /schematics/<name>.schem}, so
 * this installer copies it out the first time the prefab is confirmed.
 *
 * <p>Behaviour mirrors {@code ConfigLoader.saveResourceFromJar}: the copy is
 * skipped when the destination already exists (operators may have edited or
 * replaced it), and a missing bundled resource is recorded rather than thrown
 * so a paste failure never aborts the prefab apply. The caller audits the
 * {@link Result} via {@code RTP.log} per S-004.
 */
public final class PrefabSchematicInstaller {

    /** Region-overlay key naming the schematic file (sans extension). */
    static final String SCHEMATIC_KEY = "schematic";
    /** On-disk subdirectory (under the plugin directory) holding {@code .schem} files. */
    static final String SCHEMATICS_SUBDIR = "schematics";
    /** Bundled-resource subdirectory (classpath) holding {@code .schem} files. */
    static final String RESOURCE_DIR = "/schematics/";
    static final String SCHEM_EXT = ".schem";

    private PrefabSchematicInstaller() {
    }

    /**
     * Outcome of {@link #install}. Each list holds schematic names (sans
     * extension): {@code installed} were copied out of the jar this call,
     * {@code skippedExisting} were already present on disk, and
     * {@code missingResource} are referenced by the prefab but absent from the
     * jar (a defect to audit, never fatal).
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
     * Extract every schematic the prefab references from the jar into
     * {@code <pluginDir>/schematics/}. Existing destination files are left
     * untouched; a bundled resource that cannot be found is recorded in
     * {@link Result#missingResource()} rather than thrown.
     *
     * @param pluginDir the platform plugin directory (the {@code schematics/}
     *                  subdirectory is created beneath it); never {@code null}.
     * @param prefab    the prefab being applied; never {@code null}.
     * @return a {@link Result} describing what was copied, skipped, or missing.
     * @throws IOException if a destination file could not be written.
     */
    public static Result install(File pluginDir, Prefab prefab) throws IOException {
        Objects.requireNonNull(pluginDir, "pluginDir");
        Objects.requireNonNull(prefab, "prefab");

        List<String> installed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        Set<String> names = schematicNames(prefab);
        if (names.isEmpty()) {
            return new Result(installed, skipped, missing);
        }

        File schematicsDir = new File(pluginDir, SCHEMATICS_SUBDIR);
        for (String name : names) {
            File dest = new File(schematicsDir, name + SCHEM_EXT);
            if (dest.exists()) {
                skipped.add(name);
                continue;
            }
            try (InputStream in = PrefabSchematicInstaller.class
                    .getResourceAsStream(RESOURCE_DIR + name + SCHEM_EXT)) {
                if (in == null) {
                    missing.add(name);
                    continue;
                }
                if (!schematicsDir.exists() && !schematicsDir.mkdirs()) {
                    throw new IOException("failed to create directory - " + schematicsDir.getPath());
                }
                try (OutputStream out = Files.newOutputStream(dest.toPath())) {
                    in.transferTo(out);
                }
                installed.add(name);
            }
        }
        return new Result(installed, skipped, missing);
    }
}
