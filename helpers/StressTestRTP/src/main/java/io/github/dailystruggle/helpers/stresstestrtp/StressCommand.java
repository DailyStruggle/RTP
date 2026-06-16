package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

/** {@code /rtpstress} dispatcher. See {@code plugin.yml} usage block. */
public final class StressCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = Arrays.asList(
            "start", "stop", "status", "burst", "sequence", "reset-cold", "export");

    private final StressTestRTPPlugin plugin;

    public StressCommand(StressTestRTPPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!sender.hasPermission("stresstestrtp.admin")) {
            sender.sendMessage("StressTestRTP: missing permission stresstestrtp.admin");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        try {
            switch (sub) {
                case "start":   return doStart(sender, args);
                case "stop":    return doStop(sender);
                case "status":  return doStatus(sender);
                case "burst":   return doBurst(sender, args);
                case "sequence": return doSequence(sender, args);
                case "reset-cold": return doResetCold(sender);
                case "export":  return doExport(sender);
                default:
                    sender.sendMessage("StressTestRTP: unknown subcommand. Try: " + SUBS);
                    return true;
            }
        } catch (Throwable t) {
            sender.sendMessage("StressTestRTP: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            plugin.getLogger().log(Level.WARNING, "/rtpstress " + sub + " failed", t);
            return true;
        }
    }

    private boolean doStart(CommandSender sender, String[] args) throws IOException {
        if (plugin.runner().isRunning()) {
            sender.sendMessage("StressTestRTP: a run is already in progress. /rtpstress stop first.");
            return true;
        }
        FileConfiguration cfg = plugin.getConfig();
        int seconds = args.length >= 2 ? safeInt(args[1], (int) cfg.getLong("default-duration-seconds", 60))
                                       : (int) cfg.getLong("default-duration-seconds", 60);
        int concurrency = args.length >= 3 ? safeInt(args[2], (int) cfg.getLong("default-concurrency", 4))
                                            : (int) cfg.getLong("default-concurrency", 4);
        plugin.beginRun();
        if (!plugin.runner().startTimed(sender, seconds, concurrency)) {
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

    private boolean doBurst(CommandSender sender, String[] args) throws IOException {
        if (plugin.runner().isRunning()) {
            sender.sendMessage("StressTestRTP: a run is already in progress. /rtpstress stop first.");
            return true;
        }
        int n = args.length >= 2 ? safeInt(args[1], (int) plugin.getConfig().getLong("default-burst", 10))
                                  : (int) plugin.getConfig().getLong("default-burst", 10);
        plugin.beginRun();
        if (!plugin.runner().startBurst(sender, n)) {
            sender.sendMessage("StressTestRTP: failed to start burst.");
            return true;
        }
        sender.sendMessage("StressTestRTP: burst dispatched — n=" + n
                + ", csv=" + plugin.recorder().csvPath().getFileName());
        return true;
    }

    private boolean doSequence(CommandSender sender, String[] args) throws IOException {
        if (plugin.runner().isRunning()) {
            sender.sendMessage("StressTestRTP: a run is already in progress. /rtpstress stop first.");
            return true;
        }
        FileConfiguration cfg = plugin.getConfig();
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
        if (!plugin.runner().startSequence(sender, perTarget, gap, concurrency,
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

    private boolean doStop(CommandSender sender) {
        if (!plugin.runner().isRunning()) {
            sender.sendMessage("StressTestRTP: no run is in progress.");
            return true;
        }
        plugin.runner().stop();
        sender.sendMessage("StressTestRTP: run stopped.");
        return true;
    }

    private boolean doStatus(CommandSender sender) {
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
        return true;
    }

    private boolean doResetCold(CommandSender sender) {
        List<String> cmds = plugin.getConfig().getStringList("cache-reset-commands");
        if (cmds.isEmpty()) {
            sender.sendMessage("StressTestRTP: no cache-reset-commands configured. Edit config.yml.");
            return true;
        }
        for (String c : cmds) {
            Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), c);
        }
        sender.sendMessage("StressTestRTP: ran " + cmds.size() + " cache-reset command(s).");
        return true;
    }

    private boolean doExport(CommandSender sender) throws IOException {
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
          .append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append('\n');
        var targetsList = Targets.load(plugin.getConfig(), plugin.getLogger());
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
        // Per-target breakdown — the whole point of supporting multiple
        // target-commands is the head-to-head comparison.
        var observed = rec.observedTargetLabels();
        if (observed.size() > 1) {
            sb.append("\n--- Per-target breakdown (success only) ---\n");
            for (String label : observed) {
                var lt = rec.latenciesSnapshot(true, label);
                sb.append(String.format(java.util.Locale.ROOT,
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
        // Heap-pressure-over-time series for this run: plot heap_used_mb vs
        // attempts_since_start (or read mb_per_attempt) to see RAM per /rtp.
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
    private static String fmtNum(double d) { return d < 0 ? "n/a" : String.format(java.util.Locale.ROOT, "%.2f", d); }

    private static int safeInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length <= 1) return SUBS;
        return List.of();
    }
}
