package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cross-platform scheduling shim.
 *
 * <p>This helper plugin compiles only against the Spigot API and must run
 * unchanged on Spigot, Paper, and Folia. Folia rejects {@code BukkitScheduler}
 * for any work that touches a region-pinned entity; Spigot/Paper reject
 * Folia's {@code GlobalRegionScheduler}/{@code RegionScheduler} because those
 * classes don't exist there.
 *
 * <p>We resolve the right target reflectively at plugin enable, then route:
 * <ul>
 *   <li>{@link #runAsync(Plugin, Runnable)} - TPS / MSPT / heap sampling and
 *       CSV writes. Always async.</li>
 *   <li>{@link #runOnPlayer(Plugin, Player, Runnable)} - anything that must
 *       run on the thread that owns the player (command dispatch attributed
 *       to the player). On Folia this targets the player's {@code EntityScheduler}.
 *       On Spigot/Paper it falls back to the main thread.</li>
 *   <li>{@link #runGlobal(Plugin, Runnable)} - server-global state (no
 *       specific entity). On Folia this is the {@code GlobalRegionScheduler};
 *       on Spigot/Paper it is the main thread.</li>
 * </ul>
 *
 * <p>All reflective method handles are resolved once and cached, preferably on
 * the <em>public</em> Paper scheduler interface: Folia's concrete scheduler and
 * task classes (e.g. {@code EntityScheduledTask}) are not public, so invoking
 * through {@code obj.getClass().getMethod(..)} throws {@link IllegalAccessException}.
 * There is intentionally no fallback to {@code .get()} on a future - see the
 * helpers/ contract note in {@code build.gradle} and rules S-005 / Folia threading
 * in {@code .junie/AGENTS.md}.
 *
 * <p>Every repeating task is returned as a {@link TimerHandle}: a stop flag checked
 * first in the task body, a single cancel attempt, an optional fire bound, and
 * scheduled/cancelled counters, so a failed cancel can neither leak silently nor
 * keep running real work. A failed cancel is counted and logged once.
 */
public final class Sched {

    static final String ASYNC_SCHEDULER = "io.papermc.paper.threadedregions.scheduler.AsyncScheduler";
    static final String GLOBAL_SCHEDULER = "io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler";
    static final String REGION_SCHEDULER = "io.papermc.paper.threadedregions.scheduler.RegionScheduler";
    static final String ENTITY_SCHEDULER = "io.papermc.paper.threadedregions.scheduler.EntityScheduler";
    static final String SCHEDULED_TASK = "io.papermc.paper.threadedregions.scheduler.ScheduledTask";
    private static final String BUKKIT_ENTITY = "org.bukkit.entity.Entity";

    /** Upper bound on fires of a per-player repeater when the caller gives none (2 min at 1 tick). */
    public static final long DEFAULT_MAX_FIRES = 2400L;

    private static final AtomicReference<Boolean> FOLIA = new AtomicReference<>();
    private static final Logger LOG = Logger.getLogger("StressTestRTP");
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Method>> METHODS = new ConcurrentHashMap<>();
    private static volatile Method scheduledTaskCancel;
    private static volatile boolean scheduledTaskCancelResolved;

    /** What a repeating task is for; counted separately so the per-attempt watchers can be asserted ~0 between stages
     *  while the long-lived sampler timers stay up for the whole run. */
    public enum Kind { PLAYER, SERVICE }

    private static final AtomicLong[] SCHEDULED = {new AtomicLong(), new AtomicLong()};
    private static final AtomicLong[] CANCELLED = {new AtomicLong(), new AtomicLong()};
    private static final AtomicLong[] CANCEL_FAILURES = {new AtomicLong(), new AtomicLong()};

    /** Counter snapshot for one {@link Kind}. {@code live} = scheduled - cancelled; a task whose cancel failed stays live. */
    public record TimerStats(long scheduled, long cancelled, long cancelFailures) {
        public long live() { return Math.max(0L, scheduled - cancelled); }
        public TimerStats minus(TimerStats before) {
            return new TimerStats(scheduled - before.scheduled, cancelled - before.cancelled,
                    cancelFailures - before.cancelFailures);
        }
    }

    public static TimerStats timerStats(Kind kind) {
        int i = kind.ordinal();
        return new TimerStats(SCHEDULED[i].get(), CANCELLED[i].get(), CANCEL_FAILURES[i].get());
    }

    /** True iff we're on a Folia server (detected by {@code RegionizedServer} class presence). */
    public static boolean isFolia() {
        Boolean cached = FOLIA.get();
        if (cached != null) return cached;
        boolean detected;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            detected = true;
        } catch (ClassNotFoundException e) {
            detected = false;
        }
        FOLIA.compareAndSet(null, detected);
        return detected;
    }

    // --- reflection ----------------------------------------------------------

    /**
     * Resolves {@code name} on the public {@code iface} when {@code target} implements it, else on the target's
     * class; falls back to {@code setAccessible(true)} for a non-public declaring class. Cached per class.
     */
    static Method resolve(Object target, String iface, String name, Class<?>... types)
            throws ReflectiveOperationException {
        Class<?> c = target.getClass();
        ConcurrentHashMap<String, Method> perClass = METHODS.computeIfAbsent(c, k -> new ConcurrentHashMap<>());
        String key = name + '/' + types.length;
        Method cached = perClass.get(key);
        if (cached != null) return cached;
        Method m = null;
        try {
            Class<?> i = Class.forName(iface);
            if (i.isInstance(target)) m = i.getMethod(name, types);
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // not on this server / not declared there: try the concrete class
        }
        if (m == null) m = c.getMethod(name, types);
        if (!java.lang.reflect.Modifier.isPublic(m.getDeclaringClass().getModifiers())) m.setAccessible(true);
        perClass.put(key, m);
        return m;
    }

    private static Object call(Object target, String iface, String name, Class<?>[] types, Object... args)
            throws ReflectiveOperationException {
        return resolve(target, iface, name, types).invoke(target, args);
    }

    private static final Class<?>[] NO_TYPES = {};
    private static final Class<?>[] CONSUMER = {Plugin.class, java.util.function.Consumer.class};

    private static void logOnce(String key, String msg, Throwable t) {
        if (LOGGED.add(key)) LOG.log(Level.WARNING, "[StressTestRTP] Sched: " + msg + " (logged once)", t);
    }

    /** First failure of each scheduler call is logged once, then the caller falls back. Never silent. */
    private static void fellBack(String what, ReflectiveOperationException e) {
        logOnce("fallback:" + what, what + " failed reflectively, falling back to the Bukkit scheduler", e);
    }

    private static Object server(String getter) throws ReflectiveOperationException {
        Object srv = Bukkit.getServer();
        return call(srv, "org.bukkit.Server", getter, NO_TYPES);
    }

    // --- one-shot wrappers ---------------------------------------------------

    /** Run on any non-tick thread. Uses the async scheduler on Folia. */
    public static void runAsync(Plugin plugin, Runnable r) {
        if (isFolia()) {
            try {
                Object asyncSched = server("getAsyncScheduler");
                call(asyncSched, ASYNC_SCHEDULER, "runNow", CONSUMER,
                        plugin, (java.util.function.Consumer<Object>) task -> r.run());
                return;
            } catch (ReflectiveOperationException e) {
                fellBack("runAsync", e);
            }
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, r);
    }

    /** Run on a thread that owns the given player's region. Best effort. */
    public static void runOnPlayer(Plugin plugin, Player player, Runnable r) {
        if (isFolia()) {
            try {
                Object entSched = call(player, BUKKIT_ENTITY, "getScheduler", NO_TYPES);
                call(entSched, ENTITY_SCHEDULER, "run",
                        new Class<?>[]{Plugin.class, java.util.function.Consumer.class, Runnable.class},
                        plugin,
                        (java.util.function.Consumer<Object>) task -> r.run(),
                        (Runnable) () -> { /* retired: player offline */ });
                return;
            } catch (ReflectiveOperationException e) {
                fellBack("runOnPlayer", e);
            }
        }
        Bukkit.getScheduler().runTask(plugin, r);
    }

    /**
     * Repeating task pinned to the thread that owns {@code player}, at a
     * period in <em>ticks</em>. {@code body} returns {@code false} to cancel
     * the repetition. Bounded by {@link #DEFAULT_MAX_FIRES}.
     *
     * @see #runOnPlayerTimer(Plugin, Player, BooleanSupplier, long, long)
     */
    public static void runOnPlayerTimer(Plugin plugin, Player player,
                                        BooleanSupplier body, long periodTicks) {
        runOnPlayerTimer(plugin, player, body, periodTicks, DEFAULT_MAX_FIRES);
    }

    /**
     * Repeating task pinned to the thread that owns {@code player}, at a
     * period in <em>ticks</em>. {@code body} returns {@code false} to cancel
     * the repetition; {@code maxFires} (> 0) is a hard bound after which the
     * task stops itself.
     *
     * <p>Why a pinned repeater rather than a fresh {@link #runOnPlayer} hop
     * per poll: on Folia each hop is queued onto the owning region and only
     * runs at that region's next tick, so a poll issued from the async run
     * loop observes the player one hop late. A repeater already owns the
     * region context, so it sees the post-teleport position on the very tick
     * it lands - which is what the attribution timestamp measures.
     *
     * <p>Self-limiting: the stop flag is read before anything else, cancel is
     * attempted once, and the fire bound ends the task even if cancel fails,
     * so a failed cancel costs at most a few flag reads per tick, never body work.
     */
    public static void runOnPlayerTimer(Plugin plugin, Player player,
                                        BooleanSupplier body, long periodTicks, long maxFires) {
        long period = Math.max(1L, periodTicks);
        if (isFolia()) {
            TimerHandle h = new TimerHandle(Kind.PLAYER, maxFires);
            try {
                Object entSched = call(player, BUKKIT_ENTITY, "getScheduler", NO_TYPES);
                Object task = call(entSched, ENTITY_SCHEDULER, "runAtFixedRate",
                        new Class<?>[]{Plugin.class, java.util.function.Consumer.class, Runnable.class,
                                long.class, long.class},
                        plugin,
                        (java.util.function.Consumer<Object>) t -> h.fire(t, body),
                        (Runnable) h::retired,
                        period, period);
                if (task != null) h.attach(task);
                else h.retired(); // entity already retired: nothing was scheduled
                return;
            } catch (ReflectiveOperationException e) {
                h.abandon();
                fellBack("runOnPlayerTimer", e);
            }
        }
        TimerHandle h = new TimerHandle(Kind.PLAYER, maxFires);
        h.attach(Bukkit.getScheduler().runTaskTimer(plugin, () -> h.fire(null, body), period, period));
    }

    /** Run on the global region (Folia) or main thread (Spigot/Paper). */
    public static void runGlobal(Plugin plugin, Runnable r) {
        if (isFolia()) {
            try {
                Object globalSched = server("getGlobalRegionScheduler");
                call(globalSched, GLOBAL_SCHEDULER, "run", CONSUMER,
                        plugin, (java.util.function.Consumer<Object>) task -> r.run());
                return;
            } catch (ReflectiveOperationException e) {
                fellBack("runGlobal", e);
            }
        }
        Bukkit.getScheduler().runTask(plugin, r);
    }

    // --- service timers ------------------------------------------------------

    /**
     * Schedule {@code r} on the async scheduler at a fixed period in ms.
     *
     * <p>Returns an opaque {@link TimerHandle}; pass it back to {@link #cancel(Object)}.
     *
     * <p>Folia rejects {@code BukkitScheduler#runTaskTimerAsynchronously} with
     * {@link UnsupportedOperationException}, so we must reflectively target the
     * AsyncScheduler there.
     */
    public static Object runAsyncTimer(Plugin plugin, Runnable r, long periodMs) {
        long period = Math.max(1L, periodMs);
        BooleanSupplier body = () -> { r.run(); return true; };
        if (isFolia()) {
            TimerHandle h = new TimerHandle(Kind.SERVICE, 0L);
            try {
                Object asyncSched = server("getAsyncScheduler");
                h.attach(call(asyncSched, ASYNC_SCHEDULER, "runAtFixedRate",
                        new Class<?>[]{Plugin.class, java.util.function.Consumer.class, long.class, long.class,
                                java.util.concurrent.TimeUnit.class},
                        plugin, (java.util.function.Consumer<Object>) t -> h.fire(t, body),
                        period, period, java.util.concurrent.TimeUnit.MILLISECONDS));
                return h;
            } catch (ReflectiveOperationException e) {
                // fall through to BukkitScheduler - likely to throw on Folia,
                // but at least surfaces a useful stack trace.
                h.abandon();
                fellBack("runAsyncTimer", e);
            }
        }
        long ticks = Math.max(1L, periodMs / 50L);
        TimerHandle h = new TimerHandle(Kind.SERVICE, 0L);
        h.attach(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> h.fire(null, body), ticks, ticks));
        return h;
    }

    /**
     * Schedule {@code r} on the global region (Folia) or main thread
     * (Spigot/Paper) at a fixed period in <em>ticks</em>, starting after the
     * same delay. Returns an opaque {@link TimerHandle} for {@link #cancel(Object)}.
     *
     * <p>Used for the wall-clock TPS / MSPT fallback, which must run on a tick
     * thread to measure inter-tick wall deltas. Folia rejects
     * {@code BukkitScheduler#runTaskTimer}, so the global region scheduler is
     * the only tick-aligned timer available there.
     */
    public static Object runGlobalTimer(Plugin plugin, Runnable r, long periodTicks) {
        long period = Math.max(1L, periodTicks);
        BooleanSupplier body = () -> { r.run(); return true; };
        if (isFolia()) {
            TimerHandle h = new TimerHandle(Kind.SERVICE, 0L);
            try {
                Object globalSched = server("getGlobalRegionScheduler");
                h.attach(call(globalSched, GLOBAL_SCHEDULER, "runAtFixedRate",
                        new Class<?>[]{Plugin.class, java.util.function.Consumer.class, long.class, long.class},
                        plugin, (java.util.function.Consumer<Object>) t -> h.fire(t, body), period, period));
                return h;
            } catch (ReflectiveOperationException e) {
                h.abandon();
                fellBack("runGlobalTimer", e);
            }
        }
        TimerHandle h = new TimerHandle(Kind.SERVICE, 0L);
        h.attach(Bukkit.getScheduler().runTaskTimer(plugin, () -> h.fire(null, body), period, period));
        return h;
    }

    /**
     * Run {@code r} on the thread that owns the given chunk. On Folia this is
     * the {@code RegionScheduler}, which is the only thread permitted to touch
     * that chunk's ticket state; on Spigot/Paper it is the main thread.
     *
     * <p>Required by {@link TicketFootprintProbe}: a chunk ticket may only be
     * applied or released from the owning region, and a cross-region call is
     * rejected rather than merely slow.
     */
    public static void runOnRegion(Plugin plugin, org.bukkit.World world, int cx, int cz, Runnable r) {
        if (isFolia()) {
            try {
                Object regionSched = server("getRegionScheduler");
                call(regionSched, REGION_SCHEDULER, "execute",
                        new Class<?>[]{Plugin.class, org.bukkit.World.class, int.class, int.class, Runnable.class},
                        plugin, world, cx, cz, r);
                return;
            } catch (ReflectiveOperationException e) {
                fellBack("runOnRegion", e);
            }
        }
        Bukkit.getScheduler().runTask(plugin, r);
    }

    /**
     * One-shot delayed task on the global region (Folia) or main thread
     * (Spigot/Paper), measured in ticks. Used to let a scheduled chunk
     * operation settle before its effect is read.
     */
    public static void runGlobalLater(Plugin plugin, Runnable r, long delayTicks) {
        long delay = Math.max(1L, delayTicks);
        if (isFolia()) {
            try {
                Object globalSched = server("getGlobalRegionScheduler");
                call(globalSched, GLOBAL_SCHEDULER, "runDelayed",
                        new Class<?>[]{Plugin.class, java.util.function.Consumer.class, long.class},
                        plugin, (java.util.function.Consumer<Object>) task -> r.run(), delay);
                return;
            } catch (ReflectiveOperationException e) {
                fellBack("runGlobalLater", e);
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, r, delay);
    }

    // --- cancel --------------------------------------------------------------

    /**
     * Cancel a handle previously returned by {@link #runAsyncTimer} / {@link #runGlobalTimer}. Null-safe and
     * idempotent for a {@link TimerHandle}. A raw Bukkit task id, {@code BukkitTask} or Folia {@code ScheduledTask}
     * is also accepted. A failed cancel is counted and logged once, never swallowed.
     */
    public static void cancel(Object handle) {
        if (handle == null) return;
        if (handle instanceof TimerHandle h) {
            h.stop();
            return;
        }
        if (handle instanceof Integer id) {
            if (id > 0) Bukkit.getScheduler().cancelTask(id);
            return;
        }
        cancelTask(handle, Kind.SERVICE);
    }

    /**
     * Cancels one Bukkit or Folia task object. {@code cancel()} is resolved on the public
     * {@code ScheduledTask} interface (cached), then on any public interface or superclass of the handle's class, then
     * via {@code setAccessible(true)}.
     *
     * @return true when cancel() was invoked without error
     */
    static boolean cancelTask(Object task, Kind kind) {
        try {
            if (task instanceof org.bukkit.scheduler.BukkitTask bt) {
                bt.cancel();
                return true;
            }
            Method m = scheduledTaskCancel();
            if (m != null && m.getDeclaringClass().isInstance(task)) {
                m.invoke(task);
                return true;
            }
            Method viaPublic = publicCancel(task.getClass());
            if (viaPublic != null) {
                viaPublic.invoke(task);
                return true;
            }
            Method direct = task.getClass().getMethod("cancel");
            direct.setAccessible(true);
            direct.invoke(task);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            CANCEL_FAILURES[kind.ordinal()].incrementAndGet();
            logOnce("cancel", "cancel() failed on " + task.getClass().getName()
                    + "; the repeating task keeps running until its stop flag / fire bound ends it", e);
            return false;
        }
    }

    private static Method scheduledTaskCancel() {
        if (!scheduledTaskCancelResolved) {
            Method m = null;
            try {
                m = Class.forName(SCHEDULED_TASK).getMethod("cancel");
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
                // Spigot/Paper: no ScheduledTask; BukkitTask path applies
            }
            scheduledTaskCancel = m;
            scheduledTaskCancelResolved = true;
        }
        return scheduledTaskCancel;
    }

    /** {@code cancel()} as declared by a public type in c's hierarchy, or null. */
    private static Method publicCancel(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (java.lang.reflect.Modifier.isPublic(k.getModifiers())) {
                try {
                    return k.getMethod("cancel");
                } catch (NoSuchMethodException ignored) {
                    // keep walking
                }
            }
            for (Class<?> i : k.getInterfaces()) {
                if (java.lang.reflect.Modifier.isPublic(i.getModifiers())) {
                    try {
                        return i.getMethod("cancel");
                    } catch (NoSuchMethodException ignored) {
                        // keep walking
                    }
                }
            }
        }
        return null;
    }

    /** Test hook: clears counters and the log-once memory. */
    static void resetForTest() {
        for (int i = 0; i < 2; i++) {
            SCHEDULED[i].set(0L);
            CANCELLED[i].set(0L);
            CANCEL_FAILURES[i].set(0L);
        }
        LOGGED.clear();
    }

    static Logger logger() { return LOG; }

    /**
     * Handle of one repeating task. The stop flag is the first thing the task body reads, cancel is attempted once,
     * and an optional fire bound ends the task by itself, so a failed cancel never turns into per-tick body work.
     */
    public static final class TimerHandle {
        private final Kind kind;
        private final long maxFires;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicBoolean cancelAttempted = new AtomicBoolean();
        private final AtomicLong fires = new AtomicLong();
        private volatile Object task;

        TimerHandle(Kind kind, long maxFires) {
            this.kind = kind;
            this.maxFires = maxFires;
            SCHEDULED[kind.ordinal()].incrementAndGet();
        }

        public boolean isStopped() { return stopped.get(); }

        public long fires() { return fires.get(); }

        /** Binds the scheduler's task object; cancels it right away when stop() came first. */
        void attach(Object t) {
            if (t == null) return;
            task = t;
            if (stopped.get()) cancelUnderlying(t);
        }

        /** One scheduler firing. {@code t} is the scheduler-provided task (Folia) or null. */
        void fire(Object t, BooleanSupplier body) {
            if (stopped.get()) return; // first read: a leaked task does no work
            if (task == null && t != null) {
                task = t;
                if (stopped.get()) return;
            }
            if (maxFires > 0L && fires.incrementAndGet() > maxFires) {
                logOnce("bound:" + kind, "repeating " + kind + " task hit its fire bound (" + maxFires + ")", null);
                stop();
                return;
            }
            if (!body.getAsBoolean()) stop();
        }

        /** Stops the task; the cancel call is made at most once. */
        public void stop() {
            if (!stopped.compareAndSet(false, true)) return;
            Object t = task;
            if (t != null) cancelUnderlying(t);
        }

        /** The scheduler dropped the task itself (entity retired). */
        void retired() {
            if (stopped.compareAndSet(false, true)) {
                cancelAttempted.set(true);
                CANCELLED[kind.ordinal()].incrementAndGet();
            }
        }

        /** Scheduling failed; nothing is running. */
        void abandon() { retired(); }

        private void cancelUnderlying(Object t) {
            if (!cancelAttempted.compareAndSet(false, true)) return;
            if (cancelTask(t, kind)) CANCELLED[kind.ordinal()].incrementAndGet();
        }
    }

    private Sched() {}
}
