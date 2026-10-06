package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
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
 * <p><b>Rule.</b> Skip the harness's own listener frames at the top of the
 * stack, then the innermost frame whose class was defined by a plugin's class
 * loader names the requester. No plugin frame means a load nobody blocked on.
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
    public enum SelfTest { NOT_RUN, RUNNING, PASS, FAIL_SYNC, FAIL_ASYNC, NO_CANDIDATE, NO_ASYNC_API }

    private static final int MAX_FRAMES = 256;
    private static final StackWalker WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    /** Cache value for a class loader that belongs to no plugin. */
    private static final String NONE = "";

    private final Plugin self;
    private final ClassLoader selfLoader;
    private final Map<ClassLoader, String> loaderNames = new ConcurrentHashMap<>();

    private final AtomicLong phaseSyncLoads = new AtomicLong();
    private final Map<String, LongAdder> phaseByPlugin = new ConcurrentHashMap<>();

    private volatile SelfTest selfTest = SelfTest.NOT_RUN;
    private volatile long syncProbeKey = Long.MIN_VALUE;
    private volatile long asyncProbeKey = Long.MIN_VALUE;
    private volatile String syncProbeRequester = null;
    private volatile String asyncProbeRequester = null;
    private volatile boolean asyncProbeSeen = false;

    public SyncLoadAttributor(Plugin self) {
        this.self = self;
        this.selfLoader = self.getClass().getClassLoader();
    }

    public SelfTest selfTest() { return selfTest; }

    /** True once the self-test passed; the phase columns are -1 otherwise. */
    public boolean trusted() { return selfTest == SelfTest.PASS; }

    /** Called from {@code ChunkLoadCounter#onChunkLoad} for every load. */
    void onLoad(int cx, int cz, boolean onTick) {
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        boolean probe = key == syncProbeKey || key == asyncProbeKey;
        if (!onTick && !probe) return; // off the tick thread nothing waited on it
        String requester = requester();
        if (key == syncProbeKey) syncProbeRequester = requester;
        if (key == asyncProbeKey) { asyncProbeRequester = requester; asyncProbeSeen = true; }
        if (requester == null || probe) return;
        if (requester.equals(self.getName())) return; // harness's own loads
        phaseSyncLoads.incrementAndGet();
        phaseByPlugin.computeIfAbsent(requester, k -> new LongAdder()).increment();
    }

    /** Innermost plugin-owned frame below the harness's listener, or null. */
    String requester() {
        try {
            return WALKER.walk(s -> s.limit(MAX_FRAMES)
                    .map(f -> f.getDeclaringClass().getClassLoader())
                    .dropWhile(cl -> cl == selfLoader)
                    .map(this::pluginNameOf)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null));
        } catch (Throwable t) {
            return null;
        }
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
        phaseSyncLoads.set(0L);
        phaseByPlugin.clear();
    }

    /** Synchronous loads requested by plugins this phase; -1 unless trusted. */
    public long phaseSyncLoads() { return trusted() ? phaseSyncLoads.get() : -1L; }

    /** {@code name=count;...} sorted by name; empty unless trusted. */
    public String phaseByPluginSummary() {
        if (!trusted()) return "";
        Map<String, Long> sorted = new TreeMap<>();
        phaseByPlugin.forEach((k, v) -> sorted.put(k, v.sum()));
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
                    finish(SelfTest.FAIL_SYNC, "sync load attributed to " + syncProbeRequester);
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
                } else if (asyncProbeRequester != null) {
                    finish(SelfTest.FAIL_ASYNC, "async load attributed to " + asyncProbeRequester);
                } else {
                    finish(SelfTest.PASS, "sync load -> " + syncProbeRequester + ", async load -> none");
                }
                Sched.runOnRegion(self, world, cx, cz, () -> release(world, cx, cz));
            }, 200L);
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
        Level level = verdict == SelfTest.PASS ? Level.INFO : Level.WARNING;
        self.getLogger().log(level, "[StressTestRTP] sync-load attribution self-test " + verdict
                + " (" + detail + ")" + (verdict == SelfTest.PASS ? ""
                : "; chunks_sync_* columns will be -1 for this run"));
    }
}
