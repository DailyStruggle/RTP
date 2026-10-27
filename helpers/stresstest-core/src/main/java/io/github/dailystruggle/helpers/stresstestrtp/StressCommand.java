package io.github.dailystruggle.helpers.stresstestrtp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Platform-neutral {@code /rtpstress} dispatcher (ADR-122 Decision 1 & 2).
 */
public final class StressCommand {

    private static final List<String> SUBS = Arrays.asList(
            "start", "stop", "status", "burst", "sequence", "ramp", "reset-cold", "export",
            "probe-footprint", "leakcheck", "churn", "tp", "coverage");

    private final StressHost plugin;

    public StressCommand(StressHost plugin) { this.plugin = plugin; }

    @SuppressWarnings("java:S3516")
    public boolean execute(StressSender sender, String[] args) {
        if (!sender.hasPermission("stresstestrtp.admin")) {
            sender.sendMessage("StressTestRTP: missing permission stresstestrtp.admin");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        try {
            return switch (sub) {
                case "start" -> doStart(sender, args);
                case "stop" -> doStop(sender);
                case "status" -> doStatus(sender);
                case "burst" -> doBurst(sender, args);
                case "sequence" -> doSequence(sender, args);
                case "ramp" -> doRamp(sender, args);
                case "reset-cold" -> doResetCold(sender);
                case "export" -> doExport(sender);
                case "probe-footprint" -> doProbeFootprint(sender);
                case "leakcheck" -> doLeakCheck(sender, args);
                case "churn" -> doChurn(sender, args);
                case "tp" -> doTp(sender, args);
                case "coverage" -> doCoverage(sender, args);
                default -> {
                    sender.sendMessage("StressTestRTP: unknown subcommand. Try: " + SUBS);
                    yield true;
                }
            };
        } catch (Throwable t) {
            sender.sendMessage("StressTestRTP: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            plugin.getLogger().log(Level.WARNING, "/rtpstress " + sub + " failed", t);
            return true;
        }
    }

    private boolean doStart(StressSender sender, String[] args) throws IOException {
        Runner existingRunner = plugin.runner();
        if (existingRunner != null && existingRunner.isRunning()) {
            sender.sendMessage("StressTestRTP: a run is already in progress. /rtpstress stop first.");
            return true;
        }
        StressConfig cfg = plugin.config();
        var timedTargets = Targets.load(cfg, plugin.getLogger());
        if (timedTargets.size() > 1 && !cfg.getBoolean("allow-timed-multi-target", false)) {
            sender.sendMessage("StressTestRTP: " + timedTargets.size() + " target-commands configured; "
                    + "`start` (timed) round-robins them all against the same players, so the arms "
                    + "cancel each other's teleports and timings are misattributed.");
            sender.sendMessage("  Use `/rtpstress sequence` for a clean per-target head-to-head, "
                    + "or set allow-timed-multi-target: true to force an interleaved run.");
            return true;
        }
        int seconds = args.length >= 2 ? safeInt(args[1], (int) cfg.getLong("default-duration-seconds", 60))
                                       : (int) cfg.getLong("default-duration-seconds", 60);
        int concurrency = args.length >= 3 ? safeInt(args[2], (int) cfg.getLong("default-concurrency", 4))
                                            : (int) cfg.getLong("default-concurrency", 4);
        plugin.beginRun();
        Runner activeRunner = plugin.runner();
        if (activeRunner == null || !activeRunner.startTimed(sender, seconds, concurrency)) {
            sender.sendMessage("StressTestRTP: failed to start (already running?).");
            return true;
        }
        sender.sendMessage(String.format(
                "StressTestRTP: started — %ds, concurrency=%d, targets=[%s], csv=%s",
                seconds, concurrency,
                Targets.describe(Targets.load(cfg, plugin.getLogger())),
                plugin.recorder().csvPath().getFileName()));
        return true;
    }

    private boolean doBurst(StressSender sender, String[] args) throws IOException {
        Runner existingRunner = plugin.runner();
        if (existingRunner != null && existingRunner.isRunning()) {
            sender.sendMessage("StressTestRTP: a run is already in progress. /rtpstress stop first.");
            return true;
        }
        int n = args.length >= 2 ? safeInt(args[1], (int) plugin.config().getLong("default-burst", 10))
                                  : (int) plugin.config().getLong("default-burst", 10);
        plugin.beginRun();
        Runner activeRunner = plugin.runner();
        if (activeRunner == null || !activeRunner.startBurst(sender, n)) {
            sender.sendMessage("StressTestRTP: failed to start burst.");
            return true;
        }
        sender.sendMessage("StressTestRTP: burst dispatched — n=" + n
                + ", csv=" + plugin.recorder().csvPath().getFileName());
        return true;
    }

    private boolean doSequence(StressSender sender, String[] args) throws IOException {
        Runner existingRunner = plugin.runner();
        if (existingRunner != null && existingRunner.isRunning()) {
            sender.sendMessage("StressTestRTP: a run is already in progress. /rtpstress stop first.");
            return true;
        }
        StressConfig cfg = plugin.config();
        int perTarget = args.length >= 2
                ? safeInt(args[1], (int) cfg.getLong("sequence.per-target-seconds", 60))
                : (int) cfg.getLong("sequence.per-target-seconds", 60);
        int gap = args.length >= 3
                ? safeInt(args[2], (int) cfg.getLong("sequence.gap-seconds", 30))
                : (int) cfg.getLong("sequence.gap-seconds", 30);
        int concurrency = args.length >= 4
                ? safeInt(args[3], (int) cfg.getLong("default-concurrency", 4))
                : (int) cfg.getLong("default-concurrency", 4);
        int warmupSeconds = (int) cfg.getLong("sequence.warmup-seconds", 30);
        int warmupCycles = (int) cfg.getLong("sequence.warmup-target-cycles", 1);
        var targets = Targets.load(cfg, plugin.getLogger());
        plugin.beginRun();
        Runner activeRunner = plugin.runner();
        if (activeRunner == null || !activeRunner.startSequence(sender, perTarget, gap, concurrency,
                warmupSeconds, warmupCycles)) {
            sender.sendMessage("StressTestRTP: failed to start sequence (already running?).");
            return true;
        }
        long total = (long) warmupSeconds + (long) targets.size() * (perTarget + gap);
        sender.sendMessage(String.format(
                "StressTestRTP: sequence started \u2014 warmup=%ds (%d cycle%s), %d target(s) x %ds (gap %ds), concurrency=%d, ETA ~%ds, csv=%s",
                warmupSeconds, warmupCycles, (warmupCycles == 1 ? "" : "s"),
                targets.size(), perTarget, gap, concurrency, total,
                plugin.recorder().csvPath().getFileName()));
        sender.sendMessage("  targets: " + Targets.describe(targets));
        if (warmupSeconds > 0) {
            sender.sendMessage("  warm-up runs first; CSV writes start AFTER warm-up. See <stamp>-warmup.log.");
        }
        return true;
    }

    private boolean doRamp(StressSender sender, String[] args) throws IOException {
        Runner existingRunner = plugin.runner();
        if (existingRunner != null && existingRunner.isRunning()) {
            sender.sendMessage("StressTestRTP: a run is already in progress. /rtpstress stop first.");
            return true;
        }
        StressConfig cfg = plugin.config();
        var targets = Targets.load(cfg, plugin.getLogger());
        Targets.Entry target = resolveTarget(targets, args.length >= 2 ? args[1] : null);
        if (target == null) {
            sender.sendMessage("StressTestRTP: usage /rtpstress ramp <target> [stageSeconds] [rate,rate,...]; targets: "
                    + targetLabels(targets));
            return true;
        }
        int stageSeconds = args.length >= 3
                ? safeInt(args[2], (int) cfg.getLong("ramp.stage-seconds", 60))
                : (int) cfg.getLong("ramp.stage-seconds", 60);
        double[] override = null;
        if (args.length >= 4) {
            override = parseRates(args[3]);
            if (override.length == 0) {
                sender.sendMessage("StressTestRTP: rates must be positive numbers, comma-separated (e.g. 100 or 20,50,100).");
                return true;
            }
        }
        plugin.beginRun();
        Runner activeRunner = plugin.runner();
        if (activeRunner == null || !activeRunner.startRamp(sender, target, stageSeconds, override)) {
            sender.sendMessage("StressTestRTP: failed to start ramp (already running?).");
            return true;
        }
        List<Double> stages = cfg.getDoubleList("ramp.stages");
        String stagesText;
        if (override != null) stagesText = Arrays.toString(override);
        else stagesText = stages.isEmpty() ? Arrays.toString(Runner.DEFAULT_RAMP_STAGES) : stages.toString();
        sender.sendMessage(String.format(Locale.ROOT,
                "StressTestRTP: ramp started \u2014 target=%s, stages=%s TP/s x %ds, warmup=%ds, csv=%s",
                target.label, stagesText,
                stageSeconds, cfg.getLong("ramp.warmup-seconds", 30),
                stripExt(plugin.recorder().csvPath().getFileName().toString()) + "-ramp.csv"));
        sender.sendMessage("  /rtpstress stop ends it; completed stages are already on disk.");
        return true;
    }

    static double[] parseRates(String raw) {
        if (raw == null || raw.isBlank()) return new double[0];
        String[] parts = raw.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                return new double[0];
            }
            if (!(out[i] > 0) || Double.isInfinite(out[i])) return new double[0];
        }
        return out;
    }

