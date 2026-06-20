package io.github.dailystruggle.rtp.bukkit.metrics;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.metrics.api.MetricsBinding;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.common.metrics.CoreMetrics;
import io.github.dailystruggle.rtp.common.metrics.RTPMetricsExtension;
import io.github.dailystruggle.rtp.common.metrics.RtpOutcomeStats;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.DrilldownPie;
import org.bstats.charts.MultiLineChart;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Level;

/**
 * Registers RTP's bStats chart catalogue (Phase M1+M2 cost-metrics subset) on
 * the Bukkit-family bStats {@link Metrics} instance owned by the plugin.
 *
 * <p>Per {@code docs/dev/METRICS_PLAN.md > bStats Integration}:
 * <ul>
 *   <li>All lambdas read pre-cached values from
 *       {@link io.github.dailystruggle.rtp.common.metrics.CoreMetrics#snapshot()};
 *       no parallel sampling, no platform calls inline.</li>
 *   <li>Numeric values are bucketised once per snapshot, never as raw
 *       potentially-fingerprinting scalars.</li>
 *   <li>No serverId, IP, hostnames, region names, or world keys.</li>
 *   <li>Submission cadence respects bStats defaults (no custom timer).</li>
 * </ul>
 *
 * <p>Charts here cover the *RTP cost on servers* angle from the issue: how much
 * the plugin is asking of the host (TPS / MSPT / pipeline-latency / memory /
 * chunk backlog / queue depth distributions), plus the configuration-adoption
 * facts that let us correlate cost with config choices (platform, assembly
 * variant, database backend, region count, cache fill).
 */
public final class RTPCostMetricsCharts {

  private RTPCostMetricsCharts() {}

