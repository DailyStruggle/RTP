package io.github.dailystruggle.rtp.neoforge.effects.local;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.rtp.common.RTP;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.logging.Level;

/**
 * NeoForge POTION effect (effects-api-ADR-003), compiled Mojmap in
 * {@code rtp-neoforge-common}. Builds a {@code MobEffectInstance} and applies it
 * to the target {@link ServerPlayer} via reflection, since the
 * {@code MobEffectInstance} ctor changed shape ({@code MobEffect} ->
 * {@code Holder<MobEffect>}) in 1.20.5 and {@code addEffect} drifts across the
 * line. S-004 cosmetic; dropped with no exception on a resolution miss.
 */
public class NeoForgePotionEffect extends Effect<NeoForgePotionKeys> {

    private static final MobEffect DEFAULT_POTION =
            NeoForgeRegistryResolver.resolve(BuiltInRegistries.MOB_EFFECT, "minecraft:speed");

    public NeoForgePotionEffect() throws IllegalArgumentException {
        super(new EnumMap<>(NeoForgePotionKeys.class));
        EnumMap<NeoForgePotionKeys, Object> d = getData();
        d.put(NeoForgePotionKeys.TYPE, DEFAULT_POTION);
        d.put(NeoForgePotionKeys.DURATION, 200);
        d.put(NeoForgePotionKeys.AMPLIFIER, 0);
        d.put(NeoForgePotionKeys.AMBIENT, false);
        d.put(NeoForgePotionKeys.PARTICLES, true);
        d.put(NeoForgePotionKeys.ICON, true);
        this.data = d;
        this.defaults = d.clone();
    }

    @Override
    public void run() {
        ServerPlayer player = NeoForgeHandles.unwrapPlayer(target);
        if (player == null) return;

        Object typeObj = data.get(NeoForgePotionKeys.TYPE);
        int duration  = numAsInt(data.get(NeoForgePotionKeys.DURATION),  200);
        int amplifier = numAsInt(data.get(NeoForgePotionKeys.AMPLIFIER), 0);
        boolean ambient   = boolOrDefault(data.get(NeoForgePotionKeys.AMBIENT),   false);
        boolean particles = boolOrDefault(data.get(NeoForgePotionKeys.PARTICLES), true);
        boolean icon      = boolOrDefault(data.get(NeoForgePotionKeys.ICON),      true);

        applyToPlayer(player, typeObj, duration, amplifier, ambient, particles, icon);
    }