    static Targets.Entry resolveTarget(List<Targets.Entry> targets, String label) {
        if (label == null || label.isBlank()) {
            return targets.size() == 1 ? targets.get(0) : null;
        }
        for (Targets.Entry e : targets) {
            if (e.label.equalsIgnoreCase(label)) return e;
        }
        return null;
    }

    private static List<String> targetLabels(List<Targets.Entry> targets) {
        return targets.stream().map(e -> e.label).toList();
    }

    @SuppressWarnings("java:S3516")
    private boolean doProbeFootprint(StressSender sender) {
        TicketFootprintProbe probe = plugin.ticketFootprintProbe();
        if (probe == null) {
            sender.sendMessage("StressTestRTP: ticket-footprint probe is disabled in config.");
            return true;
        }
        Runner existingRunner = plugin.runner();
        if (existingRunner != null && existingRunner.isRunning()) {
            sender.sendMessage("StressTestRTP: refusing to probe during a run; /rtpstress stop first.");
            return true;
        }
        probe.rerun();
        sender.sendMessage("StressTestRTP: ticket-footprint probe scheduled; result lands in "
                + "plugins/StressTestRTP/ticket-footprint.txt and the console within ~"
                + (TicketFootprintProbe.MAX_LOAD_WAIT_TICKS / 20L) + " s.");
        return true;
    }