  /**
   * Registers all charts on the supplied bStats {@link Metrics} instance.
   *
   * @param metrics  the bStats Metrics handle (id 30865 for the full assembly,
   *                 12277 for the lite assembly per ADR-024 — caller decides).
   * @param assemblyVariant {@code "full"} or {@code "lite"}; reported as the
   *                        {@link BStatsChartIds#ASSEMBLY_VARIANT} pie slice.
   */
  public static void register(Metrics metrics, String assemblyVariant) {
    if (metrics == null) {
      RTP.log(Level.WARNING, "[RTP] register called with null Metrics; charts not registered");
      return;
    }
    final String variant = (assemblyVariant == null || assemblyVariant.isBlank()) ? "unknown" : assemblyVariant;

    // --- Configuration adoption ---
    metrics.addCustomChart(new SimplePie(BStatsChartIds.PLATFORM, RTPCostMetricsCharts::detectPlatform));
    metrics.addCustomChart(new SimplePie(BStatsChartIds.ASSEMBLY_VARIANT, () -> variant));
    metrics.addCustomChart(new SimplePie(BStatsChartIds.DATABASE_BACKEND, RTPCostMetricsCharts::detectDatabaseBackend));

    // Region shapes in use: AdvancedPie of shape-class simple names tallied
    // across all configured regions. Cardinality bounded by the Shape catalogue
    // (Circle, Square, Ring, ...). No region names or world names leak.
    metrics.addCustomChart(new AdvancedPie(BStatsChartIds.REGION_SHAPES_IN_USE,
            RTPCostMetricsCharts::detectRegionShapesInUse));

    // Safety features enabled: SimplePie of a stable, sorted slug
    // (e.g. "anvil_prefilter+biome_whitelist" or "default"). Reads
    // SafetyKeys booleans only; no list contents, no radii, no material names.
    metrics.addCustomChart(new SimplePie(BStatsChartIds.SAFETY_FEATURES_ENABLED,
            RTPCostMetricsCharts::detectSafetyFeaturesEnabled));

    // Addons loaded: AdvancedPie tallying which known third-party integrations
    // are *enabled* on this server. Limited to the hard-coded softdepend
    // whitelist below (mirrors plugin.yml softdepend). Any plugin name not on
    // this list is ignored — prevents fingerprinting via arbitrary plugin lists.
    metrics.addCustomChart(new AdvancedPie(BStatsChartIds.ADDONS_LOADED,
            RTPCostMetricsCharts::detectAddonsLoaded));

    // Lite features dropped: SimplePie reporting whether the lite assembly is
    // active. "none" on full builds; "lite" on lite builds. Mirror of
    // ASSEMBLY_VARIANT inverted for dashboard convenience.
    metrics.addCustomChart(new SimplePie(BStatsChartIds.LITE_FEATURES_DROPPED,
            () -> "lite".equalsIgnoreCase(variant) ? "lite" : "none"));

    // Active language / locale: SimplePie of the locale selected in language.yml
    // (en / de / es / fr / ...). Bounded by the shipped-locale whitelist so a
    // custom locale name can't fingerprint the server (collapses to "other").
    metrics.addCustomChart(new SimplePie(BStatsChartIds.LANGUAGE_SELECTION,
            RTPCostMetricsCharts::detectLanguage));

    // --- Region count (single-line, configuration cost dimension) ---
    metrics.addCustomChart(new SingleLineChart(BStatsChartIds.REGION_COUNT,
            () -> safeRegionCount()));

    // --- Cache pool health: average L1 / L2 fill across regions ---
    // L1 = keptLocations (chunks loaded), L2 = unkeptLocations (chunks released).
    // These are the existing cache tiers in RegionQueueManager (see AGENTS.md
    // > Domain Analogies & Aliases). Reported as percentages 0-100 to keep both
    // lines on a comparable scale.
    metrics.addCustomChart(new MultiLineChart(BStatsChartIds.CACHE_POOL_HEALTH, () -> {
      Map<String, Integer> map = new HashMap<>();
      double[] fills = computeCacheFillPercentages();
      map.put("L1_kept_pct", (int) Math.round(fills[0]));
      map.put("L2_unkept_pct", (int) Math.round(fills[1]));
      return map;
    }));

    // --- Bucketised cost histograms ---
    // Buckets per METRICS_PLAN.md > Recommended chart catalogue. Each chart
    // reports a single 1-bucket pie per submission; bStats aggregates into a
    // distribution across the fleet.
    metrics.addCustomChart(new AdvancedPie(BStatsChartIds.TPS_BUCKETS, () -> {
      Map<String, Integer> b = new HashMap<>();
      double tps = safeTps();
      b.put(tpsBucket(tps), 1);
      return b;
    }));

    metrics.addCustomChart(new AdvancedPie(BStatsChartIds.MSPT_BUCKETS, () -> {
      Map<String, Integer> b = new HashMap<>();
      double mspt = RTP.metrics.snapshot().mspt;
      b.put(msptBucket(mspt), 1);
      return b;
    }));

    metrics.addCustomChart(new AdvancedPie(BStatsChartIds.MEMORY_TRACKER_PRESSURE, () -> {
      Map<String, Integer> b = new HashMap<>();
      int n = rtpExt(RTP.metrics.snapshot()).memoryTrackerEntries;
      b.put(memoryTrackerBucket(n), 1);
      return b;
    }));

    metrics.addCustomChart(new AdvancedPie(BStatsChartIds.CHUNK_LOAD_BACKLOG_PRESSURE, () -> {
      Map<String, Integer> b = new HashMap<>();
      int n = rtpExt(RTP.metrics.snapshot()).chunkLoadBacklog;
      b.put(chunkBacklogBucket(n), 1);
      return b;
    }));

    metrics.addCustomChart(new AdvancedPie(BStatsChartIds.QUEUE_DEPTH_PRESSURE, () -> {
      Map<String, Integer> b = new HashMap<>();
      int n = rtpExt(RTP.metrics.snapshot()).queueDepth;
      b.put(queueDepthBucket(n), 1);
      return b;
    }));

    // --- Players per server (bucketised online player count) ---
    // AdvancedPie of the host server's online-player count, bucketised so the
    // fleet dashboard gets a population-size distribution without ever emitting
    // a raw, potentially-fingerprinting player count for an individual server.
    metrics.addCustomChart(new AdvancedPie(BStatsChartIds.PLAYER_COUNT_BUCKETS, () -> {
      Map<String, Integer> b = new HashMap<>();
      b.put(playerCountBucket(detectOnlinePlayerCount()), 1);
      return b;
    }));

    // --- RTP tick cost per RTP (sync/region vs async), bucketised + windowed ---
    // Two AdvancedPies driven by RtpSchedulerProfile: the wall-clock time RTP's
    // OWN scheduler spends executing RTP tasks, split into the main-thread /
    // region family ("sync") and the asynchronous family ("async"), divided by
    // the number of RTPs served. Unlike MSPT (whole-server tick utilisation,
    // not attributable to RTP), every nanosecond counted here was spent running
    // a task RTP submitted, so this is the plugin's real per-RTP cost. Both are
    // accumulated over a rolling ~30-minute window (the bStats rollover) in
    // 1-minute delta buckets, so trailing cost (cache refill after a teleport)
    // is still counted against the same window's RTP population. Bucketised so
    // no raw timing or RTP count is ever emitted.
    // Each chart is a DrilldownPie broken into cost components (outer slice),
    // each carrying its own bucketised ms-per-RTP (inner slice): "scheduler" is
    // RTP's own scheduler cost on both charts; "chunk_generated" /
    // "chunk_ungenerated" are the wall-clock cost of loading already-generated vs
    // ungenerated chunks, folded in ONLY on the chart whose family matches the
    // backend's chunk-load mode (async backends load chunks off-thread -> async
    // chart; sync backends on the main thread -> sync chart). Summed, the
    // components are the overall per-RTP cost an operator should expect; the split
    // shows that RTP-ing into ungenerated terrain (generation) is the expensive
    // part - removable by pre-generating the world.
    startRtpCostSampler();
    metrics.addCustomChart(new DrilldownPie(BStatsChartIds.RTP_SYNC_COST_PER_RTP,
            () -> rtpCostDrilldown(true)));
    metrics.addCustomChart(new DrilldownPie(BStatsChartIds.RTP_ASYNC_COST_PER_RTP,
            () -> rtpCostDrilldown(false)));

    // --- Chunk-load floor (smallest single-chunk load time), by mode + gen state ---
    // DrilldownPie driven by ChunkLoadProfile: the smallest single-chunk live-load
    // wall-clock time ever observed on this server, bucketised. We never see exactly
    // when the platform begins a load after the request, so the running minimum is
    // taken as the assumed shortest time to start and complete a load (the "floor").
    // The outer slice encodes BOTH how this backend loads chunks (sync/async,
    // assumed from the detected platform since we know the backend and what it does)
    // AND whether the chunk was already generated on disk vs had to be generated -
    // e.g. "async/generated", "async/ungenerated" - because generation is a
    // different (more expensive) cost than loading and can be either sync or async.
    // ChunkLoadProfile only times genuine live loads (not anvil-prefilter
    // short-circuits or already-resident chunks), so cache hits don't peg the floor
    // at ~0. Both levels are categorical / bucketised, so no raw timing is emitted.
    metrics.addCustomChart(new DrilldownPie(BStatsChartIds.CHUNK_LOAD_FLOOR_MS,
            RTPCostMetricsCharts::chunkLoadFloorDrilldown));

    // --- M2 Runtime health (additive, Section C / row C4) ---
    // TPS trendlines: 1m / 5m / 15m EMA, reported as int *100 so bStats line
    // charts (int-only) preserve sub-integer resolution (e.g. 19.87 -> 1987).
    // Dashboards divide by 100 to render. NaN coerces to 0 to keep the line
    // continuous when the binding hasn't seeded yet.
    metrics.addCustomChart(new MultiLineChart(BStatsChartIds.TPS_TRENDLINES, () -> {
      Map<String, Integer> map = new HashMap<>();
      MetricsSnapshot snap = RTP.metrics.snapshot();
      map.put("tps1m_x100", scaledTps(snap.tps1m));
      map.put("tps5m_x100", scaledTps(snap.tps5m));
      map.put("tps15m_x100", scaledTps(snap.tps15m));
      return map;
    }));

    // Heap pressure: bucketised heapUsed / heapMax percentage. Single biggest
    // RTP-cost dimension after TPS — caches and the pipeline both allocate.
    metrics.addCustomChart(new AdvancedPie(BStatsChartIds.HEAP_PRESSURE, () -> {
      Map<String, Integer> b = new HashMap<>();
      MetricsSnapshot snap = RTP.metrics.snapshot();
      b.put(heapPressureBucket(snap.heapUsedBytes, snap.heapMaxBytes), 1);
      return b;
    }));

    // Tick-budget utilisation: meaningful on Folia (per-region tick budget
    // headroom); on Paper/Bukkit/Fabric this is typically NaN and buckets
    // into "unknown" — that is the correct answer for a single-region runtime.
    metrics.addCustomChart(new AdvancedPie(BStatsChartIds.TICK_BUDGET_UTILISATION, () -> {
      Map<String, Integer> b = new HashMap<>();
      double u = RTP.metrics.snapshot().tickBudgetUtilisation;
      b.put(tickBudgetBucket(u), 1);
      return b;
    }));

    // Folia region count: bucketised list size from MetricsSnapshot.foliaRegions().
    // Structurally 0 on non-Folia platforms (empty list default in MetricsBinding),
    // so this also doubles as a "Folia adoption" signal without any platform
    // branch. Bucketed for fingerprint resistance (no exact region counts).
    metrics.addCustomChart(new AdvancedPie(BStatsChartIds.FOLIA_REGION_COUNT, () -> {
      Map<String, Integer> b = new HashMap<>();
      List<?> regions = RTP.metrics.snapshot().foliaRegions;
      int n = (regions == null) ? 0 : regions.size();
      b.put(foliaRegionCountBucket(n), 1);
      return b;
    }));

    // Pending teleports: bucketised pendingTeleports — distinct from queueDepth
    // (pre-verified location pool) in that it counts players actively awaiting
    // a coordinate. Symmetric with QUEUE_DEPTH_PRESSURE.
    metrics.addCustomChart(new AdvancedPie(BStatsChartIds.PENDING_TELEPORTS_PRESSURE, () -> {
      Map<String, Integer> b = new HashMap<>();
      int n = rtpExt(RTP.metrics.snapshot()).pendingTeleports;
      b.put(pendingTeleportsBucket(n), 1);
      return b;
    }));

    // --- Aggregate outcome metrics (process-global RtpOutcomeStats.GLOBAL) ---
    // The always-on location-generation outcome accumulator records every
    // terminal PregenTask outcome (success, or a failure bucketed by
    // FailTypes) independent of the per-invocation verbose flag. Both charts
    // are categorical / bucketised so no raw cumulative count (which would grow
    // unbounded and fingerprint long-lived servers) is ever emitted.
    //
    // Success rate: bucketised fraction of successful generations. "unknown"
    // until the first outcome is recorded.
    metrics.addCustomChart(new AdvancedPie(BStatsChartIds.AGGREGATE_SUCCESS_RATE, () -> {
      Map<String, Integer> b = new HashMap<>();
      b.put(aggregateSuccessRateBucket(), 1);
      return b;
    }));

    // Top failure cause: the single FailTypes with the highest cumulative
    // reject count, or "none" when no failures (or no outcomes) recorded.
    // Cardinality is bounded by the fixed FailTypes catalogue.
    metrics.addCustomChart(new SimplePie(BStatsChartIds.AGGREGATE_TOP_FAILURE_CAUSE,
            RTPCostMetricsCharts::detectTopFailureCause));

    // --- RTP-correlated MSPT p99 cost, correlated to platform ---
    // DrilldownPie: the outer slice is the host platform, the inner slice is the
    // bucketised p99 of the host server MSPT (milliseconds per tick), computed
    // from the preexisting 1 Hz MetricsSnapshotRing (the rolling MSPT/heap ring
    // the CoreMetrics sampler already populates) rather than the
    // location-generation pipeline latency. This answers "what is the per-tick
    // tail cost, and on which platform?" directly on the fleet dashboard. Both
    // levels are categorical / bucketised so no raw MSPT scalar is ever emitted.
    metrics.addCustomChart(new DrilldownPie(BStatsChartIds.MSPT_P99_BY_PLATFORM, () -> {
      Map<String, Map<String, Integer>> map = new HashMap<>();
      Map<String, Integer> inner = new HashMap<>();
      inner.put(msptP99Bucket(msptP99Ms()), 1);
      map.put(detectPlatform(), inner);
      return map;
    }));

    // The same MSPT-p99 tail cost, correlated to the host Minecraft version
    // (outer = major.minor game version) and the RTP plugin version (outer =
    // plugin version). These answer "does a particular game / plugin version
    // regress the tail cost?" on the fleet dashboard. Inner slice is the
    // identical bucketised MSPT p99; both levels are categorical so no raw MSPT
    // scalar is emitted, and the version labels are already collected by
    // bStats' core charts (not a new fingerprint vector).
    metrics.addCustomChart(new DrilldownPie(BStatsChartIds.MSPT_P99_BY_GAME_VERSION, () -> {
      Map<String, Map<String, Integer>> map = new HashMap<>();
      Map<String, Integer> inner = new HashMap<>();
      inner.put(msptP99Bucket(msptP99Ms()), 1);
      map.put(detectGameVersion(), inner);
      return map;
    }));

    metrics.addCustomChart(new DrilldownPie(BStatsChartIds.MSPT_P99_BY_PLUGIN_VERSION, () -> {
      Map<String, Map<String, Integer>> map = new HashMap<>();
      Map<String, Integer> inner = new HashMap<>();
      inner.put(msptP99Bucket(msptP99Ms()), 1);
      map.put(detectPluginVersion(), inner);
      return map;
    }));
  }

