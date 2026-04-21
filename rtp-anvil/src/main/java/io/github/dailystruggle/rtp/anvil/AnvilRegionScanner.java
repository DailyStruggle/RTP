package io.github.dailystruggle.rtp.anvil;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pregen-scan utility that walks every {@code r.X.Z.mca} file under a dimension's
 * {@code region/} folder and returns the union of every namespaced biome identifier
 * it can decode (ADR-016 (biome) §6.1, Step 2 of §10).
 *
 * <p>Intended consumer: the platform adapter's {@code setBiomesGetter} hook (Step 3),
 * which uses the returned set as the "populated" half of a tab-completion enumerator.
 * Worlds whose pregen is complete get a complete roster; partial pregen yields the
 * subset that has actually been written to disk (addons that can enumerate from a
 * generator engine — e.g. {@code RTP_Iris_integration} — remain the authoritative
 * source in that case, see ADR-016 (biome) §7).
 *
 * <h2>Threading</h2>
 * <ul>
 *   <li>{@link #scanBiomesAsync(Path, String)} dispatches on {@link ForkJoinPool#commonPool()}
 *       and never touches the calling thread. This is the intended entry point.</li>
 *   <li>{@link #scanBiomes(Path, String)} runs inline. Callers from a Bukkit tick
 *       thread must ensure the call is already off-tick — the platform wiring in
 *       Step 3 will always go through {@code scanBiomesAsync}.</li>
 * </ul>
 *
 * <h2>Caching</h2>
 * <p>Results are memoised per {@code (regionFolder, mtimeSignature)} pair. The mtime
 * signature is the maximum {@link Files#getLastModifiedTime(Path, java.nio.file.LinkOption...) lastModified}
 * across every {@code r.*.*.mca} file in the folder. If any region file is added,
 * removed, or modified, the signature changes and the scan re-runs on the next call;
 * otherwise the cache hit returns in O(directory-listing) time, which is adequate for
 * startup-scan-plus-tab-completion usage.
 *
 * <h2>Failure mode</h2>
 * <p>Per ADR-016 (biome) §8 and ADR-016's "malformed → UNKNOWN, never crash" posture,
 * any individual chunk or file decode failure is silently skipped. The scanner returns
 * only the biome identifiers it could successfully decode; it never throws on bad data.
 * A missing region folder yields an empty set, not an exception (an unpopulated world
 * legitimately has no region files).
 */
public final class AnvilRegionScanner {

    /** Matches {@code r.<X>.<Z>.mca} with optionally-signed integer X/Z coordinates. */
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    /**
     * Process-wide cache. Keyed by absolute region-folder path; value holds the
     * mtime signature observed at scan time plus the union-of-biomes result.
     * {@link ConcurrentHashMap} so concurrent tab-completion requests on different
     * worlds don't block each other.
     */
    private static final ConcurrentHashMap<Path, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private AnvilRegionScanner() {}

    /**
     * Scan the dimension rooted at {@code worldFolder/dimensionSubpath/region/} on
     * {@link ForkJoinPool#commonPool()} and return the union of every decodable
     * namespaced biome identifier. See the class Javadoc for caching and failure
     * semantics.
     *
     * @param worldFolder      the world folder (e.g. {@code <server>/world})
     * @param dimensionSubpath {@code ""} for overworld, {@code "DIM-1"} for nether,
     *                         {@code "DIM1"} for end, or any datapack-dimension subpath
     * @return a future that completes with the insertion-ordered union of biome ids
     */
    public static CompletableFuture<Set<String>> scanBiomesAsync(Path worldFolder, String dimensionSubpath) {
        return CompletableFuture.supplyAsync(() -> scanBiomes(worldFolder, dimensionSubpath),
                ForkJoinPool.commonPool());
    }

    /**
     * Synchronous scan. Prefer {@link #scanBiomesAsync(Path, String)} from any
     * thread that may be tick-affine; this variant exists for tests and for callers
     * that have already dispatched off-tick.
     */
    public static Set<String> scanBiomes(Path worldFolder, String dimensionSubpath) {
        Path regionFolder = resolveRegionFolder(worldFolder, dimensionSubpath);
        if (regionFolder == null || !Files.isDirectory(regionFolder)) {
            return Collections.emptySet();
        }
        Path key;
        try {
            key = regionFolder.toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            key = regionFolder;
        }

        long signature = mtimeSignature(regionFolder);
        CacheEntry cached = CACHE.get(key);
        if (cached != null && cached.signature == signature) {
            return cached.biomes;
        }

        Set<String> biomes = scanUncached(regionFolder);
        CACHE.put(key, new CacheEntry(signature, biomes));
        return biomes;
    }

    /**
     * Evicts every cached result. Intended for tests and for admin commands that
     * force a re-scan (e.g. after manual pregen). Production code should rely on
     * mtime invalidation rather than calling this directly.
     */
    public static void invalidateCache() {
        CACHE.clear();
    }

    /** Visible for tests — the current cache size. */
    static int cacheSize() {
        return CACHE.size();
    }

    // ------------------------------------------------------------------ internals

    private static Path resolveRegionFolder(Path worldFolder, String dimensionSubpath) {
        if (worldFolder == null) return null;
        if (dimensionSubpath == null || dimensionSubpath.isEmpty()) {
            return worldFolder.resolve("region");
        }
        return worldFolder.resolve(dimensionSubpath).resolve("region");
    }

    /**
     * Maximum {@link Files#getLastModifiedTime(Path, java.nio.file.LinkOption...) lastModified}
     * across every region file in the folder. Returns {@code 0} when the folder has
     * no region files or cannot be listed — in which case {@link #scanBiomes} will
     * also return an empty set, so the 0-signature cache entry is harmless.
     */
    private static long mtimeSignature(Path regionFolder) {
        long max = 0L;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionFolder, "r.*.*.mca")) {
            for (Path p : ds) {
                Matcher m = REGION_FILE.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                try {
                    long t = Files.getLastModifiedTime(p).toMillis();
                    if (t > max) max = t;
                } catch (IOException ignored) {
                    // Skip unreadable files — mtime signature will still be stable
                    // across calls because the same files fail the same way.
                }
            }
        } catch (IOException ignored) {
            return 0L;
        }
        return max;
    }

    private static Set<String> scanUncached(Path regionFolder) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionFolder, "r.*.*.mca")) {
            for (Path p : ds) {
                Matcher m = REGION_FILE.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                scanRegionFile(p, out);
            }
        } catch (IOException ignored) {
            // Directory unreadable — return what we have (possibly empty). Matches
            // the "never crash" posture: callers get the same empty-set fallthrough
            // as on an unpopulated world.
        }
        return out.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(out);
    }

    private static void scanRegionFile(Path regionFile, Set<String> out) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(regionFile);
        } catch (IOException ignored) {
            return;
        }
        // Walk all 1024 chunk slots; absent chunks return null from readChunkView.
        for (int cz = 0; cz < 32; cz++) {
            for (int cx = 0; cx < 32; cx++) {
                AnvilChunkView view;
                try {
                    view = AnvilReader.readChunkView(bytes, cx, cz);
                } catch (IOException | RuntimeException ignored) {
                    continue;
                }
                if (view == null) continue;
                out.addAll(view.getBiomesPresent());
            }
        }
    }

    /** Cache value: scan result + the mtime signature under which it was observed. */
    private static final class CacheEntry {
        final long signature;
        final Set<String> biomes;

        CacheEntry(long signature, Set<String> biomes) {
            this.signature = signature;
            this.biomes = biomes;
        }
    }
}
