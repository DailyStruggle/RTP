package io.github.dailystruggle.helpers.stresstestrtp;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Snapshots process and main-thread CPU time at phase boundaries.
 *
 * <p>"CPU per teleport" is computed as {@code (cpuEnd - cpuStart) / completedAttempts}
 * by {@link MetricsRecorder#endPhase}. Two flavours are recorded:
 * <ul>
 *   <li><b>Process CPU</b> - total user+system CPU charged to the JVM during the
 *       phase. Includes background work (GC, async chunk loaders, other plugins);
 *       useful as the "what does this plugin cost the box" number.</li>
 *   <li><b>Main-thread CPU</b> - the server tick thread's CPU time only. The
 *       most differentiating metric: a plugin that does sync chunk I/O on the
 *       tick thread shows huge main-thread CPU; a properly async plugin shows
 *       very little. Note that wall-clock blocking ({@code .join()} waiting on
 *       a chunk future) is <i>not</i> counted as CPU - that gap surfaces in
 *       MSPT instead, and the disparity between the two is itself a useful
 *       diagnostic.</li>
 * </ul>
 *
 * <p>Per-attempt CPU attribution is intentionally not provided: the work for
 * a single {@code /rtp} is split across the tick thread, the async chunk
 * loader, the safety scanner, and the entity scheduler, so per-attempt CPU
 * cannot be honestly assembled on Bukkit.
 *
 * <p><b>Folia.</b> There is no single tick thread: regions tick on a pool of
 * {@value #FOLIA_REGION_THREAD_MARKER} threads and a region is not pinned to
 * one of them. Reading the one thread that ran a startup task measured an
 * arbitrary share of the work, set by how the scheduler spread regions. On
 * Folia the "main" figure is therefore the summed CPU of every region
 * scheduler thread, kept as a monotonic accumulator of per-thread deltas so a
 * thread that appears or retires mid-phase cannot drive a phase delta
 * negative. {@link #mainThreadScope()} names which reading a row carries.
 */
public final class CpuSampler {

    /** Name fragment of Folia's region tick threads
     *  ({@code "Folia Region Scheduler Thread #N"} in 1.21.x and 26.x logs). */
    static final String FOLIA_REGION_THREAD_MARKER = "Region Scheduler Thread";

    private final OperatingSystemMXBean osBean;
    private final ThreadMXBean threadBean;
    private volatile long mainThreadId = -1L;

    /** Folia accumulator state; guarded by {@code this}. */
    private final java.util.Map<Long, Long> lastRegionCpu = new java.util.HashMap<>();
    private long regionCpuAccumNs = 0L;
    private int regionThreadCount = 0;

    public CpuSampler() {
        // OperatingSystemMXBean#getProcessCpuTime is on the com.sun extension
        // interface; cast is safe on every supported JVM (HotSpot, OpenJ9).
        java.lang.management.OperatingSystemMXBean raw =
                ManagementFactory.getOperatingSystemMXBean();
        this.osBean = (raw instanceof OperatingSystemMXBean s) ? s : null;
        this.threadBean = ManagementFactory.getThreadMXBean();
        // Best-effort enable per-thread CPU; on most JVMs this is true by default.
        if (threadBean.isThreadCpuTimeSupported() && !threadBean.isThreadCpuTimeEnabled()) {
            try { threadBean.setThreadCpuTimeEnabled(true); } catch (SecurityException ignored) {}
        }
    }

    /**
     * Records the id of whatever thread invokes this method as the main
     * (tick) thread. Call from a one-shot sync Bukkit task at startup.
     */
    public void recordCurrentThreadAsMain() {
        this.mainThreadId = Thread.currentThread().getId();
    }

    public long mainThreadId() { return mainThreadId; }

    /** Process-wide CPU time in nanoseconds, or {@code -1} if unavailable. */
    public long processCpuTimeNs() {
        return osBean != null ? osBean.getProcessCpuTime() : -1L;
    }

    /** Tick-thread CPU time in nanoseconds, or {@code -1} if unknown. On
     *  Folia: cumulative CPU of all region scheduler threads (see class doc). */
    public long mainThreadCpuTimeNs() {
        if (!threadBean.isThreadCpuTimeSupported()) return -1L;
        if (Sched.isFolia()) return regionThreadsCpuNs();
        if (mainThreadId <= 0) return -1L;
        try {
            return threadBean.getThreadCpuTime(mainThreadId);
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** {@code folia-region-threads:N} (N = threads summed at the last read)
     *  on Folia, {@code main-thread} elsewhere. Written per phase row. */
    public synchronized String mainThreadScope() {
        return Sched.isFolia() ? "folia-region-threads:" + regionThreadCount : "main-thread";
    }

    private synchronized long regionThreadsCpuNs() {
        java.util.Set<Long> seen = new java.util.HashSet<>();
        // maxDepth 0: names only, no stack capture and no safepoint per thread.
        for (java.lang.management.ThreadInfo info
                : threadBean.getThreadInfo(threadBean.getAllThreadIds(), 0)) {
            if (info == null || !isRegionThreadName(info.getThreadName())) continue;
            long id = info.getThreadId();
            long cpu;
            try {
                cpu = threadBean.getThreadCpuTime(id);
            } catch (Throwable ignored) {
                continue;
            }
            if (cpu < 0) continue; // thread died between enumeration and read
            seen.add(id);
            Long prev = lastRegionCpu.put(id, cpu);
            // First sighting contributes nothing: its lifetime CPU predates
            // this accumulator and would be billed to whichever phase saw it.
            if (prev != null && cpu > prev) regionCpuAccumNs += cpu - prev;
        }
        lastRegionCpu.keySet().retainAll(seen);
        regionThreadCount = seen.size();
        return seen.isEmpty() ? -1L : regionCpuAccumNs;
    }

    static boolean isRegionThreadName(String name) {
        return name != null && name.contains(FOLIA_REGION_THREAD_MARKER);
    }
}
