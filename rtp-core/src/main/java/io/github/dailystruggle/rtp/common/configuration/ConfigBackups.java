package io.github.dailystruggle.rtp.common.configuration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Shared disk-backup helper for YAML configuration files.
 * Manages {@code <file>.bak.<epochMillis>} sibling backups and FIFO retention.
 */
public final class ConfigBackups {

    /** Default bak retention if no explicit knob is supplied. */
    public static final int DEFAULT_BAK_RETENTION = 3;

    /** Sibling suffix prefix: {@code <file>.bak.<epochMillis>}. */
    public static final String BAK_INFIX = ".bak.";

    private ConfigBackups() {
    }

    /**
     * Copies {@code target} sideways as {@code <name>.bak.<epochMillis>} and prunes
     * sibling backups beyond {@code retention} (oldest first).
     *
     * @param retention number of backups to keep (clamped to >= 1).
     * @return path of created backup, or {@code null} if target did not exist.
     */
    public static Path backup(File target, int retention) throws IOException {
        Objects.requireNonNull(target, "target");
        int keep = Math.max(1, retention);
        if (!target.exists() || !target.isFile()) {
            return null;
        }
        String suffix = BAK_INFIX + System.currentTimeMillis();
        Path bakPath = target.toPath().resolveSibling(target.getName() + suffix);
        // Tight loop guard for sub-millisecond consecutive writes (test scaffolds).
        int collisionGuard = 0;
        while (Files.exists(bakPath) && collisionGuard++ < 1000) {
            suffix = BAK_INFIX + (System.currentTimeMillis() + collisionGuard);
            bakPath = target.toPath().resolveSibling(target.getName() + suffix);
        }
        Files.copy(target.toPath(), bakPath, StandardCopyOption.REPLACE_EXISTING);
        pruneBaks(target, keep);
        return bakPath;
    }

    /**
     * List the {@code .bak.<ts>} sibling files for {@code target}, newest first
     * (highest epoch ms). Non-bak files are filtered out. Returns an empty list
     * if the parent directory does not exist.
     */
    public static List<Path> listBaks(File target) {
        Objects.requireNonNull(target, "target");
        File parent = target.getParentFile();
        if (parent == null || !parent.isDirectory()) return new ArrayList<>();
        String prefix = target.getName() + BAK_INFIX;
        File[] kids = parent.listFiles((dir, name) -> name.startsWith(prefix) && parseEpoch(name, prefix) >= 0);
        if (kids == null || kids.length == 0) return new ArrayList<>();
        List<Path> out = new ArrayList<>(kids.length);
        for (File k : kids) out.add(k.toPath());
        out.sort(Comparator.comparingLong((Path p) -> parseEpoch(p.getFileName().toString(), prefix)).reversed());
        return out;
    }

    /**
     * Restore the newest {@code .bak.<ts>} sibling over {@code target},
     * atomically when the filesystem supports it. The chosen backup is then
     * removed (consumed) so a subsequent rollback walks to the next-newest.
     * Returns {@code null} when no backups exist.
     */
    public static Path restoreLatest(File target) throws IOException {
        Objects.requireNonNull(target, "target");
        List<Path> baks = listBaks(target);
        if (baks.isEmpty()) return null;
        Path newest = baks.get(0);
        try {
            Files.move(newest, target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (UnsupportedOperationException uoe) {
            // Filesystem can't do ATOMIC_MOVE (Windows across volumes, some CI
            // containers). Fall back to copy+delete which preserves the restore
            // semantic at the cost of a non-atomic window.
            Files.copy(newest, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(newest);
        }
        return newest;
    }

    /**
     * Retention sweep: keep at most {@code keep} backups for {@code target},
     * deleting the oldest.
     */
    public static int pruneBaks(File target, int keep) {
        if (keep < 0) keep = 0;
        List<Path> baks = listBaks(target); // newest first
        if (baks.size() <= keep) return 0;
        int pruned = 0;
        for (int i = keep; i < baks.size(); i++) {
            try {
                Files.deleteIfExists(baks.get(i));
                pruned++;
            } catch (IOException ignored) {
                // Best-effort prune; surfacing a checked exception per file
                // would force the write path to roll back the new backup,
                // which is worse than leaving an extra stale file.
            }
        }
        return pruned;
    }

    private static long parseEpoch(String fileName, String prefix) {
        if (!fileName.startsWith(prefix)) return -1L;
        String tail = fileName.substring(prefix.length());
        // Tolerate test-only sub-millisecond disambiguators tacked onto the
        // epoch (see backup): everything must still be ASCII digits.
        if (tail.isEmpty()) return -1L;
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c < '0' || c > '9') return -1L;
        }
        try {
            return Long.parseLong(tail);
        } catch (NumberFormatException nfe) {
            return -1L;
        }
    }
}
