package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;

/**
 * Entry point for the StressTestRTP helper plugin.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@code onEnable}: load config, start the {@link TpsMsptHeapSampler},
 *       register the {@link TeleportProbe} listener, register the
 *       {@code /rtpstress} command. No {@link MetricsRecorder} is created
 *       until the first run starts — {@link #beginRun()} rolls a fresh CSV
 *       file per run, named after the run's start timestamp.</li>
 *   <li>{@code onDisable}: stop any running test, flush the sampler,
 *       unregister the listener.</li>
 * </ol>
 *
 * <p>The class is deliberately thin — every responsibility lives in a
 * dedicated component (Sched / MetricsRecorder / TpsMsptHeapSampler /
 * TeleportProbe / Runner / StressCommand). This mirrors the helpers/
 * convention set by {@code PeriodicWorldSaver}: a single {@link JavaPlugin}
 * subclass that wires components, no business logic in the plugin itself.
 */
public final class StressTestRTPPlugin extends JavaPlugin {

    private TpsMsptHeapSampler sampler;
    private TeleportProbe probe;
    private Runner runner;
    private ConsoleWatcher consoleWatcher;
    private MetricsRecorder recorder; // recreated per run
    private CpuSampler cpuSampler;
    private ChunkLoadCounter chunkCounter;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        long samplePeriod = getConfig().getLong("sample-period-ms", 1000L);
        sampler = new TpsMsptHeapSampler(this, samplePeriod);
        sampler.start();

        // CPU sampler: records process and main-thread CPU at phase boundaries.
        // The main-thread id is whichever thread runs a one-shot sync Bukkit
        // task, which on Spigot/Paper is the server tick thread, and on Folia
        // is the global region scheduler thread (the closest analogue of "main"
        // — region threads aren't pinned, but the global scheduler runs on the
        // primary scheduler dispatch thread).
        cpuSampler = new CpuSampler();
        Sched.runGlobal(this, () -> cpuSampler.recordCurrentThreadAsMain());

        // Register the global chunk-load counter. It accumulates ChunkLoadEvents
        // for the lifetime of this plugin and is read by MetricsRecorder per
        // phase (resetPhase + phaseTotal) and per attempt (snapshot deltas in
        // Runner.dispatchOne / MetricsRecorder.onComplete). The counter is the
        // cleanest available proxy for chunk-I/O caused by a target plugin's
        // teleport pipeline, since the per-thread CPU sampler doesn't bill
        // server-internal chunk-system threads to the calling plugin.
        chunkCounter = new ChunkLoadCounter(this);
        chunkCounter.register();

        // Probe + runner share a recorder *proxy* whose target is swapped
        // by beginRun() each /rtpstress start|burst. When no run is live
        // the proxy drops everything, since the probe's expectation map
        // is also empty in that state.
        RecorderProxy proxy;
        try {
            proxy = new RecorderProxy(this);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "StressTestRTP failed to initialise temp recorder", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        probe = new TeleportProbe(this, proxy);
        probe.register();
        runner = new Runner(this, proxy, probe, sampler, getConfig());
        // Release the runner's in-flight slot the moment a teleport is
        // attributed, instead of waiting for the per-attempt timeout reaper.
        // Without this hook the runner stalls after ~concurrencyCap dispatches:
        // every roster player has a stale deadline entry and the dispatch
        // loop's containsKey guard skips them all until ~30 s elapses.
        probe.setOnAttributed(runner::onAttemptCompleted);

        // Watch console for short-circuit failure messages ("already
        // teleporting!", cooldown rejections, etc.) so the in-flight slot
        // is freed immediately when a target plugin rejects a dispatch
        // without firing PlayerTeleportEvent.
        consoleWatcher = new ConsoleWatcher(this, getConfig(), runner);
        consoleWatcher.start();

        PluginCommand cmd = getCommand("rtpstress");
        if (cmd != null) {
            StressCommand sc = new StressCommand(this);
            cmd.setExecutor(sc);
            cmd.setTabCompleter(sc);
        } else {
            getLogger().warning("Command 'rtpstress' not registered in plugin.yml — /rtpstress will not work.");
        }
        getLogger().info("StressTestRTP enabled. /rtpstress (permission stresstestrtp.admin) is live."
                + (Sched.isFolia() ? " Folia detected — using region/entity schedulers." : ""));
    }

    @Override
    public void onDisable() {
        try {
            if (runner != null && runner.isRunning()) runner.stop();
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Error stopping runner on disable", t);
        }
        if (consoleWatcher != null) consoleWatcher.stop();
        if (probe != null) probe.unregister();
        if (chunkCounter != null) chunkCounter.unregister();
        if (sampler != null) { sampler.stopHeapSeries(); sampler.stop(); }
    }

