package io.github.dailystruggle.rtp.common.metrics.bstats;

import io.github.dailystruggle.bstats.api.AdvancedPie;
import io.github.dailystruggle.bstats.api.BStatsConfig;
import io.github.dailystruggle.bstats.api.BStatsService;
import io.github.dailystruggle.bstats.api.DrilldownPie;
import io.github.dailystruggle.bstats.api.MultiLineChart;
import io.github.dailystruggle.bstats.api.ServerInfo;
import io.github.dailystruggle.bstats.api.SimplePie;
import io.github.dailystruggle.bstats.api.SingleLineChart;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.LanguageBootstrap;
import io.github.dailystruggle.rtp.common.configuration.enums.BiomesKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.metrics.ChunkLoadProfile;
import io.github.dailystruggle.rtp.common.metrics.CoreMetrics;
import io.github.dailystruggle.rtp.common.metrics.RTPMetricsExtension;
import io.github.dailystruggle.rtp.common.metrics.RtpOutcomeStats;
import io.github.dailystruggle.rtp.common.metrics.RtpSchedulerProfile;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Level;

/**
 * RTP's bStats chart catalogue, shared by every platform.
 *
 * <p>Privacy constraints:
 * <ul>
 *   <li>Suppliers read {@code RTP.metrics.snapshot()} / core counters; platform
 *       facts come only through {@link Host}.</li>
 *   <li>Every numeric value is bucketised; no raw scalar that could fingerprint a
 *       server is emitted.</li>
 *   <li>No serverId, IP, hostnames, region names or world keys.</li>
 * </ul>
 */
public final class RtpBStatsCatalogue {

    private RtpBStatsCatalogue() {}

    /** Platform facts the catalogue cannot derive from core state. All calls must not throw. */
    public interface Host {
        /** Platform label for the {@code platform} pie and the MSPT-p99 drilldown outer slice. */
        String platform();

        /** {@code sync} / {@code async} / {@code unknown}: how the backend loads chunks. */
        default String chunkLoadMode() {
            return "unknown";
        }

        /** Online players, {@code -1} when unknown. */
        default int onlinePlayerCount() {
            try {
                return (RTP.metrics != null) ? RTP.metrics.snapshot().playerCount : -1;
            } catch (Throwable t) {
                return -1;
            }
        }

        /** Host game version reduced to {@code major.minor}, or {@code unknown}. */
        default String gameVersion() {
            try {
                return (RTP.serverAccessor != null) ? majorMinor(RTP.serverAccessor.getServerVersion()) : "unknown";
            } catch (Throwable t) {
                return "unknown";
            }
        }

        /** RTP plugin version, or {@code unknown}. */
        default String pluginVersion() {
            try {
                String v = (RTP.serverAccessor != null) ? RTP.serverAccessor.getPluginVersion() : null;
                return (v == null || v.isBlank()) ? "unknown" : v;
            } catch (Throwable t) {
                return "unknown";
            }
        }

        /** Whitelisted integration tally; {@code null} omits the {@code addons_loaded} chart. */
        default Map<String, Integer> addonsLoaded() {
            return null;
        }

        /**
         * Server facts for the bStats root fields. Default: online players, the
         * capitalised platform label as the software name, and
         * {@code <Name> (MC: <version>)}; {@code onlineMode} stays unknown (omitted)
         * rather than guessed.
         */
        default ServerInfo serverInfo() {
            String name = displayName(platform());
            String mc = null;
            try {
                if (RTP.serverAccessor != null) mc = RTP.serverAccessor.getServerVersion();
            } catch (Throwable ignored) {
                // unknown
            }
            return ServerInfo.of(onlinePlayerCount(), name,
                    (mc == null || mc.isBlank()) ? name : name + " (MC: " + mc + ")");
        }

        /** Whether suppliers touch a main-thread-only server API; collection then runs on the main thread. */
        default boolean collectOnMainThread() {
            return false;
        }

        /** Format of a newly created shared bStats config ({@code config.txt} unless the host's loader expects YAML). */
        default BStatsConfig.Format configFormat() {
            return BStatsConfig.Format.TEXT;
        }

