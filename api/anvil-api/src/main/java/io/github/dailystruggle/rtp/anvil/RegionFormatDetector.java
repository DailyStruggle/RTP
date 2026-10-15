package io.github.dailystruggle.rtp.anvil;

import io.github.dailystruggle.rtp.anvil.bit.BitHealth;
import io.github.dailystruggle.rtp.anvil.bit.BuiltInTestable;
import io.github.dailystruggle.rtp.anvil.bit.PbitResult;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Surveys region storage directories to detect the primary storage format (.mca vs .linear).
 *
 * <p>Avoids speculative trial-and-error filesystem stat calls across multiple extensions
 * by establishing a {@link WorldFormatProfile} per region directory.</p>
 */
public final class RegionFormatDetector implements BuiltInTestable {

    public static final RegionFormatDetector INSTANCE = new RegionFormatDetector();

    public enum PrimaryFormat {
        MCA(".mca"),
        LINEAR(".linear"),
        EMPTY(".mca");

        private final String defaultExtension;

        PrimaryFormat(String defaultExtension) {
            this.defaultExtension = defaultExtension;
        }

        public String defaultExtension() {
            return defaultExtension;
        }
    }

    public record WorldFormatProfile(PrimaryFormat primaryFormat, boolean hasMixedFormats) {}

    private static final int PROFILE_CACHE_CAPACITY = 128;
    private static final long CACHE_TTL_NANOS = 10_000_000_000L; // 10 seconds

    private record CachedProfile(WorldFormatProfile profile, long createdNanos, FileTime lastModified) {}

    private final Map<Path, CachedProfile> profileCache = new ConcurrentHashMap<>(PROFILE_CACHE_CAPACITY);

    private RegionFormatDetector() {}

    /**
     * Resolves the {@link WorldFormatProfile} for the given region directory.
     *
     * @param regionDir the directory containing region files
     * @return detected format profile
     */
    public WorldFormatProfile detectProfile(Path regionDir) {
        if (regionDir == null) {
            return new WorldFormatProfile(PrimaryFormat.EMPTY, false);
        }

        long now = System.nanoTime();
        long window = AnvilRegionByteCache.revalidateIntervalNanos();
        if (window > 0L) {
            CachedProfile cached = profileCache.get(regionDir);
            if (cached != null && (now - cached.createdNanos()) < CACHE_TTL_NANOS) {
                try {
                    FileTime mtime = Files.getLastModifiedTime(regionDir);
                    if (mtime.equals(cached.lastModified())) {
                        return cached.profile();
                    }
                } catch (IOException ignored) {
                    return cached.profile();
                }
            }
        }

        FileTime mtime = null;
        try {
            if (Files.isDirectory(regionDir)) {
                mtime = Files.getLastModifiedTime(regionDir);
            }
        } catch (IOException ignored) {}

        WorldFormatProfile profile = surveyDirectory(regionDir);
        profileCache.put(regionDir, new CachedProfile(profile, now, mtime));
        return profile;
    }

    /**
     * Clears all cached format profiles.
     */
    public void invalidateCache() {
        profileCache.clear();
    }

    private WorldFormatProfile surveyDirectory(Path regionDir) {
        if (!Files.isDirectory(regionDir)) {
            return new WorldFormatProfile(PrimaryFormat.EMPTY, false);
        }

        int mcaCount = 0;
        int linearCount = 0;
        int otherCount = 0;

        Set<String> extensions = RegionFormatRegistry.getRegisteredExtensions();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.startsWith("r.") && Files.isRegularFile(entry)) {
                    if (name.endsWith(".linear")) {
                        linearCount++;
                    } else if (name.endsWith(".mca")) {
                        mcaCount++;
                    } else {
                        for (String ext : extensions) {
                            if (!".mca".equalsIgnoreCase(ext) && !".linear".equalsIgnoreCase(ext) && name.endsWith(ext)) {
                                otherCount++;
                                break;
                            }
                        }
                    }
                    if ((linearCount > 5 || otherCount > 5) && mcaCount > 5) {
                        break; // Sufficient sample
                    }
                }
            }
        } catch (IOException ignored) {
            return new WorldFormatProfile(PrimaryFormat.EMPTY, false);
        }

        if (linearCount > 0 && mcaCount == 0 && otherCount == 0) {
            return new WorldFormatProfile(PrimaryFormat.LINEAR, false);
        }
        if (mcaCount > 0 && linearCount == 0 && otherCount == 0) {
            return new WorldFormatProfile(PrimaryFormat.MCA, false);
        }
        if ((linearCount > 0 || otherCount > 0) && mcaCount > 0) {
            // Linear/custom servers often retain legacy .mca files after migration
            PrimaryFormat primary = DynamicZstdBinding.isAvailable() ? PrimaryFormat.LINEAR : PrimaryFormat.MCA;
            return new WorldFormatProfile(primary, true);
        }
        if (linearCount > 0 && otherCount > 0) {
            PrimaryFormat primary = DynamicZstdBinding.isAvailable() ? PrimaryFormat.LINEAR : PrimaryFormat.MCA;
            return new WorldFormatProfile(primary, true);
        }

        return new WorldFormatProfile(PrimaryFormat.EMPTY, false);
    }

    @Override
    public PbitResult executePbit() {
        long start = System.nanoTime();
        long duration = System.nanoTime() - start;
        return PbitResult.ok("RegionFormatDetector", duration, "Format detector operational");
    }

    @Override
    public BitHealth getHealthStatus() {
        return BitHealth.HEALTHY;
    }
}
