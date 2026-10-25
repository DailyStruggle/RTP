package io.github.dailystruggle.helpers.stresstestrtp;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Snapshots process and main-thread CPU time at phase boundaries.
 */
public final class CpuSampler {

    /** Java-thread groups, in phases-CSV column order. */
    public enum Group { SERVER, REGION, SCHEDULER, ASYNC_SCHEDULER, CHUNK_SYSTEM, NETWORK, OTHER }

    static final String CRAFT_SCHEDULER_PREFIX = "Craft Scheduler Thread";
    private static final java.util.regex.Pattern DIGITS = java.util.regex.Pattern.compile("\\d+");
    /** Bounds the per-name maps; overflow is folded into one key. */
    static final int MAX_NAME_KEYS = 256;
    static final String OVERFLOW_KEY = "(more)";
    static final String IDLE_KEY = "(idle)";

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

    /** Breakdown accumulator state; guarded by {@code this}. */
    private final java.util.Map<Long, Long> lastThreadCpu = new java.util.HashMap<>();
    private final long[] groupAccumNs = new long[Group.values().length];
    private final java.util.Map<String, Long> pluginAccumNs = new java.util.HashMap<>();
    private final java.util.Map<String, Long> otherAccumNs = new java.util.HashMap<>();
    private long maxSeenThreadId = -1L;
    private long breakdownSamples = 0L;
    private int lastThreadCount = 0;
    private Object breakdownTimer;
    /** {@code MemoryMXBean#getTotalGcCpuTime} (JDK 26+), or null. */
    private final java.lang.reflect.Method gcCpuMethod = resolveGcCpuMethod();

    public CpuSampler() {
        java.lang.management.OperatingSystemMXBean raw =
                ManagementFactory.getOperatingSystemMXBean();
        this.osBean = (raw instanceof OperatingSystemMXBean s) ? s : null;
        this.threadBean = ManagementFactory.getThreadMXBean();
        if (threadBean.isThreadCpuTimeSupported() && !threadBean.isThreadCpuTimeEnabled()) {
            try { threadBean.setThreadCpuTimeEnabled(true); } catch (SecurityException ignored) {}
        }
    }

    /**
     * Records the id of whatever thread invokes this method as the main
     * (tick) thread. Call from a one-shot sync task at startup.
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
            if (cpu < 0) continue;
            seen.add(id);
            Long prev = lastRegionCpu.put(id, cpu);
            if (prev != null && cpu > prev) regionCpuAccumNs += cpu - prev;
        }
        lastRegionCpu.keySet().retainAll(seen);
        regionThreadCount = seen.size();
        return seen.isEmpty() ? -1L : regionCpuAccumNs;
    }

    static boolean isRegionThreadName(String name) {
        return name != null && name.contains(FOLIA_REGION_THREAD_MARKER);
    }

    /** Samples the breakdown every {@code periodMs} off the tick thread; a
     *  shorter period loses less CPU from threads that exit between samples.
     *  {@code <= 0} leaves sampling to the phase boundaries and partial flushes. */
    public synchronized void startBreakdownTimer(StressHost host, long periodMs) {
        sampleBreakdown(); // prime: lifetime CPU before this point is never billed
        if (periodMs <= 0 || breakdownTimer != null) return;
        breakdownTimer = Sched.runAsyncTimer(host, this::sampleBreakdown, periodMs);
    }

    public synchronized void stopBreakdownTimer() {
        Sched.cancel(breakdownTimer);
        breakdownTimer = null;
    }

    /**
     * Folds every live Java thread's CPU since the previous sample into the
     * group accumulators and returns the cumulative totals. Totals only grow,
     * so a phase is {@code end.minus(start)}.
     */
    public synchronized Breakdown sampleBreakdown() {
        long processNs = processCpuTimeNs();
        long gcNs = gcCpuTimeNs();
        if (!threadBean.isThreadCpuTimeSupported()) {
            return new Breakdown(false, processNs, gcNs, new long[groupAccumNs.length],
                    java.util.Map.of(), java.util.Map.of(), 0, breakdownSamples);
        }
        boolean primed = breakdownSamples > 0;
        long bornAfter = maxSeenThreadId;
        long main = mainThreadId;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (java.lang.management.ThreadInfo info
                : threadBean.getThreadInfo(threadBean.getAllThreadIds(), 0)) {
            if (info == null) continue;
            long id = info.getThreadId();
            long cpu;
            try {
                cpu = threadBean.getThreadCpuTime(id);
            } catch (Throwable ignored) {
                continue;
            }
            if (cpu < 0) continue;
            seen.add(id);
            if (id > maxSeenThreadId) maxSeenThreadId = id;
            Long prev = lastThreadCpu.put(id, cpu);
            long delta;
            if (prev != null) delta = Math.max(0L, cpu - prev);
            else if (primed && id > bornAfter) delta = cpu;
            else delta = 0L;
            if (delta == 0L) continue;
            String name = info.getThreadName();
            Group g = classify(name, id == main);
            groupAccumNs[g.ordinal()] += delta;
            if (g == Group.SCHEDULER) addCapped(pluginAccumNs, schedulerPlugin(name), delta);
            else if (g == Group.OTHER) addCapped(otherAccumNs, normalizeName(name), delta);
        }
        lastThreadCpu.keySet().retainAll(seen);
        lastThreadCount = seen.size();
        breakdownSamples++;
        return new Breakdown(true, processNs, gcNs, groupAccumNs.clone(),
                java.util.Map.copyOf(pluginAccumNs), java.util.Map.copyOf(otherAccumNs),
                lastThreadCount, breakdownSamples);
    }

