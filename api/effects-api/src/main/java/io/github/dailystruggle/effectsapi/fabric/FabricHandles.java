package io.github.dailystruggle.effectsapi.fabric;

import io.github.dailystruggle.effectsapi.common.spi.HandleProvider;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.effectsapi.common.spi.LocationHandle;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;
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

    public static PlayerHandle wrap(@NotNull ServerPlayer player) {
        return new PlayerWrapper(player);
    }

    public static LocationHandle wrap(@NotNull Vec3 location, @NotNull String worldName) {
        return new LocationWrapper(location, worldName);
    }

    public static LocationHandle wrapLocation(@NotNull ServerPlayer player) {
        return new LocationWrapper(player.position(), player.serverLevel().dimension().location().toString());
    }

    /**
     * Extracts a Fabric {@link Vec3} from a target object.
     */
    public static @Nullable Vec3 unwrapLocation(@Nullable Object target) {
        if (target instanceof LocationHandle lh) {
            return (Vec3) lh.platformLocation();
        }
        if (target instanceof PlayerWrapper pw) {
            return pw.player().position();
        }
        if (target instanceof ServerPlayer) {
            return ((ServerPlayer) target).position();
        }
        if (target instanceof Vec3) {
            return (Vec3) target;
        }
        return null;
    }

    private record PlayerWrapper(@NotNull ServerPlayer player) implements PlayerHandle {
        @Override
        public @NotNull UUID uuid() {
            return player.getUUID();
        }

        @Override
        public @NotNull String name() {
            return player.getGameProfile().getName();
        }

        @Override
        public void playSound(Object type, float volume, float pitch, double dx, double dy, double dz) {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                    net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder((SoundEvent) type),
                    SoundSource.PLAYERS,
                    player.getX() + dx, player.getY() + dy, player.getZ() + dz,
                    volume, pitch, player.getRandom().nextLong()
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
            player.addEffect(new MobEffectInstance(
                    net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.wrapAsHolder((MobEffect) type),
                    duration, amplifier, ambient, particles, icon));
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
        public void spawnFirework(Map<String, Object> data) {
            // Firework implementation for Fabric
        }

        @Override
        public void startGlide(int relativeLift, int maxY, int landingTimeoutTicks,
                               boolean allowFireworks, boolean placeOnShutdown, String platformMaterial) {
            ServerLevel level = player.serverLevel();
            double toY = Math.min(player.getY() + relativeLift, maxY);
            try {
                player.teleportTo(level, player.getX(), toY, player.getZ(),
                        player.getYRot(), player.getXRot());
                player.startFallFlying(); // begin gliding
                player.hurtMarked = true;
            } catch (Throwable t) {
                return;
            }

            // Arm a landing watchdog on the server thread. Integer.MAX_VALUE is
            // treated as "no timeout" - skip scheduling entirely.
            if (landingTimeoutTicks > 0 && landingTimeoutTicks < Integer.MAX_VALUE) {
                final UUID id = player.getUUID();
                net.minecraft.server.MinecraftServer server = player.getServer();
                if (server != null) {
                    final long deadline = server.getTickCount() + landingTimeoutTicks;
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
        }

        @Override
        public void playNote(Object instrument, int tone) {
        }

        @Override
        public String toString() {
            return "FabricLocationHandle{world=" + worldName + ", x=" + x() + ", y=" + y() + ", z=" + z() + "}";
        }
    }
}
