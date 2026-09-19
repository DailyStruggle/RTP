package io.github.dailystruggle.helpers.stresstestrtp;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-attempt CSV recorder + rolling-aggregate computer.
 *
 * <p>Columns are aligned to the front-page comparison table:
 * <ul>
 *   <li>per-attempt CSV → raw evidence</li>
 *   <li>summary.txt → cold-start, warm-queue (median), TPS-burst, MSPT, heap</li>
 * </ul>
 *
 * <p>All public methods are thread-safe. The class never touches Bukkit
 * directly - it is fed values from {@link TeleportProbe} and
 * {@link TpsMsptHeapSampler}.
 */
public class MetricsRecorder {

    /** Single attempt record. Fields mirror the CSV header. */
    public static final class Attempt {
        public final UUID attemptId;
        public final String player;
        public final String world;
        public final String targetLabel;
        public final long dispatchEpochMs;
        /** Wall-clock epoch (ms) at which the command was actually handed to
         *  {@code Bukkit.dispatchCommand} - i.e. <em>after</em> the
         *  {@code Sched.runOnPlayer} hop. On Spigot/Paper that hop is a
         *  {@code BukkitScheduler.runTask} which defers to the next tick
         *  boundary (0-50 ms, avg ~25 ms); attributing that wait to the
         *  target plugin would inflate every measurement by up to one tick.
         *  Set by {@link Runner#dispatchOne}. {@code -1} until the runnable
         *  fires; {@link #latencyMs()} falls back to {@link #dispatchEpochMs}
         *  in that window so an attempt that completes before the hop runs
         *  (impossible in practice, but defensive) still gets a finite
         *  latency. */
        public volatile long commandDispatchedEpochMs = -1L;
        public volatile long teleportEpochMs = -1L;
        public volatile boolean success = false;
        public volatile String failReason = "";
        public volatile double fromX, fromZ, toX, toZ;
        public volatile double distance = -1.0;
        public volatile double tpsAtDispatch = -1.0;
        public volatile double msptAtDispatch = -1.0;
        public volatile long heapUsedMbAtDispatch = -1L;
        /** Chunk loads attributed to this attempt by
         *  {@link ChunkLoadCounter}'s per-attempt attribution chain (Paper
         *  plugin-ticket lookup, then main-thread temporal fallback). Set by
         *  {@link ChunkLoadCounter#endAttempt} on completion or timeout.
         *  Remains {@code -1L} when no counter is wired or the attempt was
         *  never registered. Replaces the old
         *  {@code chunkLoadsAtDispatch}/{@code chunkLoadsAtComplete} snapshot
         *  pair, which double-counted concurrent attempts because the
         *  underlying counter was global. */
        public volatile long attributedChunkLoads = -1L;
        /** Chunk loads attributed to this attempt after removing the
         *  post-teleport arrival ring (the render-distance square the server
         *  loads around the player on arrival, which is plugin-independent and
         *  scales with view distance). Computed by {@link ChunkLoadCounter#endAttempt}
         *  from the recorded load coordinates and the destination. Reflects the
         *  plugin's destination-selection work (typically ~1). Falls back to
         *  {@link #attributedChunkLoads} for timeouts / failures where the
         *  destination is unknown, and remains {@code -1L} when no counter is
         *  wired. */
        public volatile long selectionChunkLoads = -1L;

        public Attempt(UUID id, String player, String world, String targetLabel, long dispatchEpochMs,
                       double fromX, double fromZ,
                       double tps, double mspt, long heapMb) {
            this.attemptId = id;
            this.player = player;
            this.world = world;
            this.targetLabel = targetLabel == null ? "" : targetLabel;
            this.dispatchEpochMs = dispatchEpochMs;
            this.fromX = fromX;
            this.fromZ = fromZ;
            this.tpsAtDispatch = tps;
            this.msptAtDispatch = mspt;
            this.heapUsedMbAtDispatch = heapMb;
        }

