package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.lang.reflect.Method;

/**
 * Thread-identity oracle: "is the calling thread a server tick thread?".
 *
 * <p>Needed because the strongest health discriminator in the competitor
 * dataset is not how much chunk work a plugin does but <em>where</em> it does
 * it. A chunk load on a chunk-system thread costs wall time only; the same
 * load on the tick thread is charged directly to the tick budget and shows up
 * in the MSPT tail. The per-phase async share already collected cannot answer
 * that per attempt, and the average hides the tail entirely.
 *
 * <p>Platform behaviour:
 * <ul>
 *   <li><b>Spigot / Paper.</b> One tick thread. {@link Bukkit#isPrimaryThread()}
 *       is exact. The thread id captured by {@link #captureTickThread()} is a
 *       fallback for calls made while the server is not in a tickable state.</li>
 *   <li><b>Folia.</b> Many region tick threads, plus the global region thread.
 *       {@code isPrimaryThread()} is not meaningful, so we ask Folia's own
 *       {@code TickRegionScheduler.isTickThread()} reflectively, and for
 *       chunk-scoped questions we ask
 *       {@code Bukkit.isOwnedByCurrentRegion(World, int, int)} - i.e. "is this
 *       thread the region thread that owns the chunk that just loaded".
 *       Detection is runtime-only, gated on {@link Sched#isFolia()}; there is
 *       deliberately no build variant and no config toggle.</li>
 * </ul>
 *
 * <p>All lookups are resolved once and cached in static fields. Every query is
 * allocation-free and non-blocking, so it is safe to call from a MONITOR event
 * handler on the tick thread at peak chunk-load rates.
 */
public final class TickThreadDetector {

    /** Id of the thread that ran a one-shot global/sync task at plugin enable.
     *  On Spigot/Paper that is the server tick thread; on Folia it is the
     *  global region thread. {@code -1} until captured. */
    private static volatile long tickThreadId = -1L;

    /** Folia: {@code TickRegionScheduler.isTickThread()}. Null elsewhere or if absent. */
    private static volatile Method foliaIsTickThread;
    /** Folia: {@code Bukkit.isOwnedByCurrentRegion(World, int, int)}. Null elsewhere or if absent. */
    private static volatile Method isOwnedByCurrentRegion;
    private static volatile boolean resolved = false;

    private TickThreadDetector() {}

    /** Called from a global/sync task at plugin enable to record the tick
     *  thread's identity. Idempotent; last writer wins. */
    public static void captureTickThread() {
        tickThreadId = Thread.currentThread().getId();
        resolve();
    }

    private static void resolve() {
        if (resolved) return;
        if (Sched.isFolia()) {
            try {
                Class<?> sched = Class.forName(
                        "io.papermc.paper.threadedregions.TickRegionScheduler");
                Method m = sched.getMethod("isTickThread");
                foliaIsTickThread = m;
            } catch (ReflectiveOperationException ignored) {
                foliaIsTickThread = null;
            }
            try {
                isOwnedByCurrentRegion = Bukkit.class.getMethod(
                        "isOwnedByCurrentRegion", World.class, int.class, int.class);
            } catch (NoSuchMethodException ignored) {
                isOwnedByCurrentRegion = null;
            }
        }
        resolved = true;
    }

    /**
     * True iff the calling thread is a server tick thread. On Folia this
     * accepts any region tick thread and the global region thread; use
     * {@link #ownsChunk} when the question is scoped to a specific chunk.
     */
    public static boolean onTickThread() {
        resolve();
        Method m = foliaIsTickThread;
        if (m != null) {
            try {
                Object r = m.invoke(null);
                if (r instanceof Boolean) return (Boolean) r;
            } catch (ReflectiveOperationException ignored) {
                foliaIsTickThread = null; // degrade once, never spam
            }
        }
        try {
            if (Bukkit.isPrimaryThread()) return true;
        } catch (Throwable ignored) {
            // Server not in a queryable state; fall through to the id check.
        }
        long id = tickThreadId;
        return id >= 0L && Thread.currentThread().getId() == id;
    }

    /**
     * Folia-aware, chunk-scoped variant: true iff the calling thread is the
     * tick thread that owns {@code (chunkX, chunkZ)} in {@code world}. On
     * Spigot/Paper (and on Folia builds that do not expose the ownership
     * query) this falls back to {@link #onTickThread()}, which is exact there
     * because there is only one tick thread.
     */
    public static boolean ownsChunk(World world, int chunkX, int chunkZ) {
        resolve();
        Method m = isOwnedByCurrentRegion;
        if (m != null && world != null) {
            try {
                Object r = m.invoke(null, world, chunkX, chunkZ);
                if (r instanceof Boolean) return (Boolean) r;
            } catch (ReflectiveOperationException ignored) {
                isOwnedByCurrentRegion = null; // degrade once, never spam
            }
        }
        return onTickThread();
    }

    /** True iff the Folia region-ownership query is live, i.e. the
     *  {@code *_folia_*} interpretation of the on-tick columns applies. */
    public static boolean regionOwnershipAvailable() {
        resolve();
        return isOwnedByCurrentRegion != null;
    }
}
