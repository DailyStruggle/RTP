package io.github.dailystruggle.rtp.anvil;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves region file paths and determines the appropriate {@link RegionFileReader}
 * format for a given coordinate (ADR-077).
 *
 * <p>Supports standard Anvil ({@code .mca}) plus any format extension registered with
 * {@link RegionFormatRegistry}. Files in unregistered formats are ignored.</p>
 */
public final class RegionFileResolver {

    private RegionFileResolver() {}

    /**
     * Target region file description holding its on-disk path, matching reader, and format metadata.
     */
    public record ResolvedRegion(Path path, RegionFileReader reader) {}

    /**
     * Resolves the on-disk region file and matching reader for chunk {@code (cx, cz)}.
     * Probes registered non-Anvil formats first, then {@code .mca}.
     *
     * @param worldFolder      the root world directory
     * @param dimensionSubpath dimension subdirectory (e.g., {@code "DIM-1"}, {@code "DIM1"}, or {@code ""})
     * @param cx               chunk X coordinate
     * @param cz               chunk Z coordinate
     * @return {@link ResolvedRegion} with the existing file path, or the default {@code .mca} path if neither exists on disk
     */
    public static ResolvedRegion resolve(Path worldFolder, String dimensionSubpath, int cx, int cz) {
        Path dir = regionDirectoryFor(worldFolder, dimensionSubpath);
        String baseName = "r." + (cx >> 5) + "." + (cz >> 5);
        ResolvedRegion found = resolveOnDisk(dir, baseName);
        // Neither exists on disk - default to .mca path and Anvil reader
        return found != null ? found : new ResolvedRegion(dir.resolve(baseName + ".mca"), AnvilReader.INSTANCE);
    }

    /** Memo entry; {@code region == null} records that no region file existed. */
    private record Memo(ResolvedRegion region, long checkedNanos) {}

    private static final int MEMO_CAPACITY = 4096;

    private static final LinkedHashMap<Path, Memo> MEMO =
            new LinkedHashMap<>(MEMO_CAPACITY * 2, 0.75f, /* accessOrder = */ true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Path, Memo> eldest) {
                    return size() > MEMO_CAPACITY;
                }
            };

    /**
     * As {@link #resolve} but returns {@code null} when no region file exists, memoizing the answer
     * (present or absent) for {@link AnvilRegionByteCache#revalidateIntervalMillis()}. Inside the
     * window a probe pays no existence syscalls; a file created or converted by a save is observed
     * within one window, well under the chunk-save cadence. Blocking stat: off the tick thread (S-005).
     */
    public static ResolvedRegion resolveExisting(Path worldFolder, String dimensionSubpath, int cx, int cz) {
        Path dir = regionDirectoryFor(worldFolder, dimensionSubpath);
        String baseName = "r." + (cx >> 5) + "." + (cz >> 5);
        Path key = dir.resolve(baseName + ".mca");
        long window = AnvilRegionByteCache.revalidateIntervalNanos();
        if (window > 0L) {
            Memo memo;
            synchronized (MEMO) {
                memo = MEMO.get(key);
            }
            if (memo != null && System.nanoTime() - memo.checkedNanos() < window) {
                return memo.region();
            }
        }
        ResolvedRegion found = resolveOnDisk(dir, baseName);
        if (window > 0L) {
            synchronized (MEMO) {
                MEMO.put(key, new Memo(found, System.nanoTime()));
            }
        }
        return found;
    }

    /** Clears the {@link #resolveExisting} memo. Test hook. */
    public static void invalidateMemo() {
        synchronized (MEMO) {
            MEMO.clear();
        }
    }

    /** Existing region file for {@code baseName} in {@code dir}, or {@code null}. */
    private static ResolvedRegion resolveOnDisk(Path dir, String baseName) {
        // Registered non-Anvil formats first: a server that converted its world keeps stale .mca files.
        Set<String> extensions = RegionFormatRegistry.getRegisteredExtensions();
        for (String ext : extensions) {
            if (".mca".equalsIgnoreCase(ext)) continue;
            Path customPath = dir.resolve(baseName + ext);
            if (Files.isRegularFile(customPath)) {
                RegionFileReader reader = RegionFormatRegistry.getReader(ext);
                if (reader != null) {
                    return new ResolvedRegion(customPath, reader);
                }
            }
        }

        Path mcaPath = dir.resolve(baseName + ".mca");
        if (Files.isRegularFile(mcaPath)) {
            return new ResolvedRegion(mcaPath, AnvilReader.INSTANCE);
        }
        return null;
    }

    /**
     * Returns the region directory path for the given world and dimension.
     */
    public static Path regionDirectoryFor(Path worldFolder, String dimensionSubpath) {
        if (dimensionSubpath == null || dimensionSubpath.isEmpty()) {
            return worldFolder.resolve("region");
        }
        return worldFolder.resolve(dimensionSubpath).resolve("region");
    }

    private static final Pattern ANVIL_FILE = Pattern.compile("r\\.(-?\\d{1,7})\\.(-?\\d{1,7})\\.mca");

    /**
     * Coordinates {@code [rx, rz]} of every non-empty {@code r.X.Z.mca} in {@code regionDir}.
     * Directory listing only; never opens a file. Blocking: call off the tick thread (S-005).
     *
     * @return coordinates in listing order; empty when the directory does not exist
     * @throws IOException when the directory exists but cannot be listed
     */
    public static List<int[]> listAnvilRegionCoords(Path regionDir) throws IOException {
        List<int[]> out = new ArrayList<>();
        if (regionDir == null || !Files.isDirectory(regionDir)) return out;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(regionDir, "r.*.mca")) {
            for (Path p : files) {
                Matcher m = ANVIL_FILE.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                // Zero-length files are pre-allocated stubs with no chunks
                if (Files.size(p) <= 0L) continue;
                out.add(new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
            }
        }
        return out;
    }
}
