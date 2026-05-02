package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Periodic sampler for TPS, MSPT, and heap-used (MB).
 *
 * <p>Reads {@code Server#getTPS()} and {@code Server#getAverageTickTime()}
 * reflectively because they are Paper / Folia API additions absent from
 * pure Spigot. On Spigot we fall back to a simple wall-clock TPS estimator
 * that runs on the main thread once per second; MSPT is reported as -1.
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

    private int taskId = -1;
    private int spigotTpsTaskId = -1;
    private long spigotTpsLastTick = -1L;
    private long spigotTpsLastWallNs = -1L;
    private final AtomicReference<Double> spigotTps = new AtomicReference<>(-1.0);

    // Reflective access — resolved once.
    private final Method getTpsMethod;
    private final Method getAvgTickTimeMethod;

    public TpsMsptHeapSampler(Plugin plugin, long periodMs) {
        this.plugin = plugin;
        this.periodMs = periodMs;
        this.getTpsMethod = lookup("getTPS");
        this.getAvgTickTimeMethod = lookup("getAverageTickTime");
    }

    private static Method lookup(String name) {
        try {
            return Bukkit.getServer().getClass().getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    public void start() {
        if (taskId > 0) return;
        // Snapshot loop runs async — pure JMX + already-computed Bukkit values.
        taskId = Sched.runAsyncTimer(plugin, this::sample, periodMs);

        // Spigot fallback: poll a tick counter on the main thread once per
        // second to estimate TPS. On Folia this would throw if scheduled to
        // the BukkitScheduler synchronously, so only enable when no native
        // getTPS is available AND we're not on Folia.
        if (getTpsMethod == null && !Sched.isFolia()) {
            spigotTpsTaskId = Bukkit.getScheduler()
                    .runTaskTimer(plugin, this::spigotTpsTick, 20L, 20L).getTaskId();
        }
    }

    public void stop() {
        Sched.cancel(taskId); taskId = -1;
        if (spigotTpsTaskId > 0) { Bukkit.getScheduler().cancelTask(spigotTpsTaskId); spigotTpsTaskId = -1; }
    }

    /** Snapshot reads are concurrent; safe for the dispatch hot path. */
    public Snapshot latest() { return latest; }

    private void sample() {
        double tps = readTps();
        double mspt = readMspt();
        long heapMb = ManagementFactory.getMemoryMXBean()
                .getHeapMemoryUsage().getUsed() / (1024L * 1024L);
        latest = new Snapshot(tps, mspt, heapMb);
        synchronized (tpsSamples) {
            if (tps  >= 0) tpsSamples.add(tps);
            if (mspt >= 0) msptSamples.add(mspt);
            heapSamples.add(heapMb);
        }
    }

    private double readTps() {
        if (getTpsMethod != null) {
            try {
                Object res = getTpsMethod.invoke(Bukkit.getServer());
                if (res instanceof double[] arr && arr.length > 0) return arr[0]; // 1m TPS
            } catch (ReflectiveOperationException ignored) { /* fall through */ }
        }
        return spigotTps.get();
    }

    private double readMspt() {
        if (getAvgTickTimeMethod != null) {
            try {
                Object res = getAvgTickTimeMethod.invoke(Bukkit.getServer());
                if (res instanceof Number n) return n.doubleValue();
            } catch (ReflectiveOperationException ignored) { /* fall through */ }
        }
        return -1.0;
    }

    private void spigotTpsTick() {
        // Scheduled every 20 ticks. If 20 ticks took ~1s wall, TPS == 20.
        // Wall delta < 1s shouldn't happen (BukkitScheduler is tick-driven),
        // but wall delta > 1s means the server fell behind — that's exactly
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
