package io.github.dailystruggle.rtp.anvil;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves region file paths and determines the appropriate {@link RegionFileReader}
 * format for a given coordinate (ADR-077).
 *
 * <p>Supports registered format extensions from {@link RegionFormatRegistry}, including the
 * built-in Linear ({@code .linear} / ZSTD) format as well as standard Anvil ({@code .mca}).</p>
 */
public final class RegionFileResolver {

    private RegionFileResolver() {}

    /**
     * Target region file description holding its on-disk path, matching reader, and format metadata.
     */
    public record ResolvedRegion(Path path, RegionFileReader reader, boolean isLinear) {}

    /**
     * Resolves the on-disk region file and matching reader for chunk {@code (cx, cz)}.
     * Probes registered non-Anvil formats (e.g. {@code .linear}) first, then {@code .mca}.
     *
     * @param worldFolder      the root world directory
     * @param dimensionSubpath dimension subdirectory (e.g., {@code "DIM-1"}, {@code "DIM1"}, or {@code ""})
     * @param cx               chunk X coordinate
     * @param cz               chunk Z coordinate
     * @return {@link ResolvedRegion} with the existing file path, or the default {@code .mca} path if neither exists on disk
     */
    public static ResolvedRegion resolve(Path worldFolder, String dimensionSubpath, int cx, int cz) {
        int regionX = cx >> 5;
        int regionZ = cz >> 5;
        Path dir = regionDirectoryFor(worldFolder, dimensionSubpath);
        String baseName = "r." + regionX + "." + regionZ;

        // Check registered non-default formats first (e.g. .linear)
        Set<String> extensions = RegionFormatRegistry.getRegisteredExtensions();
        for (String ext : extensions) {
            if (".mca".equalsIgnoreCase(ext)) continue;
            Path customPath = dir.resolve(baseName + ext);
            if (Files.isRegularFile(customPath)) {
                RegionFileReader reader = RegionFormatRegistry.getReader(ext);
                if (reader != null) {
                    boolean isLinear = ".linear".equalsIgnoreCase(ext);
                    return new ResolvedRegion(customPath, reader, isLinear);
                }
            }
        }

        // Check standard .mca
        Path mcaPath = dir.resolve(baseName + ".mca");
        if (Files.isRegularFile(mcaPath)) {
            return new ResolvedRegion(mcaPath, AnvilReader.INSTANCE, false);
        }

        // Check any remaining registered formats on disk in case .linear exists even if not registered yet
        Path linearFallback = dir.resolve(baseName + ".linear");
        if (Files.isRegularFile(linearFallback) && RegionFormatRegistry.isRegistered(".linear")) {
            return new ResolvedRegion(linearFallback, RegionFormatRegistry.getReader(".linear"), true);
        }

        // Neither exists on disk - default to .mca path and Anvil reader
        return new ResolvedRegion(mcaPath, AnvilReader.INSTANCE, false);
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
