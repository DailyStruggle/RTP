package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Plugin-free random teleport for the {@code direct} leak control
 * ({@code /rtpstress tp <player>}).
 *
 * <p>Why: the {@code none} control never teleports, so growth caused by
 * exploring new terrain (generation, structure templates, landing-area
 * loads) was billed to whichever RTP plugin ran. Driving this through the
 * ramp like any target gives a control with the same teleport rate and
 * landing cost but no RTP plugin code; its slope is subtracted per metric.
 *
 * <p>No selection, safety or claim logic: the destination is a uniform
 * point in a square, landed on the highest block. The chunk is loaded with
 * Paper's {@code World#getChunkAtAsync} (S-005), the height is read on the
 * thread that completed the load, and {@code Entity#teleportAsync} runs on
 * the player's owning thread. Both are reflective; on plain Spigot the
 * command fails closed with a message instead of loading synchronously.
 */
final class HarnessTeleporter {

    private final Plugin plugin;
    private final int radius;
    private final Method chunkAtAsync;
    private final Method teleportAsync;
    private final AtomicLong issued = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    HarnessTeleporter(Plugin plugin, int radius) {
        this.plugin = plugin;
        this.radius = Math.max(16, radius);
        this.chunkAtAsync = find(World.class, "getChunkAtAsync", int.class, int.class, boolean.class);
        this.teleportAsync = find(org.bukkit.entity.Entity.class, "teleportAsync", Location.class);
    }

    private static Method find(Class<?> c, String name, Class<?>... args) {
        try {
            return c.getMethod(name, args);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    boolean available() {
        return chunkAtAsync != null && teleportAsync != null;
    }

    long issued() { return issued.get(); }

    long failed() { return failed.get(); }

    /** Uniform block coordinate in [-radius, radius]. */
    static int pick(int radius) {
        return ThreadLocalRandom.current().nextInt(-radius, radius + 1);
    }

    /** Starts one teleport; {@code onFail} gets a reason when it cannot complete. */
    void teleport(Player player, Consumer<String> onFail) {
        if (!available()) {
            onFail.accept("direct teleport needs Paper (getChunkAtAsync/teleportAsync)");
            return;
        }
        World world = player.getWorld();
        int x = pick(radius);
        int z = pick(radius);
        issued.incrementAndGet();
        CompletableFuture<?> load;
        try {
            load = (CompletableFuture<?>) chunkAtAsync.invoke(world, x >> 4, z >> 4, true);
        } catch (ReflectiveOperationException e) {
            failed.incrementAndGet();
            onFail.accept("getChunkAtAsync: " + e);
            return;
        }
        load.whenComplete((chunk, err) -> {
            if (err != null || chunk == null) {
                failed.incrementAndGet();
                onFail.accept("chunk load failed: " + err);
                return;
            }
            // Completion thread owns the chunk (Paper main / Folia region).
            int y = world.getHighestBlockYAt(x, z) + 1;
            Location to = new Location(world, x + 0.5, y, z + 0.5);
            Sched.runOnPlayer(plugin, player, () -> {
                if (!player.isOnline()) return;
                try {
                    Object f = teleportAsync.invoke(player, to);
                    if (f instanceof CompletableFuture<?> cf) {
                        cf.whenComplete((ok, e2) -> {
                            if (e2 != null || Boolean.FALSE.equals(ok)) {
                                failed.incrementAndGet();
                                onFail.accept("teleportAsync refused: " + e2);
                            }
                        });
                    }
                } catch (ReflectiveOperationException e) {
                    failed.incrementAndGet();
                    onFail.accept("teleportAsync: " + e);
                }
            });
        });
    }
}
