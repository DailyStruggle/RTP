package io.github.dailystruggle.rtp.api.maps;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Renderer-neutral declarative request for a chart (REQ-RTP-MAP-006, ADR-047).
 * Pure data record forwarded to registered {@code ChartSpecResolver} for the spec's {@link Kind}.
 *
 * @param kind          chart family (never null)
 * @param regionName    canonical region name for scoping data lookup (never null)
 * @param viewer        optional viewer UUID (null for shared/non-viewer)
 * @param tilesRows     number of map-item rows (&gt;= 1)
 * @param tilesCols     number of map-item columns (&gt;= 1)
 * @param metricKey     optional resolver selector (null if not applicable)
 * @param windowSeconds optional time window in seconds (0 for default)
 */
public record ChartSpec(
        Kind kind,
        String regionName,
        @Nullable UUID viewer,
        int tilesRows,
        int tilesCols,
        @Nullable String metricKey,
        int windowSeconds) {

    public ChartSpec {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(regionName, "regionName");
        if (tilesRows < 1) {
            throw new IllegalArgumentException("tilesRows must be >= 1, was " + tilesRows);
        }
        if (tilesCols < 1) {
            throw new IllegalArgumentException("tilesCols must be >= 1, was " + tilesCols);
        }
        if (windowSeconds < 0) {
            throw new IllegalArgumentException("windowSeconds must be >= 0, was " + windowSeconds);
        }
    }

    /**
     * Convenience constructor for the common single-tile, no-viewer,
     * no-metric, no-window case.
     */
    public static ChartSpec of(Kind kind, String regionName) {
        return new ChartSpec(kind, regionName, null, 1, 1, null, 0);
    }

    /**
     * Families of chart that {@code ChartSpec} may request.
     */
    public enum Kind {
        /** Per-region "bad points" heatmap sourced from {@code MemoryShape.badKeysCache}. */
        BAD_POINTS_HEATMAP,
        /**
         * Per-region two-tone shape map: green inside the region disk, red
         * for each bad-flagged location, black for outside the disk. Sourced
         * from {@code MemoryShape.badKeysSnapshot()}. Drives the admin
         * "Visualizations -> Region shape" entry; see
         * {@code maps-api} {@code RegionBadLocations} +
         * {@code RegionBadLocationsRenderer}.
         */
        REGION_BAD_LOCATIONS_SHAPE,
        /**
         * Per-region biome map sourced from {@code MemoryShape.biomeKeysCache}.
         * Drives {@code /rtp visualization biomes region=<name>}.
         */
        REGION_BIOMES,
        /** Reserved (Stage 3): spiral coverage view of a region. */
        REGION_COVERAGE,
        /** Reserved (Stage 3): per-region pipeline failure-rate heatmap. */
        FAIL_RATE_HEATMAP,
        /** Reserved (Stage 3): L1 / L2 / L3 cache occupancy categories. */
        CACHE_OCCUPANCY,
        /** Reserved (Stage 3): TPS / MSPT / pipeline-latency sparkline. */
        METRIC_SPARKLINE
    }
}
