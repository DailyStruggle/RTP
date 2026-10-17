package io.github.dailystruggle.rtp.fabric.effects;

import io.github.dailystruggle.effectsapi.common.spi.HandleProvider;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.effectsapi.common.spi.LocationHandle;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter;
import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;

import java.util.UUID;
import java.util.Map;
import java.util.logging.Level;

/**
 * Fabric implementations of {@link PlayerHandle} and {@link LocationHandle}.
 */
public final class FabricHandles implements HandleProvider {
    private static final FabricHandles INSTANCE = new FabricHandles();

    private FabricHandles() {}

    public static void register() {
        HandleRegistry.setProvider(INSTANCE);
    }

    @Override
    public @Nullable PlayerHandle wrapPlayer(@NotNull Object player) {
        if (player instanceof ServerPlayer) return new PlayerWrapper((ServerPlayer) player);
        return null;
    }

    @Override
    public @Nullable LocationHandle wrapLocation(@NotNull Object location) {
        if (location instanceof Vec3) return new LocationWrapper((Vec3) location, "unknown");
        if (location instanceof ServerPlayer) return wrapLocation((ServerPlayer) location);
        return null;
    }

    @SuppressWarnings("java:S3077") // Volatile reference publication for server handle
    private static volatile net.minecraft.server.MinecraftServer cachedServer;

    public static void setServer(net.minecraft.server.MinecraftServer server) {
        cachedServer = server;
    }

    /**
     * Server for effect dispatch. {@link FabricEffectRuntime} is bound on SERVER_STARTED and
     * unbound on SERVER_STOPPED; {@link #setServer} is an explicit override (tests).
     */
    static @Nullable net.minecraft.server.MinecraftServer server() {
        net.minecraft.server.MinecraftServer s = FabricEffectRuntime.server();
        return s != null ? s : cachedServer;
    }

    @Override
    public void dispatchConsoleCommand(@NotNull String command) {
        net.minecraft.server.MinecraftServer server = server();
        if (server == null) {
            RTP.log(Level.WARNING, "[RTP][Fabric] console command effect dropped (no server bound): " + command);
            return;
        }
        server.execute(() -> {
            FabricVersionAdapter adapter = FabricVersionAdapterRegistry.peek();
            if (adapter != null) {
                try {
                    if (adapter.dispatchConsoleCommand(server, command)) {
                        return;
                    }
                } catch (Throwable ignored) {
                    // adapter hook failed; fall back to the vanilla dispatcher below
                }
            }
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        });
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
        return new LocationWrapper(positionOf(player), player.serverLevel().dimension().location().toString());
    }

    /**
     * Player position via {@code getX/getY/getZ}. This code links against 1.21.1, and
     * {@code Entity#position()} ({@code method_19538}) does not exist on 1.21.11; the
     * resulting {@link NoSuchMethodError} escapes the tick loop and stops the server.
     */
    public static Vec3 positionOf(@NotNull ServerPlayer player) {
        return new Vec3(player.getX(), player.getY(), player.getZ());
    }

    /**
     * Extracts a Fabric {@link Vec3} from a target object.
     */
    public static @Nullable Vec3 unwrapLocation(@Nullable Object target) {
        if (target instanceof LocationHandle lh) {
            return (Vec3) lh.platformLocation();
        }
        if (target instanceof PlayerWrapper pw) {
            return positionOf(pw.player());
        }
        if (target instanceof ServerPlayer) {
            return positionOf((ServerPlayer) target);
        }
        if (target instanceof Vec3) {
            return (Vec3) target;
        }
        return null;
    }

    private static volatile java.lang.reflect.Constructor<?> mobEffectInstanceCtor;

