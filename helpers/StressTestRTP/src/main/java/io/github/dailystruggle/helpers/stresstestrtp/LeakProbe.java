package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.scheduler.BukkitTask;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plugin-agnostic retention check for player churn.
 *
 * <p>Why: a heap-used curve cannot tell a leak from GC sawtooth, and reading a
 * competitor's private maps would make the proof plugin-specific. The class
 * histogram from the {@code DiagnosticCommand} MBean runs a full GC first, so
 * every instance it counts is strongly reachable. After every bot has
 * quit and rejoined, live server-side player objects ({@code CraftPlayer},
 * {@code ServerPlayer}) above the online count are sessions something still
 * references. Repeating this per churn cycle turns "is there a leak" into a
 * slope: retained player objects and post-GC heap per departed session. The
 * same probe runs unchanged against every arm (LeafRTP included) and against
 * a no-RTP-plugin control, so the platform's own retention is subtracted, not
 * assumed to be zero.
 *
 * <p>Churn accounts for identity two ways: same-name rejoin (same offline
 * UUID, new {@code Player} object) exposes {@code Player}-keyed retention;
 * renamed rejoin ({@code bench-swarm.js --churn-rename}) additionally exposes
 * UUID/name-keyed retention. Renamed bots are not in {@code ops.json}, so the
 * probe can op them on join and deop them on quit ({@code leak.auto-op-prefix});
 * the op list therefore returns to its starting size and cannot itself
 * masquerade as growth.
 *
 * <p>A checkpoint pauses the whole JVM for one full GC. It is refused while a
 * run is measuring unless forced; it never runs on the tick thread.
 */
public final class LeakProbe implements Listener {

    /** Kick reason marker; bench-swarm keys {@code --churn-rename} on it. */
    public static final String CHURN_KICK_REASON = "StressTestRTP churn";

    /** Live-instance class suffixes for one server-side player session.
     *  Suffix match covers versioned CraftBukkit packages and both Mojang
     *  ({@code ServerPlayer}) and Spigot ({@code EntityPlayer}) mappings. */
    static final String[] CRAFT_PLAYER = {".entity.CraftPlayer"};
    static final String[] SERVER_PLAYER = {
            "net.minecraft.server.level.ServerPlayer",
            "net.minecraft.server.level.EntityPlayer"};
    static final String[] CONNECTION = {
            "net.minecraft.server.network.ServerGamePacketListenerImpl",
            "net.minecraft.server.network.PlayerConnection"};

    static final String CSV_HEADER = "epoch_ms,label,online,joins_total,quits_total,distinct_uuids,"
            + "craftplayer_live,serverplayer_live,game_listener_live,retained_player_objects,"
            + "heap_used_after_gc_mb,old_gen_after_gc_mb,histogram_ms,histogram_file";

    /** Long-format sidecar: every checkpoint metric beyond the fixed
     *  {@code leak.csv} columns, so the analysis fits a slope per metric
     *  without a schema change when a metric is added. */
    static final String METRICS_HEADER = "epoch_ms,label,metric,value";

