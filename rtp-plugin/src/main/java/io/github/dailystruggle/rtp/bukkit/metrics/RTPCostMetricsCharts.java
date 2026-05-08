package io.github.dailystruggle.rtp.bukkit.metrics;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.metrics.MetricsSnapshot;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.MultiLineChart;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Registers RTP's bStats chart catalogue (Phase M1+M2 cost-metrics subset) on
 * the Bukkit-family bStats {@link Metrics} instance owned by the plugin.
 *
 * <p>Per {@code docs/dev/METRICS_PLAN.md > bStats Integration}:
 * <ul>
 *   <li>All lambdas read pre-cached values from
 *       {@link io.github.dailystruggle.rtp.common.metrics.Metrics#snapshot()};
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
      RTP.log(Level.WARNING, "[bStats] register called with null Metrics; charts not registered");
      return;
    }
    final String variant = (assemblyVariant == null || assemblyVariant.isBlank()) ? "unknown" : assemblyVariant;

    // --- Configuration adoption ---
    metrics.addCustomChart(new SimplePie(BStatsChartIds.PLATFORM, RTPCostMetricsCharts::detectPlatform));
    metrics.addCustomChart(new SimplePie(BStatsChartIds.ASSEMBLY_VARIANT, () -> variant));
    metrics.addCustomChart(new SimplePie(BStatsChartIds.DATABASE_BACKEND, RTPCostMetricsCharts::detectDatabaseBackend));

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

    metrics.addCustomChart(new AdvancedPie(BStatsChartIds.PIPELINE_LATENCY_BUCKETS, () -> {
      Map<String, Integer> b = new HashMap<>();
      double avg = RTP.metrics.snapshot().avgPipelineMs;
      b.put(pipelineLatencyBucket(avg), 1);
      return b;
    }));

    metrics.addCustomChart(new AdvancedPie(BStatsChartIds.MEMORY_TRACKER_PRESSURE, () -> {
      Map<String, Integer> b = new HashMap<>();
      int n = RTP.metrics.snapshot().memoryTrackerEntries;
      b.put(memoryTrackerBucket(n), 1);
      return b;
    }));

    metrics.addCustomChart(new AdvancedPie(BStatsChartIds.CHUNK_LOAD_BACKLOG_PRESSURE, () -> {
      Map<String, Integer> b = new HashMap<>();
      int n = RTP.metrics.snapshot().chunkLoadBacklog;
      b.put(chunkBacklogBucket(n), 1);
      return b;
    }));

    metrics.addCustomChart(new AdvancedPie(BStatsChartIds.QUEUE_DEPTH_PRESSURE, () -> {
      Map<String, Integer> b = new HashMap<>();
      int n = RTP.metrics.snapshot().queueDepth;
      b.put(queueDepthBucket(n), 1);
      return b;
    }));
  }

  // --- Detection helpers (no-throw; log-and-fallback so a chart fail never breaks startup) ---

  private static String detectPlatform() {
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
    double tps = RTP.metrics.snapshot().tps1m;
    if (Double.isNaN(tps)) {
      // Fall back to Paper's API directly if the metrics binding isn't wired
      // yet (Phase M1 partial). This is the same probe a future PaperBinding
      // would use.
      try {
        // Bukkit.getServer().getTPS() exists on Paper / Spigot 1.20+ but not on
        // the lowest-common-denominator Bukkit API artifact this module compiles
        // against. Reflective lookup keeps the classpath stable and degrades to
        // NaN on hosts that don't expose the method.
        var m = Bukkit.getServer().getClass().getMethod("getTPS");
        Object api = m.invoke(Bukkit.getServer());
        if (api instanceof double[] arr && arr.length > 0) tps = arr[0];
      } catch (Throwable ignored) {
        // tps stays NaN; bucket falls into "unknown".
      }
    }
    return tps;
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

  private static String pipelineLatencyBucket(double ms) {
    if (Double.isNaN(ms)) return "unknown";
    if (ms < 100.0) return "<100";
    if (ms < 500.0) return "100-500";
    if (ms < 2000.0) return "500-2000";
    return "2000+";
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

}