    /**
     * The 6-arg {@code MobEffectInstance} constructor takes a {@code MobEffect} on 1.20.1 and a
     * {@code Holder<MobEffect>} on 1.21+; it is picked by parameter shape (Loom remaps names,
     * not reflective lookups) so this common code links on both carriers (ADR-113).
     */
    static MobEffectInstance newMobEffectInstance(MobEffect effect, int duration, int amplifier,
                                                  boolean ambient, boolean particles, boolean icon) {
        try {
            java.lang.reflect.Constructor<?> ctor = mobEffectInstanceCtor;
            if (ctor == null) {
                for (java.lang.reflect.Constructor<?> c : MobEffectInstance.class.getConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 6 && p[1] == int.class && p[2] == int.class
                            && p[3] == boolean.class && p[4] == boolean.class && p[5] == boolean.class) {
                        ctor = c;
                        break;
                    }
                }
                if (ctor == null) {
                    throw new NoSuchMethodException("MobEffectInstance(effect, int, int, boolean, boolean, boolean)");
                }
                mobEffectInstanceCtor = ctor;
            }
            Object first = ctor.getParameterTypes()[0].isInstance(effect)
                    ? effect
                    : net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect);
            return (MobEffectInstance) ctor.newInstance(first, duration, amplifier, ambient, particles, icon);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("[RTP][Fabric] no usable MobEffectInstance constructor", e);
        }
    }

    /**
     * Common code links {@code kill()V} (1.21.1); 1.21.2+ only has {@code kill(ServerLevel)},
     * and reflection by name fails under intermediary. Fall back to vanilla {@code /kill <uuid>},
     * which runs the full death path on every version.
     */
    static void killPlayer(@NotNull ServerPlayer player, @Nullable net.minecraft.server.MinecraftServer server) {
        try {
            player.kill();
            return;
        } catch (LinkageError drift) {
            RTP.log(Level.FINE, "[RTP][Fabric] Entity#kill() unavailable on this runtime; using /kill: " + drift);
        }
        if (server == null) {
            RTP.log(Level.WARNING, "[RTP][Fabric] DEATH effect dropped for " + player.getUUID() + " (no server bound)");
            return;
        }
        String command = "kill " + player.getUUID();
        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
            return;
        } catch (LinkageError drift) {
            RTP.log(Level.FINE, "[RTP][Fabric] common /kill dispatch did not link; using adapter: " + drift);
        }
        FabricVersionAdapter adapter = FabricVersionAdapterRegistry.peek();
        if (adapter == null || !adapter.dispatchConsoleCommand(server, command)) {
            RTP.log(Level.WARNING, "[RTP][Fabric] DEATH effect could not kill " + player.getUUID());
        }
    }

    private record PlayerWrapper(@NotNull ServerPlayer player) implements PlayerHandle {
        @Override
        public @NotNull UUID uuid() {
            return player.getUUID();
        }

        @Override
        public @NotNull String name() {
            try {
                if (player.getGameProfile() != null) {
                    java.lang.reflect.Method m = player.getGameProfile().getClass().getMethod("name");
                    Object val = m.invoke(player.getGameProfile());
                    if (val instanceof String s) return s;
                }
            } catch (Throwable ignored) {
                // record-style GameProfile#name() absent; try getName()
            }
            try {
                if (player.getGameProfile() != null) {
                    return player.getGameProfile().getName();
                }
            } catch (LinkageError ignored) {
                // getName() removed on newer authlib; try display name
            }
            try {
                return player.getName().getString();
            } catch (Throwable ignored) {}
            return player.getUUID().toString();
        }

        @Override
        public void playSound(Object type, float volume, float pitch, double dx, double dy, double dz) {
            // Entity#getRandom() (method_59922) exists only from 1.21; on 1.20.x it throws
            // NoSuchMethodError and crashes the server. ThreadLocalRandom provides the random
            // variation seed safely on all MC versions without touching entity/level methods.
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                    net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder((SoundEvent) type),
                    SoundSource.PLAYERS,
                    player.getX() + dx, player.getY() + dy, player.getZ() + dz,
                    volume, pitch, java.util.concurrent.ThreadLocalRandom.current().nextLong()
            ));
        }

        @Override
        public void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed) {
            ServerLevel level = player.serverLevel();
            double px = player.getX() + dx, py = player.getY() + dy, pz = player.getZ() + dz;
            // ServerLevel#sendParticles drifted across runtimes: 1.20.x/early 1.21.x
            // expose a single-boolean (overrideLimiter) overload, while later 1.21.x
            // (e.g. 1.21.11) replaced it with a two-boolean (longDistance,
            // overrideLimiter) overload. Try the single-boolean form first, then
            // fall back to the two-boolean form so this links on both.
            try {
                level.sendParticles(player, (ParticleOptions) type, true,
                        px, py, pz, count, 0, 0, 0, speed);
            } catch (NoSuchMethodError single) {
                // The two-boolean overload is absent from this carrier's compile
                // mappings, and reflective name lookup is unreliable because Loom
                // remaps Mojmap names to intermediary (so the runtime name is not
                // "sendParticles"). Locate the overload by its parameter shape
                // instead: (ServerPlayer, ParticleOptions, boolean, boolean,
                // double, double, double, int, double, double, double, double).
                java.lang.reflect.Method target = null;
                for (java.lang.reflect.Method m : ServerLevel.class.getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 12
                            && ServerPlayer.class.isAssignableFrom(p[0])
                            && p[1].isAssignableFrom(ParticleOptions.class)
                            && p[2] == boolean.class && p[3] == boolean.class
                            && p[4] == double.class && p[5] == double.class && p[6] == double.class
                            && p[7] == int.class
                            && p[8] == double.class && p[9] == double.class
                            && p[10] == double.class && p[11] == double.class) {
                        target = m;
                        break;
                    }
                }
                if (target == null) {
                    throw single;
                }
                try {
                    target.invoke(level, player, type, true, false,
                            px, py, pz, count, 0.0, 0.0, 0.0, speed);
                } catch (ReflectiveOperationException reflective) {
                    throw single;
                }
            }
        }

        @Override
        public void applyPotionEffect(Object type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {
            player.addEffect(newMobEffectInstance((MobEffect) type, duration, amplifier, ambient, particles, icon));
        }

        @Override
        public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
            player.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
            if (title != null && !title.isEmpty()) {
                player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(title)));
            }
            if (subtitle != null && !subtitle.isEmpty()) {
                player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
            }
        }

        @Override
        public void playNote(Object instrument, int tone) {
            // Fabric note playing logic is typically handled via sound packets if no block exists
            // For now, we leave it empty or implement via sound if instrument is mapped.
        }

        @Override
        public void setGliding(boolean gliding) {
            if (gliding) player.startFallFlying();
            else player.stopFallFlying();
        }

        @Override
        public void dropInventory(@Nullable LocationHandle dropLocation) {
            net.minecraft.server.MinecraftServer server = server();
            Runnable task = () -> {
                try {
                    player.getInventory().dropAll();
                } catch (Throwable ignored) {}
            };
            if (server != null) {
                server.execute(task);
            } else {
                task.run();
            }
        }

        @Override
        public void dropExperience(@Nullable LocationHandle dropLocation) {
            net.minecraft.server.MinecraftServer server = server();
            Runnable task = () -> {
                try {
                    int pointsToDrop = Math.min(player.experienceLevel * 7, 100);
                    if (pointsToDrop > 0) {
                        net.minecraft.world.phys.Vec3 pos = positionOf(player);
                        if (dropLocation != null) {
                            pos = new net.minecraft.world.phys.Vec3(dropLocation.doubleX(), dropLocation.doubleY(), dropLocation.doubleZ());
                        }
                        net.minecraft.world.entity.ExperienceOrb.award(player.serverLevel(), pos, pointsToDrop);
                    }
                    player.experienceLevel = 0;
                    player.experienceProgress = 0.0f;
                    player.totalExperience = 0;
                } catch (Throwable ignored) {}
            };
            if (server != null) {
                server.execute(task);
            } else {
                task.run();
            }
        }

        @Override
        public void kill(boolean setBed, @Nullable LocationHandle respawnLocation) {
            final net.minecraft.server.MinecraftServer srv = server();
            Runnable task = () -> {
                try {
                    if (respawnLocation != null) {
                        try {
                            ServerLevel targetLevel = player.serverLevel();
                            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> targetDim = targetLevel.dimension();
                            if (srv != null) {
                                for (ServerLevel sl : srv.getAllLevels()) {
                                    if (sl.dimension().location().toString().equals(respawnLocation.worldName())) {
                                        targetDim = sl.dimension();
                                        break;
                                    }
                                }
                            }
                            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                                    respawnLocation.x(), respawnLocation.y(), respawnLocation.z());
                            for (java.lang.reflect.Method m : ServerPlayer.class.getMethods()) {
                                if (!m.getName().equals("setRespawnPosition")) continue;
                                Class<?>[] pt = m.getParameterTypes();
                                if (pt.length == 5) {
                                    m.invoke(player, targetDim, pos, 0.0f, setBed, false);
                                    break;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                    killPlayer(player, srv);
                } catch (Throwable t) {
                    RTP.log(Level.WARNING, "[RTP][Fabric] DEATH effect failed for " + player.getUUID(), t);
                }
            };
            if (srv != null) {
                srv.execute(task);
            } else {
                task.run();
            }
        }

        @Override
        public void kill() {
            kill(true, null);
        }

        @Override
        public void performCommand(@NotNull String command) {
            net.minecraft.server.MinecraftServer server = server();
            if (server == null) {
                RTP.log(Level.WARNING, "[RTP][Fabric] player command effect dropped (no server): " + command);
                return;
            }
            server.execute(() -> {
                // Carrier-typed dispatch first: the common performPrefixedCommand call is
                // linked as void and throws NoSuchMethodError on 1.20.1 (returns int there).
                FabricVersionAdapter adapter = FabricVersionAdapterRegistry.peek();
                if (adapter != null) {
                    try {
                        if (adapter.dispatchPlayerCommand(player, command)) return;
                    } catch (Throwable t) {
                        RTP.log(Level.FINE, "[RTP][Fabric] adapter dispatchPlayerCommand failed: " + t);
                    }
                }
                // Try 0-arg method returning CommandSourceStack reflectively.
                // On 1.21.11 intermediary, this is method_64396; on 1.21.1 it is method_5671; on 26.x it is createCommandSourceStack.
                for (java.lang.reflect.Method m : player.getClass().getMethods()) {
                    if (m.getParameterCount() == 0 && net.minecraft.commands.CommandSourceStack.class.isAssignableFrom(m.getReturnType())) {
                        try {
                            Object src = m.invoke(player);
                            if (src instanceof net.minecraft.commands.CommandSourceStack css) {
                                server.getCommands().performPrefixedCommand(css, command);
                                return;
                            }
                        } catch (Throwable ignored) {
                            // candidate accessor failed; try the next one
                        }
                    }
                }
                // Direct bytecode call (compiled against 1.21.1 intermediary method_5671)
                try {
                    server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
                    return;
                } catch (Throwable ignored) {
                    // linkage drift; fall through to the console-anchored source
                }
                // Re-anchor console source on player (stable fallback)
                try {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withEntity(player), command);
                } catch (Throwable t) {
                    RTP.log(Level.WARNING, "[RTP][Fabric] player command effect failed: '" + command + "': " + t);
                }
            });
        }

        @Override
        public void spawnFirework(Map<String, Object> data) {
            // Firework implementation for Fabric
        }

        @Override
        public void startGlide(int relativeLift, int maxY, int landingTimeoutTicks,
                               boolean allowFireworks, boolean placeOnShutdown, String platformMaterial) {
            ServerLevel level = player.serverLevel();
            double toY = Math.min(player.getY() + relativeLift, maxY);
            try {
                if (!teleportGlide(player, level, player.getX(), toY, player.getZ(),
                        player.getYRot(), player.getXRot())) {
                    return;
                }
                player.startFallFlying(); // begin gliding
                player.hurtMarked = true;
            } catch (Throwable t) {
                return;
            }

            // Arm a landing watchdog on the server thread. Integer.MAX_VALUE is
            // treated as "no timeout" - skip scheduling entirely.
            if (landingTimeoutTicks > 0 && landingTimeoutTicks < Integer.MAX_VALUE) {
                final UUID id = player.getUUID();
                net.minecraft.server.MinecraftServer server = player.serverLevel().getServer();
                final long deadline = (long) server.getTickCount() + landingTimeoutTicks;
                final Runnable[] watchdog = new Runnable[1];
                watchdog[0] = () -> {
                    if (server.getTickCount() >= deadline) {
                        ServerPlayer p = server.getPlayerList().getPlayer(id);
                        if (p != null && p.isFallFlying()) {
                            p.stopFallFlying(); // force landing
                        }
                    } else {
                        server.execute(watchdog[0]);
                    }
                };
                server.execute(watchdog[0]);
            }
        }

        private static boolean teleportGlide(ServerPlayer player, ServerLevel level,
                                             double x, double y, double z,
                                             float yaw, float pitch) {
            FabricVersionAdapter adapter = FabricVersionAdapterRegistry.peek();
            if (adapter != null) {
                try {
                    if (adapter.teleport(player, level, x, y, z, yaw, pitch)) {
                        return true;
                    }
                } catch (Throwable ignored) {
                    // adapter teleport failed; try the reflective fallbacks
                }
            }
            // Reflective 6-arg teleportTo lookup: level, xyz, yaw, pitch
            try {
                java.lang.reflect.Method m = ServerPlayer.class.getMethod(
                        "teleportTo", ServerLevel.class,
                        double.class, double.class, double.class, float.class, float.class);
                m.invoke(player, level, x, y, z, yaw, pitch);
                return true;
            } catch (Throwable ignored) {
                // name remapped or absent on this runtime
            }
            // Same-dimension connection packet teleport
            try {
                if (player.serverLevel() == level) {
                    player.connection.teleport(x, y, z, yaw, pitch);
                    player.setYRot(yaw);
                    player.setXRot(pitch);
                    return true;
                }
            } catch (Throwable ignored) {
                // packet teleport unavailable; fall back to setPos
            }
            // In-place setPos fallback
            try {
                player.setPos(x, y, z);
                player.setYRot(yaw);
                player.setXRot(pitch);
                return true;
            } catch (Throwable ignored) {
                // all paths failed; caller sees false
            }
            return false;
        }

        @Override
        public String toString() {
            return "FabricPlayerHandle{name=" + name() + ", uuid=" + uuid() + "}";
        }
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
            // Need a way to get the level from worldName.
        }

        @Override
        public void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed) {
            // Need a way to get the level from worldName.
        }

        @Override
        public void spawnFirework(Map<String, Object> data) {
            // No-op fallback; firework spawning handled via version adapters or player handles
        }

        @Override
        public void playNote(Object instrument, int tone) {
            // No-op fallback; note block instrument playback handled via version adapters or player handles
        }

        @Override
        public String toString() {
            return "FabricLocationHandle{world=" + worldName + ", x=" + x() + ", y=" + y() + ", z=" + z() + "}";
        }
    }
}
