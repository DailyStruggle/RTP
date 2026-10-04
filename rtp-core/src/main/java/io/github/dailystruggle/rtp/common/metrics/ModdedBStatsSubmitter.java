package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.LanguageBootstrap;
import io.github.dailystruggle.rtp.common.configuration.enums.BiomesKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.logging.Level;
import java.util.zip.GZIPOutputStream;

/**
 * Lightweight, zero-dependency bStats v2 HTTP submitter for modded platforms (Fabric and NeoForge).
 *
 * <p>Runs strictly via {@link RTP#scheduler} async timers (S-005) on a 30-minute interval,
 * posting gzip-compressed JSON payloads directly to the official bStats v2 endpoint.
 *
 * <p>Reuses the exact same privacy and anti-fingerprinting safeguards established for Bukkit:
 * bucketised player counts, TPS, MSPT, heap, and whitelisted slugs.
 */
public final class ModdedBStatsSubmitter {

    public static final int BSTATS_PLUGIN_ID = 30865;
    private static final String BSTATS_URL = "https://bstats.org/api/v2/data/" + BSTATS_PLUGIN_ID;

    // 30 minutes in server ticks (20 ticks/sec * 60 * 30 = 36000 ticks)
    private static final long INTERVAL_TICKS = 36000L;
    // Initial delay of 3 minutes before first submission to let the server stabilize
    private static final long INITIAL_DELAY_TICKS = 3600L;

    private static final List<String> KNOWN_LOCALES = List.of(
            "en", "cat", "de", "es", "fr", "it",
            "ja", "ko", "nl", "pl", "pt", "ru", "zh");

    private static volatile boolean started = false;

    private final String platform;
    private final File pluginDirectory;
    private final HttpClient httpClient;
    private final String serverUuid;

    public ModdedBStatsSubmitter(String platform, File pluginDirectory) {
        this.platform = (platform == null || platform.isBlank()) ? "modded" : platform.toLowerCase(Locale.ROOT);
        this.pluginDirectory = pluginDirectory;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.serverUuid = resolveOrGenerateServerUuid(pluginDirectory);
    }

    /**
     * Starts the periodic bStats submitter for the given modded platform if not already started.
     *
     * @param platform platform identifier ("fabric" or "neoforge")
     * @param pluginDirectory the plugin/mod working directory
     */
    public static synchronized void start(String platform, File pluginDirectory) {
        if (started) {
            return;
        }
        started = true;

        ModdedBStatsSubmitter submitter = new ModdedBStatsSubmitter(platform, pluginDirectory);
        if (submitter.isOptedOut()) {
            RTP.log(Level.INFO, "[RTP][bStats] Metrics collection is disabled by operator configuration.");
            return;
        }

        try {
            if (RTP.scheduler != null) {
                RTP.scheduler.runTaskTimerAsynchronously(
                        submitter::submit,
                        INITIAL_DELAY_TICKS,
                        INTERVAL_TICKS
                );
                RTP.log(Level.INFO, "[RTP][bStats] Enabled bStats telemetry for " + platform + " (id=" + BSTATS_PLUGIN_ID + ").");
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP][bStats] Failed to schedule bStats submitter: " + t.getMessage());
        }
    }

