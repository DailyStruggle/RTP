package io.github.dailystruggle.helpers.stresstestrtp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Run loop: dispatches the configured command against the roster while
 * respecting concurrency, attempt timeouts, and the run duration (ADR-122 Decision 1 & 2).
 */
public final class Runner {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public enum Mode { TIMED, BURST, SEQUENCE, WARMUP, RAMP }

    enum RampState { IDLE, WARMUP, SETTLE, RUNNING, DRAIN }

    private final StressHost plugin;
    private final MetricsRecorder recorder;
    private final TeleportProbe probe;
    private final TpsMsptHeapSampler sampler;
    private final StressConfig config;
    private final SparkHook spark;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean ticking = new AtomicBoolean(false);
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final AtomicInteger targetCursor = new AtomicInteger(0);
    private final AtomicInteger rosterCursor = new AtomicInteger(0);
    private volatile List<Targets.Entry> roundRobinTargets = null;
    private final List<StressPlayer> rosterBuffer = new ArrayList<>();
    private final Map<StressPlayer, Boolean> distanceCapped =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final ConcurrentHashMap<UUID, Long> deadlines = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> nextDispatchAt = new ConcurrentHashMap<>();
    private final AtomicReference<UUID> lastDispatched = new AtomicReference<>();

    private volatile Object taskId = null;
    private volatile long endEpochMs = 0L;
    private volatile int concurrencyCap = 4;
    private volatile Mode mode = Mode.TIMED;
    private int burstRemaining = 0;
    private volatile String operatorName = "console";
    private volatile UUID operatorId = null;

    private volatile List<Targets.Entry> seqTargets = null;
    private int seqIndex = 0;
    private volatile long seqPerTargetMs = 0L;
    private volatile long seqGapMs = 0L;
    private volatile long seqPhaseEndMs = 0L;
    private volatile long seqGapEndMs = 0L;

    private boolean gapCalibActive = false;
    private long gapCalibStartEpochMs = 0L;
    private long gapCalibStartProcCpuNs = -1L;
    private long gapCalibStartMainCpuNs = -1L;
    private final List<Double> gapCalibMsptSamples = new ArrayList<>();
    private long lastGapMsptSampleMs = 0L;
    private volatile long seqPerTargetCount = 0L;
    private final AtomicInteger seqPhaseDispatched = new AtomicInteger(0);

    private volatile boolean warmupActive = false;
    private volatile long warmupSliceMs = 0L;
    private volatile int warmupCycles = 1;
    private int warmupCycleIdx = 0;
    private int warmupTargetIdx = 0;
    private volatile long warmupSliceEndMs = 0L;
    private volatile long warmupSliceDrainDeadline = 0L;
    private volatile int warmupSliceStartTotal = 0;
    private volatile long warmupSliceStartEpoch = 0L;
    private volatile java.io.PrintWriter warmupLog = null;

    private volatile long lastProgressEpochMs = 0L;
    private volatile long lastDispatchStartEpochMs = 0L;
    private int kickstartCount = 0;
    private final AtomicBoolean kickQueued = new AtomicBoolean(false);

    private volatile Targets.Entry rampTarget = null;
    private double[] rampStages = new double[0];
    private long rampStageMs = 60_000L;
    private long rampWarmupMs = 0L;
    private long rampIdleMs = 0L;
    private MetricsRecorder.IdleBaseline rampIdleBaseline = null;
    private long rampGapMs = 0L;
    private long rampPerPlayerGapMs = 0L;
    private volatile RampState rampState = RampState.SETTLE;
    private volatile int rampStageIdx = -1;
    private int rampNextIdx = 0;
    private boolean rampInWarmup = false;
    private long rampStateStartMs = 0L;
    private long rampStageEndMs = 0L;
    private long rampDrainDeadlineMs = 0L;
    private long rampSettleEndMs = 0L;
    private long rampIssued = 0L;
    private int rampShed = 0;
    private int rampPeakInFlight = 0;
    private Sched.TimerStats rampTimersAtStart = new Sched.TimerStats(0L, 0L, 0L);
    private long rampLiveTimersAtStart = 0L;
    private long rampMaxLiveBetweenStages = 0L;
    private volatile String rampProvenance = "";
    private int rampRosterSize = 0;
    private boolean rampAborted = false;
    private final List<Double> rampMsptSamples = Collections.synchronizedList(new ArrayList<>());
    private double rampTpsMin = -1.0;
    private long rampHeapPeakMb = -1L;
    private long rampLastSampleMs = 0L;
    private volatile RampEvaluator rampEval = null;
    private RampEvaluator.LowTpsWatch rampLowTps = null;
    private volatile RampWriter rampWriter = null;
    private List<Pattern> rampBusyPatterns = List.of();
    private boolean rampSparkStarted = false;
    private final AtomicBoolean rampFinalized = new AtomicBoolean(true);
    private volatile Object rampTpsTaskId = null;
    private volatile double rampWallTps = -1.0;
    private volatile long rampLastTpsTickMs = 0L;
    private long rampTpsLastNs = -1L;

    public Runner(StressHost plugin, MetricsRecorder recorder, TeleportProbe probe,
                  TpsMsptHeapSampler sampler, StressConfig config) {
        this(plugin, recorder, probe, sampler, config, new SparkHook(plugin, config, plugin.getLogger()));
    }

    public Runner(StressHost plugin, MetricsRecorder recorder, TeleportProbe probe,
                  TpsMsptHeapSampler sampler, StressConfig config, SparkHook spark) {
        this.plugin = plugin;
        this.recorder = recorder;
        this.probe = probe;
        this.sampler = sampler;
        this.config = config;
        this.spark = spark;
    }

    private volatile RegionTpsSampler regionTpsSampler;
    public void setRegionTpsSampler(RegionTpsSampler s) { this.regionTpsSampler = s; }

    private volatile DirectTeleportProbe directProbe;
    public void setDirectProbe(DirectTeleportProbe p) { this.directProbe = p; }

    private boolean isDirectArm(Targets.Entry entry) {
        DirectTeleportProbe dp = directProbe;
        if (dp == null || !dp.available() || entry == null) return false;
        String owner = dp.ownerPluginName();
        if (owner == null || owner.isBlank()) return false;
        String firstTok = WHITESPACE.split(entry.template.trim(), 2)[0];
        int colon = firstTok.indexOf(':');
        String name = colon > 0 ? firstTok.substring(0, colon) : firstTok;
        return name.equalsIgnoreCase(owner);
    }

    public boolean isRunning() { return running.get(); }

    public void onAttemptCompleted(UUID playerId) {
        if (deadlines.remove(playerId) != null) {
            inFlight.decrementAndGet();
        }
        lastProgressEpochMs = System.currentTimeMillis();
        kickDispatch();
    }

    private void kickDispatch() {
        if (!running.get()) return;
        if (!config.getBoolean("immediate-redispatch", true)) return;
        if (ticking.get()) return;
        if (!kickQueued.compareAndSet(false, true)) return;
        try {
            Sched.runAsync(plugin, () -> {
                kickQueued.set(false);
                tick();
            });
        } catch (Throwable t) {
            kickQueued.set(false);
        }
    }

    public void onConsoleFail(UUID playerId, String reason) {
        Attempt a = probe.forget(playerId);
        if (a != null) {
            recorder.onComplete(a, false, reason == null ? "CONSOLE_FAIL" : reason, 0, 0,
                    AttributionSource.CONSOLE);
        }
        if (deadlines.remove(playerId) != null) {
            inFlight.decrementAndGet();
        }
    }