    private static final Pattern HISTO_LINE = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)");
    /** NMT summary category line, e.g. {@code -  Java Heap (reserved=16777216KB, committed=16777216KB)}.
     *  Possessive name group keeps trailing blanks (no backtracking); {@link #metricSafe} trims them. */
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

    /** Per-class live instance and byte totals parsed from one histogram. */
    record ClassCount(String name, long instances, long bytes) {}

    /** Metric groups a checkpoint may collect beyond the GC histogram. */
    enum Section { JVM_POOLS, WORKLOAD, PROC, NMT, THREAD_COUNTS, THREAD_CENSUS, THREAD_DUMP, PLUGINS, HEAP_DUMP }

    /**
     * Checkpoint options. {@code quick} keeps only the exact counts a
     * per-episode delta needs (heap, memory and buffer pools, loaded classes,
     * retained players, workload counters, per-plugin census, thread count,
     * open files) and skips the slow
     * diagnostics: NMT, the thread census and {@code threadPrint} file. The
     * full GC, histogram file and {@code leak.csv} row are always written.
     */
    record Options(boolean heapDump, boolean quick) {
        static final Options FULL = new Options(false, false);
        static final Options QUICK = new Options(false, true);

        Set<Section> sections() {
            java.util.EnumSet<Section> s = quick
                    // JVM_POOLS is JMX-only (no pause) and carries metaspace, direct
                    // buffers and loaded classes: the classloader / reload leak signals.
                    ? java.util.EnumSet.of(Section.JVM_POOLS, Section.WORKLOAD, Section.PROC,
                            Section.THREAD_COUNTS, Section.PLUGINS)
                    : java.util.EnumSet.complementOf(java.util.EnumSet.of(Section.HEAP_DUMP));
            if (heapDump) s.add(Section.HEAP_DUMP);
            return s;
        }
    }

    /** One checkpoint row; {@code -1} marks a value the JVM did not report. */
    record Checkpoint(long epochMs, String label, int online, long joins, long quits, int distinctUuids,
                      long craftPlayers, long serverPlayers, long gameListeners,
                      double heapAfterGcMb, double oldGenAfterGcMb, long histogramMs, String histogramFile) {
        /** Player sessions kept alive beyond those online. The larger of the
         *  two player classes is used: either alone proves retention. */
        long retainedPlayerObjects() {
            long live = Math.max(craftPlayers, serverPlayers);
            return live < 0 ? -1L : Math.max(0L, live - online);
        }

        String csvRow() {
            return String.join(",",
                    Long.toString(epochMs), csvSafe(label), Integer.toString(online),
                    Long.toString(joins), Long.toString(quits), Integer.toString(distinctUuids),
                    Long.toString(craftPlayers), Long.toString(serverPlayers), Long.toString(gameListeners),
                    Long.toString(retainedPlayerObjects()),
                    fmt(heapAfterGcMb), fmt(oldGenAfterGcMb), Long.toString(histogramMs),
                    csvSafe(histogramFile));
        }

        String describe() {
            return String.format(Locale.ROOT,
                    "leakcheck %s: online=%d joins=%d quits=%d distinct=%d craftplayer=%d serverplayer=%d "
                            + "retained=%d heapAfterGC=%sMB oldGen=%sMB (gc+histogram %dms)",
                    label, online, joins, quits, distinctUuids, craftPlayers, serverPlayers,
                    retainedPlayerObjects(), fmt(heapAfterGcMb), fmt(oldGenAfterGcMb), histogramMs);
        }
    }

    private final Plugin plugin;
    private final Path runsDir;
    private final String autoOpPrefix;
    private final AtomicLong joins = new AtomicLong();
    private final AtomicLong quits = new AtomicLong();
    private final Set<UUID> distinct = ConcurrentHashMap.newKeySet();
    /** Names this probe opped; only these are deopped, never a real operator. */
    private final Set<UUID> autoOpped = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    /** Last finished checkpoint's description; polled by RCON drivers, which
     *  cannot wait on the asynchronous reply. */
    private volatile String lastResult = "leakcheck: none yet";
    private final AtomicLong completed = new AtomicLong();
    /** Workload counters: the x-axes a leak may scale with besides departures. */
    private final AtomicLong teleports = new AtomicLong();
    private final AtomicLong chunkLoads = new AtomicLong();
    private final AtomicLong chunkNew = new AtomicLong();
    private volatile HarnessTeleporter teleporter;

    public LeakProbe(Plugin plugin, Path runsDir, String autoOpPrefix) {
        this.plugin = plugin;
        this.runsDir = runsDir;
        this.autoOpPrefix = autoOpPrefix == null ? "" : autoOpPrefix;
    }

    public void register() {
        for (Player p : Bukkit.getOnlinePlayers()) distinct.add(p.getUniqueId());
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    /** Source of the {@code direct_tp_*} counters; null when not created. */
    void setTeleporter(HarnessTeleporter t) {
        this.teleporter = t;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        teleports.incrementAndGet();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        chunkLoads.incrementAndGet();
        if (e.isNewChunk()) chunkNew.incrementAndGet();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        joins.incrementAndGet();
        distinct.add(p.getUniqueId());
        if (!autoOpPrefix.isEmpty() && p.getName().startsWith(autoOpPrefix) && !p.isOp()) {
            autoOpped.add(p.getUniqueId());
            p.setOp(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        quits.incrementAndGet();
        if (autoOpped.remove(p.getUniqueId())) p.setOp(false);
    }

    /**
     * Kicks up to {@code n} online players whose name starts with
     * {@code namePrefix} ({@code n <= 0}: all of them). Each kick runs on the
     * player's owning thread. Returns the number scheduled.
     */
    public int churn(int n, String namePrefix) {
        List<Player> pick = Bukkit.getOnlinePlayers().stream()
                .filter(p -> namePrefix == null || namePrefix.isEmpty() || p.getName().startsWith(namePrefix))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Collections.shuffle(pick);
        int limit = n <= 0 ? pick.size() : Math.min(n, pick.size());
        for (int i = 0; i < limit; i++) {
            Player p = pick.get(i);
            Sched.runOnPlayer(plugin, p, () -> {
                if (p.isOnline()) p.kickPlayer(CHURN_KICK_REASON);
            });
        }
        return limit;
    }

    public boolean busy() {
        return busy.get();
    }

    /** {@code busy=<bool> completed=<n> | <last result>}; stable for scripts. */
    public String status() {
        return "leakcheck busy=" + busy.get() + " completed=" + completed.get() + " | " + lastResult;
    }

    /**
     * Runs one full-GC histogram checkpoint off the tick thread, appends a row
     * to {@code runs/leak.csv}, keeps the full histogram beside it, and passes
     * the one-line description to {@code reply}. Returns false if a checkpoint
     * is already running.
     */
    public boolean checkpoint(String label, Consumer<String> reply) {
        return checkpoint(label, Options.FULL, reply);
    }

    /**
     * As {@link #checkpoint(String, Consumer)}; with {@code heapDump} it also
     * writes a live-objects HPROF ({@code leak-<stamp>-<label>.hprof}) after
     * the histogram, for owner attribution by {@code scripts/heap_owner.py}.
     * The dump is a second stop-the-world pause of several seconds.
     */
    public boolean checkpoint(String label, boolean heapDump, Consumer<String> reply) {
        return checkpoint(label, new Options(heapDump, false), reply);
    }

    /** As {@link #checkpoint(String, Consumer)} with explicit {@link Options}. */
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
        // Read right after the histogram's full GC: the closest JMX gets to
        // retained heap without a second pause.
        double heapMb = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1048576.0;
        double oldMb = oldGenAfterGcMb();
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("checkpoint_quick", opts.quick() ? 1.0 : 0.0);
        m.put("histogram_ms", (double) histoMs);
        m.put("heap_used_after_gc_mb", heapMb);
        if (sections.contains(Section.JVM_POOLS)) jvmMetrics(m);
        int online = Bukkit.getOnlinePlayers().size();
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
            // Native growth the JVM does not account for, such as malloc from native
            // libs or allocator fragmentation, is invisible to both the heap and NMT.
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
                sumInstances(counts, CRAFT_PLAYER, true), sumInstances(counts, SERVER_PLAYER, false),
                sumInstances(counts, CONNECTION, false), heapMb, oldMb, histoMs, file);
    }

    /** Memory pools (read right after the full GC), buffer pools, class loading. */
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

    /** RSS, peak RSS, swap, OS thread count and open descriptors (Linux only;
     *  absent elsewhere, which the analysis reads as NOT MEASURED). */
    private static void procMetrics(Map<String, Double> m) {
        Path status = Path.of("/proc/self/status");
        if (Files.isReadable(status)) {
            try {
                m.putAll(parseProcStatus(Files.readString(status, StandardCharsets.UTF_8)));
            } catch (IOException ignored) { /* not measured */ }
        }
        Path fd = Path.of("/proc/self/fd");
        if (Files.isDirectory(fd)) {
            try (var s = Files.list(fd)) {
                m.put("fd_open", (double) s.count());
            } catch (IOException ignored) { /* not measured */ }
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

    /** {@code VM.native_memory summary}; needs {@code -XX:NativeMemoryTracking=summary}.
     *  Without it the JVM answers with a not-enabled message and only
     *  {@code nmt_enabled=0} is recorded. */
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
        } catch (IOException ignored) { /* the parsed values are already recorded */ }
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

    /** JVM thread counters; no safepoint, unlike a thread dump. */
    private static void threadCounts(Map<String, Double> m) {
        ThreadMXBean tm = ManagementFactory.getThreadMXBean();
        m.put("threads_live", (double) tm.getThreadCount());
        m.put("threads_daemon", (double) tm.getDaemonThreadCount());
        m.put("threads_peak", (double) tm.getPeakThreadCount());
        m.put("threads_started_total", (double) tm.getTotalStartedThreadCount());
    }

    /** Per-name-group thread census. Pool threads carry their owner's name
     *  ("BetterRTP-async-3"), which attributes thread growth without reading
     *  the plugin's code. Needs every thread's stack (a safepoint). */
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

    /** Full {@code threadPrint} beside the histogram. */
    private static void threadDump(Path file) {
        try {
            Files.writeString(file, diagnosticCommand("threadPrint"), StandardCharsets.UTF_8);
        } catch (Exception ignored) { /* census above is still recorded */ }
    }

    /** Thread name with digit runs folded, so pool members share one group. */
    static String threadGroup(String name) {
        if (name == null || name.isBlank()) return "unnamed";
        return metricSafe(DIGITS.matcher(name).replaceAll("#"));
    }

    /**
     * Per plugin: pending scheduler tasks, registered listeners across every
     * handler list, and live objects of classes shipped in the plugin's own jar
     * (main package as fallback when the jar is unreadable, {@code owned_classes=-1};
     * from the histogram already taken). Snapshot reads off the tick thread.
     * Folia has no {@code BukkitScheduler} task list; there
     * {@code plugin_tasks_measured=0}.
     */
    private void pluginMetrics(Map<String, Double> m, List<ClassCount> counts) {
        Map<String, Integer> tasks = new TreeMap<>();
        boolean tasksMeasured = true;
        try {
            for (BukkitTask task : Bukkit.getScheduler().getPendingTasks()) {
                if (task.getOwner() != null) tasks.merge(task.getOwner().getName(), 1, Integer::sum);
            }
        } catch (Throwable t) {
            tasksMeasured = false;
        }
        m.put("plugin_tasks_measured", tasksMeasured ? 1.0 : 0.0);
        Map<String, Integer> listeners = new TreeMap<>();
        try {
            for (HandlerList hl : HandlerList.getHandlerLists()) {
                for (RegisteredListener rl : hl.getRegisteredListeners()) {
                    if (rl.getPlugin() != null) listeners.merge(rl.getPlugin().getName(), 1, Integer::sum);
                }
            }
        } catch (Throwable ignored) { /* partial census is still useful */ }
        Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
        Map<String, String> owners = ownerIndex(plugins);
        Map<String, long[]> owned = sumByOwner(counts, owners);
        for (Plugin p : plugins) {
            String k = "plugin." + metricSafe(p.getName());
            if (tasksMeasured) m.put(k + ".pending_tasks", (double) tasks.getOrDefault(p.getName(), 0));
            m.put(k + ".listeners", (double) listeners.getOrDefault(p.getName(), 0));
            Set<String> jar = jarClassCache.get(p.getName());
            long[] live = jar != null && !jar.isEmpty()
                    ? owned.getOrDefault(p.getName(), new long[]{0L, 0L})
                    : sumPackage(counts, p.getClass().getPackageName());
            m.put(k + ".owned_classes", jar != null && !jar.isEmpty() ? (double) jar.size() : -1.0);
            m.put(k + ".live_instances", (double) live[0]);
            m.put(k + ".live_mb", live[1] / 1048576.0);
        }
    }

    /** plugin name -> class names in its jar; jars do not change while loaded. */
    private final Map<String, Set<String>> jarClassCache = new ConcurrentHashMap<>();

    /** class name -> owning plugin, from the classes each plugin's own jar
     *  contains. Why: a plugin's code spans more than its main class's package
     *  (LeafRTP: core, bundled effects/maps/commands APIs), and a shared
     *  parent package would also swallow other jars (this harness). */
    private Map<String, String> ownerIndex(Plugin[] plugins) {
        Map<String, Set<String>> byPlugin = new LinkedHashMap<>();
        for (Plugin p : plugins) {
            Set<String> names = jarClassCache.computeIfAbsent(p.getName(), n -> jarClasses(p));
            byPlugin.put(p.getName(), names);
        }
        return ownerIndex(byPlugin);
    }

    /** A class shipped unrelocated by several jars cannot be attributed from a
     *  histogram (names only, no loader), so it is owned by none. */
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

    private static Set<String> jarClasses(Plugin p) {
        try {
            var src = p.getClass().getProtectionDomain().getCodeSource();
            if (src == null || src.getLocation() == null) return Set.of();
            Path jar = Path.of(src.getLocation().toURI());
            if (!Files.isRegularFile(jar)) return Set.of();
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar.toFile())) {
                Set<String> out = new java.util.HashSet<>();
                z.stream().forEach(en -> {
                    String c = jarEntryClass(en.getName());
                    if (c != null) out.add(c);
                });
                return out;
            }
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    /** Binary class name for a jar entry, or null (resources, module/package-info).
     *  Multi-release entries map to their base name. */
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

    /** Histogram name to the defining class's binary name: array markers
     *  stripped, hidden/lambda classes folded onto their host class. */
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

    /** plugin -> {instances, bytes} of histogram classes it owns. */
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

    /** {instances, bytes} of histogram classes inside {@code pkg} (arrays of
     *  them included). An empty package would match everything, so it matches nothing. */
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

    /** Live bytes excluding G1 filler arrays/objects, which pad region tails
     *  and track allocation pattern, not retained state. */
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

    /** {@code GC.class_histogram} via JMX; performs a full GC (live objects only). */
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

    static List<ClassCount> parseHistogram(String histo) {
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

    /** Sum of live instances whose class name equals (or, with {@code suffix},
     *  ends with) any of {@code names}; {@code -1} when no class matched, so a
     *  remapped server reads as not-measured rather than as zero retention. */
    static long sumInstances(List<ClassCount> counts, String[] names, boolean suffix) {
        long sum = 0;
        boolean seen = false;
        for (ClassCount c : counts) {
            for (String n : names) {
                if (suffix ? c.name().endsWith(n) : c.name().equals(n)) {
                    sum += c.instances();
                    seen = true;
                    break;
                }
            }
        }
        return seen ? sum : -1L;
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
