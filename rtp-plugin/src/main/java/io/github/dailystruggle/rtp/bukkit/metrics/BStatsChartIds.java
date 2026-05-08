package io.github.dailystruggle.rtp.bukkit.metrics;

/**
 * Centralised constants for the bStats chart IDs registered by RTP.
 *
 * <p>Per {@code docs/dev/METRICS_PLAN.md > bStats Integration > Implementation
 * notes}, chart IDs registered on bStats.org must be locked into a single
 * constants class so a typo in one chart-registration site doesn't silently
 * break the chart on the dashboard.
 *
 * <p>The catalogue here is the Phase M1 + Phase M2 *cost-metrics* subset
 * approved 2026-05-07 for the {@code RTP cost metrics on servers} use case.
 * Charts deliberately omitted from this slice: {@code default_strategy_curves},
 * {@code region_shapes_in_use}, {@code safety_features_enabled},
 * {@code addons_loaded}, {@code lite_features_dropped},
 * {@code biome_reroll_distribution_shape}, {@code biome_reroll_rate},
 * {@code s005_violations_recent}, {@code selection_strategy_shape},
 * {@code region_topology}, {@code trigger_sources}. They follow when the
 * underlying {@link io.github.dailystruggle.rtp.common.metrics.MetricsSnapshot}
 * fields land (M1 pending rows in {@code CHECKLIST-metrics-and-multiserver.md}).
 */
public final class BStatsChartIds {

  private BStatsChartIds() {}

  // --- Configuration adoption (SimplePie) ---
  public static final String PLATFORM = "platform";
  public static final String ASSEMBLY_VARIANT = "assembly_variant";
  public static final String DATABASE_BACKEND = "database_backend";

  // --- Runtime cost / health (numeric + bucketised) ---
  public static final String REGION_COUNT = "region_count";
  public static final String CACHE_POOL_HEALTH = "cache_pool_health";
  public static final String TPS_BUCKETS = "tps_buckets";
  public static final String MSPT_BUCKETS = "mspt_buckets";
  public static final String PIPELINE_LATENCY_BUCKETS = "pipeline_latency_buckets";
  public static final String MEMORY_TRACKER_PRESSURE = "memory_tracker_pressure";
  public static final String CHUNK_LOAD_BACKLOG_PRESSURE = "chunk_load_backlog_pressure";
  public static final String QUEUE_DEPTH_PRESSURE = "queue_depth_pressure";
}
