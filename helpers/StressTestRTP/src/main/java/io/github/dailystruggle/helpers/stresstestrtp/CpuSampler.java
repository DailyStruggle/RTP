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
 *
 * <p><b>Thread-group breakdown.</b> {@link #sampleBreakdown()} splits process
 * CPU by thread name ({@link Group}), Bukkit async work by the plugin Paper
 * writes into the worker's name, and leaves the rest as a residual: GC, JIT
 * and VM threads are not Java threads and are invisible to
 * {@link ThreadMXBean}. Accumulated from per-thread deltas, so pooled threads
 * that come and go mid-phase keep their CPU; a thread that dies between two
 * samples loses only its last interval, which lands in the residual.
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

    /** Samples the breakdown every {@code periodMs} off the tick thread; a
     *  shorter period loses less CPU from threads that exit between samples.
     *  {@code <= 0} leaves sampling to the phase boundaries and partial flushes. */
    public synchronized void startBreakdownTimer(org.bukkit.plugin.Plugin plugin, long periodMs) {
        sampleBreakdown(); // prime: lifetime CPU before this point is never billed
        if (periodMs <= 0 || breakdownTimer != null) return;
        breakdownTimer = Sched.runAsyncTimer(plugin, this::sampleBreakdown, periodMs);
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
        // maxDepth 0: names only, no stack capture and no safepoint per thread.
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
            if (cpu < 0) continue; // died between enumeration and read
            seen.add(id);
            if (id > maxSeenThreadId) maxSeenThreadId = id;
            Long prev = lastThreadCpu.put(id, cpu);
            long delta;
            if (prev != null) delta = Math.max(0L, cpu - prev);
            // Thread ids are allocated monotonically: an unseen id above the
            // previous maximum was born after the last sample, so all of its
            // CPU is new. Anything else predates the accumulator.
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

    /** Groups a thread by name. Patterns cover CraftBukkit/Paper/Folia and
     *  vanilla names; misses land in OTHER, whose top names are written per
     *  phase so a new platform's naming is visible in the first run. */
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

    /** Plugin a Craft scheduler worker is running for. Paper renames the
     *  worker to {@code "Craft Scheduler Thread - <n> - <plugin>"} while a
     *  task runs; a bare {@code "... - <n>"} is an idle pooled worker. The
     *  name is read at sample time, so an interval in which a worker switched
     *  plugins is charged to the later one. */
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

    /** Collapses per-instance numbering so pooled threads share one key. */
    static String normalizeName(String name) {
        if (name == null || name.isEmpty()) return "(unnamed)";
        return DIGITS.matcher(name).replaceAll("#");
    }

    /** Cumulative breakdown totals at one sample (ns). Immutable. */
    public static final class Breakdown {
        public final boolean available;
        public final long processNs;
        /** -1 when the JVM exposes no GC CPU counter. */
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

        /** Process CPU not seen on any sampled Java thread: GC, JIT and VM
         *  threads plus the unsampled tail of threads that exited. -1 when
         *  process CPU is unavailable. */
        public long nonJavaNs() {
            return processNs < 0 ? -1L : Math.max(0L, processNs - javaThreadsNs());
        }

        /** Window from {@code start} to this sample. {@code threads} is this
         *  sample's count; {@code samples} the number taken inside the window. */
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

        /** {@code key=ms;...} by descending CPU, at most {@code limit} entries
         *  (0 = all), sub-millisecond entries dropped. */
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
