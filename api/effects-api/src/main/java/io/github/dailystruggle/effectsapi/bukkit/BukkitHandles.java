package io.github.dailystruggle.effectsapi.bukkit;

import io.github.dailystruggle.effectsapi.common.spi.HandleProvider;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.effectsapi.common.spi.LocationHandle;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.bukkit.Particle;
import org.bukkit.Instrument;
import org.bukkit.Note;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.entity.Firework;
import org.bukkit.entity.EntityType;
import org.bukkit.util.Vector;
import io.github.dailystruggle.effectsapi.bukkit.BukkitListeners.FireworkSafetyListener;
import java.util.Objects;
import java.util.Map;

import java.util.UUID;

/**
 * Bukkit implementations of {@link PlayerHandle} and {@link LocationHandle}.
 */
public final class BukkitHandles implements HandleProvider {
    private static final BukkitHandles INSTANCE = new BukkitHandles();

    private BukkitHandles() {}

    public static void register() {
        HandleRegistry.setProvider(INSTANCE);
    }

    /**
     * Test seam: dispatch a region-bound task at {@code location}. Returns
     * {@code true} when the task was dispatched (or executed) by a Folia-style
     * region scheduler; {@code false} otherwise. Default implementation
     * resolves {@code Bukkit.getRegionScheduler()} reflectively so
     * {@code effects-api} keeps zero compile-time dependency on the Folia API.
     *
     * <p>Relocated here from {@code FireworkEffect} (which is now a
     * platform-neutral {@code common} effect): the region-dispatch seam is a
     * Bukkit/Folia concept and is only consumed by {@link #spawnFirework}.
     */
    @FunctionalInterface
    public interface RegionDispatcher {
        boolean dispatch(org.bukkit.plugin.Plugin caller, Location location, Runnable task);
    }

    public static volatile RegionDispatcher regionDispatcher = (caller, loc, task) -> {
        if (!isFolia()) return false;
        try {
            Object regionScheduler = org.bukkit.Bukkit.class
                    .getMethod("getRegionScheduler").invoke(null);
            regionScheduler.getClass()
                    .getMethod("run", org.bukkit.plugin.Plugin.class, Location.class,
                            java.util.function.Consumer.class)
                    .invoke(regionScheduler, caller, loc,
                            (java.util.function.Consumer<Object>) t -> task.run());
            return true;
        } catch (Throwable t) {
            return false;
        }
    };

    /**
     * Class-probe for Folia. Independent of any plugin instance and safe to
     * evaluate from {@code effects-api} (which has no compile-time dependency
     * on Folia API).
     */
    static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public @Nullable PlayerHandle wrapPlayer(@NotNull Object player) {
        if (player instanceof Player) return new PlayerWrapper((Player) player);
        return null;
    }

    @Override
    public @Nullable LocationHandle wrapLocation(@NotNull Object location) {
        if (location instanceof Location) return new LocationWrapper((Location) location);
        if (location instanceof Entity) return new LocationWrapper(((Entity) location).getLocation());
        return null;
    }

