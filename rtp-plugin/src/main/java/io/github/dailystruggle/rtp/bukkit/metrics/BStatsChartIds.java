package io.github.dailystruggle.rtp.bukkit.metrics;

/**
 * Centralised constants for the bStats chart IDs registered by RTP.
 *
 * <p>Per {@code docs/dev/METRICS_PLAN.md > bStats Integration > Implementation
 * notes}, chart IDs registered on bStats.org must be locked into a single
 * constants class so a typo in one chart-registration site doesn't silently
 * break the chart on the dashboard.
 *
 * <p>The catalogue here is the *cost-metrics* subset
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

  // --- Active locale / language selection (SimplePie) ---
  // Reports which shipped locale the server has selected in language.yml
  // (en / de / es / fr / ...). Cardinality is bounded by the fixed shipped-
  // locale whitelist in RTPCostMetricsCharts#KNOWN_LOCALES; any other value
  // collapses to "other" and an unresolved value to "unknown", so an admin
  // can't turn this into a fingerprint vector with a custom locale name.
  public static final String LANGUAGE_SELECTION = "language_selection";

  // --- Runtime cost / health (numeric + bucketised) ---
  public static final String REGION_COUNT = "region_count";
  public static final String CACHE_POOL_HEALTH = "cache_pool_health";
  public static final String TPS_BUCKETS = "tps_buckets";
  public static final String MSPT_BUCKETS = "mspt_buckets";
  public static final String MEMORY_TRACKER_PRESSURE = "memory_tracker_pressure";
  public static final String CHUNK_LOAD_BACKLOG_PRESSURE = "chunk_load_backlog_pressure";
  public static final String QUEUE_DEPTH_PRESSURE = "queue_depth_pressure";

  // --- Players per server (bucketised online player count) ---
  // AdvancedPie reporting the bucketised count of online players on the host
  // server. Bucketised (never a raw player count) so a long-lived or uniquely-
  // sized server cannot be fingerprinted by an exact population figure; the
  // fleet dashboard still gets a population-size distribution.
  public static final String PLAYER_COUNT_BUCKETS = "player_count_buckets";

  // --- RTP scheduler cost per RTP (sync/region vs async) ---
  // Two AdvancedPies reporting the bucketised wall-clock cost RTP's own
  // scheduler spends per RTP served, split by submission family: the
  // main-thread / region tasks ("sync") and the asynchronous tasks ("async").
  // Driven by RtpSchedulerProfile (which only ever times RTP-submitted work, so
  // it is RTP-attributable cost, unlike whole-server MSPT) over a rolling
  // ~30-minute window in 1-minute delta buckets. Bucketised (never a raw timing
  // or RTP count) so the per-server cost can't be fingerprinted.
  public static final String RTP_SYNC_COST_PER_RTP = "rtp_sync_cost_per_rtp";
  public static final String RTP_ASYNC_COST_PER_RTP = "rtp_async_cost_per_rtp";

  // --- Chunk-load floor (smallest single-chunk load time), by chunk-load mode ---
  // DrilldownPie: outer slice = how this backend loads chunks (sync / async,
  // assumed from the detected platform rather than runtime-probed), inner slice
  // = the bucketised smallest single-chunk live-load wall-clock time ever
  // observed on this server. We never see exactly when the platform begins a
  // load after it is requested, so the running minimum is taken as the assumed
  // shortest time to start and complete a load (the "floor"). Driven by
  // ChunkLoadProfile, which only times genuine live loads (not anvil-prefilter
  // short-circuits or already-resident chunks). Both levels are categorical /
  // bucketised, so no raw timing is ever emitted.
  public static final String CHUNK_LOAD_FLOOR_MS = "chunk_load_floor_ms";

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

  // --- Aggregate outcome metrics (process-global RtpOutcomeStats.GLOBAL) ---
  // Driven by the always-on location-generation outcome accumulator
  // (RtpOutcomeStats), independent of per-invocation verbose flags. Both charts
  // are categorical / bucketised and therefore fingerprint-safe: the success
  // rate is bucketised (never a raw cumulative count) and the dominant failure
  // cause is drawn from the fixed LocationGenerator.FailTypes catalogue.
  public static final String AGGREGATE_SUCCESS_RATE = "aggregate_success_rate";
  public static final String AGGREGATE_TOP_FAILURE_CAUSE = "aggregate_top_failure_cause";

  // --- RTP-correlated MSPT p99 cost, correlated to platform ---
  // DrilldownPie: outer slice = server platform (folia / paper / spigot / ...),
  // inner slice = bucketised p99 of the host server MSPT (milliseconds per
  // tick), computed from the preexisting 1 Hz MetricsSnapshotRing populated by
  // the CoreMetrics sampler. This surfaces the real per-tick cost (not just the
  // location-generation pipeline latency) and lets the fleet dashboard
  // correlate that tail cost with the host platform. Both levels are
  // categorical / bucketised, so no raw MSPT scalar is ever emitted.
  public static final String MSPT_P99_BY_PLATFORM = "mspt_p99_by_platform";

  // --- RTP-correlated MSPT p99 cost, correlated to game / plugin version ---
  // Two DrilldownPie companions to MSPT_P99_BY_PLATFORM: the outer slice is
  // the host Minecraft version (major.minor) / the RTP plugin version, the inner
  // slice is the same bucketised MSPT p99. This lets the fleet dashboard
  // correlate the tail cost with the game version and the plugin version, in
  // addition to the platform. Both levels are categorical / bucketised, so no
  // raw MSPT scalar is ever emitted. The outer version labels are not
  // fingerprinting vectors: bStats already collects the core Minecraft-version
  // and plugin-version charts for every server.
  public static final String MSPT_P99_BY_GAME_VERSION = "mspt_p99_by_game_version";
  public static final String MSPT_P99_BY_PLUGIN_VERSION = "mspt_p99_by_plugin_version";
}