  /**
   * Host Minecraft version reduced to {@code major.minor} (e.g. {@code "1.21"}),
   * read from {@link Bukkit#getBukkitVersion()} (e.g. {@code "1.21.4-R0.1-SNAPSHOT"}).
   * Returns {@code "unknown"} when the version can't be parsed (or on any error).
   * Reducing to major.minor keeps cardinality bounded.
   */
  static String detectGameVersion() {
    try {
      String raw = Bukkit.getBukkitVersion();
      if (raw == null || raw.isBlank()) return "unknown";
      // Strip the "-R0.1-SNAPSHOT" qualifier, then keep major.minor only.
      String core = raw.split("-", 2)[0];
      String[] parts = core.split("\\.");
      if (parts.length >= 2) return parts[0] + "." + parts[1];
      return core.isBlank() ? "unknown" : core;
    } catch (Throwable t) {
      return "unknown";
    }
  }

  /**
   * The RTP plugin version, read from the {@code "RTP"} plugin's description.
   * Returns {@code "unknown"} when the plugin handle isn't available (or on any
   * error). The plugin version is already reported by bStats' core chart, so it
   * is not a new fingerprinting vector.
   */
  static String detectPluginVersion() {
    try {
      org.bukkit.plugin.Plugin rtp = Bukkit.getPluginManager().getPlugin("RTP");
      if (rtp == null) return "unknown";
      String version = rtp.getDescription().getVersion();
      return (version == null || version.isBlank()) ? "unknown" : version;
    } catch (Throwable t) {
      return "unknown";
    }
  }

