package io.github.dailystruggle.helpers.stresstestrtp;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Plugin-free random teleport for the leak check's {@code direct} control arm
 * (ADR-122 Decision 5).
 *
 * <p>Routes the platform-specific chunk load and player teleport through
 * {@link StressHost#teleportDirect(StressPlayer, int, int, Consumer)} while
 * keeping coordinate picking, radius bounds, and issued/failed counters in
 * {@code stresstest-core}.
 */
public final class HarnessTeleporter {

    private final StressHost host;
    private volatile int radius;
    private final AtomicLong issued = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public HarnessTeleporter(StressHost host, int radius) {
        this.host = host;
        this.radius = Math.max(16, radius);
    }

    public void setRadius(int r) {
        this.radius = Math.max(16, r);
    }

    public int radius() {
        return radius;
    }

    public boolean available() {
        return host != null && host.canTeleportDirect();
    }

    public long issued() {
        return issued.get();
    }

    public long failed() {
        return failed.get();
    }

    /** Picks a random coordinate in {@code [-radius, radius]} and teleports {@code player}. */
    public void teleport(StressPlayer player, Consumer<String> onFail) {
        if (!available()) {
            failed.incrementAndGet();
            if (onFail != null) onFail.accept("teleportDirect unavailable on this server");
            return;
        }
        int r = this.radius;
        int x = pick(r);
        int z = pick(r);
        issued.incrementAndGet();
        host.teleportDirect(player, x, z, reason -> {
            failed.incrementAndGet();
            if (onFail != null) onFail.accept(reason);
        });
    }

    static int pick(int r) {
        return ThreadLocalRandom.current().nextInt(-r, r + 1);
    }
}
