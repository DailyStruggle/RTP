package io.github.dailystruggle.helpers.stresstestrtp;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cross-platform scheduling facade in {@code stresstest-core} routing through
 * {@link StressHost} while preserving all timer-lifecycle accounting, fire
 * bounds, and reflective cancellation guarantees (ADR-122 Decision 1 & 2).
 */
public final class Sched {

    static final String SCHEDULED_TASK = "io.papermc.paper.threadedregions.scheduler.ScheduledTask";

    /** Upper bound on fires of a per-player repeater when the caller gives none (2 min at 1 tick). */
    public static final long DEFAULT_MAX_FIRES = 2400L;

    private static final AtomicReference<Boolean> FOLIA = new AtomicReference<>();
    private static volatile StressHost boundHost;
    private static final Logger LOG = Logger.getLogger("StressTestRTP");
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
    private static volatile Method scheduledTaskCancel;
    private static volatile boolean scheduledTaskCancelResolved;

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

    public static void bind(StressHost host) {
        boundHost = host;
    }

    public static StressHost boundHost() {
        return boundHost;
    }

    /** True iff we're on a Folia server. */
    public static boolean isFolia() {
        StressHost h = boundHost;
        if (h != null) return h.isFolia();
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

    private static void logOnce(String key, String msg, Throwable t) {
        if (LOGGED.add(key)) LOG.log(Level.WARNING, t, () -> "[StressTestRTP] Sched: " + msg + " (logged once)");
    }

    public static void runAsync(StressHost plugin, Runnable r) {
        if (plugin != null) {
            plugin.runAsync(r);
        } else {
            r.run();
        }
    }

    public static void runOnPlayer(StressHost plugin, StressPlayer player, Runnable r) {
        if (plugin != null && player != null) {
            plugin.runOnPlayer(player.uuid(), r);
        }
    }

    public static void runOnPlayerTimer(StressHost plugin, StressPlayer player,
                                        BooleanSupplier body, long periodTicks) {
        runOnPlayerTimer(plugin, player, body, periodTicks, DEFAULT_MAX_FIRES);
    }

    public static void runOnPlayerTimer(StressHost plugin, StressPlayer player,
                                        BooleanSupplier body, long periodTicks, long maxFires) {
        if (plugin == null || player == null) return;
        long period = Math.max(1L, periodTicks);
        TimerHandle h = new TimerHandle(Kind.PLAYER, maxFires);
        Object raw = plugin.runOnPlayerTimer(player.uuid(), () -> h.fire(null, body), period, period);
        if (raw == null) {
            h.retired();
        } else {
            h.attach(raw);
        }
    }

    public static void runGlobal(StressHost plugin, Runnable r) {
        if (plugin != null) {
            plugin.runGlobal(r);
        } else {
            r.run();
        }
    }

    public static Object runAsyncTimer(StressHost plugin, Runnable r, long periodMs) {
        if (plugin == null) return null;
        long period = Math.max(1L, periodMs);
        BooleanSupplier body = () -> { r.run(); return true; };
        TimerHandle h = new TimerHandle(Kind.SERVICE, 0L);
        Object raw = plugin.runAsyncTimer(() -> h.fire(null, body), period, period);
        if (raw == null) {
            h.abandon();
        } else {
            h.attach(raw);
        }
        return h;
    }

    public static Object runGlobalTimer(StressHost plugin, Runnable r, long periodTicks) {
        if (plugin == null) return null;
        long period = Math.max(1L, periodTicks);
        BooleanSupplier body = () -> { r.run(); return true; };
        TimerHandle h = new TimerHandle(Kind.SERVICE, 0L);
        Object raw = plugin.runGlobalTimer(() -> h.fire(null, body), period, period);
        if (raw == null) {
            h.abandon();
        } else {
            h.attach(raw);
        }
        return h;
    }

    public static void runOnRegion(StressHost plugin, StressWorld world, int cx, int cz, Runnable r) {
        if (plugin != null) {
            plugin.runOnRegion(world != null ? world.name() : "", cx, cz, r);
        }
    }

    public static void runGlobalLater(StressHost plugin, Runnable r, long delayTicks) {
        if (plugin != null) {
            plugin.runGlobalLater(r, Math.max(1L, delayTicks));
        }
    }

    public static Object runAsyncLater(StressHost plugin, Runnable r, long delayMs) {
        return plugin != null ? plugin.runAsyncLater(r, Math.max(1L, delayMs)) : null;
    }

    public static void cancel(Object handle) {
        if (handle == null) return;
        if (handle instanceof TimerHandle h) {
            h.stop();
            return;
        }
        cancelTask(handle, Kind.SERVICE);
    }

    static boolean cancelTask(Object task, Kind kind) {
        try {
            StressHost h = boundHost;
            if (h != null && h.cancelTask(task)) {
                return true;
            }
            if (task instanceof Future<?> f) {
                f.cancel(false);
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
            }
            scheduledTaskCancel = m;
            scheduledTaskCancelResolved = true;
        }
        return scheduledTaskCancel;
    }

    private static Method publicCancel(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (Modifier.isPublic(k.getModifiers())) {
                try {
                    return k.getMethod("cancel");
                } catch (NoSuchMethodException ignored) {
                }
            }
            for (Class<?> i : k.getInterfaces()) {
                if (Modifier.isPublic(i.getModifiers())) {
                    try {
                        return i.getMethod("cancel");
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            }
        }
        return null;
    }

    static void resetForTest() {
        for (int i = 0; i < 2; i++) {
            SCHEDULED[i].set(0L);
            CANCELLED[i].set(0L);
            CANCEL_FAILURES[i].set(0L);
        }
        LOGGED.clear();
    }

    static Logger logger() { return LOG; }

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

        void attach(Object t) {
            if (t == null) return;
            task = t;
            if (stopped.get()) cancelUnderlying(t);
        }

        void fire(Object t, BooleanSupplier body) {
            if (stopped.get()) return;
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

        public void stop() {
            if (!stopped.compareAndSet(false, true)) return;
            Object t = task;
            if (t != null) cancelUnderlying(t);
        }

        void retired() {
            if (stopped.compareAndSet(false, true)) {
                cancelAttempted.set(true);
                CANCELLED[kind.ordinal()].incrementAndGet();
            }
        }

        void abandon() { retired(); }

        private void cancelUnderlying(Object t) {
            if (!cancelAttempted.compareAndSet(false, true)) return;
            if (cancelTask(t, kind)) CANCELLED[kind.ordinal()].incrementAndGet();
        }
    }

    private Sched() {}
}
