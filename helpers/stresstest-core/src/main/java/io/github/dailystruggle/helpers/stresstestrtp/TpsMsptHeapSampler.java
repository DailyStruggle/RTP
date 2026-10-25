package io.github.dailystruggle.helpers.stresstestrtp;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
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
 * Periodic sampler for TPS, MSPT, and heap-used (MB) via {@link StressHost}
 * (ADR-122 Decision 1 & 2).
 */
public final class TpsMsptHeapSampler {

    public record Snapshot(double tps, double mspt, long heapUsedMb) {}

    private final StressHost plugin;
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

    private final boolean tpsNativeWorks;
    private final boolean msptNativeWorks;

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

    public TpsMsptHeapSampler(StressHost plugin, long periodMs) {
        this.plugin = plugin;
        this.periodMs = periodMs;
        this.tpsNativeWorks = plugin != null && plugin.nativeTps() >= 0.0;
        this.msptNativeWorks = plugin != null && plugin.nativeMspt() >= 0.0;
    }

    public void start() {
        if (taskId != null) return;
        taskId = Sched.runAsyncTimer(plugin, this::sample, periodMs);

        if (!tpsNativeWorks) {
            spigotTpsTaskId = Sched.runGlobalTimer(plugin, this::spigotTpsTick, 20L);
        }
        if (!msptNativeWorks) {
            spigotMsptTaskId = Sched.runGlobalTimer(plugin, this::spigotMsptTick, 1L);
        }
    }

    public void stop() {
        Sched.cancel(taskId); taskId = null;
        Sched.cancel(spigotTpsTaskId); spigotTpsTaskId = null;
        Sched.cancel(spigotMsptTaskId); spigotMsptTaskId = null;
    }

    public Snapshot latest() { return latest; }

    private void sample() {
        double tps = readTps();
        double mspt = readMspt();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long heapMb = heap.getUsed() / (1024L * 1024L);
        latest = new Snapshot(tps, mspt, heapMb);
        synchronized (tpsSamples) {
            if (tps  >= 0) tpsSamples.add(tps);
            if (mspt >= 0 && msptNativeWorks) msptSamples.add(mspt);
            heapSamples.add(heapMb);
        }
        writeHeapSeriesRow(heap, heapMb, tps, mspt);
    }

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
            heapSeriesStartMs = -1L;
            heapSeriesBaselineMb = -1L;
            heapSeriesBaselineAttempts = -1;
        }
    }

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
        if (tpsNativeWorks && plugin != null) {
            double v = plugin.nativeTps();
            if (v >= 0.0) return v;
        }
        return spigotTps.get();
    }

    private double readMspt() {
        if (msptNativeWorks && plugin != null) {
            double v = plugin.nativeMspt();
            if (v >= 0.0) return v;
        }
        return spigotMspt.get();
    }

    private void spigotMsptTick() {
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
        long now = System.nanoTime();
        if (spigotTpsLastWallNs > 0) {
            double secs = (now - spigotTpsLastWallNs) / 1_000_000_000.0;
            if (secs > 0.0) spigotTps.set(Math.min(20.0, 20.0 / secs));
        }
        spigotTpsLastWallNs = now;
        spigotTpsLastTick++;
    }

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
