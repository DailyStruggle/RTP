package io.github.dailystruggle.rtp.anvil;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves region file paths and determines the appropriate {@link RegionFileReader}
 * format for a given coordinate (ADR-077).
 *
 * <p>Supported formats:
 * <ul>
 *   <li>Linear ({@code .linear}): ZStandard-compressed continuous region format used by Leaves, Gale, etc.</li>
 *   <li>Anvil ({@code .mca}): Standard 4 KiB sector-allocated Minecraft region format.</li>
 * </ul>
 */
public final class RegionFileResolver {

    private RegionFileResolver() {}

    /**
     * Target region file description holding its on-disk path and matching reader.
     */
    public record ResolvedRegion(Path path, RegionFileReader reader, boolean isLinear) {}

    /**
     * Resolves the on-disk region file and matching reader for chunk {@code (cx, cz)}.
     * Probes for {@code r.X.Z.linear} first, then {@code r.X.Z.mca}.
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

        Path linearPath = dir.resolve("r." + regionX + "." + regionZ + ".linear");
        if (Files.isRegularFile(linearPath)) {
            return new ResolvedRegion(linearPath, LinearRegionReader.INSTANCE, true);
        }

        Path mcaPath = dir.resolve("r." + regionX + "." + regionZ + ".mca");
        if (Files.isRegularFile(mcaPath)) {
            return new ResolvedRegion(mcaPath, AnvilReader.INSTANCE, false);
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
}