    public void onConsoleFailFallback(String reason) {
        UUID id = lastDispatched.get();
        if (id != null && deadlines.containsKey(id)) {
            onConsoleFail(id, reason);
        }
    }

    public boolean startTimed(StressSender starter, int seconds, int concurrency) {
        if (!running.compareAndSet(false, true)) return false;
        rememberOperator(starter);
        this.mode = Mode.TIMED;
        this.concurrencyCap = Math.max(1, concurrency);
        this.endEpochMs = System.currentTimeMillis() + seconds * 1000L;
        this.lastProgressEpochMs = System.currentTimeMillis();
        this.kickstartCount = 0;
        this.rosterCursor.set(0);
        this.roundRobinTargets = Targets.load(config, plugin.getLogger());
        long timerPeriodMs = Math.max(5L, config != null ? config.getLong("runner-tick-ms", 20L) : 20L);
        this.taskId = Sched.runAsyncTimer(plugin, this::tick, timerPeriodMs);
        spark.startPhase("timed");
        recorder.beginPhase("timed");
        return true;
    }

    public boolean startSequence(StressSender starter, int perTargetSeconds, int gapSeconds, int concurrency) {
        return startSequence(starter, perTargetSeconds, gapSeconds, concurrency, 0, 1);
    }

    public boolean startSequence(StressSender starter, int perTargetSeconds, int gapSeconds,
                                 int concurrency, int warmupSeconds, int warmupCyclesArg) {
        if (!running.compareAndSet(false, true)) return false;
        rememberOperator(starter);
        this.mode = Mode.SEQUENCE;
        this.concurrencyCap = Math.max(1, concurrency);
        this.seqTargets = Targets.load(config, plugin.getLogger());
        this.seqIndex = 0;
        this.seqPerTargetMs = Math.max(1L, perTargetSeconds) * 1000L;
        this.seqGapMs = Math.max(0L, gapSeconds) * 1000L;
        this.seqPerTargetCount = Math.max(0L, config != null ? config.getLong("per-target-count", 0L) : 0L);
        this.seqPhaseDispatched.set(0);
        long now = System.currentTimeMillis();
        this.seqPhaseEndMs = now + seqPerTargetMs;
        this.seqGapEndMs = 0L;
        this.endEpochMs = now + seqTargets.size() * (seqPerTargetMs + seqGapMs);

        this.warmupCycles = Math.max(1, warmupCyclesArg);
        int n = seqTargets.size();
        long totalWarmupMs = Math.max(0L, warmupSeconds) * 1000L;
        long sliceMs = (n > 0 && warmupCycles > 0)
                ? totalWarmupMs / ((long) n * warmupCycles)
                : 0L;
        this.warmupSliceMs = sliceMs;
        this.warmupActive = totalWarmupMs > 0L && sliceMs > 0L && n > 0;
        this.warmupCycleIdx = 0;
        this.warmupTargetIdx = 0;
        this.warmupSliceDrainDeadline = 0L;

        this.lastProgressEpochMs = now;
        this.lastDispatchStartEpochMs = 0L;
        this.kickstartCount = 0;
        this.rosterCursor.set(0);
        if (warmupActive) {
            openWarmupLog();
            recorder.setRecording(false);
            warmupSliceStartEpoch = now;
            warmupSliceStartTotal = recorder.totalAttempts();
            warmupSliceEndMs = now + warmupSliceMs;
            logWarmup(String.format(Locale.ROOT,
                    "warm-up start: %d targets x %d cycles x %d ms each (total %d ms)",
                    n, warmupCycles, warmupSliceMs, totalWarmupMs));
        } else if (!seqTargets.isEmpty()) {
            spark.startPhase(seqTargets.get(0).label);
            recorder.beginPhase(seqTargets.get(0).label);
        }
        long timerPeriodMs = Math.max(5L, config.getLong("runner-tick-ms", 20L));
        this.taskId = Sched.runAsyncTimer(plugin, this::tick, timerPeriodMs);
        return true;
    }

