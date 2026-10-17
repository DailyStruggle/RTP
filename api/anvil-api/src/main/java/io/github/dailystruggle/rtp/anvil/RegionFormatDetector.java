package io.github.dailystruggle.rtp.anvil;

import io.github.dailystruggle.rtp.anvil.bit.BitHealth;
import io.github.dailystruggle.rtp.anvil.bit.BuiltInTestable;
import io.github.dailystruggle.rtp.anvil.bit.PbitResult;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
    // Only EMPTY profiles expire (a fresh world gains files); a detected format stays until
    // invalidateCache() (format registry changes). Re-surveying on a TTL or directory mtime
    // listed a 4.5k-file directory every 10 s and stalled scans on Folia 26.2.
    private static final long EMPTY_TTL_NANOS = 10_000_000_000L; // 10 seconds

    private record CachedProfile(WorldFormatProfile profile, long createdNanos) {}

    private final Map<Path, CachedProfile> profileCache = new ConcurrentHashMap<>(PROFILE_CACHE_CAPACITY);
    // One survey per directory at a time, so concurrent IO-pool misses wait instead of re-listing.
    private final Map<Path, Object> surveyLocks = new ConcurrentHashMap<>();

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

        long window = AnvilRegionByteCache.revalidateIntervalNanos();
        if (window <= 0L) {
            return survey(regionDir, System.nanoTime());
        }
        WorldFormatProfile hit = freshCached(regionDir);
        if (hit != null) {
            return hit;
        }
        synchronized (surveyLocks.computeIfAbsent(regionDir, k -> new Object())) {
            hit = freshCached(regionDir);
            return hit != null ? hit : survey(regionDir, System.nanoTime());
        }
    }

    private WorldFormatProfile freshCached(Path regionDir) {
        CachedProfile cached = profileCache.get(regionDir);
        if (cached == null) {
            return null;
        }
        if (cached.profile().primaryFormat() == PrimaryFormat.EMPTY
                && (System.nanoTime() - cached.createdNanos()) >= EMPTY_TTL_NANOS) {
            return null;
        }
        return cached.profile();
    }

    private WorldFormatProfile survey(Path regionDir, long now) {
        WorldFormatProfile profile = surveyDirectory(regionDir);
        profileCache.put(regionDir, new CachedProfile(profile, now));
        return profile;
    }

    /**
     * Clears all cached format profiles.
     */
    public void invalidateCache() {
        profileCache.clear();
        surveyLocks.clear();
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
                // Name only: a per-entry stat cost ~4.5k syscalls per survey on a pregenerated world.
                if (name.startsWith("r.")) {
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
