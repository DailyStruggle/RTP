package io.github.dailystruggle.rtp.neoforge.effects.local;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.fabric.LocalEffects.enums.FabricSoundKeys;
import io.github.dailystruggle.rtp.neoforge.player.NeoForgeRTPPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;
import java.util.EnumMap;

/**
 * NeoForge SOUND effect (effects-api-ADR-003), compiled Mojmap in
 * {@code rtp-neoforge-common} so it carries no Fabric-intermediary or
 * {@code ResourceLocation} bytecode reference. Plays the configured
 * {@link SoundEvent} to the target {@link ServerPlayer} using only
 * signature-resolved reflective dispatch (so it binds across the whole 1.21.x
 * Mojmap line, where {@code playNotifySound}/{@code playSound}/{@code sendXxx}
 * arities and {@code Entity#level()} drift). S-004: cosmetic; never breaks
 * teleport.
 */
public class NeoForgeSoundEffect extends Effect<FabricSoundKeys> {

    private static final SoundEvent DEFAULT_SOUND =
            NeoForgeRegistryResolver.resolve(BuiltInRegistries.SOUND_EVENT, "minecraft:entity.player.levelup");

    public NeoForgeSoundEffect() throws IllegalArgumentException {
        super(new EnumMap<>(FabricSoundKeys.class));
        EnumMap<FabricSoundKeys, Object> d = getData();
        d.put(FabricSoundKeys.TYPE, DEFAULT_SOUND);
        d.put(FabricSoundKeys.VOLUME, 100);
        d.put(FabricSoundKeys.PITCH, 100);
        d.put(FabricSoundKeys.DX, 0.0);
        d.put(FabricSoundKeys.DY, 0.0);
        d.put(FabricSoundKeys.DZ, 0.0);
        this.data = d;
        this.defaults = d.clone();
    }

    @Override
    public void run() {
        if (!(target instanceof ServerPlayer)) return;
        ServerPlayer player = (ServerPlayer) target;

        Object typeObj = data.get(FabricSoundKeys.TYPE);
        if (!(typeObj instanceof SoundEvent)) return;
        SoundEvent sound = (SoundEvent) typeObj;

        float volume = numAsFloat(data.get(FabricSoundKeys.VOLUME), 100) / 100f;
        float pitch  = numAsFloat(data.get(FabricSoundKeys.PITCH), 100) / 100f;
        double dx = numAsDouble(data.get(FabricSoundKeys.DX), 0.0);
        double dy = numAsDouble(data.get(FabricSoundKeys.DY), 0.0);
        double dz = numAsDouble(data.get(FabricSoundKeys.DZ), 0.0);

        // Preferred: direct-to-connection so the packet bypasses the chunk
        // tracker (broadcast packets are dropped right after a long teleport).
        if (invokePlayNotifySound(player, sound, SoundSource.MASTER, volume, pitch)) return;
        if (sendSoundPacket(player, sound, SoundSource.MASTER,
                player.getX() + dx, player.getY() + dy, player.getZ() + dz, volume, pitch)) return;
        // Last resort: level broadcast. Resolve the level via the
        // mapping-agnostic NeoForge resolver (Entity#level() drifts on 1.21.11).
        ServerLevel level = NeoForgeRTPPlayer.resolveServerLevel(player);
        if (level == null) return;
        invokePlaySound(level, player.getX() + dx, player.getY() + dy, player.getZ() + dz,
                sound, SoundSource.MASTER, volume, pitch);
    }

    // ---- direct-to-connection ClientboundSoundPacket fallback -------------
    private static volatile java.lang.reflect.Constructor<?> SOUND_PACKET_CTOR;
    private static volatile boolean SOUND_PACKET_HOLDER;
    private static volatile boolean SOUND_PACKET_HAS_SEED;
    private static volatile boolean SOUND_PACKET_RESOLVED;
    private static volatile java.lang.reflect.Method CONNECTION_SEND;
    private static volatile java.lang.reflect.Field CONNECTION_FIELD;

