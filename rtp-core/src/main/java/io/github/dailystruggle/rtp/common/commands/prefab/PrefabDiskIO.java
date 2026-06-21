package io.github.dailystruggle.rtp.common.commands.prefab;

import io.github.dailystruggle.rtp.common.configuration.ConfigBackups;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Session 4b on-disk side of the prefab pipeline. Pure static helpers; no
 * dependency on {@link io.github.dailystruggle.rtp.common.RTP} so each
 * method is unit-testable against a {@code @TempDir} plugin directory.
 *
 * <p>File-id convention (matches {@link PrefabApplier}):
 * <ul>
 *   <li>{@code "performance"} -> {@code <pluginDir>/performance.yml}</li>
 *   <li>{@code "regions/<id>"} -> {@code <pluginDir>/regions/<id>.yml}</li>
 * </ul>
 *
 * <p>Backup naming: {@code <originalFileName>.bak.<epochMillis>} as a sibling
 * of the original file. Rotation is FIFO by epoch (lexicographic on the
 * fixed-width suffix), oldest pruned first once the count exceeds the
 * retention cap.
 */
public final class PrefabDiskIO {

    /** Default bak retention if the {@code prefab.bakRetention} knob is absent. */
    public static final int DEFAULT_BAK_RETENTION = ConfigBackups.DEFAULT_BAK_RETENTION;

    /** Sibling suffix prefix: {@code <file>.bak.<epochMillis>}. */
    public static final String BAK_INFIX = ConfigBackups.BAK_INFIX;

    private PrefabDiskIO() {
    }

    /**
     * Read the live YAML tree for a single file id. Returns an empty map if
     * the file does not exist (so prefabs introducing a brand-new
     * {@code regions/<id>.yml} can still diff cleanly).
     */
    public static Map<String, Object> readLive(File pluginDirectory, String fileId) {
        Objects.requireNonNull(pluginDirectory, "pluginDirectory");
        Objects.requireNonNull(fileId, "fileId");
        File f = resolveFile(pluginDirectory, fileId);
        if (!f.exists() || !f.isFile()) {
            return new LinkedHashMap<>();
        }
        RtpYamlConfig cfg = new RtpYamlConfig(f);
        try {
            cfg.load();
        } catch (IOException ioe) {
            // Treat unreadable as empty; the apply diff will then describe a
            // full file write rather than masking the failure mode.
            return new LinkedHashMap<>();
        }
        // Use the shallow (non-deep) read and recurse manually: getMapValues(true)
        // FLATTENS nested sections into dot-delimited keys (e.g. "shape.name"),
        // which defeats PrefabApplier's nested-map merge (it would then see no
        // "shape" Map in the base and replace the whole node wholesale, dropping
        // sibling keys and comments). A genuinely nested tree is required so the
        // sparse overlay merges key-by-key.
        return deepCopy(cfg.getMapValues(false));
    }

    /**
     * Snapshot every file id touched by {@code prefab} (its performance
     * overlay, its safety overlay, and each region overlay) into a
     * {@code fileId -> tree} map
     * suitable as the {@code currentTrees} argument to
     * {@link PrefabApplier#apply}.
     */
    public static Map<String, Map<String, Object>> snapshotLive(File pluginDirectory, Prefab prefab) {
        Objects.requireNonNull(prefab, "prefab");
        Map<String, Map<String, Object>> snapshot = new LinkedHashMap<>();
        if (!prefab.performanceOverlay().isEmpty()) {
            snapshot.put("performance", readLive(pluginDirectory, "performance"));
        }
        if (!prefab.safetyOverlay().isEmpty()) {
            snapshot.put("safety", readLive(pluginDirectory, "safety"));
        }
        for (String regionId : prefab.regionOverlays().keySet()) {
            String fileId = "regions/" + regionId;
            snapshot.put(fileId, readLive(pluginDirectory, fileId));
        }
        return snapshot;
    }

    /**
     * Write the new tree to disk for a single file id. Behaviour:
     * <ol>
     *   <li>If the original file exists, copy it sideways as
     *       {@code <name>.bak.<epochMillis>} via
     *       {@link ConfigBackups#backup(File, int)}.</li>
     *   <li>Load the original with comments preserved (or create an empty
     *       config when the file is brand-new) so structural comments survive.</li>
     *   <li>Apply each diff entry via {@link io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection#set}
     *       (dot-delimited key paths).</li>
     *   <li>Save with comments preserved.</li>
     *   <li>Prune sibling backups beyond {@code bakRetention}, oldest first.</li>
     * </ol>
     *
     * @param bakRetention number of {@code .bak.<ts>} siblings to keep
     *                     <strong>after</strong> the new backup is written.
     *                     Clamped to {@code >= 1}; pass at least 1 so the
     *                     just-written backup survives.
     * @return the path of the freshly-written backup file, or {@code null}
     *         if the original did not exist (new-file prefab).
     */
    public static Path writeWithBackup(File pluginDirectory,
                                       String fileId,
                                       Map<String, Object> newTree,
                                       List<PrefabApplier.Change> changes,
                                       int bakRetention) throws IOException {
        return writeWithBackup(pluginDirectory, fileId, newTree, changes, bakRetention, null);
    }