        long latencyMs() {
            if (teleportEpochMs <= 0) return -1L;
            // Prefer the post-scheduler-hop timestamp so we measure the
            // target plugin's work, not the 1-tick BukkitScheduler.runTask
            // wait that {@code Sched.runOnPlayer} adds on Spigot/Paper.
            long base = commandDispatchedEpochMs > 0 ? commandDispatchedEpochMs : dispatchEpochMs;
            return teleportEpochMs - base;
        }
    }

    public static final String CSV_HEADER =
            "attempt_id,player,world,target_label,dispatch_epoch_ms,teleport_epoch_ms,latency_ms,"
                    + "success,fail_reason,from_x,from_z,to_x,to_z,distance,"
                    + "tps_at_dispatch,mspt_at_dispatch,heap_used_mb_at_dispatch,"
                    + "chunks_loaded_during_attempt,chunk_load_cost_ms,chunks_selection";

    public static final String PHASES_CSV_HEADER =
            "phase_label,start_epoch_ms,end_epoch_ms,wall_ms,attempts,successes,"
                    + "process_cpu_ms,main_thread_cpu_ms,"
                    + "cpu_ms_per_attempt_total,cpu_ms_per_attempt_main,"
                    + "chunks_loaded,chunks_loaded_attributed,chunks_loaded_background,"
                    + "chunks_per_attempt,"
                    + "chunk_load_cost_ms,cpu_ms_with_chunks,cpu_ms_with_chunks_per_attempt,"
                    + "chunks_selection,chunks_selection_per_attempt";

    private final Path csvPath;
    private final Path phasesCsvPath;
    /** Sidecar holding a periodically-refreshed snapshot of the in-flight
     *  phase, so a mid-phase server crash (e.g. a competitor plugin stalling
     *  the main thread to death) still leaves the latest partial aggregate of
     *  the phase that was running. Overwritten in place on each flush and
     *  removed when the phase closes normally. */
    private final Path partialPhaseCsvPath;
    /** Wall-clock throttle so {@link #flushPartialPhase} can be called every
     *  tick cheaply; the sidecar is only rewritten at most once per interval. */
    private static final long PARTIAL_PHASE_FLUSH_MS = 2000L;
    private volatile long lastPartialFlushMs = 0L;
    private final ConcurrentLinkedQueue<Attempt> finished = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final AtomicInteger total = new AtomicInteger(0);
    private final AtomicInteger successes = new AtomicInteger(0);

    /** First completed (success) attempt's latency, used for cold-start. */
    private volatile long coldStartLatencyMs = -1L;

    /** Optional CPU sampler for phase-aggregate CPU/TP measurement. May be null. */
    private volatile CpuSampler cpuSampler;
    /** Optional chunk-load counter (set by the plugin on enable). May be null -
     *  in which case per-attempt and per-phase chunk columns are written empty. */
    private volatile ChunkLoadCounter chunkCounter;
    // Active phase snapshot; written to phases CSV by endPhase().
    private volatile String phaseLabel;
    private volatile long phaseStartEpochMs = -1L;
    private volatile long phaseStartProcessCpuNs = -1L;
    private volatile long phaseStartMainCpuNs = -1L;
    private volatile int phaseStartTotal = 0;
    private volatile int phaseStartSuccesses = 0;

