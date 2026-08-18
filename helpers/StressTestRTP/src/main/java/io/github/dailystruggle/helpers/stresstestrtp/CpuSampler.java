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
 */
public final class CpuSampler {

    private final OperatingSystemMXBean osBean;
    private final ThreadMXBean threadBean;
    private volatile long mainThreadId = -1L;

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

    /** Main-thread CPU time in nanoseconds, or {@code -1} if unknown. */
    public long mainThreadCpuTimeNs() {
        if (mainThreadId <= 0 || !threadBean.isThreadCpuTimeSupported()) return -1L;
        try {
            return threadBean.getThreadCpuTime(mainThreadId);
        } catch (Throwable t) {
            return -1L;
        }
    }
}
