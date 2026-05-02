package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Run loop: dispatches the configured command against the roster while
 * respecting concurrency, attempt timeouts, and the run duration.
 *
 * <p>The loop itself is async (via {@link Sched#runAsyncTimer}) at 100ms
 * cadence — fast enough that a freed concurrency slot is filled within
 * one dispatch interval, slow enough to avoid burning CPU when the roster
 * is small. Each individual dispatch hops to the player-owning thread via
 * {@link Sched#runOnPlayer} (Folia: EntityScheduler; Spigot/Paper: main).
 *
 * <p>The command is dispatched from {@link Bukkit#getConsoleSender()} so
 * permission checks against the target player still apply (the player
 * receives the teleport), but the harness operator does not have to be
 * online to drive the run.
 */
public final class Runner {

    public enum Mode { TIMED, BURST, SEQUENCE, WARMUP }

    private final Plugin plugin;
    private final MetricsRecorder recorder;
    private final TeleportProbe probe;
    private final TpsMsptHeapSampler sampler;
    private final FileConfiguration config;
    private final SparkHook spark;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final AtomicInteger targetCursor = new AtomicInteger(0);
    private final ConcurrentHashMap<UUID, Long> deadlines = new ConcurrentHashMap<>();
    /** Per-player earliest-next-dispatch timestamp (epoch ms). Prevents the
     *  "you're already teleporting!" flood when concurrency > 1 and the
     *  roster is small: even after an attempt completes/times out, the same
     *  player will not be re-dispatched until {@code per-player-gap-ticks}
     *  server ticks have elapsed. */
    private final ConcurrentHashMap<UUID, Long> nextDispatchAt = new ConcurrentHashMap<>();
    /** Most-recently dispatched player, used by {@link ConsoleWatcher} when a
     *  failure log line cannot be attributed to a player by name (e.g. a
     *  generic "invalid command" with no echoed name). */
    private final java.util.concurrent.atomic.AtomicReference<UUID> lastDispatched =
            new java.util.concurrent.atomic.AtomicReference<>();

    private volatile int taskId = -1;
    private volatile long endEpochMs = 0L;
    private volatile int concurrencyCap = 4;
    private volatile Mode mode = Mode.TIMED;
    private volatile int burstRemaining = 0;
    private volatile String operatorName = "console";
    private volatile UUID operatorId = null;

    // SEQUENCE-mode state: pin one target at a time, with a recovery gap between targets.
    private volatile List<Targets.Entry> seqTargets = null;
    private volatile int seqIndex = 0;
    private volatile long seqPerTargetMs = 0L;
    private volatile long seqGapMs = 0L;
    private volatile long seqPhaseEndMs = 0L; // when current run-phase ends
    private volatile long seqGapEndMs = 0L;   // when current gap-phase ends (0 if not in gap)

    // SEQUENCE warm-up state. When `warmupActive` is true, the run loop cycles
    // every configured target for `warmupSliceMs` each, `warmupCycles` times,
    // BEFORE starting the actual measurement sequence. During warm-up, the
    // recorder's CSV writes are disabled (via {@link MetricsRecorder#setRecording(false)})
    // and spark profiling is NOT started, so warm-up dispatches don't pollute
    // the measurement table or the spark profile call-stacks. A summary line
    // per target is written to a sibling `<stamp>-warmup.log` file for audit.
    private volatile boolean warmupActive = false;
    private volatile long warmupSliceMs = 0L;
    private volatile int warmupCycles = 1;
    private volatile int warmupCycleIdx = 0;     // 0..warmupCycles-1
    private volatile int warmupTargetIdx = 0;    // current target within the cycle
    private volatile long warmupSliceEndMs = 0L; // when the current target's warm-up slice ends
    private volatile int warmupSliceStartTotal = 0; // total attempts at slice start (for log line)
    private volatile long warmupSliceStartEpoch = 0L;
    private volatile java.io.PrintWriter warmupLog = null;

    // No-progress watchdog. If the runner stops dispatching for an extended
    // period during a measurement phase (deadlines stuck, inFlight wedged,
    // some platform-side scheduler hiccup), we force-clear all per-player
    // bookkeeping and re-emit a "begin phase" message so the next tick
    // dispatches normally. Tracks the last time we observed forward
    // progress (a successful dispatch *or* a successful completion).
    private volatile long lastProgressEpochMs = 0L;
    private volatile int kickstartCount = 0;

    public Runner(Plugin plugin, MetricsRecorder recorder, TeleportProbe probe,
                  TpsMsptHeapSampler sampler, FileConfiguration config) {
        this(plugin, recorder, probe, sampler, config, new SparkHook(config, plugin.getLogger()));
    }

    public Runner(Plugin plugin, MetricsRecorder recorder, TeleportProbe probe,
                  TpsMsptHeapSampler sampler, FileConfiguration config, SparkHook spark) {
        this.plugin = plugin;
        this.recorder = recorder;
        this.probe = probe;
        this.sampler = sampler;
        this.config = config;
        this.spark = spark;
    }

    public boolean isRunning() { return running.get(); }

    /** Called by {@link TeleportProbe} when an attempt completes (success or
     *  attributed failure) so the in-flight slot is released immediately
     *  rather than waiting for the {@code attempt-timeout-ms} reaper. Without
     *  this, the runner appears to "stall after a dozen teleports": every
     *  roster player ends up with a stale deadline entry, the dispatch loop's
     *  {@code deadlines.containsKey} guard skips them all, and no new work is
     *  scheduled until each 30 s deadline elapses. */
    public void onAttemptCompleted(UUID playerId) {
        if (deadlines.remove(playerId) != null) {
            inFlight.decrementAndGet();
        }
        lastProgressEpochMs = System.currentTimeMillis();
    }

    /** Called by {@link ConsoleWatcher} when a console line matches a
     *  configured failure pattern and a player name in the line was matched
     *  to an in-flight attempt. Records the attempt as failed with the given
     *  reason and frees the slot immediately. */
    public void onConsoleFail(UUID playerId, String reason) {
        MetricsRecorder.Attempt a = probe.forget(playerId);
        if (a != null) {
            recorder.onComplete(a, false, reason == null ? "CONSOLE_FAIL" : reason, 0, 0);
        }
        if (deadlines.remove(playerId) != null) {
            inFlight.decrementAndGet();
        }
    }

    /** Called by {@link ConsoleWatcher} when a console line matches a
     *  failure pattern but no player name was identifiable in the line.
     *  Attributes the failure to the most-recently dispatched in-flight
     *  player — the overwhelmingly common case is "the dispatch we just
     *  fired produced this rejection". */
    public void onConsoleFailFallback(String reason) {
        UUID id = lastDispatched.get();
        if (id != null && deadlines.containsKey(id)) {
            onConsoleFail(id, reason);
        }
    }

    public boolean startTimed(CommandSender starter, int seconds, int concurrency) {
        if (!running.compareAndSet(false, true)) return false;
        rememberOperator(starter);
        this.mode = Mode.TIMED;
        this.concurrencyCap = Math.max(1, concurrency);
        this.endEpochMs = System.currentTimeMillis() + seconds * 1000L;
        this.lastProgressEpochMs = System.currentTimeMillis();
        this.kickstartCount = 0;
        this.taskId = Sched.runAsyncTimer(plugin, this::tick, 100L);
        spark.startPhase("timed");
        recorder.beginPhase("timed");
        return true;
    }

    /**
     * Run each configured target in turn for {@code perTargetSeconds}, with a
     * {@code gapSeconds} recovery pause between targets. Per-target results are
     * still attributed via {@link MetricsRecorder.Attempt#targetLabel}, so
     * {@code /rtpstress export} produces the same per-target breakdown — but
     * targets do not contend with one another within a single phase, which is
     * what the user wants when isolating each plugin's behaviour.
     */
    public boolean startSequence(CommandSender starter, int perTargetSeconds, int gapSeconds, int concurrency) {
        return startSequence(starter, perTargetSeconds, gapSeconds, concurrency, 0, 1);
    }

    /**
     * Sequence mode with optional JIT warm-up. When {@code warmupSeconds > 0},
     * the run loop cycles every configured target for {@code warmupSeconds /
     * (N * warmupCycles)} ms each, {@code warmupCycles} times, BEFORE starting
     * the actual measurement sequence. During warm-up:
     * <ul>
     *   <li>CSV writes are disabled (recorder's {@code setRecording(false)}).</li>
     *   <li>Spark profiling is not started.</li>
     *   <li>A per-target summary line is appended to {@code <stamp>-warmup.log}.</li>
     * </ul>
     * The goal is to push HotSpot through tiered compilation for the harness
     * itself and each plugin's hot path, so the first measured phase doesn't
     * pay an interpreter / C1-only tax that later phases avoid.
     */
    public boolean startSequence(CommandSender starter, int perTargetSeconds, int gapSeconds,
                                 int concurrency, int warmupSeconds, int warmupCyclesArg) {
        if (!running.compareAndSet(false, true)) return false;
        rememberOperator(starter);
        this.mode = Mode.SEQUENCE;
        this.concurrencyCap = Math.max(1, concurrency);
        this.seqTargets = Targets.load(config, plugin.getLogger());
        this.seqIndex = 0;
        this.seqPerTargetMs = Math.max(1L, perTargetSeconds) * 1000L;
        this.seqGapMs = Math.max(0L, gapSeconds) * 1000L;
        long now = System.currentTimeMillis();
        this.seqPhaseEndMs = now + seqPerTargetMs;
        this.seqGapEndMs = 0L;
        // endEpochMs is informational only in SEQUENCE mode (UI/status).
        this.endEpochMs = now + seqTargets.size() * (seqPerTargetMs + seqGapMs);

        // Configure warm-up state. Even per-target slices so JIT exposure is
        // equal across plugins; if total/N rounds to 0, we skip warm-up.
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

        this.lastProgressEpochMs = now;
        this.kickstartCount = 0;
        if (warmupActive) {
            openWarmupLog();
            // Disable measurement-side persistence for the warm-up duration.
            recorder.setRecording(false);
            warmupSliceStartEpoch = now;
            warmupSliceStartTotal = recorder.totalAttempts();
            warmupSliceEndMs = now + warmupSliceMs;
            logWarmup(String.format(java.util.Locale.ROOT,
                    "warm-up start: %d targets x %d cycles x %d ms each (total %d ms)",
                    n, warmupCycles, warmupSliceMs, totalWarmupMs));
        } else if (!seqTargets.isEmpty()) {
            // No warm-up requested: start measurement immediately.
            spark.startPhase(seqTargets.get(0).label);
            recorder.beginPhase(seqTargets.get(0).label);
        }
        this.taskId = Sched.runAsyncTimer(plugin, this::tick, 100L);
        return true;
    }

    /** Opens (truncates) the warm-up log file beside the run CSV. */
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

    public boolean startBurst(CommandSender starter, int n) {
        if (!running.compareAndSet(false, true)) return false;
        rememberOperator(starter);
        this.mode = Mode.BURST;
        this.concurrencyCap = Math.max(1, n);
        this.burstRemaining = Math.max(1, n);
        this.endEpochMs = System.currentTimeMillis() + Math.max(30000L,
                config.getLong("attempt-timeout-ms", 30000L) + 5000L);
        this.taskId = Sched.runAsyncTimer(plugin, this::tick, 100L);
        spark.startPhase("burst");
        recorder.beginPhase("burst");
        return true;
    }

    private void rememberOperator(CommandSender s) {
        if (s instanceof Player p) {
            operatorName = p.getName();
            operatorId = p.getUniqueId();
        } else {
            operatorName = "console";
            operatorId = null;
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        Sched.cancel(taskId);
        taskId = -1;
        // If we were stopped mid-warmup, leave the recorder ready for the next
        // run and close the warmup log file.
        if (warmupActive) {
            warmupActive = false;
            recorder.setRecording(true);
            if (warmupLog != null) {
                logWarmup("warm-up aborted by stop()");
                warmupLog.close();
                warmupLog = null;
            }
        }
        // Stop any in-progress spark phase; label with the active target if known.
        String label = (mode == Mode.SEQUENCE && seqTargets != null
                && seqIndex >= 0 && seqIndex < seqTargets.size())
                ? seqTargets.get(seqIndex).label
                : (mode == Mode.BURST ? "burst" : "timed");
        spark.stopPhase(label);
        recorder.endPhase(label);
        // Time out any still-expected attempts so the CSV is closed cleanly.
        for (UUID id : new ArrayList<>(deadlines.keySet())) {
            MetricsRecorder.Attempt a = probe.forget(id);
            if (a != null) recorder.onTimeout(a);
            deadlines.remove(id);
        }
    }

    /** Async tick: ~10 Hz. Reaps timeouts then dispatches up to the concurrency cap. */
    private void tick() {
        try {
            long now = System.currentTimeMillis();

            // 0) No-progress watchdog. If the runner has been idle for
            //    `no-progress-kickstart-ms` (default 30s) during a measurement
            //    phase — not warm-up, not a sequence gap — force-clear all
            //    per-player bookkeeping, log a warning, and re-stamp progress
            //    so the next tick dispatches normally. This recovers from the
            //    sporadic "freeze between targets" symptom without requiring
            //    operator intervention. The watchdog is a safety net, not a
            //    primary mechanism — every triggered kickstart should be
            //    logged and investigated, but the run continues either way.
            long kickstartMs = config.getLong("no-progress-kickstart-ms", 30000L);
            boolean inMeasurementPhase = running.get()
                    && !warmupActive
                    && !(mode == Mode.SEQUENCE && seqGapEndMs > 0L && now < seqGapEndMs);
            if (inMeasurementPhase && kickstartMs > 0
                    && lastProgressEpochMs > 0
                    && (now - lastProgressEpochMs) > kickstartMs) {
                kickstartCount++;
                plugin.getLogger().warning(String.format(
                        "[StressTestRTP] no-progress watchdog fired (idle %d ms); kickstart #%d: clearing %d deadlines, %d cooldowns, inFlight=%d",
                        (now - lastProgressEpochMs), kickstartCount,
                        deadlines.size(), nextDispatchAt.size(), inFlight.get()));
                for (UUID id : new ArrayList<>(deadlines.keySet())) {
                    MetricsRecorder.Attempt a = probe.forget(id);
                    if (a != null) recorder.onTimeout(a);
                    deadlines.remove(id);
                }
                inFlight.set(0);
                nextDispatchAt.clear();
                lastProgressEpochMs = now; // re-arm watchdog so it doesn't spam
            }

            // 0.5) Spark profile rotation. While a measurement phase is
            //      active and `spark.rotate-seconds` > 0, periodically stop
            //      and restart the spark profile so each slice is flushed to
            //      disk as a `.sparkprofile` file. Without this, a server
            //      crash mid-phase (OOM, watchdog kill, host reboot) loses
            //      the entire phase's profiling data — the failure mode that
            //      occurred during the EssentialsX phase of the
            //      20260502-023640 stress run. The rotation is gated to
            //      measurement phases (skipped during warm-up and sequence
            //      gaps) so warm-up and idle ticks don't dilute or fragment
            //      the per-target call-stack samples.
            if (inMeasurementPhase) {
                spark.rotateIfDue(now);
            }

            // 1) Reap timeouts.
            for (var entry : deadlines.entrySet()) {
                if (entry.getValue() <= now) {
                    UUID id = entry.getKey();
                    MetricsRecorder.Attempt a = probe.forget(id);
                    if (a != null) recorder.onTimeout(a);
                    deadlines.remove(id);
                    inFlight.decrementAndGet();
                }
            }

            // 2) Should we keep going?
            if (mode == Mode.TIMED && now >= endEpochMs && inFlight.get() == 0) {
                stop();
                return;
            }
            if (mode == Mode.BURST && burstRemaining == 0 && inFlight.get() == 0) {
                stop();
                return;
            }
            Targets.Entry pinned = null;
            if (mode == Mode.SEQUENCE && warmupActive) {
                // Warm-up phase machine: cycle every target for `warmupSliceMs`,
                // `warmupCycles` times. No CSV, no spark, no gap between slices —
                // we want sustained dispatch to maximise JIT throughput.
                if (now >= warmupSliceEndMs) {
                    // Slice ended: log per-target summary, advance.
                    int attemptsThisSlice = Math.max(0,
                            recorder.totalAttempts() - warmupSliceStartTotal);
                    long wallMs = Math.max(0L, now - warmupSliceStartEpoch);
                    String label = seqTargets.get(warmupTargetIdx).label;
                    logWarmup(String.format(java.util.Locale.ROOT,
                            "  cycle %d/%d  %-14s  %d ms  %d attempts",
                            warmupCycleIdx + 1, warmupCycles, label, wallMs, attemptsThisSlice));
                    // Drain in-flight before advancing so the next target's
                    // slice doesn't measure the prior plugin's stragglers.
                    if (inFlight.get() > 0) {
                        long warmupDrainDeadline = warmupSliceEndMs + 5000L;
                        if (now < warmupDrainDeadline) return;
                        for (UUID id : new ArrayList<>(deadlines.keySet())) {
                            MetricsRecorder.Attempt a = probe.forget(id);
                            if (a != null) recorder.onTimeout(a);
                            if (deadlines.remove(id) != null) inFlight.decrementAndGet();
                        }
                    }
                    warmupTargetIdx++;
                    if (warmupTargetIdx >= seqTargets.size()) {
                        warmupTargetIdx = 0;
                        warmupCycleIdx++;
                    }
                    if (warmupCycleIdx >= warmupCycles) {
                        // Warm-up complete: re-enable recording and start the
                        // first measurement phase. Reset seqIndex defensively
                        // so dispatch picks the first configured target even
                        // if earlier code paths advanced it (they don't today,
                        // but the explicit reset removes a class of bugs).
                        logWarmup("warm-up complete; starting measurement sequence");
                        // Trial-and-error pruning: any target whose command
                        // template did not produce a single successful
                        // PlayerTeleportEvent during warm-up is dropped from
                        // the measurement sequence. This catches both
                        // "plugin not installed" (no command registered) and
                        // "command syntax wrong for this server" (rejected
                        // every time) without any plugin-name guessing — the
                        // dispatcher itself is the oracle. If pruning would
                        // empty the list (e.g. warm-up was too short to land
                        // any teleport), keep the original roster so the run
                        // still produces output rather than silently exiting.
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
                        seqIndex = 0;
                        seqGapEndMs = 0L;
                        long t = System.currentTimeMillis();
                        seqPhaseEndMs = t + seqPerTargetMs;
                        // Force-clear all per-player state carried over from
                        // warm-up. The dispatch loop's `deadlines.containsKey`
                        // and `nextDispatchAt` guards would otherwise skip
                        // every roster player whose last warm-up attempt
                        // didn't have a corresponding teleport-event firing
                        // before the slice ended (silently rejected, attributed
                        // late, etc.). Recording is off during warm-up, so
                        // none of these lingering attempts have CSV impact;
                        // they only need their bookkeeping wiped.
                        for (UUID id : new ArrayList<>(deadlines.keySet())) {
                            MetricsRecorder.Attempt a = probe.forget(id);
                            if (a != null) recorder.onTimeout(a); // no-op while !recording
                            deadlines.remove(id);
                        }
                        inFlight.set(0);
                        nextDispatchAt.clear();
                        lastProgressEpochMs = System.currentTimeMillis();
                        if (!seqTargets.isEmpty()) {
                            spark.startPhase(seqTargets.get(0).label);
                            recorder.beginPhase(seqTargets.get(0).label);
                            plugin.getLogger().info(String.format(
                                "[StressTestRTP] measurement sequence begin: target=%s, phase ends at +%ds, %d targets total",
                                seqTargets.get(0).label, seqPerTargetMs / 1000L, seqTargets.size()));
                        } else {
                            plugin.getLogger().warning(
                                "[StressTestRTP] warm-up complete but seqTargets is empty; nothing to dispatch");
                        }
                        return; // pick up measurement on the next tick
                    }
                    warmupSliceStartEpoch = System.currentTimeMillis();
                    warmupSliceStartTotal = recorder.totalAttempts();
                    warmupSliceEndMs = warmupSliceStartEpoch + warmupSliceMs;
                }
                pinned = seqTargets.get(warmupTargetIdx);
            } else if (mode == Mode.SEQUENCE) {
                // Phase machine: run-phase → gap-phase → next target → ... → stop.
                if (seqGapEndMs > 0L) {
                    // In gap: dispatch nothing, just wait it out.
                    if (now < seqGapEndMs) return;
                    // Gap finished — advance.
                    seqGapEndMs = 0L;
                    seqIndex++;
                    if (seqIndex >= seqTargets.size()) {
                        if (inFlight.get() == 0) { stop(); return; }
                        return;
                    }
                    seqPhaseEndMs = now + seqPerTargetMs;
                    nextDispatchAt.clear();
                    lastProgressEpochMs = now;
                    spark.startPhase(seqTargets.get(seqIndex).label);
                    recorder.beginPhase(seqTargets.get(seqIndex).label);
                } else if (now >= seqPhaseEndMs) {
                    // Run-phase finished; let in-flight drain, then enter gap.
                    // Bounded drain: if attempts are still in flight more than
                    // 5 s after the phase end (e.g. silently rejected with no
                    // console-match and no timeout firing yet), force-timeout
                    // them so the sequence cannot stall here indefinitely.
                    long drainDeadline = seqPhaseEndMs + 5000L;
                    if (inFlight.get() != 0) {
                        if (now >= drainDeadline) {
                            // Force-clear: drop every still-tracked deadline
                            // and reset inFlight to zero. The previous
                            // implementation only decremented inFlight when
                            // `deadlines.remove(id) != null`, which left
                            // inFlight stuck positive whenever a concurrent
                            // onAttemptCompleted/onConsoleFail had already
                            // removed the same deadline — and any positive
                            // residual inFlight at end-of-sequence pins the
                            // line-474 `if (inFlight.get() == 0) stop()`
                            // guard open forever, hanging the run with the
                            // tick task still firing at 10 Hz (observed as
                            // fans-revving freeze ~60 s after phase start).
                            for (UUID id : new ArrayList<>(deadlines.keySet())) {
                                MetricsRecorder.Attempt a = probe.forget(id);
                                if (a != null) recorder.onTimeout(a);
                                deadlines.remove(id);
                            }
                            inFlight.set(0);
                            nextDispatchAt.clear();
                        } else {
                            return;
                        }
                    }
                    // Run-phase ended — stop the spark profile for this target
                    // BEFORE entering the gap, so the gap's idle ticks don't
                    // dilute the per-target sample.
                    String endingLabel = seqTargets.get(seqIndex).label;
                    spark.stopPhase(endingLabel);
                    recorder.endPhase(endingLabel);
                    // Between-phases world save. On Spigot, chunks loaded by
                    // the prior plugin's RTP path tend to linger in memory
                    // until the next save tick, biasing the next phase's
                    // baseline (warmer disk cache, higher resident heap,
                    // skewed chunk-load counters). Forcing a save here gives
                    // each phase a comparable on-disk state. Gated by config
                    // so Folia / Paper operators can opt out — Folia in
                    // particular has its own per-region save cadence.
                    saveWorldsBetweenPhases(endingLabel);
                    if (seqGapMs <= 0L) {
                        seqIndex++;
                        if (seqIndex >= seqTargets.size()) { stop(); return; }
                        seqPhaseEndMs = now + seqPerTargetMs;
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

            // 3) Fill empty slots.
            List<Player> roster = roster();
            if (roster.isEmpty()) return; // dormant until someone logs in
            long timeoutMs = config.getLong("attempt-timeout-ms", 30000L);
            int rosterIdx = 0;
            while (running.get() && inFlight.get() < concurrencyCap) {
                if (mode == Mode.BURST && burstRemaining <= 0) break;
                if (mode == Mode.TIMED && now >= endEpochMs) break;
                if (mode == Mode.SEQUENCE && !warmupActive && now >= seqPhaseEndMs) break;
                if (mode == Mode.SEQUENCE && warmupActive && now >= warmupSliceEndMs) break;
                int rosterSize = roster.size();
                if (rosterSize <= 0) break; // roster emptied mid-loop (race)
                int pickIdx = Math.floorMod(rosterIdx, rosterSize);
                if (pickIdx < 0 || pickIdx >= rosterSize) break; // defensive
                Player target = roster.get(pickIdx);
                rosterIdx++;
                UUID tid = target.getUniqueId();
                // Bound the scan: regardless of whether we dispatched on
                // this iteration, after roster.size()*4 picks we have walked
                // every player multiple times — if no slot has been filled
                // by then, every player is either in-flight or cooling down,
                // and continuing would spin the CPU indefinitely (observed
                // as an indefinite freeze with fans revving). The break
                // MUST run before any `continue` paths below, otherwise the
                // loop never terminates this tick.
                if (rosterIdx > rosterSize * 4) break; // everyone is busy this tick
                if (deadlines.containsKey(tid)) continue; // already in flight
                Long readyAt = nextDispatchAt.get(tid);
                if (readyAt != null && readyAt > now) continue; // per-player cooldown
                dispatchOne(target, timeoutMs, pinned);
                lastProgressEpochMs = System.currentTimeMillis();
                if (mode == Mode.BURST) burstRemaining--;
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "StressTestRTP runner tick failed", t);
        }
    }

    private void dispatchOne(Player target, long timeoutMs, Targets.Entry pinned) {
        TpsMsptHeapSampler.Snapshot snap = sampler.latest();
        Location from = target.getLocation();
        // Round-robin across configured target commands so multiple co-installed
        // RTP-style plugins are exercised evenly within a single run — unless
        // SEQUENCE mode pinned a single target for the current phase.
        Targets.Entry chosen;
        if (pinned != null) {
            chosen = pinned;
        } else {
            List<Targets.Entry> targets = Targets.load(config, plugin.getLogger());
            chosen = targets.get(Math.floorMod(targetCursor.getAndIncrement(), targets.size()));
        }
        MetricsRecorder.Attempt attempt = new MetricsRecorder.Attempt(
                UUID.randomUUID(),
                target.getName(),
                from.getWorld() == null ? "" : from.getWorld().getName(),
                chosen.label,
                System.currentTimeMillis(),
                from.getX(), from.getZ(),
                snap.tps(), snap.mspt(), snap.heapUsedMb());
        // Snapshot the global chunk-load counter as the per-attempt
        // baseline. The matching read happens in
        // MetricsRecorder.onComplete / onTimeout; the delta is the
        // chunk-I/O cost the harness attributes to this attempt.
        // Returns -1 when no counter is wired (chunk accounting disabled).
        attempt.chunkLoadsAtDispatch = recorder.chunkLoadsTotal();

        // Race-condition fix: install runner-side bookkeeping (deadlines,
        // nextDispatchAt, lastDispatched, inFlight) BEFORE registering the
        // probe expectation and BEFORE dispatching the command. Otherwise
        // PlayerTeleportEvent can fire on the main thread between
        // probe.expect(...) and deadlines.put(...) — observed under low
        // per-player-gap-ticks where queue-served plugins teleport in
        // microseconds. The completion callback's deadlines.remove(...)
        // returns null (entry not yet present), inFlight is never
        // decremented (because deadlines.remove was the gate), then this
        // method races on to put a stale deadline that nothing clears.
        // Result: the player's UUID is permanently stuck in deadlines, the
        // dispatch loop's containsKey guard skips them forever, and the
        // run wedges. Doing put-then-expect makes the race benign:
        // completion either sees the deadline (and removes it cleanly) or
        // the deadline is removed before completion fires (timeout/reset
        // path).
        UUID tid = target.getUniqueId();
        long gapMs = perPlayerGapMs();
        if (gapMs > 0L) nextDispatchAt.put(tid, System.currentTimeMillis() + gapMs);
        deadlines.put(tid, System.currentTimeMillis() + timeoutMs);
        lastDispatched.set(tid);
        inFlight.incrementAndGet();
        recorder.onDispatch(attempt);
        probe.expect(attempt, tid);

        String cmd = chosen.render(target.getName());

        // Hop to the player-owning thread (Folia: entity scheduler; else: main)
        // before dispatching, so the target plugin's own threading expectations
        // are satisfied by the time it inspects the player.
        //
        // Latency-attribution fix: capture the timestamp inside the runnable,
        // i.e. AFTER the BukkitScheduler.runTask hop on Spigot/Paper (which
        // always defers to the next tick boundary, 0–50 ms, avg ~25 ms).
        // Without this, every per-attempt latency includes that wait —
        // observable as a baseline floor of ~50 ms even when the target
        // plugin teleports in microseconds (e.g. RTP queue-served).
        Sched.runOnPlayer(plugin, target, () -> {
            attempt.commandDispatchedEpochMs = System.currentTimeMillis();
            Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), cmd);
        });
    }

    private List<Player> roster() {
        String mode = config.getString("roster", "all").toLowerCase(Locale.ROOT);
        List<Player> out = new ArrayList<>();
        if (mode.startsWith("permission:")) {
            String node = mode.substring("permission:".length()).trim();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission(node)) out.add(p);
            }
        } else if (mode.startsWith("names:")) {
            // Names list comes through the YAML list, not the inline string.
            List<String> names = config.getStringList("roster-names");
            for (Player p : Bukkit.getOnlinePlayers()) {
                for (String n : names) {
                    if (p.getName().equalsIgnoreCase(n)) { out.add(p); break; }
                }
            }
        } else {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (operatorId != null && p.getUniqueId().equals(operatorId)) continue;
                out.add(p);
            }
            // Single-account fallback: if the operator is the only player online,
            // include them. The (α) "admin-self" mode from the proposal.
            if (out.isEmpty() && operatorId != null) {
                Player self = Bukkit.getPlayer(operatorId);
                if (self != null) out.add(self);
            }
        }
        return out;
    }

    /**
     * Force-save every loaded world between phases. Bukkit's {@code World.save()}
     * must be called on the main thread; the runner tick is async (10 Hz), so we
     * schedule the actual save via the global scheduler and do not block on it —
     * the gap (default a few seconds, see {@code sequence.gap-seconds}) absorbs
     * the I/O latency before the next phase begins dispatch.
     *
     * <p>Behaviour is controlled by {@code save-worlds-between-phases} in
     * config.yml:
     * <ul>
     *   <li>{@code "auto"} (default) — save only when the server is Spigot
     *       (i.e. not Paper / Folia), since Paper/Folia have their own region-
     *       or world-level save cadences and an extra forced save dilutes the
     *       gap.</li>
     *   <li>{@code "always"} / {@code true} — save unconditionally.</li>
     *   <li>{@code "never"} / {@code false} — never save.</li>
     * </ul>
     *
     * <p>Rationale: on Spigot, chunks loaded by the prior plugin's RTP path
     * stay resident until the next vanilla save-all tick (default ~5 min),
     * which biases the next phase's baseline (warmer page cache, higher heap
     * carry-over, inflated `chunks_loaded` for the *next* phase whose first
     * attempts re-touch lingering chunks). A save between phases gives each
     * target a comparable starting state.
     */
    private void saveWorldsBetweenPhases(String endingLabel) {
        String mode = config.getString("save-worlds-between-phases", "auto");
        if (mode == null) mode = "auto";
        mode = mode.trim().toLowerCase(Locale.ROOT);
        boolean save;
        switch (mode) {
            case "never":
            case "false":
            case "off":
            case "no":
                save = false;
                break;
            case "always":
            case "true":
            case "on":
            case "yes":
                save = true;
                break;
            case "auto":
            default:
                // Auto: save on Spigot only. Heuristic: Bukkit#getName() is
                // "CraftBukkit" on Spigot, "Paper" on Paper, "Folia" on Folia.
                // Treat anything that is NOT Paper/Folia as Spigot for safety.
                String serverName = Bukkit.getName();
                String s = serverName == null ? "" : serverName.toLowerCase(Locale.ROOT);
                save = !(s.contains("paper") || s.contains("folia"));
                break;
        }
        if (!save) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                long t0 = System.currentTimeMillis();
                int n = 0;
                for (org.bukkit.World w : Bukkit.getWorlds()) {
                    try {
                        w.save();
                        n++;
                    } catch (Throwable t) {
                        plugin.getLogger().log(Level.WARNING,
                                "[StressTestRTP] world.save() failed for " + w.getName(), t);
                    }
                }
                plugin.getLogger().info(String.format(Locale.ROOT,
                        "[StressTestRTP] between-phase save after '%s': %d world(s) saved in %d ms",
                        endingLabel, n, System.currentTimeMillis() - t0));
            });
        } catch (Throwable t) {
            // Folia (if "always" was forced): runTask on the global region
            // scheduler is the wrong API. We don't fight that here — the
            // "auto" default already excludes Folia. Just log and move on.
            plugin.getLogger().log(Level.WARNING,
                    "[StressTestRTP] between-phase world save scheduling failed", t);
        }
    }

    /** Per-player minimum gap between successive dispatches, in ms.
     *  Configurable via {@code per-player-gap-ticks} (default 3 ticks = 150 ms).
     *  Bounded to a sane range so a misconfiguration cannot stall a run. */
    private long perPlayerGapMs() {
        long ticks = config.getLong("per-player-gap-ticks", 3L);
        if (ticks < 0L) ticks = 0L;
        if (ticks > 100L) ticks = 100L;
        return ticks * 50L;
    }

    public String operatorName() { return operatorName; }
    public Mode mode() { return mode; }
    public long endEpochMs() { return endEpochMs; }
    public int concurrencyCap() { return concurrencyCap; }
}
