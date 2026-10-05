package io.github.dailystruggle.rtp.common.metrics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-region, per-chunk count of successful teleport landings (ADR-104 §4.6 heatmap layer).
 *
 * <p>In-memory, bounded ({@link #MAX_CHUNKS_PER_REGION} chunks per region; landings in new chunks
 * past the bound are dropped), lost on restart. Each landing bumps the version of its region file
 * (32 x 32 chunks) so the web editor feed rewrites only changed tiles. Thread-safe and allocation
 * light: called from teleport completion threads.
 */
public final class LandingHeatmap {

    static final int MAX_CHUNKS_PER_REGION = 262_144;

    private static final class RegionCounts {
        final Map<Long, AtomicInteger> chunks = new ConcurrentHashMap<>();
        final Map<Long, Long> binVersions = new ConcurrentHashMap<>();
    }

    private static final Map<String, RegionCounts> REGIONS = new ConcurrentHashMap<>();
    private static final AtomicLong VERSION = new AtomicLong();

    private LandingHeatmap() {}

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** Records one successful landing of region {@code region} at block {@code (x, z)}. */
    public static void record(String region, int blockX, int blockZ) {
        if (region == null) return;
        RegionCounts rc = REGIONS.computeIfAbsent(region, k -> new RegionCounts());
        int cx = blockX >> 4;
        int cz = blockZ >> 4;
        long key = chunkKey(cx, cz);
        AtomicInteger n = rc.chunks.get(key);
        if (n == null) {
            if (rc.chunks.size() >= MAX_CHUNKS_PER_REGION) return;
            n = rc.chunks.computeIfAbsent(key, k -> new AtomicInteger());
        }
        n.incrementAndGet();
        rc.binVersions.put(chunkKey(cx >> 5, cz >> 5), VERSION.incrementAndGet());
    }

    /** Landings recorded in chunk {@code (cx, cz)} of {@code region}. */
    public static int count(String region, int cx, int cz) {
        RegionCounts rc = REGIONS.get(region);
        if (rc == null) return 0;
        AtomicInteger n = rc.chunks.get(chunkKey(cx, cz));
        return (n == null) ? 0 : n.get();
    }

    /** Current global version; pass to {@link #changedBins} to get later changes. */
    public static long version() {
        return VERSION.get();
    }

    /** Region-file coordinates {@code [rx, rz]} of {@code region} with landings after {@code sinceVersion}. */
    public static List<int[]> changedBins(String region, long sinceVersion) {
        RegionCounts rc = REGIONS.get(region);
        if (rc == null) return List.of();
        List<int[]> out = new ArrayList<>();
        for (Map.Entry<Long, Long> e : rc.binVersions.entrySet()) {
            if (e.getValue() > sinceVersion) {
                long k = e.getKey();
                out.add(new int[]{(int) (k >> 32), (int) k});
            }
        }
        return out;
    }

    /** Total landings recorded for {@code region}. */
    public static long total(String region) {
        RegionCounts rc = REGIONS.get(region);
        if (rc == null) return 0L;
        long sum = 0;
        for (AtomicInteger n : rc.chunks.values()) sum += n.get();
        return sum;
    }

    static void clear() {
        REGIONS.clear();
    }
}