  /**
   * Point-in-time p99 of the host server MSPT (milliseconds per tick), computed
   * from the preexisting {@link io.github.dailystruggle.rtp.common.metrics.MetricsSnapshotRing}
   * owned by {@link CoreMetrics} (the rolling 1 Hz MSPT/heap ring). Returns
   * {@link Double#NaN} when the core metrics implementation is not live, no
   * samples have been recorded yet, or on any error.
   */
  static double msptP99Ms() {
    try {
      if (RTP.metrics instanceof CoreMetrics) {
        double[] samples = ((CoreMetrics) RTP.metrics).snapshotRing().msptSnapshot();
        return percentile(samples, 0.99);
      }
    } catch (Throwable ignored) {
      // fall through to the NaN sentinel
    }
    return Double.NaN;
  }

  /**
   * Nearest-rank percentile over a snapshot array, skipping {@code NaN}
   * sentinels (unsampled slots). Returns {@link Double#NaN} when there are no
   * finite samples. {@code q} is a fraction in {@code [0, 1]}.
   */
  private static double percentile(double[] samples, double q) {
    if (samples == null || samples.length == 0) return Double.NaN;
    double[] finite = new double[samples.length];
    int n = 0;
    for (double v : samples) {
      if (!Double.isNaN(v)) finite[n++] = v;
    }
    if (n == 0) return Double.NaN;
    double[] sorted = java.util.Arrays.copyOf(finite, n);
    java.util.Arrays.sort(sorted);
    int rank = (int) Math.ceil(q * n) - 1;
    if (rank < 0) rank = 0;
    if (rank >= n) rank = n - 1;
    return sorted[rank];
  }

  /**
   * Fingerprint-safe bucket for the MSPT p99 in ms. {@code "unknown"} until the
   * first sample lands (NaN). Never emits a raw MSPT value.
   */
  static String msptP99Bucket(double ms) {
    if (Double.isNaN(ms) || ms < 0.0) return "unknown";
    if (ms < 25.0) return "<25";
    if (ms < 50.0) return "25-50";
    if (ms < 100.0) return "50-100";
    if (ms < 250.0) return "100-250";
    if (ms < 1000.0) return "250-1000";
    return "1000+";
  }

  /**
   * Bucketised success rate from {@link RtpOutcomeStats#GLOBAL}. Returns
   * {@code "unknown"} when no outcomes have been recorded yet (or on any
   * error). Never emits a raw count, so long-lived servers cannot be
   * fingerprinted by their cumulative totals.
   */
  static String aggregateSuccessRateBucket() {
    try {
      double rate = RtpOutcomeStats.GLOBAL.successRate();
      if (Double.isNaN(rate) || rate < 0.0) return "unknown";
      if (rate < 0.50) return "<50";
      if (rate < 0.75) return "50-75";
      if (rate < 0.90) return "75-90";
      if (rate < 0.99) return "90-99";
      return "99+";
    } catch (Throwable t) {
      return "unknown";
    }
  }

  /**
   * Name of the {@link LocationGenerator.FailTypes} with the highest cumulative
   * reject count in {@link RtpOutcomeStats#GLOBAL}, or {@code "none"} when no
   * failures have been recorded. Cardinality is bounded by the fixed FailTypes
   * enum, so this never leaks a fingerprintable value.
   */
  static String detectTopFailureCause() {
    try {
      LocationGenerator.FailTypes top = null;
      long best = 0L;
      for (Map.Entry<LocationGenerator.FailTypes, Long> e :
              RtpOutcomeStats.GLOBAL.failureBreakdown().entrySet()) {
        long count = (e.getValue() == null) ? 0L : e.getValue();
        if (count > best) {
          best = count;
          top = e.getKey();
        }
      }
      return (top == null) ? "none" : top.name();
    } catch (Throwable t) {
      return "unknown";
    }
  }

  // --- Detection helpers (no-throw; log-and-fallback so a chart fail never breaks startup) ---

  static String detectPlatform() {
    try {
      String name = Bukkit.getServer().getName();
      String version = Bukkit.getServer().getVersion();
      // Folia exposes itself via the server class signature; checking version
      // strings is the lowest-coupling probe we can do here without pulling
      // in a Folia/Paper-specific import (which rtp-plugin shouldn't carry).
      if (version != null && version.toLowerCase().contains("folia")) return "folia";
      // Paper is reliably detected via the presence of Bukkit.getServer().getName()
      // returning "Paper" or by the Paper-specific TPS API; we use the name.
      if (name != null && name.equalsIgnoreCase("Paper")) return "paper";
      if (name != null && name.equalsIgnoreCase("Spigot")) return "spigot";
      // Forks (Purpur, Pufferfish, etc.) report their fork name in getName(); the
      // fleet-wide aggregation cares about the upstream family, so we fall back
      // to "paper-fork" / "spigot-fork" when the version line includes "Paper".
      if (version != null && version.toLowerCase().contains("paper")) return "paper-fork";
      return name == null ? "unknown" : name.toLowerCase();
    } catch (Throwable t) {
      return "unknown";
    }
  }

  private static String detectDatabaseBackend() {
    try {
      // RTP.configs.databaseAccessor is the canonical accessor; its concrete
      // class name encodes the backend (H2/SQLite/MySQL/PostgreSQL/YamlFile).
      Object accessor = (RTP.getInstance() == null) ? null : RTP.getInstance().databaseAccessor;
      if (accessor == null) return "none";
      String cls = accessor.getClass().getSimpleName();
      String lc = cls.toLowerCase();
      if (lc.contains("yaml")) return "yaml";
      if (lc.contains("h2")) return "h2";
      if (lc.contains("sqlite")) return "sqlite";
      if (lc.contains("postgres")) return "postgresql";
      if (lc.contains("mysql") || lc.contains("mariadb")) return "mysql";
      return "other";
    } catch (Throwable t) {
      return "unknown";
    }
  }

