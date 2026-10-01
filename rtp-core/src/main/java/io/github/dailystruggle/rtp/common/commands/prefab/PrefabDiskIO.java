package io.github.dailystruggle.rtp.common.commands.prefab;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigBackups;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * On-disk helpers for the prefab pipeline. Pure static methods testable
 * against temporary directories.
 *
 * <p>Handles YAML reading, diff application, and timestamped {@code .bak} rotation.
 */
public final class PrefabDiskIO {

    /** Default bak retention if the {@code prefab.bakRetention} knob is absent. */
    public static final int DEFAULT_BAK_RETENTION = ConfigBackups.DEFAULT_BAK_RETENTION;

    /** Sibling suffix prefix: {@code <file>.bak.<epochMillis>}. */
    public static final String BAK_INFIX = ConfigBackups.BAK_INFIX;

    private PrefabDiskIO() {
    }

    /**
     * Safely resolve the plugin directory. If absent, logs a warning and informs caller.
     *
     * @param callerId recipient UUID (optional / nullable)
     * @param actionName name of the action being attempted (e.g. "apply", "confirm", "rollback")
     * @param prefabId id of the target prefab (or empty/null)
     * @param userFacingFailure optional custom message sent to caller when directory is missing
     * @return Optional containing the plugin directory, or empty if unavailable.
     */
    public static Optional<File> resolvePluginDirectory(UUID callerId,
                                                        String actionName,
                                                        String prefabId,
                                                        String userFacingFailure) {
        File dir = (RTP.serverAccessor != null) ? RTP.serverAccessor.getPluginDirectory() : null;
        if (dir == null) {
            String pId = (prefabId == null) ? "" : prefabId;
            RTP.log(Level.WARNING,
                    "[prefab] " + actionName + " rejected NO_PLUGIN_DIR: caller=" + callerId
                            + (pId.isEmpty() ? "" : " prefab=" + pId));
            if (userFacingFailure != null && !userFacingFailure.isEmpty()) {
                send(callerId, userFacingFailure);
            }
            return Optional.empty();
        }
        return Optional.of(dir);
    }

    /**
     * Convenience overload to safely resolve the plugin directory with standard operator notification.
     *
     * @param callerId recipient UUID
     * @return Optional containing the plugin directory, or empty if unavailable.
     */
    public static Optional<File> resolvePluginDirectory(UUID callerId) {
        return resolvePluginDirectory(callerId, "operation", null, "&cPlugin directory unavailable.");
    }

