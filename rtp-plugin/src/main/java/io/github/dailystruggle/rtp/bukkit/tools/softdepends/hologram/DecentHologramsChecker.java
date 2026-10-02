package io.github.dailystruggle.rtp.bukkit.tools.softdepends.hologram;

import io.github.dailystruggle.effectsapi.common.hologram.HologramHandle;
import io.github.dailystruggle.effectsapi.common.hologram.HologramProvider;
import io.github.dailystruggle.effectsapi.common.volumetric.Vector3d;
import io.github.dailystruggle.rtp.common.RTP;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Level;

/**
 * Reflective softdepend adapter for DecentHolograms (eu.decentsoftware.holograms.api.DHAPI).
 */
public final class DecentHologramsChecker implements HologramProvider {
    private static final boolean AVAILABLE;
    private static Method CREATE_HOLOGRAM_METHOD;
    private static Method SET_LINES_METHOD;
    private static Method MOVE_HOLOGRAM_METHOD;
    private static Method DESTROY_METHOD;

    static {
        boolean avail = false;
        try {
            Class<?> dhApiClass = Class.forName("eu.decentsoftware.holograms.api.DHAPI");
            Class<?> holoClass = Class.forName("eu.decentsoftware.holograms.api.holograms.Hologram");

            CREATE_HOLOGRAM_METHOD = dhApiClass.getMethod("createHologram", String.class, Location.class, List.class);
            SET_LINES_METHOD = dhApiClass.getMethod("setHologramLines", holoClass, List.class);
            MOVE_HOLOGRAM_METHOD = dhApiClass.getMethod("moveHologram", holoClass, Location.class);
            DESTROY_METHOD = holoClass.getMethod("destroy");
            avail = true;
        } catch (Throwable t) {
            avail = false;
        }
        AVAILABLE = avail;
    }

    public static boolean isAvailable() {
        if (!AVAILABLE) return false;
        try {
            return Bukkit.getPluginManager().isPluginEnabled("DecentHolograms");
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public HologramHandle spawnHologram(String id, String worldName, Vector3d position, List<String> lines) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return new io.github.dailystruggle.effectsapi.common.hologram.VirtualHologramHandle(id, worldName, position, lines);
        }
        Location loc = new Location(world, position.x(), position.y(), position.z());

        try {
            Object hologramObj = CREATE_HOLOGRAM_METHOD.invoke(null, id, loc, lines);
            return new DecentHologramHandle(id, worldName, position, lines, hologramObj);
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP] Failed to spawn DecentHolograms hologram '" + id + "': " + t.getMessage(), t);
            return new io.github.dailystruggle.effectsapi.common.hologram.VirtualHologramHandle(id, worldName, position, lines);
        }
    }

    private static final class DecentHologramHandle implements HologramHandle {
        private final String id;
        private final String worldName;
        private volatile Vector3d position;
        private volatile List<String> lines;
        private final Object hologramObj;

        DecentHologramHandle(String id, String worldName, Vector3d position, List<String> lines, Object hologramObj) {
            this.id = id;
            this.worldName = worldName;
            this.position = position;
            this.lines = List.copyOf(lines);
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
                SET_LINES_METHOD.invoke(null, hologramObj, newLines);
            } catch (Throwable t) {
                RTP.log(Level.FINE, "[RTP] DecentHolograms updateLines failed for " + id + ": " + t.getMessage());
            }
        }

        @Override
        public void teleport(Vector3d newPosition) {
            this.position = newPosition;
            try {
                World world = Bukkit.getWorld(worldName);
                Location loc = new Location(world, newPosition.x(), newPosition.y(), newPosition.z());
                MOVE_HOLOGRAM_METHOD.invoke(null, hologramObj, loc);
            } catch (Throwable t) {
                RTP.log(Level.FINE, "[RTP] DecentHolograms moveHologram failed for " + id + ": " + t.getMessage());
            }
        }

        @Override
        public void close() {
            try {
                DESTROY_METHOD.invoke(hologramObj);
            } catch (Throwable t) {
                RTP.log(Level.FINE, "[RTP] DecentHolograms destroy failed for " + id + ": " + t.getMessage());
            }
        }
    }
}