  /**
   * Fixed set of locales shipped with RTP (the {@code lang/<locale>/} resource
   * directories plus the {@code en} baseline). Reporting only members of this
   * set bounds the chart's cardinality; any other configured locale collapses
   * to {@code "other"} so a custom locale name can't fingerprint the server.
   */
  static final List<String> KNOWN_LOCALES =
          Collections.unmodifiableList(Arrays.asList(
                  "en", "cat", "de", "es", "fr", "it",
                  "ja", "ko", "nl", "pl", "pt", "ru", "zh"));

  /**
   * Active locale selected in {@code language.yml}, normalised to a member of
   * {@link #KNOWN_LOCALES} (or {@code "other"} for any shipped-but-unlisted /
   * custom locale, {@code "unknown"} when it can't be resolved). Never echoes a
   * raw, potentially-custom locale string.
   */
  static String detectLanguage() {
    try {
      if (RTP.serverAccessor == null) return "unknown";
      String locale = io.github.dailystruggle.rtp.common.configuration.LanguageBootstrap
              .resolve(RTP.serverAccessor.getPluginDirectory());
      if (locale == null || locale.isBlank()) return "unknown";
      String lc = locale.toLowerCase();
      return KNOWN_LOCALES.contains(lc) ? lc : "other";
    } catch (Throwable t) {
      return "unknown";
    }
  }

  /**
   * Hard-coded set of integration plugin names mirrored from
   * {@code plugin.yml > softdepend}. Limiting the reported set to this fixed
   * whitelist prevents the chart from leaking arbitrary plugin names on the
   * host server, which would otherwise be a fingerprinting vector. Names are
   * compared case-insensitively against {@code Bukkit.getPluginManager()}.
   */
  static final List<String> KNOWN_ADDON_PLUGINS =
          Collections.unmodifiableList(Arrays.asList(
                  "WorldGuard",
                  "WorldEdit",
                  "PlaceholderAPI",
                  "Vault",
                  "Chunky",
                  "ChunkyBorder",
                  "GriefDefender",
                  "GriefPrevention",
                  "Towny",
                  "Factions",
                  "FactionsBridge",
                  "Lands",
                  "RedProtect",
                  "Residence",
                  "CrashClaim",
                  "HuskClaims",
                  "Kingdoms"));

  /**
   * Tally of region shape simple-class-names across all configured regions.
   * Falls back to {@code "unknown"} when the shape can't be resolved. Returns
   * an empty map (no bStats entry) if the region table isn't ready yet.
   */
  static Map<String, Integer> detectRegionShapesInUse() {
    Map<String, Integer> tally = new HashMap<>();
    try {
      if (RTP.selectionAPI == null || RTP.selectionAPI.permRegionLookup == null) return tally;
      for (var region : RTP.selectionAPI.permRegionLookup.values()) {
        if (region == null) continue;
        String label = "unknown";
        try {
          Object shape = region.getData(RegionKeys.shape);
          if (shape != null) {
            label = shape.getClass().getSimpleName().toLowerCase();
            if (label.isEmpty()) label = "unknown";
          }
        } catch (Throwable ignored) {
          // label stays "unknown"
        }
        tally.merge(label, 1, Integer::sum);
      }
    } catch (Throwable ignored) {
      // Defensive: chart must never throw.
    }
    return tally;
  }

