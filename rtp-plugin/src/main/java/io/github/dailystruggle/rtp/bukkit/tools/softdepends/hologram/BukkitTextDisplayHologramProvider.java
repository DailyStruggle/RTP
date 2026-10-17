package io.github.dailystruggle.rtp.bukkit.tools.softdepends.hologram;

import io.github.dailystruggle.effectsapi.common.hologram.HologramHandle;
import io.github.dailystruggle.effectsapi.common.hologram.HologramProvider;
import io.github.dailystruggle.effectsapi.common.volumetric.Vector3d;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;

import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Level;

/**
 * Native TextDisplay entity provider for Paper / Folia (1.19.4+).
 * Renders floating billboard text displays using vanilla entity packets.
 *
 * <p>Callers may be on any thread (global region, async timer). Every entity mutation is
 * routed through {@code RTP.scheduler} onto the thread owning the hologram's chunk; spawning
 * from a non-owning Folia thread NPEs inside {@code ServerLevel#addEntity}. The scheduler runs
 * inline when the caller already owns the chunk.
 */
public final class BukkitTextDisplayHologramProvider implements HologramProvider {
    private static final boolean AVAILABLE;

    static {
        boolean avail = false;
        try {
            EntityType.valueOf("TEXT_DISPLAY");
            Class.forName("org.bukkit.entity.TextDisplay");
            avail = true;
        } catch (Throwable t) {
            avail = false;
        }
        AVAILABLE = avail;
    }

    public static boolean isAvailable() {
        if (!AVAILABLE) return false;
        try {
            return Bukkit.getServer() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static World resolveWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) return null;
        try {
            return Bukkit.getWorld(worldName);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public HologramHandle spawnHologram(String id, String worldName, Vector3d position, List<String> lines) {
        World world = resolveWorld(worldName);
        if (world == null) {
            return new io.github.dailystruggle.effectsapi.common.hologram.VirtualHologramHandle(id, worldName, position, lines);
        }

        BukkitTextDisplayHandle handle = new BukkitTextDisplayHandle(id, worldName, position, lines);
        runOnOwner(worldName, position, () -> handle.spawn(world, position));
        return handle;
    }

    /** Runs {@code task} on the thread owning the chunk at {@code position}; inline when no scheduler/world is bound. */
    static void runOnOwner(String worldName, Vector3d position, Runnable task) {
        Runnable guarded = () -> {
            try {
                task.run();
            } catch (Throwable t) {
                RTP.log(Level.WARNING, "[RTP] TextDisplay hologram task failed: " + t.getMessage(), t);
            }
        };
        RTPWorld<?> rtpWorld = null;
        try {
            if (RTP.scheduler != null && RTP.serverAccessor != null) {
                rtpWorld = RTP.serverAccessor.getRTPWorld(worldName);
            }
        } catch (Throwable ignored) {
            rtpWorld = null;
        }
        if (rtpWorld == null) {
            guarded.run();
            return;
        }
        int cx = (int) Math.floor(position.x()) >> 4;
        int cz = (int) Math.floor(position.z()) >> 4;
        RTP.scheduler.runTask(rtpWorld, cx, cz, guarded);
    }

    private static String formatLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        return String.join("\n", HologramText.render(lines));
    }

    private static final class BukkitTextDisplayHandle implements HologramHandle {
        private final String id;
        private final String worldName;
        private volatile Vector3d position;
        private volatile List<String> lines;
        private volatile TextDisplay display;
        private volatile boolean closed;

        BukkitTextDisplayHandle(String id, String worldName, Vector3d position, List<String> lines) {
            this.id = id;
            this.worldName = worldName;
            this.position = position;
            this.lines = lines != null ? List.copyOf(lines) : List.of();
        }

        /** Must run on the owning region thread. */
        void spawn(World world, Vector3d pos) {
            if (closed) return;
            Location loc = new Location(world, pos.x(), pos.y(), pos.z());
            TextDisplay spawned;
            try {
                spawned = (TextDisplay) world.spawnEntity(loc, EntityType.valueOf("TEXT_DISPLAY"));
                spawned.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                spawned.setText(formatLines(lines));
                spawned.setPersistent(false);
            } catch (Throwable t) {
                RTP.log(Level.WARNING, "[RTP] Failed to spawn TextDisplay hologram '" + id + "': " + t.getMessage(), t);
                return;
            }
            if (closed) {
                spawned.remove();
                return;
            }
            display = spawned;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String worldName() {
            return worldName;
        }

        @Override
        public Vector3d position() {
            return position;
        }

        @Override
        public List<String> lines() {
            return lines;
        }

        @Override
        public void updateLines(List<String> newLines) {
            this.lines = List.copyOf(newLines);
            runOnOwner(worldName, position, () -> {
                TextDisplay d = display;
                if (d != null && d.isValid()) {
                    d.setText(formatLines(lines));
                }
            });
        }

        @Override
        public void teleport(Vector3d newPosition) {
            Vector3d previous = this.position;
            this.position = newPosition;
            // Entity is still owned by the region of its previous position.
            runOnOwner(worldName, previous, () -> {
                TextDisplay d = display;
                if (d == null || !d.isValid()) return;
                World world = Bukkit.getWorld(worldName);
                if (world == null) return;
                teleportEntity(d, new Location(world, newPosition.x(), newPosition.y(), newPosition.z()));
            });
        }

        @Override
        public void close() {
            closed = true;
            runOnOwner(worldName, position, () -> {
                TextDisplay d = display;
                display = null;
                if (d != null && d.isValid()) {
                    d.remove();
                }
            });
        }

        /** Folia rejects synchronous {@code Entity#teleport}; prefer Paper's {@code teleportAsync} when present. */
        private static void teleportEntity(TextDisplay d, Location loc) {
            try {
                Method async = d.getClass().getMethod("teleportAsync", Location.class);
                async.invoke(d, loc);
                return;
            } catch (NoSuchMethodException ignored) {
                // Spigot: synchronous teleport is valid on the main thread.
            } catch (ReflectiveOperationException e) {
                RTP.log(Level.FINE, "[RTP] teleportAsync failed for TextDisplay: " + e.getMessage());
                return;
            }
            d.teleport(loc);
        }
    }
}
