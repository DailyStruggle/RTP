package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Names the plugin that synchronously requested a chunk load, from the call
 * stack of the {@code ChunkLoadEvent}.
 *
 * <p><b>Why.</b> The thread a load event fires on cannot separate a blocking
 * load from an async one: Paper fires every {@code ChunkLoadEvent} on the main
 * thread and Folia on the owning region thread, so "on tick" reads near 100%
 * for every plugin. What does differ is the stack. A synchronous
 * {@code World#getChunkAt} (or a sync teleport that loads its target) drives
 * the load from inside the caller, so the caller's frames sit below the event
 * dispatch. An async load completes from the server's own chunk task loop,
 * with no plugin frame on the stack.
 *
 * <p><b>Rule.</b> Skip the listener prefix at the top of the stack (harness
 * frames plus JDK frames such as the stack walker), up to the first server
 * frame (event dispatch). Below that, the innermost frame whose class was
 * defined by a plugin's class loader names the requester, the harness
 * included: the self-test's own {@code getChunkAt} must name the harness.
 * No plugin frame means a load nobody blocked on.
 *
 * <p><b>Self-test.</b> At startup the harness loads one already-generated,
 * unloaded chunk synchronously and one asynchronously and checks that the
 * rule names itself for the first and nobody for the second. The phase
 * columns are written only after {@link SelfTest#PASS}; otherwise they carry
 * the {@code -1} sentinel and the verdict says why.
 *
 * <p><b>Limit.</b> An async load that happens to complete while the tick
 * thread is blocked inside another plugin's synchronous load is charged to
 * that plugin. That is still a load the tick waited for.
 */
public final class SyncLoadAttributor {

    /** Self-test verdict, written literally to {@code chunks_sync_selftest}. */
    public enum SelfTest { NOT_RUN, RUNNING, PASS, FAIL_SYNC, FAIL_ASYNC, FAIL_TICKET, NO_CANDIDATE, NO_ASYNC_API }

    public static final class Attribution {
        public final String pluginName;
        public final boolean blocking;

        public Attribution(String pluginName, boolean blocking) {
            this.pluginName = pluginName;
            this.blocking = blocking;
        }
    }

    /** Walk bound for ordinary loads; a Paper sync load nests the event about
     *  30-50 frames below the caller (managedBlock task drain + chunk system). */
    private static final int MAX_FRAMES = 96;
    /** Walk bound for the self-test's probe chunks (one-off). */
    private static final int PROBE_MAX_FRAMES = 512;
    private static final StackWalker WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    /** Cache value for a class loader that belongs to no plugin. */
    private static final String NONE = "";

    private final Plugin self;
    private final ClassLoader selfLoader;
    private final Map<ClassLoader, String> loaderNames = new ConcurrentHashMap<>();

    private final AtomicLong sampleIndex = new AtomicLong(0L);
    private volatile int sampleStride = 1;

    private final AtomicLong phaseSyncLoads = new AtomicLong();
    private final Map<String, LongAdder> phaseByPlugin = new ConcurrentHashMap<>();
    private final AtomicLong phaseInlinePromotions = new AtomicLong();
    private final Map<String, LongAdder> phaseInlineByPlugin = new ConcurrentHashMap<>();

    private volatile SelfTest selfTest = SelfTest.NOT_RUN;
    private volatile long syncProbeKey = Long.MIN_VALUE;
    private volatile long asyncProbeKey = Long.MIN_VALUE;
    private volatile long residentTicketProbeKey = Long.MIN_VALUE;
    private volatile String syncProbeRequester = null;
    private volatile boolean syncProbeBlocking = false;
    private volatile boolean syncProbeSeen = false;
    /** Compact probe stack, logged only when the sync probe is misattributed. */
    private volatile String syncProbeTrace = null;
    private volatile String asyncProbeRequester = null;
    private volatile boolean asyncProbeSeen = false;
    private volatile String residentTicketProbeRequester = null;
    private volatile boolean residentTicketProbeBlocking = false;
    private volatile boolean residentTicketProbeSeen = false;

    public SyncLoadAttributor(Plugin self) {
        this.self = self;
        this.selfLoader = self.getClass().getClassLoader();
    }

    public SelfTest selfTest() { return selfTest; }

    /** True once the self-test passed; the phase columns are -1 otherwise. */
    public boolean trusted() { return selfTest == SelfTest.PASS; }

    public void setSampleStride(int stride) {
        this.sampleStride = Math.max(1, stride);
    }

    public int sampleStride() {
        return this.sampleStride;
    }

    /** Called from {@code ChunkLoadCounter#onChunkLoad} for every load. */
    void onLoad(int cx, int cz, boolean onTick) {
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        boolean probe = key == syncProbeKey || key == asyncProbeKey || key == residentTicketProbeKey;
        if (!onTick && !probe) return; // off the tick thread nothing waited on it

        int stride = sampleStride;
        if (!probe && stride > 1) {
            long seq = sampleIndex.getAndIncrement();
            if ((seq % stride) != 0) return;
        }

        Attribution attr = requester(probe ? PROBE_MAX_FRAMES : MAX_FRAMES);
        String requester = attr != null ? attr.pluginName : null;
        boolean blocking = attr != null && attr.blocking;

        if (key == syncProbeKey) {
            syncProbeRequester = requester;
            syncProbeBlocking = blocking;
            syncProbeSeen = true;
            if (requester == null) syncProbeTrace = describeStack();
        }
        if (key == asyncProbeKey) {
            asyncProbeRequester = requester;
            asyncProbeSeen = true;
        }
        if (key == residentTicketProbeKey) {
            residentTicketProbeRequester = requester;
            residentTicketProbeBlocking = blocking;
            residentTicketProbeSeen = true;
        }
        if (requester == null || probe) return;
        if (requester.equals(self.getName())) return; // harness's own loads

        long weight = (!probe && stride > 1) ? stride : 1L;
        if (blocking) {
            phaseSyncLoads.addAndGet(weight);
            phaseByPlugin.computeIfAbsent(requester, k -> new LongAdder()).add(weight);
        } else {
            phaseInlinePromotions.addAndGet(weight);
            phaseInlineByPlugin.computeIfAbsent(requester, k -> new LongAdder()).add(weight);
        }
    }

    static boolean isBlockingMethod(String method) {
        return "syncLoad".equals(method)
                || "getChunkFallback".equals(method)
                || "managedBlock".equals(method)
                || "getChunkAt".equals(method)
                || "loadChunk".equals(method);
    }

    static boolean isServerBoundaryMethod(String method) {
        return isTickBoundaryMethod(method) || isTaskDrainMethod(method);
    }

    /** Outermost server loop frames: nothing past them requested the load. */
    static boolean isTickBoundaryMethod(String method) {
        return "tickServer".equals(method)
                || "runServer".equals(method)
                || "tickChildren".equals(method)
                || "mainThreadHeartbeat".equals(method);
    }

    /** Task-queue drain frames. A blocking wait ({@code managedBlock}) drains
     *  the same queue, so a drain only ends the walk if no blocking frame
     *  lies further out. */
    static boolean isTaskDrainMethod(String method) {
        return "pollTask".equals(method)
                || "executeTask".equals(method)
                || "runAllTasks".equals(method)
                || "pollNextChunkTask".equals(method);
    }

    /** One stack frame as the attribution rule sees it. */
    record Frame(ClassLoader loader, String method) {}

    /** Innermost plugin-owned frame below the harness's listener, and whether a blocking call was present. */
    Attribution requester(int maxFrames) {
        try {
            return WALKER.walk(frames -> attribute(
                    frames.map(f -> new Frame(f.getDeclaringClass().getClassLoader(), f.getMethodName())).iterator(),
                    selfLoader, this::pluginNameOf, maxFrames));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Innermost-first {@code tag:method} list; tag H=harness, J=JDK, S=server, else plugin name. */
    private String describeStack() {
        try {
            return WALKER.walk(frames -> {
                StringBuilder sb = new StringBuilder();
                int[] n = {0};
                frames.limit(PROBE_MAX_FRAMES).forEach(f -> {
                    ClassLoader cl = f.getDeclaringClass().getClassLoader();
                    String plugin = cl == selfLoader || isJdkLoader(cl) ? null : pluginNameOf(cl);
                    String tag = cl == selfLoader ? "H" : isJdkLoader(cl) ? "J"
                            : plugin != null ? plugin : "S";
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(tag).append(':').append(f.getMethodName());
                    n[0]++;
                });
                return "depth=" + n[0] + " [" + sb + "]";
            });
        } catch (Throwable t) {
            return "stack unavailable: " + t;
        }
    }

    /**
     * The attribution rule over innermost-first frames. Harness and JDK frames
     * are skipped only while still in the listener prefix; once a server frame
     * was seen, a harness frame is a requester like any plugin's.
     */
    static Attribution attribute(Iterator<Frame> frames, ClassLoader selfLoader,
                                 Function<ClassLoader, String> pluginOf) {
        return attribute(frames, selfLoader, pluginOf, MAX_FRAMES);
    }

    /**
     * A task-drain frame marks the load as drained by the server; a blocking
     * frame further out clears that (the drain ran inside the caller's wait),
     * and the next plugin frame is the requester. A plugin frame reached while
     * still drained, or a tick boundary, means nobody waited on this load.
     */
    static Attribution attribute(Iterator<Frame> frames, ClassLoader selfLoader,
                                 Function<ClassLoader, String> pluginOf, int maxFrames) {
        boolean inPrefix = true;
        boolean blocking = false;
        boolean drained = false;
        int count = 0;
        while (frames.hasNext() && count++ < maxFrames) {
            Frame f = frames.next();
            ClassLoader cl = f.loader();
            if (inPrefix) {
                if (cl == selfLoader || isJdkLoader(cl)) continue;
                inPrefix = false;
            }
            String method = f.method();
            if (isBlockingMethod(method)) {
                blocking = true;
                drained = false;
            }
            if (cl != null && cl != ClassLoader.getSystemClassLoader()) {
                String plugin = pluginOf.apply(cl);
                if (plugin != null) {
                    return drained ? null : new Attribution(plugin, blocking);
                }
            }
            if (isTickBoundaryMethod(method)) {
                break;
            }
            if (isTaskDrainMethod(method)) {
                drained = true;
            }
        }
        return null;
    }

    private static boolean isJdkLoader(ClassLoader cl) {
        return cl == null || cl == ClassLoader.getPlatformClassLoader();
    }

    private String pluginNameOf(ClassLoader cl) {
        if (cl == null) return null;
        String cached = loaderNames.get(cl);
        if (cached == null) {
            cached = NONE;
            for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                if (p.getClass().getClassLoader() == cl) { cached = p.getName(); break; }
            }
            loaderNames.put(cl, cached);
        }
        return cached.isEmpty() ? null : cached;
    }

    public void resetPhase() {
        sampleIndex.set(0L);
        phaseSyncLoads.set(0L);
        phaseByPlugin.clear();
        phaseInlinePromotions.set(0L);
        phaseInlineByPlugin.clear();
    }

    /** Synchronous blocking loads requested by plugins this phase; -1 unless trusted. */
    public long phaseSyncLoads() { return trusted() ? phaseSyncLoads.get() : -1L; }

    /** {@code name=count;...} sorted by name; empty unless trusted. */
    public String phaseByPluginSummary() {
        return summarizeMap(phaseByPlugin);
    }

    /** Inline promotions (e.g. ticket promotions without blocking) requested by plugins this phase; -1 unless trusted. */
    public long phaseInlinePromotions() { return trusted() ? phaseInlinePromotions.get() : -1L; }

    /** Inline promotions {@code name=count;...} sorted by name; empty unless trusted. */
    public String phaseInlineByPluginSummary() {
        return summarizeMap(phaseInlineByPlugin);
    }

    private String summarizeMap(Map<String, LongAdder> map) {
        if (!trusted()) return "";
        Map<String, Long> sorted = new TreeMap<>();
        map.forEach((k, v) -> sorted.put(k, v.sum()));
        StringBuilder sb = new StringBuilder();
        sorted.forEach((k, v) -> {
            if (sb.length() > 0) sb.append(';');
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    // ------------------------------------------------------------------ self-test

    /** Candidate chunk X offsets along +Z=0; far enough to be unloaded at
     *  startup, close enough to be inside a pregenerated border. */
    private static final int[] CANDIDATE_CX = {48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256};

    /** Starts the self-test after {@code delayTicks}; never blocks a caller. */
    public void runSelfTestLater(long delayTicks) {
        Sched.runGlobalLater(self, () -> {
            if (Bukkit.getWorlds().isEmpty()) { finish(SelfTest.NO_CANDIDATE, "no world"); return; }
            selfTest = SelfTest.RUNNING;
            tryCandidate(Bukkit.getWorlds().get(0), 0, false);
        }, delayTicks);
    }

    /** Walks the candidates on each one's owning thread; the first suitable
     *  one is loaded synchronously, the next one asynchronously. */
    private void tryCandidate(World world, int idx, boolean syncDone) {
        if (idx >= CANDIDATE_CX.length) {
            finish(SelfTest.NO_CANDIDATE, "no generated, unloaded chunk among the candidates");
            return;
        }
        int cx = CANDIDATE_CX[idx], cz = 0;
        Sched.runOnRegion(self, world, cx, cz, () -> {
            boolean usable;
            try {
                usable = !world.isChunkLoaded(cx, cz) && world.isChunkGenerated(cx, cz);
            } catch (Throwable t) {
                usable = false;
            }
            if (!usable) { tryCandidate(world, idx + 1, syncDone); return; }
            long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
            if (!syncDone) {
                syncProbeKey = key;
                try {
                    world.getChunkAt(cx, cz); // deliberate synchronous load
                } catch (Throwable t) {
                    finish(SelfTest.FAIL_SYNC, "sync load threw " + t);
                    return;
                }
                release(world, cx, cz);
                if (!self.getName().equals(syncProbeRequester)) {
                    finish(SelfTest.FAIL_SYNC, !syncProbeSeen
                            ? "sync load event not observed"
                            : "sync load attributed to " + syncProbeRequester
                                    + (syncProbeTrace != null ? "; stack " + syncProbeTrace : ""));
                    return;
                }
                tryCandidate(world, idx + 1, true);
                return;
            }
            Method async;
            try {
                async = World.class.getMethod("getChunkAtAsync", int.class, int.class);
            } catch (NoSuchMethodException e) {
                finish(SelfTest.NO_ASYNC_API, "World#getChunkAtAsync absent (Spigot)");
                return;
            }
            asyncProbeKey = key;
            try {
                async.invoke(world, cx, cz);
            } catch (ReflectiveOperationException e) {
                finish(SelfTest.FAIL_ASYNC, "async load threw " + e);
                return;
            }
            // The event fires on a later tick; judge it once it had time to.
            Sched.runGlobalLater(self, () -> {
                if (!asyncProbeSeen) {
                    finish(SelfTest.FAIL_ASYNC, "async load event not observed within 10 s");
                    Sched.runOnRegion(self, world, cx, cz, () -> release(world, cx, cz));
                } else if (asyncProbeRequester != null) {
                    finish(SelfTest.FAIL_ASYNC, "async load attributed to " + asyncProbeRequester);
                    Sched.runOnRegion(self, world, cx, cz, () -> release(world, cx, cz));
                } else {
                    testResidentTicket(world, cx, cz);
                }
            }, 200L);
        });
    }

    private void testResidentTicket(World world, int cx, int cz) {
        Method addTicket;
        Method removeTicket;
        try {
            addTicket = World.class.getMethod("addPluginChunkTicket", int.class, int.class, Plugin.class);
            removeTicket = World.class.getMethod("removePluginChunkTicket", int.class, int.class, Plugin.class);
        } catch (NoSuchMethodException e) {
            // Paper ticket API not available (e.g. Spigot)
            finish(SelfTest.PASS, "sync load -> " + syncProbeRequester + ", async load -> none (no ticket API)");
            Sched.runOnRegion(self, world, cx, cz, () -> release(world, cx, cz));
            return;
        }

        Sched.runOnRegion(self, world, cx, cz, () -> {
            long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
            residentTicketProbeKey = key;
            try {
                addTicket.invoke(world, cx, cz, self);
                removeTicket.invoke(world, cx, cz, self);
            } catch (Throwable t) {
                finish(SelfTest.FAIL_TICKET, "resident ticket promotion threw " + t);
                release(world, cx, cz);
                return;
            }
            release(world, cx, cz);
            if (residentTicketProbeBlocking) {
                finish(SelfTest.FAIL_TICKET, "resident ticket promotion attributed as blocking sync load");
            } else {
                finish(SelfTest.PASS, "sync load -> " + syncProbeRequester + ", async load -> none, resident ticket -> non-blocking");
            }
        });
    }

    private static void release(World world, int cx, int cz) {
        try {
            world.unloadChunkRequest(cx, cz);
        } catch (Throwable ignored) {
            // Folia / newer APIs may reject it; the chunk then unloads normally.
        }
    }

    private void finish(SelfTest verdict, String detail) {
        selfTest = verdict;
        syncProbeKey = Long.MIN_VALUE;
        asyncProbeKey = Long.MIN_VALUE;
        residentTicketProbeKey = Long.MIN_VALUE;
        Level level = verdict == SelfTest.PASS ? Level.INFO : Level.WARNING;
        self.getLogger().log(level, "[StressTestRTP] sync-load attribution self-test " + verdict
                + " (" + detail + ")" + (verdict == SelfTest.PASS ? ""
                : "; chunks_sync_* columns will be -1 for this run"));
    }
}
