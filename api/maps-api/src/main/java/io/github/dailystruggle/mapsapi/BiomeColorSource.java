package io.github.dailystruggle.mapsapi;

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
     * Built-in 16-entry categorical fallback table for binding contexts
     * where {@link #ACTIVE} is unset or returns {@code 0}. Stable across
     * calls so the same biome always lands on the same fallback colour.
     *
     * <p>The 16 RGB values are hand-picked to be perceptually distinct at
     * 1-pixel scale even after {@code MapPalette.matchColor} quantisation:
     * saturated primaries plus a few earth tones. Intentionally avoids
     * near-white / near-black so the fallback never collides with the
     * resolver's BLACK "no data" sentinel.
     */
    static int fallback(String biomeName) {
        int idx = Math.floorMod(biomeName.hashCode(), FALLBACK_PALETTE.length);
        return FALLBACK_PALETTE[idx];
    }

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