    static MobEffect resolveMobEffect(Object typeObj) {
        if (typeObj instanceof MobEffect me) return me;
        if (typeObj instanceof Holder<?> h && h.value() instanceof MobEffect me) return me;
        if (typeObj != null) {
            try {
                return NeoForgeRegistryResolver.resolve(BuiltInRegistries.MOB_EFFECT, typeObj.toString());
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    public static boolean applyToPlayer(ServerPlayer player, Object typeObj,
                                        int duration, int amplifier,
                                        boolean ambient, boolean particles, boolean icon) {
        if (player == null) return false;
        MobEffect potion = resolveMobEffect(typeObj);
        if (potion == null) return false;

        Object instance = buildInstance(potion, duration, amplifier, ambient, particles, icon);
        if (instance == null) {
            warnOnce("could not construct MobEffectInstance (" + INSTANCE_FAILURE + ")");
            return false;
        }
        Method add = resolveAddEffect(instance.getClass());
        if (add == null) {
            warnOnce("no LivingEntity#addEffect(" + instance.getClass().getSimpleName()
                    + ") method found (mapping drift)");
            return false;
        }
        try {
            add.invoke(player, instance);
            return true;
        } catch (Throwable t) {
            warnOnce("addEffect threw " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()));
            return false;
        }
    }

    private static volatile String INSTANCE_FAILURE;
    private static volatile boolean WARNED;

    private static void warnOnce(String reason) {
        if (WARNED) return;
        synchronized (NeoForgePotionEffect.class) {
            if (WARNED) return;
            WARNED = true;
        }
        RTP.log(Level.WARNING, "[RTP][NeoForge] POTION effect could not be delivered: "
                + reason + " (cosmetic; teleport unaffected).");
    }

    // ---- MobEffectInstance(Holder<MobEffect>|MobEffect, int, int, bool, bool, bool) ----
    private static volatile Constructor<?> INSTANCE_CTOR;
    private static volatile boolean INSTANCE_CTOR_HOLDER;
    private static volatile boolean INSTANCE_CTOR_RESOLVED;

    private static Object buildInstance(MobEffect potion, int duration, int amplifier,
                                        boolean ambient, boolean visible, boolean showIcon) {
        if (!INSTANCE_CTOR_RESOLVED) {
            synchronized (NeoForgePotionEffect.class) {
                if (!INSTANCE_CTOR_RESOLVED) {
                    try {
                        Class<?> cls = resolveInstanceClass();
                        if (cls == null) {
                            INSTANCE_FAILURE = "MobEffectInstance class not found";
                            INSTANCE_CTOR_RESOLVED = true;
                            return null;
                        }
                        Constructor<?> best = null;
                        boolean holder = false;
                        for (Constructor<?> c : cls.getConstructors()) {
                            Class<?>[] p = c.getParameterTypes();
                            if (p.length != 6) continue;
                            if (p[1] != int.class || p[2] != int.class) continue;
                            if (p[3] != boolean.class || p[4] != boolean.class || p[5] != boolean.class) continue;
                            if (Holder.class.isAssignableFrom(p[0])) { best = c; holder = true; break; }
                            if (MobEffect.class.isAssignableFrom(p[0])) { best = c; holder = false; }
                        }
                        INSTANCE_CTOR = best;
                        INSTANCE_CTOR_HOLDER = holder;
                        if (best == null) INSTANCE_FAILURE = "no 6-arg MobEffectInstance ctor matched";
                    } catch (Throwable t) {
                        INSTANCE_FAILURE = t.getClass().getSimpleName();
                    } finally {
                        INSTANCE_CTOR_RESOLVED = true;
                    }
                }
            }
        }
        Constructor<?> ctor = INSTANCE_CTOR;
        if (ctor == null) return null;
        try {
            Object first = INSTANCE_CTOR_HOLDER ? BuiltInRegistries.MOB_EFFECT.wrapAsHolder(potion) : potion;
            return ctor.newInstance(first, duration, amplifier, ambient, visible, showIcon);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Resolve the {@code MobEffectInstance} class without naming it as a
     * bytecode type, tolerant of a future rename.
     */
    private static Class<?> resolveInstanceClass() {
        for (String name : new String[]{
                "net.minecraft.world.effect.MobEffectInstance"}) {
            try {
                return Class.forName(name);
            } catch (Throwable ignored) {
                // try the next candidate
            }
        }
        return null;
    }

    // ---- LivingEntity#addEffect(MobEffectInstance) ------------------------
    private static volatile Method ADD_EFFECT;
    private static volatile boolean ADD_EFFECT_RESOLVED;

    /**
     * Resolve {@code addEffect} by matching a single-arg method that accepts
     * the concrete instance class we just built, rather than matching the
     * parameter type by its (drift-prone) name. Prefers a method named
     * {@code addEffect}; falls back to any signature-compatible 1-arg method.
     */
    private static Method resolveAddEffect(Class<?> instanceClass) {
        if (ADD_EFFECT_RESOLVED) return ADD_EFFECT;
        synchronized (NeoForgePotionEffect.class) {
            if (ADD_EFFECT_RESOLVED) return ADD_EFFECT;
            Method found = null;
            Method fallback = null;
            for (Method m : ServerPlayer.class.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1) continue;
                if (!p[0].isAssignableFrom(instanceClass)) continue;
                if ("addEffect".equals(m.getName())) { found = m; break; }
                if (fallback == null && p[0].getName().endsWith("MobEffectInstance")) fallback = m;
            }
            ADD_EFFECT = (found != null) ? found : fallback;
            ADD_EFFECT_RESOLVED = true;
            return ADD_EFFECT;
        }
    }

    @Override
    public String toPermission() {
        return data.get(NeoForgePotionKeys.TYPE) + "."
                + data.get(NeoForgePotionKeys.DURATION) + "."
                + data.get(NeoForgePotionKeys.AMPLIFIER) + "."
                + data.get(NeoForgePotionKeys.AMBIENT) + "."
                + data.get(NeoForgePotionKeys.PARTICLES) + "."
                + data.get(NeoForgePotionKeys.ICON);
    }

    @Override
    public void setData(String... data) {
        applyByType(KEY_ORDER, data);
    }

    private static final NeoForgePotionKeys[] KEY_ORDER = {
            NeoForgePotionKeys.TYPE, NeoForgePotionKeys.DURATION, NeoForgePotionKeys.AMPLIFIER,
            NeoForgePotionKeys.AMBIENT, NeoForgePotionKeys.PARTICLES, NeoForgePotionKeys.ICON
    };

    private static int numAsInt(Object o, int fallback) {
        return (o instanceof Number) ? ((Number) o).intValue() : fallback;
    }

    private static boolean boolOrDefault(Object o, boolean fallback) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof String) return Boolean.parseBoolean((String) o);
        return fallback;
    }
}
