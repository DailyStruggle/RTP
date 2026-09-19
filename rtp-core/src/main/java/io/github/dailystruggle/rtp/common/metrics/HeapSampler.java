package io.github.dailystruggle.rtp.common.metrics;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

/**
 * Thin platform-portable wrapper over {@link ManagementFactory#getMemoryMXBean()}'s
 * heap usage. All methods are O(1) and safe to call from any thread.
 *
 * <p>Heap usage is platform-agnostic, so this lives in {@code rtp-core} rather than a per-platform
 * binding. No {@code org.bukkit.*} or platform imports.
 */
public final class HeapSampler {

    private HeapSampler() {}

    /** Bytes of heap currently in use. Returns {@code 0} if the JVM does not expose the bean. */
    public static long heapUsedBytes() {
        try {
            MemoryUsage u = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            long used = u.getUsed();
            return used < 0L ? 0L : used;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * Maximum heap the JVM is allowed to grow to. Returns {@code 0} if the JVM reports
     * "undefined" (-1) or if the bean is unavailable.
     */
    public static long heapMaxBytes() {
        try {
            MemoryUsage u = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            long max = u.getMax();
            return max < 0L ? 0L : max;
        } catch (Throwable ignored) {
            return 0L;
        }
    }
}
