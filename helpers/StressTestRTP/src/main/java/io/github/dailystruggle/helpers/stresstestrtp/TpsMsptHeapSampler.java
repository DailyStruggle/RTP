package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

/**
 * Periodic sampler for TPS, MSPT, and heap-used (MB).
 *
 * <p>Reads {@code Server#getTPS()} and {@code Server#getAverageTickTime()}
 * reflectively because they are Paper API additions absent from pure Spigot.
 * On Folia both methods exist but throw (TPS/MSPT are per-region there, with
 * no global aggregate), so the sampler probes them once at construction and,
 * when they are absent (Spigot) <em>or</em> throw (Folia), falls back to
 * tick-aligned wall-clock sampling. The fallback timers are scheduled via
 * {@link Sched#runGlobalTimer} so they land on the global region scheduler on
 * Folia and the main thread on Spigot/Paper:
 * <ul>
 *   <li><b>TPS</b>: a 20-tick timer measures wall delta and reports
 *       {@code min(20, 20 / secs)}.</li>
 *   <li><b>MSPT</b>: a 1-tick timer measures the wall delta between
 *       consecutive ticks. The reported value is <em>tick wall time</em>,
 *       not Paper's "work performed per tick" - when the server is idle
 *       it is padded to ~50 ms by the scheduler. During a stress run
 *       (the only regime we publish from) the server is never idle, so
 *       wall-MSPT and Paper's MSPT converge above 50 ms and the same
 *       p95/max aggregates are meaningful. Below 50 ms wall-MSPT is
 *       capped - finer resolution would require NMS/agent instrumentation
 *       and is intentionally out of scope.</li>
 * </ul>
 *
 * <p>Heap-used is read from the JVM's {@code MemoryMXBean#getHeapMemoryUsage()}.
 * No GC is forced (that would skew the very metric we're measuring).
 *
 * <p>The sampler maintains:
 * <ul>
 *   <li>latest snapshot (read by {@link MetricsRecorder.Attempt} at dispatch)</li>
 *   <li>rolling samples for the run summary (min TPS, p95 MSPT, peak heap)</li>
 * </ul>
 */
public final class TpsMsptHeapSampler {

    public record Snapshot(double tps, double mspt, long heapUsedMb) {}

    private final Plugin plugin;
    private final long periodMs;

    private volatile Snapshot latest = new Snapshot(-1, -1, -1);
    private final List<Double> tpsSamples = new ArrayList<>(1024);
    private final List<Double> msptSamples = new ArrayList<>(1024);
    private final List<Long>   heapSamples = new ArrayList<>(1024);

    private Object taskId = null;
    private Object spigotTpsTaskId = null;
    private Object spigotMsptTaskId = null;
    private long spigotTpsLastTick = -1L;
    private long spigotTpsLastWallNs = -1L;
    private long spigotMsptLastWallNs = -1L;
    private final AtomicReference<Double> spigotTps = new AtomicReference<>(-1.0);
    private final AtomicReference<Double> spigotMspt = new AtomicReference<>(-1.0);

    // Reflective access - resolved once.
    private final Method getTpsMethod;
    private final Method getAvgTickTimeMethod;
    // Whether the native methods actually return a usable value. They exist on
    // Folia but throw UnsupportedOperationException (TPS/MSPT are per-region
    // there, with no global aggregate), so method presence is not enough - we
    // probe once and fall back to wall-clock tick sampling when they throw.
    private final boolean tpsNativeWorks;
    private final boolean msptNativeWorks;

    // Heap-pressure-over-time series. Optional per-run CSV that records one
    // row per sample so heap-used can be plotted against cumulative /rtp
    // attempts - the slope is RAM consumed per teleport. Started by
    // beginRun() and stopped on run-stop / disable. All access is guarded by
    // heapSeriesLock because start/stop run on the command thread while the
    // writes happen on the async sampler thread.
    public static final String HEAP_SERIES_HEADER =
            "epoch_ms,elapsed_ms,heap_used_mb,heap_committed_mb,heap_max_mb,"
                    + "tps,mspt,attempts_completed,attempts_since_start,"
                    + "heap_delta_mb,mb_per_attempt";
    private final Object heapSeriesLock = new Object();
    private BufferedWriter heapSeriesWriter = null;
    private IntSupplier heapSeriesAttempts = null;
    private long heapSeriesStartMs = -1L;
    private long heapSeriesBaselineMb = -1L;
    private int  heapSeriesBaselineAttempts = -1;

    public TpsMsptHeapSampler(Plugin plugin, long periodMs) {
        this.plugin = plugin;
        this.periodMs = periodMs;
        this.getTpsMethod = lookup("getTPS");
        this.getAvgTickTimeMethod = lookup("getAverageTickTime");
        this.tpsNativeWorks = probe(getTpsMethod, res -> res instanceof double[] arr && arr.length > 0);
        this.msptNativeWorks = probe(getAvgTickTimeMethod, res -> res instanceof Number);
    }

