package io.github.dailystruggle.helpers.stresstestrtp;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Names the plugin that synchronously requested a chunk load, from the call
 * stack of the {@code ChunkLoadEvent} (ADR-122 Decision 1 & 2).
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

    private static final int MAX_FRAMES = 96;
    private static final int PROBE_MAX_FRAMES = 512;
    private static final StackWalker WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final String NONE = "";

    private final StressHost self;
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
    private volatile boolean syncProbeSeen = false;
    private volatile String syncProbeTrace = null;
    private volatile String asyncProbeRequester = null;
    private volatile boolean asyncProbeSeen = false;
    private volatile boolean residentTicketProbeBlocking = false;

    public SyncLoadAttributor(StressHost self) {
        this.self = self;
        this.selfLoader = self != null ? self.getClass().getClassLoader() : SyncLoadAttributor.class.getClassLoader();
    }

    public SelfTest selfTest() { return selfTest; }

    public boolean trusted() { return selfTest == SelfTest.PASS; }

    public void setSampleStride(int stride) {
        this.sampleStride = Math.max(1, stride);
    }

    public int sampleStride() {
        return this.sampleStride;
    }

    void onLoad(int cx, int cz, boolean onTick) {
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        boolean probe = key == syncProbeKey || key == asyncProbeKey || key == residentTicketProbeKey;
        if (!onTick && !probe) return;

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
            syncProbeSeen = true;
            if (requester == null) syncProbeTrace = describeStack();
        }
        if (key == asyncProbeKey) {
            asyncProbeRequester = requester;
            asyncProbeSeen = true;
        }
        if (key == residentTicketProbeKey) {
            residentTicketProbeBlocking = blocking;
        }
        if (requester == null || probe) return;
        if (self != null && requester.equals(self.getName())) return;

        long weight = stride > 1 ? stride : 1L;
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

    static boolean isTickBoundaryMethod(String method) {
        return "tickServer".equals(method)
                || "runServer".equals(method)
                || "tickChildren".equals(method)
                || "mainThreadHeartbeat".equals(method);
    }

    static boolean isTaskDrainMethod(String method) {
        return "pollTask".equals(method)
                || "executeTask".equals(method)
                || "runAllTasks".equals(method)
                || "pollNextChunkTask".equals(method);
    }

    record Frame(ClassLoader loader, String method) {}

    Attribution requester(int maxFrames) {
        try {
            return WALKER.walk(frames -> attribute(
                    frames.map(f -> new Frame(f.getDeclaringClass().getClassLoader(), f.getMethodName())).iterator(),
                    selfLoader, this::pluginNameOf, maxFrames));
        } catch (Throwable t) {
            return null;
        }
    }

    private String describeStack() {
        try {
            return WALKER.walk(frames -> {
                StringBuilder sb = new StringBuilder();
                int[] n = {0};
                frames.limit(PROBE_MAX_FRAMES).forEach(f -> {
                    ClassLoader cl = f.getDeclaringClass().getClassLoader();
                    String tag;
                    if (cl == selfLoader) {
                        tag = "H";
                    } else if (isJdkLoader(cl)) {
                        tag = "J";
                    } else {
                        String plugin = pluginNameOf(cl);
                        tag = plugin != null ? plugin : "S";
                    }
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

    static Attribution attribute(Iterator<Frame> frames, ClassLoader selfLoader,
                                 Function<ClassLoader, String> pluginOf) {
        return attribute(frames, selfLoader, pluginOf, MAX_FRAMES);
    }

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
        if (cl == null || self == null) return null;
        String cached = loaderNames.computeIfAbsent(cl, loader -> {
            for (StressHost.LoadedPlugin p : self.loadedPlugins()) {
                if (p != null && p.classLoader() == loader) return p.name();
            }
            return NONE;
        });
        return cached.isEmpty() ? null : cached;
    }

    public void resetPhase() {
        sampleIndex.set(0L);
        phaseSyncLoads.set(0L);
        phaseByPlugin.clear();
        phaseInlinePromotions.set(0L);
        phaseInlineByPlugin.clear();
    }

    public long phaseSyncLoads() { return trusted() ? phaseSyncLoads.get() : -1L; }

    public String phaseByPluginSummary() {
        return summarizeMap(phaseByPlugin);
    }

    public long phaseInlinePromotions() { return trusted() ? phaseInlinePromotions.get() : -1L; }

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

    private static final int[] CANDIDATE_CX = {48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256};

    public void runSelfTestLater(long delayTicks) {
        Sched.runGlobalLater(self, () -> {
            List<StressWorld> worlds = self.worlds();
            if (worlds.isEmpty()) { finish(SelfTest.NO_CANDIDATE, "no world"); return; }
            selfTest = SelfTest.RUNNING;
            tryCandidate(worlds.get(0), 0, false);
        }, delayTicks);
    }

    private void tryCandidate(StressWorld world, int idx, boolean syncDone) {
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
                    if (!world.loadChunkSync(cx, cz)) {
                        finish(SelfTest.FAIL_SYNC, "sync load returned false");
                        return;
                    }
                } catch (Throwable t) {
                    finish(SelfTest.FAIL_SYNC, "sync load threw " + t);
                    return;
                }
                release(world, cx, cz);
                if (!self.getName().equals(syncProbeRequester)) {
                    String detail;
                    if (!syncProbeSeen) {
                        detail = "sync load event not observed";
                    } else {
                        detail = "sync load attributed to " + syncProbeRequester
                                + (syncProbeTrace != null ? "; stack " + syncProbeTrace : "");
                    }
                    finish(SelfTest.FAIL_SYNC, detail);
                    return;
                }
                tryCandidate(world, idx + 1, true);
                return;
            }
            asyncProbeKey = key;
            CompletableFuture<Boolean> fut;
            try {
                fut = world.loadChunkAsync(cx, cz, false);
            } catch (Throwable e) {
                finish(SelfTest.FAIL_ASYNC, "async load threw " + e);
                return;
            }
            if (fut == null) {
                finish(SelfTest.NO_ASYNC_API, "World#getChunkAtAsync absent (Spigot)");
                return;
            }
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

    private void testResidentTicket(StressWorld world, int cx, int cz) {
        if (!self.supportsChunkTickets()) {
            finish(SelfTest.PASS, "sync load -> " + syncProbeRequester + ", async load -> none (no ticket API)");
            Sched.runOnRegion(self, world, cx, cz, () -> release(world, cx, cz));
            return;
        }

        Sched.runOnRegion(self, world, cx, cz, () -> {
            long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
            residentTicketProbeKey = key;
            try {
                self.addChunkTicket(world.name(), cx, cz);
                self.removeChunkTicket(world.name(), cx, cz);
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

    private static void release(StressWorld world, int cx, int cz) {
        try {
            world.unloadChunkRequest(cx, cz);
        } catch (Throwable ignored) {
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