  /**
   * Stable, sorted slug describing which safety toggles are non-default.
   * Returns {@code "default"} when every read boolean matches its documented
   * default. Reads only enum-keyed booleans (no list contents, no numeric
   * radii, no material names) so no per-server config detail leaks.
   */
  @SuppressWarnings("unchecked")
  static String detectSafetyFeaturesEnabled() {
    try {
      if (RTP.getInstance() == null) return "unknown";
      ConfigParser<SafetyKeys> safety =
              (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
      if (safety == null) return "unknown";
      TreeSet<String> on = new TreeSet<>();
      // anvilPrefilterEnabled default: true; "default" means present, off means non-default.
      Object anvil = safety.getConfigValue(SafetyKeys.anvilPrefilterEnabled, Boolean.TRUE);
      if (!Boolean.parseBoolean(String.valueOf(anvil))) on.add("anvil_prefilter_off");
      // biomeWhitelist default: false; on means non-default.
      Object biome = safety.getConfigValue(SafetyKeys.biomeWhitelist, Boolean.FALSE);
      if (Boolean.parseBoolean(String.valueOf(biome))) on.add("biome_whitelist");
      if (on.isEmpty()) return "default";
      return String.join("+", on);
    } catch (Throwable t) {
      return "unknown";
    }
  }

  /**
   * Tally of enabled integration plugins from {@link #KNOWN_ADDON_PLUGINS}.
   * Reports each detected plugin by its canonical (capitalized) softdepend
   * name and a {@code "none"} bucket when zero are present.
   */
  static Map<String, Integer> detectAddonsLoaded() {
    Map<String, Integer> tally = new HashMap<>();
    try {
      var pm = Bukkit.getPluginManager();
      if (pm == null) {
        tally.put("none", 1);
        return tally;
      }
      for (String name : KNOWN_ADDON_PLUGINS) {
        try {
          if (pm.isPluginEnabled(name)) tally.put(name, 1);
        } catch (Throwable ignored) {
          // skip — chart must never throw.
        }
      }
      if (tally.isEmpty()) tally.put("none", 1);
    } catch (Throwable ignored) {
      tally.clear();
      tally.put("none", 1);
    }
    return tally;
  }

  /**
   * Look up RTP's plugin-specific counters on the snapshot, falling back to a
   * zeroed extension if the NOOP binding is live. Never returns {@code null}.
   */
  private static RTPMetricsExtension rtpExt(MetricsSnapshot snap) {
    RTPMetricsExtension ext = (snap == null) ? null : snap.extension(RTPMetricsExtension.class);
    return (ext != null) ? ext : new RTPMetricsExtension(0, 0, 0, 0, Double.NaN, -1);
  }

  /**
   * Number of players currently online on the host server, read from
   * {@link Bukkit#getOnlinePlayers()}. Returns {@code -1} when the count can't
   * be resolved (no live server in the test fixture, or any error); callers
   * bucket {@code -1} into {@code "unknown"}.
   */
  static int detectOnlinePlayerCount() {
    try {
      return Bukkit.getOnlinePlayers().size();
    } catch (Throwable t) {
      return -1;
    }
  }

  /**
   * Fingerprint-safe bucket for the online-player count. Never emits a raw
   * count, so a uniquely-sized server can't be identified by its population.
   * Negative input (unresolved count) buckets into {@code "unknown"}.
   */
  static String playerCountBucket(int n) {
    if (n < 0) return "unknown";
    if (n == 0) return "0";
    if (n <= 5) return "1-5";
    if (n <= 20) return "6-20";
    if (n <= 50) return "21-50";
    if (n <= 100) return "51-100";
    if (n <= 250) return "101-250";
    return "250+";
  }

  /**
   * Number of one-minute buckets retained for the RTP-cost windows. Sized to the
   * default bStats submission cadence (~30 minutes) so the whole rollover window
   * is covered: a burst of RTP commands and the scheduler cost that trails it
   * (cache refill in the minutes after the teleport) are both counted within the
   * same window, against the same RTP population.
   */
  static final int RTP_COST_WINDOW_MINUTES = 30;

  /** Rolling sync/region scheduler-cost / RTP-served accumulator (30 x 1-minute window). */
  static final RtpCostWindow RTP_SYNC_COST_WINDOW = new RtpCostWindow(true);

  /** Rolling async scheduler-cost / RTP-served accumulator (30 x 1-minute window). */
  static final RtpCostWindow RTP_ASYNC_COST_WINDOW = new RtpCostWindow(false);

  /**
   * Rolling wall-clock cost / RTP-served accumulator for loading already-generated
   * chunks (loaded from disk). Folded into whichever per-RTP cost chart matches the
   * backend's chunk-load mode (async backend -> async chart, sync -> sync chart).
   */
  static final RtpCostWindow RTP_CHUNK_GEN_COST_WINDOW = new RtpCostWindow(true);

  /**
   * Rolling wall-clock cost / RTP-served accumulator for loading ungenerated chunks
   * (the load triggers generation - the expensive path). Folded into the same
   * mode-matched per-RTP cost chart as {@link #RTP_CHUNK_GEN_COST_WINDOW}.
   */
  static final RtpCostWindow RTP_CHUNK_UNGEN_COST_WINDOW = new RtpCostWindow(false);

  /** Handle for the 1-minute sampler task, so it can be cancelled on shutdown. */
  private static volatile Object rtpCostSamplerTask;

  /**
   * Starts the 1-minute RTP-cost sampler on {@link RTP#scheduler}. Idempotent and
   * null-defended: a missing scheduler (e.g. a test fixture) is a no-op, and the
   * sampler body never propagates a throwable onto the scheduler thread. Each
   * fire records, into both windows, the delta of the cumulative scheduler-cost
   * counters ({@link io.github.dailystruggle.rtp.common.metrics.RtpSchedulerProfile})
   * and the RTP-served counter ({@link RtpOutcomeStats}) since the previous fire.
   */
  static synchronized void startRtpCostSampler() {
    try {
      if (rtpCostSamplerTask != null) return;
      if (RTP.scheduler == null) return;
      // 1 minute == 1200 server ticks (20 tps).
      rtpCostSamplerTask = RTP.scheduler.runTaskTimerAsynchronously(() -> {
        try {
          long rtp = RtpOutcomeStats.GLOBAL.successCount();
          RTP_SYNC_COST_WINDOW.sample(
                  io.github.dailystruggle.rtp.common.metrics.RtpSchedulerProfile.GLOBAL.syncNanos(), rtp);
          RTP_ASYNC_COST_WINDOW.sample(
                  io.github.dailystruggle.rtp.common.metrics.RtpSchedulerProfile.GLOBAL.asyncNanos(), rtp);
          // Chunk-load wall-clock cost, split generated vs ungenerated, folded into
          // whichever per-RTP cost chart matches the backend's chunk-load mode.
          RTP_CHUNK_GEN_COST_WINDOW.sample(
                  io.github.dailystruggle.rtp.common.metrics.ChunkLoadProfile.GLOBAL.totalNanos(true), rtp);
          RTP_CHUNK_UNGEN_COST_WINDOW.sample(
                  io.github.dailystruggle.rtp.common.metrics.ChunkLoadProfile.GLOBAL.totalNanos(false), rtp);
        } catch (Throwable ignored) {
          // Sampler must never poison the scheduler thread.
        }
      }, 1200L, 1200L);
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[RTP] failed to start rtp-cost sampler", t);
    }
  }

  /**
   * Rolling-window accumulator for one RTP scheduler-cost chart. Sampled once a
   * minute from a cumulative nanosecond counter and the cumulative RTP-served
   * counter; each minute bucket stores the delta of both since the previous
   * sample. Summing per-minute deltas over the window is exact (the counters are
   * cumulative, so no spike is missed regardless of sampling cadence), and
   * dividing total nanoseconds by total RTP served yields the mean cost per RTP.
   * Thread-safe: the sampler (scheduler thread) writes and the bStats submission
   * thread reads.
   *
   * <p>The {@code sync} flag is informational only (it labels which counter feeds
   * this instance); the accumulation logic is identical for both families.
   */
  static final class RtpCostWindow {
    private final boolean sync;
    private final int minutes;
    private final long[] nanosByMinute;
    private final long[] rtpByMinute;
    private final boolean[] filled;
    private int cursor = 0;
    private long lastCumulativeNanos = -1L;
    private long lastCumulativeRtp = -1L;

    RtpCostWindow(boolean sync) {
      this(sync, RTP_COST_WINDOW_MINUTES);
    }

    RtpCostWindow(boolean sync, int minutes) {
      this.sync = sync;
      this.minutes = minutes;
      this.nanosByMinute = new long[minutes];
      this.rtpByMinute = new long[minutes];
      this.filled = new boolean[minutes];
    }

    /** Whether this window is fed by the sync/region (true) or async (false) counter. */
    boolean isSync() {
      return sync;
    }

    /**
     * Records one minute's cumulative scheduler-cost nanoseconds and cumulative
     * RTP-served counter. The first call only seeds the baselines (deltas
     * unknown), recording a zero-cost / zero-RTP minute.
     */
    synchronized void sample(long cumulativeNanos, long cumulativeRtp) {
      long nanosDelta;
      long rtpDelta;
      if (lastCumulativeNanos < 0L) {
        nanosDelta = 0L;
        rtpDelta = 0L;
      } else {
        nanosDelta = cumulativeNanos - lastCumulativeNanos;
        rtpDelta = cumulativeRtp - lastCumulativeRtp;
        if (nanosDelta < 0L) nanosDelta = 0L; // defensive: counter reset
        if (rtpDelta < 0L) rtpDelta = 0L;
      }
      lastCumulativeNanos = cumulativeNanos;
      lastCumulativeRtp = cumulativeRtp;
      nanosByMinute[cursor] = nanosDelta;
      rtpByMinute[cursor] = rtpDelta;
      filled[cursor] = true;
      cursor = (cursor + 1) % minutes;
    }

    /**
     * Mean milliseconds of scheduler cost per RTP served over the window: total
     * nanoseconds divided by total RTP served, converted to ms.
     * {@link Double#NaN} when no minute has been recorded or no RTP has been
     * served in the window.
     */
    synchronized double msPerRtp() {
      long nanosAccum = 0L;
      long rtpAccum = 0L;
      boolean any = false;
      for (int i = 0; i < minutes; i++) {
        if (!filled[i]) continue;
        any = true;
        nanosAccum += nanosByMinute[i];
        rtpAccum += rtpByMinute[i];
      }
      if (!any || rtpAccum <= 0L) return Double.NaN;
      return (nanosAccum / 1.0e6) / (double) rtpAccum;
    }

    /** Test hook: clears the window to its freshly-constructed state. */
    synchronized void reset() {
      Arrays.fill(nanosByMinute, 0L);
      Arrays.fill(rtpByMinute, 0L);
      Arrays.fill(filled, false);
      cursor = 0;
      lastCumulativeNanos = -1L;
      lastCumulativeRtp = -1L;
    }
  }

  /**
   * Fingerprint-safe bucket for an RTP scheduler-cost-per-RTP figure in
   * milliseconds. {@code "unknown"} on the NaN sentinel (window not yet usable).
   * Never emits a raw timing or RTP count.
   */
  static String rtpCostBucket(double ms) {
    if (Double.isNaN(ms) || ms < 0.0) return "unknown";
    if (ms < 0.1) return "<0.1";
    if (ms < 0.5) return "0.1-0.5";
    if (ms < 2.0) return "0.5-2";
    if (ms < 10.0) return "2-10";
    if (ms < 50.0) return "10-50";
    return "50+";
  }

  /**
   * How this backend loads chunks for the RTP pipeline, assumed from the detected
   * platform rather than runtime-probed (we know the backend and what it does):
   * Paper / Paper-fork / Folia load chunks asynchronously off the tick thread;
   * vanilla Spigot / CraftBukkit resolve the load on the main thread. Returns
   * {@code "unknown"} when the platform can't be determined. Cardinality is
   * bounded to {sync, async, unknown}.
   */
  static String detectChunkLoadMode() {
    String platform = detectPlatform();
    switch (platform) {
      case "folia":
      case "paper":
      case "paper-fork":
        return "async";
      case "spigot":
      case "craftbukkit":
        return "sync";
      default:
        return "unknown";
    }
  }

  /**
   * Fingerprint-safe bucket for the smallest single-chunk live-load time, given
   * the running-minimum in nanoseconds ({@code -1} when no load recorded yet ->
   * {@code "unknown"}). Bucketised in milliseconds so no raw timing is emitted.
   */
  static String chunkLoadFloorBucket(long minNanos) {
    if (minNanos < 0L) return "unknown";
    double ms = minNanos / 1.0e6;
    if (ms < 0.1) return "<0.1";
    if (ms < 0.5) return "0.1-0.5";
    if (ms < 2.0) return "0.5-2";
    if (ms < 10.0) return "2-10";
    if (ms < 50.0) return "10-50";
    return "50+";
  }

  /** A one-entry inner-slice map ({@code label -> 1}) for a DrilldownPie. */
  private static Map<String, Integer> singleBucket(String label) {
    Map<String, Integer> inner = new HashMap<>();
    inner.put(label, 1);
    return inner;
  }

  /**
   * Builds the DrilldownPie payload for one per-RTP cost chart (sync or async
   * family). Outer slices are cost components, each carrying its own bucketised
   * ms-per-RTP inner slice:
   * <ul>
   *   <li>{@code scheduler} - RTP's own scheduler cost per RTP (always present).</li>
   *   <li>{@code chunk_generated} / {@code chunk_ungenerated} - wall-clock cost
   *       per RTP of loading already-generated vs ungenerated chunks, present
   *       ONLY when this chart's family matches the backend's chunk-load mode
   *       ({@link #detectChunkLoadMode()}): an async backend loads chunks
   *       off-thread so its cost lands on the async chart, a sync backend on the
   *       sync chart. An {@code unknown} mode attributes chunk cost to neither.</li>
   * </ul>
   * Every value is a bucket label, so no raw timing or RTP count is emitted.
   */
  static Map<String, Map<String, Integer>> rtpCostDrilldown(boolean sync) {
    Map<String, Map<String, Integer>> map = new HashMap<>();
    RtpCostWindow scheduler = sync ? RTP_SYNC_COST_WINDOW : RTP_ASYNC_COST_WINDOW;
    map.put("scheduler", singleBucket(rtpCostBucket(scheduler.msPerRtp())));
    String mode = detectChunkLoadMode();
    boolean modeMatches =
            ("async".equals(mode) && !sync) || ("sync".equals(mode) && sync);
    if (modeMatches) {
      map.put("chunk_generated",
              singleBucket(rtpCostBucket(RTP_CHUNK_GEN_COST_WINDOW.msPerRtp())));
      map.put("chunk_ungenerated",
              singleBucket(rtpCostBucket(RTP_CHUNK_UNGEN_COST_WINDOW.msPerRtp())));
    }
    return map;
  }

  /**
   * Builds the DrilldownPie payload for the chunk-load floor chart. Outer slice
   * is {@code "<mode>/<genstate>"} (e.g. {@code async/generated},
   * {@code async/ungenerated}), inner slice is the bucketised running-minimum
   * floor for that generated/ungenerated class. Only classes with a recorded
   * load emit; if neither has one yet, a single {@code "unknown"} slice is
   * reported so the chart still appears on the dashboard.
   */
  static Map<String, Map<String, Integer>> chunkLoadFloorDrilldown() {
    Map<String, Map<String, Integer>> map = new HashMap<>();
    String mode = detectChunkLoadMode();
    io.github.dailystruggle.rtp.common.metrics.ChunkLoadProfile profile =
            io.github.dailystruggle.rtp.common.metrics.ChunkLoadProfile.GLOBAL;
    long genFloor = profile.minNanos(true);
    long ungenFloor = profile.minNanos(false);
    if (genFloor >= 0L) {
      map.put(mode + "/generated", singleBucket(chunkLoadFloorBucket(genFloor)));
    }
    if (ungenFloor >= 0L) {
      map.put(mode + "/ungenerated", singleBucket(chunkLoadFloorBucket(ungenFloor)));
    }
    if (map.isEmpty()) {
      map.put(mode + "/generated", singleBucket(chunkLoadFloorBucket(-1L)));
    }
    return map;
  }

  private static int safeRegionCount() {
    try {
      if (RTP.selectionAPI == null || RTP.selectionAPI.permRegionLookup == null) return 0;
      return RTP.selectionAPI.permRegionLookup.size();
    } catch (Throwable t) {
      return 0;
    }
  }

  /**
   * Returns {@code [L1_pct, L2_pct]} averaged across configured regions.
   * Reads buffers directly because the current {@link MetricsSnapshot} doesn't
   * yet carry per-cache fill ratios (deferred — see
   * {@code CHECKLIST-metrics-and-multiserver.md} row B11/C3 for the snapshot
   * extension). All access is null-defended so the chart never throws.
   */
  private static double[] computeCacheFillPercentages() {
    long keptUsed = 0L, keptCap = 0L, unkeptUsed = 0L, unkeptCap = 0L;
    try {
      if (RTP.selectionAPI != null && RTP.selectionAPI.permRegionLookup != null) {
        for (var region : RTP.selectionAPI.permRegionLookup.values()) {
          if (region == null || region.queueManager == null) continue;
          if (region.queueManager.keptLocations != null) {
            keptUsed += region.queueManager.keptLocations.size();
            keptCap += region.queueManager.keptLocations.capacity();
          }
          if (region.queueManager.unkeptLocations != null) {
            unkeptUsed += region.queueManager.unkeptLocations.size();
            unkeptCap += region.queueManager.unkeptLocations.capacity();
          }
        }
      }
    } catch (Throwable ignored) {
      // Defensive: chart must never throw.
    }
    double l1 = (keptCap > 0) ? 100.0 * keptUsed / keptCap : 0.0;
    double l2 = (unkeptCap > 0) ? 100.0 * unkeptUsed / unkeptCap : 0.0;
    return new double[] {l1, l2};
  }

  private static double safeTps() {
    // C6 (Section C of CHECKLIST-metrics-and-multiserver, §1.6.4) — the
    // metrics facade is the single TPS source. The previous reflective
    // Bukkit.getServer().getTPS() fallback was redundant: when a
    // BukkitTpsBinding is installed snapshot.tps1m is sampled, and when it
    // is not the chart correctly degrades to the "unknown" bucket. The
    // RTPServerAccessor#getTPS path (used elsewhere) already routes through
    // this same snapshot per §1.6.1, so any reflective probe re-introduced
    // here would only fingerprint stale data.
    return RTP.metrics.snapshot().tps1m;
  }

  // --- Bucket functions (METRICS_PLAN.md ranges) ---

  private static String tpsBucket(double tps) {
    if (Double.isNaN(tps)) return "unknown";
    if (tps < 10.0) return "<10";
    if (tps < 15.0) return "10-15";
    if (tps < 19.0) return "15-19";
    return "19-20+";
  }

  private static String msptBucket(double mspt) {
    if (Double.isNaN(mspt)) return "unknown";
    if (mspt < 25.0) return "<25";
    if (mspt < 50.0) return "25-50";
    if (mspt < 100.0) return "50-100";
    return "100+";
  }

  private static String memoryTrackerBucket(int n) {
    if (n < 10) return "<10";
    if (n < 50) return "10-50";
    if (n < 200) return "50-200";
    return "200+";
  }

  private static String chunkBacklogBucket(int n) {
    if (n == 0) return "0";
    if (n <= 5) return "1-5";
    if (n <= 20) return "6-20";
    return "21+";
  }

  private static String queueDepthBucket(int n) {
    if (n == 0) return "0";
    if (n <= 5) return "1-5";
    if (n <= 20) return "6-20";
    if (n <= 100) return "21-100";
    return "100+";
  }

  // --- M2 Runtime health bucket helpers (Section C / row C4) ---

  /**
   * Scales a TPS double to int *100 for bStats line charts. NaN -> 0 so the
   * line stays continuous when a binding hasn't seeded yet. Clamped to
   * {@code [0, 2500]} (i.e. 0.00-25.00 TPS) to avoid runaway outliers from a
   * mis-implemented binding skewing dashboards.
   */
  static int scaledTps(double tps) {
    if (Double.isNaN(tps) || tps < 0.0) return 0;
    int v = (int) Math.round(tps * 100.0);
    return Math.min(v, 2500);
  }

  static String heapPressureBucket(long used, long max) {
    if (max <= 0L || used < 0L) return "unknown";
    double pct = 100.0 * used / (double) max;
    if (pct < 25.0) return "<25";
    if (pct < 50.0) return "25-50";
    if (pct < 75.0) return "50-75";
    if (pct < 90.0) return "75-90";
    return "90+";
  }

  static String tickBudgetBucket(double u) {
    if (Double.isNaN(u) || u < 0.0) return "unknown";
    if (u < 0.25) return "<25";
    if (u < 0.50) return "25-50";
    if (u < 0.75) return "50-75";
    if (u < 0.90) return "75-90";
    return "90+";
  }

  /**
   * Fingerprint-safe bucket for the number of Folia regions RTP has observed.
   * Non-Folia platforms always bucket into {@code "0"} because the
   * {@link MetricsBinding#foliaRegions()} default is an empty list.
   */
  static String foliaRegionCountBucket(int n) {
    if (n <= 0) return "0";
    if (n == 1) return "1";
    if (n <= 4) return "2-4";
    if (n <= 16) return "5-16";
    if (n <= 64) return "17-64";
    return "65+";
  }

  static String pendingTeleportsBucket(int n) {
    if (n <= 0) return "0";
    if (n <= 5) return "1-5";
    if (n <= 20) return "6-20";
    if (n <= 100) return "21-100";
    return "100+";
  }

}
