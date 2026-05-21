package io.github.dailystruggle.rtp.api.maps;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Renderer-neutral declarative request for a chart that some {@code MapBinding}
 * implementation will eventually paint.
 *
 * <p>A {@code ChartSpec} is pure data: it carries no {@code maps-api} types, no
 * platform types, and no live state. Producers (admin commands, info menu
 * actions, addons) construct a spec; the {@code rtp-core} dispatch layer hands
 * it to the registered {@code ChartSpecResolver} for the spec's {@link Kind},
 * which materialises a {@code ChartModel} from in-memory state and forwards
 * the result to the active {@code MapBinding}.
 *
 * <p>This split is the load-bearing contract of REQ-RTP-MAP-006: callers do
 * not couple to a renderer, a binding, or a data source. See ADR-047 and
 * {@code docs/dev/scratch/CHECKLIST-metrics-to-maps.md}.
 *
 * @param kind          which family of chart to compose. Never {@code null}.
 * @param regionName    canonical region name used by the resolver to scope its
 *                      data lookup. Never {@code null}; may be empty for
 *                      resolvers that read non-region-scoped state.
 * @param viewer        optional viewer UUID. {@code null} means the chart is
 *                      not per-viewer (e.g. a shared admin diagnostic).
 * @param tilesRows     number of map-item rows in the output mosaic. Must be
 *                      {@code >= 1}. Default single-tile callers pass {@code 1}.
 * @param tilesCols     number of map-item columns in the output mosaic. Must
 *                      be {@code >= 1}.
 * @param metricKey     optional resolver-specific selector (e.g. a metric id
 *                      for sparkline kinds). {@code null} when not applicable.
 * @param windowSeconds optional time window in seconds for time-scoped
 *                      resolvers. {@code 0} means "resolver default";
 *                      negative values are rejected.
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
     * Families of chart that {@code ChartSpec} may request. Only
     * {@link #BAD_POINTS_HEATMAP} has a registered resolver in Stage 1 of
     * {@code CHECKLIST-metrics-to-maps.md}; the other values are reserved
     * placeholders for Stage 3 and dispatching against them currently
     * surfaces the configurable {@code mapResolverMissing} message.
     */
    public enum Kind {
        /** Per-region "bad points" heatmap sourced from {@code MemoryShape.badKeysCache}. */
        BAD_POINTS_HEATMAP,
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
