package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.rtp.api.maps.ChartSpec;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Process-wide registry mapping each {@link ChartSpec.Kind} to its
 * {@link ChartSpecResolver}. Part of the ADR-047 declarative
 * chart-composition bridge (REQ-RTP-MAP-006).
 *
 * <p>Stage 1 pre-registers exactly one resolver:
 * {@code BadPointsHeatmapResolver} for {@link ChartSpec.Kind#BAD_POINTS_HEATMAP}.
 * Stage 3 adds resolvers for the remaining four {@code Kind} values; Stage 2
 * (menu wiring) does not add new resolvers but starts dispatching through
 * this registry from the {@code OPEN_MAP} menu action.
 *
 * <p>Thread-safety: all mutations happen on the platform main thread during
 * {@code RTP.onEnable} / addon load; reads happen from the dispatcher thread
 * (typically scheduler-async). The underlying {@link EnumMap} is wrapped in
 * a synchronized view so concurrent reads after registration are safe; this
 * matches the {@code RTPHooks} idiom.
 */
public final class ChartSpecResolvers {

  private static final Map<ChartSpec.Kind, ChartSpecResolver> RESOLVERS =
      java.util.Collections.synchronizedMap(new EnumMap<>(ChartSpec.Kind.class));

  static {
    // Stage 1 default resolver. Addons / platform adapters may override via
    // register(BAD_POINTS_HEATMAP, ...) but the default is sufficient for
    // the /rtp info -> "bad points in the region" menu entry.
    RESOLVERS.put(ChartSpec.Kind.BAD_POINTS_HEATMAP, new BadPointsHeatmapResolver());
    // Stage 2 (PR2a): region-shape two-tone map sourced from the same
    // MemoryShape.badKeysSnapshot() data. Drives the admin
    // "Visualizations -> Region shape" entry; menu wiring lands in PR2b.
    RESOLVERS.put(
        ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE, new RegionBadLocationsShapeResolver());
    // Per-region biome map sourced from the same MemoryShape memory caches;
    // mirrors the bad-locations resolver but stamps biome-run palette slots
    // into a location-indexed lookup and reads them back per pixel.
    RESOLVERS.put(ChartSpec.Kind.REGION_BIOMES, new RegionBiomesResolver());
    // Global MSPT + heap sparkline backed by the 1 Hz MetricsSnapshotRing
    // populated by the sampler installed in RTP.start.
    RESOLVERS.put(ChartSpec.Kind.METRIC_SPARKLINE, new MetricSparklineResolver());
  }

  private ChartSpecResolvers() {}

  /**
   * Registers {@code resolver} as the active handler for {@code kind},
   * replacing any prior registration. Returns the displaced resolver (or
   * {@code null} if none was registered). Intended for platform adapters
   * and addons to supply a richer implementation than the rtp-core default.
   */
  public static ChartSpecResolver register(ChartSpec.Kind kind, ChartSpecResolver resolver) {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(resolver, "resolver");
    return RESOLVERS.put(kind, resolver);
  }

  /**
   * Returns the active resolver for {@code kind}, or {@code null} if none is
   * registered. {@link MapDispatch#paint} translates a {@code null} result
   * into the configurable {@code mapResolverMissing} message
   * (REQ-RTP-F-013 / S-004).
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
