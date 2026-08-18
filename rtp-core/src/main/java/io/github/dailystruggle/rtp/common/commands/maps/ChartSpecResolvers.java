package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.rtp.api.maps.ChartSpec;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Process-wide registry mapping each {@link ChartSpec.Kind} to its
 * {@link ChartSpecResolver} (ADR-047, REQ-RTP-MAP-006).
 */
public final class ChartSpecResolvers {

  private static final Map<ChartSpec.Kind, ChartSpecResolver> RESOLVERS =
      java.util.Collections.synchronizedMap(new EnumMap<>(ChartSpec.Kind.class));

  static {
    // Default resolvers.
    RESOLVERS.put(ChartSpec.Kind.BAD_POINTS_HEATMAP, new BadPointsHeatmapResolver());
    RESOLVERS.put(
        ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE, new RegionBadLocationsShapeResolver());
    RESOLVERS.put(ChartSpec.Kind.REGION_BIOMES, new RegionBiomesResolver());
    RESOLVERS.put(ChartSpec.Kind.METRIC_SPARKLINE, new MetricSparklineResolver());
  }

  private ChartSpecResolvers() {}

  /**
   * Registers {@code resolver} as the active handler for {@code kind}.
   *
   * @param kind     the chart kind; never {@code null}
   * @param resolver the resolver; never {@code null}
   * @return the previous resolver, or {@code null}
   */
  public static ChartSpecResolver register(ChartSpec.Kind kind, ChartSpecResolver resolver) {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(resolver, "resolver");
    return RESOLVERS.put(kind, resolver);
  }

  /**
   * Returns active resolver for {@code kind}, or {@code null} if none.
   *
   * @param kind the chart kind; never {@code null}
   * @return the registered resolver, or {@code null}
   */
  public static ChartSpecResolver get(ChartSpec.Kind kind) {
    Objects.requireNonNull(kind, "kind");
    return RESOLVERS.get(kind);
  }

  /** Test-only: removes every registration. Resets the Stage-1 defaults afterwards. */
  static void resetForTest() {
    RESOLVERS.clear();
    RESOLVERS.put(ChartSpec.Kind.BAD_POINTS_HEATMAP, new BadPointsHeatmapResolver());
    RESOLVERS.put(
        ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE, new RegionBadLocationsShapeResolver());
    RESOLVERS.put(ChartSpec.Kind.REGION_BIOMES, new RegionBiomesResolver());
    RESOLVERS.put(ChartSpec.Kind.METRIC_SPARKLINE, new MetricSparklineResolver());
  }
}
