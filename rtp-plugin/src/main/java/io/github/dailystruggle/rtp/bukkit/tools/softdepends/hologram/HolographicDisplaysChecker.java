package io.github.dailystruggle.rtp.bukkit.tools.softdepends.hologram;

import io.github.dailystruggle.effectsapi.common.hologram.HologramHandle;
import io.github.dailystruggle.effectsapi.common.hologram.HologramProvider;
import io.github.dailystruggle.effectsapi.common.volumetric.Vector3d;
import io.github.dailystruggle.rtp.common.RTP;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Level;

/**
 * Reflective softdepend adapter for HolographicDisplays (com.gmail.filoghost.holographicdisplays.api.HologramsAPI).
 */
public final class HolographicDisplaysChecker implements HologramProvider {
    private static final boolean AVAILABLE;
    private static Method CREATE_HOLOGRAM_METHOD;
    private static Method CLEAR_LINES_METHOD;
    private static Method APPEND_TEXT_LINE_METHOD;
    private static Method TELEPORT_METHOD;
    private static Method DELETE_METHOD;

    static {
        boolean avail = false;
        try {
            Class<?> apiClass = Class.forName("com.gmail.filoghost.holographicdisplays.api.HologramsAPI");
            Class<?> holoClass = Class.forName("com.gmail.filoghost.holographicdisplays.api.Hologram");

            CREATE_HOLOGRAM_METHOD = apiClass.getMethod("createHologram", Plugin.class, Location.class);
            CLEAR_LINES_METHOD = holoClass.getMethod("clearLines");
            APPEND_TEXT_LINE_METHOD = holoClass.getMethod("appendTextLine", String.class);
            TELEPORT_METHOD = holoClass.getMethod("teleport", Location.class);
            DELETE_METHOD = holoClass.getMethod("delete");
            avail = true;
        } catch (Throwable t) {
            avail = false;
        }
        AVAILABLE = avail;
    }

    private final Plugin plugin;

    public HolographicDisplaysChecker(Plugin plugin) {
        this.plugin = plugin;
    }

    public static boolean isAvailable() {
        if (!AVAILABLE) return false;
        try {
            return Bukkit.getPluginManager().isPluginEnabled("HolographicDisplays");
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
            Object hologramObj = CREATE_HOLOGRAM_METHOD.invoke(null, plugin, loc);
            if (lines != null) {
                for (String line : lines) {
                    APPEND_TEXT_LINE_METHOD.invoke(hologramObj, line);
                }
            }
            return new HolographicDisplaysHandle(id, worldName, position, lines, hologramObj);
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP] Failed to spawn HolographicDisplays hologram '" + id + "': " + t.getMessage(), t);
            return new io.github.dailystruggle.effectsapi.common.hologram.VirtualHologramHandle(id, worldName, position, lines);
        }
    }

    private static final class HolographicDisplaysHandle implements HologramHandle {
        private final String id;
        private final String worldName;
        private volatile Vector3d position;
        private volatile List<String> lines;
        private final Object hologramObj;

        HolographicDisplaysHandle(String id, String worldName, Vector3d position, List<String> lines, Object hologramObj) {
            this.id = id;
            this.worldName = worldName;
            this.position = position;
            this.lines = lines != null ? List.copyOf(lines) : List.of();
            this.hologramObj = hologramObj;
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
            try {
                CLEAR_LINES_METHOD.invoke(hologramObj);
                for (String line : newLines) {
                    APPEND_TEXT_LINE_METHOD.invoke(hologramObj, line);
                }
            } catch (Throwable t) {
                RTP.log(Level.FINE, "[RTP] HolographicDisplays updateLines failed for " + id + ": " + t.getMessage());
            }
        }

        @Override
        public void teleport(Vector3d newPosition) {
            this.position = newPosition;
            try {
                World world = Bukkit.getWorld(worldName);
                Location loc = new Location(world, newPosition.x(), newPosition.y(), newPosition.z());
                TELEPORT_METHOD.invoke(hologramObj, loc);
            } catch (Throwable t) {
                RTP.log(Level.FINE, "[RTP] HolographicDisplays teleport failed for " + id + ": " + t.getMessage());
            }
        }

        @Override
        public void close() {
            try {
                DELETE_METHOD.invoke(hologramObj);
            } catch (Throwable t) {
                RTP.log(Level.FINE, "[RTP] HolographicDisplays delete failed for " + id + ": " + t.getMessage());
            }
        }
    }
}