    @SuppressWarnings("java:S3516")
    private boolean doLeakCheck(StressSender sender, String[] args) {
        LeakProbe leak = plugin.leakProbe();
        if (leak == null) {
            sender.sendMessage("StressTestRTP: leak probe unavailable.");
            return true;
        }
        String label = args.length >= 2 ? args[1] : "checkpoint";
        if ("status".equalsIgnoreCase(label)) {
            sender.sendMessage("StressTestRTP: " + leak.status());
            return true;
        }
        boolean force = false;
        boolean dump = false;
        boolean quick = false;
        for (int i = 2; i < args.length; i++) {
            if ("force".equalsIgnoreCase(args[i])) force = true;
            else if ("dump".equalsIgnoreCase(args[i])) dump = true;
            else if ("quick".equalsIgnoreCase(args[i])) quick = true;
        }
        Runner existingRunner = plugin.runner();
        if (existingRunner != null && existingRunner.isRunning() && !force) {
            sender.sendMessage("StressTestRTP: refusing leakcheck during a run (full GC would pause the "
                    + "measured window); /rtpstress stop first or append 'force'.");
            return true;
        }
        if (existingRunner != null) {
            existingRunner.releaseRosterRefs();
        }
        if (!leak.checkpoint(label, new LeakProbe.Options(dump, quick),
                msg -> Sched.runGlobal(plugin, () -> sender.sendMessage("StressTestRTP: " + msg)))) {
            sender.sendMessage("StressTestRTP: a leakcheck is already running; poll /rtpstress leakcheck status.");
            return true;
        }
        sender.sendMessage("StressTestRTP: leakcheck '" + label + "' started (full GC + class histogram"
                + (quick ? ", quick" : "") + (dump ? " + heap dump" : "")
                + "); poll /rtpstress leakcheck status, rows land in "
                + "runs/leak.csv and runs/leak-metrics.csv.");
        return true;
    }