    private void openWarmupLog() {
        try {
            java.nio.file.Path csv = recorder.csvPath();
            String name = csv.getFileName().toString();
            String base = name.endsWith(".csv") ? name.substring(0, name.length() - 4) : name;
            java.nio.file.Path logPath = csv.resolveSibling(base + "-warmup.log");
            warmupLog = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(logPath,
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING));
            warmupLog.printf("# StressTestRTP warm-up log %s%n", java.time.Instant.now());
            warmupLog.flush();
        } catch (java.io.IOException e) {
            plugin.getLogger().log(Level.WARNING, "warm-up log open failed; continuing without it", e);
            warmupLog = null;
        }
    }

    private void logWarmup(String line) {
        plugin.getLogger().info("[warm-up] " + line);
        if (warmupLog != null) {
            warmupLog.println(line);
            warmupLog.flush();
        }
    }

    public boolean startBurst(StressSender starter, int n) {
        if (!running.compareAndSet(false, true)) return false;
        rememberOperator(starter);
        this.mode = Mode.BURST;
        this.concurrencyCap = Math.max(1, n);
        this.burstRemaining = Math.max(1, n);
        this.endEpochMs = System.currentTimeMillis() + Math.max(30000L,
                config.getLong("attempt-timeout-ms", 30000L) + 5000L);
        this.rosterCursor.set(0);
        this.roundRobinTargets = Targets.load(config, plugin.getLogger());
        long timerPeriodMs = Math.max(5L, config.getLong("runner-tick-ms", 20L));
        this.taskId = Sched.runAsyncTimer(plugin, this::tick, timerPeriodMs);
        spark.startPhase("burst");
        recorder.beginPhase("burst");
        return true;
    }

    static final double[] DEFAULT_RAMP_STAGES = {5, 10, 20, 40, 80, 160, 320};

    public boolean startRamp(StressSender starter, Targets.Entry target, int stageSeconds) throws java.io.IOException {
        return startRamp(starter, target, stageSeconds, null);
    }

    public boolean startRamp(StressSender starter, Targets.Entry target, int stageSeconds,
                             double[] stagesOverride) throws java.io.IOException {
        if (target == null) return false;
        if (!running.compareAndSet(false, true)) return false;
        try {
            this.rampWriter = new RampWriter(recorder.csvPath());
        } catch (java.io.IOException e) {
            running.set(false);
            throw e;
        }
        rememberOperator(starter);
        this.mode = Mode.RAMP;
        this.concurrencyCap = Integer.MAX_VALUE;
        this.rampTarget = target;
        this.rampProvenance = "provenance: pending\n";
        RunProvenance prov = RunProvenance.snapshot(plugin);
        java.nio.file.Path provCsv = recorder.csvPath();
        Sched.runAsync(plugin, () -> {
            try {
                rampProvenance = prov.write(provCsv);
            } catch (java.io.IOException | RuntimeException e) {
                rampProvenance = "provenance: write failed (" + e + ")\n";
                plugin.getLogger().log(Level.WARNING, "[StressTestRTP] run provenance write failed", e);
            }
        });
        double[] arr = stagesOverride != null && stagesOverride.length > 0
                ? Arrays.stream(stagesOverride).filter(d -> d > 0).toArray()
                : config.getDoubleList("ramp.stages").stream()
                        .mapToDouble(Double::doubleValue).filter(d -> d > 0).toArray();
        this.rampStages = arr.length == 0 ? DEFAULT_RAMP_STAGES.clone() : arr;
        long secs = stageSeconds > 0 ? stageSeconds : config.getLong("ramp.stage-seconds", 60L);
        this.rampStageMs = Math.max(1L, secs) * 1000L;
        this.rampWarmupMs = Math.max(0L, config.getLong("ramp.warmup-seconds", 30L)) * 1000L;
        this.rampIdleMs = Math.max(0L, config.getLong("ramp.idle-seconds", 30L)) * 1000L;
        this.rampIdleBaseline = null;
        this.rampGapMs = Math.max(0L, config.getLong("ramp.gap-seconds", 5L)) * 1000L;
        this.rampPerPlayerGapMs = Math.clamp(config.getLong("ramp.per-player-gap-ms", 0L), 0L, 5000L);
        RampEvaluator.Thresholds th = new RampEvaluator.Thresholds(
                config.getDouble("ramp.pass.min-achieved-fraction", 0.95),
                config.getDouble("ramp.pass.max-mspt-p95", 50.0),
                config.getDouble("ramp.pass.max-fail-fraction", 0.01),
                (int) config.getLong("ramp.stop.consecutive-failures", 2L),
                config.getDouble("ramp.stop.low-tps-threshold", 5.0),
                Math.max(0L, config.getLong("ramp.stop.low-tps-seconds", 10L)) * 1000L);
        this.rampEval = new RampEvaluator(th);
        this.rampLowTps = new RampEvaluator.LowTpsWatch(th.lowTpsThreshold(), th.lowTpsWindowMs());
        this.rampBusyPatterns = RampEvaluator.compile(config.getStringList("ramp.busy-patterns"));
        this.rampFinalized.set(false);
        this.rampSparkStarted = false;
        this.rampStageIdx = -1;
        this.rampMaxLiveBetweenStages = 0L;
        this.rampNextIdx = 0;
        this.rampWallTps = -1.0;
        this.rampTpsLastNs = -1L;
        this.rampLastTpsTickMs = System.currentTimeMillis();
        this.rampTpsTaskId = Sched.runGlobalTimer(plugin, this::rampTpsTick, 20L);
        long now = System.currentTimeMillis();
        this.lastProgressEpochMs = now;
        this.kickstartCount = 0;
        this.rosterCursor.set(0);
        this.nextDispatchAt.clear();
        long total = rampIdleMs + rampWarmupMs + rampStages.length * (rampStageMs + rampGapMs);
        this.endEpochMs = now + total;
        gapCalibActive = false;
        if (rampIdleMs > 0L) {
            recorder.setRecording(false);
            rampInWarmup = false;
            rampState = RampState.IDLE;
            resetRampWindow(now);
        } else if (rampWarmupMs > 0L) {
            recorder.setRecording(false);
            rampInWarmup = true;
            rampState = RampState.WARMUP;
            resetRampWindow(now);
        } else {
            rampInWarmup = false;
            beginRampStage(0, now);
        }
        plugin.getLogger().info(() -> String.format(Locale.ROOT,
                "[StressTestRTP] ramp start: target=%s stages=%s stage=%ds idle=%ds warmup=%ds gap=%ds per-player-gap=%dms thresholds=%s csv=%s",
                target.label, Arrays.toString(rampStages), rampStageMs / 1000L, rampIdleMs / 1000L, rampWarmupMs / 1000L,
                rampGapMs / 1000L, rampPerPlayerGapMs, th, rampWriter.csvPath().getFileName()));
        long timerPeriodMs = Math.max(5L, config.getLong("runner-tick-ms", 20L));
        this.taskId = Sched.runAsyncTimer(plugin, this::tick, timerPeriodMs);
        return true;
    }

    private void rampTpsTick() {
        long ns = System.nanoTime();
        if (rampTpsLastNs > 0L) {
            double s = (ns - rampTpsLastNs) / 1_000_000_000.0;
            if (s > 0.0) rampWallTps = Math.min(20.0, 20.0 / s);
        }
        rampTpsLastNs = ns;
        rampLastTpsTickMs = System.currentTimeMillis();
    }

    private String rampStageLabel(int idx) {
        String l = rampTarget == null ? "ramp" : rampTarget.label;
        return l + "@ramp" + idx + "-" + RampEvaluator.fmtRate(rampStages[idx]) + "tps";
    }

    private void resetRampWindow(long now) {
        rampStateStartMs = now;
        rampIssued = 0L;
        rampShed = 0;
        rampPeakInFlight = 0;
        rampAborted = false;
        rampMsptSamples.clear();
        rampTpsMin = -1.0;
        rampHeapPeakMb = -1L;
        rampLastSampleMs = 0L;
        if (rampLowTps != null) rampLowTps.reset();
    }

    private void beginRampStage(int idx, long now) {
        rampStageIdx = idx;
        resetRampWindow(now);
        rampTimersAtStart = Sched.timerStats(Sched.Kind.PLAYER);
        rampLiveTimersAtStart = rampTimersAtStart.live();
        rampMaxLiveBetweenStages = Math.max(rampMaxLiveBetweenStages, rampLiveTimersAtStart);
        long tolerance = Math.max(0L, config.getLong("ramp.repeating-task-tolerance", 2L));
        if (rampLiveTimersAtStart > tolerance) {
            plugin.getLogger().warning(() -> String.format(Locale.ROOT,
                    "[StressTestRTP] ramp stage %d starts with %d live per-player repeating tasks (expected ~0, tolerance %d): scheduled=%d cancelled=%d cancel_failures=%d",
                    idx, rampLiveTimersAtStart, tolerance, rampTimersAtStart.scheduled(),
                    rampTimersAtStart.cancelled(), rampTimersAtStart.cancelFailures()));
        }
        rampStageEndMs = now + rampStageMs;
        rampState = RampState.RUNNING;
        nextDispatchAt.clear();
        lastProgressEpochMs = now;
        if (!rampSparkStarted) {
            spark.startPhase((rampTarget == null ? "ramp" : rampTarget.label) + "-ramp");
            rampSparkStarted = true;
        }
        if (rampIdleBaseline != null) recorder.setNextPhaseIdleBaseline(rampIdleBaseline);
        recorder.beginPhase(rampStageLabel(idx));
        plugin.getLogger().info(() -> String.format(Locale.ROOT,
                "[StressTestRTP] ramp stage %d/%d: offered %s TP/s for %ds",
                idx, rampStages.length - 1, RampEvaluator.fmtRate(rampStages[idx]), rampStageMs / 1000L));
    }

    private void enterRampSettle(long now, long timeoutMs) {
        rampState = RampState.SETTLE;
        rampDrainDeadlineMs = now + timeoutMs + 1000L;
        rampSettleEndMs = now + rampGapMs;
    }

    private void forceClearInFlight() {
        for (UUID id : new ArrayList<>(deadlines.keySet())) {
            Attempt a = probe.forget(id);
            if (a != null) recorder.onTimeout(a);
            deadlines.remove(id);
        }
        inFlight.set(0);
    }

    private void beginIdleCalibration(long now) {
        gapCalibActive = true;
        gapCalibStartEpochMs = now;
        CpuSampler cpu = recorder.cpuSampler();
        gapCalibStartProcCpuNs = cpu != null ? cpu.processCpuTimeNs() : -1L;
        gapCalibStartMainCpuNs = cpu != null ? cpu.mainThreadCpuTimeNs() : -1L;
        gapCalibMsptSamples.clear();
        lastGapMsptSampleMs = 0L;
    }

    private void sampleIdleCalibration(long now) {
        if (now - lastGapMsptSampleMs < 100L) return;
        lastGapMsptSampleMs = now;
        double mspt = sampler != null ? sampler.latest().mspt() : -1.0;
        if (mspt > 0.0) gapCalibMsptSamples.add(mspt);
    }

    private MetricsRecorder.IdleBaseline harvestIdleCalibration(long now) {
        CpuSampler cpu = recorder.cpuSampler();
        MetricsRecorder.IdleBaseline b = idleBaseline(gapCalibStartEpochMs, now,
                gapCalibStartProcCpuNs, cpu != null ? cpu.processCpuTimeNs() : -1L,
                gapCalibStartMainCpuNs, cpu != null ? cpu.mainThreadCpuTimeNs() : -1L,
                gapCalibMsptSamples);
        gapCalibActive = false;
        gapCalibMsptSamples.clear();
        return b;
    }

    static MetricsRecorder.IdleBaseline idleBaseline(long startMs, long endMs,
                                                     long procStartNs, long procEndNs,
                                                     long mainStartNs, long mainEndNs,
                                                     List<Double> msptSamples) {
        long wallNs = (endMs - startMs) * 1_000_000L;
        double procCores = (procStartNs >= 0 && procEndNs >= procStartNs && wallNs > 0)
                ? (double) (procEndNs - procStartNs) / (double) wallNs : -1.0;
        double mainCores = (mainStartNs >= 0 && mainEndNs >= mainStartNs && wallNs > 0)
                ? (double) (mainEndNs - mainStartNs) / (double) wallNs : -1.0;
        double msptP50 = -1.0;
        if (msptSamples != null && !msptSamples.isEmpty()) {
            List<Double> sorted = new ArrayList<>(msptSamples);
            Collections.sort(sorted);
            msptP50 = sorted.get(sorted.size() / 2);
        }
        return new MetricsRecorder.IdleBaseline(msptP50, mainCores, procCores, Math.max(0L, endMs - startMs));
    }

    private void rampTick(long now) {
        long timeoutMs = config.getLong("attempt-timeout-ms", 30000L);
        switch (rampState) {
            case IDLE -> {
                if (now - rampStateStartMs >= rampIdleMs / 2) {
                    if (!gapCalibActive) beginIdleCalibration(now);
                    sampleIdleCalibration(now);
                }
                if (now - rampStateStartMs < rampIdleMs) return;
                rampIdleBaseline = gapCalibActive ? harvestIdleCalibration(now) : null;
                if (rampIdleBaseline != null) {
                    plugin.getLogger().info(() -> String.format(Locale.ROOT,
                            "[StressTestRTP] ramp idle baseline: MSPT p50=%.2f ms, main CPU=%.3f cores, proc CPU=%.3f cores (over %.1fs); applied to every stage",
                            rampIdleBaseline.msptP50(), rampIdleBaseline.mainCpuCores(),
                            rampIdleBaseline.processCpuCores(), rampIdleBaseline.durationMs() / 1000.0));
                }
                if (rampWarmupMs > 0L) {
                    rampInWarmup = true;
                    rampState = RampState.WARMUP;
                    resetRampWindow(now);
                } else {
                    recorder.setRecording(true);
                    beginRampStage(0, now);
                }
            }
            case WARMUP -> {
                rampDispatchDue(now, rampStages[0], timeoutMs);
                if (now - rampStateStartMs >= rampWarmupMs) enterRampSettle(now, timeoutMs);
            }
            case SETTLE -> {
                boolean drained = inFlight.get() <= 0 || deadlines.isEmpty();
                if (!drained && now < rampDrainDeadlineMs) return;
                if (now < rampSettleEndMs) return;
                if (!drained) forceClearInFlight();
                if (rampInWarmup) {
                    rampInWarmup = false;
                    recorder.setRecording(true);
                    plugin.getLogger().info("[StressTestRTP] ramp warm-up complete");
                }
                beginRampStage(rampNextIdx, now);
            }
            case RUNNING -> {
                if (now - rampLastSampleMs >= 100L) {
                    rampLastSampleMs = now;
                    TpsMsptHeapSampler.Snapshot snap = sampler.latest();
                    if (snap.mspt() > 0.0) rampMsptSamples.add(snap.mspt());
                    if (snap.heapUsedMb() > rampHeapPeakMb) rampHeapPeakMb = snap.heapUsedMb();
                    double tps = RampEvaluator.effectiveTps(rampWallTps, now - rampLastTpsTickMs);
                    if (tps >= 0 && (rampTpsMin < 0 || tps < rampTpsMin)) rampTpsMin = tps;
                    if (rampLowTps.observe(now, tps)) {
                        rampAborted = true;
                        plugin.getLogger().warning(() -> String.format(Locale.ROOT,
                                "[StressTestRTP] ramp stage %d aborted: TPS < %.1f for %ds",
                                rampStageIdx, rampEval.thresholds().lowTpsThreshold(),
                                rampEval.thresholds().lowTpsWindowMs() / 1000L));
                    }
                }
                if (rampAborted || now >= rampStageEndMs) {
                    rampStageEndMs = Math.min(now, rampStageEndMs);
                    rampState = RampState.DRAIN;
                    rampDrainDeadlineMs = now + timeoutMs + 1000L;
                    return;
                }
                rampDispatchDue(now, rampStages[rampStageIdx], timeoutMs);
            }
            case DRAIN -> {
                boolean drained = inFlight.get() <= 0 || deadlines.isEmpty();
                if (!drained && now < rampDrainDeadlineMs) return;
                if (!drained) forceClearInFlight();
                completeRampStage(now, timeoutMs, true);
            }
        }
    }

    private void rampDispatchDue(long now, double rate, long timeoutMs) {
        List<StressPlayer> roster = roster();
        rampRosterSize = roster.size();
        long due = RampEvaluator.slotsDue(rate, now - rampStateStartMs, rampIssued);
        boolean exhausted = roster.isEmpty();
        for (long i = 0; i < due && running.get(); i++) {
            rampIssued++;
            StressPlayer p = exhausted ? null : nextIdlePlayer(roster, now);
            if (p == null) {
                exhausted = true;
                rampShed++;
                continue;
            }
            dispatchOne(p, timeoutMs, rampTarget);
            lastDispatchStartEpochMs = System.currentTimeMillis();
            lastProgressEpochMs = lastDispatchStartEpochMs;
            int f = inFlight.get();
            if (f > rampPeakInFlight) rampPeakInFlight = f;
        }
    }

    private StressPlayer nextIdlePlayer(List<StressPlayer> roster, long now) {
        int n = roster.size();
        for (int k = 0; k < n; k++) {
            StressPlayer p = roster.get(Math.floorMod(rosterCursor.getAndIncrement(), n));
            UUID id = p.uuid();
            if (deadlines.containsKey(id)) continue;
            Long readyAt = nextDispatchAt.get(id);
            if (readyAt != null && readyAt > now) continue;
            return p;
        }
        return null;
    }

    private void completeRampStage(long now, long timeoutMs, boolean complete) {
        int idx = rampStageIdx;
        if (idx < 0 || idx >= rampStages.length || rampTarget == null) return;
        long end = Math.max(rampStateStartMs, Math.min(rampStageEndMs, now));
        List<Attempt> atts =
                recorder.finishedDispatchedBetween(rampStateStartMs, end + 1L, rampTarget.label);
        int ok = 0, to = 0, err = 0, busy = 0;
        List<Long> lat = new ArrayList<>();
        for (Attempt a : atts) {
            switch (RampEvaluator.classify(a.success, a.failReason, rampBusyPatterns)) {
                case SUCCESS -> {
                    ok++;
                    long l = a.latencyMs();
                    if (l >= 0) lat.add(l);
                }
                case TIMEOUT -> to++;
                case BUSY -> busy++;
                default -> err++;
            }
        }
        List<Double> ms;
        synchronized (rampMsptSamples) {
            ms = new ArrayList<>(rampMsptSamples);
        }
        Collections.sort(ms);
        double msptP50 = ms.isEmpty() ? -1.0 : ms.get((int) Math.round(0.50 * (ms.size() - 1)));
        double msptP95 = ms.isEmpty() ? -1.0 : ms.get((int) Math.round(0.95 * (ms.size() - 1)));
        double secs = Math.max(0.001, (end - rampStateStartMs) / 1000.0);
        RampEvaluator.StageResult sr = new RampEvaluator.StageResult(idx, rampStages[idx], secs,
                atts.size(), ok, to, err, busy, rampShed, msptP95, rampAborted);
        RampEvaluator.Decision decision = RampEvaluator.Decision.STOP;
        RampEvaluator.Evaluation ev;
        if (complete) {
            decision = rampEval.recordStage(sr, idx >= rampStages.length - 1);
            ev = rampEval.history().get(rampEval.history().size() - 1);
        } else {
            ev = RampEvaluator.evaluate(sr, rampEval.thresholds());
        }
        Sched.TimerStats timersNow = Sched.timerStats(Sched.Kind.PLAYER);
        Sched.TimerStats timersStage = timersNow.minus(rampTimersAtStart);
        RampWriter.Extras x = new RampWriter.Extras(
                MetricsRecorder.percentile(lat, 50), MetricsRecorder.percentile(lat, 95),
                MetricsRecorder.percentile(lat, 99), msptP50, ms.size(), rampTpsMin, rampHeapPeakMb,
                rampRosterSize, rampPeakInFlight, complete, rampStateStartMs, end,
                timersStage.scheduled(), timersStage.cancelled(), rampLiveTimersAtStart, timersNow.live(),
                timersStage.cancelFailures());
        try {
            rampWriter.appendStage(rampTarget.label, ev, x);
        } catch (java.io.IOException e) {
            plugin.getLogger().log(Level.WARNING, "[StressTestRTP] ramp row write failed", e);
        }
        if (plugin.getLogger().isLoggable(Level.INFO)) {
            String verdict;
            if (!complete) verdict = "PARTIAL";
            else verdict = ev.passed() ? "PASS" : "FAIL";
            plugin.getLogger().info(String.format(Locale.ROOT,
                    "[StressTestRTP] ramp stage %d %s: offered=%s achieved=%.2f TP/s attempts=%d ok=%d timeouts=%d errors=%d busy=%d shed=%d p95=%dms mspt_p95=%.2f tps_min=%.2f%s",
                    idx, verdict,
                    RampEvaluator.fmtRate(sr.offeredTps()), sr.achievedTps(), sr.attempts(), ok, to, err,
                    busy, rampShed, x.latencyP95Ms(), msptP95, rampTpsMin,
                    ev.passed() ? "" : " [" + ev.criteriaString() + "]"));
        }
        recorder.endPhase(rampStageLabel(idx));
        rampStageIdx = -1;
        if (!complete) return;
        if (decision == RampEvaluator.Decision.STOP) {
            stop();
        } else {
            rampNextIdx = idx + 1;
            enterRampSettle(now, timeoutMs);
        }
    }

    private void finishRamp(boolean operatorStop) {
        if (!rampFinalized.compareAndSet(false, true)) return;
        RampEvaluator ev = rampEval;
        if (ev == null || rampTarget == null) return;
        if (operatorStop) {
            if (rampStageIdx >= 0 && (rampState == RampState.RUNNING || rampState == RampState.DRAIN)) {
                long now = System.currentTimeMillis();
                rampStageEndMs = Math.min(rampStageEndMs, now);
                completeRampStage(now, 0L, false);
            }
            ev.markOperatorStop();
        }
        String line = ev.summaryLine(rampTarget.label);
        plugin.getLogger().log(Level.INFO, "[StressTestRTP] {0}", line);
        StringBuilder sb = new StringBuilder();
        sb.append("StressTestRTP ramp summary\n");
        sb.append("target: ").append(rampTarget.label).append(" = `").append(rampTarget.template).append("`\n");
        sb.append("stages_tps: ").append(Arrays.toString(rampStages)).append('\n');
        sb.append("stage_seconds: ").append(rampStageMs / 1000L).append('\n');
        sb.append("thresholds: ").append(ev.thresholds()).append('\n');
        sb.append("stress_point_stage: ").append(ev.stressPointIndex()).append('\n');
        sb.append("stress_point_offered_tps: ").append(RampEvaluator.fmtRate(ev.stressPointTps())).append('\n');
        RampEvaluator.Evaluation ff = ev.firstFailure();
        sb.append("first_failure_stage: ").append(ff == null ? -1 : ff.stage().index()).append('\n');
        sb.append("first_failure_criteria: ").append(ff == null ? "" : ff.criteriaString()).append('\n');
        sb.append("stop_reason: ").append(ev.stopReason().name()).append('\n');
        Sched.TimerStats pt = Sched.timerStats(Sched.Kind.PLAYER);
        sb.append("repeating_live_between_stages_max: ").append(rampMaxLiveBetweenStages).append('\n');
        sb.append("repeating_live_at_end: ").append(pt.live()).append('\n');
        sb.append("repeating_cancel_failures: ").append(pt.cancelFailures()).append('\n');
        sb.append(line).append('\n');
        sb.append(rampProvenance);
        try {
            rampWriter.writeSummary(sb.toString());
        } catch (java.io.IOException e) {
            plugin.getLogger().log(Level.WARNING, "[StressTestRTP] ramp summary write failed", e);
        }
    }

    public String rampStatus() {
        if (mode != Mode.RAMP || rampTarget == null) return "";
        RampEvaluator ev = rampEval;
        int idx = rampStageIdx;
        return String.format(Locale.ROOT, "ramp target=%s state=%s stage=%s stress-point=%s",
                rampTarget.label, rampState,
                idx >= 0 && idx < rampStages.length ? idx + "@" + RampEvaluator.fmtRate(rampStages[idx]) + "tps" : "-",
                ev == null || ev.stressPointIndex() < 0 ? "none" : RampEvaluator.fmtRate(ev.stressPointTps()) + "tps");
    }

    private void rememberOperator(StressSender s) {
        if (s != null && s.isPlayer()) {
            operatorName = s.name();
            operatorId = s.playerUuid();
        } else {
            operatorName = "console";
            operatorId = null;
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        Sched.cancel(taskId);
        taskId = null;
        gapCalibActive = false;
        gapCalibMsptSamples.clear();
        if (warmupActive) {
            warmupActive = false;
            warmupSliceDrainDeadline = 0L;
            recorder.setRecording(true);
            if (warmupLog != null) {
                logWarmup("warm-up aborted by stop()");
                warmupLog.close();
                warmupLog = null;
            }
        }
        if (mode == Mode.RAMP) {
            Sched.cancel(rampTpsTaskId);
            rampTpsTaskId = null;
            if (rampInWarmup || rampState == RampState.IDLE) {
                rampInWarmup = false;
                recorder.setRecording(true);
            }
        }
        String label = (mode == Mode.SEQUENCE && seqTargets != null
                && seqIndex >= 0 && seqIndex < seqTargets.size())
                ? seqTargets.get(seqIndex).label
                : (mode == Mode.BURST ? "burst" : "timed");
        if (mode == Mode.RAMP) {
            String base = rampTarget == null ? "ramp" : rampTarget.label;
            spark.stopPhase(base + "-ramp");
        } else {
            spark.stopPhase(label);
            recorder.endPhase(label);
        }
        for (UUID id : new ArrayList<>(deadlines.keySet())) {
            Attempt a = probe.forget(id);
            if (a != null) recorder.onTimeout(a);
            deadlines.remove(id);
        }
        if (mode == Mode.RAMP) {
            inFlight.set(0);
            finishRamp(true);
        }
        recorder.flushRows();
        sampler.stopHeapSeries();
        releaseRosterRefs();
    }

    public boolean releaseRosterRefs() {
        if (running.get()) return false;
        if (!ticking.compareAndSet(false, true)) return false;
        try {
            rosterBuffer.clear();
            return true;
        } finally {
            ticking.set(false);
        }
    }

    private void tick() {
        if (!ticking.compareAndSet(false, true)) return;
        try {
            long now = System.currentTimeMillis();

            long kickstartMs = config.getLong("no-progress-kickstart-ms", 30000L);
            boolean inMeasurementPhase = running.get()
                    && !warmupActive
                    && !(mode == Mode.SEQUENCE && seqGapEndMs > 0L && now < seqGapEndMs)
                    && !(mode == Mode.RAMP && rampState != RampState.RUNNING);
            if (inMeasurementPhase && kickstartMs > 0
                    && lastProgressEpochMs > 0
                    && (now - lastProgressEpochMs) > kickstartMs) {
                kickstartCount++;
                plugin.getLogger().warning(String.format(
                        "[StressTestRTP] no-progress watchdog fired (idle %d ms); kickstart #%d: clearing %d deadlines, %d cooldowns, inFlight=%d",
                        (now - lastProgressEpochMs), kickstartCount,
                        deadlines.size(), nextDispatchAt.size(), inFlight.get()));
                for (UUID id : new ArrayList<>(deadlines.keySet())) {
                    Attempt a = probe.forget(id);
                    if (a != null) recorder.onTimeout(a);
                    deadlines.remove(id);
                }
                inFlight.set(0);
                nextDispatchAt.clear();
                lastProgressEpochMs = now;
            }

            recorder.flushDeferred(false);

            if (inMeasurementPhase) {
                spark.rotateIfDue(now);
                recorder.flushPartialPhase();
            }

            for (var entry : deadlines.entrySet()) {
                if (entry.getValue() <= now) {
                    UUID id = entry.getKey();
                    Attempt a = probe.forget(id);
                    if (a != null) recorder.onTimeout(a);
                    if (deadlines.remove(id) != null) {
                        inFlight.decrementAndGet();
                    }
                }
            }

            if (Sched.isFolia() && !config.getBoolean("folia.pinned-position-watch", true)) {
                double thr = config.getDouble("folia.poll-distance-threshold", 16.0);
                double thr2 = thr * thr;
                for (UUID id : probe.expectingIds()) {
                    Attempt a = probe.peek(id);
                    if (a == null) continue;
                    StressPlayer p = plugin.player(id);
                    if (p == null || !p.isOnline()) continue;
                    double fromX = a.fromX, fromZ = a.fromZ;
                    Sched.runOnPlayer(plugin, p, () -> {
                        try {
                            if (!p.isOnline()) return;
                            StressLocation loc = p.location();
                            if (loc == null) return;
                            double dx = loc.x() - fromX;
                            double dz = loc.z() - fromZ;
                            if ((dx * dx + dz * dz) >= thr2 && probe.attributeByPosition(id, loc)) {
                                lastProgressEpochMs = System.currentTimeMillis();
                            }
                        } catch (Throwable ignored) {
                        }
                    });
                }
            }

            if (mode == Mode.TIMED && now >= endEpochMs && inFlight.get() == 0) {
                stop();
                return;
            }
            if (mode == Mode.BURST && burstRemaining == 0 && inFlight.get() == 0) {
                stop();
                return;
            }
            if (mode == Mode.RAMP) {
                rampTick(now);
                return;
            }
            Targets.Entry pinned = null;
            if (mode == Mode.SEQUENCE && warmupActive) {
                if (warmupSliceDrainDeadline == 0L && now >= warmupSliceEndMs) {
                    int attemptsThisSlice = Math.max(0,
                            recorder.totalAttempts() - warmupSliceStartTotal);
                    long wallMs = Math.max(0L, now - warmupSliceStartEpoch);
                    String label = seqTargets.get(warmupTargetIdx).label;
                    logWarmup(String.format(Locale.ROOT,
                            "  cycle %d/%d  %-14s  %d ms  %d attempts",
                            warmupCycleIdx + 1, warmupCycles, label, wallMs, attemptsThisSlice));
                    warmupSliceDrainDeadline = now + 5000L;
                }
                if (warmupSliceDrainDeadline > 0L) {
                    if (inFlight.get() > 0) {
                        if (now < warmupSliceDrainDeadline) return;
                        for (UUID id : new ArrayList<>(deadlines.keySet())) {
                            Attempt a = probe.forget(id);
                            if (a != null) recorder.onTimeout(a);
                            if (deadlines.remove(id) != null) inFlight.decrementAndGet();
                        }
                    }
                    warmupSliceDrainDeadline = 0L;
                    warmupTargetIdx++;
                    if (warmupTargetIdx >= seqTargets.size()) {
                        warmupTargetIdx = 0;
                        warmupCycleIdx++;
                    }
                    if (warmupCycleIdx >= warmupCycles) {
                        logWarmup("warm-up complete; starting measurement sequence");
                        List<Targets.Entry> kept = new ArrayList<>(seqTargets.size());
                        List<String> dropped = new ArrayList<>();
                        for (Targets.Entry e : seqTargets) {
                            int successes = recorder.latenciesSnapshot(true, e.label).size();
                            if (successes > 0) {
                                kept.add(e);
                            } else {
                                dropped.add(e.label);
                            }
                        }
                        if (!dropped.isEmpty() && !kept.isEmpty()) {
                            seqTargets = Collections.unmodifiableList(kept);
                            plugin.getLogger().info(
                                "[StressTestRTP] warm-up pruned " + dropped.size()
                                    + " target(s) with zero successful attempts: "
                                    + String.join(", ", dropped)
                                    + " (kept " + kept.size() + ": "
                                    + kept.stream().map(e -> e.label)
                                        .reduce((a, b) -> a + ", " + b).orElse("")
                                    + ")");
                        } else if (!dropped.isEmpty()) {
                            plugin.getLogger().warning(
                                "[StressTestRTP] warm-up landed zero successful attempts on every target ("
                                    + String.join(", ", dropped)
                                    + "); keeping full roster so the measurement sequence still runs"
                                    + " — investigate target commands or extend warm-up duration");
                        }
                        if (warmupLog != null) { warmupLog.close(); warmupLog = null; }
                        warmupActive = false;
                        recorder.setRecording(true);
                        long t = System.currentTimeMillis();
                        seqIndex = -1;
                        seqPhaseEndMs = 0L;
                        seqGapEndMs = t + seqGapMs;
                        for (UUID id : new ArrayList<>(deadlines.keySet())) {
                            Attempt a = probe.forget(id);
                            if (a != null) recorder.onTimeout(a);
                            deadlines.remove(id);
                        }
                        inFlight.set(0);
                        nextDispatchAt.clear();
                        lastProgressEpochMs = t;
                        if (seqTargets.isEmpty()) {
                            plugin.getLogger().warning(
                                "[StressTestRTP] warm-up complete but seqTargets is empty; nothing to dispatch");
                        } else {
                            plugin.getLogger().info(String.format(Locale.ROOT,
                                "[StressTestRTP] warm-up complete; settling %ds (inter-phase gap) for TPS recovery before first measurement phase (%d target(s))",
                                seqGapMs / 1000L, seqTargets.size()));
                        }
                        return;
                    }
                    warmupSliceStartEpoch = System.currentTimeMillis();
                    warmupSliceStartTotal = recorder.totalAttempts();
                    warmupSliceEndMs = warmupSliceStartEpoch + warmupSliceMs;
                }
                pinned = seqTargets.get(warmupTargetIdx);
            } else if (mode == Mode.SEQUENCE) {
                if (seqIndex >= seqTargets.size()) {
                    if (inFlight.get() == 0) stop();
                    return;
                }
                if (seqGapEndMs > 0L) {
                    long gapTotalMs = seqGapMs;
                    if (gapTotalMs >= 10000L) {
                        long settleMs = (gapTotalMs >= 120000L) ? 60000L : (gapTotalMs / 2);
                        long calibStartMs = (seqGapEndMs - gapTotalMs) + settleMs;
                        if (now >= calibStartMs && now < seqGapEndMs) {
                            if (!gapCalibActive) beginIdleCalibration(now);
                            sampleIdleCalibration(now);
                        }
                    }
                    if (now < seqGapEndMs) return;

                    if (gapCalibActive) {
                        MetricsRecorder.IdleBaseline baseline = harvestIdleCalibration(now);
                        double msptP50 = baseline.msptP50();
                        double mainCores = baseline.mainCpuCores();
                        double procCores = baseline.processCpuCores();
                        long durationMs = baseline.durationMs();
                        recorder.setNextPhaseIdleBaseline(baseline);
                        int nextIdx = seqIndex + 1;
                        String nextLabel = (nextIdx >= 0 && nextIdx < seqTargets.size()) ? seqTargets.get(nextIdx).label : "unknown";
                        plugin.getLogger().info(() -> String.format(Locale.ROOT,
                                "[StressTestRTP] Idle baseline before phase '%s': MSPT p50=%.2f ms, main CPU=%.3f cores, proc CPU=%.3f cores (calibrated over %.1fs)",
                                nextLabel, msptP50, mainCores, procCores, durationMs / 1000.0));
                    }

                    seqGapEndMs = 0L;
                    seqIndex++;
                    if (seqIndex >= seqTargets.size()) {
                        if (inFlight.get() == 0) { stop(); return; }
                        return;
                    }
                    seqPhaseEndMs = now + seqPerTargetMs;
                    seqPhaseDispatched.set(0);
                    nextDispatchAt.clear();
                    lastProgressEpochMs = now;
                    spark.startPhase(seqTargets.get(seqIndex).label);
                    recorder.beginPhase(seqTargets.get(seqIndex).label);
                } else if (now >= seqPhaseEndMs || seqCountCapReached()) {
                    long drainBase = (now >= seqPhaseEndMs) ? seqPhaseEndMs : now;
                    long drainDeadline = drainBase + 5000L;
                    if (inFlight.get() != 0) {
                        if (now >= drainDeadline) {
                            for (UUID id : new ArrayList<>(deadlines.keySet())) {
                                Attempt a = probe.forget(id);
                                if (a != null) recorder.onTimeout(a);
                                deadlines.remove(id);
                            }
                            inFlight.set(0);
                            nextDispatchAt.clear();
                        } else {
                            return;
                        }
                    }
                    String endingLabel = seqTargets.get(seqIndex).label;
                    spark.stopPhase(endingLabel);
                    recorder.endPhase(endingLabel);
                    saveWorldsBetweenPhases(endingLabel);
                    if (seqGapMs <= 0L) {
                        seqIndex++;
                        if (seqIndex >= seqTargets.size()) { stop(); return; }
                        seqPhaseEndMs = now + seqPerTargetMs;
                        seqPhaseDispatched.set(0);
                        nextDispatchAt.clear();
                        lastProgressEpochMs = now;
                        spark.startPhase(seqTargets.get(seqIndex).label);
                        recorder.beginPhase(seqTargets.get(seqIndex).label);
                    } else {
                        seqGapEndMs = now + seqGapMs;
                        return;
                    }
                }
                pinned = seqTargets.get(seqIndex);
            }

            List<StressPlayer> roster = roster();
            if (roster.isEmpty()) return;
            long timeoutMs = config.getLong("attempt-timeout-ms", 30000L);
            long dispatchIntervalMs = config.getLong("dispatch-interval-ms", 0L);
            int attemptsThisTick = 0;
            while (running.get() && inFlight.get() < concurrencyCap) {
                if (mode == Mode.BURST && burstRemaining <= 0) break;
                if (mode == Mode.TIMED && now >= endEpochMs) break;
                if (mode == Mode.SEQUENCE && !warmupActive && now >= seqPhaseEndMs) break;
                if (mode == Mode.SEQUENCE && !warmupActive && seqCountCapReached()) break;
                if (mode == Mode.SEQUENCE && warmupActive && (now >= warmupSliceEndMs || warmupSliceDrainDeadline > 0L)) break;
                if (dispatchIntervalMs > 0L && (now - lastDispatchStartEpochMs) < dispatchIntervalMs) break;
                int rosterSize = roster.size();
                if (rosterSize <= 0) break;
                if (attemptsThisTick >= rosterSize * 4) break;
                int pickIdx = Math.floorMod(rosterCursor.getAndIncrement(), rosterSize);
                StressPlayer target = roster.get(pickIdx);
                attemptsThisTick++;
                UUID tid = target.uuid();
                if (deadlines.containsKey(tid)) continue;
                Long readyAt = nextDispatchAt.get(tid);
                if (readyAt != null && readyAt > now) continue;
                dispatchOne(target, timeoutMs, pinned);
                lastDispatchStartEpochMs = System.currentTimeMillis();
                lastProgressEpochMs = lastDispatchStartEpochMs;
                if (mode == Mode.BURST) burstRemaining--;
                if (mode == Mode.SEQUENCE && !warmupActive) seqPhaseDispatched.incrementAndGet();
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "StressTestRTP runner tick failed", t);
        } finally {
            ticking.set(false);
        }
    }

    private void dispatchOne(StressPlayer target, long timeoutMs, Targets.Entry pinned) {
        TpsMsptHeapSampler.Snapshot snap = sampler.latest();
        StressLocation from = target.location();
        double fromX = from != null ? from.x() : 0.0;
        double fromZ = from != null ? from.z() : 0.0;
        String worldName = from != null ? from.world() : "";

        Targets.Entry chosen;
        if (pinned != null) {
            chosen = pinned;
        } else {
            List<Targets.Entry> targets = roundRobinTargets;
            if (targets == null) {
                targets = Targets.load(config, plugin.getLogger());
                roundRobinTargets = targets;
            }
            chosen = targets.get(Math.floorMod(targetCursor.getAndIncrement(), targets.size()));
        }
        probe.setDirectAuthoritative(isDirectArm(chosen));
        Attempt attempt = new Attempt(
                UUID.randomUUID(),
                target.name(),
                worldName,
                chosen.label,
                System.currentTimeMillis(),
                fromX, fromZ,
                snap.tps(), snap.mspt(), snap.heapUsedMb());
        RegionTpsSampler rts = regionTpsSampler;
        if (rts != null) attempt.regionTps5sAtDispatch = rts.latest5s(target.uuid());

        UUID tid = target.uuid();
        long gapMs = perPlayerGapMs();
        if (gapMs > 0L) nextDispatchAt.put(tid, System.currentTimeMillis() + gapMs);
        deadlines.put(tid, System.currentTimeMillis() + timeoutMs);
        lastDispatched.set(tid);
        inFlight.incrementAndGet();
        recorder.onDispatch(attempt);
        probe.expect(attempt, tid);

        String cmd = chosen.render(target.name());

        final boolean asPlayer = chosen.dispatchAsPlayer(config.getBoolean("dispatch-as-player", true));
        final boolean watchPinned = Sched.isFolia()
                && config.getBoolean("folia.pinned-position-watch", true);
        final int distanceCap = mode == Mode.RAMP
                ? (int) config.getLong("ramp.player-view-distance", 2L) : 0;
        Sched.runOnPlayer(plugin, target, () -> {
            if (distanceCap > 0) capPlayerDistances(target, distanceCap);
            attempt.commandDispatchedEpochMs = System.currentTimeMillis();
            FoliaRegionMonitor regionMonitor = recorder.regionMonitor();
            if (regionMonitor != null) regionMonitor.noteDispatchHop(attempt);
            final boolean onTick = TickThreadDetector.onTickThread();
            final long startNs = onTick ? System.nanoTime() : 0L;
            try {
                if (asPlayer) {
                    target.performCommand(cmd);
                } else if (Sched.isFolia()) {
                    Sched.runGlobal(plugin, () -> plugin.dispatchConsoleCommand(cmd));
                } else {
                    plugin.dispatchConsoleCommand(cmd);
                }
            } finally {
                if (onTick) attempt.recordTickInterval(startNs, System.nanoTime());
            }
            if (watchPinned) startPinnedPositionWatch(target, attempt, fromX, fromZ, timeoutMs);
        });
    }

    private void startPinnedPositionWatch(StressPlayer target, Attempt attempt,
                                          double fromX, double fromZ, long timeoutMs) {
        UUID tid = target.uuid();
        double thr = config.getDouble("folia.poll-distance-threshold", 16.0);
        double thr2 = thr * thr;
        long maxTicks = pinnedWatchMaxTicks(timeoutMs, config.getLong("folia.pinned-position-watch-max-ticks", 0L));
        Sched.runOnPlayerTimer(plugin, target, () -> {
            try {
                if (probe.peek(tid) != attempt && probe.awaitingExternal(tid) != attempt) return false;
                if (!target.isOnline()) return false;
                StressLocation loc = target.location();
                if (loc == null) return true;
                double dx = loc.x() - fromX;
                double dz = loc.z() - fromZ;
                if ((dx * dx + dz * dz) < thr2) return true;
                if (probe.attributeByPosition(tid, loc)) {
                    lastProgressEpochMs = System.currentTimeMillis();
                }
                return false;
            } catch (Throwable t) {
                return false;
            }
        }, 1L, maxTicks);
    }

    static long pinnedWatchMaxTicks(long timeoutMs, long configured) {
        if (configured > 0L) return configured;
        return Math.max(40L, Math.max(0L, timeoutMs) / 50L + 40L);
    }

    private void capPlayerDistances(StressPlayer p, int distance) {
        if (distanceCapped.containsKey(p)) return;
        distanceCapped.put(p, Boolean.TRUE);
        int d = Math.clamp(distance, 2, 32);
        p.capDistances(d);
    }

    private List<StressPlayer> roster() {
        String rosterMode = config.getString("roster", "all").toLowerCase(Locale.ROOT);
        List<StressPlayer> out = rosterBuffer;
        out.clear();
        if (rosterMode.startsWith("permission:")) {
            String node = rosterMode.substring("permission:".length()).trim();
            for (StressPlayer p : plugin.onlinePlayers()) {
                if (p.hasPermission(node)) out.add(p);
            }
        } else if (rosterMode.startsWith("names:")) {
            List<String> names = config.getStringList("roster-names");
            for (StressPlayer p : plugin.onlinePlayers()) {
                for (String n : names) {
                    if (p.name().equalsIgnoreCase(n)) { out.add(p); break; }
                }
            }
        } else {
            for (StressPlayer p : plugin.onlinePlayers()) {
                if (Objects.equals(operatorId, p.uuid())) continue;
                out.add(p);
            }
            if (out.isEmpty() && operatorId != null) {
                StressPlayer self = plugin.player(operatorId);
                if (self != null) out.add(self);
            }
        }
        return out;
    }

    private void saveWorldsBetweenPhases(String endingLabel) {
        String saveMode = config.getString("save-worlds-between-phases", "auto");
        if (saveMode == null) saveMode = "auto";
        saveMode = saveMode.trim().toLowerCase(Locale.ROOT);
        boolean save = switch (saveMode) {
            case "never", "false", "off", "no" -> false;
            case "always", "true", "on", "yes" -> true;
            default -> !(isPaperFamily() || isFoliaFamily());
        };
        if (!save) return;
        try {
            Sched.runGlobal(plugin, () -> {
                long t0 = System.currentTimeMillis();
                int n = 0;
                for (StressWorld w : plugin.worlds()) {
                    try {
                        w.save();
                        n++;
                    } catch (Throwable t) {
                        plugin.getLogger().log(Level.WARNING,
                                "[StressTestRTP] world.save() failed for " + w.name(), t);
                    }
                }
                plugin.getLogger().info(String.format(Locale.ROOT,
                        "[StressTestRTP] between-phase save after '%s': %d world(s) saved in %d ms",
                        endingLabel, n, System.currentTimeMillis() - t0));
            });
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING,
                    "[StressTestRTP] between-phase world save scheduling failed", t);
        }
    }

    private long perPlayerGapMs() {
        if (mode == Mode.RAMP) return rampPerPlayerGapMs;
        long ms = config.getLong("per-player-gap-ms", -1L);
        if (ms >= 0L) return Math.min(ms, 5000L);
        long ticks = config.getLong("per-player-gap-ticks", 3L);
        if (ticks < 0L) ticks = 0L;
        if (ticks > 100L) ticks = 100L;
        return ticks * 50L;
    }

    private boolean seqCountCapReached() {
        return mode == Mode.SEQUENCE && !warmupActive
                && seqPerTargetCount > 0L
                && seqPhaseDispatched.get() >= seqPerTargetCount;
    }

    public String operatorName() { return operatorName; }
    public Mode mode() { return mode; }
    public long endEpochMs() { return endEpochMs; }
    public int concurrencyCap() { return concurrencyCap; }

    static boolean isPaperFamily() {
        try {
            Class.forName("com.destroystokyo.paper.PaperConfig");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static boolean isFoliaFamily() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
