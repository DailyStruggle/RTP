package io.github.dailystruggle.rtp.common.metrics;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.List;

/**
 * Thin platform-portable wrapper over {@link ManagementFactory#getMemoryMXBean()}'s
 * heap usage and generational memory pools. All methods are O(1) and safe to call from any thread.
 *
 * <p>Heap usage is platform-agnostic, so this lives in {@code rtp-core} rather than a per-platform
 * binding. No {@code org.bukkit.*} or platform imports.
 */
public final class HeapSampler {

    private static volatile MemoryPoolMXBean cachedOldGenPool = null;
    private static volatile boolean oldGenPoolSearched = false;

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

    /**
     * Attempts to find the tenured / old generation memory pool (e.g. G1 Old Gen, ZGC Old Gen,
     * Tenured Gen). Caches the result on first discovery.
     */
    public static MemoryPoolMXBean getTenuredPool() {
        if (!oldGenPoolSearched) {
            try {
                List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
                for (MemoryPoolMXBean pool : pools) {
                    if (pool.getType() == MemoryType.HEAP) {
                        String name = pool.getName().toLowerCase();
                        if (name.contains("old") || name.contains("tenured")) {
                            cachedOldGenPool = pool;
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // headless or security manager restricted
            }
            oldGenPoolSearched = true;
        }
        return cachedOldGenPool;
    }

    /**
     * Returns the retained tenured/old generation used bytes, or total heap used bytes
     * if the tenured pool is not available.
     * Prioritizes {@link MemoryPoolMXBean#getCollectionUsage()} (post-GC retained memory)
     * when available, falling back to current pool usage.
     */
    public static long tenuredUsedBytes() {
        MemoryPoolMXBean pool = getTenuredPool();
        if (pool != null) {
            try {
                MemoryUsage col = pool.getCollectionUsage();
                if (col != null && col.getUsed() > 0L) {
                    return col.getUsed();
                }
                MemoryUsage usage = pool.getUsage();
                if (usage != null && usage.getUsed() >= 0L) {
                    return usage.getUsed();
                }
            } catch (Throwable ignored) {
            }
        }
        return heapUsedBytes();
    }

    /**
     * Returns the maximum tenured/old generation capacity, or total heap max bytes
     * if the tenured pool is not available or has an undefined max (e.g. G1 Old Gen).
     *
     * <p>Under G1GC, {@code usage.getMax()} returns -1 (undefined) because G1 dynamically
     * moves 16MB regions between generations. Returning {@code usage.getCommitted()} here
     * would cause false alarms where 90% of a tiny initial committed old-gen pool is mistaken
     * for 90% of the entire JVM heap. When {@code max <= 0}, we fall back to {@link #heapMaxBytes()}.</p>
     */
    public static long tenuredMaxBytes() {
        MemoryPoolMXBean pool = getTenuredPool();
        if (pool != null) {
            try {
                MemoryUsage usage = pool.getUsage();
                if (usage != null) {
                    long max = usage.getMax();
                    if (max > 0L) return max;
                }
            } catch (Throwable ignored) {
            }
        }
        return heapMaxBytes();
    }
}
