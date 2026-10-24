package io.github.dailystruggle.rtp.neoforge.effects.local;

import io.github.dailystruggle.effectsapi.common.effects.GlideKeepAlive;
import io.github.dailystruggle.effectsapi.common.spi.HandleProvider;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.effectsapi.common.spi.LocationHandle;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.neoforge.player.NeoForgeRTPPlayer;
import io.github.dailystruggle.rtp.neoforge.tools.NeoForgeResourceIds;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * NeoForge implementation of {@link HandleProvider} and {@link PlayerHandle}
 * (effects-api-ADR-007).
 */
public final class NeoForgeHandles implements HandleProvider {
    private static final NeoForgeHandles INSTANCE = new NeoForgeHandles();

    private NeoForgeHandles() {}

    public static void register() {
        HandleRegistry.setProvider(INSTANCE);
    }

    @Override
    public @Nullable PlayerHandle wrapPlayer(@NotNull Object player) {
        if (player instanceof ServerPlayer sp) return new PlayerWrapper(sp);
        return null;
    }

    @Override
    public @Nullable LocationHandle wrapLocation(@NotNull Object location) {
        if (location instanceof Vec3 vec) return new LocationWrapper(vec, "unknown");
        if (location instanceof ServerPlayer sp) return wrapLocation(sp);
        return null;
    }

    @SuppressWarnings("java:S3077") // Volatile reference publication for server handle
    private static volatile MinecraftServer cachedServer;

    /** Binds the server used by {@link #execute}; pass {@code null} on shutdown. */
    public static void setServer(@Nullable MinecraftServer server) {
        cachedServer = server;
    }

    /**
     * Effect scheduler chokepoint: enqueues {@code task} on the server tick
     * thread via {@link MinecraftServer#execute} (S-005: never blocks, no chunk
     * I/O). Before server start or after shutdown the task is dropped.
     */
    public static void execute(@NotNull Runnable task) {
        MinecraftServer server = cachedServer;
        if (server == null) {
            RTP.log(Level.FINER, "[RTP][NeoForge] effect scheduled with no bound server; task dropped.");
            return;
        }
        server.execute(task);
    }

    @Override
    public void dispatchConsoleCommand(@NotNull String command) {
        MinecraftServer server = cachedServer;
        if (server != null) {
            server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
        }
    }

    @Override
    public void dispatchPlayerCommand(@NotNull PlayerHandle player, @NotNull String command) {
        player.performCommand(command);
    }

    public static PlayerHandle wrap(@NotNull ServerPlayer player) {
        return new PlayerWrapper(player);
    }

    public static LocationHandle wrap(@NotNull Vec3 location, @NotNull String worldName) {
        return new LocationWrapper(location, worldName);
    }

    public static LocationHandle wrapLocation(@NotNull ServerPlayer player) {
        return new LocationWrapper(new Vec3(player.getX(), player.getY(), player.getZ()), worldNameOf(player));
    }

    /*
     * This carrier links against 1.21.1 but also serves 1.21.11, where ServerPlayer#serverLevel(),
     * Entity#getServer(), GameProfile#getName() and ResourceKey#location() no longer exist. A
     * NoSuchMethodError from an effect escapes the tick loop and stops the server, so every
     * drift-prone call below goes through the version-robust helpers.
     */

    /** Dimension id ({@code namespace:path}) of the player's level, or {@code "unknown"}. */
    static @NotNull String worldNameOf(@NotNull ServerPlayer player) {
        ServerLevel level = NeoForgeRTPPlayer.resolveServerLevel(player);
        String id = level == null ? null : NeoForgeResourceIds.locationString(level.dimension());
        return id != null ? id : "unknown";
    }

    /** Bound server, else the player's level server. */
    static @Nullable MinecraftServer serverOf(@NotNull ServerPlayer player) {
        MinecraftServer server = cachedServer;
        if (server != null) return server;
        ServerLevel level = NeoForgeRTPPlayer.resolveServerLevel(player);
        return level == null ? null : level.getServer();
    }

