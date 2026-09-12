package io.github.dailystruggle.effectsapi.fabric_unobf;

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
 * Mojmap-unobfuscated counterpart of {@code io.github.dailystruggle.effectsapi.fabric.FabricHandles}.
 *
 * <p>The shared {@code effectsapi.fabric.FabricHandles} lives in the obf
 * {@code :effects-api} module, which Loom remaps to <em>intermediary</em>
 * names (e.g. {@code net.minecraft.server.level.ServerPlayer} -&gt;
 * {@code net.minecraft.class_3222}). Those aliases exist on a 1.20/1.21
 * Fabric runtime (the loader remaps intermediary -&gt; runtime at load) but
 * <em>not</em> on the deobfuscated MC 26.x runtime, so registering that
 * provider and calling {@code wrapPlayer} there raises
 * {@link NoClassDefFoundError} ({@code net/minecraft/class_3222}) the instant
 * a common effect (SOUND / PARTICLE / ...) fires.
 *
 * <p>This class is compiled by the unobf module's Loom plugin with <em>no</em>
 * {@code mappings} step, so its bytecode names Mojang names natively and links
 * cleanly on the deobf 26.x runtime. It is registered with
 * {@link HandleRegistry} by {@link FabricEffectsInitializer#registerAll()} in
 * place of the intermediary {@code FabricHandles}.
 */
public final class FabricHandlesUnobf implements HandleProvider {
    private static final FabricHandlesUnobf INSTANCE = new FabricHandlesUnobf();

    private FabricHandlesUnobf() {}

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

    private static volatile net.minecraft.server.MinecraftServer cachedServer;

    public static void setServer(net.minecraft.server.MinecraftServer server) {
        cachedServer = server;
    }

    @Override
    public void dispatchConsoleCommand(@NotNull String command) {
        net.minecraft.server.MinecraftServer server = cachedServer;
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
        return new LocationWrapper(player.position(),
                ((ServerLevel) player.level()).dimension().identifier().toString());
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
            // authlib's GameProfile is a record on MC 26.1+ (component accessor
            // name()); earlier versions exposed getName(). Mirror the resilient
            // resolution used by FabricRTPPlayerUnobf so this links across drift.
            try {
                return player.getGameProfile().name();
            } catch (NoSuchMethodError | NoSuchFieldError ignored) {
                // record-shape mismatch - fall through.
            } catch (Throwable ignored) {
                // defensive
            }
            return player.getName().getString();
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
            // 26.x targeted overload: 2 booleans (longDistance, overrideLimiter).
            ((ServerLevel) player.level()).sendParticles(player, (ParticleOptions) type, false, false,
                    player.getX() + dx, player.getY() + dy, player.getZ() + dz,
                    count, 0, 0, 0, speed);
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
            // Fabric note playing logic is typically handled via sound packets if no block exists.
        }

        @Override
        public void setGliding(boolean gliding) {
            if (gliding) player.startFallFlying();
            else player.stopFallFlying();
        }

        @Override
        public void performCommand(@NotNull String command) {
            net.minecraft.server.MinecraftServer server = ((ServerLevel) player.level()).getServer();
            if (server != null) {
                server.execute(() -> server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command));
            }
        }

        @Override
        public void spawnFirework(Map<String, Object> data) {
            // Firework implementation for Fabric.
        }

        @Override
        public void startGlide(int relativeLift, int maxY, int landingTimeoutTicks,
                               boolean allowFireworks, boolean placeOnShutdown, String platformMaterial) {
            ServerLevel level = (ServerLevel) player.level();
            double toY = Math.min(player.getY() + relativeLift, maxY);
            try {
                // The 6-arg ServerPlayer#teleportTo(ServerLevel,double,double,
                // double,float,float) is missing on some 26.x patch releases
                // (same intermediary drift worked around in FabricRTPPlayerUnobf);
                // attempt it reflectively, then fall back to a same-dimension
                // packet teleport.
                boolean moved = false;
                try {
                    java.lang.reflect.Method m = ServerPlayer.class.getMethod(
                            "teleportTo", ServerLevel.class,
                            double.class, double.class, double.class, float.class, float.class);
                    m.invoke(player, level, player.getX(), toY, player.getZ(),
                            player.getYRot(), player.getXRot());
                    moved = true;
                } catch (NoSuchMethodException ignored) {
                    // fall through to packet teleport
                }
                if (!moved && ((ServerLevel) player.level()) == level) {
                    player.connection.teleport(player.getX(), toY, player.getZ(),
                            player.getYRot(), player.getXRot());
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
                net.minecraft.server.MinecraftServer server = ((ServerLevel) player.level()).getServer();
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