        /** Host for platforms whose facts all come from core state ({@code "fabric"}, {@code "neoforge"}). */
        static Host of(String platform) {
            final String label = (platform == null || platform.isBlank())
                    ? "unknown" : platform.toLowerCase(Locale.ROOT);
            return () -> label;
        }
    }

    /** {@code fabric} -> {@code Fabric}, {@code neoforge} -> {@code NeoForge}; others capitalised. */
    static String displayName(String platform) {
        if (platform == null || platform.isBlank()) return "unknown";
        switch (platform.toLowerCase(Locale.ROOT)) {
            case "neoforge": return "NeoForge";
            default: return Character.toUpperCase(platform.charAt(0)) + platform.substring(1);
        }
    }

    /**
     * Fixed set of locales shipped with RTP. Any other configured locale collapses
     * to {@code "other"} so a custom locale name can't fingerprint the server.
     */
    public static final List<String> KNOWN_LOCALES =
            Collections.unmodifiableList(Arrays.asList(
                    "en", "cat", "de", "es", "fr", "it",
                    "ja", "ko", "nl", "pl", "pt", "ru", "zh"));

    /** Minutes retained by the RTP-cost windows; matches the ~30 min bStats cadence. */
    public static final int RTP_COST_WINDOW_MINUTES = 30;

    /** Rolling sync/region scheduler cost / RTP served. */
    public static final RtpCostWindow RTP_SYNC_COST_WINDOW = new RtpCostWindow(true);
    /** Rolling async scheduler cost / RTP served. */
    public static final RtpCostWindow RTP_ASYNC_COST_WINDOW = new RtpCostWindow(false);
    /** Rolling cost / RTP served of loading already-generated chunks. */
    public static final RtpCostWindow RTP_CHUNK_GEN_COST_WINDOW = new RtpCostWindow(true);
    /** Rolling cost / RTP served of loading ungenerated chunks (generation path). */
    public static final RtpCostWindow RTP_CHUNK_UNGEN_COST_WINDOW = new RtpCostWindow(false);

    private static volatile Object rtpCostSamplerTask;

