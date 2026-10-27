package io.github.dailystruggle.rtp.neoforge.effects.local;

import io.github.dailystruggle.effectsapi.common.EffectFactory;
import io.github.dailystruggle.effectsapi.common.effects.GlideKeepAlive;
import io.github.dailystruggle.effectsapi.common.spi.EffectTarget;
import io.github.dailystruggle.effectsapi.common.spi.HandleProvider;
import io.github.dailystruggle.effectsapi.common.spi.LocationHandle;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.neoforge.player.NeoForgeRTPPlayer;
import io.github.dailystruggle.rtp.neoforge.tools.NeoForgeResourceIds;
import io.github.dailystruggle.server.api.ServerAccessors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * NeoForge implementation of {@link PlayerHandle} and {@link LocationHandle}
 * (effects-api-ADR-007).
 */
@SuppressWarnings("deprecation")
public final class NeoForgeHandles implements HandleProvider {
    private static final NeoForgeHandles INSTANCE = new NeoForgeHandles();

    private NeoForgeHandles() {}

    public static void register() {
        ServerAccessors.setEffectAccessor(INSTANCE);
    }

    @Override
    public @Nullable PlayerHandle wrapPlayer(@NotNull Object player) {
        if (player instanceof ServerPlayer sp) return new PlayerWrapper(sp);
        return null;
    }

    @Override
    public @Nullable LocationHandle wrapLocation(@NotNull Object location) {
        if (location instanceof Vec3 vec) return new LocationWrapper(vec, vec.x, vec.y, vec.z, "unknown");
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
        return new LocationWrapper(location, location.x, location.y, location.z, worldName);
    }

    static LocationHandle wrapCoordinates(double x, double y, double z, @NotNull String worldName) {
        return new LocationWrapper(null, x, y, z, worldName);
    }

    public static LocationHandle wrapLocation(@NotNull ServerPlayer player) {
        return new LocationWrapper(
                new Vec3(player.getX(), player.getY(), player.getZ()),
                player.getX(), player.getY(), player.getZ(),
                worldNameOf(player));
    }

    /**
     * Unwraps a {@link ServerPlayer} from a raw {@link ServerPlayer}, {@link PlayerWrapper},
     * or {@link EffectTarget}.
     */
    public static @Nullable ServerPlayer unwrapPlayer(@Nullable Object target) {
        if (target instanceof ServerPlayer sp) return sp;
        if (target instanceof PlayerWrapper pw) return pw.player();
        if (target instanceof EffectTarget et) return unwrapPlayer(et.player());
        return null;
    }

    /** Resolves a loaded {@link ServerLevel} by dimension id, falling back to the overworld. */
    static @Nullable ServerLevel resolveLevel(@Nullable String worldName) {
        MinecraftServer server = cachedServer;
        if (server == null) return null;
        if (worldName != null && !worldName.isEmpty() && !"unknown".equals(worldName)) {
            for (ServerLevel sl : server.getAllLevels()) {
                if (worldName.equals(NeoForgeResourceIds.locationString(sl.dimension()))) {
                    return sl;
                }
            }
        }
        return server.overworld();
    }

    /** Maps a Bukkit/Minecraft instrument token to its canonical {@code block.note_block.*} sound id. */
    static @NotNull String noteSoundId(@Nullable Object instrument) {
        if (instrument == null) return "minecraft:block.note_block.harp";
        String raw = instrument.toString().trim().toLowerCase(Locale.ROOT);
        return switch (raw) {
            case "piano", "harp" -> "minecraft:block.note_block.harp";
            case "bass_drum", "basedrum" -> "minecraft:block.note_block.basedrum";
            case "snare_drum", "snare" -> "minecraft:block.note_block.snare";
            case "sticks", "hat" -> "minecraft:block.note_block.hat";
            case "bass_guitar", "bass" -> "minecraft:block.note_block.bass";
            case "flute" -> "minecraft:block.note_block.flute";
            case "bell" -> "minecraft:block.note_block.bell";
            case "guitar" -> "minecraft:block.note_block.guitar";
            case "chime" -> "minecraft:block.note_block.chime";
            case "xylophone" -> "minecraft:block.note_block.xylophone";
            case "iron_xylophone" -> "minecraft:block.note_block.iron_xylophone";
            case "cow_bell" -> "minecraft:block.note_block.cow_bell";
            case "didgeridoo" -> "minecraft:block.note_block.didgeridoo";
            case "bit" -> "minecraft:block.note_block.bit";
            case "banjo" -> "minecraft:block.note_block.banjo";
            case "pling" -> "minecraft:block.note_block.pling";
            case "zombie" -> "minecraft:block.note_block.imitate.zombie";
            case "skeleton" -> "minecraft:block.note_block.imitate.skeleton";
            case "creeper" -> "minecraft:block.note_block.imitate.creeper";
            case "dragon", "ender_dragon" -> "minecraft:block.note_block.imitate.ender_dragon";
            case "wither_skeleton" -> "minecraft:block.note_block.imitate.wither_skeleton";
            case "piglin" -> "minecraft:block.note_block.imitate.piglin";
            default -> {
                if (raw.indexOf(':') >= 0) yield raw;
                if (raw.startsWith("block.note_block.")) yield "minecraft:" + raw;
                yield "minecraft:block.note_block." + raw;
            }
        };
    }

