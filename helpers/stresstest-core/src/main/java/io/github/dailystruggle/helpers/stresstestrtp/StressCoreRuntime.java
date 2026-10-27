package io.github.dailystruggle.helpers.stresstestrtp;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Platform-neutral lifecycle wiring for {@code stresstest-core} shared by
 * {@code stresstest-bukkit} and {@code stresstest-fabric} (ADR-122 Decision 1 & 2).
 */
@SuppressWarnings("java:S6539")
public final class StressCoreRuntime {

    private final StressHost host;
    private final TpsMsptHeapSampler sampler;
    private final TeleportProbe probe;
    private final Runner runner;
    private final ConsoleWatcher consoleWatcher;
    private volatile MetricsRecorder recorder;
    private final CpuSampler cpuSampler;
    private final ChunkLoadCounter chunkCounter;
    private final FoliaRegionMonitor regionMonitor;
    private final GcSampler gcSampler;
    private final ResidencySampler residencySampler;
    private final HeapPressureWatcher heapPressureWatcher;
    private final TicketFootprintProbe ticketFootprintProbe;
    private final DirectTeleportProbe directProbe;
    private final RegionTpsSampler regionTpsSampler;
    private final SchedulerGauge schedulerGauge;
    private final JfrAllocationProfiler jfrProfiler;
    private final LeakProbe leakProbe;
    private final CoverageProbe coverageProbe;
    private final HarnessTeleporter harnessTeleporter;
    private final StressCommand command;

    public StressCoreRuntime(StressHost host) {
        this.host = host;
        Sched.bind(host);
        TickThreadDetector.bind(host);
        host.saveDefaultConfig();

        StressConfig cfg = host.config();
        long samplePeriod = cfg.getLong("sample-period-ms", 50L);
        this.sampler = new TpsMsptHeapSampler(host, samplePeriod);
        this.sampler.start();

        this.cpuSampler = new CpuSampler();
        this.gcSampler = new GcSampler();
        Sched.runGlobal(host, () -> {
            cpuSampler.recordCurrentThreadAsMain();
            TickThreadDetector.captureTickThread();
            gcSampler.setTickThreadId(cpuSampler.mainThreadId());
        });
        this.cpuSampler.startBreakdownTimer(host, cfg.getLong("cpu-breakdown-sample-ms", 1000L));

        if (cfg.getBoolean("jfr-allocation.enabled", true)) {
            this.jfrProfiler = new JfrAllocationProfiler(
                    host.getLogger(),
                    true,
                    cfg.getString("jfr-allocation.throttle", "300/s"),
                    cfg.getLong("jfr-allocation.max-size-mb", 256L) * 1024L * 1024L,
                    resolveTrackedAllocPackages(cfg),
                    host.dataFolder().resolve("temp"));
        } else {
            this.jfrProfiler = null;
        }

        this.chunkCounter = new ChunkLoadCounter(host);
        int sampleStride = cfg.getInt("sync-load-sample-rate", 1);
        if (sampleStride > 1) {
            this.chunkCounter.syncAttributor().setSampleStride(sampleStride);
        }
        this.chunkCounter.register();
        if (cfg.getBoolean("sync-load-selftest", true)) {
            this.chunkCounter.syncAttributor().runSelfTestLater(200L);
        }

        this.regionMonitor = new FoliaRegionMonitor();
        this.regionMonitor.start(host);

        this.residencySampler = new ResidencySampler(host,
                cfg.getLong("ticket-sample-period-ms", 1000L));
        this.residencySampler.setTargetLabel(resolveResidencyTargetLabel(cfg));
        this.residencySampler.register();

        if (cfg.getBoolean("ticket-footprint-probe.enabled", true)) {
            this.ticketFootprintProbe = new TicketFootprintProbe(host,
                    cfg.getInt("ticket-footprint-probe.origin-distance-blocks", 20000),
                    cfg.getLong("ticket-footprint-probe.settle-ticks", 40L),
                    host.dataFolder().resolve("ticket-footprint.txt"));
            this.ticketFootprintProbe.start();
        } else {
            this.ticketFootprintProbe = null;
        }

        RecorderProxy proxy = new RecorderProxy(host);
        this.probe = new TeleportProbe(host, proxy);
        this.probe.register();
        this.directProbe = new DirectTeleportProbe(host, probe);
        this.directProbe.register();

        this.regionTpsSampler = new RegionTpsSampler(host,
                cfg.getLong("region-tps-sample-period-ms", 250L));
        this.regionTpsSampler.start();

        this.runner = new Runner(host, proxy, probe, sampler, cfg);
        this.runner.setRegionTpsSampler(regionTpsSampler);
        if (cfg.getBoolean("scheduler-gauge.enabled", true)) {
            this.schedulerGauge = new SchedulerGauge(host,
                    host.dataFolder().resolve(cfg.getString("output-subdir", "runs"))
                            .resolve("scheduler-gauge.csv"),
                    () -> runner != null && runner.isRunning(),
                    cfg.getBoolean("scheduler-gauge.log", true));
            this.schedulerGauge.start();
        } else {
            this.schedulerGauge = null;
        }
        this.runner.setDirectProbe(directProbe);
        this.probe.setOnAttributed(runner::onAttemptCompleted);

        this.consoleWatcher = new ConsoleWatcher(host, cfg, runner);
        this.consoleWatcher.start();

        this.heapPressureWatcher = new HeapPressureWatcher(host, cfg, sampler);
        this.heapPressureWatcher.start();

        Path runsDir = host.dataFolder().resolve(cfg.getString("output-subdir", "runs"));
        this.leakProbe = new LeakProbe(host, runsDir, cfg.getString("leak.auto-op-prefix", ""));
        this.coverageProbe = new CoverageProbe(runsDir);
        this.leakProbe.register();
        this.harnessTeleporter = new HarnessTeleporter(host, cfg.getInt("leak.direct-radius", 10000));
        this.leakProbe.setTeleporter(harnessTeleporter);

        this.command = new StressCommand(host);
        host.registerCommand("rtpstress", command);
    }