    /**
     * Registers the full catalogue on {@code service}.
     *
     * @param assemblyVariant {@code "full"} or {@code "lite"} (ADR-024)
     */
    public static void register(BStatsService service, Host host, String assemblyVariant) {
        if (service == null || host == null) {
            RTP.log(Level.WARNING, "[RTP] bStats catalogue register called with null service/host; charts not registered");
            return;
        }
        final String variant = (assemblyVariant == null || assemblyVariant.isBlank()) ? "unknown" : assemblyVariant;

        // --- Configuration adoption ---
        service.addCustomChart(new SimplePie(BStatsChartIds.PLATFORM, host::platform));
        service.addCustomChart(new SimplePie(BStatsChartIds.ASSEMBLY_VARIANT, () -> variant));
        service.addCustomChart(new SimplePie(BStatsChartIds.DATABASE_BACKEND, RtpBStatsCatalogue::detectDatabaseBackend));
        service.addCustomChart(new AdvancedPie(BStatsChartIds.REGION_SHAPES_IN_USE,
                RtpBStatsCatalogue::detectRegionShapesInUse));
        service.addCustomChart(new SimplePie(BStatsChartIds.SAFETY_FEATURES_ENABLED,
                RtpBStatsCatalogue::detectSafetyFeaturesEnabled));
        if (host.addonsLoaded() != null) {
            service.addCustomChart(new AdvancedPie(BStatsChartIds.ADDONS_LOADED, host::addonsLoaded));
        }
        service.addCustomChart(new SimplePie(BStatsChartIds.LITE_FEATURES_DROPPED,
                () -> "lite".equalsIgnoreCase(variant) ? "lite" : "none"));
        service.addCustomChart(new SimplePie(BStatsChartIds.LANGUAGE_SELECTION, RtpBStatsCatalogue::detectLanguage));

        // --- Configuration cost ---
        service.addCustomChart(new SingleLineChart(BStatsChartIds.REGION_COUNT, RtpBStatsCatalogue::safeRegionCount));
        // L1 = keptLocations, L2 = unkeptLocations, as 0-100 fill percentages.
        service.addCustomChart(new MultiLineChart(BStatsChartIds.CACHE_POOL_HEALTH, () -> {
            Map<String, Integer> map = new HashMap<>();
            double[] fills = computeCacheFillPercentages();
            map.put("L1_kept_pct", (int) Math.round(fills[0]));
            map.put("L2_unkept_pct", (int) Math.round(fills[1]));
            return map;
        }));

        // --- Bucketised cost histograms: one 1-slice pie per submission; bStats aggregates. ---
        service.addCustomChart(new AdvancedPie(BStatsChartIds.TPS_BUCKETS,
                () -> singleBucket(tpsBucket(snapshot().tps1m))));
        service.addCustomChart(new AdvancedPie(BStatsChartIds.MSPT_BUCKETS,
                () -> singleBucket(msptBucket(snapshot().mspt))));
        service.addCustomChart(new AdvancedPie(BStatsChartIds.MEMORY_TRACKER_PRESSURE,
                () -> singleBucket(memoryTrackerBucket(rtpExt(snapshot()).memoryTrackerEntries))));
        service.addCustomChart(new AdvancedPie(BStatsChartIds.CHUNK_LOAD_BACKLOG_PRESSURE,
                () -> singleBucket(chunkBacklogBucket(rtpExt(snapshot()).chunkLoadBacklog))));
        service.addCustomChart(new AdvancedPie(BStatsChartIds.QUEUE_DEPTH_PRESSURE,
                () -> singleBucket(queueDepthBucket(rtpExt(snapshot()).queueDepth))));
        service.addCustomChart(new AdvancedPie(BStatsChartIds.PLAYER_COUNT_BUCKETS,
                () -> singleBucket(playerCountBucket(host.onlinePlayerCount()))));

        // --- RTP cost per RTP (sync/region vs async), windowed. Outer = cost component,
        // inner = bucketised ms per RTP. Chunk-load cost is folded only into the chart
        // whose family matches the backend's chunk-load mode.
        startRtpCostSampler();
        service.addCustomChart(new DrilldownPie(BStatsChartIds.RTP_SYNC_COST_PER_RTP,
                () -> rtpCostDrilldown(true, host.chunkLoadMode())));
        service.addCustomChart(new DrilldownPie(BStatsChartIds.RTP_ASYNC_COST_PER_RTP,
                () -> rtpCostDrilldown(false, host.chunkLoadMode())));

        // --- Chunk-load floor: outer = <mode>/<genstate>, inner = bucketised minimum load time.
        service.addCustomChart(new DrilldownPie(BStatsChartIds.CHUNK_LOAD_FLOOR_MS,
                () -> chunkLoadFloorDrilldown(host.chunkLoadMode())));

        // --- Runtime health ---
        // TPS as int*100 (bStats line charts are int-only); NaN -> 0.
        service.addCustomChart(new MultiLineChart(BStatsChartIds.TPS_TRENDLINES, () -> {
            Map<String, Integer> map = new HashMap<>();
            MetricsSnapshot snap = snapshot();
            map.put("tps1m_x100", scaledTps(snap.tps1m));
            map.put("tps5m_x100", scaledTps(snap.tps5m));
            map.put("tps15m_x100", scaledTps(snap.tps15m));
            return map;
        }));
        service.addCustomChart(new AdvancedPie(BStatsChartIds.HEAP_PRESSURE, () -> {
            MetricsSnapshot snap = snapshot();
            return singleBucket(heapPressureBucket(snap.heapUsedBytes, snap.heapMaxBytes));
        }));
        service.addCustomChart(new AdvancedPie(BStatsChartIds.TICK_BUDGET_UTILISATION,
                () -> singleBucket(tickBudgetBucket(snapshot().tickBudgetUtilisation))));
        service.addCustomChart(new AdvancedPie(BStatsChartIds.FOLIA_REGION_COUNT, () -> {
            List<?> regions = snapshot().foliaRegions;
            return singleBucket(foliaRegionCountBucket((regions == null) ? 0 : regions.size()));
        }));
        service.addCustomChart(new AdvancedPie(BStatsChartIds.PENDING_TELEPORTS_PRESSURE,
                () -> singleBucket(pendingTeleportsBucket(rtpExt(snapshot()).pendingTeleports))));

        // --- Aggregate outcome metrics (RtpOutcomeStats.GLOBAL), bucketised / bounded. ---
        service.addCustomChart(new AdvancedPie(BStatsChartIds.AGGREGATE_SUCCESS_RATE,
                () -> singleBucket(aggregateSuccessRateBucket())));
        service.addCustomChart(new SimplePie(BStatsChartIds.AGGREGATE_TOP_FAILURE_CAUSE,
                RtpBStatsCatalogue::detectTopFailureCause));

        // --- Host MSPT p99 (from the 1 Hz MetricsSnapshotRing), correlated to platform,
        // game version and plugin version. Both levels categorical.
        service.addCustomChart(new DrilldownPie(BStatsChartIds.MSPT_P99_BY_PLATFORM,
                () -> msptP99Drilldown(host.platform())));
        service.addCustomChart(new DrilldownPie(BStatsChartIds.MSPT_P99_BY_GAME_VERSION,
                () -> msptP99Drilldown(host.gameVersion())));
        service.addCustomChart(new DrilldownPie(BStatsChartIds.MSPT_P99_BY_PLUGIN_VERSION,
                () -> msptP99Drilldown(host.pluginVersion())));
    }

