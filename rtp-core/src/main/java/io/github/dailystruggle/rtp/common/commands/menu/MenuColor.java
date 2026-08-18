package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.mapsapi.BiomeColorSource;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;

import java.util.Map;

/**
 * Maps biome names to dark legacy color codes for parchment-safe book menu rendering (ADR-063).
 * Biome RGB from {@link BiomeColorSource} is clamped and matched to the nearest dark code.
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
     * Legacy color prefix for a world, weighted by observed biome colors (ADR-063).
     * Clamped to parchment-safe palette. Defaults to {@code "&2"}.
     *
     * @param world world name
     */
    public static String worldColorPrefix(String world) {
        return weightedBiomeColorPrefix(BiomeMenuSource.biomeWeightsForWorld(world));
    }

    /**
     * Legacy color prefix for a region, weighted by observed biome colors.
     * Clamped to parchment-safe palette. Defaults to {@code "&2"}.
     *
     * @param region region name
     */
    public static String regionColorPrefix(String region) {
        return weightedBiomeColorPrefix(BiomeMenuSource.biomeWeightsForRegion(region));
    }

    /**
     * Legacy color prefix for a world row, colored by its resolved destination region (ADR-065).
     * Defaults to {@code "&2"} if unresolved or cold.
     *
     * @param world world name
     */
    public static String worldRegionColorPrefix(String world) {
        String regionName = "default";
        try {
            ConfigParser<WorldKeys> worldParser =
                    RTP.configs == null ? null : RTP.configs.getWorldParser(world);
            if (worldParser != null) {
                Object r = worldParser.getConfigValue(WorldKeys.region, "default");
                if (r != null && !r.toString().isEmpty()) {
                    regionName = r.toString();
                }
            }
        } catch (RuntimeException ignored) {
            // Cold or mid-reload config state: fall back to the default region,
            // which regionColorPrefix renders as a parchment-safe green.
        }
        return regionColorPrefix(regionName);
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