    public void shutdown() {
        try {
            if (runner != null && runner.isRunning()) runner.stop();
        } catch (Throwable t) {
            host.getLogger().log(Level.WARNING, "Error stopping runner on disable", t);
        }
        closeRecorder(recorder);
        if (consoleWatcher != null) consoleWatcher.stop();
        if (probe != null) probe.unregister();
        if (directProbe != null) directProbe.unregister();
        if (regionTpsSampler != null) regionTpsSampler.stop();
        if (schedulerGauge != null) schedulerGauge.stop();
        if (chunkCounter != null) chunkCounter.unregister();
        if (regionMonitor != null) regionMonitor.stop();
        if (residencySampler != null) residencySampler.unregister();
        if (ticketFootprintProbe != null) ticketFootprintProbe.stop();
        if (heapPressureWatcher != null) heapPressureWatcher.stop();
        if (leakProbe != null) leakProbe.unregister();
        if (sampler != null) { sampler.stopHeapSeries(); sampler.stop(); }
        if (cpuSampler != null) cpuSampler.stopBreakdownTimer();
    }

    public static String chunkLoadCostKey(boolean folia, boolean paper) {
        if (folia) return "chunk-load-cost-us-folia";
        if (paper) return "chunk-load-cost-us-paper";
        return "chunk-load-cost-us";
    }

    private void closeRecorder(MetricsRecorder r) {
        if (r == null) return;
        try {
            r.close();
        } catch (Throwable t) {
            host.getLogger().log(Level.WARNING, t, () -> "Error closing run CSV " + r.csvPath());
        }
    }