    /** Vanilla note-block pitch formula {@code 2^((clamp(tone, 0, 24) - 12) / 12)}. */
    static float notePitch(int tone) {
        int clamped = Math.max(0, Math.min(24, tone));
        return (float) Math.pow(2.0D, (clamped - 12) / 12.0D);
    }

    static @Nullable SoundEvent resolveNoteSound(@Nullable Object instrument) {
        if (instrument instanceof SoundEvent se) return se;
        try {
            return NeoForgeRegistryResolver.resolve(BuiltInRegistries.SOUND_EVENT, noteSoundId(instrument));
        } catch (Throwable ignored) {
            return null;
        }
    }

    static int parseColorRgb(@Nullable Object raw) {
        if (raw instanceof Number n) return n.intValue() & 0xFFFFFF;
        if (raw == null) return 0xFFFFFF;
        String s = raw.toString().trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "WHITE" -> 0xFFFFFF;
            case "SILVER" -> 0xC0C0C0;
            case "GRAY" -> 0x808080;
            case "BLACK" -> 0x000000;
            case "RED" -> 0xFF0000;
            case "MAROON" -> 0x800000;
            case "YELLOW" -> 0xFFFF00;
            case "OLIVE" -> 0x808000;
            case "LIME" -> 0x00FF00;
            case "GREEN" -> 0x008000;
            case "AQUA" -> 0x00FFFF;
            case "TEAL" -> 0x008080;
            case "BLUE" -> 0x0000FF;
            case "NAVY" -> 0x000080;
            case "FUCHSIA" -> 0xFF00FF;
            case "PURPLE" -> 0x800080;
            case "ORANGE" -> 0xFFA500;
            default -> {
                String hex = s.startsWith("#") ? s.substring(1) : s;
                if (hex.length() == 6) {
                    try {
                        yield Integer.parseInt(hex, 16);
                    } catch (NumberFormatException ignored) {
                        yield 0xFFFFFF;
                    }
                }
                yield 0xFFFFFF;
            }
        };
    }

    static @NotNull String parseFireworkShapeName(@Nullable Object raw) {
        if (raw == null) return "SMALL_BALL";
        String s = raw.toString().trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "BALL_LARGE", "LARGE_BALL" -> "LARGE_BALL";
            case "STAR" -> "STAR";
            case "CREEPER" -> "CREEPER";
            case "BURST" -> "BURST";
            default -> "SMALL_BALL";
        };
    }

    /**
     * Spawns firework rocket entities at {@code (x, y + 1, z)} in {@code level} using
     * reflection so {@link NeoForgeHandles} carries no subtype-verification edges against
     * {@code ItemLike}/{@code Level}/{@code Entity} when loaded in unit tests without a
     * Minecraft runtime on the test classpath.
     */
    static boolean spawnFireworkAt(@Nullable ServerLevel level, double x, double y, double z,
                                   @Nullable Map<String, Object> data) {
        if (level == null) {
            EffectFactory.reportUnsupportedPrimitive(
                    "NeoForge", "FIREWORK", "no ServerLevel available for firework spawn");
            return false;
        }
        int numFireworks = 1;
        int power = 0;
        double dx = 0.0, dy = 1.0, dz = 0.0;
        int colorRgb = 0xFFFFFF;
        int fadeRgb = 0xFFFFFF;
        boolean flicker = false;
        boolean trail = false;
        String shapeName = "SMALL_BALL";

        if (data != null) {
            Object o = data.get("NUMBER");
            if (o instanceof Number n) numFireworks = Math.max(1, Math.min(8, n.intValue()));
            o = data.get("POWER");
            if (o instanceof Number n) power = Math.max(0, Math.min(3, n.intValue()));
            o = data.get("DX");
            if (o instanceof Number n) dx = n.doubleValue();
            o = data.get("DY");
            if (o instanceof Number n) dy = n.doubleValue();
            o = data.get("DZ");
            if (o instanceof Number n) dz = n.doubleValue();
            if (data.containsKey("COLOR")) colorRgb = parseColorRgb(data.get("COLOR"));
            if (data.containsKey("FADE")) fadeRgb = parseColorRgb(data.get("FADE"));
            o = data.get("FLICKER");
            if (o instanceof Boolean b) flicker = b;
            o = data.get("TRAIL");
            if (o instanceof Boolean b) trail = b;
            if (data.containsKey("TYPE")) shapeName = parseFireworkShapeName(data.get("TYPE"));
        }

        try {
            Class<?> itemsCls = Class.forName("net.minecraft.world.item.Items");
            Object fireworkItem = itemsCls.getField("FIREWORK_ROCKET").get(null);
            Class<?> itemLikeCls = Class.forName("net.minecraft.world.level.ItemLike");
            Class<?> itemStackCls = Class.forName("net.minecraft.world.item.ItemStack");
            Constructor<?> stackCtor = itemStackCls.getConstructor(itemLikeCls);
            Object stack = stackCtor.newInstance(fireworkItem);

            try {
                Class<?> shapeCls = Class.forName(
                        "net.minecraft.world.item.component.FireworkExplosion$Shape");
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object shape = Enum.valueOf((Class<Enum>) shapeCls.asSubclass(Enum.class), shapeName);
                Class<?> intListCls = Class.forName("it.unimi.dsi.fastutil.ints.IntList");
                Method intListOf = intListCls.getMethod("of", int.class);
                Object colors = intListOf.invoke(null, colorRgb);
                Object fades = intListOf.invoke(null, fadeRgb);
                Class<?> explosionCls = Class.forName(
                        "net.minecraft.world.item.component.FireworkExplosion");
                Constructor<?> expCtor = explosionCls.getConstructor(
                        shapeCls, intListCls, intListCls, boolean.class, boolean.class);
                Object explosion = expCtor.newInstance(shape, colors, fades, trail, flicker);
                Class<?> fireworksCls = Class.forName(
                        "net.minecraft.world.item.component.Fireworks");
                Constructor<?> fwCtor = fireworksCls.getConstructor(int.class, List.class);
                Object fireworks = fwCtor.newInstance(power, List.of(explosion));
                Class<?> dataComponentsCls = Class.forName(
                        "net.minecraft.core.component.DataComponents");
                Field fireworksField = dataComponentsCls.getField("FIREWORKS");
                Object fireworksType = fireworksField.get(null);
                for (Method m : itemStackCls.getMethods()) {
                    if ("set".equals(m.getName()) && m.getParameterCount() == 2) {
                        m.invoke(stack, fireworksType, fireworks);
                        break;
                    }
                }
            } catch (Throwable ignored) {
                // Component layout drifted; spawn default firework rocket stack.
            }

            Class<?> levelCls = Class.forName("net.minecraft.world.level.Level");
            Class<?> rocketCls = Class.forName(
                    "net.minecraft.world.entity.projectile.FireworkRocketEntity");
            Constructor<?> rocketCtor = rocketCls.getConstructor(
                    levelCls, double.class, double.class, double.class, itemStackCls);
            Method copyStack = itemStackCls.getMethod("copy");
            Method setDeltaMovement = rocketCls.getMethod("setDeltaMovement", double.class, double.class, double.class);
            Class<?> entityCls = Class.forName("net.minecraft.world.entity.Entity");
            Method addFreshEntity = level.getClass().getMethod("addFreshEntity", entityCls);
            Method broadcastEntityEvent = level.getClass().getMethod("broadcastEntityEvent", entityCls, byte.class);
            Method discard = rocketCls.getMethod("discard");

            boolean anyAdded = false;
            for (int i = 0; i < numFireworks; i++) {
                Object rocket = rocketCtor.newInstance(level, x, y + 1.0, z, copyStack.invoke(stack));
                if (dx != 0.0 || dy != 1.0 || dz != 0.0) {
                    setDeltaMovement.invoke(rocket, dx, dy, dz);
                }
                if (Boolean.TRUE.equals(addFreshEntity.invoke(level, rocket))) {
                    anyAdded = true;
                }
                if (power == 0) {
                    broadcastEntityEvent.invoke(level, rocket, (byte) 17);
                    discard.invoke(rocket);
                }
            }
            return anyAdded;
        } catch (Throwable t) {
            EffectFactory.reportUnsupportedPrimitive(
                    "NeoForge", "FIREWORK", t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
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

    private record LocationWrapper(
            @Nullable Object rawLocation,
            double lx,
            double ly,
            double lz,
            @NotNull String worldName) implements LocationHandle {
        @Override
        public int x() {
            return (int) Math.floor(lx);
        }

        @Override
        public int y() {
            return (int) Math.floor(ly);
        }

        @Override
        public int z() {
            return (int) Math.floor(lz);
        }

        @Override
        public double doubleX() {
            return lx;
        }

        @Override
        public double doubleY() {
            return ly;
        }

        @Override
        public double doubleZ() {
            return lz;
        }

        @Override
        public @NotNull String worldName() {
            return worldName;
        }

        @Override
        public @NotNull Object platformLocation() {
            return rawLocation != null ? rawLocation : worldName + ":" + lx + "," + ly + "," + lz;
        }

        @Override
        public void playSound(Object type, float volume, float pitch) {
            ServerLevel level = resolveLevel(worldName);
            if (!NeoForgeSoundEffect.playAtLevel(level, lx, ly, lz, type, volume, pitch)) {
                EffectFactory.reportUnsupportedPrimitive(
                        "NeoForge", "SOUND", "location sound playback unavailable for world=" + worldName);
            }
        }

        @Override
        public void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed) {
            ServerLevel level = resolveLevel(worldName);
            if (!NeoForgeParticleEffect.spawnAtLevel(
                    level, lx, ly, lz, type, count, dx, dy, dz, speed)) {
                EffectFactory.reportUnsupportedPrimitive(
                        "NeoForge", "PARTICLE", "location particle spawn unavailable for world=" + worldName);
            }
        }

        @Override
        public void spawnFirework(Map<String, Object> data) {
            ServerLevel level = resolveLevel(worldName);
            spawnFireworkAt(level, lx, ly, lz, data);
        }

        @Override
        public void playNote(Object instrument, int tone) {
            ServerLevel level = resolveLevel(worldName);
            if (level == null) {
                EffectFactory.reportUnsupportedPrimitive(
                        "NeoForge", "NOTE", "location note playback unavailable for world=" + worldName);
                return;
            }
            SoundEvent sound = resolveNoteSound(instrument);
            if (sound == null || !NeoForgeSoundEffect.playAtLevel(
                    level, lx, ly, lz, sound, 3.0f, notePitch(tone))) {
                EffectFactory.reportUnsupportedPrimitive(
                        "NeoForge", "NOTE", "location note playback unavailable for instrument=" + instrument);
            }
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
            onServer("SOUND", () -> {
                if (!NeoForgeSoundEffect.playForPlayer(player, type, volume, pitch, dx, dy, dz)) {
                    EffectFactory.reportUnsupportedPrimitive(
                            "NeoForge", "SOUND", "player sound playback unavailable for type=" + type);
                }
            });
        }

        @Override
        public void spawnParticle(Object type, int count, double dx, double dy, double dz, double speed) {
            onServer("PARTICLE", () -> {
                if (!NeoForgeParticleEffect.spawnForPlayer(player, type, count, dx, dy, dz, speed)) {
                    EffectFactory.reportUnsupportedPrimitive(
                            "NeoForge", "PARTICLE", "player particle spawn unavailable for type=" + type);
                }
            });
        }

        @Override
        public void applyPotionEffect(Object type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {
            onServer("POTION", () -> {
                if (!NeoForgePotionEffect.applyToPlayer(player, type, duration, amplifier, ambient, particles, icon)) {
                    EffectFactory.reportUnsupportedPrimitive(
                            "NeoForge", "POTION", "player potion application unavailable for type=" + type);
                }
            });
        }

        @Override
        public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
            onServer("TITLE", () -> NeoForgeTitleEffect.sendToPlayer(player, title, subtitle, fadeIn, stay, fadeOut));
        }

        @Override
        public void playNote(Object instrument, int tone) {
            onServer("NOTE", () -> {
                SoundEvent sound = resolveNoteSound(instrument);
                if (sound == null || !NeoForgeSoundEffect.playForPlayer(
                        player, sound, 3.0f, notePitch(tone), 0.0, 0.0, 0.0)) {
                    EffectFactory.reportUnsupportedPrimitive(
                            "NeoForge", "NOTE", "player note playback unavailable for instrument=" + instrument);
                }
            });
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
            onServer("FIREWORK", () -> {
                ServerLevel level = NeoForgeRTPPlayer.resolveServerLevel(player);
                spawnFireworkAt(level, player.getX(), player.getY(), player.getZ(), data);
            });
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
