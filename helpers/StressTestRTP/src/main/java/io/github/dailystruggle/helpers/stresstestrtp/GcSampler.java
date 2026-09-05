package io.github.dailystruggle.helpers.stresstestrtp;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Locale;

/**
 * Per-phase garbage-collection and tick-thread allocation accounting.
 *
 * <p>Why this exists: the harness already samples heap-used every 50 ms, but a
 * heap curve alone cannot separate memory that is <em>retained</em> from memory
 * that is <em>churned</em>. Two designs can trace the same heap-used line while
 * one keeps its bytes live past the teleport and the other drops them at the
 * next young collection. Collection counts and collection time supply the
 * churn term; {@code getThreadAllocatedBytes} on the tick thread supplies the
 * rate at which the churn is produced on the path that actually costs ticks.
 *
 * <p>Young/old attribution is by collector name against a fixed table - the
 * JVM exposes no generation flag on {@link GarbageCollectorMXBean}. A bean that
 * matches neither list is counted in the totals and in
 * {@code unclassifiedCollections} only, never folded into a split, so an
 * unfamiliar collector cannot silently be read as "no old-gen activity".
 *
 * <p>All reads are JMX counter reads: no allocation per attempt, nothing
 * scheduled, and no tick-thread work. {@link #snapshot()} allocates one small
 * record at a phase boundary, which is off the measured path by construction.
 */
public final class GcSampler {

    /** Collector names reported by HotSpot/OpenJ9 for young (scavenge) collections. */
    private static final String[] YOUNG = {
            "PS Scavenge", "Copy", "ParNew", "G1 Young Generation",
            "ZGC Cycles", "ZGC Minor Cycles", "Shenandoah Cycles",
            "scavenge", "global scavenge"
    };
    /** Collector names reported for old / full collections. */
    private static final String[] OLD = {
            "PS MarkSweep", "MarkSweepCompact", "ConcurrentMarkSweep",
            "G1 Old Generation", "G1 Concurrent GC", "ZGC Major Cycles",
            "Shenandoah Pauses", "global"
    };

    /**
     * Cumulative JVM-lifetime counters. Phase figures are differences of two
     * snapshots; {@code -1} in any field means the JVM declined to report it
     * (JMX returns -1 for unsupported counters) and shall propagate as a
     * not-measured sentinel rather than as a zero delta.
     */
    public record Snapshot(long youngCollections, long youngTimeMs,
                           long oldCollections, long oldTimeMs,
                           long unclassifiedCollections,
                           long totalCollections, long totalTimeMs,
                           long tickThreadAllocatedBytes) {}

    /** Scope label for the allocation column. Folia has no single tick thread,
     *  so the figure there is one region thread's share, not the server's. */
    public static final String SCOPE_MAIN = "MAIN_THREAD";
    public static final String SCOPE_FOLIA = "FOLIA_GLOBAL_REGION_PARTIAL";
    public static final String SCOPE_NONE = "";

    /** {@code com.sun.management.ThreadMXBean#getThreadAllocatedBytes}, present
     *  on HotSpot and OpenJ9 but not in the platform interface. Resolved once;
     *  null when the extension or the feature is unavailable. */
    private final com.sun.management.ThreadMXBean sunThreadBean;
    private final boolean allocationSupported;

    /** Thread whose allocation is charged to "the tick thread". Supplied by
     *  {@link CpuSampler#mainThreadId()} so both columns describe one thread. */
    private volatile long tickThreadId = -1L;

    public GcSampler() {
        ThreadMXBean platform = ManagementFactory.getThreadMXBean();
        this.sunThreadBean = (platform instanceof com.sun.management.ThreadMXBean s) ? s : null;
        boolean ok = false;
        if (sunThreadBean != null) {
            try {
                if (sunThreadBean.isThreadAllocatedMemorySupported()) {
                    if (!sunThreadBean.isThreadAllocatedMemoryEnabled()) {
                        sunThreadBean.setThreadAllocatedMemoryEnabled(true);
                    }
                    ok = sunThreadBean.isThreadAllocatedMemoryEnabled();
                }
            } catch (Throwable ignored) {
                ok = false;
            }
        }
        this.allocationSupported = ok;
    }

    /** Binds the thread whose allocation is reported. Pass
     *  {@link CpuSampler#mainThreadId()} once it has been captured. */
    public void setTickThreadId(long id) {
        this.tickThreadId = id;
    }

    public boolean allocationSupported() {
        return allocationSupported;
    }

    /** Runtime-gated scope label; empty when no allocation figure is produced. */
    public String allocationScope() {
        if (!allocationSupported || tickThreadId <= 0L) return SCOPE_NONE;
        return Sched.isFolia() ? SCOPE_FOLIA : SCOPE_MAIN;
    }

    /** Reads every GC bean plus the tick thread's allocated bytes. */
    public Snapshot snapshot() {
        long youngC = 0L, youngT = 0L, oldC = 0L, oldT = 0L, unC = 0L, totC = 0L, totT = 0L;
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean b : beans) {
            long c = b.getCollectionCount();
            long t = b.getCollectionTime();
            if (c < 0) continue;                 // bean declined to report
            totC += c;
            if (t > 0) totT += t;
            String name = b.getName();
            if (matches(name, YOUNG)) {
                youngC += c;
                if (t > 0) youngT += t;
            } else if (matches(name, OLD)) {
                oldC += c;
                if (t > 0) oldT += t;
            } else {
                unC += c;                        // totals only; never a split
            }
        }
        return new Snapshot(youngC, youngT, oldC, oldT, unC, totC, totT, tickThreadAllocatedBytes());
    }

    /** Allocated bytes charged to the bound tick thread, or {@code -1}. */
    public long tickThreadAllocatedBytes() {
        long id = tickThreadId;
        if (!allocationSupported || id <= 0L || sunThreadBean == null) return -1L;
        try {
            long v = sunThreadBean.getThreadAllocatedBytes(id);
            return v < 0 ? -1L : v;
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static boolean matches(String name, String[] table) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (String candidate : table) {
            if (n.equals(candidate.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** Non-negative delta of two cumulative counters, propagating the
     *  not-measured sentinel when either end is unavailable. */
    public static long delta(long start, long end) {
        if (start < 0 || end < 0) return -1L;
        return Math.max(0L, end - start);
    }
}