    /**
     * {@code Entity#kill()} became {@code kill(ServerLevel)} in 1.21.2. The runtime is Mojmap, so
     * the new overload resolves by name; vanilla {@code /kill <uuid>} is the last resort.
     */
    static void killPlayer(@NotNull ServerPlayer player, @Nullable MinecraftServer server) {
        try {
            player.kill();
            return;
        } catch (LinkageError drift) {
            RTP.log(Level.FINE, "[RTP][NeoForge] Entity#kill() unavailable on this runtime: " + drift);
        }
        ServerLevel level = NeoForgeRTPPlayer.resolveServerLevel(player);
        if (level != null) {
            try {
                player.getClass().getMethod("kill", ServerLevel.class).invoke(player, level);
                return;
            } catch (ReflectiveOperationException | RuntimeException e) {
                RTP.log(Level.FINE, "[RTP][NeoForge] reflective kill(ServerLevel) failed: " + e);
            }
        }
        if (server == null) {
            RTP.log(Level.WARNING, "[RTP][NeoForge] DEATH effect dropped for " + player.getUUID() + " (no server bound)");
            return;
        }
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), "kill " + player.getUUID());
    }

    /** Matches {@code worldName} against loaded dimension ids; falls back to the player's level. */
    private static @Nullable ResourceKey<net.minecraft.world.level.Level> resolveDimension(
            @NotNull ServerPlayer player, @Nullable MinecraftServer server, @NotNull String worldName) {
        if (server != null) {
            for (ServerLevel sl : server.getAllLevels()) {
                if (worldName.equals(NeoForgeResourceIds.locationString(sl.dimension()))) {
                    return sl.dimension();
                }
            }
        }
        ServerLevel own = NeoForgeRTPPlayer.resolveServerLevel(player);
        return own == null ? null : own.dimension();
    }

    private record LocationWrapper(@NotNull Vec3 location, @NotNull String worldName) implements LocationHandle {
        @Override
        public int x() {
            return (int) Math.floor(location.x);
        }

        @Override
        public int y() {
            return (int) Math.floor(location.y);
        }

        @Override
        public int z() {
            return (int) Math.floor(location.z);
        }

        @Override
        public double doubleX() {
            return location.x;
        }

        @Override
        public double doubleY() {
            return location.y;
        }

        @Override
        public double doubleZ() {
            return location.z;
        }

        @Override
        public @NotNull String worldName() {
            return worldName;
        }

        @Override
        public @NotNull Object platformLocation() {
            return location;
        }

        @Override
        public void playSound(Object type, float volume, float pitch) {
            // No-op fallback on common carrier; version adapters provide concrete playback
        }

        @Override
        public void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed) {
            // No-op fallback on common carrier; version adapters provide concrete particle spawn
        }

        @Override
        public void spawnFirework(Map<String, Object> data) {
            // No-op fallback on common carrier; version adapters provide concrete firework spawn
        }

        @Override
        public void playNote(Object instrument, int tone) {
            // No-op fallback on common carrier; version adapters provide concrete note playback
        }

        @Override
        public String toString() {
            return "NeoForgeLocationHandle{world=" + worldName + ", x=" + x() + ", y=" + y() + ", z=" + z() + "}";
        }
    }

    private record PlayerWrapper(@NotNull ServerPlayer player) implements PlayerHandle {
        @Override
        public @NotNull UUID uuid() {
            return player.getUUID();
        }

        @Override
        public @NotNull String name() {
            return NeoForgeRTPPlayer.resolveName(player);
        }

        @Override
        public void playSound(Object type, float volume, float pitch, double dx, double dy, double dz) {
            // No-op fallback on common carrier; version adapters provide concrete playback
        }

        @Override
        public void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed) {
            // No-op fallback on common carrier; version adapters provide concrete particle spawn
        }

        @Override
        public void applyPotionEffect(Object type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {
            // No-op fallback on common carrier; version adapters provide concrete potion effect application
        }

        @Override
        public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
            // No-op fallback on common carrier; version adapters provide concrete title send
        }

        @Override
        public void playNote(Object instrument, int tone) {
            // No-op fallback on common carrier; version adapters provide concrete note playback
        }

        @Override
        public void setGliding(boolean gliding) {
            if (gliding) player.startFallFlying();
            else player.stopFallFlying();
        }

        @Override
        public void performCommand(@NotNull String command) {
            MinecraftServer server = serverOf(player);
            if (server == null) {
                RTP.log(Level.WARNING, "[RTP][NeoForge] player command effect dropped (no server): " + command);
                return;
            }
            // NeoForgeRTPPlayer builds the CommandSourceStack with drift fallbacks.
            NeoForgeRTPPlayer sender = new NeoForgeRTPPlayer(player);
            server.execute(() -> sender.performCommand(null, command));
        }

        @Override
        public void spawnFirework(Map<String, Object> data) {
            // No-op fallback on common carrier; version adapters provide concrete firework spawn
        }

        /**
         * Lifts the player and starts a glide that {@link GlideKeepAlive} holds until landing
         * (vanilla drops the flag next tick without an elytra). {@code allowFireworks},
         * {@code placeOnShutdown} and {@code platformMaterial} have no NeoForge implementation.
         */
        @Override
        public void startGlide(int relativeLift, int maxY, int landingTimeoutTicks,
                               boolean allowFireworks, boolean placeOnShutdown, String platformMaterial) {
            onServer("GLIDE", () -> {
                ServerLevel level = NeoForgeRTPPlayer.resolveServerLevel(player);
                if (level == null) {
                    RTP.log(Level.WARNING, "[RTP][NeoForge] GLIDE dropped for " + player.getUUID() + " (no level)");
                    return;
                }
                double toY = Math.min(player.getY() + relativeLift, maxY);
                if (!NeoForgeRTPPlayer.performTeleport(player, level, player.getX(), toY, player.getZ(),
                        player.getYRot(), player.getXRot())) {
                    RTP.log(Level.WARNING, "[RTP][NeoForge] GLIDE lift failed for " + player.getUUID());
                    return;
                }
                player.startFallFlying();
                GlideKeepAlive.track(new NeoForgeGlider(player), landingTimeoutTicks);
            });
        }

        /** Drops the inventory at the player (as on Fabric); {@code dropLocation} is not used. */
        @Override
        public void dropInventory(@Nullable LocationHandle dropLocation) {
            onServer("DROP_INVENTORY", () -> player.getInventory().dropAll());
        }

        /** Vanilla death formula {@code min(level * 7, 100)}, then clears level and points. */
        @Override
        public void dropExperience(@Nullable LocationHandle dropLocation) {
            onServer("DROP_EXP", () -> {
                int points = Math.min(player.experienceLevel * 7, 100);
                ServerLevel level = NeoForgeRTPPlayer.resolveServerLevel(player);
                if (points > 0 && level != null) {
                    Vec3 pos = dropLocation != null
                            ? new Vec3(dropLocation.doubleX(), dropLocation.doubleY(), dropLocation.doubleZ())
                            : new Vec3(player.getX(), player.getY(), player.getZ());
                    net.minecraft.world.entity.ExperienceOrb.award(level, pos, points);
                }
                player.experienceLevel = 0;
                player.experienceProgress = 0.0f;
                player.totalExperience = 0;
            });
        }

        /** Runs {@code action} on the server thread (inline when no server is bound); failures are logged. */
        private void onServer(@NotNull String effect, @NotNull Runnable action) {
            Runnable task = () -> {
                try {
                    action.run();
                } catch (RuntimeException | LinkageError t) {
                    RTP.log(Level.WARNING, "[RTP][NeoForge] " + effect + " effect failed for " + player.getUUID(), t);
                }
            };
            // The running server is borrowed, never owned here.
            //noinspection resource
            MinecraftServer server = serverOf(player);
            if (server != null) {
                server.execute(task);
            } else {
                task.run();
            }
        }

        @Override
        public void kill(boolean setBed, @Nullable LocationHandle respawnLocation) {
            MinecraftServer server = serverOf(player);
            Runnable task = () -> {
                try {
                    if (respawnLocation != null) {
                        try {
                            ResourceKey<net.minecraft.world.level.Level> targetDim =
                                    resolveDimension(player, server, respawnLocation.worldName());
                            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                                    respawnLocation.x(), respawnLocation.y(), respawnLocation.z());
                            for (java.lang.reflect.Method m : ServerPlayer.class.getMethods()) {
                                if (targetDim == null || !m.getName().equals("setRespawnPosition")) continue;
                                Class<?>[] pt = m.getParameterTypes();
                                if (pt.length == 5) {
                                    m.invoke(player, targetDim, pos, 0.0f, setBed, false);
                                    break;
                                }
                            }
                        } catch (Throwable t) {
                            RTP.log(Level.FINE, "[RTP][NeoForge] DEATH respawn point not set: " + t);
                        }
                    }
                    killPlayer(player, server);
                } catch (Throwable t) {
                    RTP.log(Level.WARNING, "[RTP][NeoForge] DEATH effect failed for " + player.getUUID(), t);
                }
            };
            if (server != null) {
                server.execute(task);
            } else {
                task.run();
            }
        }

        @Override
        public void kill() {
            kill(true, null);
        }

        @Override
        public String toString() {
            return "NeoForgePlayerHandle{name=" + name() + ", uuid=" + uuid() + "}";
        }
    }
}
