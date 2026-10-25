package io.github.dailystruggle.helpers.stresstestrtp;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Plugin-agnostic retention check for player churn (ADR-122 Decision 1, 2, 4, 5).
 *
 * <p>No class is named here: which classes leak is decided offline by screening
 * every histogram class against a no-RTP-plugin control and against departures,
 * so the platform's own retention is subtracted, not assumed to be zero, and the
 * same probe runs unchanged against every arm on Bukkit and Fabric.
 */
public final class LeakProbe {

    public static final String CHURN_KICK_REASON = "StressTestRTP churn";

    static final String CSV_HEADER = "epoch_ms,label,online,joins_total,quits_total,distinct_uuids,"
            + "heap_used_after_gc_mb,old_gen_after_gc_mb,histogram_ms,histogram_file";

    static final String METRICS_HEADER = "epoch_ms,label,metric,value";

    private static final Pattern HISTO_LINE = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)");
    private static final Pattern NMT_LINE = Pattern.compile(
            "^-\\s++([^(\\s][^(]*+)\\(reserved=(\\d++)KB,\\s*+committed=(\\d++)KB");
    private static final Pattern NMT_TOTAL = Pattern.compile(
            "^Total:\\s+reserved=(\\d+)KB,\\s*committed=(\\d+)KB");
    private static final Pattern PROC_STATUS_KB = Pattern.compile("^(\\w+):\\s+(\\d+)\\s+kB");
    private static final Pattern PROC_STATUS_N = Pattern.compile("^(Threads):\\s+(\\d+)");
    private static final Pattern LINE_SPLIT = Pattern.compile("\\R");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern METRIC_NON_ALPHANUM = Pattern.compile("[^a-z0-9#_-]+");
    private static final Pattern METRIC_UNDERSCORES = Pattern.compile("(?:^_+)|(?:_+$)");
    private static final Pattern FILE_SAFE = Pattern.compile("[^A-Za-z0-9._-]");
    private static final DateTimeFormatter STAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
                    .withZone(ZoneId.systemDefault());

    public record ClassCount(String name, long instances, long bytes) {}

    public enum Section { JVM_POOLS, WORKLOAD, PROC, NMT, THREAD_COUNTS, THREAD_CENSUS, THREAD_DUMP, PLUGINS, HEAP_DUMP }

    public record Options(boolean heapDump, boolean quick) {
        public static final Options FULL = new Options(false, false);
        public static final Options QUICK = new Options(false, true);

        public Set<Section> sections() {
            java.util.EnumSet<Section> s = quick
                    ? java.util.EnumSet.of(Section.JVM_POOLS, Section.WORKLOAD, Section.PROC,
                            Section.THREAD_COUNTS, Section.PLUGINS)
                    : java.util.EnumSet.complementOf(java.util.EnumSet.of(Section.HEAP_DUMP));
            if (heapDump) s.add(Section.HEAP_DUMP);
            return s;
        }
    }

    public record Checkpoint(long epochMs, String label, int online, long joins, long quits, int distinctUuids,
                             double heapAfterGcMb, double oldGenAfterGcMb, long histogramMs, String histogramFile) {
        String csvRow() {
            return String.join(",",
                    Long.toString(epochMs), csvSafe(label), Integer.toString(online),
                    Long.toString(joins), Long.toString(quits), Integer.toString(distinctUuids),
                    fmt(heapAfterGcMb), fmt(oldGenAfterGcMb), Long.toString(histogramMs),
                    csvSafe(histogramFile));
        }

        String describe() {
            return String.format(Locale.ROOT,
                    "leakcheck %s: online=%d joins=%d quits=%d distinct=%d "
                            + "heapAfterGC=%sMB oldGen=%sMB (gc+histogram %dms)",
                    label, online, joins, quits, distinctUuids,
                    fmt(heapAfterGcMb), fmt(oldGenAfterGcMb), histogramMs);
        }
    }

    private final StressHost plugin;
    private final Path runsDir;
    private final String autoOpPrefix;
    private final AtomicLong joins = new AtomicLong();
    private final AtomicLong quits = new AtomicLong();
    private final Set<UUID> distinct = ConcurrentHashMap.newKeySet();
    private final Set<UUID> autoOpped = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile String lastResult = "leakcheck: none yet";
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong teleports = new AtomicLong();
    private final AtomicLong chunkLoads = new AtomicLong();
    private final AtomicLong chunkNew = new AtomicLong();
    private volatile HarnessTeleporter teleporter;
    private final List<Runnable> unsubscribes = new ArrayList<>();

    public LeakProbe(StressHost plugin, Path runsDir, String autoOpPrefix) {
        this.plugin = plugin;
        this.runsDir = runsDir;
        this.autoOpPrefix = autoOpPrefix == null ? "" : autoOpPrefix;
    }

    public void register() {
        if (plugin == null) return;
        for (StressPlayer p : plugin.onlinePlayers()) {
            if (p != null) distinct.add(p.uuid());
        }
        unsubscribes.add(plugin.onPlayerJoin(this::onJoin));
        unsubscribes.add(plugin.onPlayerQuit(this::onQuit));
        unsubscribes.add(plugin.onPlayerTeleport(this::onTeleport));
        unsubscribes.add(plugin.onChunkLoad(this::onChunkLoad));
    }

    public void unregister() {
        for (Runnable u : unsubscribes) {
            try { u.run(); } catch (Throwable ignored) {}
        }
        unsubscribes.clear();
    }

    public void setTeleporter(HarnessTeleporter t) {
        this.teleporter = t;
    }

    public void onTeleport(StressHost.TeleportEvent e) {
        teleports.incrementAndGet();
    }

    public void onChunkLoad(StressHost.ChunkEvent e) {
        chunkLoads.incrementAndGet();
        if (e != null && e.newChunk()) chunkNew.incrementAndGet();
    }

    public void onJoin(StressPlayer p) {
        if (p == null) return;
        joins.incrementAndGet();
        distinct.add(p.uuid());
        if (!autoOpPrefix.isEmpty() && p.name() != null && p.name().startsWith(autoOpPrefix) && !p.isOp()) {
            autoOpped.add(p.uuid());
            p.setOp(true);
        }
    }

    public void onQuit(StressPlayer p) {
        if (p == null) return;
        quits.incrementAndGet();
        if (autoOpped.remove(p.uuid())) p.setOp(false);
    }

    public int churn(int n, String namePrefix) {
        if (plugin == null) return 0;
        List<StressPlayer> pick = plugin.onlinePlayers().stream()
                .filter(p -> p != null && (namePrefix == null || namePrefix.isEmpty()
                        || (p.name() != null && p.name().startsWith(namePrefix))))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Collections.shuffle(pick);
        int limit = n <= 0 ? pick.size() : Math.min(n, pick.size());
        for (int i = 0; i < limit; i++) {
            StressPlayer p = pick.get(i);
            Sched.runOnPlayer(plugin, p, () -> {
                if (p.isOnline()) p.kick(CHURN_KICK_REASON);
            });
        }
        return limit;
    }

    public boolean busy() {
        return busy.get();
    }

    public String status() {
        return "leakcheck busy=" + busy.get() + " completed=" + completed.get() + " | " + lastResult;
    }

    /**
     * Formats a human-readable census status line for listeners, pending tasks, and
     * chunk tickets on the given host (reporting {@link StressHost#NOT_MEASURED}
     * when a census is unsupported, never {@code 0} or empty; ADR-122 Decision 2).
     */
    public static String describeHostCensuses(StressHost host) {
        if (host == null) {
            return "listeners=" + StressHost.NOT_MEASURED
                    + " pending_tasks=" + StressHost.NOT_MEASURED
                    + " chunk_tickets=" + StressHost.NOT_MEASURED;
        }
        return "listeners=" + formatCensus(host.listenerCensus())
                + " pending_tasks=" + formatCensus(host.pendingTaskCensus())
                + " chunk_tickets=" + formatCensus(host.chunkTicketCensus());
    }

    static String formatCensus(StressHost.Census census) {
        if (census == null || !census.measured()) {
            return StressHost.NOT_MEASURED;
        }
        return census.counts().toString();
    }

    public boolean checkpoint(String label, Consumer<String> reply) {
        return checkpoint(label, Options.FULL, reply);
    }

    public boolean checkpoint(String label, boolean heapDump, Consumer<String> reply) {
        return checkpoint(label, new Options(heapDump, false), reply);
    }

    public boolean checkpoint(String label, Options options, Consumer<String> reply) {
        final Options opts = options == null ? Options.FULL : options;
        if (!busy.compareAndSet(false, true)) return false;
        final String safeLabel = (label == null || label.isBlank()) ? "checkpoint" : label.trim();
        Sched.runAsync(plugin, () -> {
            try {
                Checkpoint c = take(safeLabel, opts);
                appendCsv(c);
                plugin.getLogger().info("[StressTestRTP] " + c.describe());
                lastResult = c.describe();
                reply.accept(c.describe());
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, t, () -> "leakcheck " + safeLabel + " failed");
                lastResult = "leakcheck " + safeLabel + " failed: " + t.getClass().getSimpleName()
                        + ": " + t.getMessage();
                reply.accept(lastResult);
            } finally {
                completed.incrementAndGet();
                busy.set(false);
            }
        });
        return true;
    }

    private Checkpoint take(String label, Options opts) throws Exception {
        Set<Section> sections = opts.sections();
        long t0 = System.currentTimeMillis();
        String histo = classHistogram();
        long histoMs = System.currentTimeMillis() - t0;
        double heapMb = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1048576.0;
        double oldMb = oldGenAfterGcMb();
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("checkpoint_quick", opts.quick() ? 1.0 : 0.0);
        m.put("histogram_ms", (double) histoMs);
        m.put("heap_used_after_gc_mb", heapMb);
        if (sections.contains(Section.JVM_POOLS)) jvmMetrics(m);
        int online = plugin != null ? plugin.onlinePlayers().size() : 0;
        List<ClassCount> counts = parseHistogram(histo);
        String stamp = STAMP_FORMATTER.format(Instant.ofEpochMilli(t0));
        String base = "leak-" + stamp + "-" + fileSafe(label);
        String file = base + ".histo.txt";
        Files.createDirectories(runsDir);
        Files.writeString(runsDir.resolve(file), histo, StandardCharsets.UTF_8);

        if (sections.contains(Section.WORKLOAD)) {
            m.put("uptime_s", ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0);
            m.put("teleports_total", (double) teleports.get());
            m.put("chunk_loads_total", (double) chunkLoads.get());
            m.put("chunk_generated_total", (double) chunkNew.get());
            m.put("joins_total", (double) joins.get());
            m.put("quits_total", (double) quits.get());
            m.put("histogram_live_mb", sumBytes(counts) / 1048576.0);
            HarnessTeleporter t = teleporter;
            if (t != null) {
                m.put("direct_tp_issued_total", (double) t.issued());
                m.put("direct_tp_failed_total", (double) t.failed());
            }
        }
        if (sections.contains(Section.PROC)) procMetrics(m);
        if (sections.contains(Section.NMT)) {
            nativeMemory(m, runsDir.resolve(base + ".nmt.txt"));
            Double rss = m.get("rss_mb");
            Double nmt = m.get("nmt.total.committed_mb");
            if (rss != null && nmt != null) m.put("rss_minus_nmt_mb", rss - nmt);
        }
        if (sections.contains(Section.THREAD_COUNTS)) threadCounts(m);
        if (sections.contains(Section.THREAD_CENSUS)) threadCensus(m);
        if (sections.contains(Section.THREAD_DUMP)) threadDump(runsDir.resolve(base + ".threads.txt"));
        if (sections.contains(Section.PLUGINS)) pluginMetrics(m, counts);
        if (sections.contains(Section.HEAP_DUMP)) {
            long d0 = System.currentTimeMillis();
            Path hprof = runsDir.resolve(base + ".hprof");
            Files.deleteIfExists(hprof);
            ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class)
                    .dumpHeap(hprof.toAbsolutePath().toString(), true);
            m.put("heap_dump_ms", (double) (System.currentTimeMillis() - d0));
            m.put("heap_dump_mb", Files.size(hprof) / 1048576.0);
        }
        appendMetrics(t0, label, m);
        return new Checkpoint(t0, label, online, joins.get(), quits.get(), distinct.size(),
                heapMb, oldMb, histoMs, file);
    }

    private static void jvmMetrics(Map<String, Double> m) {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage u = pool.getUsage();
            if (u == null) continue;
            String k = "pool." + metricSafe(pool.getName());
            m.put(k + ".used_mb", u.getUsed() / 1048576.0);
            m.put(k + ".committed_mb", u.getCommitted() / 1048576.0);
        }
        for (BufferPoolMXBean b : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            String k = "buffer." + metricSafe(b.getName());
            m.put(k + ".count", (double) b.getCount());
            m.put(k + ".used_mb", b.getMemoryUsed() / 1048576.0);
        }
        ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();
        m.put("classes_loaded", (double) cl.getLoadedClassCount());
        m.put("classes_loaded_total", (double) cl.getTotalLoadedClassCount());
        m.put("classes_unloaded_total", (double) cl.getUnloadedClassCount());
    }

    private static void procMetrics(Map<String, Double> m) {
        Path status = Path.of("/proc/self/status");
        if (Files.isReadable(status)) {
            try {
                m.putAll(parseProcStatus(Files.readString(status, StandardCharsets.UTF_8)));
            } catch (IOException ignored) { }
        }
        Path fd = Path.of("/proc/self/fd");
        if (Files.isDirectory(fd)) {
            try (var s = Files.list(fd)) {
                m.put("fd_open", (double) s.count());
            } catch (IOException ignored) { }
        }
    }

    static Map<String, Double> parseProcStatus(String text) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String line : LINE_SPLIT.split(text)) {
            Matcher kb = PROC_STATUS_KB.matcher(line);
            if (kb.find()) {
                String k = switch (kb.group(1)) {
                    case "VmRSS" -> "rss_mb";
                    case "VmHWM" -> "rss_peak_mb";
                    case "VmSwap" -> "swap_mb";
                    case "RssAnon" -> "rss_anon_mb";
                    case "RssFile" -> "rss_file_mb";
                    default -> null;
                };
                if (k != null) out.put(k, Long.parseLong(kb.group(2)) / 1024.0);
                continue;
            }
            Matcher n = PROC_STATUS_N.matcher(line);
            if (n.find()) out.put("os_threads", Double.parseDouble(n.group(2)));
        }
        return out;
    }

    private static void nativeMemory(Map<String, Double> m, Path file) {
        String out;
        try {
            out = diagnosticCommand("vmNativeMemory", "summary");
        } catch (Exception e) {
            m.put("nmt_enabled", 0.0);
            return;
        }
        Map<String, Double> parsed = parseNmt(out);
        m.put("nmt_enabled", parsed.isEmpty() ? 0.0 : 1.0);
        if (parsed.isEmpty()) return;
        m.putAll(parsed);
        try {
            Files.writeString(file, out, StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    static Map<String, Double> parseNmt(String text) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String raw : LINE_SPLIT.split(text)) {
            String line = raw.trim();
            Matcher t = NMT_TOTAL.matcher(line);
            if (t.find()) {
                out.put("nmt.total.committed_mb", Long.parseLong(t.group(2)) / 1024.0);
                continue;
            }
            Matcher c = NMT_LINE.matcher(line);
            if (c.find()) {
                out.put("nmt." + metricSafe(c.group(1)) + ".committed_mb", Long.parseLong(c.group(3)) / 1024.0);
            }
        }
        return out;
    }

    private static void threadCounts(Map<String, Double> m) {
        ThreadMXBean tm = ManagementFactory.getThreadMXBean();
        m.put("threads_live", (double) tm.getThreadCount());
        m.put("threads_daemon", (double) tm.getDaemonThreadCount());
        m.put("threads_peak", (double) tm.getPeakThreadCount());
        m.put("threads_started_total", (double) tm.getTotalStartedThreadCount());
    }

    private static void threadCensus(Map<String, Double> m) {
        ThreadMXBean tm = ManagementFactory.getThreadMXBean();
        Map<String, Integer> groups = new TreeMap<>();
        for (ThreadInfo ti : tm.dumpAllThreads(false, false)) {
            if (ti == null) continue;
            groups.merge(threadGroup(ti.getThreadName()) + "." + ti.getThreadState().name(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : groups.entrySet()) {
            m.put("threadgroup." + e.getKey(), (double) e.getValue());
        }
    }

    private static void threadDump(Path file) {
        try {
            Files.writeString(file, diagnosticCommand("threadPrint"), StandardCharsets.UTF_8);
        } catch (Exception ignored) { }
    }

    static String threadGroup(String name) {
        if (name == null || name.isBlank()) return "unnamed";
        return metricSafe(DIGITS.matcher(name).replaceAll("#"));
    }

    void pluginMetrics(Map<String, Double> m, List<ClassCount> counts) {
        if (plugin == null) return;
        StressHost.Census taskCensus = plugin.pendingTaskCensus();
        m.put("plugin_tasks_measured", taskCensus.measured() ? 1.0 : 0.0);
        StressHost.Census listenerCensus = plugin.listenerCensus();
        m.put("plugin_listeners_measured", listenerCensus.measured() ? 1.0 : 0.0);
        List<StressHost.LoadedPlugin> plugins = plugin.loadedPlugins();
        Map<String, String> owners = ownerIndexFromLoaded(plugins);
        Map<String, long[]> owned = sumByOwner(counts, owners);
        for (StressHost.LoadedPlugin p : plugins) {
            if (p == null) continue;
            String k = "plugin." + metricSafe(p.name());
            if (taskCensus.measured()) {
                m.put(k + ".pending_tasks", (double) taskCensus.counts().getOrDefault(p.name(), 0));
            }
            if (listenerCensus.measured()) {
                m.put(k + ".listeners", (double) listenerCensus.counts().getOrDefault(p.name(), 0));
            }
            Set<String> jar = jarClassCache.get(p.name());
            long[] live = jar != null && !jar.isEmpty()
                    ? owned.getOrDefault(p.name(), new long[]{0L, 0L})
                    : sumPackage(counts, p.fallbackPackage());
            m.put(k + ".owned_classes", jar != null && !jar.isEmpty() ? (double) jar.size() : -1.0);
            m.put(k + ".live_instances", (double) live[0]);
            m.put(k + ".live_mb", live[1] / 1048576.0);
        }
    }

    private final Map<String, Set<String>> jarClassCache = new ConcurrentHashMap<>();

    private Map<String, String> ownerIndexFromLoaded(List<StressHost.LoadedPlugin> plugins) {
        Map<String, Set<String>> byPlugin = new LinkedHashMap<>();
        for (StressHost.LoadedPlugin p : plugins) {
            if (p == null) continue;
            Set<String> names = jarClassCache.computeIfAbsent(p.name(), n -> jarClasses(p));
            byPlugin.put(p.name(), names);
        }
        return ownerIndex(byPlugin);
    }

    static Map<String, String> ownerIndex(Map<String, Set<String>> byPlugin) {
        Map<String, String> out = new java.util.HashMap<>();
        Set<String> ambiguous = new java.util.HashSet<>();
        for (Map.Entry<String, Set<String>> e : byPlugin.entrySet()) {
            for (String cls : e.getValue()) {
                String prev = out.putIfAbsent(cls, e.getKey());
                if (prev != null && !prev.equals(e.getKey())) ambiguous.add(cls);
            }
        }
        out.keySet().removeAll(ambiguous);
        return out;
    }

    static Set<String> jarClasses(StressHost.LoadedPlugin p) {
        if (p == null) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (Path origin : p.originPaths()) {
            if (origin == null) continue;
            if (Files.isRegularFile(origin)) {
                try (ZipFile z = new ZipFile(origin.toFile())) {
                    z.stream().forEach(en -> {
                        String c = jarEntryClass(en.getName());
                        if (c != null) out.add(c);
                    });
                } catch (Exception ignored) {
                }
            } else if (Files.isDirectory(origin)) {
                try (var walk = Files.walk(origin)) {
                    walk.filter(Files::isRegularFile).forEach(f -> {
                        String rel = origin.relativize(f).toString().replace('\\', '/');
                        String c = jarEntryClass(rel);
                        if (c != null) out.add(c);
                    });
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    static String jarEntryClass(String entry) {
        if (entry == null || !entry.endsWith(".class")) return null;
        String e = entry;
        if (e.startsWith("META-INF/versions/")) {
            int s = e.indexOf('/', "META-INF/versions/".length());
            if (s < 0) return null;
            e = e.substring(s + 1);
        } else if (e.startsWith("META-INF/")) {
            return null;
        }
        if (e.endsWith("module-info.class") || e.endsWith("package-info.class")) return null;
        return e.substring(0, e.length() - ".class".length()).replace('/', '.');
    }

    static String histogramClass(String n) {
        int i = 0;
        while (i < n.length() && n.charAt(i) == '[') i++;
        String s = n.substring(i);
        if (i > 0) {
            if (!s.startsWith("L")) return null;
            s = s.substring(1);
            if (s.endsWith(";")) s = s.substring(0, s.length() - 1);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int lambda = s.indexOf("$$");
        if (lambda >= 0) s = s.substring(0, lambda);
        return s;
    }

    static Map<String, long[]> sumByOwner(List<ClassCount> counts, Map<String, String> owners) {
        Map<String, long[]> out = new LinkedHashMap<>();
        for (ClassCount c : counts) {
            String cls = histogramClass(c.name());
            String owner = cls == null ? null : owners.get(cls);
            if (owner == null) continue;
            long[] acc = out.computeIfAbsent(owner, k -> new long[]{0L, 0L});
            acc[0] += c.instances();
            acc[1] += c.bytes();
        }
        return out;
    }

    static long[] sumPackage(List<ClassCount> counts, String pkg) {
        long[] out = {0L, 0L};
        if (pkg == null || pkg.isEmpty()) return out;
        String prefix = pkg + ".";
        for (ClassCount c : counts) {
            String n = c.name();
            int i = 0;
            while (i < n.length() && n.charAt(i) == '[') i++;
            if (i > 0 && i < n.length() && n.charAt(i) == 'L') i++;
            if (n.startsWith(prefix, i)) {
                out[0] += c.instances();
                out[1] += c.bytes();
            }
        }
        return out;
    }

    static long sumBytes(List<ClassCount> counts) {
        long s = 0;
        for (ClassCount c : counts) {
            if (!c.name().contains("jdk.internal.vm.Filler")) s += c.bytes();
        }
        return s;
    }

    private static String diagnosticCommand(String op, String... args) throws JMException {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName("com.sun.management:type=DiagnosticCommand");
        Object out = server.invoke(name, op, new Object[]{args}, new String[]{String[].class.getName()});
        return out == null ? "" : out.toString();
    }

    static String metricSafe(String s) {
        String s1 = METRIC_NON_ALPHANUM.matcher(s.trim().toLowerCase(Locale.ROOT)).replaceAll("_");
        return METRIC_UNDERSCORES.matcher(s1).replaceAll("");
    }

    static List<String> metricRows(long epochMs, String label, Map<String, Double> m) {
        List<String> rows = new ArrayList<>(m.size());
        for (Map.Entry<String, Double> e : m.entrySet()) {
            double v = e.getValue();
            String val = v == Math.rint(v) && Math.abs(v) < 1e15
                    ? Long.toString((long) v) : String.format(Locale.ROOT, "%.3f", v);
            rows.add(epochMs + "," + csvSafe(label) + "," + csvSafe(e.getKey()) + "," + val);
        }
        return rows;
    }

    private void appendMetrics(long epochMs, String label, Map<String, Double> m) throws IOException {
        Path csv = runsDir.resolve("leak-metrics.csv");
        if (!Files.exists(csv)) {
            Files.writeString(csv, METRICS_HEADER + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE);
        }
        Files.writeString(csv, String.join("\n", metricRows(epochMs, label, m)) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    static String classHistogram() throws JMException {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName("com.sun.management:type=DiagnosticCommand");
        Object out = server.invoke(name, "gcClassHistogram",
                new Object[]{new String[0]}, new String[]{String[].class.getName()});
        return out == null ? "" : out.toString();
    }

    private static double oldGenAfterGcMb() {
        double total = -1;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            String n = pool.getName().toLowerCase(Locale.ROOT);
            if (!(n.contains("old gen") || n.contains("tenured"))) continue;
            MemoryUsage u = pool.getCollectionUsage();
            if (u == null) continue;
            total = Math.max(0, total) + u.getUsed() / 1048576.0;
        }
        return total;
    }

    public static List<ClassCount> parseHistogram(String histo) {
        List<ClassCount> out = new ArrayList<>();
        if (histo == null) return out;
        for (String line : LINE_SPLIT.split(histo)) {
            Matcher m = HISTO_LINE.matcher(line);
            if (m.find()) {
                out.add(new ClassCount(m.group(3), Long.parseLong(m.group(1)), Long.parseLong(m.group(2))));
            }
        }
        return out;
    }

    private void appendCsv(Checkpoint c) throws IOException {
        Path csv = runsDir.resolve("leak.csv");
        Files.createDirectories(runsDir);
        if (!Files.exists(csv)) {
            Files.writeString(csv, CSV_HEADER + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE);
        }
        Files.writeString(csv, c.csvRow() + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private static String fmt(double d) {
        return d < 0 ? "-1" : String.format(Locale.ROOT, "%.1f", d);
    }

    private static String csvSafe(String s) {
        return s == null ? "" : s.replace(',', ';').replace('\n', ' ');
    }

    static String fileSafe(String s) {
        return FILE_SAFE.matcher(s).replaceAll("_");
    }
}
