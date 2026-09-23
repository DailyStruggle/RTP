package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
 *       until the first run starts - {@link #beginRun()} rolls a fresh CSV
 *       file per run, named after the run's start timestamp.</li>
 *   <li>{@code onDisable}: stop any running test, flush the sampler,
 *       unregister the listener.</li>
 * </ol>
 *
 * <p>The class is deliberately thin - every responsibility lives in a
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
    private FoliaRegionMonitor regionMonitor;
    private GcSampler gcSampler;
    private ResidencySampler residencySampler;
    private HeapPressureWatcher heapPressureWatcher;
    private TicketFootprintProbe ticketFootprintProbe;
    private DirectTeleportProbe directProbe;
    private RegionTpsSampler regionTpsSampler;
    private JfrAllocationProfiler jfrProfiler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        long samplePeriod = getConfig().getLong("sample-period-ms", 50L);
        sampler = new TpsMsptHeapSampler(this, samplePeriod);
        sampler.start();

        // CPU sampler: records process and main-thread CPU at phase boundaries.
        // The main-thread id is whichever thread runs a one-shot sync Bukkit
        // task, which on Spigot/Paper is the server tick thread, and on Folia
        // is the global region scheduler thread (the closest analogue of "main"
        // - region threads aren't pinned, but the global scheduler runs on the
        // primary scheduler dispatch thread).
        cpuSampler = new CpuSampler();
        Sched.runGlobal(this, () -> {
            cpuSampler.recordCurrentThreadAsMain();
            // Same hop records the tick-thread identity used to split chunk
            // loads into foreground (on-tick) and background (off-tick) and to
            // bracket tick-thread occupancy intervals. On Folia the per-chunk
            // region-ownership query is preferred at query time; this id is the
            // fallback. Runtime detection only - no build variant, no toggle.
            TickThreadDetector.captureTickThread();
            // The GC sampler's allocation column must describe the same thread
            // the CPU column describes, so it is bound from the same hop.
            if (gcSampler != null) gcSampler.setTickThreadId(cpuSampler.mainThreadId());
        });

        // GC / tick-thread-allocation accounting. Heap-used is already sampled
        // every 50 ms, but a heap curve cannot separate retained bytes from
        // churned ones: collection count and time supply the churn term and
        // getThreadAllocatedBytes supplies the rate producing it. Pure JMX
        // counter reads at phase boundaries - nothing scheduled, no tick work.
        gcSampler = new GcSampler();

        // Per-plugin allocation profiler (Flight Recorder). GcSampler answers
        // "how often did GC run" and the heap series "how much churned", but on
        // a shared JVM neither can charge that churn - or the global STW pauses
        // it causes - to a specific plugin. jdk.ObjectAllocationSample can:
        // throttled, low-overhead, equal across arms. Config-gated; if Flight
        // Recorder is unavailable the profiler degrades to the -1 sentinel.
        if (getConfig().getBoolean("jfr-allocation.enabled", true)) {
            jfrProfiler = new JfrAllocationProfiler(
                    getLogger(),
                    true,
                    getConfig().getString("jfr-allocation.throttle", "300/s"),
                    getConfig().getLong("jfr-allocation.max-size-mb", 256L) * 1024L * 1024L,
                    resolveTrackedAllocPackages());
            if (jfrProfiler.available()) {
                getLogger().info("StressTestRTP: per-plugin allocation profiling on "
                        + "(jdk.ObjectAllocationSample); target/total bytes per phase plus "
                        + "a <stamp>-jfr-alloc.csv per-package breakdown.");
            }
        } else {
            getLogger().info("StressTestRTP: jfr-allocation disabled; jfr_alloc_* columns stay -1.");
        }

        // Register the global chunk-load counter. It accumulates ChunkLoadEvents
        // for the lifetime of this plugin and is read by MetricsRecorder per
        // phase (resetPhase + phaseTotal) and per attempt (snapshot deltas in
        // Runner.dispatchOne / MetricsRecorder.onComplete). The counter is the
        // cleanest available proxy for chunk-I/O caused by a target plugin's
        // teleport pipeline, since the per-thread CPU sampler doesn't bill
        // server-internal chunk-system threads to the calling plugin.
        chunkCounter = new ChunkLoadCounter(this);
        chunkCounter.register();

        // Folia region-context accounting and freeze detection. Gated purely on
        // runtime detection (Sched.isFolia()): off Folia the monitor stays
        // inert and every region column writes the -1 not-measured sentinel,
        // so the CSV schema is identical on every platform.
        regionMonitor = new FoliaRegionMonitor();
        regionMonitor.start(this);

        // Residency accounting: peak resident chunks and peak plugin chunk
        // tickets. This is the observable proxy for the platform-owned
        // retained term no JVM counter attributes to a plugin, and the axis on
        // which the designs actually differ (coordinate tuples plus released
        // tickets against resident chunk neighbourhoods). Chunk residency is a
        // load/unload delta over a one-time baseline; the ticket census runs on
        // its own slow timer and never on an attempt path.
        residencySampler = new ResidencySampler(this,
                getConfig().getLong("ticket-sample-period-ms", 1000L));
        residencySampler.setTargetLabel(resolveResidencyTargetLabel());
        residencySampler.register();

        // Setup-phase calibration of what one plugin chunk ticket actually
        // costs. residencySampler reports resident chunks and held tickets,
        // but converting between them needs the platform's ticket footprint,
        // which vanilla, Paper and Folia each decide differently. Measured
        // once here - on an idle server, before any teleport is recorded - so
        // every run carries the multiplier its own numbers were produced
        // under instead of inheriting an assumed one.
        if (getConfig().getBoolean("ticket-footprint-probe.enabled", true)) {
            ticketFootprintProbe = new TicketFootprintProbe(this,
                    getConfig().getInt("ticket-footprint-probe.origin-distance-blocks", 20000),
                    getConfig().getLong("ticket-footprint-probe.settle-ticks", 40L),
                    getDataFolder().toPath().resolve("ticket-footprint.txt"));
            ticketFootprintProbe.start();
        } else {
            getLogger().info("StressTestRTP: ticket-footprint probe disabled; "
                    + "ticket_footprint_* columns stay -1.");
        }

        // Probe + runner share a recorder *proxy* whose target is swapped
        // by beginRun() each /rtpstress start|burst. When no run is live
        // the proxy drops everything, since the probe's expectation map
        // is also empty in that state.
        RecorderProxy proxy = new RecorderProxy(this);
        probe = new TeleportProbe(this, proxy);
        probe.register();
        // Direct attribution from LeafRTP's own Pre/PostTeleportEvent when it
        // is the plugin under test. Resolved by class name; a no-op for every
        // other arm, whose rows keep the external observation channels.
        directProbe = new DirectTeleportProbe(this, probe);
        directProbe.register();
        // Direct attribution is made authoritative PER ARM by the Runner, not
        // globally here: when LeafRTP's own teleport events are present, the
        // poll and PlayerTeleportEvent are suppressed only while the LeafRTP
        // arm is dispatching (so a cold teleport is attributed at its
        // PostTeleportEvent instant instead of losing the race to the poll,
        // which re-adds the ~100 ms Folia destination-tick floor). Every other
        // arm keeps the external channels, since LeafRTP fires no events for
        // it - a permanent global flag here previously suppressed all
        // attribution for competitor arms, so they logged only TIMEOUTs.
        if (directProbe.available()) {
            getLogger().info("[StressTestRTP] Direct attribution available for the rtp arm; "
                    + "poll and PlayerTeleportEvent will be suppressed only while that arm dispatches.");
        }
        // Folia per-region TPS. The tps column on Folia is a global-region
        // wall-clock timer and never sees player-region load; this sampler
        // reads Server#getRegionTPS for each online player's owning region.
        regionTpsSampler = new RegionTpsSampler(this,
                getConfig().getLong("region-tps-sample-period-ms", 250L));
        regionTpsSampler.start();
        runner = new Runner(this, proxy, probe, sampler, getConfig());
        runner.setRegionTpsSampler(regionTpsSampler);
        runner.setDirectProbe(directProbe);
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

        // Heap-pressure control-loop evidence. Records the trigger line and
        // the heap level at which a plugin under test observably changes
        // behaviour under heap pressure. Evidence only - no response is
        // inferred, modelled, or attributed from a match.
        heapPressureWatcher = new HeapPressureWatcher(this, getConfig(), sampler);
        heapPressureWatcher.start();

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
        if (directProbe != null) directProbe.unregister();
        if (regionTpsSampler != null) regionTpsSampler.stop();
        if (chunkCounter != null) chunkCounter.unregister();
        if (regionMonitor != null) regionMonitor.stop();
        if (residencySampler != null) residencySampler.unregister();
        if (ticketFootprintProbe != null) ticketFootprintProbe.stop();
        if (heapPressureWatcher != null) heapPressureWatcher.stop();
        if (sampler != null) { sampler.stopHeapSeries(); sampler.stop(); }
    }

    /** Roll a new CSV for the next run. Called by {@link StressCommand} on start/burst. */
    public void beginRun() throws IOException {
        String stamp = LocalDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dir = getDataFolder().toPath().resolve(getConfig().getString("output-subdir", "runs"));
        Path csv = dir.resolve(stamp + ".csv");
        recorder = new MetricsRecorder(csv);
        if (cpuSampler != null) recorder.setCpuSampler(cpuSampler);
        if (chunkCounter != null) recorder.setChunkCounter(chunkCounter);
        if (regionMonitor != null) recorder.setRegionMonitor(regionMonitor);
        if (gcSampler != null) recorder.setGcSampler(gcSampler);
        if (residencySampler != null) recorder.setResidencySampler(residencySampler);
        if (ticketFootprintProbe != null) {
            recorder.setTicketFootprintProbe(ticketFootprintProbe);
            // A start-up probe that never saw its ticket load (cold Folia
            // start, chunk still generating) is NOT MEASURED, not zero; give
            // it one more go on a server that is now idle before this run.
            if (ticketFootprintProbe.needsRerun()) ticketFootprintProbe.rerun();
        }
        if (regionTpsSampler != null) recorder.setRegionTpsSampler(regionTpsSampler);
        if (jfrProfiler != null) {
            recorder.setJfrAllocationProfiler(jfrProfiler);
            if (jfrProfiler.available()) {
                getLogger().info("StressTestRTP: per-plugin allocation sidecar \u2192 "
                        + recorder.jfrAllocPath());
            }
        }
        recorder.setTpsScope(resolveTpsScope());
        // Heap-pressure evidence: per-phase columns plus a per-run sidecar of
        // every trigger (<stamp>-heap-triggers.csv). A missing sidecar does not
        // disable the columns - the count is still a measurement.
        if (heapPressureWatcher != null) {
            recorder.setHeapPressureWatcher(heapPressureWatcher);
            Path triggerCsv = dir.resolve(stamp + "-heap-triggers.csv");
            try {
                heapPressureWatcher.startSidecar(triggerCsv);
                getLogger().info("StressTestRTP: rolled new heap-trigger sidecar \u2192 " + triggerCsv);
            } catch (IOException e) {
                getLogger().log(Level.WARNING,
                        "StressTestRTP: could not open heap-trigger sidecar; "
                                + "phase columns still recorded", e);
            }
        }
        // Storage characterisation of the world the run teleports within. The
        // cold-read-versus-warm-hit ratio that motivates answering safety from
        // region bytes is device dependent, so the device is recorded rather
        // than assumed. All probing is off-tick inside the profiler.
        try {
            java.util.List<org.bukkit.World> worlds = getServer().getWorlds();
            if (!worlds.isEmpty()) {
                Path worldDir = worlds.get(0).getWorldFolder().toPath();
                recorder.setStorageProfiler(new StorageProfiler(this, worldDir));
                getLogger().info("StressTestRTP: storage profile sidecar \u2192 "
                        + recorder.storageProfilePath());
            } else {
                getLogger().warning("StressTestRTP: no world loaded; storage columns stay -1.");
            }
        } catch (Throwable t) {
            getLogger().log(Level.WARNING,
                    "StressTestRTP: could not wire storage profiler; storage columns stay -1", t);
        }
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

    /**
     * Plugin name whose chunk tickets are broken out into
     * {@code peak_target_plugin_tickets}. Explicit config wins; otherwise the
     * label is inferred only when exactly one target is configured, because a
     * guessed attribution across several arms would be a fabricated reading.
     * An empty result leaves the breakout column at its -1 sentinel.
     */
    private String resolveResidencyTargetLabel() {
        String explicit = getConfig().getString("residency-target-plugin", "");
        if (explicit != null && !explicit.isBlank()) return explicit.trim();
        org.bukkit.configuration.ConfigurationSection sec =
                getConfig().getConfigurationSection("target-commands");
        if (sec == null) return "";
        java.util.Set<String> keys = sec.getKeys(false);
        return keys.size() == 1 ? keys.iterator().next() : "";
    }

    /**
     * Tracked plugin package prefixes for the allocation profiler: the built-in
     * RTP-family + known-competitor defaults merged with (and overridden by) a
     * {@code jfr-allocation.tracked-packages} config section of
     * {@code label: package.prefix} entries. Config supplies packages the
     * defaults do not know (e.g. JustRTP, whose package was not verifiable from
     * the shipped jar), and can correct a prefix if a plugin is repackaged.
     */
    private java.util.Map<String, String> resolveTrackedAllocPackages() {
        java.util.Map<String, String> m = JfrAllocationProfiler.defaultTrackedPrefixes();
        org.bukkit.configuration.ConfigurationSection sec =
                getConfig().getConfigurationSection("jfr-allocation.tracked-packages");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                String prefix = sec.getString(key, "");
                if (prefix != null && !prefix.isBlank()) {
                    m.put(key.toLowerCase(java.util.Locale.ROOT), prefix.trim());
                }
            }
        }
        return m;
    }

    /** Names what the {@code tps} column measures on this server so the
     *  phase row states its own scope instead of implying a server-wide TPS. */
    private String resolveTpsScope() {
        if (!Sched.isFolia()) return "SERVER_NATIVE";
        return (regionTpsSampler != null && regionTpsSampler.available())
                ? "FOLIA_PLAYER_REGIONS" : "GLOBAL_REGION_TIMER";
    }

    public Runner runner() { return runner; }
    public TpsMsptHeapSampler sampler() { return sampler; }
    public TicketFootprintProbe ticketFootprintProbe() { return ticketFootprintProbe; }
    public MetricsRecorder recorder() { return recorder; }

    // ---------------------------------------------------------------------
    // Internal: forwarders so that probe/runner always see the *current*
    // recorder swapped in by beginRun(), without rebuilding them.
    // ---------------------------------------------------------------------

    /**
     * Recorder that delegates to the plugin's current per-run
     * {@link MetricsRecorder}. When no run has started yet, completion
     * callbacks are dropped silently - they correspond to teleports we
     * never expected anyway, since the probe's expectation map is empty.
     */
    private static final class RecorderProxy extends MetricsRecorder {
        private final StressTestRTPPlugin plugin;
        RecorderProxy(StressTestRTPPlugin plugin) {
            super();
            this.plugin = plugin;
        }
        @Override public void onDispatch(Attempt a) {
            MetricsRecorder r = plugin.recorder(); if (r != null) r.onDispatch(a);
        }
        @Override public void onComplete(Attempt a, boolean ok, String why, double x, double z) {
            MetricsRecorder r = plugin.recorder(); if (r != null) r.onComplete(a, ok, why, x, z);
        }
        @Override public void onComplete(Attempt a, boolean ok, String why, double x, double z,
                                         AttributionSource source) {
            MetricsRecorder r = plugin.recorder(); if (r != null) r.onComplete(a, ok, why, x, z, source);
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
        @Override public java.nio.file.Path csvPath() {
            MetricsRecorder r = plugin.recorder();
            return r != null ? r.csvPath() : null;
        }
        @Override public java.nio.file.Path phasesCsvPath() {
            MetricsRecorder r = plugin.recorder();
            return r != null ? r.phasesCsvPath() : null;
        }
        @Override public FoliaRegionMonitor regionMonitor() {
            MetricsRecorder r = plugin.recorder();
            return r != null ? r.regionMonitor() : null;
        }
        @Override public void setRecording(boolean enabled) {
            super.setRecording(enabled);
            MetricsRecorder r = plugin.recorder();
            if (r != null) r.setRecording(enabled);
        }
        @Override public boolean isRecording() {
            MetricsRecorder r = plugin.recorder();
            return r != null ? r.isRecording() : super.isRecording();
        }
        @Override public int totalAttempts() {
            MetricsRecorder r = plugin.recorder();
            return r != null ? r.totalAttempts() : 0;
        }
        @Override public int successCount() {
            MetricsRecorder r = plugin.recorder();
            return r != null ? r.successCount() : 0;
        }
        @Override public int inFlightCount() {
            MetricsRecorder r = plugin.recorder();
            return r != null ? r.inFlightCount() : 0;
        }
        @Override public long coldStartLatencyMs() {
            MetricsRecorder r = plugin.recorder();
            return r != null ? r.coldStartLatencyMs() : -1L;
        }
        @Override public long coldStartLatencyMs(String targetLabel) {
            MetricsRecorder r = plugin.recorder();
            return r != null ? r.coldStartLatencyMs(targetLabel) : -1L;
        }
        @Override public java.util.List<Long> latenciesSnapshot(boolean successOnly) {
            MetricsRecorder r = plugin.recorder();
            return r != null ? r.latenciesSnapshot(successOnly) : java.util.Collections.emptyList();
        }
        @Override public java.util.List<Long> latenciesSnapshot(boolean successOnly, String targetLabel) {
            MetricsRecorder r = plugin.recorder();
            return r != null ? r.latenciesSnapshot(successOnly, targetLabel) : java.util.Collections.emptyList();
        }
        @Override public java.util.List<String> observedTargetLabels() {
            MetricsRecorder r = plugin.recorder();
            return r != null ? r.observedTargetLabels() : java.util.Collections.emptyList();
        }
        @Override public void setAttemptLogger(java.util.logging.Logger logger,
                                               boolean logSuccessful, boolean logFailures) {
            super.setAttemptLogger(logger, logSuccessful, logFailures);
            MetricsRecorder r = plugin.recorder();
            if (r != null) r.setAttemptLogger(logger, logSuccessful, logFailures);
        }
        @Override public void setChunkLoadCostNs(long ns) {
            super.setChunkLoadCostNs(ns);
            MetricsRecorder r = plugin.recorder();
            if (r != null) r.setChunkLoadCostNs(ns);
        }
        @Override public long chunkLoadCostNs() {
            MetricsRecorder r = plugin.recorder();
            return r != null ? r.chunkLoadCostNs() : super.chunkLoadCostNs();
        }
    }

}