    @SuppressWarnings("java:S3516")
    private boolean doCoverage(StressSender sender, String[] args) throws IOException {
        CoverageProbe probe = plugin.coverageProbe();
        if (probe == null || !CoverageProbe.agentAttached()) {
            sender.sendMessage("StressTestRTP: coverage unavailable; start the server with the JaCoCo "
                    + "-javaagent (see devstack/docker-compose.coverage.yml).");
            return true;
        }
        String label = args.length >= 2 ? args[1] : "checkpoint";
        CoverageProbe.Result r = probe.dumpAndReset(label);
        sender.sendMessage("StressTestRTP: coverage '" + label + "': " + r.reachedProbes()
                + " probes reached -> " + r.file().getFileName());
        return true;
    }

    @SuppressWarnings("java:S3516")
    private boolean doTp(StressSender sender, String[] args) {
        HarnessTeleporter t = plugin.harnessTeleporter();
        if (t == null || !t.available()) {
            sender.sendMessage("StressTestRTP: direct teleport unavailable (needs Paper async chunk API).");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("StressTestRTP: usage /rtpstress tp <player>");
            return true;
        }
        StressPlayer p = plugin.playerExact(args[1]);
        if (p == null) {
            sender.sendMessage("StressTestRTP: tp: no online player '" + args[1] + "'.");
            return true;
        }
        t.teleport(p, reason -> plugin.getLogger().warning("[StressTestRTP] direct tp " + p.name()
                + " failed: " + reason));
        return true;
    }

    @SuppressWarnings("java:S3516")
    private boolean doChurn(StressSender sender, String[] args) {
        LeakProbe leak = plugin.leakProbe();
        if (leak == null) {
            sender.sendMessage("StressTestRTP: leak probe unavailable.");
            return true;
        }
        Runner existingRunner = plugin.runner();
        if (existingRunner != null && existingRunner.isRunning()) {
            sender.sendMessage("StressTestRTP: refusing churn during a run; /rtpstress stop first.");
            return true;
        }
        int n = args.length >= 2 && !"all".equalsIgnoreCase(args[1]) ? safeInt(args[1], 0) : 0;
        String prefix = args.length >= 3 ? args[2]
                : plugin.config().getString("leak.churn-name-prefix", "");
        int kicked = leak.churn(n, prefix);
        sender.sendMessage("StressTestRTP: churn kicked " + kicked + " player(s)"
                + (prefix == null || prefix.isEmpty() ? "" : " matching '" + prefix + "*'")
                + "; reason '" + LeakProbe.CHURN_KICK_REASON + "'.");
        return true;
    }

    @SuppressWarnings("java:S3516")
    private boolean doStop(StressSender sender) {
        Runner existingRunner = plugin.runner();
        if (existingRunner == null || !existingRunner.isRunning()) {
            sender.sendMessage("StressTestRTP: no run is in progress.");
            return true;
        }
        existingRunner.stop();
        sender.sendMessage("StressTestRTP: run stopped.");
        return true;
    }

    @SuppressWarnings("java:S3516")
    private boolean doStatus(StressSender sender) {
        MetricsRecorder rec = plugin.recorder();
        if (rec == null) {
            sender.sendMessage("StressTestRTP: no run yet. Try /rtpstress start.");
            return true;
        }
        var lat = rec.latenciesSnapshot(true);
        long p50 = MetricsRecorder.percentile(lat, 50);
        long p95 = MetricsRecorder.percentile(lat, 95);
        long p99 = MetricsRecorder.percentile(lat, 99);
        var agg = plugin.sampler().aggregates();
        sender.sendMessage(String.format(
                "StressTestRTP: running=%s total=%d ok=%d in-flight=%d p50=%dms p95=%dms p99=%dms cold=%dms minTPS=%.2f p95MSPT=%.2f peakHeapMB=%d",
                plugin.runner().isRunning(),
                rec.totalAttempts(), rec.successCount(), rec.inFlightCount(),
                p50, p95, p99, rec.coldStartLatencyMs(),
                agg.minTps(), agg.p95Mspt(), agg.peakHeapMb()));
        String ramp = plugin.runner().rampStatus();
        if (!ramp.isEmpty()) sender.sendMessage("  " + ramp);
        return true;
    }

    @SuppressWarnings("java:S3516")
    private boolean doResetCold(StressSender sender) {
        List<String> cmds = plugin.config().getStringList("cache-reset-commands");
        if (cmds.isEmpty()) {
            sender.sendMessage("StressTestRTP: no cache-reset-commands configured. Edit config.yml.");
            return true;
        }
        for (String c : cmds) {
            plugin.dispatchConsoleCommand(c);
        }
        sender.sendMessage("StressTestRTP: ran " + cmds.size() + " cache-reset command(s).");
        return true;
    }

