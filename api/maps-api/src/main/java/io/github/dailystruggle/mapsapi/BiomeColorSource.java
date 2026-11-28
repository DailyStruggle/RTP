package io.github.dailystruggle.mapsapi;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Platform-side hook that resolves a biome name (uppercase, as stored by
 * {@code MemoryShape.addBiomeLocation}) to a 24-bit RGB colour describing
 * how the biome would tint the vanilla cartography map - effectively the
 * server's own answer to "what colour is this biome on a map?".
 *
 * <p>Used by {@code RegionBiomesResolver} (rtp-core) to produce a
 * {@code RegionBiomesRgb} model that {@code RegionBiomesRgbRenderer} then
 * blits onto a {@link MapCanvas} via {@link MapCanvas#setPixelRgb}. The
 * binding takes care of the actual vanilla palette match.
 *
 * <p>Bukkit-family impl (in {@code rtp-plugin}) calls
 * {@code Biome#getMapColor()} (1.21+) via reflection so the maps-api module
 * stays free of platform imports. Fabric / Noop / test bindings can
 * register their own implementation or leave the source unset, in which
 * case the resolver falls back to a built-in categorical palette
 * ({@link #fallback(String)}).
 *
 * <p>Implementations shall be pure functions of {@code biomeName} (and the
 * binding's static state) and shall be safe to call from any thread. No
 * chunk I/O (REQ-RTP-S-005). Returning {@code 0} or any negative value
 * signals "no native colour for this biome" and triggers the categorical
 * fallback in the resolver.
 *
 * @governedBy ADR-053
 * @governedBy ADR-115
 */
@FunctionalInterface
public interface BiomeColorSource {

    /**
     * @param biomeName uppercase biome name (e.g. {@code "OCEAN"},
     *                  {@code "CRIMSON_FOREST"}, {@code "THE_END"})
     * @return 24-bit RGB ({@code 0xRRGGBB}) for the biome, or {@code 0}
     *         to indicate "use the resolver's categorical fallback"
     */
    int rgbFor(String biomeName);

    // --- static holder + categorical fallback ----------------------------

    AtomicReference<BiomeColorSource> ACTIVE = new AtomicReference<>();

    /**
     * Installs the active {@code BiomeColorSource}. Called once by the
     * platform adapter during plugin enable; returns the prior
     * installation (or {@code null}) for restoration in tests.
     */
    static BiomeColorSource install(BiomeColorSource source) {
        return ACTIVE.getAndSet(source);
    }

    /** Resolves a biome's RGB via the active source, with categorical fallback. */
    static int resolve(String biomeName) {
        if (biomeName == null) return fallback("");
        BiomeColorSource src = ACTIVE.get();
        if (src != null) {
            try {
                int v = src.rgbFor(biomeName);
                if (v != 0) return v & 0xFFFFFF;
            } catch (RuntimeException e) {
                // Fall through to categorical fallback on any binding-side
                // exception (e.g. unknown biome on a non-vanilla world).
            }
        }
        return fallback(biomeName);
    }

    /**
     * Resolves a biome name against {@link #CANONICAL_BIOME_RGB} (and terrain
     * family keywords for namespaced/modded biomes) before falling back to
     * the 16-entry {@link #FALLBACK_PALETTE}.
     */
    static int fallback(String biomeName) {
        if (biomeName != null && !biomeName.isBlank()) {
            String raw = biomeName.trim();
            int colon = raw.indexOf(':');
            String clean = (colon >= 0 ? raw.substring(colon + 1) : raw)
                    .trim()
                    .toUpperCase(Locale.ROOT);
            Integer direct = CANONICAL_BIOME_RGB.get(clean);
            if (direct != null) {
                return direct;
            }
            int family = resolveFamilyRgb(clean);
            if (family != 0) {
                return family;
            }
        }
        int idx = Math.floorMod((biomeName == null ? "" : biomeName).hashCode(), FALLBACK_PALETTE.length);
        return FALLBACK_PALETTE[idx];
    }

    private static int resolveFamilyRgb(String upper) {
        for (Map.Entry<String, Integer> entry : FAMILY_KEYWORD_RGB) {
            if (upper.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return 0;
    }

    /** Ordered terrain-family keyword fallbacks for namespaced/modded biomes. */
    List<Map.Entry<String, Integer>> FAMILY_KEYWORD_RGB = List.of(
            Map.entry("CHERRY", 0xE89BB5),
            Map.entry("BAMBOO", 0x6BA82E),
            Map.entry("FROZEN_OCEAN", 0x5A78A8),
            Map.entry("COLD_OCEAN", 0x264B8C),
            Map.entry("LUKEWARM_OCEAN", 0x238996),
            Map.entry("WARM_OCEAN", 0x28B5B5),
            Map.entry("DEEP_OCEAN", 0x16386B),
            Map.entry("OCEAN", 0x1F569E),
            Map.entry("FROZEN_RIVER", 0x7898BD),
            Map.entry("RIVER", 0x356EB8),
            Map.entry("BEACH", 0xDEC887),
            Map.entry("SHORE", 0x7B7F82),
            Map.entry("BADLANDS", 0xC46238),
            Map.entry("MESA", 0xC46238),
            Map.entry("DESERT", 0xE0CC82),
            Map.entry("MANGROVE", 0x4E7A42),
            Map.entry("SWAMP", 0x4C6638),
            Map.entry("MARSH", 0x4C6638),
            Map.entry("ICE", 0x8EC7D8),
            Map.entry("SNOW", 0xD9E6EB),
            Map.entry("FROZEN", 0xD9E6EB),
            Map.entry("TAIGA", 0x3D6E52),
            Map.entry("SPRUCE", 0x3D6E52),
            Map.entry("PINE", 0x4A6342),
            Map.entry("JUNGLE", 0x388E2C),
            Map.entry("DARK_FOREST", 0x345428),
            Map.entry("BIRCH", 0x689C4E),
            Map.entry("FLOWER", 0x62A84B),
            Map.entry("FOREST", 0x478238),
            Map.entry("GROVE", 0x6E8A7D),
            Map.entry("SAVANNA", 0x9E9644),
            Map.entry("PEAK", 0x848B8E),
            Map.entry("MOUNTAIN", 0x848B8E),
            Map.entry("HILL", 0x788278),
            Map.entry("CRIMSON", 0x9E222A),
            Map.entry("WARPED", 0x1E7874),
            Map.entry("SOUL", 0x4E3E36),
            Map.entry("BASALT", 0x58545C),
            Map.entry("NETHER", 0x7A2929),
            Map.entry("END", 0xC8C48B),
            Map.entry("LUSH", 0x5C9E48),
            Map.entry("CAVE", 0x755E4E),
            Map.entry("DEEP_DARK", 0x12262E),
            Map.entry("MUSHROOM", 0x8A5C82),
            Map.entry("PLAIN", 0x6DA346),
            Map.entry("MEADOW", 0x5D9C56)
    );

    /** Canonical Minecraft cartography RGB colours for standard Overworld, Nether, and End biomes. */
    Map<String, Integer> CANONICAL_BIOME_RGB = Map.<String, Integer>ofEntries(
            Map.entry("PLAINS", 0x6DA346),
            Map.entry("SUNFLOWER_PLAINS", 0x85B548),
            Map.entry("MEADOW", 0x5D9C56),
            Map.entry("CHERRY_GROVE", 0xE89BB5),
            Map.entry("FOREST", 0x478238),
            Map.entry("FLOWER_FOREST", 0x62A84B),
            Map.entry("BIRCH_FOREST", 0x689C4E),
            Map.entry("OLD_GROWTH_BIRCH_FOREST", 0x5E9146),
            Map.entry("DARK_FOREST", 0x345428),
            Map.entry("PALE_GARDEN", 0x7C857A),
            Map.entry("TAIGA", 0x3D6E52),
            Map.entry("SNOWY_TAIGA", 0x527868),
            Map.entry("OLD_GROWTH_PINE_TAIGA", 0x4A6342),
            Map.entry("OLD_GROWTH_SPRUCE_TAIGA", 0x3E593C),
            Map.entry("GROVE", 0x6E8A7D),
            Map.entry("JUNGLE", 0x388E2C),
            Map.entry("SPARSE_JUNGLE", 0x4E9938),
            Map.entry("BAMBOO_JUNGLE", 0x6BA82E),
            Map.entry("SWAMP", 0x4C6638),
            Map.entry("MANGROVE_SWAMP", 0x4E7A42),
            Map.entry("MUSHROOM_FIELDS", 0x8A5C82),
            Map.entry("DESERT", 0xE0CC82),
            Map.entry("SAVANNA", 0x9E9644),
            Map.entry("SAVANNA_PLATEAU", 0x8E873E),
            Map.entry("WINDSWEPT_SAVANNA", 0x968E49),
            Map.entry("BADLANDS", 0xC46238),
            Map.entry("ERODED_BADLANDS", 0xD46E42),
            Map.entry("WOODED_BADLANDS", 0x9E5D38),
            Map.entry("WINDSWEPT_HILLS", 0x788278),
            Map.entry("WINDSWEPT_GRAVELLY_HILLS", 0x858885),
            Map.entry("WINDSWEPT_FOREST", 0x56734E),
            Map.entry("STONY_PEAKS", 0x949896),
            Map.entry("JAGGED_PEAKS", 0xB8C2C8),
            Map.entry("FROZEN_PEAKS", 0xA8C2D1),
            Map.entry("SNOWY_SLOPES", 0xC4D6E0),
            Map.entry("SNOWY_PLAINS", 0xD9E6EB),
            Map.entry("ICE_SPIKES", 0x8EC7D8),
            Map.entry("OCEAN", 0x1F569E),
            Map.entry("DEEP_OCEAN", 0x16386B),
            Map.entry("WARM_OCEAN", 0x28B5B5),
            Map.entry("LUKEWARM_OCEAN", 0x238996),
            Map.entry("DEEP_LUKEWARM_OCEAN", 0x1B6778),
            Map.entry("COLD_OCEAN", 0x264B8C),
            Map.entry("DEEP_COLD_OCEAN", 0x1A3466),
            Map.entry("FROZEN_OCEAN", 0x5A78A8),
            Map.entry("DEEP_FROZEN_OCEAN", 0x3D5580),
            Map.entry("RIVER", 0x356EB8),
            Map.entry("FROZEN_RIVER", 0x7898BD),
            Map.entry("BEACH", 0xDEC887),
            Map.entry("SNOWY_BEACH", 0xDCE3DC),
            Map.entry("STONY_SHORE", 0x7B7F82),
            Map.entry("DRIPSTONE_CAVES", 0x755E4E),
            Map.entry("LUSH_CAVES", 0x5C9E48),
            Map.entry("DEEP_DARK", 0x12262E),
            Map.entry("NETHER_WASTES", 0x7A2929),
            Map.entry("CRIMSON_FOREST", 0x9E222A),
            Map.entry("WARPED_FOREST", 0x1E7874),
            Map.entry("SOUL_SAND_VALLEY", 0x4E3E36),
            Map.entry("BASALT_DELTAS", 0x58545C),
            Map.entry("THE_END", 0xC8C48B),
            Map.entry("END_HIGHLANDS", 0xD4CF94),
            Map.entry("END_MIDLANDS", 0xBEB982),
            Map.entry("SMALL_END_ISLANDS", 0xABA674),
            Map.entry("END_BARRENS", 0x9C9768)
    );

    /** Categorical fallback palette (16 perceptually-distinct colours). */
    int[] FALLBACK_PALETTE = new int[] {
            0x2E7D32, // forest green
            0xC62828, // crimson red
            0x1565C0, // ocean blue
            0xF9A825, // amber / desert yellow
            0x6A1B9A, // royal purple
            0x00838F, // teal / cyan
            0xEF6C00, // orange / mesa
            0x4E342E, // dark brown / dirt
            0xAD1457, // magenta / pink
            0x558B2F, // olive / swamp green
            0x283593, // deep navy
            0x00695C, // dark teal / jungle
            0xBF360C, // burnt orange / badlands
            0x827717, // mustard / savanna
            0x1B5E20, // dark forest
            0x4527A0, // indigo / mushroom
    };
}
