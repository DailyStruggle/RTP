package io.github.dailystruggle.helpers.stresstestrtp;

/**
 * Thread-identity oracle: "is the calling thread a server tick thread?"
 * via {@link StressHost} (ADR-122 Decision 1 & 2).
 */
public final class TickThreadDetector {

    private static volatile long tickThreadId = -1L;
    private static volatile StressHost boundHost;

    private TickThreadDetector() {}

    public static void bind(StressHost host) {
        boundHost = host;
    }

    public static void captureTickThread() {
        tickThreadId = Thread.currentThread().getId();
    }

    public static boolean onTickThread() {
        StressHost h = boundHost != null ? boundHost : Sched.boundHost();
        if (h != null) {
            try {
                if (h.isTickThread()) return true;
            } catch (Throwable ignored) {
            }
        }
        long id = tickThreadId;
        return id >= 0L && Thread.currentThread().getId() == id;
    }

    public static boolean ownsChunk(StressWorld world, int chunkX, int chunkZ) {
        return ownsChunk(world != null ? world.name() : "", chunkX, chunkZ);
    }

    public static boolean ownsChunk(String worldName, int chunkX, int chunkZ) {
        StressHost h = boundHost != null ? boundHost : Sched.boundHost();
        if (h != null) {
            try {
                return h.ownsChunk(worldName, chunkX, chunkZ);
            } catch (Throwable ignored) {
            }
        }
        return onTickThread();
    }

    public static boolean regionOwnershipAvailable() {
        StressHost h = boundHost != null ? boundHost : Sched.boundHost();
        return h != null && h.regionOwnershipAvailable();
    }
}