    private boolean doExport(StressSender sender) throws IOException {
        MetricsRecorder rec = plugin.recorder();
        if (rec == null) {
            sender.sendMessage("StressTestRTP: no run yet — nothing to export.");
            return true;
        }
        Path csv = rec.csvPath();
        Path summary = csv.resolveSibling(stripExt(csv.getFileName().toString()) + "-summary.txt");
        var lat = rec.latenciesSnapshot(true);
        var agg = plugin.sampler().aggregates();
        StringBuilder sb = new StringBuilder();
        sb.append("StressTestRTP run summary\n");
        sb.append("generated: ")
          .append(LocalDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append('\n');
        var targetsList = Targets.load(plugin.config(), plugin.getLogger());
        sb.append("target-commands: ").append(Targets.describe(targetsList)).append('\n');
        sb.append("attempts: ").append(rec.totalAttempts())
          .append(" (ok=").append(rec.successCount()).append(")\n");
        sb.append("\n--- Front-page comparison columns (all targets, combined) ---\n");
        sb.append("Cold-start /rtp:    ").append(fmtMs(rec.coldStartLatencyMs())).append('\n');
        sb.append("Warm-queue /rtp:    ").append(fmtMs(MetricsRecorder.median(lat))).append("  (median)\n");
        sb.append("TPS under burst:    ").append(fmtNum(agg.minTps())).append("  (min observed)\n");
        sb.append("MSPT during eval:   ").append(fmtNum(agg.p95Mspt())).append(" ms (p95)\n");
        sb.append("Memory footprint:   ").append(agg.peakHeapMb()).append(" MB peak\n");
        sb.append("\n--- Latency percentiles (success only, all targets) ---\n");
        sb.append("p50: ").append(fmtMs(MetricsRecorder.percentile(lat, 50))).append('\n');
        sb.append("p95: ").append(fmtMs(MetricsRecorder.percentile(lat, 95))).append('\n');
        sb.append("p99: ").append(fmtMs(MetricsRecorder.percentile(lat, 99))).append('\n');
        var observed = rec.observedTargetLabels();
        if (observed.size() > 1) {
            sb.append("\n--- Per-target breakdown (success only) ---\n");
            for (String label : observed) {
                var lt = rec.latenciesSnapshot(true, label);
                sb.append(String.format(Locale.ROOT,
                        "%-20s n=%-4d cold=%s p50=%s p95=%s p99=%s%n",
                        label,
                        lt.size(),
                        fmtMs(rec.coldStartLatencyMs(label)),
                        fmtMs(MetricsRecorder.percentile(lt, 50)),
                        fmtMs(MetricsRecorder.percentile(lt, 95)),
                        fmtMs(MetricsRecorder.percentile(lt, 99))));
            }
        }
        sb.append("\nraw csv: ").append(csv.getFileName()).append('\n');
        sb.append("heap series csv: ")
          .append(stripExt(csv.getFileName().toString())).append("-heap.csv").append('\n');
        Files.writeString(summary, sb.toString(), StandardCharsets.UTF_8);
        sender.sendMessage("StressTestRTP: wrote " + summary.getFileName());
        return true;
    }

    private static String stripExt(String s) {
        int i = s.lastIndexOf('.');
        return i < 0 ? s : s.substring(0, i);
    }

    private static String fmtMs(long ms)   { return ms < 0 ? "n/a" : ms + " ms"; }
    private static String fmtNum(double d) { return d < 0 ? "n/a" : String.format(Locale.ROOT, "%.2f", d); }

    private static int safeInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    @SuppressWarnings("java:S1172")
    public List<String> tabComplete(StressSender sender, String[] args) {
        if (args.length <= 1) {
            String p = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
            return SUBS.stream().filter(s -> s.startsWith(p)).toList();
        }
        if (args.length == 2 && "ramp".equalsIgnoreCase(args[0])) {
            String p = args[1].toLowerCase(Locale.ROOT);
            return targetLabels(Targets.load(plugin.config(), plugin.getLogger())).stream()
                    .filter(l -> l.toLowerCase(Locale.ROOT).startsWith(p)).toList();
        }
        return List.of();
    }
}