    /** Total GC-thread CPU in ns (JDK 26+ {@code MemoryMXBean}), or {@code -1}. */
    long gcCpuTimeNs() {
        if (gcCpuMethod == null) return -1L;
        try {
            Object v = gcCpuMethod.invoke(ManagementFactory.getMemoryMXBean());
            return v instanceof Long l ? l : -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static java.lang.reflect.Method resolveGcCpuMethod() {
        try {
            return java.lang.management.MemoryMXBean.class.getMethod("getTotalGcCpuTime");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void addCapped(java.util.Map<String, Long> m, String key, long delta) {
        String k = (m.containsKey(key) || m.size() < MAX_NAME_KEYS) ? key : OVERFLOW_KEY;
        m.merge(k, delta, Long::sum);
    }

    static Group classify(String name, boolean isMainThread) {
        if (isMainThread) return Group.SERVER;
        if (name == null) return Group.OTHER;
        if (name.equals("Server thread")) return Group.SERVER;
        if (isRegionThreadName(name)) return Group.REGION;
        if (name.startsWith(CRAFT_SCHEDULER_PREFIX)) return Group.SCHEDULER;
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.contains("async scheduler") || n.contains("async task")) return Group.ASYNC_SCHEDULER;
        if (n.contains("chunk") || n.startsWith("worker-main") || n.startsWith("io-worker")
                || n.contains("region file") || n.contains("i/o")
                || n.contains("common worker")) return Group.CHUNK_SYSTEM;
        if (n.contains("netty") || n.contains("epoll") || n.contains("server io")) return Group.NETWORK;
        return Group.OTHER;
    }

    static String schedulerPlugin(String name) {
        if (name == null || !name.startsWith(CRAFT_SCHEDULER_PREFIX)) return IDLE_KEY;
        String rest = name.substring(CRAFT_SCHEDULER_PREFIX.length()).trim();
        if (rest.startsWith("-")) rest = rest.substring(1).trim();
        int i = 0;
        while (i < rest.length() && Character.isDigit(rest.charAt(i))) i++;
        rest = rest.substring(i).trim();
        if (rest.startsWith("-")) rest = rest.substring(1).trim();
        return rest.isEmpty() ? IDLE_KEY : rest;
    }

    static String normalizeName(String name) {
        if (name == null || name.isEmpty()) return "(unnamed)";
        return DIGITS.matcher(name).replaceAll("#");
    }

    /** Cumulative breakdown totals at one sample (ns). Immutable. */
    public static final class Breakdown {
        public final boolean available;
        public final long processNs;
        public final long gcNs;
        private final long[] groupNs;
        public final java.util.Map<String, Long> schedulerByPluginNs;
        public final java.util.Map<String, Long> otherByNameNs;
        public final int threads;
        public final long samples;

        Breakdown(boolean available, long processNs, long gcNs, long[] groupNs,
                  java.util.Map<String, Long> schedulerByPluginNs,
                  java.util.Map<String, Long> otherByNameNs, int threads, long samples) {
            this.available = available;
            this.processNs = processNs;
            this.gcNs = gcNs;
            this.groupNs = groupNs;
            this.schedulerByPluginNs = schedulerByPluginNs;
            this.otherByNameNs = otherByNameNs;
            this.threads = threads;
            this.samples = samples;
        }

        public long groupNs(Group g) { return groupNs[g.ordinal()]; }

        public long javaThreadsNs() {
            long s = 0L;
            for (long v : groupNs) s += v;
            return s;
        }

        public long nonJavaNs() {
            return processNs < 0 ? -1L : Math.max(0L, processNs - javaThreadsNs());
        }

        public Breakdown minus(Breakdown start) {
            long[] g = new long[groupNs.length];
            for (int i = 0; i < g.length; i++) g[i] = Math.max(0L, groupNs[i] - start.groupNs[i]);
            long proc = (processNs >= 0 && start.processNs >= 0)
                    ? Math.max(0L, processNs - start.processNs) : -1L;
            long gc = (gcNs >= 0 && start.gcNs >= 0) ? Math.max(0L, gcNs - start.gcNs) : -1L;
            return new Breakdown(available && start.available, proc, gc, g,
                    diff(schedulerByPluginNs, start.schedulerByPluginNs),
                    diff(otherByNameNs, start.otherByNameNs),
                    threads, Math.max(0L, samples - start.samples));
        }

        private static java.util.Map<String, Long> diff(java.util.Map<String, Long> end,
                                                        java.util.Map<String, Long> start) {
            java.util.Map<String, Long> out = new java.util.HashMap<>();
            for (var e : end.entrySet()) {
                long d = e.getValue() - start.getOrDefault(e.getKey(), 0L);
                if (d > 0) out.put(e.getKey(), d);
            }
            return out;
        }

        public static String summary(java.util.Map<String, Long> m, int limit) {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (var e : m.entrySet().stream()
                    .sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed())
                    .toList()) {
                long ms = e.getValue() / 1_000_000L;
                if (ms <= 0) break;
                if (limit > 0 && n++ >= limit) break;
                if (sb.length() > 0) sb.append(';');
                sb.append(e.getKey().replace(';', '_').replace('=', '_')).append('=').append(ms);
            }
            return sb.toString();
        }
    }
}
