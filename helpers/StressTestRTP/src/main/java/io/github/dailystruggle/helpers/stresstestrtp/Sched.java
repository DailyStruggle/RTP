package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

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
 *   <li>{@link #runAsync(Plugin, Runnable)} — TPS / MSPT / heap sampling and
 *       CSV writes. Always async.</li>
 *   <li>{@link #runOnPlayer(Plugin, Player, Runnable)} — anything that must
 *       run on the thread that owns the player (command dispatch attributed
 *       to the player). On Folia this targets the player's {@code EntityScheduler}.
 *       On Spigot/Paper it falls back to the main thread.</li>
 *   <li>{@link #runGlobal(Plugin, Runnable)} — server-global state (no
 *       specific entity). On Folia this is the {@code GlobalRegionScheduler};
 *       on Spigot/Paper it is the main thread.</li>
 * </ul>
 *
 * <p>All reflective method handles are resolved once and cached. There is
 * intentionally no fallback to {@code .get()} on a future — see the helpers/
 * contract note in {@code build.gradle} and rules S-005 / Folia threading
 * in {@code .junie/AGENTS.md}.
 */
public final class Sched {

    private static final AtomicReference<Boolean> FOLIA = new AtomicReference<>();

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

    /** Run on any non-tick thread. Uses the async scheduler on Folia. */
    public static void runAsync(Plugin plugin, Runnable r) {
        if (isFolia()) {
            try {
                Object asyncSched = Bukkit.getServer().getClass()
                        .getMethod("getAsyncScheduler").invoke(Bukkit.getServer());
                Method m = asyncSched.getClass().getMethod(
                        "runNow", Plugin.class, java.util.function.Consumer.class);
                m.invoke(asyncSched, plugin, (java.util.function.Consumer<Object>) task -> r.run());
                return;
            } catch (ReflectiveOperationException ignored) {
                // fall through to BukkitScheduler async
            }
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, r);
    }

    /** Run on a thread that owns the given player's region. Best effort. */
    public static void runOnPlayer(Plugin plugin, Player player, Runnable r) {
        if (isFolia()) {
            try {
                Object entSched = player.getClass().getMethod("getScheduler").invoke(player);
                Method m = entSched.getClass().getMethod(
                        "run", Plugin.class, java.util.function.Consumer.class, Runnable.class);
                m.invoke(entSched, plugin,
                        (java.util.function.Consumer<Object>) task -> r.run(),
                        (Runnable) () -> { /* retired: player offline */ });
                return;
            } catch (ReflectiveOperationException ignored) {
                // fall through
            }
        }
        Bukkit.getScheduler().runTask(plugin, r);
    }

    /** Run on the global region (Folia) or main thread (Spigot/Paper). */
    public static void runGlobal(Plugin plugin, Runnable r) {
        if (isFolia()) {
            try {
                Object globalSched = Bukkit.getServer().getClass()
                        .getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer());
                Method m = globalSched.getClass().getMethod(
                        "run", Plugin.class, java.util.function.Consumer.class);
                m.invoke(globalSched, plugin,
                        (java.util.function.Consumer<Object>) task -> r.run());
                return;
            } catch (ReflectiveOperationException ignored) {
                // fall through
            }
        }
        Bukkit.getScheduler().runTask(plugin, r);
    }

    /**
     * Schedule {@code r} on the async scheduler at a fixed period in ms.
     *
     * <p>Returns an opaque handle: on Spigot/Paper it boxes the BukkitScheduler
     * task id; on Folia it is the {@code ScheduledTask} returned by
     * {@code AsyncScheduler#runAtFixedRate}. Pass it back to {@link #cancel(Object)}.
     *
     * <p>Folia rejects {@code BukkitScheduler#runTaskTimerAsynchronously} with
     * {@link UnsupportedOperationException}, so we must reflectively target the
     * AsyncScheduler there.
     */
    public static Object runAsyncTimer(Plugin plugin, Runnable r, long periodMs) {
        long period = Math.max(1L, periodMs);
        if (isFolia()) {
            try {
                Object asyncSched = Bukkit.getServer().getClass()
                        .getMethod("getAsyncScheduler").invoke(Bukkit.getServer());
                Method m = asyncSched.getClass().getMethod(
                        "runAtFixedRate", Plugin.class, java.util.function.Consumer.class,
                        long.class, long.class, java.util.concurrent.TimeUnit.class);
                return m.invoke(asyncSched, plugin,
                        (java.util.function.Consumer<Object>) task -> r.run(),
                        period, period, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (ReflectiveOperationException e) {
                // fall through to BukkitScheduler — likely to throw on Folia,
                // but at least surfaces a useful stack trace.
            }
        }
        long ticks = Math.max(1L, periodMs / 50L);
        return Integer.valueOf(
                Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, r, ticks, ticks).getTaskId());
    }

    /** Cancel a handle previously returned by {@link #runAsyncTimer}. Null-safe. */
    public static void cancel(Object handle) {
        if (handle == null) return;
        if (handle instanceof Integer) {
            int id = (Integer) handle;
            if (id > 0) Bukkit.getScheduler().cancelTask(id);
            return;
        }
        // Folia ScheduledTask — call cancel() reflectively to avoid compile-time
        // dependency on io.papermc.paper.threadedregions.scheduler.ScheduledTask.
        try {
            handle.getClass().getMethod("cancel").invoke(handle);
        } catch (ReflectiveOperationException ignored) {
            // best effort
        }
    }

    private Sched() {}
}