    public void beginRun() throws IOException {
        StressConfig cfg = host.config();
        String stamp = LocalDateTime.now(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dir = host.dataFolder().resolve(cfg.getString("output-subdir", "runs"));
        Path csv = dir.resolve(stamp + ".csv");
        MetricsRecorder previous = recorder;
        recorder = new MetricsRecorder(csv);
        closeRecorder(previous);
        if (cpuSampler != null) recorder.setCpuSampler(cpuSampler);
        if (chunkCounter != null) recorder.setChunkCounter(chunkCounter);
        if (regionMonitor != null) recorder.setRegionMonitor(regionMonitor);
        if (gcSampler != null) recorder.setGcSampler(gcSampler);
        if (residencySampler != null) recorder.setResidencySampler(residencySampler);
        if (ticketFootprintProbe != null) {
            recorder.setTicketFootprintProbe(ticketFootprintProbe);
            if (ticketFootprintProbe.needsRerun()) ticketFootprintProbe.rerun();
        }
        if (regionTpsSampler != null) recorder.setRegionTpsSampler(regionTpsSampler);
        if (jfrProfiler != null) {
            recorder.setJfrAllocationProfiler(jfrProfiler);
        }
        recorder.setTpsScope(resolveTpsScope());
        if (heapPressureWatcher != null) {
            recorder.setHeapPressureWatcher(heapPressureWatcher);
            Path triggerCsv = dir.resolve(stamp + "-heap-triggers.csv");
            try {
                heapPressureWatcher.startSidecar(triggerCsv);
            } catch (IOException e) {
                host.getLogger().log(Level.WARNING,
                        "StressTestRTP: could not open heap-trigger sidecar; phase columns still recorded", e);
            }
        }
        try {
            List<StressWorld> worlds = host.worlds();
            if (!worlds.isEmpty() && worlds.get(0).worldFolder() != null) {
                recorder.setStorageProfiler(new StorageProfiler(host, worlds.get(0).worldFolder()));
            }
        } catch (Throwable t) {
            host.getLogger().log(Level.WARNING,
                    "StressTestRTP: could not wire storage profiler; storage columns stay -1", t);
        }
        if (sampler != null) {
            Path heapCsv = dir.resolve(stamp + "-heap.csv");
            sampler.startHeapSeries(heapCsv, () -> {
                MetricsRecorder r = recorder();
                return r != null ? r.totalAttempts() : 0;
            });
        }
        String chunkCostKey = chunkLoadCostKey(Runner.isFoliaFamily(), Runner.isPaperFamily());
        double chunkCostUs = cfg.getDouble(chunkCostKey, 0.0);
        if (chunkCostUs > 0.0) {
            recorder.setChunkLoadCostNs(Math.round(chunkCostUs * 1_000.0));
        }
        boolean logSucc = cfg.getBoolean("log-successful-teleports", true);
        boolean logFail = cfg.getBoolean("log-failed-teleports", true);
        if (logSucc || logFail) {
            recorder.setAttemptLogger(host.getLogger(), logSucc, logFail);
        }
    }

    private String resolveResidencyTargetLabel(StressConfig cfg) {
        String explicit = cfg.getString("residency-target-plugin", "");
        if (explicit != null && !explicit.isBlank()) return explicit.trim();
        StressConfig sec = cfg.getConfigurationSection("target-commands");
        if (sec == null) return "";
        Set<String> keys = sec.getKeys(false);
        return keys.size() == 1 ? keys.iterator().next() : "";
    }

    private static Map<String, String> resolveTrackedAllocPackages(StressConfig cfg) {
        Map<String, String> m = JfrAllocationProfiler.defaultTrackedPrefixes();
        StressConfig sec = cfg.getConfigurationSection("jfr-allocation.tracked-packages");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                String prefix = sec.getString(key, "");
                if (prefix != null && !prefix.isBlank()) {
                    m.put(key.toLowerCase(Locale.ROOT), prefix.trim());
                }
            }
        }
        return m;
    }

    private String resolveTpsScope() {
        if (!Sched.isFolia()) return "SERVER_NATIVE";
        return (regionTpsSampler != null && regionTpsSampler.available())
                ? "FOLIA_PLAYER_REGIONS" : "GLOBAL_REGION_TIMER";
    }

    public Runner runner() { return runner; }
    public LeakProbe leakProbe() { return leakProbe; }
    public CoverageProbe coverageProbe() { return coverageProbe; }
    public HarnessTeleporter harnessTeleporter() { return harnessTeleporter; }
    public TpsMsptHeapSampler sampler() { return sampler; }
    public TicketFootprintProbe ticketFootprintProbe() { return ticketFootprintProbe; }
    public MetricsRecorder recorder() { return recorder; }
    public StressCommand command() { return command; }

    public static final class RecorderProxy extends MetricsRecorder {
        private final StressHost plugin;
        public RecorderProxy(StressHost plugin) {
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
        @Override public void onComplete(Attempt a, boolean ok, String why, double x, double z,
                                         AttributionSource source, boolean deferRow) {
            MetricsRecorder r = plugin.recorder();
            if (r != null) r.onComplete(a, ok, why, x, z, source, deferRow);
        }
        @Override public boolean releaseDeferred(Attempt a) {
            MetricsRecorder r = plugin.recorder(); return r != null && r.releaseDeferred(a);
        }
        @Override public boolean isDeferred(Attempt a) {
            MetricsRecorder r = plugin.recorder(); return r != null && r.isDeferred(a);
        }
        @Override public void flushDeferred(boolean all) {
            MetricsRecorder r = plugin.recorder(); if (r != null) r.flushDeferred(all);
        }
        @Override public void flushPartialPhase() {
            MetricsRecorder r = plugin.recorder(); if (r != null) r.flushPartialPhase();
        }
        @Override public void flushRows() {
            MetricsRecorder r = plugin.recorder(); if (r != null) r.flushRows();
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
        @Override public Path csvPath() {
            MetricsRecorder r = plugin.recorder();
            return r != null ? r.csvPath() : null;
        }
        @Override public Path phasesCsvPath() {
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
        @Override public List<Attempt> finishedDispatchedBetween(long from, long to, String targetLabel) {
            MetricsRecorder r = plugin.recorder();
            return r != null ? r.finishedDispatchedBetween(from, to, targetLabel) : Collections.emptyList();
        }
        @Override public List<Long> latenciesSnapshot(boolean successOnly) {
            MetricsRecorder r = plugin.recorder();
            return r != null ? r.latenciesSnapshot(successOnly) : Collections.emptyList();
        }
        @Override public List<Long> latenciesSnapshot(boolean successOnly, String targetLabel) {
            MetricsRecorder r = plugin.recorder();
            return r != null ? r.latenciesSnapshot(successOnly, targetLabel) : Collections.emptyList();
        }
        @Override public List<String> observedTargetLabels() {
            MetricsRecorder r = plugin.recorder();
            return r != null ? r.observedTargetLabels() : Collections.emptyList();
        }
        @Override public void setAttemptLogger(Logger logger,
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
        @Override public CpuSampler cpuSampler() {
            MetricsRecorder r = plugin.recorder();
            return r != null ? r.cpuSampler() : super.cpuSampler();
        }
        @Override public void setCpuSampler(CpuSampler sampler) {
            super.setCpuSampler(sampler);
            MetricsRecorder r = plugin.recorder();
            if (r != null) r.setCpuSampler(sampler);
        }
        @Override public void setNextPhaseIdleBaseline(IdleBaseline baseline) {
            super.setNextPhaseIdleBaseline(baseline);
            MetricsRecorder r = plugin.recorder();
            if (r != null) r.setNextPhaseIdleBaseline(baseline);
        }
    }
}
