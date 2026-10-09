package io.github.dailystruggle.rtp.bukkit.tools.softdepends.hologram;

import io.github.dailystruggle.effectsapi.common.hologram.HologramHandle;
import io.github.dailystruggle.effectsapi.common.hologram.HologramProvider;
import io.github.dailystruggle.effectsapi.common.volumetric.Vector3d;
import io.github.dailystruggle.rtp.common.RTP;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;

import java.util.List;
import java.util.logging.Level;

/**
 * Native TextDisplay entity provider for Paper / Folia (1.19.4+).
 * Renders floating billboard text displays using vanilla entity packets.
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

        Location loc = new Location(world, position.x(), position.y(), position.z());
        try {
            TextDisplay display = (TextDisplay) world.spawnEntity(loc, EntityType.valueOf("TEXT_DISPLAY"));
            display.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            display.setText(formatLines(lines));
            display.setPersistent(false);

            return new BukkitTextDisplayHandle(id, worldName, position, lines, display);
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP] Failed to spawn TextDisplay hologram '" + id + "': " + t.getMessage(), t);
            return new io.github.dailystruggle.effectsapi.common.hologram.VirtualHologramHandle(id, worldName, position, lines);
        }
    }

    private static String formatLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        return String.join("\n", lines);
    }

    private static final class BukkitTextDisplayHandle implements HologramHandle {
        private final String id;
        private final String worldName;
        private volatile Vector3d position;
        private volatile List<String> lines;
        private final TextDisplay display;

        BukkitTextDisplayHandle(String id, String worldName, Vector3d position, List<String> lines, TextDisplay display) {
            this.id = id;
            this.worldName = worldName;
            this.position = position;
            this.lines = lines != null ? List.copyOf(lines) : List.of();
            this.display = display;
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
            if (display.isValid()) {
                display.setText(formatLines(newLines));
            }
        }

        @Override
        public void teleport(Vector3d newPosition) {
            this.position = newPosition;
            if (display.isValid()) {
                World world = Bukkit.getWorld(worldName);
                if (world != null) {
                    Location loc = new Location(world, newPosition.x(), newPosition.y(), newPosition.z());
                    display.teleport(loc);
                }
            }
        }

        @Override
        public void close() {
            if (display.isValid()) {
                display.remove();
            }
        }
    }
}