    /**
     * Submits a single metrics snapshot to bStats v2. Fail-soft; never throws.
     */
    public void submit() {
        if (isOptedOut()) {
            return;
        }

        try {
            String jsonPayload = buildJsonPayload();
            byte[] compressed = gzip(jsonPayload.getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BSTATS_URL))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Content-Encoding", "gzip")
                    .header("User-Agent", "RTP-Metrics")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(compressed))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((resp, ex) -> {
                        if (ex != null) {
                            RTP.log(Level.FINER, "[RTP][bStats] Submission failed: " + ex.getMessage());
                        } else if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                            RTP.log(Level.FINER, "[RTP][bStats] Submission returned HTTP " + resp.statusCode());
                        } else {
                            RTP.log(Level.FINE, "[RTP][bStats] Metrics successfully submitted to bStats (HTTP " + resp.statusCode() + ").");
                        }
                    });
        } catch (Throwable t) {
            RTP.log(Level.FINER, "[RTP][bStats] Submission error: " + t.getMessage());
        }
    }

    /**
     * Checks whether the operator has globally or locally opted out of bStats.
     */
    public boolean isOptedOut() {
        File[] candidateConfigFiles = new File[] {
                new File(pluginDirectory, "bStats/config.txt"),
                new File(pluginDirectory, "../bStats/config.txt"),
                new File("plugins/bStats/config.txt"),
                new File("config/bStats/config.txt"),
                new File(".bStats/config.txt")
        };

        for (File cfg : candidateConfigFiles) {
            if (cfg.exists() && cfg.isFile()) {
                try {
                    List<String> lines = Files.readAllLines(cfg.toPath(), StandardCharsets.UTF_8);
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("enabled=") || trimmed.startsWith("enabled =")) {
                            String[] parts = trimmed.split("=", 2);
                            if (parts.length == 2 && !Boolean.parseBoolean(parts[1].trim())) {
                                return true;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // Ignored: failure reading bStats config file, continue checking other locations
                }
            }
        }

        return false;
    }

    /**
     * Builds the complete bStats v2 JSON payload.
     */
    public String buildJsonPayload() {
        MetricsSnapshot snap = (RTP.metrics != null) ? RTP.metrics.snapshot() : null;
        RTPMetricsExtension ext = (snap != null) ? snap.extension(RTPMetricsExtension.class) : null;
        if (ext == null) {
            ext = new RTPMetricsExtension(0, 0, 0, 0, Double.NaN, -1);
        }

        double tps1m = (snap != null) ? snap.tps1m : Double.NaN;
        double tps5m = (snap != null) ? snap.tps5m : Double.NaN;
        double tps15m = (snap != null) ? snap.tps15m : Double.NaN;
        double mspt = (snap != null) ? snap.mspt : Double.NaN;
        int playerCount = (snap != null) ? snap.playerCount : -1;
        long heapUsed = (snap != null) ? snap.heapUsedBytes : 0L;
        long heapMax = (snap != null) ? snap.heapMaxBytes : 0L;

        StringBuilder sb = new StringBuilder(2048);
        sb.append("{");
        sb.append("\"serverUUID\":").append(quote(serverUuid)).append(",");
        sb.append("\"metricsVersion\":2,");
        sb.append("\"service\":{\"id\":").append(BSTATS_PLUGIN_ID).append("},");

        sb.append("\"data\":{");
        // Standard bStats system attributes
        sb.append("\"osName\":").append(quote(System.getProperty("os.name", "unknown"))).append(",");
        sb.append("\"osArch\":").append(quote(System.getProperty("os.arch", "unknown"))).append(",");
        sb.append("\"osVersion\":").append(quote(System.getProperty("os.version", "unknown"))).append(",");
        sb.append("\"coreCount\":").append(Runtime.getRuntime().availableProcessors()).append(",");
        sb.append("\"javaVersion\":").append(quote(System.getProperty("java.version", "unknown"))).append(",");

        // Custom charts
        sb.append("\"customCharts\":[");
        List<String> charts = new ArrayList<>();

        // SimplePie: platform
        charts.add(simplePie("platform", platform));
        // SimplePie: assembly_variant
        charts.add(simplePie("assembly_variant", "full"));
        // SimplePie: database_backend
        charts.add(simplePie("database_backend", detectDatabaseBackend()));
        // SimplePie: safety_features_enabled
        charts.add(simplePie("safety_features_enabled", detectSafetyFeaturesEnabled()));
        // SimplePie: lite_features_dropped
        charts.add(simplePie("lite_features_dropped", "none"));
        // SimplePie: language_selection
        charts.add(simplePie("language_selection", detectLanguage()));

        // SingleLineChart: region_count
        charts.add(singleLineChart("region_count", safeRegionCount()));

        // AdvancedPie: region_shapes_in_use
        charts.add(advancedPie("region_shapes_in_use", detectRegionShapesInUse()));

        // MultiLineChart: cache_pool_health
        Map<String, Integer> cacheMap = new HashMap<>();
        double[] fills = computeCacheFillPercentages();
        cacheMap.put("L1_kept_pct", (int) Math.round(fills[0]));
        cacheMap.put("L2_unkept_pct", (int) Math.round(fills[1]));
        charts.add(multiLineChart("cache_pool_health", cacheMap));

        // AdvancedPie: tps_buckets
        charts.add(advancedPie("tps_buckets", Map.of(tpsBucket(tps1m), 1)));
        // AdvancedPie: mspt_buckets
        charts.add(advancedPie("mspt_buckets", Map.of(msptBucket(mspt), 1)));
        // AdvancedPie: memory_tracker_pressure
        charts.add(advancedPie("memory_tracker_pressure", Map.of(memoryTrackerBucket(ext.memoryTrackerEntries), 1)));
        // AdvancedPie: chunk_load_backlog_pressure
        charts.add(advancedPie("chunk_load_backlog_pressure", Map.of(chunkBacklogBucket(ext.chunkLoadBacklog), 1)));
        // AdvancedPie: queue_depth_pressure
        charts.add(advancedPie("queue_depth_pressure", Map.of(queueDepthBucket(ext.queueDepth), 1)));
        // AdvancedPie: player_count_buckets
        charts.add(advancedPie("player_count_buckets", Map.of(playerCountBucket(playerCount), 1)));

        // MultiLineChart: tps_trendlines
        Map<String, Integer> tpsMap = new HashMap<>();
        tpsMap.put("tps1m_x100", scaledTps(tps1m));
        tpsMap.put("tps5m_x100", scaledTps(tps5m));
        tpsMap.put("tps15m_x100", scaledTps(tps15m));
        charts.add(multiLineChart("tps_trendlines", tpsMap));

        // AdvancedPie: heap_pressure
        charts.add(advancedPie("heap_pressure", Map.of(heapPressureBucket(heapUsed, heapMax), 1)));

        sb.append(String.join(",", charts));
        sb.append("]");

        sb.append("}"); // end data
        sb.append("}"); // end root

        return sb.toString();
    }

    private static String simplePie(String chartId, String value) {
        String safeVal = (value == null || value.isBlank()) ? "unknown" : value;
        return "{\"chartId\":\"" + chartId + "\",\"data\":{\"value\":" + quote(safeVal) + "}}";
    }

    private static String singleLineChart(String chartId, int value) {
        return "{\"chartId\":\"" + chartId + "\",\"data\":{\"value\":" + value + "}}";
    }

    private static String multiLineChart(String chartId, Map<String, Integer> values) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"chartId\":\"").append(chartId).append("\",\"data\":{\"values\":{");
        int count = 0;
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            if (count++ > 0) sb.append(",");
            sb.append(quote(entry.getKey())).append(":").append(entry.getValue());
        }
        sb.append("}}}");
        return sb.toString();
    }

    private static String advancedPie(String chartId, Map<String, Integer> slices) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"chartId\":\"").append(chartId).append("\",\"data\":{\"values\":{");
        int count = 0;
        for (Map.Entry<String, Integer> entry : slices.entrySet()) {
            if (count++ > 0) sb.append(",");
            sb.append(quote(entry.getKey())).append(":").append(entry.getValue());
        }
        sb.append("}}}");
        return sb.toString();
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private String detectDatabaseBackend() {
        try {
            Object accessor = (RTP.getInstance() == null) ? null : RTP.getInstance().databaseAccessor;
            if (accessor == null) return "none";
            String cls = accessor.getClass().getSimpleName();
            String lc = cls.toLowerCase(Locale.ROOT);
            if (lc.contains("yaml")) return "yaml";
            if (lc.contains("h2")) return "h2";
            if (lc.contains("sqlite")) return "sqlite";
            if (lc.contains("postgres")) return "postgresql";
            if (lc.contains("mysql") || lc.contains("mariadb")) return "mysql";
            return "other";
        } catch (Throwable ignored) {
            // Ignored: fallback to default database backend when detection fails
        }
        return "sqlite";
    }

    @SuppressWarnings("unchecked")
    private String detectSafetyFeaturesEnabled() {
        try {
            if (RTP.getInstance() == null || RTP.configs == null) return "unknown";
            ConfigParser<SafetyKeys> safety =
                    (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
            if (safety == null) return "unknown";
            TreeSet<String> on = new TreeSet<>();
            Object anvil = safety.getConfigValue(SafetyKeys.anvilPrefilterEnabled, Boolean.TRUE);
            if (!Boolean.parseBoolean(String.valueOf(anvil))) on.add("anvil_prefilter_off");
            Object biome = RTP.configs.getConfigValue(BiomesKeys.biomeWhitelist, Boolean.FALSE);
            if (Boolean.parseBoolean(String.valueOf(biome))) on.add("biome_whitelist");
            if (on.isEmpty()) return "default";
            return String.join("+", on);
        } catch (Throwable ignored) {
            // Ignored: fallback to default safety features on error
        }
        return "default";
    }

    private String detectLanguage() {
        try {
            if (pluginDirectory == null) return "unknown";
            String locale = LanguageBootstrap.resolve(pluginDirectory);
            if (locale == null || locale.isBlank()) return "unknown";
            String lc = locale.toLowerCase(Locale.ROOT);
            return KNOWN_LOCALES.contains(lc) ? lc : "other";
        } catch (Throwable ignored) {
            // Ignored: fallback to default language on error
        }
        return "en";
    }

    private int safeRegionCount() {
        try {
            if (RTP.selectionAPI == null || RTP.selectionAPI.permRegionLookup == null) return 0;
            return RTP.selectionAPI.permRegionLookup.size();
        } catch (Throwable ignored) {
            // Ignored: return 0 if region lookup is unavailable
            return 0;
        }
    }

    private Map<String, Integer> detectRegionShapesInUse() {
        Map<String, Integer> tally = new HashMap<>();
        try {
            if (RTP.selectionAPI == null || RTP.selectionAPI.permRegionLookup == null) return tally;
            for (var region : RTP.selectionAPI.permRegionLookup.values()) {
                if (region == null) continue;
                String label = "unknown";
                try {
                    Object shape = region.getData(RegionKeys.shape);
                    if (shape != null) {
                        label = shape.getClass().getSimpleName().toLowerCase(Locale.ROOT);
                        if (label.isEmpty()) label = "unknown";
                    }
                } catch (Throwable ignored) {
                    // Ignored: shape retrieval failed for region
                }
                tally.merge(label, 1, Integer::sum);
            }
        } catch (Throwable ignored) {
            // Ignored: region lookup failed
        }
        if (tally.isEmpty()) {
            tally.put("none", 1);
        }
        return tally;
    }

    private double[] computeCacheFillPercentages() {
        long keptUsed = 0L;
        long keptCap = 0L;
        long unkeptUsed = 0L;
        long unkeptCap = 0L;
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
            // Ignored: cache inspection failed
        }
        double l1 = (keptCap > 0) ? 100.0 * keptUsed / keptCap : 0.0;
        double l2 = (unkeptCap > 0) ? 100.0 * unkeptUsed / unkeptCap : 0.0;
        return new double[] {l1, l2};
    }

    // --- Bucketisers mirroring RTPCostMetricsCharts (Anti-fingerprinting) ---

    public static String tpsBucket(double tps) {
        if (Double.isNaN(tps)) return "unknown";
        if (tps < 10.0) return "<10";
        if (tps < 15.0) return "10-15";
        if (tps < 19.0) return "15-19";
        return "19-20+";
    }

    public static String msptBucket(double mspt) {
        if (Double.isNaN(mspt)) return "unknown";
        if (mspt < 25.0) return "<25";
        if (mspt < 50.0) return "25-50";
        if (mspt < 100.0) return "50-100";
        return "100+";
    }

    public static String memoryTrackerBucket(int n) {
        if (n < 10) return "<10";
        if (n < 50) return "10-50";
        if (n < 200) return "50-200";
        return "200+";
    }

    public static String chunkBacklogBucket(int n) {
        if (n == 0) return "0";
        if (n <= 5) return "1-5";
        if (n <= 20) return "6-20";
        return "21+";
    }

    public static String queueDepthBucket(int n) {
        if (n == 0) return "0";
        if (n <= 5) return "1-5";
        if (n <= 20) return "6-20";
        if (n <= 100) return "21-100";
        return "100+";
    }

    public static String playerCountBucket(int n) {
        if (n < 0) return "unknown";
        if (n == 0) return "0";
        if (n <= 5) return "1-5";
        if (n <= 20) return "6-20";
        if (n <= 50) return "21-50";
        if (n <= 100) return "51-100";
        if (n <= 250) return "101-250";
        return "250+";
    }

    public static String heapPressureBucket(long used, long max) {
        if (max <= 0L || used < 0L) return "unknown";
        double pct = 100.0 * used / (double) max;
        if (pct < 25.0) return "<25";
        if (pct < 50.0) return "25-50";
        if (pct < 75.0) return "50-75";
        if (pct < 90.0) return "75-90";
        return "90+";
    }

    public static int scaledTps(double tps) {
        if (Double.isNaN(tps) || tps < 0.0) return 0;
        int v = (int) Math.round(tps * 100.0);
        return Math.min(v, 2500);
    }

    private static byte[] gzip(byte[] input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(input);
        }
        return baos.toByteArray();
    }

    private static String resolveOrGenerateServerUuid(File pluginDirectory) {
        try {
            File bstatsDir = new File(pluginDirectory != null ? pluginDirectory.getParentFile() : new File("config"), "bStats");
            if (!bstatsDir.exists()) {
                bstatsDir.mkdirs();
            }
            File uuidFile = new File(bstatsDir, "config.txt");
            if (uuidFile.exists()) {
                List<String> lines = Files.readAllLines(uuidFile.toPath(), StandardCharsets.UTF_8);
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("serverUuid=") || trimmed.startsWith("serverUuid =")) {
                        String[] parts = trimmed.split("=", 2);
                        if (parts.length == 2 && !parts[1].isBlank()) {
                            return parts[1].trim();
                        }
                    }
                }
            } else {
                String newUuid = UUID.randomUUID().toString();
                String content = "# bStats configuration file\nenabled=true\nserverUuid=" + newUuid + "\nlogFailedRequests=false\n";
                Files.writeString(uuidFile.toPath(), content, StandardCharsets.UTF_8);
                return newUuid;
            }
        } catch (Throwable ignored) {
            // Ignored: fallback to ephemeral UUID when file IO fails
        }
        return UUID.randomUUID().toString();
    }
}
