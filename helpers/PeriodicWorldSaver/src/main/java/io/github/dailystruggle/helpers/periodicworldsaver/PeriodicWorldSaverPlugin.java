package io.github.dailystruggle.helpers.periodicworldsaver;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Level;

/**
 * Standalone Spigot helper plugin: periodically saves every loaded world
 * and unloads every loaded chunk while the server is empty.
 *
 * <p>Designed to run alongside any unattended pre-generation tool
 * (Chunky, WorldBorder fill, custom generators) so the resident-chunk
 * set never grows large enough to make {@code /stop} freeze on the
 * final save-and-close pass.
 *
 * <p>Pre-generator-agnostic by design: the only trigger is "zero
 * players online". No detection of Chunky or any other plugin.
 *
 * <p>This plugin is intentionally configuration-free. To disable, remove
 * the JAR. Behaviour is hardcoded:
 * <ul>
 *   <li>scan cadence: 60 seconds</li>
 *   <li>dormant whenever any player is online</li>
 *   <li>unload mode: {@code save = true}</li>
 * </ul>
 */
public final class PeriodicWorldSaverPlugin extends JavaPlugin {

    /** Scan cadence in server ticks (60 s × 20 ticks). */
    private static final long PERIOD_TICKS = 60L * 20L;

    private BukkitTask task;

    @Override
    public void onEnable() {
        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, PERIOD_TICKS, PERIOD_TICKS);
        getLogger().info("PeriodicWorldSaver enabled: sweep every 60s while no players are online.");
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        // Final sweep so manual /stop mid-pregen still benefits from
        // the same incremental flush behaviour as the periodic timer.
        try {
            sweep(true);
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Final sweep on disable failed", t);
        }
    }

    private void tick() {
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            return; // dormant while anyone is online
        }
        sweep(false);
    }

    /**
     * Save every loaded world, then walk every loaded chunk and request
     * an unload (with {@code save = true}). The server preserves chunks
     * held by force-tickets automatically — {@link World#unloadChunk(int, int, boolean)}
     * is a no-op in that case.
     *
     * @param finalSweep true when invoked from {@link #onDisable()};
     *                   logs slightly differently for diagnostics.
     */
    private void sweep(boolean finalSweep) {
        long start = System.currentTimeMillis();
        int worldsSaved = 0;
        int chunksUnloaded = 0;

        for (World world : Bukkit.getWorlds()) {
            try {
                world.save();
                worldsSaved++;
            } catch (Throwable t) {
                getLogger().log(Level.WARNING,
                        "Failed to save world '" + world.getName() + "'", t);
                continue;
            }

            // Snapshot — Bukkit's getLoadedChunks() returns a fresh array
            // each call, so iteration is safe even though we're mutating
            // the loaded set below.
            for (Chunk chunk : world.getLoadedChunks()) {
                try {
                    if (world.unloadChunk(chunk.getX(), chunk.getZ(), true)) {
                        chunksUnloaded++;
                    }
                } catch (Throwable t) {
                    getLogger().log(Level.WARNING,
                            "Failed to unload chunk (" + chunk.getX() + "," + chunk.getZ()
                                    + ") in world '" + world.getName() + "'", t);
                }
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        getLogger().info((finalSweep ? "Final sweep: " : "Sweep: ")
                + "saved " + worldsSaved + " worlds, unloaded "
                + chunksUnloaded + " chunks in " + elapsed + " ms");
    }
}
