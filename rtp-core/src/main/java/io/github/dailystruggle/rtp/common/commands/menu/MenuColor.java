package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.mapsapi.BiomeColorSource;

import java.util.Map;

/**
 * Maps a biome name to a legacy Minecraft color code for menu rows (ADR-063).
 *
 * <p>The biome's 24-bit RGB comes from {@code maps-api}'s
 * {@link BiomeColorSource#resolve(String)} - the server's real vanilla map
 * color where known, a deterministic categorical hash for unknown / datapack /
 * custom-generator biomes. That RGB is then matched to the nearest of a
 * curated set of <em>dark</em> legacy color codes so the row stays readable on
 * the parchment-yellow Book menu background (see the "Book Menu Color
 * Contrast" rule in {@code .junie/AGENTS.md}): yellow / white / gold are
 * excluded, and bright colors are darkened before matching. The result is a
 * single {@code &x} prefix usable by every renderer (book and chat).
 */
public final class MenuColor {

    private MenuColor() {}

    /** Dark, parchment-safe legacy codes and their representative RGB. */
    private static final char[] CODES = {'1', '2', '3', '4', '5', '8', '0'};
    private static final int[] CODE_RGB = {
            0x0000AA, // &1 dark blue
            0x00AA00, // &2 dark green
            0x00AAAA, // &3 dark aqua
            0xAA0000, // &4 dark red
            0xAA00AA, // &5 dark purple
            0x555555, // &8 dark gray
            0x000000, // &0 black
    };

    /**
     * Returns the legacy color prefix (e.g. {@code "&2"}) best representing
     * {@code biome} against a parchment background. Never {@code null}.
     */
    public static String biomeColorPrefix(String biome) {
        int rgb = BiomeColorSource.resolve(biome) & 0xFFFFFF;
        return "&" + nearestDarkCode(rgb);
    }

    /**
     * Returns the legacy color prefix best representing a world, computed as
     * the observation-count-weighted average of the {@link BiomeColorSource}
     * map colors of the biomes observed in that world (ADR-063 follow-up:
     * world rows are colored by the biomes in those worlds). The averaged RGB
     * is run through the same parchment luminance clamp + nearest-dark-code
     * match as {@link #biomeColorPrefix(String)}, so a washed-out (pale on
     * pale parchment) world average is darkened to a readable dark code rather
     * than emitted raw. Falls back to {@code "&2"} when no biome has been
     * observed in the world yet (cold-data state). Never {@code null}.
     *
     * @param world world name (as returned by {@code RTPWorld.name()})
     */
    public static String worldColorPrefix(String world) {
        return weightedBiomeColorPrefix(BiomeMenuSource.biomeWeightsForWorld(world));
    }

    /**
     * Returns the legacy color prefix best representing a region, computed as
     * the observation-count-weighted average of the {@link BiomeColorSource}
     * map colors of the biomes observed in that region - i.e. the aggregate
     * of the colors that would appear on that region's map. Uses the same
     * parchment luminance clamp + nearest-dark-code match as
     * {@link #biomeColorPrefix(String)} and {@link #worldColorPrefix(String)}.
     * Falls back to {@code "&2"} when no biome has been observed in the region
     * yet (cold-data state). Never {@code null}.
     *
     * @param region region name (as returned by region lookup keys)
     */
    public static String regionColorPrefix(String region) {
        return weightedBiomeColorPrefix(BiomeMenuSource.biomeWeightsForRegion(region));
    }

    /**
     * Shared weighted-average implementation behind {@link #worldColorPrefix}
     * and {@link #regionColorPrefix}: averages the {@link BiomeColorSource}
     * RGB of each biome by its observation count, then clamps + matches to the
     * parchment-safe dark palette. Falls back to {@code "&2"} when the weight
     * map is empty (cold-data state).
     */
    private static String weightedBiomeColorPrefix(Map<String, Long> weights) {
        if (weights == null || weights.isEmpty()) return "&2";

        double sumR = 0, sumG = 0, sumB = 0;
        long total = 0;
        for (Map.Entry<String, Long> e : weights.entrySet()) {
            long w = e.getValue() == null ? 0L : Math.max(0L, e.getValue());
            // Weight of 0 still contributes presence; treat as 1 so a freshly
            // observed biome with no recorded run count is not ignored.
            if (w == 0L) w = 1L;
            int rgb = BiomeColorSource.resolve(e.getKey()) & 0xFFFFFF;
            sumR += ((rgb >> 16) & 0xFF) * (double) w;
            sumG += ((rgb >> 8) & 0xFF) * (double) w;
            sumB += (rgb & 0xFF) * (double) w;
            total += w;
        }
        if (total <= 0) return "&2";
        int r = (int) Math.round(sumR / total);
        int g = (int) Math.round(sumG / total);
        int b = (int) Math.round(sumB / total);
        return "&" + nearestDarkCode((r << 16) | (g << 8) | b);
    }

    private static char nearestDarkCode(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        // Luminance clamp: darken bright colors so they remain readable on
        // parchment before matching to the dark palette.
        double lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
        if (lum > 0.5) {
            double scale = 0.5 / lum;
            r = (int) Math.round(r * scale);
            g = (int) Math.round(g * scale);
            b = (int) Math.round(b * scale);
        }

        char best = CODES[0];
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < CODES.length; i++) {
            int cr = (CODE_RGB[i] >> 16) & 0xFF;
            int cg = (CODE_RGB[i] >> 8) & 0xFF;
            int cb = CODE_RGB[i] & 0xFF;
            long dr = r - cr, dg = g - cg, db = b - cb;
            long dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = CODES[i];
            }
        }
        return best;
    }
}
