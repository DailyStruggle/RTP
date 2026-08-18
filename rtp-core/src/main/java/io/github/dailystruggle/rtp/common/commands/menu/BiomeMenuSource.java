package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.BiomeNames;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Biome-first menu source (ADR-063).
 *
 * <p>Computes accessible biomes and optimal target regions from per-region
 * {@code MemoryShape.getObservedBiomes()} spatial memory. Chunk-I/O-free (REQ-RTP-S-005).
 */
public final class BiomeMenuSource {

    private BiomeMenuSource() {}

    /**
     * Returns union of canonical biome identifiers observed across accessible regions.
     *
     * @param senderId command sender id (or {@code null} for console)
     * @return sorted set of canonical biome names
     */
    public static Set<String> observedBiomes(UUID senderId) {
        return new TreeSet<>(bestRegionByBiome(senderId).keySet());
    }

    /**
     * Maps each accessible observed biome to the region offering the highest observation count.
     *
     * @param senderId command sender id (or {@code null} for console)
     * @return sorted map of canonical biome name to best region name
     */
    public static Map<String, String> bestRegionByBiome(UUID senderId) {
        Map<String, String> best = new TreeMap<>();
        Map<String, Long> bestCount = new TreeMap<>();
        if (RTP.selectionAPI == null) return best;

        RTPPlayer player = null;
        if (senderId != null && RTP.serverAccessor != null) {
            try {
                player = RTP.serverAccessor.getPlayer(senderId);
            } catch (RuntimeException ignored) {
                // degrade to no-player (console-like) view
            }
        }

        for (Map.Entry<String, Region> e : RTP.selectionAPI.permRegionLookup.entrySet()) {
            String regionName = e.getKey();
            Region region = e.getValue();
            if (regionName == null || region == null) continue;

            if (!isAccessible(region, regionName, player)) continue;
            if (!(region.shape instanceof MemoryShape<?> memoryShape)) continue;

            Set<String> observed;
            try {
                observed = memoryShape.getObservedBiomes();
            } catch (RuntimeException ignored) {
                continue;
            }
            if (observed == null) continue;

            for (String biome : observed) {
                if (biome == null) continue;
                long count = observationCount(memoryShape, biome);
                Long prev = bestCount.get(biome);
                if (prev == null || count > prev
                        || (count == prev && regionName.compareTo(best.get(biome)) < 0)) {
                    best.put(biome, regionName);
                    bestCount.put(biome, count);
                }
            }
        }
        return best;
    }

    /**
     * Resolves the best accessible region offering {@code biome}, checking {@code preferredRegion} first.
     *
     * @param senderId        command sender id (or {@code null})
     * @param biome           requested biome
     * @param preferredRegion region to keep if it already contains the biome, or {@code null}
     * @return region name, or {@code null} if unobserved in accessible regions
     */
    public static String bestRegionForBiome(UUID senderId, String biome, String preferredRegion) {
        if (biome == null) return null;
        if (preferredRegion != null && regionObservesBiome(preferredRegion, biome)) {
            return preferredRegion;
        }
        String canonical = BiomeNames.canonical(biome);
        return bestRegionByBiome(senderId).get(canonical);
    }

    /**
     * Whether {@code regionName} has any recorded observation of {@code biome}.
     * Chunk-I/O-free; reads only persisted spatial memory.
     *
     * @param regionName the region to check; {@code null} returns {@code false}
     * @param biome      the biome name to look up; {@code null} returns {@code false}
     * @return {@code true} if the region has observed the biome at least once
     */
    public static boolean regionObservesBiome(String regionName, String biome) {
        if (regionName == null || biome == null || RTP.selectionAPI == null) return false;
        Region region = RTP.selectionAPI.getRegion(regionName);
        if (region == null || !(region.shape instanceof MemoryShape<?> memoryShape)) return false;
        try {
            long[] sums = memoryShape.getBiomePrefixSums(biome);
            return sums != null && sums.length > 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Aggregates per-biome observation counts across regions matching {@code worldName}.
     *
     * @param worldName world name (from {@code RTPWorld.name()})
     * @return map of canonical biome name to total observation count
     */
    public static Map<String, Long> biomeWeightsForWorld(String worldName) {
        Map<String, Long> weights = new TreeMap<>();
        if (worldName == null || RTP.selectionAPI == null) return weights;

        for (Region region : RTP.selectionAPI.permRegionLookup.values()) {
            if (region == null) continue;
            if (!(region.shape instanceof MemoryShape<?> memoryShape)) continue;

            String regionWorld;
            try {
                regionWorld = region.getWorld() == null ? null : region.getWorld().name();
            } catch (RuntimeException ignored) {
                continue;
            }
            if (regionWorld == null || !worldName.equals(regionWorld)) continue;

            Set<String> observed;
            try {
                observed = memoryShape.getObservedBiomes();
            } catch (RuntimeException ignored) {
                continue;
            }
            if (observed == null) continue;

            for (String biome : observed) {
                if (biome == null) continue;
                long count = observationCount(memoryShape, biome);
                weights.merge(biome, count, Long::sum);
            }
        }
        return weights;
    }

    /**
     * Computes per-biome observation counts for a single region.
     *
     * @param regionName region name
     * @return map of canonical biome name to total observation count
     */
    public static Map<String, Long> biomeWeightsForRegion(String regionName) {
        Map<String, Long> weights = new TreeMap<>();
        if (regionName == null || RTP.selectionAPI == null) return weights;

        Region region = RTP.selectionAPI.getRegion(regionName);
        if (region == null || !(region.shape instanceof MemoryShape<?> memoryShape)) {
            return weights;
        }

        Set<String> observed;
        try {
            observed = memoryShape.getObservedBiomes();
        } catch (RuntimeException ignored) {
            return weights;
        }
        if (observed == null) return weights;

        for (String biome : observed) {
            if (biome == null) continue;
            weights.merge(biome, observationCount(memoryShape, biome), Long::sum);
        }
        return weights;
    }

    private static boolean isAccessible(Region region, String regionName, RTPPlayer player) {
        try {
            if (region.getSettings().requirePermission()) {
                return player != null && player.hasPermission("rtp.regions." + regionName);
            }
        } catch (RuntimeException ignored) {
            // a flaky settings read should not crash suggestion building
        }
        return true;
    }

    private static long observationCount(MemoryShape<?> memoryShape, String biome) {
        try {
            long[] sums = memoryShape.getBiomePrefixSums(biome);
            if (sums != null && sums.length > 0) return sums[sums.length - 1];
        } catch (RuntimeException ignored) {
            // fall through
        }
        return 0L;
    }
}