    /**
     * Send a message to a caller if server accessor is present.
     * Tolerant of test scaffolds without a real sender.
     */
    public static void send(UUID callerId, String msg) {
        if (callerId == null || RTP.serverAccessor == null) return;
        try {
            RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, msg);
        } catch (RuntimeException ignored) {
            // Tolerant of test scaffolds without a real sender.
        }
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
            snapshot.put("advanced/performance", readLive(pluginDirectory, "advanced/performance"));
        }
        if (!prefab.safetyOverlay().isEmpty()) {
            snapshot.put("safety", readLive(pluginDirectory, "safety"));
        }
        for (String regionId : prefab.regionOverlays().keySet()) {
            String fileId = "definitions/regions/" + regionId;
            snapshot.put(fileId, readLive(pluginDirectory, fileId));
        }
        return snapshot;
    }

    /**
     * Snapshot the live baseline configuration for a prefab, handling expandPerWorld template
     * seeding and fallback safely.
     *
     * @param pluginDir the plugin directory (nullable)
     * @param prefab target prefab
     * @return map of fileId -> live YAML tree
     */
    public static Map<String, Map<String, Object>> snapshotLiveBaseline(File pluginDir, Prefab prefab) {
        Map<String, Map<String, Object>> baseline;
        try {
            baseline = (pluginDir == null)
                    ? new LinkedHashMap<>()
                    : snapshotLive(pluginDir, prefab);
            if (prefab.expandPerWorld() && pluginDir != null
                    && !baseline.containsKey("definitions/regions/" + MultiWorldExpander.DEFAULT_REGION_ID)) {
                baseline.put("definitions/regions/" + MultiWorldExpander.DEFAULT_REGION_ID,
                        readLive(pluginDir, "definitions/regions/" + MultiWorldExpander.DEFAULT_REGION_ID));
            }
        } catch (RuntimeException re) {
            RTP.log(Level.WARNING,
                    "[prefab] apply: live snapshot failed for " + prefab.id()
                            + " - falling back to empty baseline: " + re.getMessage());
            baseline = new LinkedHashMap<>();
        }
        return baseline;
    }

    /**
     * Writes changes for a single file id to disk, saving a {@code .bak} copy first.
     * Preserves comments and prunes excess backups beyond {@code bakRetention}.
     *
     * @param bakRetention number of backups to keep (clamped to >= 1).
     * @return path of created backup, or {@code null} if target was a new file.
     */
    public static Path writeWithBackup(File pluginDirectory,
                                       String fileId,
                                       Map<String, Object> newTree,
                                       List<PrefabApplier.Change> changes,
                                       int bakRetention) throws IOException {
        return writeWithBackup(pluginDirectory, fileId, newTree, changes, bakRetention, null);
    }

    /**
     * Overload that seeds comments of a brand-new target file from {@code templateFileId}
     * (e.g. {@code "regions/default"}). Ignored if the target already exists.
     *
     * @param templateFileId optional template file id; {@code null} disables seeding.
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

    /**
     * Reads the {@code prefab.bakRetention} knob from {@code advanced/performance.yml} directly.
     * Falls back to {@link #DEFAULT_BAK_RETENTION} on missing/invalid value.
     */
    @SuppressWarnings("unchecked")
    public static int resolveBakRetention() {
        try {
            if (RTP.serverAccessor == null) return DEFAULT_BAK_RETENTION;
            File pluginDir = RTP.serverAccessor.getPluginDirectory();
            if (pluginDir == null) return DEFAULT_BAK_RETENTION;
            Map<String, Object> perf = readLive(pluginDir, "advanced/performance");
            if (perf.isEmpty()) {
                perf = readLive(pluginDir, "performance");
            }
            Object prefabNode = perf.get("prefab");
            if (!(prefabNode instanceof Map<?, ?>)) return DEFAULT_BAK_RETENTION;
            Object raw = ((Map<String, Object>) prefabNode).get("bakRetention");
            if (raw == null) return DEFAULT_BAK_RETENTION;
            if (raw instanceof Number n) return Math.max(1, n.intValue());
            try {
                return Math.max(1, Integer.parseInt(raw.toString().trim()));
            } catch (NumberFormatException nfe) {
                return DEFAULT_BAK_RETENTION;
            }
        } catch (RuntimeException re) {
            return DEFAULT_BAK_RETENTION;
        }
    }

    /**
     * Enumerate all file IDs that a prefab touches across its performance, safety, and region overlays.
     * Includes fallback legacy paths (e.g. without definitions/ prefix) for rollback restoration checks.
     */
    public static Set<String> enumeratePrefabFileIds(Prefab prefab) {
        Set<String> fileIds = new LinkedHashSet<>();
        if (!prefab.performanceOverlay().isEmpty()) {
            fileIds.add("advanced/performance");
            fileIds.add("performance");
        }
        if (!prefab.safetyOverlay().isEmpty()) {
            fileIds.add("safety");
        }
        for (String regionId : prefab.regionOverlays().keySet()) {
            fileIds.add("definitions/regions/" + regionId);
            fileIds.add("regions/" + regionId);
        }
        return fileIds;
    }

    /**
     * Format a summary line for created backup files.
     */
    public static String formatBackupSummary(List<String> backupFileNames) {
        if (backupFileNames == null || backupFileNames.isEmpty()) {
            return "";
        }
        return "&7Backups: &f" + String.join(", ", backupFileNames);
    }
}
