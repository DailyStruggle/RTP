package io.github.dailystruggle.effectsapi.fabric_unobf.LocalEffects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.fabric_unobf.FabricEffectRuntimeUnobf;
import io.github.dailystruggle.effectsapi.fabric_unobf.FabricRegistryCompat;
import io.github.dailystruggle.effectsapi.fabric_unobf.LocalEffects.enums.FabricPotionKeys;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;

import java.util.EnumMap;

/**
 * Fabric counterpart of
 * {@code io.github.dailystruggle.effectsapi.bukkit.LocalEffects.PotionEffect}.
 *
 * <p>Mojmap drift across versions: {@code MobEffectInstance} took a raw
 * {@code MobEffect} as its first ctor arg through 1.20.x, then was rewritten
 * to take {@code Holder<MobEffect>} in 1.20.5. effects-api is non-Loom and
 * platform-agnostic, so it cannot pick the right ctor at compile time.
 *
 * <p>Per the project rule "no reflection in the api — if we need Mojang
 * mappings we use an interface completed by each server version adapter",
 * actual {@code MobEffectInstance} construction and application is delegated
 * to {@link FabricEffectRuntimeUnobf.PotionDispatcher}, registered by the active
 * {@code rtp-fabric-v*} adapter via {@code installEffectsDispatchers()}.
 *
 * <p>S-004: if no dispatcher has been registered for this runtime, the
 * effect is dropped with a single diagnostic line — never silently swallowed.
 */
public class FabricPotionEffect extends Effect<FabricPotionKeys> {

    private static final MobEffect DEFAULT_POTION = FabricRegistryCompat.resolve(
            BuiltInRegistries.MOB_EFFECT,
            Identifier.tryParse("minecraft:speed"));

    public FabricPotionEffect() throws IllegalArgumentException {
        super(new EnumMap<>(FabricPotionKeys.class));
        EnumMap<FabricPotionKeys, Object> d = getData();
        d.put(FabricPotionKeys.TYPE, DEFAULT_POTION);
        d.put(FabricPotionKeys.DURATION, 200); // 10s
        d.put(FabricPotionKeys.AMPLIFIER, 0);
        d.put(FabricPotionKeys.AMBIENT, false);
        d.put(FabricPotionKeys.PARTICLES, true);
        d.put(FabricPotionKeys.ICON, true);
        this.data = d;
        this.defaults = d.clone();
    }

    @Override
    public void run() {
        if (!(target instanceof ServerPlayer)) return;
        ServerPlayer player = (ServerPlayer) target;

        Object typeObj = data.get(FabricPotionKeys.TYPE);
        if (!(typeObj instanceof MobEffect)) return;
        MobEffect potion = (MobEffect) typeObj;

        int duration  = numAsInt(data.get(FabricPotionKeys.DURATION),  200);
        int amplifier = numAsInt(data.get(FabricPotionKeys.AMPLIFIER), 0);
        boolean ambient   = boolOrDefault(data.get(FabricPotionKeys.AMBIENT),   false);
        boolean particles = boolOrDefault(data.get(FabricPotionKeys.PARTICLES), true);
        boolean icon      = boolOrDefault(data.get(FabricPotionKeys.ICON),      true);

        FabricEffectRuntimeUnobf.PotionDispatcher dispatcher = FabricEffectRuntimeUnobf.getPotionDispatcher();
        if (dispatcher == null) {
            // S-004: don't silently swallow; one diagnostic line so admins can
            // see why /rtp on-teleport potion effects don't fire on an
            // unsupported MC build (rtp-fabric-v* adapter missing or older
            // than the running server).
            System.err.println("[effects-api] [FabricPotionEffect] no PotionDispatcher registered; "
                    + "dropping potion effect (rtp-fabric-v* adapter for this MC version is missing "
                    + "or did not call installEffectsDispatchers).");
            return;
        }
        dispatcher.apply(player, potion, duration, amplifier, ambient, particles, icon);
    }

    @Override
    public String toPermission() {
        return String.valueOf(data.get(FabricPotionKeys.TYPE)) + "."
                + data.get(FabricPotionKeys.DURATION) + "."
                + data.get(FabricPotionKeys.AMPLIFIER) + "."
                + data.get(FabricPotionKeys.AMBIENT) + "."
                + data.get(FabricPotionKeys.PARTICLES) + "."
                + data.get(FabricPotionKeys.ICON);
    }

    @Override
    public void setData(String... data) {
        applyByType(KEY_ORDER, data);
    }

    /**
     * Unified Bukkit/Fabric POTION token order:
     * {@code POTION.<TYPE>.<DURATION>.<AMPLIFIER>.<AMBIENT>.<PARTICLES>.<ICON>}.
     * Trailing fields are optional and fall back to ctor defaults.
     */
    private static final FabricPotionKeys[] KEY_ORDER = {
            FabricPotionKeys.TYPE,
            FabricPotionKeys.DURATION,
            FabricPotionKeys.AMPLIFIER,
            FabricPotionKeys.AMBIENT,
            FabricPotionKeys.PARTICLES,
            FabricPotionKeys.ICON
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