    public MetricsRecorder(Path csvPath) throws IOException {
        this.csvPath = csvPath;
        // Sibling CSV next to the main per-attempt CSV: <stamp>.csv → <stamp>-phases.csv
        String name = csvPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String phasesName = (dot > 0 ? name.substring(0, dot) : name) + "-phases"
                + (dot > 0 ? name.substring(dot) : ".csv");
        this.phasesCsvPath = csvPath.resolveSibling(phasesName);
        String partialName = (dot > 0 ? name.substring(0, dot) : name) + "-phases-partial"
                + (dot > 0 ? name.substring(dot) : ".csv");
        this.partialPhaseCsvPath = csvPath.resolveSibling(partialName);
        Files.createDirectories(csvPath.getParent());
        Files.writeString(csvPath, CSV_HEADER + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(phasesCsvPath, PHASES_CSV_HEADER + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** Wires the CPU sampler used by {@link #beginPhase}/{@link #endPhase}. */
    public void setCpuSampler(CpuSampler sampler) { this.cpuSampler = sampler; }

    /** Wires the chunk-load counter used by {@link #onComplete}/{@link #onTimeout}
     *  (per-attempt deltas) and {@link #beginPhase}/{@link #endPhase} (per-phase
     *  totals). Setting to {@code null} disables chunk accounting; the
     *  corresponding CSV columns are written empty. */
    public void setChunkCounter(ChunkLoadCounter counter) { this.chunkCounter = counter; }

    /** Calibration: nanoseconds of computation per chunk-load, used to derive
     *  {@code chunk_load_cost_ms} (per-attempt) and {@code cpu_ms_with_chunks}
     *  (per-phase). Obtain by running {@code /rtp test chunk-probe-perf} on the
     *  test server and reading the {@code full avg=Nµs} field from its log
     *  output. {@code 0} (default) leaves the chunk-cost columns empty. */
    private volatile long chunkLoadCostNs = 0L;
    public void setChunkLoadCostNs(long ns) { this.chunkLoadCostNs = Math.max(0L, ns); }
    public long chunkLoadCostNs() { return chunkLoadCostNs; }
    /** Read-only accessor for the global chunk-load total. Retained for
     *  diagnostics; per-attempt attribution now flows through
     *  {@link ChunkLoadCounter#beginAttempt(Attempt)} /
     *  {@link ChunkLoadCounter#endAttempt(Attempt)} rather than dispatch /
     *  completion snapshots. Returns {@code -1L} when no counter is wired. */
    public long chunkLoadsTotal() {
        ChunkLoadCounter c = chunkCounter;
        return c == null ? -1L : c.total();
    }

    /** Optional console logger for per-attempt completion lines. When set
     *  and {@link #logSuccessful} or {@link #logFailures} is true, each
     *  completion is announced via this logger so operators can see
     *  forward progress live without tailing the CSV. */
    private volatile java.util.logging.Logger attemptLogger = null;
    private volatile boolean logSuccessful = false;
    private volatile boolean logFailures = true;
    public void setAttemptLogger(java.util.logging.Logger logger,
                                 boolean logSuccessful, boolean logFailures) {
        this.attemptLogger = logger;
        this.logSuccessful = logSuccessful;
        this.logFailures = logFailures;
    }

    /** When false, attempts are still tracked in-memory (so the runner's
     *  in-flight bookkeeping stays correct) but no CSV rows or phase rows
     *  are written. Used during JIT warm-up so warm-up dispatches don't
     *  pollute the measurement table. Defaults to true. */
    private volatile boolean recording = true;
    public void setRecording(boolean enabled) { this.recording = enabled; }
    public boolean isRecording() { return recording; }

    public Path csvPath() { return csvPath; }

    public void onDispatch(Attempt a) {
        inFlight.incrementAndGet();
        total.incrementAndGet();
        // Register the attempt with the chunk counter so that subsequent
        // ChunkLoadEvents can be attributed to it via the per-attempt
        // attribution chain (Paper plugin-ticket lookup, then main-thread
        // temporal fallback). Replaces the old
        // a.chunkLoadsAtDispatch = counter.total() snapshot, which silently
        // double-counted concurrent attempts.
        ChunkLoadCounter cc = chunkCounter;
        if (cc != null) cc.beginAttempt(a);
    }

    /** Called by {@link TeleportProbe} when a PlayerTeleportEvent is attributed. */
    public void onComplete(Attempt a, boolean success, String failReason,
                           double toX, double toZ) {
        if (a.teleportEpochMs > 0) return; // already completed
        a.teleportEpochMs = System.currentTimeMillis();
        a.success = success;
        a.failReason = failReason == null ? "" : failReason;
        a.toX = toX;
        a.toZ = toZ;
        ChunkLoadCounter cc = chunkCounter;
        if (cc != null) cc.endAttempt(a);
        if (success) {
            double dx = a.toX - a.fromX, dz = a.toZ - a.fromZ;
            a.distance = Math.sqrt(dx * dx + dz * dz);
            successes.incrementAndGet();
            if (coldStartLatencyMs < 0) coldStartLatencyMs = a.latencyMs();
        }
        inFlight.decrementAndGet();
        finished.add(a);
        if (recording) {
            appendRow(a);
            logCompletion(a);
        }
    }

    /** Called by {@link Runner} when an attempt times out without a teleport. */
    public void onTimeout(Attempt a) {
        if (a.teleportEpochMs > 0) return;
        a.teleportEpochMs = System.currentTimeMillis();
        a.success = false;
        a.failReason = "TIMEOUT";
        ChunkLoadCounter cc = chunkCounter;
        if (cc != null) cc.endAttempt(a);
        inFlight.decrementAndGet();
        finished.add(a);
        if (recording) {
            appendRow(a);
            logCompletion(a);
        }
    }

    /** Console echo of a finished attempt, gated by {@link #attemptLogger}
     *  and the per-side flags. Format chosen to be one line, scannable, and
     *  immediately useful for operators tailing the server log during a long
     *  benchmark run. */
    private void logCompletion(Attempt a) {
        java.util.logging.Logger lg = attemptLogger;
        if (lg == null) return;
        if (a.success) {
            if (!logSuccessful) return;
            lg.info(String.format(java.util.Locale.ROOT,
                    "[StressTestRTP] %s -> %s OK %dms (%.0f, %.0f)",
                    a.targetLabel, a.player, a.latencyMs(), a.toX, a.toZ));
        } else {
            if (!logFailures) return;
            String reason = a.failReason == null || a.failReason.isEmpty()
                    ? "FAIL" : a.failReason;
            lg.info(String.format(java.util.Locale.ROOT,
                    "[StressTestRTP] %s -> %s %s %dms",
                    a.targetLabel, a.player, reason, a.latencyMs()));
        }
    }

    private void appendRow(Attempt a) {
        String chunkDelta = chunkDeltaCol(a);
        String chunkCostMs;
        long ns = chunkLoadCostNs;
        if (ns <= 0L || chunkDelta.isEmpty()) {
            chunkCostMs = "";
        } else {
            long delta = Long.parseLong(chunkDelta);
            chunkCostMs = fmt(((double) delta * (double) ns) / 1_000_000.0d);
        }
        String row = String.join(",",
                a.attemptId.toString(),
                csv(a.player),
                csv(a.world),
                csv(a.targetLabel),
                Long.toString(a.dispatchEpochMs),
                Long.toString(a.teleportEpochMs),
                Long.toString(a.latencyMs()),
                Boolean.toString(a.success),
                csv(a.failReason),
                fmt(a.fromX), fmt(a.fromZ),
                fmt(a.toX), fmt(a.toZ),
                fmt(a.distance),
                fmt(a.tpsAtDispatch),
                fmt(a.msptAtDispatch),
                Long.toString(a.heapUsedMbAtDispatch),
                chunkDelta,
                chunkCostMs,
                a.selectionChunkLoads >= 0 ? Long.toString(a.selectionChunkLoads) : "");
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND)) {
            w.write(row);
            w.newLine();
        } catch (IOException e) {
            // CSV write failures are diagnostic-only; the run continues.
            // Logged at the plugin level via Runner's exception path.
            throw new RuntimeException("CSV append failed: " + e.getMessage(), e);
        }
    }

    public Path phasesCsvPath() { return phasesCsvPath; }

    /**
     * Marks the start of a measurement phase (one TIMED run, one BURST, or
     * one SEQUENCE per-target window). Captures the CPU baselines and the
     * current attempt counters; the deltas are written by {@link #endPhase}.
     *
     * <p>If a previous phase is still active when called, it is implicitly
     * closed first via {@link #endPhase} so the phases CSV stays consistent
     * even if the operator switches modes mid-run.
     */
    public void beginPhase(String label) {
        if (!recording) return;
        if (phaseLabel != null) endPhase(phaseLabel);
        phaseLabel = label == null ? "" : label;
        phaseStartEpochMs = System.currentTimeMillis();
        CpuSampler s = cpuSampler;
        phaseStartProcessCpuNs = s != null ? s.processCpuTimeNs() : -1L;
        phaseStartMainCpuNs = s != null ? s.mainThreadCpuTimeNs() : -1L;
        phaseStartTotal = total.get();
        phaseStartSuccesses = successes.get();
        ChunkLoadCounter cc = chunkCounter;
        if (cc != null) cc.resetPhase();
    }

    /**
     * Closes the current phase (if any) and appends one row to the phases
     * CSV. The {@code label} argument is allowed to differ from the
     * {@code beginPhase} label (e.g. SEQUENCE end-of-target uses the
     * advancing target's name) - the recorded label is whichever was active.
     */
    public void endPhase(@SuppressWarnings("unused") String label) {
        if (!recording) return;
        if (phaseLabel == null) return;
        long endEpoch = System.currentTimeMillis();
        String row = buildPhaseRow(endEpoch);
        try (BufferedWriter w = Files.newBufferedWriter(phasesCsvPath, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND)) {
            w.write(row);
            w.newLine();
        } catch (IOException e) {
            // Phases CSV write failures are diagnostic-only; the run continues.
            throw new RuntimeException("phases CSV append failed: " + e.getMessage(), e);
        }
        // The phase closed normally; its summary is now in the durable phases
        // CSV, so the partial sidecar is no longer needed.
        try {
            Files.deleteIfExists(partialPhaseCsvPath);
        } catch (IOException ignored) { /* sidecar cleanup is best-effort */ }
        lastPartialFlushMs = 0L;
        // Clear phase state.
        phaseLabel = null;
        phaseStartEpochMs = -1L;
        phaseStartProcessCpuNs = -1L;
        phaseStartMainCpuNs = -1L;
    }

    /**
     * Periodically snapshots the in-flight phase to {@link #partialPhaseCsvPath}
     * so a mid-phase server crash still leaves the latest partial aggregate of
     * the phase that was running (the per-attempt and heap-series CSVs already
     * flush per row, but the phase summary is only written by {@link #endPhase}
     * at phase end). Safe to call every tick: self-throttled to at most once
     * per {@link #PARTIAL_PHASE_FLUSH_MS} and a no-op when no phase is active or
     * recording is disabled. Read-only with respect to phase/chunk state.
     */
    public void flushPartialPhase() {
        if (!recording) return;
        if (phaseLabel == null) return;
        long now = System.currentTimeMillis();
        if (now - lastPartialFlushMs < PARTIAL_PHASE_FLUSH_MS) return;
        lastPartialFlushMs = now;
        String row = buildPhaseRow(now);
        try (BufferedWriter w = Files.newBufferedWriter(partialPhaseCsvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(PHASES_CSV_HEADER);
            w.newLine();
            w.write(row);
            w.newLine();
        } catch (IOException ignored) {
            // Partial-phase sidecar failures are best-effort and intentionally
            // quiet: the durable phases CSV is still written at phase end, and
            // the per-attempt CSV is unaffected.
        }
    }

    /**
     * Builds one phases-CSV row for the currently-active phase, measured up to
     * {@code endEpoch}. Read-only: does not clear phase state or reset the
     * chunk counter, so it is reused for both the final {@link #endPhase} row
     * and the periodic {@link #flushPartialPhase} snapshot.
     */
    private String buildPhaseRow(long endEpoch) {
        CpuSampler s = cpuSampler;
        long endProcessCpu = s != null ? s.processCpuTimeNs() : -1L;
        long endMainCpu = s != null ? s.mainThreadCpuTimeNs() : -1L;
        long wallMs = Math.max(0L, endEpoch - phaseStartEpochMs);
        int attempts = Math.max(0, total.get() - phaseStartTotal);
        int succ = Math.max(0, successes.get() - phaseStartSuccesses);

        long procCpuMs = -1L;
        if (phaseStartProcessCpuNs >= 0 && endProcessCpu >= 0) {
            procCpuMs = Math.max(0L, (endProcessCpu - phaseStartProcessCpuNs) / 1_000_000L);
        }
        long mainCpuMs = -1L;
        if (phaseStartMainCpuNs >= 0 && endMainCpu >= 0) {
            mainCpuMs = Math.max(0L, (endMainCpu - phaseStartMainCpuNs) / 1_000_000L);
        }
        double perTotal = (procCpuMs >= 0 && attempts > 0) ? (double) procCpuMs / attempts : -1.0;
        double perMain  = (mainCpuMs >= 0 && attempts > 0) ? (double) mainCpuMs / attempts : -1.0;

        ChunkLoadCounter cc = chunkCounter;
        long chunksLoaded = cc != null ? cc.phaseTotal() : -1L;
        long chunksAttributed = cc != null ? cc.phaseAttributed() : -1L;
        long chunksBackground = cc != null ? cc.phaseBackground() : -1L;
        // Selection loads: attributed minus the post-teleport arrival ring.
        // This is the view-distance-corrected per-attempt chunk metric.
        long chunksSelection = cc != null ? cc.phaseSelection() : -1L;
        double chunksSelectionPerAtt = (chunksSelection >= 0 && attempts > 0)
                ? (double) chunksSelection / attempts : -1.0;
        // chunks_per_attempt now reports attributed loads (i.e. loads charged
        // to a specific in-flight teleport via plugin-ticket / main-thread
        // attribution) rather than the global phase total. The total and
        // background columns remain available for sanity checking; pre-fix
        // runs that compared global-total/attempt across plugins were
        // measuring "all chunk loads anywhere on the server" / attempts and
        // double-counted with concurrent dispatch.
        double chunksPerAtt = (chunksAttributed >= 0 && attempts > 0)
                ? (double) chunksAttributed / attempts : -1.0;

        // Chunk-load cost amendment. When a calibration value is set
        // (chunkLoadCostNs > 0, typically obtained from `/rtp test
        // chunk-probe-perf`), we estimate the CPU cost the server attributed
        // to its own chunk-system threads - work that the per-process JMX
        // sampler counts in process_cpu_ms but that the per-thread main
        // sampler does NOT, and which the cpu_ms_per_attempt_total column
        // therefore underweights for plugins that synchronously load many
        // chunks per teleport (BetterRTP's PreloadRadius is the motivating
        // case). Use the attributed count rather than the phase total so
        // background loads (view-distance follow-ups, other-plugin loads)
        // do not inflate per-plugin CPU.
        long ns = chunkLoadCostNs;
        long chunksForCost = chunksAttributed >= 0 ? chunksAttributed : chunksLoaded;
        double chunkLoadCostMs = (ns > 0L && chunksForCost > 0)
                ? ((double) chunksForCost * (double) ns) / 1_000_000.0d : -1.0;
        long cpuMsWithChunks = (procCpuMs >= 0 && chunkLoadCostMs >= 0)
                ? procCpuMs + Math.round(chunkLoadCostMs) : -1L;
        double cpuWithChunksPerAtt = (cpuMsWithChunks >= 0 && attempts > 0)
                ? (double) cpuMsWithChunks / attempts : -1.0;

        String row = String.join(",",
                csv(phaseLabel),
                Long.toString(phaseStartEpochMs),
                Long.toString(endEpoch),
                Long.toString(wallMs),
                Integer.toString(attempts),
                Integer.toString(succ),
                procCpuMs >= 0 ? Long.toString(procCpuMs) : "",
                mainCpuMs >= 0 ? Long.toString(mainCpuMs) : "",
                perTotal >= 0 ? fmt(perTotal) : "",
                perMain  >= 0 ? fmt(perMain)  : "",
                chunksLoaded >= 0 ? Long.toString(chunksLoaded) : "",
                chunksAttributed >= 0 ? Long.toString(chunksAttributed) : "",
                chunksBackground >= 0 ? Long.toString(chunksBackground) : "",
                chunksPerAtt >= 0 ? fmt(chunksPerAtt) : "",
                chunkLoadCostMs >= 0 ? fmt(chunkLoadCostMs) : "",
                cpuMsWithChunks >= 0 ? Long.toString(cpuMsWithChunks) : "",
                cpuWithChunksPerAtt >= 0 ? fmt(cpuWithChunksPerAtt) : "",
                chunksSelection >= 0 ? Long.toString(chunksSelection) : "",
                chunksSelectionPerAtt >= 0 ? fmt(chunksSelectionPerAtt) : "");
        return row;
    }

    private static String csv(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** Per-attempt chunk-load count, attributed via the {@link ChunkLoadCounter}
     *  chain (Paper plugin-ticket lookup, then main-thread temporal fallback).
     *  Empty when no counter is wired or the attempt was never registered. */
    private static String chunkDeltaCol(Attempt a) {
        if (a.attributedChunkLoads < 0) return "";
        return Long.toString(a.attributedChunkLoads);
    }

    private static String fmt(double d) {
        if (Double.isNaN(d) || d < 0) return "";
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }

    public int totalAttempts()  { return total.get(); }
    public int successCount()   { return successes.get(); }
    public int inFlightCount()  { return inFlight.get(); }
    public long coldStartLatencyMs() { return coldStartLatencyMs; }

    /** Snapshot of finished attempts for percentile/median computation. */
    public List<Long> latenciesSnapshot(boolean successOnly) {
        return latenciesSnapshot(successOnly, null);
    }

    /**
     * Snapshot of finished attempts, optionally filtered to a single target
     * label. {@code targetLabel == null} means "all targets".
     */
    public List<Long> latenciesSnapshot(boolean successOnly, String targetLabel) {
        List<Long> out = new ArrayList<>(finished.size());
        for (Attempt a : finished) {
            if (successOnly && !a.success) continue;
            if (targetLabel != null && !targetLabel.equals(a.targetLabel)) continue;
            long l = a.latencyMs();
            if (l >= 0) out.add(l);
        }
        return out;
    }

    /** Distinct target labels observed across finished attempts, in first-seen order. */
    public List<String> observedTargetLabels() {
        List<String> out = new ArrayList<>();
        for (Attempt a : finished) {
            if (!out.contains(a.targetLabel)) out.add(a.targetLabel);
        }
        return out;
    }

    /** Cold-start latency (first success) per target label. */
    public long coldStartLatencyMs(String targetLabel) {
        for (Attempt a : finished) {
            if (!a.success) continue;
            if (targetLabel == null || targetLabel.equals(a.targetLabel)) {
                return a.latencyMs();
            }
        }
        return -1L;
    }

    /** Inclusive percentile (0..100). Returns -1 when sample is empty. */
    public static long percentile(List<Long> samples, double p) {
        if (samples.isEmpty()) return -1L;
        long[] arr = samples.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(arr);
        int idx = (int) Math.min(arr.length - 1L,
                Math.max(0L, Math.round((p / 100.0) * (arr.length - 1))));
        return arr[idx];
    }

    public static long median(List<Long> samples) { return percentile(samples, 50.0); }
}