    /** Roll a new CSV for the next run. Called by {@link StressCommand} on start/burst. */
    public void beginRun() throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dir = getDataFolder().toPath().resolve(getConfig().getString("output-subdir", "runs"));
        Path csv = dir.resolve(stamp + ".csv");
        recorder = new MetricsRecorder(csv);
        if (cpuSampler != null) recorder.setCpuSampler(cpuSampler);
        if (chunkCounter != null) recorder.setChunkCounter(chunkCounter);
        // Roll a per-run heap-pressure-over-time series next to the per-attempt
        // CSV (<stamp>-heap.csv). One row per sampler tick records heap
        // used/committed/max against the cumulative attempt count, so the
        // heap-vs-attempts slope (mb_per_attempt) shows RAM consumed per /rtp.
        if (sampler != null) {
            Path heapCsv = dir.resolve(stamp + "-heap.csv");
            sampler.startHeapSeries(heapCsv, () -> {
                MetricsRecorder r = recorder();
                return r != null ? r.totalAttempts() : 0;
            });
            getLogger().info("StressTestRTP: rolled new heap series CSV \u2192 " + heapCsv);
        }
        // Per-chunk-load CPU cost calibration. When > 0, MetricsRecorder
        // amends process_cpu_ms with (chunks_loaded * chunkLoadCostNs) so
        // that work the server attributes to its own chunk-system threads
        // (e.g. BetterRTP's synchronous PreloadRadius=5) is no longer
        // hidden from cpu_ms_per_attempt_total. Calibrate by running
        // `/rtp test chunk-probe-perf` and reading the `full avg=Nµs`
        // value from the resulting log line.
        double chunkCostUs = getConfig().getDouble("chunk-load-cost-us", 0.0);
        if (chunkCostUs > 0.0) {
            long ns = Math.round(chunkCostUs * 1_000.0);
            recorder.setChunkLoadCostNs(ns);
            getLogger().info(String.format(java.util.Locale.ROOT,
                    "StressTestRTP: chunk-load-cost-us=%.2f \u2192 amending phase CPU "
                            + "with %d ns/chunk-load (chunk_load_cost_ms, cpu_ms_with_chunks).",
                    chunkCostUs, ns));
        }
        // Wire optional per-attempt console echo so operators can see
        // forward progress live without tailing the CSV. Defaults: log
        // successes ON, log failures ON. Both can be flipped via config.
        boolean logSucc = getConfig().getBoolean("log-successful-teleports", true);
        boolean logFail = getConfig().getBoolean("log-failed-teleports", true);
        if (logSucc || logFail) {
            recorder.setAttemptLogger(getLogger(), logSucc, logFail);
        }
        getLogger().info("StressTestRTP: rolled new run CSV → " + csv);
    }

    public Runner runner() { return runner; }
    public TpsMsptHeapSampler sampler() { return sampler; }
    public MetricsRecorder recorder() { return recorder; }

    // ---------------------------------------------------------------------
    // Internal: forwarders so that probe/runner always see the *current*
    // recorder swapped in by beginRun(), without rebuilding them.
    // ---------------------------------------------------------------------

    /**
     * Recorder that delegates to the plugin's current per-run
     * {@link MetricsRecorder}. When no run has started yet, completion
     * callbacks are dropped silently — they correspond to teleports we
     * never expected anyway, since the probe's expectation map is empty.
     */
    private static final class RecorderProxy extends MetricsRecorder {
        private final StressTestRTPPlugin plugin;
        RecorderProxy(StressTestRTPPlugin plugin) throws IOException {
            super(java.nio.file.Files.createTempFile("stresstestrtp-proxy-unused", ".csv"));
            this.plugin = plugin;
        }
        @Override public void onDispatch(Attempt a) {
            MetricsRecorder r = plugin.recorder(); if (r != null) r.onDispatch(a);
        }
        @Override public void onComplete(Attempt a, boolean ok, String why, double x, double z) {
            MetricsRecorder r = plugin.recorder(); if (r != null) r.onComplete(a, ok, why, x, z);
        }
        @Override public void onTimeout(Attempt a) {
            MetricsRecorder r = plugin.recorder(); if (r != null) r.onTimeout(a);
        }
        @Override public void beginPhase(String label) {
            MetricsRecorder r = plugin.recorder(); if (r != null) r.beginPhase(label);
        }
        @Override public void endPhase(String label) {
            MetricsRecorder r = plugin.recorder(); if (r != null) r.endPhase(label);
        }
        @Override public long chunkLoadsTotal() {
            MetricsRecorder r = plugin.recorder();
            return r != null ? r.chunkLoadsTotal() : -1L;
        }
    }

}