    @Override
    public @Nullable PlayerHandle playerAt(@NotNull LocationHandle location) {
        Location loc = unwrapLocation(location);
        if (loc == null || loc.getWorld() == null) return null;
        // Recover the online player standing within 1 block of the location.
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (p.getWorld() != loc.getWorld()) continue;
            if (p.getLocation().distanceSquared(loc) < 4.0) return new PlayerWrapper(p);
        }
        return null;
    }

    public static PlayerHandle wrap(@NotNull Player player) {
        return new PlayerWrapper(player);
    }

    public static LocationHandle wrap(@NotNull Location location) {
        return new LocationWrapper(location);
    }

    public static LocationHandle wrap(@NotNull Entity entity) {
        return new LocationWrapper(entity.getLocation());
    }

    /**
     * Extracts a Bukkit {@link Location} from a target object.
     * Supports {@link Location}, {@link LocationHandle}, {@link Entity}, and {@link PlayerHandle}.
     */
    public static @Nullable Location unwrapLocation(@Nullable Object target) {
        if (target instanceof LocationHandle lh) {
            return (Location) lh.platformLocation();
        }
        if (target instanceof PlayerWrapper pw) {
            return pw.player().getLocation();
        }
        if (target instanceof Entity) {
            return ((Entity) target).getLocation();
        }
        if (target instanceof Location) {
            return (Location) target;
        }
        return null;
    }

    private record PlayerWrapper(@NotNull Player player) implements PlayerHandle {
        @Override
        public @NotNull UUID uuid() {
            return player.getUniqueId();
        }

        @Override
        public @NotNull String name() {
            return player.getName();
        }

        @Override
        public void playSound(Object type, float volume, float pitch, double dx, double dy, double dz) {
            player.playSound(player.getLocation().add(dx, dy, dz), (Sound) type, volume, pitch);
        }

        @Override
        public void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed) {
            player.spawnParticle((Particle) type, player.getLocation().add(dx, dy, dz), count, 0, 0, 0, speed);
        }

        @Override
        public void applyPotionEffect(Object type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {
            player.addPotionEffect(new org.bukkit.potion.PotionEffect((org.bukkit.potion.PotionEffectType) type, duration, amplifier, ambient, particles, icon));
        }

        @Override
        public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
            player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
        }

        @Override
        public void playNote(Object instrument, int tone) {
            player.playNote(player.getLocation(), (Instrument) instrument, new Note(tone));
        }

        @Override
        public void setGliding(boolean gliding) {
            player.setGliding(gliding);
        }

        @Override
        public void spawnFirework(Map<String, Object> data) {
            BukkitHandles.spawnFirework(player.getLocation(), data);
        }

        @Override
        public void startGlide(int relativeLift, int maxY, int landingTimeoutTicks,
                               boolean allowFireworks, boolean placeOnShutdown, String platformMaterial) {
            org.bukkit.Material platformMat = coerceMaterial(platformMaterial);

            Location lifted = player.getLocation().clone();
            int toY = Math.min(lifted.getBlockY() + relativeLift, maxY);
            lifted.setY(toY);
            try {
                player.teleport(lifted);
                player.setFlying(false);
                player.setGliding(true);
            } catch (Throwable t) {
                // S-004: don't swallow silently; surface via plugin logger.
                org.bukkit.plugin.Plugin caller = io.github.dailystruggle.effectsapi.EffectsAPI.getInstance();
                if (caller != null) caller.getLogger().warning(
                        "GlideEffect failed to start glide for " + player.getName() + ": " + t.getMessage());
                return;
            }

            io.github.dailystruggle.effectsapi.bukkit.BukkitListeners.GlideSafetyListener.GlideState state =
                    new io.github.dailystruggle.effectsapi.bukkit.BukkitListeners.GlideSafetyListener.GlideState(
                            player.getUniqueId(), allowFireworks, placeOnShutdown, platformMat);
            io.github.dailystruggle.effectsapi.bukkit.BukkitListeners.GlideSafetyListener.register(state);

            org.bukkit.Bukkit.getPluginManager().callEvent(
                    new io.github.dailystruggle.effectsapi.bukkit.events.PlayerGlideEvent(player));

            // Arm watchdog. Integer.MAX_VALUE is treated as "no timeout" — skip the
            // schedule entirely to avoid a long-lived task that will never fire.
            if (landingTimeoutTicks > 0 && landingTimeoutTicks < Integer.MAX_VALUE) {
                org.bukkit.plugin.Plugin caller = io.github.dailystruggle.effectsapi.EffectsAPI.getInstance();
                if (caller != null) {
                    try {
                        org.bukkit.scheduler.BukkitTask task = org.bukkit.Bukkit.getScheduler().runTaskLater(caller, () -> {
                            Player p = org.bukkit.Bukkit.getPlayer(state.playerId);
                            if (p != null && io.github.dailystruggle.effectsapi.bukkit.BukkitListeners.GlideSafetyListener.isGliding(state.playerId)) {
                                io.github.dailystruggle.effectsapi.bukkit.BukkitListeners.GlideSafetyListener.endGlide(
                                        p, io.github.dailystruggle.effectsapi.bukkit.events.PlayerLandEvent.Reason.TIMEOUT);
                            }
                        }, landingTimeoutTicks);
                        state.watchdogTaskId = task.getTaskId();
                    } catch (Throwable t) {
                        caller.getLogger().warning(
                                "GlideEffect could not schedule timeout watchdog: " + t.getMessage());
                    }
                }
            }
        }

        private static org.bukkit.Material coerceMaterial(String value) {
            if (value != null) {
                org.bukkit.Material m = org.bukkit.Material.matchMaterial(value);
                if (m != null) return m;
            }
            return org.bukkit.Material.STONE;
        }

        @Override
        public String toString() {
            return "BukkitPlayerHandle{name=" + player.getName() + ", uuid=" + player.getUniqueId() + "}";
        }
    }

    private record LocationWrapper(@NotNull Location location) implements LocationHandle {
        @Override
        public int x() {
            return location.getBlockX();
        }

        @Override
        public int y() {
            return location.getBlockY();
        }

        @Override
        public int z() {
            return location.getBlockZ();
        }

        @Override
        public double doubleX() {
            return location.getX();
        }

        @Override
        public double doubleY() {
            return location.getY();
        }

        @Override
        public double doubleZ() {
            return location.getZ();
        }

        @Override
        public @NotNull String worldName() {
            World world = location.getWorld();
            return world != null ? world.getName() : "unknown";
        }

        @Override
        public @NotNull Object platformLocation() {
            return location;
        }

        @Override
        public void playSound(Object type, float volume, float pitch) {
            World world = location.getWorld();
            if (world != null) world.playSound(location, (Sound) type, volume, pitch);
        }

        @Override
        public void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed) {
            World world = location.getWorld();
            if (world != null) world.spawnParticle((Particle) type, location.add(dx, dy, dz), count, 0, 0, 0, speed);
        }

        @Override
        public void spawnFirework(Map<String, Object> data) {
            org.bukkit.plugin.Plugin caller;
            FireworkSafetyListener fsl = io.github.dailystruggle.effectsapi.EffectsAPI.getFireworkSafetyListener();
            if (fsl != null) {
                caller = fsl.caller;
            } else {
                try {
                    caller = io.github.dailystruggle.effectsapi.EffectsAPI.getInstance();
                } catch (IllegalStateException pre) {
                    caller = null;
                }
            }

            if (regionDispatcher.dispatch(caller, location, () -> BukkitHandles.spawnFirework(location, data))) {
                return;
            }
            if (!org.bukkit.Bukkit.isPrimaryThread()) {
                org.bukkit.Bukkit.getScheduler().runTask(caller, () -> BukkitHandles.spawnFirework(location, data));
                return;
            }
            BukkitHandles.spawnFirework(location, data);
        }

        @Override
        public void playNote(Object instrument, int tone) {
            World world = location.getWorld();
            if (world != null) {
                for (Player player : world.getPlayers()) {
                    if (player.getLocation().distanceSquared(location) < 1600) { // 40 blocks
                        player.playNote(location, (Instrument) instrument, new Note(tone));
                    }
                }
            }
        }

        @Override
        public String toString() {
            return "BukkitLocationHandle{world=" + worldName() + ", x=" + x() + ", y=" + y() + ", z=" + z() + "}";
        }
    }

    private static void spawnFirework(Location location, Map<String, Object> data) {
        World world = location.getWorld();
        if (world == null) return;

        int numFireworks = 1;
        double dx = 0, dy = 1, dz = 0;
        Color color = Color.WHITE, fade = Color.WHITE;
        boolean flicker = false, trail = false, safe = true;

        Object o = data.get("NUMBER");
        if (o instanceof Number) numFireworks = ((Number) o).intValue();

        o = data.get("DX");
        if (o instanceof Number) dx = ((Number) o).doubleValue();
        o = data.get("DY");
        if (o instanceof Number) dy = ((Number) o).doubleValue();
        o = data.get("DZ");
        if (o instanceof Number) dz = ((Number) o).doubleValue();

        o = data.get("COLOR");
        if (o instanceof Color) color = (Color) o;

        o = data.get("FADE");
        if (o instanceof Color) fade = (Color) o;

        o = data.get("FLICKER");
        if (o instanceof Boolean) flicker = (Boolean) o;

        o = data.get("TRAIL");
        if (o instanceof Boolean) trail = (Boolean) o;

        o = data.get("SAFE");
        if (o instanceof Boolean) safe = (Boolean) o;

        Firework f = (Firework) world.spawnEntity(location.clone().add(0, 1, 0), EntityType.FIREWORK);
        if (dx != 0.0 || dy != 1.0 || dz != 0.0) {
            f.setShotAtAngle(true);
            f.setVelocity(new Vector(dx, dy, dz));
        }
        FireworkMeta fwm = f.getFireworkMeta();

        FireworkEffect fireworkEffect = FireworkEffect.builder()
                .with((FireworkEffect.Type) data.get("TYPE"))
                .withColor(color)
                .withFade(fade)
                .flicker(flicker)
                .trail(trail)
                .build();
        fwm.addEffect(fireworkEffect);
        fwm.setPower((Integer) data.get("POWER"));
        f.setFireworkMeta(fwm);

        if (numFireworks > 1) FireworkSafetyListener.addFirework(f.getEntityId(), numFireworks, safe);
        if (fwm.getPower() == 0) f.detonate();
    }
}