    /** Invokes {@code m} once and returns true iff it yields a usable value
     *  (no exception, and the result satisfies {@code ok}). Used to detect
     *  Folia, where getTPS/getAverageTickTime exist but throw. */
    private static boolean probe(Method m, java.util.function.Predicate<Object> ok) {
        if (m == null) return false;
        try {
            return ok.test(m.invoke(Bukkit.getServer()));
        } catch (Throwable t) {
            return false;
        }
    }

    private static Method lookup(String name) {
        try {
            return Bukkit.getServer().getClass().getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    public void start() {
        if (taskId != null) return;
        // Snapshot loop runs async - pure JMX + already-computed Bukkit values.
        taskId = Sched.runAsyncTimer(plugin, this::sample, periodMs);

        // Wall-clock TPS fallback: tick a counter on a tick-aligned timer once
        // per second and report 20 / wall-seconds. Enabled whenever the native
        // getTPS is unusable - including Folia, where getTPS exists but throws
        // (no global TPS aggregate). Routed through Sched.runGlobalTimer so it
        // lands on the global region scheduler on Folia and the main thread on
        // Spigot/Paper, instead of the BukkitScheduler that Folia rejects.
        if (!tpsNativeWorks) {
            spigotTpsTaskId = Sched.runGlobalTimer(plugin, this::spigotTpsTick, 20L);
        }
        // Wall-clock MSPT fallback: measure the wall delta between consecutive
        // ticks on a 1-tick timer. Enabled whenever native getAverageTickTime
        // is unusable (Spigot, and Folia where it throws). Same global-timer
        // routing as the TPS fallback.
        if (!msptNativeWorks) {
            spigotMsptTaskId = Sched.runGlobalTimer(plugin, this::spigotMsptTick, 1L);
        }
    }

    public void stop() {
        Sched.cancel(taskId); taskId = null;
        Sched.cancel(spigotTpsTaskId); spigotTpsTaskId = null;
        Sched.cancel(spigotMsptTaskId); spigotMsptTaskId = null;
    }

    /** Snapshot reads are concurrent; safe for the dispatch hot path. */
    public Snapshot latest() { return latest; }

    private void sample() {
        double tps = readTps();
        double mspt = readMspt();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long heapMb = heap.getUsed() / (1024L * 1024L);
        latest = new Snapshot(tps, mspt, heapMb);
        synchronized (tpsSamples) {
            if (tps  >= 0) tpsSamples.add(tps);
            // On Paper/Folia, push the polled MSPT into the rolling list.
            // On Spigot, msptSamples is fed by spigotMsptTick() at 1-tick
            // resolution - pushing the polled value here would double-count
            // and bias the p95 toward whichever value happens to be cached
            // in spigotMspt at sample time.
            if (mspt >= 0 && msptNativeWorks) msptSamples.add(mspt);
            heapSamples.add(heapMb);
        }
        writeHeapSeriesRow(heap, heapMb, tps, mspt);
    }

    /**
     * Begins a heap-pressure-over-time series at {@code csvPath}. One row is
     * appended per sample for as long as the series is active, capturing
     * heap-used/committed/max alongside the cumulative attempt count read
     * from {@code attemptsSupplier} (typically {@code MetricsRecorder#totalAttempts}).
     * Plotting {@code heap_used_mb} against {@code attempts_since_start} yields
     * RAM consumed per /rtp (the {@code mb_per_attempt} column is that slope
     * relative to the series baseline). Calling this while a series is active
     * closes the previous one first.
     */
    public void startHeapSeries(Path csvPath, IntSupplier attemptsSupplier) throws IOException {
        synchronized (heapSeriesLock) {
            closeHeapSeriesWriter();
            Files.createDirectories(csvPath.getParent());
            Files.writeString(csvPath, HEAP_SERIES_HEADER + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            heapSeriesWriter = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
            heapSeriesAttempts = attemptsSupplier;
            heapSeriesStartMs = -1L;          // captured on first row
            heapSeriesBaselineMb = -1L;
            heapSeriesBaselineAttempts = -1;
        }
    }

    /** Flushes and closes the active heap series, if any. Null-safe. */
    public void stopHeapSeries() {
        synchronized (heapSeriesLock) {
            closeHeapSeriesWriter();
            heapSeriesAttempts = null;
        }
    }

    private void closeHeapSeriesWriter() {
        if (heapSeriesWriter != null) {
            try {
                heapSeriesWriter.flush();
                heapSeriesWriter.close();
            } catch (IOException e) {
                plugin.getLogger().warning("[StressTestRTP] failed to close heap series: " + e.getMessage());
            }
            heapSeriesWriter = null;
        }
    }

    private void writeHeapSeriesRow(MemoryUsage heap, long heapMb, double tps, double mspt) {
        synchronized (heapSeriesLock) {
            BufferedWriter w = heapSeriesWriter;
            if (w == null) return;
            long now = System.currentTimeMillis();
            int attempts = heapSeriesAttempts != null ? heapSeriesAttempts.getAsInt() : -1;
            if (heapSeriesStartMs < 0) {
                heapSeriesStartMs = now;
                heapSeriesBaselineMb = heapMb;
                heapSeriesBaselineAttempts = Math.max(0, attempts);
            }
            long elapsed = now - heapSeriesStartMs;
            long committedMb = heap.getCommitted() / (1024L * 1024L);
            long maxMb = heap.getMax() < 0 ? -1L : heap.getMax() / (1024L * 1024L);
            int attemptsSince = attempts < 0 ? -1 : Math.max(0, attempts - heapSeriesBaselineAttempts);
            long heapDelta = heapMb - heapSeriesBaselineMb;
            String mbPerAttempt = attemptsSince > 0
                    ? String.format(java.util.Locale.ROOT, "%.4f", (double) heapDelta / attemptsSince)
                    : "";
            String row = String.join(",",
                    Long.toString(now),
                    Long.toString(elapsed),
                    Long.toString(heapMb),
                    Long.toString(committedMb),
                    maxMb < 0 ? "" : Long.toString(maxMb),
                    tps  >= 0 ? String.format(java.util.Locale.ROOT, "%.3f", tps)  : "",
                    mspt >= 0 ? String.format(java.util.Locale.ROOT, "%.3f", mspt) : "",
                    attempts < 0 ? "" : Integer.toString(attempts),
                    attemptsSince < 0 ? "" : Integer.toString(attemptsSince),
                    Long.toString(heapDelta),
                    mbPerAttempt);
            try {
                w.write(row);
                w.newLine();
                w.flush();
            } catch (IOException e) {
                plugin.getLogger().warning("[StressTestRTP] heap series append failed: " + e.getMessage());
            }
        }
    }

    private double readTps() {
        if (tpsNativeWorks) {
            try {
                Object res = getTpsMethod.invoke(Bukkit.getServer());
                if (res instanceof double[] arr && arr.length > 0) return arr[0]; // 1m TPS
            } catch (ReflectiveOperationException ignored) { /* fall through */ }
        }
        return spigotTps.get();
    }

    private double readMspt() {
        if (msptNativeWorks) {
            try {
                Object res = getAvgTickTimeMethod.invoke(Bukkit.getServer());
                if (res instanceof Number n) return n.doubleValue();
            } catch (ReflectiveOperationException ignored) { /* fall through */ }
        }
        return spigotMspt.get();
    }

    private void spigotMsptTick() {
        // Scheduled every tick. The wall delta between consecutive
        // invocations is the wall time of one tick - equivalent to
        // Paper's MSPT above 50 ms (the regime we care about), padded
        // to ~50 ms when the server is idle. See class Javadoc.
        long now = System.nanoTime();
        if (spigotMsptLastWallNs > 0) {
            double ms = (now - spigotMsptLastWallNs) / 1_000_000.0;
            if (ms >= 0.0) {
                spigotMspt.set(ms);
                synchronized (tpsSamples) {
                    msptSamples.add(ms);
                }
            }
        }
        spigotMsptLastWallNs = now;
    }

    private void spigotTpsTick() {
        // Scheduled every 20 ticks. If 20 ticks took ~1s wall, TPS == 20.
        // Wall delta < 1s shouldn't happen (BukkitScheduler is tick-driven),
        // but wall delta > 1s means the server fell behind - that's exactly
        // the TPS dip we want to surface.
        long now = System.nanoTime();
        if (spigotTpsLastWallNs > 0) {
            double secs = (now - spigotTpsLastWallNs) / 1_000_000_000.0;
            if (secs > 0.0) spigotTps.set(Math.min(20.0, 20.0 / secs));
        }
        spigotTpsLastWallNs = now;
        spigotTpsLastTick++;
    }

    /** Aggregates aligned to the front-page comparison table. */
    public record Aggregates(double minTps, double p95Mspt, long peakHeapMb,
                             int sampleCount) {}

    public Aggregates aggregates() {
        synchronized (tpsSamples) {
            double minTps = tpsSamples.stream().mapToDouble(Double::doubleValue).min().orElse(-1);
            double[] msptArr = msptSamples.stream().mapToDouble(Double::doubleValue).toArray();
            Arrays.sort(msptArr);
            double p95 = msptArr.length == 0 ? -1
                    : msptArr[(int) Math.min(msptArr.length - 1L,
                              Math.round(0.95 * (msptArr.length - 1)))];
            long peakHeap = heapSamples.stream().mapToLong(Long::longValue).max().orElse(-1);
            return new Aggregates(minTps, p95, peakHeap,
                    Math.max(tpsSamples.size(), Math.max(msptSamples.size(), heapSamples.size())));
        }
    }
}
