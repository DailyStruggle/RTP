package io.github.dailystruggle.rtp.neoforge.effects.local;

import io.github.dailystruggle.effectsapi.common.spi.HandleProvider;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.effectsapi.common.spi.LocationHandle;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

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

    private static volatile MinecraftServer cachedServer;

    public static void setServer(MinecraftServer server) {
        cachedServer = server;
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

    public static LocationHandle wrapLocation(@NotNull ServerPlayer player) {
        return new LocationWrapper(player.position(), player.serverLevel().dimension().location().toString());
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
        }

        @Override
        public void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed) {
        }

        @Override
        public void spawnFirework(Map<String, Object> data) {
        }

        @Override
        public void playNote(Object instrument, int tone) {
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
            return player.getGameProfile().getName();
        }

        @Override
        public void playSound(Object type, float volume, float pitch, double dx, double dy, double dz) {
        }

        @Override
        public void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed) {
        }

        @Override
        public void applyPotionEffect(Object type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {
        }

        @Override
        public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        }

        @Override
        public void playNote(Object instrument, int tone) {
        }

        @Override
        public void setGliding(boolean gliding) {
            if (gliding) player.startFallFlying();
            else player.stopFallFlying();
        }

        @Override
        public void performCommand(@NotNull String command) {
            MinecraftServer server = player.getServer();
            if (server != null) {
                server.execute(() -> server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command));
            }
        }

        @Override
        public void spawnFirework(Map<String, Object> data) {
        }

        @Override
        public void startGlide(int relativeLift, int maxY, int landingTimeoutTicks,
                               boolean allowFireworks, boolean placeOnShutdown, String platformMaterial) {
        }

        @Override
        public String toString() {
            return "NeoForgePlayerHandle{name=" + name() + ", uuid=" + uuid() + "}";
        }
    }
}