    private static boolean sendSoundPacket(ServerPlayer player, SoundEvent sound, SoundSource source,
                                           double x, double y, double z, float volume, float pitch) {
        if (!SOUND_PACKET_RESOLVED) {
            synchronized (NeoForgeSoundEffect.class) {
                if (!SOUND_PACKET_RESOLVED) {
                    try {
                        Class<?> packetCls = Class.forName(
                                "net.minecraft.network.protocol.game.ClientboundSoundPacket");
                        java.lang.reflect.Constructor<?> best = null;
                        boolean bestHolder = false;
                        boolean bestSeed = false;
                        int bestLen = 0;
                        for (java.lang.reflect.Constructor<?> c : packetCls.getConstructors()) {
                            Class<?>[] p = c.getParameterTypes();
                            if (p.length != 7 && p.length != 8) continue;
                            boolean holder;
                            if (SoundEvent.class.isAssignableFrom(p[0])) holder = false;
                            else if (Holder.class.isAssignableFrom(p[0])) holder = true;
                            else continue;
                            if (!SoundSource.class.isAssignableFrom(p[1])) continue;
                            if (p[2] != double.class || p[3] != double.class || p[4] != double.class) continue;
                            if (p[5] != float.class || p[6] != float.class) continue;
                            if (p.length == 8 && p[7] != long.class) continue;
                            if (best == null || p.length > bestLen) {
                                best = c; bestLen = p.length; bestHolder = holder; bestSeed = (p.length == 8);
                            }
                        }
                        SOUND_PACKET_CTOR = best;
                        SOUND_PACKET_HOLDER = bestHolder;
                        SOUND_PACKET_HAS_SEED = bestSeed;
                        CONNECTION_FIELD = resolveConnectionField();
                        if (CONNECTION_FIELD != null) {
                            for (Method mm : CONNECTION_FIELD.getType().getMethods()) {
                                if (mm.getParameterCount() != 1) continue;
                                Class<?> pt = mm.getParameterTypes()[0];
                                if (pt.getName().endsWith("Packet") && "send".equals(mm.getName())) {
                                    CONNECTION_SEND = mm; break;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // packet fallback unavailable; resolved flag still set below
                    } finally {
                        SOUND_PACKET_RESOLVED = true;
                    }
                }
            }
        }
        java.lang.reflect.Constructor<?> ctor = SOUND_PACKET_CTOR;
        if (ctor == null || CONNECTION_FIELD == null || CONNECTION_SEND == null) return false;
        try {
            Object soundArg = SOUND_PACKET_HOLDER ? BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound) : sound;
            Object packet = SOUND_PACKET_HAS_SEED
                    ? ctor.newInstance(soundArg, source, x, y, z, volume, pitch,
                            ((ServerLevel) NeoForgeRTPPlayer.resolveServerLevel(player)).getRandom().nextLong())
                    : ctor.newInstance(soundArg, source, x, y, z, volume, pitch);
            Object conn = CONNECTION_FIELD.get(player);
            if (conn == null) return false;
            CONNECTION_SEND.invoke(conn, packet);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    static java.lang.reflect.Field resolveConnectionField() {
        for (java.lang.reflect.Field f : ServerPlayer.class.getFields()) {
            if (f.getType().getName().endsWith("ServerGamePacketListenerImpl")) return f;
        }
        for (Class<?> cls = ServerPlayer.class; cls != null; cls = cls.getSuperclass()) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (f.getType().getName().endsWith("ServerGamePacketListenerImpl")) {
                    try { f.setAccessible(true); } catch (Throwable ignored) {}
                    return f;
                }
            }
        }
        return null;
    }

    // ---- ServerPlayer#playNotifySound (SoundEvent|Holder,SoundSource,f,f) --
    private static volatile Method NOTIFY_SOUND;
    private static volatile boolean NOTIFY_SOUND_HOLDER;
    private static volatile boolean NOTIFY_SOUND_RESOLVED;

    private static boolean invokePlayNotifySound(ServerPlayer player, SoundEvent sound,
                                                 SoundSource source, float volume, float pitch) {
        if (!NOTIFY_SOUND_RESOLVED) {
            synchronized (NeoForgeSoundEffect.class) {
                if (!NOTIFY_SOUND_RESOLVED) {
                    Method found = null;
                    boolean foundHolder = false;
                    java.util.LinkedHashSet<Method> all = new java.util.LinkedHashSet<>();
                    for (Method mm : ServerPlayer.class.getMethods()) all.add(mm);
                    for (Class<?> c = ServerPlayer.class; c != null && c != Object.class; c = c.getSuperclass()) {
                        for (Method mm : c.getDeclaredMethods()) {
                            int mod = mm.getModifiers();
                            if (java.lang.reflect.Modifier.isStatic(mod)
                                    || java.lang.reflect.Modifier.isPrivate(mod)) continue;
                            all.add(mm);
                        }
                    }
                    for (Method cand : all) {
                        Class<?>[] p = cand.getParameterTypes();
                        if (p.length != 4 || cand.getReturnType() != void.class) continue;
                        if (!SoundSource.class.isAssignableFrom(p[1])) continue;
                        if (p[2] != float.class || p[3] != float.class) continue;
                        boolean holder;
                        if (SoundEvent.class.isAssignableFrom(p[0])) holder = false;
                        else if (Holder.class.isAssignableFrom(p[0])) holder = true;
                        else continue;
                        if (found == null || (foundHolder && !holder)) {
                            try { cand.setAccessible(true); } catch (Throwable ignored) {}
                            found = cand; foundHolder = holder;
                            if (!holder) break;
                        }
                    }
                    NOTIFY_SOUND = found;
                    NOTIFY_SOUND_HOLDER = foundHolder;
                    NOTIFY_SOUND_RESOLVED = true;
                }
            }
        }
        Method m = NOTIFY_SOUND;
        if (m == null) return false;
        try {
            Object soundArg = NOTIFY_SOUND_HOLDER ? BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound) : sound;
            m.invoke(player, soundArg, source, volume, pitch);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // ---- ServerLevel#playSound (Entity,d,d,d,SoundEvent|Holder,SoundSource,f,f[,long]) ----
    private static volatile Method PLAY_SOUND;
    private static volatile boolean PLAY_SOUND_HAS_SEED;
    private static volatile boolean PLAY_SOUND_HOLDER;
    private static volatile boolean PLAY_SOUND_RESOLVED;

    private static void resolvePlaySound() {
        if (PLAY_SOUND_RESOLVED) return;
        synchronized (NeoForgeSoundEffect.class) {
            if (PLAY_SOUND_RESOLVED) return;
            Method best = null;
            int bestLen = 0;
            boolean bestHolder = false;
            for (Method m : ServerLevel.class.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 8 && p.length != 9) continue;
                if (!Entity.class.isAssignableFrom(p[0])) continue;
                if (p[1] != double.class || p[2] != double.class || p[3] != double.class) continue;
                boolean holder;
                if (SoundEvent.class.isAssignableFrom(p[4])) holder = false;
                else if (Holder.class.isAssignableFrom(p[4])) holder = true;
                else continue;
                if (!SoundSource.class.isAssignableFrom(p[5])) continue;
                if (p[6] != float.class || p[7] != float.class) continue;
                if (p.length == 9 && p[8] != long.class) continue;
                if (m.getReturnType() != void.class) continue;
                if (best == null || p.length > bestLen) {
                    best = m; bestLen = p.length; bestHolder = holder;
                }
            }
            PLAY_SOUND = best;
            PLAY_SOUND_HAS_SEED = (bestLen == 9);
            PLAY_SOUND_HOLDER = bestHolder;
            PLAY_SOUND_RESOLVED = true;
        }
    }

    private static void invokePlaySound(ServerLevel level, double x, double y, double z,
                                        SoundEvent sound, SoundSource source, float volume, float pitch) {
        resolvePlaySound();
        Method m = PLAY_SOUND;
        if (m == null) return;
        Object soundArg = PLAY_SOUND_HOLDER ? Holder.direct(sound) : sound;
        try {
            if (PLAY_SOUND_HAS_SEED) {
                m.invoke(level, null, x, y, z, soundArg, source, volume, pitch, level.getRandom().nextLong());
            } else {
                m.invoke(level, null, x, y, z, soundArg, source, volume, pitch);
            }
        } catch (Throwable ignored) {
            // cosmetic; never break teleport
        }
    }

    @Override
    public String toPermission() {
        return String.valueOf(data.get(FabricSoundKeys.TYPE)) + "."
                + data.get(FabricSoundKeys.VOLUME) + "."
                + data.get(FabricSoundKeys.PITCH) + "."
                + data.get(FabricSoundKeys.DX) + "."
                + data.get(FabricSoundKeys.DY) + "."
                + data.get(FabricSoundKeys.DZ);
    }

    @Override
    public void setData(String... data) {
        applyByType(KEY_ORDER, data);
    }

    private static final FabricSoundKeys[] KEY_ORDER = {
            FabricSoundKeys.TYPE, FabricSoundKeys.VOLUME, FabricSoundKeys.PITCH,
            FabricSoundKeys.DX, FabricSoundKeys.DY, FabricSoundKeys.DZ
    };

    private static float numAsFloat(Object o, float fallback) {
        return (o instanceof Number) ? ((Number) o).floatValue() : fallback;
    }
    private static double numAsDouble(Object o, double fallback) {
        return (o instanceof Number) ? ((Number) o).doubleValue() : fallback;
    }
}
