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
 * approved 2026-05-07 for the {@code RTP cost metrics on servers} use case,
 * extended 2026-05-17 (checklist row B13) with the four remaining
 * configuration-adoption charts: {@code region_shapes_in_use},
 * {@code safety_features_enabled}, {@code addons_loaded},
 * {@code lite_features_dropped}. Charts still deliberately omitted from this
 * slice: {@code default_strategy_curves},
 * {@code biome_reroll_distribution_shape}, {@code biome_reroll_rate},
 * {@code s005_violations_recent}, {@code selection_strategy_shape},
 * {@code region_topology}, {@code trigger_sources}. They follow when the
 * underlying {@link io.github.dailystruggle.metrics.api.MetricsSnapshot}
 * fields land (Section C/F rows in {@code CHECKLIST-metrics-and-multiserver.md}).
 */
public final class BStatsChartIds {

  private BStatsChartIds() {}

  // --- Configuration adoption (SimplePie / AdvancedPie) ---
  public static final String PLATFORM = "platform";
  public static final String ASSEMBLY_VARIANT = "assembly_variant";
  public static final String DATABASE_BACKEND = "database_backend";
  public static final String REGION_SHAPES_IN_USE = "region_shapes_in_use";
  public static final String SAFETY_FEATURES_ENABLED = "safety_features_enabled";
  public static final String ADDONS_LOADED = "addons_loaded";
  public static final String LITE_FEATURES_DROPPED = "lite_features_dropped";

  // --- Runtime cost / health (numeric + bucketised) ---
  public static final String REGION_COUNT = "region_count";
  public static final String CACHE_POOL_HEALTH = "cache_pool_health";
  public static final String TPS_BUCKETS = "tps_buckets";
  public static final String MSPT_BUCKETS = "mspt_buckets";
  public static final String PIPELINE_LATENCY_BUCKETS = "pipeline_latency_buckets";
  public static final String MEMORY_TRACKER_PRESSURE = "memory_tracker_pressure";
  public static final String CHUNK_LOAD_BACKLOG_PRESSURE = "chunk_load_backlog_pressure";
  public static final String QUEUE_DEPTH_PRESSURE = "queue_depth_pressure";

  // --- M2 Runtime health (added 2026-05-17, Section C / row C4) ---
  // Additive only: existing IDs above are unchanged. These charts consume the
  // same MetricsSnapshot already populated on every platform (Paper, Bukkit,
  // Folia, Fabric), so they "just work" without any per-platform branch.
  // foliaRegionCount is structurally fingerprint-safe — non-Folia platforms
  // bucket into "0" (FoliaRegionSample list is empty); only Folia operators
  // ever produce non-zero buckets.
  public static final String TPS_TRENDLINES = "tps_trendlines";
  public static final String HEAP_PRESSURE = "heap_pressure";
  public static final String TICK_BUDGET_UTILISATION = "tick_budget_utilisation";
  public static final String FOLIA_REGION_COUNT = "folia_region_count";
  public static final String PENDING_TELEPORTS_PRESSURE = "pending_teleports_pressure";
}