    // --- Snapshot access ---

    private static MetricsSnapshot snapshot() {
        return RTP.metrics.snapshot();
    }

    /** RTP counters on the snapshot, or a zeroed extension under the NOOP binding. Never null. */
    static RTPMetricsExtension rtpExt(MetricsSnapshot snap) {
        RTPMetricsExtension ext = (snap == null) ? null : snap.extension(RTPMetricsExtension.class);
        return (ext != null) ? ext : new RTPMetricsExtension(0, 0, 0, 0, Double.NaN, -1);
    }

    /** A one-entry {@code label -> 1} map. */
    static Map<String, Integer> singleBucket(String label) {
        Map<String, Integer> m = new HashMap<>();
        m.put(label, 1);
        return m;
    }

    // --- Detectors (no-throw; fall back to a sentinel) ---

    /** {@code "1.21.4-R0.1-SNAPSHOT"} / {@code "1.21.4"} -> {@code "1.21"}; blank -> {@code "unknown"}. */
    public static String majorMinor(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        String core = raw.trim().split("[-\\s]", 2)[0];
        String[] parts = core.split("\\.");
        if (parts.length >= 2 && parts[0].matches("\\d+") && parts[1].matches("\\d+")) {
            return parts[0] + "." + parts[1];
        }
        return core.matches("\\d+") ? core : "unknown";
    }

