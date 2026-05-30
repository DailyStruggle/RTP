package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.common.metrics.RTPMetricsExtension;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.ScanTask;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlaceholderProvider {
    public static final Map<String, Function<UUID, String>> placeholders = new ConcurrentHashMap<>();

    /**
     * Per-thread cache for a single {@link MetricsSnapshot} so that all metrics-backed
     * placeholders in one message-formatting pass observe the same atomic snapshot and
     * the underlying {@code RTP.metrics.snapshot()} (and its binding) is invoked exactly
     * once per caller-defined scope (e.g. one {@code /rtp info} invocation).
     *
     * <p>Callers wrap their formatting block with {@link #withSnapshot(Runnable)}
     * or {@link #pushSnapshot(MetricsSnapshot)} / {@link #popSnapshot()}. Placeholders that
     * read metrics call {@link #currentSnapshot()}, which lazily falls back to
     * {@code RTP.metrics.snapshot()} when no caller-supplied snapshot is in scope.
     *
     * <p>Plan reference: {@code docs/dev/METRICS_PLAN.md} — "one snapshot per
     * {@code /rtp info} invocation" (Section M1 / row B11).
     */
    private static final ThreadLocal<MetricsSnapshot> METRICS_SNAPSHOT_CACHE = new ThreadLocal<>();

    /** Install a snapshot for the current thread; pair with {@link #popSnapshot()}. */
    public static void pushSnapshot(MetricsSnapshot snap) {
        METRICS_SNAPSHOT_CACHE.set(snap);
    }

    /** Clear any snapshot installed for the current thread. */
    public static void popSnapshot() {
        METRICS_SNAPSHOT_CACHE.remove();
    }

    /**
     * Return the snapshot in scope for the current thread, or a fresh
     * {@code RTP.metrics.snapshot()} if none. Never returns {@code null}.
     */
    public static MetricsSnapshot currentSnapshot() {
        MetricsSnapshot snap = METRICS_SNAPSHOT_CACHE.get();
        if (snap != null) return snap;
        return RTP.metrics.snapshot();
    }

    /**
     * Return the RTP-specific extension on the current snapshot, or a zeroed
     * fallback if absent (e.g. NOOP binding). Never returns {@code null}.
     */
    private static RTPMetricsExtension currentRtpExt() {
        RTPMetricsExtension ext = currentSnapshot().extension(RTPMetricsExtension.class);
        return (ext != null) ? ext : new RTPMetricsExtension(0, 0, 0, 0, Double.NaN, -1);
    }

    /**
     * Point-in-time pipeline-latency percentiles (ADR-053 §1). Reads the
     * {@code CoreMetrics}-owned {@link io.github.dailystruggle.rtp.common.metrics.PipelineHistogram}
     * when the live {@code Metrics} is the core implementation; otherwise returns an all-{@code NaN}
     * (zero-sample) snapshot. Never returns {@code null}.
     */
    private static io.github.dailystruggle.rtp.common.metrics.PipelineHistogram.Percentiles currentPercentiles() {
        try {
            if (RTP.metrics instanceof io.github.dailystruggle.rtp.common.metrics.CoreMetrics) {
                return ((io.github.dailystruggle.rtp.common.metrics.CoreMetrics) RTP.metrics)
                        .pipelineHistogram().percentiles();
            }
        } catch (Throwable ignored) {
            // fall through to the empty readout
        }
        return new io.github.dailystruggle.rtp.common.metrics.PipelineHistogram.Percentiles(
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, 0);
    }

    /** Renders a percentile value in ms (2 dp), or {@code "n/a"} when unsampled. */
    private static String formatPercentile(double v) {
        return (Double.isNaN(v) || Double.isInfinite(v)) ? "n/a" : String.format("%.2f", v);
    }

    /**
     * Run {@code body} with one freshly-captured snapshot installed for the current thread.
     * Snapshot is captured by {@code RTP.metrics.snapshot()} exactly once at entry and
     * is cleared on exit (even on exception).
     */
    public static void withSnapshot(Runnable body) {
        MetricsSnapshot prior = METRICS_SNAPSHOT_CACHE.get();
        METRICS_SNAPSHOT_CACHE.set(RTP.metrics.snapshot());
        try {
            body.run();
        } finally {
            if (prior == null) METRICS_SNAPSHOT_CACHE.remove();
            else METRICS_SNAPSHOT_CACHE.set(prior);
        }
    }

    static {
        placeholders.put(
                "delay",
                uuid -> {
                    if (RTP.getInstance() == null) return "0";
                    if (RTP.serverAccessor == null) return "0";
                    RTPCommandSender commandSender = RTP.serverAccessor.getSender(uuid);
                    Number n = RTP.configs.getParser(ConfigKeys.class).getNumber(ConfigKeys.teleportDelay, 0);
                    int n2 = ParsePermissions.getInt(commandSender, "rtp.delay.");
                    if (n2 >= 0) n = n2;
                    if (n.longValue() == 0) return "0";

                    long time = n.longValue();
                    ConfigParser<MessagesKeys> langParser =
                            (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
                    long days = TimeUnit.SECONDS.toDays(time);
                    long hours = TimeUnit.SECONDS.toHours(time) % 24;
                    long minutes = TimeUnit.SECONDS.toMinutes(time) % 60;
                    long seconds = time % 60;

                    String replacement = "";
                    if (days > 0)
                        replacement += days + langParser.getConfigValue(MessagesKeys.days, "").toString() + " ";
                    if (hours > 0)
                        replacement +=
                                hours + langParser.getConfigValue(MessagesKeys.hours, "").toString() + " ";
                    if (minutes > 0)
                        replacement +=
                                minutes + langParser.getConfigValue(MessagesKeys.minutes, "").toString() + " ";
                    if (seconds > 0)
                        replacement += seconds + langParser.getConfigValue(MessagesKeys.seconds, "").toString();
                    return replacement;
                });
        placeholders.put(
                "cooldown",
                uuid -> {
                    if (RTP.getInstance() == null) return "A";
                    if (RTP.serverAccessor == null) return "B";
                    RTPCommandSender commandSender = RTP.serverAccessor.getSender(uuid);
                    Number n =
                            RTP.configs.getParser(ConfigKeys.class).getNumber(ConfigKeys.teleportCooldown, 0);
                    int n2 = ParsePermissions.getInt(commandSender, "rtp.cooldown.");
                    if (n2 >= 0) n = n2;

                    long time = n.longValue();
                    if (time <= 0) time = 0;
                    ConfigParser<MessagesKeys> langParser =
                            (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
                    long days = TimeUnit.SECONDS.toDays(time);
                    long hours = TimeUnit.SECONDS.toHours(time) % 24;
                    long minutes = TimeUnit.SECONDS.toMinutes(time) % 60;
                    long seconds = time % 60;

                    String replacement = "";
                    if (days > 0)
                        replacement += days + langParser.getConfigValue(MessagesKeys.days, "").toString() + " ";
                    if (hours > 0)
                        replacement +=
                                hours + langParser.getConfigValue(MessagesKeys.hours, "").toString() + " ";
                    if (minutes > 0)
                        replacement +=
                                minutes + langParser.getConfigValue(MessagesKeys.minutes, "").toString() + " ";
                    if (seconds > 0)
                        replacement += seconds + langParser.getConfigValue(MessagesKeys.seconds, "").toString();
                    return replacement;
                });
        placeholders.put(
                "remainingCooldown",
                uuid -> {
                    if (RTP.getInstance() == null) return "A";
                    if (RTP.serverAccessor == null) return "B";

                    long start = System.currentTimeMillis();

                    RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
                    if (sender != null) {
                        TeleportData teleportData = RTP.getInstance().latestTeleportData.get(uuid);
                        long lastTime = start;
                        if (teleportData != null) lastTime = teleportData.time;

                        long n = sender.cooldown();

                        long currTime = (start - lastTime);
                        long remainingTime = n - currTime;
                        if (remainingTime < 0) remainingTime = 0;

                        ConfigParser<MessagesKeys> langParser =
                                (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
                        long days = TimeUnit.MILLISECONDS.toDays(remainingTime);
                        long hours = TimeUnit.MILLISECONDS.toHours(remainingTime) % 24;
                        long minutes = TimeUnit.MILLISECONDS.toMinutes(remainingTime) % 60;
                        long seconds = TimeUnit.MILLISECONDS.toSeconds(remainingTime) % 60;
                        long millis = remainingTime % 1000;
                        if (millis > 500 && seconds > 0) {
                            seconds++;
                            millis = 0;
                        }

                        String replacement = "";
                        if (days > 0)
                            replacement +=
                                    days + langParser.getConfigValue(MessagesKeys.days, "").toString() + " ";
                        if (hours > 0)
                            replacement +=
                                    hours + langParser.getConfigValue(MessagesKeys.hours, "").toString() + " ";
                        if (minutes > 0)
                            replacement +=
                                    minutes + langParser.getConfigValue(MessagesKeys.minutes, "").toString() + " ";
                        if (seconds > 0) {

                            replacement +=
                                    seconds + langParser.getConfigValue(MessagesKeys.seconds, "").toString();
                        }
                        if (seconds < 2) {
                            replacement += millis + langParser.getConfigValue(MessagesKeys.millis, "").toString();
                        }
                        return replacement;
                    }
                    return "C";
                });
        placeholders.put(
                "queueLocation",
                uuid -> {
                    if (RTP.getInstance() == null) return "0";
                    TeleportData teleportData = RTP.getInstance().latestTeleportData.get(uuid);
                    if (teleportData == null) return "0";
                    return String.valueOf(teleportData.queueLocation);
                });
        placeholders.put(
                "tickets",
                uuid ->{
                    long res = 0;
                    for (RTPWorld<?> world : RTP.serverAccessor.getRTPWorlds()) {
                        res += world.activeChunkTickets.get();
                    }
                    return String.valueOf(res);
                });

        placeholders.put(
                "plugin_forced",
                uuid -> {
                    long res = 0;
                    for (RTPWorld<?> world : RTP.serverAccessor.getRTPWorlds()) {
                        res += world.numForceLoaded();
                    }
                    return String.valueOf(res);
                });

        placeholders.put(
                "server_forced",
                uuid -> {
                    long res = 0;
                    for (RTPWorld<?> world : RTP.serverAccessor.getRTPWorlds()) {
                        res += world.getServerForceLoadedCount().join();
                    }
                    return String.valueOf(res);
                });

        placeholders.put(
                "teleports",
                uuid -> {
                    if (RTP.getInstance() == null) return "0";
                    return String.valueOf(RTP.getInstance().latestTeleportData.values().stream().filter(teleportData -> !teleportData.completed).count());
                });

        placeholders.put(
                "mspt",
                uuid -> String.format("%.4f", io.github.dailystruggle.rtp.common.tools.PerformanceTracker.pluginMSPT));

        placeholders.put(
                "loads",
                uuid -> {
                    long totalLoads = 0;
                    for (RTPWorld<?> world : RTP.serverAccessor.getRTPWorlds()) {
                        totalLoads += world.totalChunkLoads.get();
                    }
                    return String.valueOf(totalLoads);
                });

        placeholders.put(
                "loadsByOrigin",
                uuid -> {
                    // Aggregate per-origin chunk-load counts across all worlds. Each
                    // origin tag is registered by RTPWorld.recordChunkLoadOrigin at
                    // every getOrLoadChunk(cx,cz,origin) call site (QueueTask.*,
                    // PregenTask.*, ScanTask.fullLoad, Region.coldPromote, etc.).
                    // Formatted as a comma-separated "tag=N" list, sorted by count
                    // descending so the noisiest origin sits first. Empty when no
                    // origin has been recorded yet (e.g. fresh server, no loads).
                    java.util.Map<String, Long> totals = new java.util.HashMap<>();
                    for (RTPWorld<?> world : RTP.serverAccessor.getRTPWorlds()) {
                        world.chunkLoadsByOrigin.forEach((tag, ctr) ->
                                totals.merge(tag, ctr.get(), Long::sum));
                    }
                    if (totals.isEmpty()) return "";
                    return totals.entrySet().stream()
                            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .collect(java.util.stream.Collectors.joining(", "));
                });

        placeholders.put(
                "leakRate",
                uuid -> {
                    // Defensive leak-rate accounting: rather than trusting that everything in
                    // each region's kept cache / per-player queues is genuinely kept (which
                    // would silently hide bugs where a "kept" entry has no live ticket, or a
                    // ticket exists for a chunk no longer in any cache), we rely on the
                    // makeshift GC sweep in MemoryTracker — it scans world.chunkTickets,
                    // compares against the keep-alive set, and increments
                    // RTPWorld.lifetimeOrphanedTicketsScanned for every unexpected ticket it
                    // finds. The leak rate is therefore the cumulative count of unexpected
                    // tickets ever observed, divided by the cumulative number of chunk
                    // tickets we have ever issued (lifetimeTicketsIssued). Note: we
                    // deliberately do NOT use totalChunkLoads as the divisor — that counter
                    // tracks only live chunk-load attempts, so it would undercount the
                    // denominator by missing tickets that were ref-counted onto an
                    // already-loaded chunk (a kept-cache replay or sibling-of-already-pinned
                    // candidate). lifetimeTicketsIssued increments on every acquire and is
                    // the correct denominator for "fraction of issued tickets that leaked".
                    long lifetimeOrphaned = 0;
                    long ticketsIssued = 0;
                    for (RTPWorld<?> world : RTP.serverAccessor.getRTPWorlds()) {
                        lifetimeOrphaned += world.lifetimeOrphanedTicketsScanned.get();
                        ticketsIssued += world.lifetimeTicketsIssued.get();
                    }
                    double leakRate = (ticketsIssued > 0) ? ((double) lifetimeOrphaned / ticketsIssued) * 100.0 : 0.0;
                    return String.format("%.4f%%", leakRate);
                });

        // --- Metrics SPI placeholders (METRICS_PLAN.md > /rtp info Surface) ---
        // All read a single MetricsSnapshot via currentSnapshot(): callers (e.g. InfoCmd)
        // wrap their formatting pass in PlaceholderProvider.withSnapshot(...) so that one
        // /rtp info invocation produces exactly one MetricsBinding round-trip. If no
        // caller scope is active, currentSnapshot() falls back to RTP.metrics.snapshot()
        // per call — safe, just slightly less efficient (B11 cleanup, METRICS_PLAN.md).
        placeholders.put(
                "queueDepth",
                uuid -> String.valueOf(currentRtpExt().queueDepth));
        placeholders.put(
                "pendingTeleports",
                uuid -> String.valueOf(currentRtpExt().pendingTeleports));
        placeholders.put(
                "avgPipelineMs",
                uuid -> {
                    double v = currentRtpExt().avgPipelineMs;
                    return Double.isNaN(v) ? "n/a" : String.format("%.2f", v);
                });
        // ADR-053 §1 / REQ-RTP-OBS-004: pipeline-latency percentiles, computed on demand by
        // sorting a point-in-time copy of the bounded histogram ring. Read directly off the
        // CoreMetrics-owned PipelineHistogram (not the snapshot extension) since percentiles are
        // a read-path over the same data; render "n/a" until the first sample lands.
        placeholders.put("pipelineMsP50", uuid -> formatPercentile(currentPercentiles().p50));
        placeholders.put("pipelineMsP75", uuid -> formatPercentile(currentPercentiles().p75));
        placeholders.put("pipelineMsP90", uuid -> formatPercentile(currentPercentiles().p90));
        placeholders.put("pipelineMsP95", uuid -> formatPercentile(currentPercentiles().p95));
        placeholders.put("pipelineMsP99", uuid -> formatPercentile(currentPercentiles().p99));
        placeholders.put("pipelineSampleCount",
                uuid -> String.valueOf(currentPercentiles().sampleCount));
        // ADR-053 §2a / REQ-RTP-OBS-005: slow-teleport audit (immediate/unqueued only).
        placeholders.put("slowPipelineCount",
                uuid -> String.valueOf(currentRtpExt().slowPipelineCount));
        placeholders.put("slowPipelineThresholdMs",
                uuid -> {
                    long t = currentRtpExt().slowPipelineThresholdMs;
                    return (t <= 0L) ? "disabled" : String.valueOf(t);
                });
        // ADR-053 §2b / REQ-RTP-OBS-006: queue-growth audit.
        placeholders.put("queueGrowthWarnCount",
                uuid -> String.valueOf(currentRtpExt().queueGrowthWarnCount));
        placeholders.put("queueGrowthWarnThreshold",
                uuid -> {
                    int t = currentRtpExt().queueGrowthWarnThreshold;
                    return (t <= 0) ? "disabled" : String.valueOf(t);
                });
        placeholders.put(
                "heapUsedMb",
                uuid -> String.valueOf(currentSnapshot().heapUsedMb()));
        placeholders.put(
                "heapMaxMb",
                uuid -> {
                    long mb = currentSnapshot().heapMaxMb();
                    return (mb < 0) ? "unbounded" : String.valueOf(mb);
                });
        placeholders.put(
                "memoryEntries",
                uuid -> String.valueOf(currentRtpExt().memoryTrackerEntries));
        placeholders.put(
                "chunkLoadBacklog",
                uuid -> String.valueOf(currentRtpExt().chunkLoadBacklog));
        // Additional Metrics SPI placeholders (B11 scope) — TPS / MSPT / capacity /
        // database latency / tick budget. Each renders the documented sentinel (NaN
        // tps/mspt, -1 db latency, 0 softCap) as "n/a" / "unbounded" so locales can
        // surface "metrics unavailable" deterministically when the NOOP binding is live.
        placeholders.put(
                "tps1m",
                uuid -> {
                    double v = currentSnapshot().tps1m;
                    return Double.isNaN(v) ? "n/a" : String.format("%.2f", v);
                });
        placeholders.put(
                "tps5m",
                uuid -> {
                    double v = currentSnapshot().tps5m;
                    return Double.isNaN(v) ? "n/a" : String.format("%.2f", v);
                });
        placeholders.put(
                "tps15m",
                uuid -> {
                    double v = currentSnapshot().tps15m;
                    return Double.isNaN(v) ? "n/a" : String.format("%.2f", v);
                });
        placeholders.put(
                "mspt",
                uuid -> {
                    double v = currentSnapshot().mspt;
                    return Double.isNaN(v) ? "n/a" : String.format("%.2f", v);
                });
        placeholders.put(
                "tickBudgetUtilisation",
                uuid -> {
                    double v = currentSnapshot().tickBudgetUtilisation;
                    return Double.isNaN(v) ? "n/a" : String.format("%.1f%%", v * 100.0);
                });
        placeholders.put(
                "softCap",
                uuid -> {
                    int v = currentSnapshot().softCap;
                    return (v <= 0) ? "unbounded" : String.valueOf(v);
                });
        placeholders.put(
                "playerCount",
                uuid -> String.valueOf(currentSnapshot().playerCount));
        placeholders.put(
                "databaseLatencyMs",
                uuid -> {
                    int v = currentRtpExt().databaseLatencyMs;
                    return (v < 0) ? "n/a" : String.valueOf(v);
                });
        // Generation-outcome placeholders (ADR-052). Read directly off the process-global
        // RtpOutcomeStats.GLOBAL accumulator (the same read-directly pattern the ADR-053
        // percentiles use for the CoreMetrics histogram) rather than threading through the
        // MetricsSnapshot extension: these counters are wait-free and process-global, so a
        // direct read needs no snapshot round-trip. They surface the operator-facing
        // success/failure rate and per-cause rejection breakdown (the analogue of a
        // competitor's "/rtp unsafe-stats") in /rtp info.
        placeholders.put(
                "genSuccessRate",
                uuid -> {
                    double r = io.github.dailystruggle.rtp.common.metrics.RtpOutcomeStats.GLOBAL.successRate();
                    return Double.isNaN(r) ? "n/a" : String.format("%.1f%%", r * 100.0);
                });
        placeholders.put(
                "genFailureRate",
                uuid -> {
                    double r = io.github.dailystruggle.rtp.common.metrics.RtpOutcomeStats.GLOBAL.successRate();
                    return Double.isNaN(r) ? "n/a" : String.format("%.1f%%", (1.0 - r) * 100.0);
                });
        placeholders.put(
                "genOutcomeTotal",
                uuid -> {
                    io.github.dailystruggle.rtp.common.metrics.RtpOutcomeStats stats =
                            io.github.dailystruggle.rtp.common.metrics.RtpOutcomeStats.GLOBAL;
                    return String.valueOf(stats.successCount() + stats.totalFailures());
                });
        placeholders.put(
                "genFailureBreakdown",
                uuid -> {
                    Map<io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes, Long>
                            breakdown =
                            io.github.dailystruggle.rtp.common.metrics.RtpOutcomeStats.GLOBAL.failureBreakdown();
                    // Comma-separated "cause=N" list, omitting zero buckets, busiest first.
                    String out = breakdown.entrySet().stream()
                            .filter(e -> e.getValue() > 0L)
                            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                            .map(e -> e.getKey().name() + "=" + e.getValue())
                            .collect(java.util.stream.Collectors.joining(", "));
                    return out.isEmpty() ? "none" : out;
                });
        placeholders.put(
                "genTopRejectionCause",
                uuid -> {
                    Map<io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes, Long>
                            breakdown =
                            io.github.dailystruggle.rtp.common.metrics.RtpOutcomeStats.GLOBAL.failureBreakdown();
                    return breakdown.entrySet().stream()
                            .filter(e -> e.getValue() > 0L)
                            .max(java.util.Map.Entry.comparingByValue())
                            .map(e -> e.getKey().name())
                            .orElse("none");
                });
        placeholders.put(
                "genTopRejectionShare",
                uuid -> {
                    Map<io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes, Long>
                            breakdown =
                            io.github.dailystruggle.rtp.common.metrics.RtpOutcomeStats.GLOBAL.failureBreakdown();
                    long total = 0L;
                    long top = 0L;
                    for (long v : breakdown.values()) {
                        total += v;
                        if (v > top) top = v;
                    }
                    if (total == 0L) return "n/a";
                    return String.format("%.1f%%", ((double) top / total) * 100.0);
                });
        // B12 (METRICS_PLAN.md > Health colour coding): coloured-wrapper variants of the
        // numeric Health-Pipeline placeholders. Each prepends a legacy &-code chosen by
        // ColourBands (green/yellow/red/grey) and trails &r so adjacent template text keeps
        // its own colour. NaN / unavailable values render as "n/a" prefixed by &7 (grey) so
        // operators do not see false-red on first start. Operators may opt out of colouring
        // by switching templates back to the bare placeholders (e.g. [tps1m]); the coloured
        // variants are additive and never replace the originals.
        placeholders.put(
                "tps1mColoured",
                uuid -> {
                    double v = currentSnapshot().tps1m;
                    return ColourBands.forTps(v)
                            + (Double.isNaN(v) ? "n/a" : String.format("%.2f", v))
                            + "&r";
                });
        placeholders.put(
                "tps5mColoured",
                uuid -> {
                    double v = currentSnapshot().tps5m;
                    return ColourBands.forTps(v)
                            + (Double.isNaN(v) ? "n/a" : String.format("%.2f", v))
                            + "&r";
                });
        placeholders.put(
                "tps15mColoured",
                uuid -> {
                    double v = currentSnapshot().tps15m;
                    return ColourBands.forTps(v)
                            + (Double.isNaN(v) ? "n/a" : String.format("%.2f", v))
                            + "&r";
                });
        placeholders.put(
                "msptColoured",
                uuid -> {
                    double v = currentSnapshot().mspt;
                    return ColourBands.forMspt(v)
                            + (Double.isNaN(v) ? "n/a" : String.format("%.2f", v))
                            + "&r";
                });
        placeholders.put(
                "tickBudgetUtilisationColoured",
                uuid -> {
                    double v = currentSnapshot().tickBudgetUtilisation;
                    return ColourBands.forTickBudgetUtilisation(v)
                            + (Double.isNaN(v) ? "n/a" : String.format("%.1f%%", v * 100.0))
                            + "&r";
                });
        placeholders.put(
                "avgPipelineMsColoured",
                uuid -> {
                    double v = currentRtpExt().avgPipelineMs;
                    // avgPipelineMs is on the same lower-is-better axis as server MSPT, so
                    // reuse the MSPT bands. A high average pipeline latency is the operator
                    // signal that the teleport pipeline is starved or stalling.
                    return ColourBands.forMspt(v)
                            + (Double.isNaN(v) ? "n/a" : String.format("%.2f", v))
                            + "&r";
                });

        placeholders.put(
                "attempts",
                uuid -> {
                    if (RTP.getInstance() == null) return "A";
                    TeleportData teleportData = RTP.getInstance().latestTeleportData.get(uuid);
                    if (teleportData == null) return "B";
                    return String.valueOf(teleportData.attempts);
                });
        placeholders.put(
                "processingTime",
                uuid -> {
                    if (RTP.getInstance() == null) return "0";
                    TeleportData teleportData = RTP.getInstance().latestTeleportData.get(uuid);

                    long time = (teleportData != null) ? teleportData.processingTime : 0L;

                    ConfigParser<MessagesKeys> langParser =
                            (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
                    long days = TimeUnit.MILLISECONDS.toDays(time);
                    long hours = TimeUnit.MILLISECONDS.toHours(time) % 24;
                    long minutes = TimeUnit.MILLISECONDS.toMinutes(time) % 60;
                    long seconds = TimeUnit.MILLISECONDS.toSeconds(time) % 60;
                    long millis = time % 1000;
                    if (millis > 500 && seconds > 0) {
                        seconds++;
                        millis = 0;
                    }

                    String replacement = "";
                    if (days > 0)
                        replacement += days + langParser.getConfigValue(MessagesKeys.days, "").toString() + " ";
                    if (hours > 0)
                        replacement +=
                                hours + langParser.getConfigValue(MessagesKeys.hours, "").toString() + " ";
                    if (minutes > 0)
                        replacement +=
                                minutes + langParser.getConfigValue(MessagesKeys.minutes, "").toString() + " ";
                    if (seconds > 0)
                        replacement += seconds + langParser.getConfigValue(MessagesKeys.seconds, "").toString();
                    if (seconds < 2) {
                        replacement += millis + langParser.getConfigValue(MessagesKeys.millis, "").toString();
                    }
                    return replacement;
                });
        placeholders.put(
                "spot",
                uuid -> {
                    if (RTP.getInstance() == null) return "0";
                    TeleportData teleportData = RTP.getInstance().latestTeleportData.get(uuid);
                    if (teleportData == null) return "0";

                    long spot = teleportData.queueLocation;
                    return String.valueOf(spot);
                });
        placeholders.put(
                "player",
                uuid -> {
                    RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
                    if (sender == null) {
                        return "";
                    }
                    return sender.name();
                });
        placeholders.put(
                "player_name",
                uuid -> {
                    RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
                    if (sender == null) {
                        return "";
                    }
                    return sender.name();
                });
        placeholders.put(
                "player_status",
                uuid -> {
                    RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
                    if (sender == null) {
                        return "";
                    }

                    TeleportData data = RTP.getInstance().latestTeleportData.get(uuid);
                    ConfigParser<MessagesKeys> lang =
                            (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);

                    if (data == null)
                        return lang.getConfigValue(MessagesKeys.PLAYER_AVAILABLE, "").toString();
                    if (data.completed) {
                        long dt = System.currentTimeMillis() - data.time;
                        if (dt < 0) dt = Long.MAX_VALUE + dt;
                        if (dt < sender.cooldown()) {
                            return lang.getConfigValue(MessagesKeys.PLAYER_COOLDOWN, "").toString();
                        }

                        return lang.getConfigValue(MessagesKeys.PLAYER_AVAILABLE, "").toString();
                    }

                    RTPRunnable nextTask = data.nextTask;
                    if (nextTask instanceof TeleportPipelineTask) {
                        TeleportPipelineTask task = (TeleportPipelineTask) nextTask;
                        switch (task.getPhase()) {
                            case TELEPORT:
                                return lang.getConfigValue(MessagesKeys.PLAYER_TELEPORTING, "").toString();
                            case LOAD:
                                return lang.getConfigValue(MessagesKeys.PLAYER_LOADING, "").toString();
                            case SETUP:
                                return lang.getConfigValue(MessagesKeys.PLAYER_SETUP, "").toString();
                            default:
                                break;
                        }
                    }
                    return "";
                });

        placeholders.put(
                "total_queue_length",
                uuid -> {
                    RTPPlayer player = RTP.serverAccessor.getPlayer(uuid);
                    if (player == null) {
                        return "";
                    }

                    Region region =
                            RTP.selectionAPI.getRegion(player);
                    if (region == null) return "0";
                    return String.valueOf(region.queueManager.getTotalQueueLength(uuid));
                });

        placeholders.put(
                "public_queue_length",
                uuid -> {
                    RTPPlayer player = RTP.serverAccessor.getPlayer(uuid);
                    if (player == null) {
                        return "";
                    }

                    Region region =
                            RTP.selectionAPI.getRegion(player);
                    if (region == null) return "0";
                    return String.valueOf(region.queueManager.getPublicQueueLength());
                });

        placeholders.put(
                "personal_queue_length",
                uuid -> {
                    RTPPlayer player = RTP.serverAccessor.getPlayer(uuid);
                    if (player == null) {
                        return "";
                    }

                    Region region =
                            RTP.selectionAPI.getRegion(player);
                    if (region == null) return "0";
                    return String.valueOf(region.queueManager.getPersonalQueueLength(uuid));
                });

        placeholders.put(
                "teleport_world",
                uuid -> {
                    TeleportData data = RTP.getInstance().latestTeleportData.get(uuid);
                    if (data == null || data.selectedCoords == null) return "";
                    return data.selectedCoords.worldName();
                });

        placeholders.put(
                "teleport_x",
                uuid -> {
                    TeleportData data = RTP.getInstance().latestTeleportData.get(uuid);
                    if (data == null || data.selectedCoords == null) return "";
                    return String.valueOf(data.selectedCoords.x());
                });

        placeholders.put(
                "teleport_y",
                uuid -> {
                    TeleportData data = RTP.getInstance().latestTeleportData.get(uuid);
                    if (data == null || data.selectedCoords == null) return "";
                    return String.valueOf(data.selectedCoords.y());
                });

        placeholders.put(
                "teleport_z",
                uuid -> {
                    TeleportData data = RTP.getInstance().latestTeleportData.get(uuid);
                    if (data == null || data.selectedCoords == null) return "";
                    return String.valueOf(data.selectedCoords.z());
                });

        placeholders.put(
                "teleport_biome",
                uuid -> {
                    TeleportData data = RTP.getInstance().latestTeleportData.get(uuid);
                    if (data == null || data.selectedCoords == null) return "";
                    RTPWorld<?> world =
                            RTP.serverAccessor.getRTPWorld(data.selectedCoords.worldName());
                    if (world == null) return "";
                    // ADR-016 §13.1 follow-up (2026-04-20): route the biome read
                    // through the resolved chunk so anvil-cached data is used
                    // transparently and an ungenerated chunk is probed/loaded on
                    // demand rather than falling to the seed-synth getter.
                    int cx = data.selectedCoords.x() >> 4;
                    int cz = data.selectedCoords.z() >> 4;
                    try {
                        io.github.dailystruggle.rtp.api.world.RTPChunk<?> chunk =
                                world.getOrLoadChunk(cx, cz, "PlaceholderProvider.teleportBiome").get(5, TimeUnit.SECONDS);
                        if (chunk != null) {
                            return chunk.getBiome(
                                    data.selectedCoords.x(),
                                    data.selectedCoords.y(),
                                    data.selectedCoords.z());
                        }
                    } catch (Exception ignored) {
                        // Fall through to world-level getter on timeout/error.
                    }
                    return world.getBiome(
                            data.selectedCoords.x(), data.selectedCoords.y(), data.selectedCoords.z());
                });
        placeholders.put(
                "scan_chunks",
                uuid -> {
                    if (RTP.getInstance() == null) return "0";
                    long total = 0;
                    for (ScanTask task : RTP.getInstance().scanTasks.values()) {
                        total += task.latestAbsolutePos;
                    }
                    return String.valueOf(total);
                });
        placeholders.put(
                "scan_totalChunks",
                uuid -> {
                    if (RTP.getInstance() == null) return "0";
                    long total = 0;
                    for (ScanTask task : RTP.getInstance().scanTasks.values()) {
                        total += task.latestAbsoluteTotal;
                    }
                    return String.valueOf(total);
                });
        placeholders.put(
                "scan_cps",
                uuid -> {
                    if (RTP.getInstance() == null) return "0";
                    long total = 0;
                    for (ScanTask task : RTP.getInstance().scanTasks.values()) {
                        total += task.latestCps;
                    }
                    return String.valueOf(total);
                });
        placeholders.put(
                "scan_regions",
                uuid -> {
                    if (RTP.getInstance() == null) return "";
                    return String.join(", ", RTP.getInstance().scanTasks.keySet());
                });
        placeholders.put(
                "scan_eta",
                uuid -> {
                    try {
                        if (RTP.getInstance() == null) return "0s";

                        long maxEta = 0;
                        for (ScanTask task : RTP.getInstance().scanTasks.values()) {
                            if (task.latestEtaSeconds > maxEta) maxEta = task.latestEtaSeconds;
                        }

                        ConfigParser<MessagesKeys> langParser =
                                (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);

                        if (langParser == null) return maxEta + "s";

                        long days = TimeUnit.SECONDS.toDays(maxEta);
                        long hours = TimeUnit.SECONDS.toHours(maxEta) % 24;
                        long minutes = TimeUnit.SECONDS.toMinutes(maxEta) % 60;
                        long seconds = maxEta % 60;

                        StringBuilder replacement = new StringBuilder();

                        if (days > 0) {
                            replacement.append(days).append(String.valueOf(langParser.getConfigValue(MessagesKeys.days, ""))).append(" ");
                        }
                        if (hours > 0) {
                            replacement.append(hours).append(String.valueOf(langParser.getConfigValue(MessagesKeys.hours, ""))).append(" ");
                        }
                        if (minutes > 0) {
                            replacement.append(minutes).append(String.valueOf(langParser.getConfigValue(MessagesKeys.minutes, ""))).append(" ");
                        }

                        if (seconds > 0 || replacement.length() == 0) {
                            replacement.append(seconds).append(String.valueOf(langParser.getConfigValue(MessagesKeys.seconds, "")));
                        }

                        return replacement.toString().trim();
                    } catch (Exception e) {
                        RTP.log(java.util.logging.Level.WARNING, "Placeholder resolution failed for scan_eta", e);
                        return "0s";
                    }
                });

        placeholders.put("world", uuid -> {
            RTPWorld world = RTP.worldContext.get();
            if (world != null) return world.name();
            Region region = RTP.regionContext.get();
            if (region != null) return region.getWorld().name();
            RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
            if (sender != null) {
                RTPWorld senderWorld = RTP.serverAccessor.getRTPWorld(sender.uuid()); // This is wrong, sender uuid is player uuid.
                // Wait, RTPWorld getRTPWorld(UUID) might be world UID or player UID depending on implementation.
                // Usually it's world UID.
                // Let's use a better way if possible.
            }
            return "";
        });

        placeholders.put("name", uuid -> {
            RTPWorld world = RTP.worldContext.get();
            if (world != null) return world.name();
            Region region = RTP.regionContext.get();
            if (region != null) return region.getWorld().name();
            return "";
        });

        placeholders.put("region", uuid -> {
            Region region = RTP.regionContext.get();
            if (region != null) return region.name;
            RTPWorld world = RTP.worldContext.get();
            if (world != null) return RTP.selectionAPI.getRegion(world).name;
            return "";
        });

        placeholders.put("requirePermission", uuid -> {
            Region region = RTP.regionContext.get();
            if (region != null) {
                boolean req = false;
                EnumMap<RegionKeys, Object> data = region.getData();
                Object o = data.getOrDefault(RegionKeys.requirePermission, false);
                if (o instanceof Boolean) req = (Boolean) o;
                else if (o instanceof String) {
                    req = Boolean.parseBoolean((String) o);
                    data.put(RegionKeys.requirePermission, req);
                }
                return String.valueOf(req);
            }
            RTPWorld world = RTP.worldContext.get();
            if (world != null) {
                MultiConfigParser<WorldKeys> worlds =
                        (MultiConfigParser<WorldKeys>) RTP.configs.getParser(WorldKeys.class);
                ConfigParser<WorldKeys> parser = worlds.getParser(world.name());
                return parser.getConfigValue(WorldKeys.requirePermission, false).toString();
            }
            return "false";
        });

        placeholders.put("override", uuid -> {
            RTPWorld world = RTP.worldContext.get();
            if (world != null) {
                MultiConfigParser<WorldKeys> worlds =
                        (MultiConfigParser<WorldKeys>) RTP.configs.getParser(WorldKeys.class);
                ConfigParser<WorldKeys> parser = worlds.getParser(world.name());
                String override = parser.getConfigValue(WorldKeys.override, "[0]").toString();
                if (override.startsWith("[") && override.endsWith("]")) {
                    try {
                        int num = Integer.parseInt(override.substring(1, override.length() - 1));
                        List<RTPWorld<?>> rtpWorlds = RTP.serverAccessor.getRTPWorlds();
                        if (num >= 0 && num < rtpWorlds.size()) {
                            return rtpWorlds.get(num).name();
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                return override;
            }
            return "0";
        });

        placeholders.put("pluginForced", uuid -> {
            RTPWorld world = RTP.worldContext.get();
            if (world != null) return String.valueOf(world.numForceLoaded());
            return "0";
        });

        placeholders.put("serverForced", uuid -> {
            RTPWorld world = RTP.worldContext.get();
            if (world != null) return String.valueOf(world.getServerForceLoadedCount().join());
            return "0";
        });

        placeholders.put("shape", uuid -> {
            Region region = RTP.regionContext.get();
            if (region != null) return region.getShape().name;
            return "none";
        });

        placeholders.put("cacheCap", uuid -> {
            Region region = RTP.regionContext.get();
            if (region != null) return String.valueOf(region.getSettings().cacheCap());
            return "0";
        });

        placeholders.put("cached", uuid -> {
            Region region = RTP.regionContext.get();
            if (region != null) return String.valueOf(region.queueManager.getPublicQueueLength());
            return "0";
        });

        placeholders.put("keptCache", uuid -> {
            Region region = RTP.regionContext.get();
            if (region != null) return String.valueOf(region.queueManager.keptLocations.size());
            return "0";
        });

        // L2 — pre-verified locations whose chunks have been released ("cold" / "unkept").
        placeholders.put("unkeptCache", uuid -> {
            Region region = RTP.regionContext.get();
            if (region != null) return String.valueOf(region.queueManager.unkeptLocations.size());
            return "0";
        });

        // L3 — optional verified-pile size from the backlog buffer (ADR-028).
        // Reports the number of Anvil-pre-filter VALIDATED entries currently
        // waiting to be promoted into L2 (the "success" pile), not the raw
        // buffer occupancy. INVALIDATED entries are ejected eagerly in
        // Region.processBacklog (step 2b) and UNVERIFIED entries are still
        // in flight, so neither contributes to this number. Resolves to 0
        // when L3 is disabled (backlogCacheCap == 0, lite default;
        // backlogLocations is null).
        placeholders.put("backlogCache", uuid -> {
            Region region = RTP.regionContext.get();
            if (region != null && region.queueManager.backlogLocations != null) {
                return String.valueOf(region.queueManager.backlogLocations.validatedSize());
            }
            return "0";
        });

        // L3 capacity (ADR-028). Mirrors %rtp_cacheCap%; 0 indicates L3 is disabled.
        placeholders.put("backlogCacheCap", uuid -> {
            Region region = RTP.regionContext.get();
            if (region != null) return String.valueOf(region.getSettings().backlogCacheCap());
            return "0";
        });

        placeholders.put("locationQueue", uuid -> {
            Region region = RTP.regionContext.get();
            if (region != null) return String.valueOf(region.queueManager.keptLocations.size());
            return "0";
        });

        placeholders.put("inFlightCalculations", uuid -> {
            Region region = RTP.regionContext.get();
            if (region != null) return String.valueOf(region.inFlightCalculations.get());
            return "0";
        });

        placeholders.put("worldBorderOverride", uuid -> {
            Region region = RTP.regionContext.get();
            if (region != null) {
                boolean wbo = false;
                EnumMap<RegionKeys, Object> data = region.getData();
                Object o = data.getOrDefault(RegionKeys.worldBorderOverride, false);
                if (o instanceof Boolean) wbo = (Boolean) o;
                else if (o instanceof String) {
                    wbo = Boolean.parseBoolean((String) o);
                    data.put(RegionKeys.worldBorderOverride, wbo);
                }
                return String.valueOf(wbo);
            }
            return "false";
        });
    }

    public static String fillPlaceholders(String text, UUID uuid) {
        Set<String> keywords =
                ParseString.keywords(
                        text,
                        placeholders.keySet(),
                        new HashSet<>(Arrays.asList('[', '%')),
                        new HashSet<>(Arrays.asList(']', '%')));

        for (String s : keywords) {
            Function<UUID, String> function = placeholders.get(s);
            if (function == null) continue;
            String value = function.apply(uuid);
            String quotedValue = Matcher.quoteReplacement(value);
            text = Pattern.compile("\\[" + s + "]", Pattern.CASE_INSENSITIVE)
                    .matcher(text)
                    .replaceAll(quotedValue);
            text = Pattern.compile("%" + s + "%", Pattern.CASE_INSENSITIVE)
                    .matcher(text)
                    .replaceAll(quotedValue);
        }
        return text;
    }

    public static String fillNumericPlaceholders(String text) {
        ConfigParser<MessagesKeys> lang = (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
        if (lang == null) return text;
        // [p0], [p1]...
        text = fillNumericPlaceholders(text, Pattern.compile("\\[([Pp])(\\d*)]"), "\\[[Pp]\\d*]");
        // %p0%, %p1%...
        text = fillNumericPlaceholders(text, Pattern.compile("%([Pp])(\\d*)%"), "%[Pp]\\d*%");
        return text;
    }

    private static String fillNumericPlaceholders(String text, Pattern pattern, String removeRegex) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String group = matcher.group(2);
            int bits;
            try {
                bits = Integer.parseInt(group);
            } catch (NumberFormatException ignored) {
                continue;
            }
            matcher.reset();

            String replacement = "[invalid]";
            ConfigParser<MessagesKeys> parser =
                    (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
            Object o = parser.getConfigValue(MessagesKeys.placeholders, new ArrayList<>());
            if (o instanceof List<?> pList) {
                if (pList.size() > bits) {
                    replacement = pList.get(bits).toString();
                }
            }

            replacement = Pattern.compile(removeRegex).matcher(replacement).replaceAll("");

            text = matcher.replaceFirst(Matcher.quoteReplacement(replacement));
            matcher = pattern.matcher(text);
        }
        return text;
    }
}