    /**
     * Overload that seeds the comments of a brand-new target file from a
     * {@code templateFileId} (e.g. {@code "regions/default"}). When the target
     * already exists this argument is ignored - the live file's own comments
     * are preserved as usual. When the target does not exist and the template
     * file does, the template is loaded (with comments) and re-bound to the
     * target path before the changes are applied, so a synthesised per-world
     * region overlay inherits the structural comments of the reference
     * {@code regions/default.yml} instead of writing a comment-less file.
     *
     * @param templateFileId optional file id whose comments seed a new target;
     *                       {@code null} disables template seeding.
     */
    public static Path writeWithBackup(File pluginDirectory,
                                       String fileId,
                                       Map<String, Object> newTree,
                                       List<PrefabApplier.Change> changes,
                                       int bakRetention,
                                       String templateFileId) throws IOException {
        Objects.requireNonNull(pluginDirectory, "pluginDirectory");
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(newTree, "newTree");
        Objects.requireNonNull(changes, "changes");
        int retention = Math.max(1, bakRetention);
        File target = resolveFile(pluginDirectory, fileId);

        Path bakPath = ConfigBackups.backup(target, retention);
        if (bakPath == null) {
            // Parent directory may not exist (regions/<new>.yml).
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                if (!parent.mkdirs() && !parent.exists()) {
                    throw new IOException("could not create parent directory: " + parent);
                }
            }
        }

        RtpYamlConfig cfg = null;
        if (target.exists() && target.isFile()) {
            cfg = new RtpYamlConfig(target);
            cfg.loadWithComments();
        } else if (templateFileId != null) {
            // New file with a comment template: seed structure and comments
            // from the template (e.g. regions/default.yml), then re-bind the
            // config to the target path so the save lands on the new file.
            File template = resolveFile(pluginDirectory, templateFileId);
            if (template.exists() && template.isFile()) {
                cfg = new RtpYamlConfig(template);
                cfg.loadWithComments();
                cfg.setConfigurationFile(target);
            }
        }
        if (cfg == null) {
            // New file: write a fresh empty config bound to the target path.
            cfg = new RtpYamlConfig(target);
        }
        for (PrefabApplier.Change c : changes) {
            cfg.set(c.keyPath(), c.newValue());
        }
        cfg.saveWithComments();
        return bakPath;
    }

    /**
     * List the {@code .bak.<ts>} sibling files for {@code fileId}, newest
     * first (highest epoch ms). Non-bak files are filtered out. Returns an
     * empty list if the parent directory does not exist.
     */
    public static List<Path> listBaks(File pluginDirectory, String fileId) {
        Objects.requireNonNull(pluginDirectory, "pluginDirectory");
        Objects.requireNonNull(fileId, "fileId");
        return ConfigBackups.listBaks(resolveFile(pluginDirectory, fileId));
    }

    /**
     * Restore the newest {@code .bak.<ts>} sibling over the live file for
     * {@code fileId}, atomically when the filesystem supports it. The chosen
     * backup is then removed (consumed) so a subsequent rollback walks to
     * the next-newest. Returns {@code null} when no backups exist.
     */
    public static Path restoreLatest(File pluginDirectory, String fileId) throws IOException {
        Objects.requireNonNull(pluginDirectory, "pluginDirectory");
        Objects.requireNonNull(fileId, "fileId");
        return ConfigBackups.restoreLatest(resolveFile(pluginDirectory, fileId));
    }

    /**
     * Retention sweep: keep at most {@code keep} backups for {@code fileId},
     * deleting the oldest. Exposed for tests; {@link #writeWithBackup}
     * invokes it automatically.
     */
    public static int pruneBaks(File pluginDirectory, String fileId, int keep) {
        Objects.requireNonNull(pluginDirectory, "pluginDirectory");
        Objects.requireNonNull(fileId, "fileId");
        return ConfigBackups.pruneBaks(resolveFile(pluginDirectory, fileId), keep);
    }

    /** Resolve a file id to an absolute {@link File} under {@code pluginDirectory}. */
    public static File resolveFile(File pluginDirectory, String fileId) {
        Objects.requireNonNull(pluginDirectory, "pluginDirectory");
        Objects.requireNonNull(fileId, "fileId");
        String fid = fileId.trim();
        if (fid.isEmpty()) throw new IllegalArgumentException("empty fileId");
        // Reject path-traversal attempts; file ids are always simple slash-segmented relpaths.
        if (fid.contains("..") || fid.startsWith("/") || fid.startsWith("\\")) {
            throw new IllegalArgumentException("invalid fileId: " + fileId);
        }
        String relPath = fid.replace('/', File.separatorChar) + ".yml";
        return new File(pluginDirectory, relPath);
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in == null) return out;
        for (Map.Entry<String, Object> e : in.entrySet()) {
            Object v = e.getValue();
            if (v instanceof RtpYamlSection section) {
                // Nested section (shallow getMapValues(false) coerces nested
                // mappings to RtpYamlSection): recurse so the resulting tree is
                // a plain nested Map that PrefabApplier can merge key-by-key.
                out.put(e.getKey(), deepCopy(section.getMapValues(false)));
            } else if (v instanceof Map<?, ?>) {
                out.put(e.getKey(), deepCopy((Map<String, Object>) v));
            } else if (v instanceof List<?>) {
                out.put(e.getKey(), new ArrayList<>((List<Object>) v));
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    // Defensive: keep imports tidy / used.
    @SuppressWarnings("unused")
    private static String describeLocale() {
        return Locale.ROOT.toString();
    }
}