    public static String detectDatabaseBackend() {
        try {
            Object accessor = (RTP.getInstance() == null) ? null : RTP.getInstance().databaseAccessor;
            if (accessor == null) return "none";
            String lc = accessor.getClass().getSimpleName().toLowerCase(Locale.ROOT);
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

    /** Active locale normalised to {@link #KNOWN_LOCALES}, {@code "other"} or {@code "unknown"}. */
    public static String detectLanguage() {
        try {
            if (RTP.serverAccessor == null) return "unknown";
            String locale = LanguageBootstrap.resolve(RTP.serverAccessor.getPluginDirectory());
            if (locale == null || locale.isBlank()) return "unknown";
            String lc = locale.toLowerCase(Locale.ROOT);
            return KNOWN_LOCALES.contains(lc) ? lc : "other";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** Shape class simple names tallied across regions; empty (chart skipped) when not ready. */
    public static Map<String, Integer> detectRegionShapesInUse() {
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
                    // label stays "unknown"
                }
                tally.merge(label, 1, Integer::sum);
            }
        } catch (Throwable ignored) {
            // chart must never throw
        }
        return tally;
    }

    /**
     * Sorted slug of non-default safety toggles, or {@code "default"}. Reads enum-keyed
     * booleans only (no list contents, radii or material names).
     */
    @SuppressWarnings("unchecked")
    public static String detectSafetyFeaturesEnabled() {
        try {
            if (RTP.getInstance() == null || RTP.configs == null) return "unknown";
            ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
            if (safety == null) return "unknown";
            TreeSet<String> on = new TreeSet<>();
            Object anvil = safety.getConfigValue(SafetyKeys.anvilPrefilterEnabled, Boolean.TRUE);
            if (!Boolean.parseBoolean(String.valueOf(anvil))) on.add("anvil_prefilter_off");
            Object biome = RTP.configs.getConfigValue(BiomesKeys.biomeWhitelist, Boolean.FALSE);
            if (Boolean.parseBoolean(String.valueOf(biome))) on.add("biome_whitelist");
            return on.isEmpty() ? "default" : String.join("+", on);
        } catch (Throwable t) {
            return "unknown";
        }
    }

    static int safeRegionCount() {
        try {
            if (RTP.selectionAPI == null || RTP.selectionAPI.permRegionLookup == null) return 0;
            return RTP.selectionAPI.permRegionLookup.size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** {@code [L1_pct, L2_pct]} across configured regions. */
    static double[] computeCacheFillPercentages() {
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
            // chart must never throw
        }
        double l1 = (keptCap > 0) ? 100.0 * keptUsed / keptCap : 0.0;
        double l2 = (unkeptCap > 0) ? 100.0 * unkeptUsed / unkeptCap : 0.0;
        return new double[] {l1, l2};
    }

    /** Bucketised {@link RtpOutcomeStats#GLOBAL} success rate; never a raw count. */
    public static String aggregateSuccessRateBucket() {
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

    /** {@link LocationGenerator.FailTypes} with the most rejects, or {@code "none"}. */
    public static String detectTopFailureCause() {
        try {
            LocationGenerator.FailTypes top = null;
            long best = 0L;
            for (Map.Entry<LocationGenerator.FailTypes, Long> e : RtpOutcomeStats.GLOBAL.failureBreakdown().entrySet()) {
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

    /** Point-in-time p99 of host MSPT from the core snapshot ring; NaN when unavailable. */
    public static double msptP99Ms() {
        try {
            if (RTP.metrics instanceof CoreMetrics) {
                return percentile(((CoreMetrics) RTP.metrics).snapshotRing().msptSnapshot(), 0.99);
            }
        } catch (Throwable ignored) {
            // fall through to NaN
        }
        return Double.NaN;
    }

    static Map<String, Map<String, Integer>> msptP99Drilldown(String outer) {
        Map<String, Map<String, Integer>> map = new HashMap<>();
        map.put((outer == null || outer.isBlank()) ? "unknown" : outer, singleBucket(msptP99Bucket(msptP99Ms())));
        return map;
    }

    /** Nearest-rank percentile skipping NaN slots; NaN when no finite samples. */
    static double percentile(double[] samples, double q) {
        if (samples == null || samples.length == 0) return Double.NaN;
        double[] finite = new double[samples.length];
        int n = 0;
        for (double v : samples) {
            if (!Double.isNaN(v)) finite[n++] = v;
        }
        if (n == 0) return Double.NaN;
        double[] sorted = Arrays.copyOf(finite, n);
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(q * n) - 1;
        if (rank < 0) rank = 0;
        if (rank >= n) rank = n - 1;
        return sorted[rank];
    }

    // --- RTP cost windows ---

    /**
     * Starts the 1-minute RTP-cost sampler on {@link RTP#scheduler}. Idempotent; no-op
     * without a scheduler; the body never propagates onto the scheduler thread.
     */
    static void startRtpCostSampler() {
        synchronized (RtpBStatsCatalogue.class) {
            try {
                if (rtpCostSamplerTask != null || RTP.scheduler == null) return;
                rtpCostSamplerTask = RTP.scheduler.runTaskTimerAsynchronously(() -> {
                    try {
                        long rtp = RtpOutcomeStats.GLOBAL.successCount();
                        RTP_SYNC_COST_WINDOW.sample(RtpSchedulerProfile.GLOBAL.syncNanos(), rtp);
                        RTP_ASYNC_COST_WINDOW.sample(RtpSchedulerProfile.GLOBAL.asyncNanos(), rtp);
                        RTP_CHUNK_GEN_COST_WINDOW.sample(ChunkLoadProfile.GLOBAL.totalNanos(true), rtp);
                        RTP_CHUNK_UNGEN_COST_WINDOW.sample(ChunkLoadProfile.GLOBAL.totalNanos(false), rtp);
                    } catch (Throwable ignored) {
                        // sampler must never poison the scheduler thread
                    }
                }, 1200L, 1200L);
            } catch (Throwable t) {
                RTP.log(Level.WARNING, "[RTP] failed to start rtp-cost sampler", t);
            }
        }
    }

    /**
     * Rolling 1-minute-delta accumulator of a cumulative nanosecond counter against
     * the cumulative RTP-served counter. Summing deltas is exact regardless of
     * cadence; {@link #msPerRtp()} is total ns / total RTP. Thread-safe.
     */
    public static final class RtpCostWindow {
        private final boolean sync;
        private final int minutes;
        private final long[] nanosByMinute;
        private final long[] rtpByMinute;
        private final boolean[] filled;
        private int cursor = 0;
        private long lastCumulativeNanos = -1L;
        private long lastCumulativeRtp = -1L;

        public RtpCostWindow(boolean sync) {
            this(sync, RTP_COST_WINDOW_MINUTES);
        }

        public RtpCostWindow(boolean sync, int minutes) {
            this.sync = sync;
            this.minutes = minutes;
            this.nanosByMinute = new long[minutes];
            this.rtpByMinute = new long[minutes];
            this.filled = new boolean[minutes];
        }

        /** Whether this window is fed by the sync/region (true) or async (false) counter. */
        public boolean isSync() {
            return sync;
        }

        /** Records one minute; the first call only seeds the baselines. */
        public void sample(long cumulativeNanos, long cumulativeRtp) {
            synchronized (this) {
                long nanosDelta;
                long rtpDelta;
                if (lastCumulativeNanos < 0L) {
                    nanosDelta = 0L;
                    rtpDelta = 0L;
                } else {
                    nanosDelta = Math.max(0L, cumulativeNanos - lastCumulativeNanos);
                    rtpDelta = Math.max(0L, cumulativeRtp - lastCumulativeRtp);
                }
                lastCumulativeNanos = cumulativeNanos;
                lastCumulativeRtp = cumulativeRtp;
                nanosByMinute[cursor] = nanosDelta;
                rtpByMinute[cursor] = rtpDelta;
                filled[cursor] = true;
                cursor = (cursor + 1) % minutes;
            }
        }

        /** Mean ms per RTP over the window; NaN when unsampled or no RTP served. */
        public double msPerRtp() {
            synchronized (this) {
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
        }

        /** Test hook: clears the window. */
        public void reset() {
            synchronized (this) {
                Arrays.fill(nanosByMinute, 0L);
                Arrays.fill(rtpByMinute, 0L);
                Arrays.fill(filled, false);
                cursor = 0;
                lastCumulativeNanos = -1L;
                lastCumulativeRtp = -1L;
            }
        }
    }

    /**
     * Per-RTP cost drilldown: {@code scheduler} always; {@code chunk_generated} /
     * {@code chunk_ungenerated} only when {@code sync} matches {@code chunkLoadMode}.
     */
    public static Map<String, Map<String, Integer>> rtpCostDrilldown(boolean sync, String chunkLoadMode) {
        Map<String, Map<String, Integer>> map = new HashMap<>();
        RtpCostWindow scheduler = sync ? RTP_SYNC_COST_WINDOW : RTP_ASYNC_COST_WINDOW;
        map.put("scheduler", singleBucket(rtpCostBucket(scheduler.msPerRtp())));
        boolean modeMatches = ("async".equals(chunkLoadMode) && !sync) || ("sync".equals(chunkLoadMode) && sync);
        if (modeMatches) {
            map.put("chunk_generated", singleBucket(rtpCostBucket(RTP_CHUNK_GEN_COST_WINDOW.msPerRtp())));
            map.put("chunk_ungenerated", singleBucket(rtpCostBucket(RTP_CHUNK_UNGEN_COST_WINDOW.msPerRtp())));
        }
        return map;
    }

    /**
     * Chunk-load floor drilldown: outer {@code <mode>/<genstate>} per recorded class;
     * a single {@code <mode>/generated -> unknown} placeholder when nothing recorded.
     */
    public static Map<String, Map<String, Integer>> chunkLoadFloorDrilldown(String chunkLoadMode) {
        String mode = (chunkLoadMode == null || chunkLoadMode.isBlank()) ? "unknown" : chunkLoadMode;
        Map<String, Map<String, Integer>> map = new HashMap<>();
        long genFloor = ChunkLoadProfile.GLOBAL.minNanos(true);
        long ungenFloor = ChunkLoadProfile.GLOBAL.minNanos(false);
        if (genFloor >= 0L) map.put(mode + "/generated", singleBucket(chunkLoadFloorBucket(genFloor)));
        if (ungenFloor >= 0L) map.put(mode + "/ungenerated", singleBucket(chunkLoadFloorBucket(ungenFloor)));
        if (map.isEmpty()) map.put(mode + "/generated", singleBucket(chunkLoadFloorBucket(-1L)));
        return map;
    }

    // --- Bucket functions (labels are dashboard keys: renaming one breaks the chart) ---

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

    public static String msptP99Bucket(double ms) {
        if (Double.isNaN(ms) || ms < 0.0) return "unknown";
        if (ms < 25.0) return "<25";
        if (ms < 50.0) return "25-50";
        if (ms < 100.0) return "50-100";
        if (ms < 250.0) return "100-250";
        if (ms < 1000.0) return "250-1000";
        return "1000+";
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

    public static String rtpCostBucket(double ms) {
        if (Double.isNaN(ms) || ms < 0.0) return "unknown";
        if (ms < 0.1) return "<0.1";
        if (ms < 0.5) return "0.1-0.5";
        if (ms < 2.0) return "0.5-2";
        if (ms < 10.0) return "2-10";
        if (ms < 50.0) return "10-50";
        return "50+";
    }

    /** {@code -1} (no load recorded) -> {@code unknown}; otherwise bucketised ms. */
    public static String chunkLoadFloorBucket(long minNanos) {
        if (minNanos < 0L) return "unknown";
        return rtpCostBucket(minNanos / 1.0e6);
    }

    /** TPS as int*100, NaN/negative -> 0, clamped to 2500 (25.00 TPS). */
    public static int scaledTps(double tps) {
        if (Double.isNaN(tps) || tps < 0.0) return 0;
        return Math.min((int) Math.round(tps * 100.0), 2500);
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

    public static String tickBudgetBucket(double u) {
        if (Double.isNaN(u) || u < 0.0) return "unknown";
        if (u < 0.25) return "<25";
        if (u < 0.50) return "25-50";
        if (u < 0.75) return "50-75";
        if (u < 0.90) return "75-90";
        return "90+";
    }

    /** Non-Folia platforms always bucket to {@code "0"} (empty region list). */
    public static String foliaRegionCountBucket(int n) {
        if (n <= 0) return "0";
        if (n == 1) return "1";
        if (n <= 4) return "2-4";
        if (n <= 16) return "5-16";
        if (n <= 64) return "17-64";
        return "65+";
    }

    public static String pendingTeleportsBucket(int n) {
        if (n <= 0) return "0";
        if (n <= 5) return "1-5";
        if (n <= 20) return "6-20";
        if (n <= 100) return "21-100";
        return "100+";
    }
}
